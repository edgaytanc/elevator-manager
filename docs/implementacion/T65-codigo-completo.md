# T65 — Código completo

Fecha: 2026-11-02, Guatemala. Los bloques siguientes reproducen todos los archivos Java nuevos/modificados por T65, íntegros y sin omitir métodos. El ZIP contiene además todos los .java del proyecto anterior.

## src/main/java/com/elevatormanager/diagnostics/ProtocolIntegrationDemo.java

```java
package com.elevatormanager.diagnostics;

import com.elevatormanager.model.ElevatorPhase;
import com.elevatormanager.model.SimulationConfig;
import com.elevatormanager.model.SimulationState;
import com.elevatormanager.model.request.CabinRequest;
import com.elevatormanager.protocol.ProtocolCodec;
import com.elevatormanager.protocol.ProtocolMessage;
import com.elevatormanager.simulation.ProtocolMailbox;
import com.elevatormanager.simulation.SimulationController;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** T65: STATUS en pausa y reset confirmado por respuestas de todas las cabinas. */
public final class ProtocolIntegrationDemo {
    private ProtocolIntegrationDemo() { }
    public static void main(String[] args) throws Exception {
        var config = new SimulationConfig(2, 6, 100, 100);
        var codec = new ProtocolCodec(config);
        var controller = new SimulationController(config, 0);
        var mailbox = new ProtocolMailbox(); controller.subscribeProtocol(mailbox);
        try {
            controller.start().get(6, TimeUnit.SECONDS);
            controller.submit(new CabinRequest(UUID.fromString("12345678-1234-1234-1234-123456789abc"),
                    0, 6, Instant.EPOCH, 1)).get(6, TimeUnit.SECONDS);
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(6);
            while (controller.snapshot(1).currentFloor() < 3) {
                if (System.nanoTime() > end) { throw new IllegalStateException("No inició recorrido."); }
                TimeUnit.MILLISECONDS.sleep(2);
            }
            controller.pause().get(6, TimeUnit.SECONDS);
            long frozen = controller.timeView().activeNanos();
            ProtocolMessage.State status = controller.queryStatus(1).get(6, TimeUnit.SECONDS);
            if (status.phase() != ElevatorPhase.HELD || controller.timeView().activeNanos() != frozen) {
                throw new IllegalStateException("STATUS modificó la pausa.");
            }
            System.out.println("T65_STATUS cmd=" + status.header().id() + "; cabin=1; epoch=0; phase="
                    + status.phase() + "; remaining_ms=" + status.remainingMillis() + "; clockFrozen=true");
            controller.reset().get(6, TimeUnit.SECONDS);
            for (int id = 1; id <= config.elevatorCount(); id++) {
                var proof = controller.queryStatus(id).get(6, TimeUnit.SECONDS);
                if (proof.header().epoch() != 1 || proof.floor() != 1 || !proof.internal().isEmpty()) {
                    throw new IllegalStateException("Estado posterior al reset inválido.");
                }
            }
            controller.closeAsync().get(6, TimeUnit.SECONDS);
            if (!controller.awaitTermination(Duration.ofSeconds(6)) || controller.state() != SimulationState.CLOSED) {
                throw new IllegalStateException("Cierre sin join completo.");
            }
            while (true) {
                var text = mailbox.await(Duration.ZERO);
                if (text.isEmpty()) { break; }
                var message = codec.decodeResponse(text.orElseThrow()).message().orElseThrow();
                if (message instanceof ProtocolMessage.Ack || message instanceof ProtocolMessage.Terminal
                        || message instanceof ProtocolMessage.State state && state.header().id() > 0) {
                    System.out.println("T65_WIRE " + text.orElseThrow());
                }
            }
            System.out.println("T65_INTEGRATION_OK; epoch=1; state=CLOSED; responsesJoined=true");
        } finally { controller.closeAsync().get(6, TimeUnit.SECONDS); }
    }
}
```

## src/main/java/com/elevatormanager/diagnostics/TextTransportDemo.java

```java
package com.elevatormanager.diagnostics;

import com.elevatormanager.dispatch.HallCallSnapshot;
import com.elevatormanager.dispatch.ManagerWorker;
import com.elevatormanager.model.Direction;
import com.elevatormanager.model.SimulationConfig;
import com.elevatormanager.model.request.HallRequest;
import com.elevatormanager.protocol.ProtocolCodec;
import com.elevatormanager.protocol.ProtocolCommands;
import com.elevatormanager.protocol.ProtocolMessage;
import com.elevatormanager.simulation.ElevatorWorker;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** T64: manejador/cabinas reales, mensajes de salida producidos por el receptor. */
public final class TextTransportDemo {
    private static final Duration LIMIT = Duration.ofSeconds(6);
    private TextTransportDemo() { }

    public static void main(String[] args) throws Exception {
        var config = new SimulationConfig(2, 5, 50, 100);
        var codec = new ProtocolCodec(config);
        var first = new ElevatorWorker(1, config, 0);
        var second = new ElevatorWorker(2, config, 0);
        var sequence = new AtomicLong(40);
        var manager = new ManagerWorker(config, 0, List.of(first, second), sequence::incrementAndGet);
        try {
            first.start(); second.start(); manager.start();
            var request = new HallRequest(UUID.fromString("12345678-1234-1234-1234-123456789abc"),
                    0, 3, Instant.EPOCH, Direction.UP);
            manager.submit(request);
            var sent = manager.awaitResult(LIMIT).orElseThrow().command().orElseThrow();
            var receiver = sent.elevatorId() == 1 ? first : second;
            System.out.println("T64_ORDER " + codec.encode(ProtocolCommands.fromDomain(sent)));
            var admitted = receiver.awaitResult(LIMIT).orElseThrow();
            if (admitted.command() == sent || !sent.sameContent(admitted.command())) {
                throw new IllegalStateException("No se reconstruyó el contenido desde texto.");
            }
            System.out.println("T64_RX thread=" + receiver.protocolReception().processedBy()
                    + "; reconstructed=true; sameContent=true");
            awaitTerminal(receiver, codec, sent.commandId());
            long end = System.nanoTime() + LIMIT.toNanos();
            while (manager.call(request.getId()).orElseThrow().status() != HallCallSnapshot.Status.SERVED) {
                if (System.nanoTime() > end) { throw new IllegalStateException("Servicio sin confirmar."); }
                TimeUnit.MILLISECONDS.sleep(2);
            }
            receiver.submitText("V1 CMD 42 EPOCH 0 ELEVATOR " + sent.elevatorId() + " FLY");
            ProtocolMessage message;
            do {
                String rejected = receiver.awaitProtocolMessage(LIMIT).orElseThrow();
                System.out.println("T64_WIRE " + rejected);
                message = codec.decodeResponse(rejected).message().orElseThrow();
            } while (!(message instanceof ProtocolMessage.Error));
            if (!(message instanceof ProtocolMessage.Error error)
                    || error.code() != ProtocolMessage.ErrorCode.UNKNOWN_COMMAND || receiver.failure().isPresent()) {
                throw new IllegalStateException("El receptor no recuperó el mensaje inválido.");
            }
            System.out.println("T64_TRANSPORT_OK; managerStatus=SERVED; workerAlive=true");
        } finally {
            manager.close(); first.close(); second.close();
            if (!manager.awaitTermination(LIMIT) || !first.awaitTermination(LIMIT) || !second.awaitTermination(LIMIT)) {
                throw new IllegalStateException("Hilos sin terminar.");
            }
        }
    }

    private static void awaitTerminal(ElevatorWorker receiver, ProtocolCodec codec, long commandId) throws Exception {
        long end = System.nanoTime() + LIMIT.toNanos();
        while (System.nanoTime() < end) {
            String text = receiver.awaitProtocolMessage(LIMIT).orElseThrow();
            System.out.println("T64_WIRE " + text);
            var result = codec.decodeResponse(text).message().orElseThrow();
            if (result instanceof ProtocolMessage.Terminal terminal && terminal.header().id() == commandId
                    && terminal.kind() == ProtocolMessage.TerminalKind.COMPLETED) { return; }
        }
        throw new IllegalStateException("No se recibió finalización textual.");
    }
}
```

## src/main/java/com/elevatormanager/dispatch/HallRequestLedger.java

```java
package com.elevatormanager.dispatch;

import com.elevatormanager.model.ElevatorPhase;
import com.elevatormanager.model.SimulationConfig;
import com.elevatormanager.model.request.HallRequest;
import com.elevatormanager.protocol.Command;
import com.elevatormanager.protocol.ProtocolMessage;
import com.elevatormanager.simulation.ExecutionEvent;
import com.elevatormanager.simulation.PlanningSnapshot;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import static com.elevatormanager.dispatch.HallCallSnapshot.Status.*;

/** T32: registro global privado del actor. No comparte estado mutable con productores. */
public final class HallRequestLedger {
    public enum AdmissionKind { NEW, DUPLICATE, REJECTED }
    public record Admission(AdmissionKind kind, Optional<UUID> canonicalId, String detail) { }
    private record Key(int floor, com.elevatormanager.model.Direction direction) { }
    private record AttemptKey(int elevatorId, long commandId) { }
    private record Identity(HallRequest original, Entry entry) { }
    private static final class Entry {
        private final HallRequest request;
        private HallCallSnapshot.Status status = RECEIVED;
        private Command.Pickup command;
        private UUID receiverCanonical;
        private int attempts;
        private String detail = "Recibida; pendiente de despacho.";
        private final Set<Integer> rejectedBy = new HashSet<>();
        private Entry(HallRequest request) { this.request = request; }
    }

    private final SimulationConfig config;
    private final long epoch;
    private final Map<UUID, Identity> identities = new LinkedHashMap<>();
    private final Map<UUID, Entry> canonical = new LinkedHashMap<>();
    private final Map<Key, Entry> active = new HashMap<>();
    private final Map<AttemptKey, Entry> attempts = new HashMap<>();
    private final Map<Integer, Long> lastSequence = new HashMap<>();

    public HallRequestLedger(SimulationConfig config, long epoch) {
        this.config = Objects.requireNonNull(config, "config");
        if (epoch < 0) { throw new IllegalArgumentException("Época negativa."); }
        this.epoch = epoch;
    }

    public Admission receive(HallRequest request) {
        Objects.requireNonNull(request, "request");
        if (request.getEpoch() != epoch || !request.validateAgainst(config).isValid()) {
            return new Admission(AdmissionKind.REJECTED, Optional.empty(), "Solicitud o época inválida.");
        }
        var previous = identities.get(request.getId());
        if (previous != null) {
            if (!PlanningSnapshot.sameRequest(previous.original(), request)) {
                return new Admission(AdmissionKind.REJECTED, Optional.empty(), "UUID reutilizado con otro contenido.");
            }
            return new Admission(AdmissionKind.DUPLICATE, Optional.of(previous.entry().request.getId()),
                    "Identidad ya registrada; se conserva su estado histórico.");
        }
        var key = key(request);
        var entry = active.get(key);
        var kind = entry == null ? AdmissionKind.NEW : AdmissionKind.DUPLICATE;
        if (entry == null) {
            entry = new Entry(request);
            canonical.put(request.getId(), entry);
            active.put(key, entry);
        }
        identities.put(request.getId(), new Identity(request, entry));
        return new Admission(kind, Optional.of(entry.request.getId()),
                kind == AdmissionKind.NEW ? "Nueva llamada canónica." : "Alias de una llamada activa del mismo piso y sentido.");
    }

    public List<HallRequest> pending() {
        return canonical.values().stream().filter(e -> e.status == RECEIVED).map(e -> e.request).toList();
    }

    public Set<Integer> rejectedCabins(UUID id) { return Set.copyOf(entry(id).rejectedBy); }

    /** Reserva provisional de carga hasta que la agenda confirme la admisión. */
    public Set<Integer> awaitingAdmissionCabins() {
        var ids = new HashSet<Integer>();
        for (var e : canonical.values()) {
            if (e.status == DISPATCHED) { ids.add(e.command.elevatorId()); }
        }
        return Set.copyOf(ids);
    }

    public void pendingReason(UUID id, String detail) {
        var e = entry(id);
        if (e.status == RECEIVED) { e.detail = detail; }
    }

    public void dispatched(UUID id, Command.Pickup command) {
        var e = entry(id);
        if (e.status != RECEIVED || !PlanningSnapshot.sameRequest(e.request, command.request())
                || command.epoch() != epoch || command.elevatorId() > config.elevatorCount()) {
            throw new IllegalStateException("Despacho incompatible con la llamada pendiente.");
        }
        var key = new AttemptKey(command.elevatorId(), command.commandId());
        if (attempts.containsKey(key)) { throw new IllegalStateException("ID de comando reutilizado."); }
        attempts.put(key, e);
        e.command = command;
        e.receiverCanonical = null;
        e.attempts++;
        e.status = DISPATCHED;
        e.detail = "Encolada; falta confirmación de admisión.";
    }

    /** Consume el flujo ordenado de la suscripción, nunca interpreta una llegada como servicio. */
    public void onEvent(ExecutionEvent event) {
        Objects.requireNonNull(event, "event");
        if (event.epoch() != epoch || !event.snapshot().config().equals(config)
                || event.sequence() <= lastSequence.getOrDefault(event.elevatorId(), 0L)) { return; }
        lastSequence.put(event.elevatorId(), event.sequence());
        if (event.type() == ExecutionEvent.Type.WORKER_TERMINATED
                || event.type() == ExecutionEvent.Type.WORKER_FAILED) {
            for (var e : canonical.values()) {
                if ((e.status == DISPATCHED || e.status == ASSIGNED)
                        && e.command.elevatorId() == event.elevatorId()) {
                    if (event.type() == ExecutionEvent.Type.WORKER_FAILED) {
                        e.status = UNCONFIRMED;
                        e.detail = "Cabina fallida: servicio no confirmado; no reasignar automáticamente.";
                    } else {
                        retry(e, "Cabina terminada sin servicio confirmado.");
                    }
                }
            }
            return;
        }
        if (!(event.command().orElse(null) instanceof Command.Pickup command)) { return; }
        var e = attempts.get(new AttemptKey(event.elevatorId(), command.commandId()));
        if (e == null || (e.status != DISPATCHED && e.status != ASSIGNED)
                || !command.sameContent(e.command) || command.epoch() != epoch
                || command.elevatorId() != event.elevatorId()) { return; }
        switch (event.type()) {
            case COMMAND_ACCEPTED, COMMAND_DUPLICATE, COMMAND_REPLAYED -> {
                if (event.canonicalRequestId().isEmpty()) {
                    if (event.type() == ExecutionEvent.Type.COMMAND_REPLAYED) {
                        e.rejectedBy.add(command.elevatorId()); retry(e, "Retransmisión de una decisión rechazada.");
                    }
                } else {
                    e.receiverCanonical = event.canonicalRequestId().orElseThrow();
                    e.status = ASSIGNED;
                    e.detail = "Admisión confirmada; falta servicio compatible.";
                }
            }
            case COMMAND_REJECTED -> {
                e.rejectedBy.add(command.elevatorId());
                retry(e, "Cabina rechazó el comando: " + event.detail());
            }
            case COMMAND_COMPLETED -> {
                if (e.status != ASSIGNED || e.receiverCanonical == null
                        || !event.canonicalRequestId().equals(Optional.of(e.receiverCanonical))) { return; }
                var s = event.snapshot();
                if (s.phase() != ElevatorPhase.DWELLING || s.currentFloor() != e.request.getFloor()
                        || s.direction() != e.request.getDirection()) {
                    e.status = UNCONFIRMED;
                    e.detail = "Confirmación sin evidencia de parada compatible; requiere revisión.";
                } else {
                    e.status = SERVED;
                    e.detail = "Servicio confirmado por COMMAND_COMPLETED en parada compatible.";
                    active.remove(key(e.request), e);
                }
            }
            default -> { }
        }
    }

    private record WireStop(int floor, com.elevatormanager.model.Direction direction) { }
    private final Map<Integer, WireStop> wireStops = new HashMap<>();
    private final Map<Integer, Long> wireSequence = new HashMap<>();

    /** Seguimiento de producción T65: solo datos decodificados del canal V1. */
    public void onProtocol(ProtocolMessage message) {
        var h = message.header();
        if (h.epoch() != epoch) { return; }
        if (message instanceof ProtocolMessage.Move || message instanceof ProtocolMessage.Arrived
                || message instanceof ProtocolMessage.StoppedAt || message instanceof ProtocolMessage.DirectionChanged) {
            if (h.id() <= wireSequence.getOrDefault(h.elevatorId(), 0L)) { return; }
            wireSequence.put(h.elevatorId(), h.id());
            if (message instanceof ProtocolMessage.StoppedAt stop) {
                wireStops.put(h.elevatorId(), new WireStop(stop.floor(), stop.service()));
            } else { wireStops.remove(h.elevatorId()); }
            return;
        }
        var e = attempts.get(new AttemptKey(h.elevatorId(), h.id()));
        if (e == null || (e.status != DISPATCHED && e.status != ASSIGNED)
                || e.command.epoch() != h.epoch() || e.command.commandId() != h.id()
                || e.command.elevatorId() != h.elevatorId()) { return; }
        if (message instanceof ProtocolMessage.Ack ack) {
            e.receiverCanonical = ack.canonicalRequestId().orElse(e.command.request().getId());
            e.status = ASSIGNED; e.detail = "Admisión confirmada; falta servicio compatible.";
        } else if (message instanceof ProtocolMessage.Error error) {
            e.rejectedBy.add(h.elevatorId()); retry(e, "ERROR textual: " + error.code());
        } else if (message instanceof ProtocolMessage.Terminal terminal) {
            switch (terminal.kind()) {
                case COMPLETED -> {
                    WireStop stop = wireStops.get(h.elevatorId());
                    if (e.status != ASSIGNED || stop == null || stop.floor() != e.request.getFloor()
                            || stop.direction() != e.request.getDirection()) { return; }
                    e.status = SERVED; e.detail = "Servicio confirmado en parada compatible.";
                    active.remove(key(e.request), e);
                }
                case CANCELLED -> {
                    if (terminal.reason().orElseThrow() == ProtocolMessage.CancelReason.SHUTDOWN) {
                        retry(e, "Cabina cerrada; cancelación textual confirmada.");
                    } else {
                        e.status = CANCELLED; e.detail = "Cancelación textual: " + terminal.reason().orElseThrow();
                        active.remove(key(e.request), e);
                    }
                }
                case FAILED -> { e.status = UNCONFIRMED; e.detail = "FAILED textual; requiere revisión, sin reasignar."; }
            }
        }
    }

    /** Invalida seguimiento abierto por reset; no deshace servicios ya confirmados. */
    public void cancelForReset() {
        for (var entry : canonical.values()) {
            if (entry.status != SERVED && entry.status != CANCELLED) {
                entry.status = CANCELLED;
                entry.detail = "Seguimiento invalidado por reset; conservar hechos históricos por época.";
            }
        }
        active.clear();
    }

    /** Un cierre del manejador no cancela físicamente comandos ya enviados a una cabina. */
    public void close() {
        for (var e : canonical.values()) {
            if (e.status == RECEIVED) {
                e.status = CANCELLED;
                e.detail = "Manejador cerrado antes de enviar la llamada.";
                active.remove(key(e.request), e);
            } else if (e.status == DISPATCHED || e.status == ASSIGNED) {
                e.status = UNCONFIRMED;
                e.detail = "Manejador cerrado; la cabina puede continuar. No se confirma ni cancela su servicio.";
            }
        }
    }

    public Optional<HallCallSnapshot> get(UUID id) {
        var identity = identities.get(id);
        return identity == null ? Optional.empty() : Optional.of(snapshot(identity));
    }

    public Map<UUID, HallCallSnapshot> snapshot() {
        var result = new LinkedHashMap<UUID, HallCallSnapshot>();
        identities.forEach((id, identity) -> result.put(id, snapshot(identity)));
        return Map.copyOf(result);
    }

    private static HallCallSnapshot snapshot(Identity identity) {
        var e = identity.entry();
        return new HallCallSnapshot(identity.original(), e.request.getId(), e.status,
                Optional.ofNullable(e.command), e.attempts, e.detail);
    }

    private Entry entry(UUID id) {
        var identity = identities.get(id);
        if (identity == null) { throw new IllegalArgumentException("Solicitud desconocida."); }
        return identity.entry();
    }

    private static void retry(Entry e, String detail) {
        e.status = RECEIVED;
        e.command = null;
        e.receiverCanonical = null;
        e.detail = detail;
    }

    private static Key key(HallRequest request) { return new Key(request.getFloor(), request.getDirection()); }
}
```

## src/main/java/com/elevatormanager/dispatch/ManagerWorker.java

```java
package com.elevatormanager.dispatch;

import com.elevatormanager.logging.TraceMailbox;
import com.elevatormanager.simulation.time.PauseCoordinator;
import com.elevatormanager.model.SimulationConfig;
import com.elevatormanager.model.request.HallRequest;
import com.elevatormanager.protocol.Command;
import com.elevatormanager.protocol.ProtocolCodec;
import com.elevatormanager.protocol.ProtocolCommands;
import com.elevatormanager.simulation.ElevatorWorker;
import com.elevatormanager.simulation.ProtocolMailbox;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;
import javax.swing.SwingUtilities;

/**
 * T38: actor de llamadas externas con invalidación de época y barreras de control.
 * Consume una suscripción propia; mantiene intactos los canales anteriores.
 */
public final class ManagerWorker implements AutoCloseable {
    public enum Lifecycle { NEW, RUNNING, CLOSING, TERMINATED, FAILED }
    public enum Outcome { DISPATCHED, PENDING, DUPLICATE, REJECTED, CANCELLED }

    /** DISPATCHED confirma envío al buzón, nunca admisión ni servicio. */
    public record Result(HallRequest request, Outcome outcome,
            Optional<Command.Pickup> command, String detail, String processedBy) { }

    private final TraceMailbox trace;
    private final PauseCoordinator pause; // null: actores independientes de la sesión.
    private final SimulationConfig config;
    private final ProtocolCodec protocol;
    private volatile long epoch;
    private final List<ElevatorWorker> elevators;
    private final LongSupplier commandIds;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition controlChanged = lock.newCondition();
    private boolean stopRequested;
    private boolean stopped;
    private CompletableFuture<Void> stopConfirmation;
    private record ResetTicket(long epoch, CompletableFuture<Long> response) { }
    private record ResetOperation(long epoch, CompletableFuture<Long> completion) { }
    private final ArrayDeque<ResetTicket> resetInbox = new ArrayDeque<>();
    private ResetOperation lastReset;
    private boolean resetHeld;
    private final ProtocolMailbox mailbox = new ProtocolMailbox();
    private HallRequestLedger ledger;
    private final BlockingQueue<HallCallSnapshot> updates = new LinkedBlockingQueue<>();
    private final AtomicReference<Map<UUID, HallCallSnapshot>> published = new AtomicReference<>(Map.of());
    private static final long RETRY_NANOS = TimeUnit.MILLISECONDS.toNanos(100);
    private int retryCursor;
    private final ArrayDeque<HallRequest> inbox = new ArrayDeque<>();
    private final BlockingQueue<Result> results = new LinkedBlockingQueue<>();
    private final Thread thread;
    private volatile Lifecycle lifecycle = Lifecycle.NEW;
    private volatile Throwable failure;
    private static final int MAX_SELECTION_ATTEMPTS = 3;
    private final ElevatorSelector selector = new ElevatorSelector();

    /**
     * Cabinas prestadas: este actor no las arranca ni las cierra.
     * commandIds debe ser rápido, seguro entre hilos y producir IDs positivos
     * únicos, compartidos con TODO productor de comandos de estas cabinas.
     * No reutilizar un generador nuevo sobre cabinas con historial previo.
     */
    public ManagerWorker(SimulationConfig config, long epoch,
            List<ElevatorWorker> elevators, LongSupplier commandIds) {
        this(config, epoch, elevators, commandIds, TraceMailbox.disabled());
    }

    public ManagerWorker(SimulationConfig config, long epoch,
            List<ElevatorWorker> elevators, LongSupplier commandIds, TraceMailbox trace) {
        this(config, epoch, elevators, commandIds, trace, null);
    }

    /** Dominio temporal compartido con las cabinas de esta sesión. */
    public ManagerWorker(SimulationConfig config, long epoch,
            List<ElevatorWorker> elevators, LongSupplier commandIds, TraceMailbox trace,
            PauseCoordinator pause) {
        this.pause = pause;
        this.trace = Objects.requireNonNull(trace, "trace");
        this.config = Objects.requireNonNull(config, "config");
        protocol = new ProtocolCodec(config);
        if (epoch < 0) { throw new IllegalArgumentException("Época negativa."); }
        this.epoch = epoch;
        ledger = new HallRequestLedger(config, epoch);
        this.commandIds = Objects.requireNonNull(commandIds, "commandIds");
        this.elevators = List.copyOf(elevators).stream()
                .sorted(Comparator.comparingInt(e -> e.snapshot().id())).toList();
        if (this.elevators.size() != config.elevatorCount()) {
            throw new IllegalArgumentException("Se requiere una cabina por ID configurado.");
        }
        for (int i = 0; i < this.elevators.size(); i++) {
            var snapshot = this.elevators.get(i).snapshot();
            if (snapshot.id() != i + 1 || !snapshot.config().equals(config)
                    || snapshot.epoch() != epoch) {
                throw new IllegalArgumentException("IDs, configuración o época incompatibles.");
            }
        }
        thread = new Thread(this::runLoop, "manager-worker-" + epoch);
    }

    public boolean start() {
        lock.lock();
        try {
            if (lifecycle != Lifecycle.NEW) { return false; }
            lifecycle = Lifecycle.RUNNING;
            try {
                for (var elevator : elevators) { elevator.subscribeProtocol(mailbox); }
                thread.start();
            }
            catch (RuntimeException | Error exception) {
                for (var elevator : elevators) { elevator.unsubscribeProtocol(mailbox); }
                failure = exception;
                lifecycle = Lifecycle.FAILED;
                throw exception;
            }
            return true;
        } finally { lock.unlock(); }
    }

    /** Apto para EDT: solo encola; validación y despacho ocurren en el actor. */
    public boolean submit(HallRequest request) {
        Objects.requireNonNull(request, "request");
        lock.lock();
        try {
            if (lifecycle != Lifecycle.RUNNING || resetHeld || !resetInbox.isEmpty()) { return false; }
            inbox.addLast(request);
            mailbox.wake();
            return true;
        } finally { lock.unlock(); }
    }

    /** Confirma adopción de época y limpieza; mantiene detenido el despacho hasta resume. */
    public CompletableFuture<Long> requestReset(long newEpoch) {
        lock.lock();
        try {
            if (lifecycle != Lifecycle.RUNNING) {
                return CompletableFuture.failedFuture(new IllegalStateException("Manejador no disponible."));
            }
            var response = new CompletableFuture<Long>();
            resetInbox.addLast(new ResetTicket(newEpoch, response));
            controlChanged.signalAll();
            mailbox.wake();
            return response.copy();
        } finally { lock.unlock(); }
    }

    public long epoch() { return epoch; }

    /** Solo el actor reemplaza su registro. Completa futuros después de soltar el lock. */
    private void processReset(ResetTicket ticket) {
        if (lastReset != null && ticket.epoch() == lastReset.epoch()) {
            lastReset.completion().whenComplete((value, error) -> {
                if (error == null) { ticket.response().complete(value); }
                else { ticket.response().completeExceptionally(error); }
            });
            return;
        }
        if (ticket.epoch() <= epoch) {
            ticket.response().completeExceptionally(new IllegalArgumentException("Época de reset no creciente."));
            return;
        }
        List<HallRequest> abandoned;
        CompletableFuture<Void> replaced;
        lock.lock();
        try {
            if (lifecycle != Lifecycle.RUNNING) {
                // El resultado se completa fuera de esta sección.
                abandoned = null;
                replaced = null;
            } else {
                abandoned = List.copyOf(inbox);
                inbox.clear();
                replaced = stopConfirmation;
                resetHeld = true;
                stopRequested = true;
                stopped = true;
                stopConfirmation = CompletableFuture.completedFuture(null);
            }
        } finally { lock.unlock(); }
        if (abandoned == null) {
            ticket.response().completeExceptionally(new IllegalStateException("Cierre interrumpió reset del manejador."));
            return;
        }
        ledger.cancelForReset();
        publish();
        for (var request : abandoned) {
            results.add(result(request, Outcome.CANCELLED, Optional.empty(), "Entrada invalidada por reset global."));
        }
        ledger = new HallRequestLedger(config, ticket.epoch());
        epoch = ticket.epoch();
        retryCursor = 0;
        publish();
        lastReset = new ResetOperation(epoch, new CompletableFuture<>());
        if (replaced != null && !replaced.isDone()) {
            replaced.completeExceptionally(new IllegalStateException("Stop del manejador sustituido por reset."));
        }
        trace.managerState(epoch, "MANAGER_RESET", null);
        lastReset.completion().complete(epoch);
        ticket.response().complete(epoch);
    }

    /** Confirma al salir del despacho en vuelo; conserva inbox, ledger y eventos. */
    public CompletableFuture<Void> requestStop() {
        lock.lock();
        try {
            if (lifecycle != Lifecycle.RUNNING || resetHeld || !resetInbox.isEmpty()) {
                return CompletableFuture.failedFuture(new IllegalStateException("Manejador no disponible para Stop."));
            }
            if (!stopRequested) {
                stopRequested = true;
                stopConfirmation = new CompletableFuture<>();
            }
            mailbox.wake();
            return stopConfirmation.copy();
        } finally { lock.unlock(); }
    }

    public boolean resume() {
        lock.lock();
        try {
            if (lifecycle != Lifecycle.RUNNING || !stopRequested || !stopped
                    || !resetInbox.isEmpty()) { return false; }
            resetHeld = false;
            stopRequested = false;
            stopped = false;
            controlChanged.signalAll();
            return true;
        } finally { lock.unlock(); }
    }

    /** Sin callbacks ni esperas externas bajo el lock local. */
    private boolean awaitDispatchPermission() throws InterruptedException {
        while (true) {
            CompletableFuture<Void> ack = null;
            ResetTicket reset = null;
            lock.lockInterruptibly();
            try {
                if (lifecycle != Lifecycle.RUNNING) { return false; }
                if (!resetInbox.isEmpty()) { reset = resetInbox.removeFirst(); }
                else if (!stopRequested) { return true; }
                else { stopped = true; ack = stopConfirmation; }
            } finally { lock.unlock(); }
            if (reset != null) { processReset(reset); continue; }
            ack.complete(null);
            lock.lockInterruptibly();
            try {
                if (stopRequested && stopConfirmation == ack && resetInbox.isEmpty()
                        && lifecycle == Lifecycle.RUNNING) { controlChanged.await(); }
            } finally { lock.unlock(); }
        }
    }

    public Lifecycle lifecycle() { return lifecycle; }
    public Optional<Throwable> failure() { return Optional.ofNullable(failure); }
    public String threadName() { return thread.getName(); }
    public Thread.State threadState() { return thread.getState(); }

    /** Un consumidor lógico. ZERO permite sondeo desde Swing sin esperar. */
    public Optional<Result> awaitResult(Duration timeout) throws InterruptedException {
        long nanos = timeoutNanos(timeout);
        rejectBlockingOnEdt(nanos);
        return Optional.ofNullable(results.poll(nanos, TimeUnit.NANOSECONDS));
    }

    /**
     * Cierre local sin join. El despacho ya extraído puede terminar; los demás
     * se cancelan explícitamente. No equivale a Stop o Reset del simulador.
     */
    @Override public void close() {
        lock.lock();
        try {
            if (lifecycle == Lifecycle.NEW) { lifecycle = Lifecycle.TERMINATED; }
            else if (lifecycle == Lifecycle.RUNNING) {
                lifecycle = Lifecycle.CLOSING;
                controlChanged.signalAll();
                mailbox.wake();
            }
        } finally { lock.unlock(); }
    }

    public boolean awaitTermination(Duration timeout) throws InterruptedException {
        long nanos = timeoutNanos(timeout);
        rejectBlockingOnEdt(nanos);
        if (Thread.currentThread() == thread) {
            throw new IllegalStateException("El manejador no espera su propia terminación.");
        }
        if (lifecycle == Lifecycle.NEW) { return false; }
        if (nanos > 0) { TimeUnit.NANOSECONDS.timedJoin(thread, nanos); }
        return !thread.isAlive();
    }

    /** Historial global inmutable; también conserva identidades alias y atendidas. */
    public Map<UUID, HallCallSnapshot> calls() { return published.get(); }
    public Optional<HallCallSnapshot> call(UUID id) { return Optional.ofNullable(published.get().get(id)); }

    /** Canal de cambios, independiente del resultado único de cada submit. */
    public Optional<HallCallSnapshot> awaitUpdate(Duration timeout) throws InterruptedException {
        long nanos = timeoutNanos(timeout);
        rejectBlockingOnEdt(nanos);
        return Optional.ofNullable(updates.poll(nanos, TimeUnit.NANOSECONDS));
    }

    /** El wake queda en cola si el actor aún no está esperando. */
    public void wakeForPause() { mailbox.wake(); }

    private long nowNanos() { return pause == null ? System.nanoTime() : pause.nowNanos(); }

    private void runLoop() {
        HallRequest inFlight = null;
        long lastRetry = nowNanos();
        try {
            trace.managerState(epoch, "MANAGER_STARTED", null);
            while (lifecycle == Lifecycle.RUNNING) {
                if (pause != null && !pause.checkpoint(0, () -> { }, () -> { })) { break; }
                if (!awaitDispatchPermission()) { break; }
                boolean hadEvents = false;
                for (int i = 0; i < 256; i++) {
                    var event = mailbox.await(Duration.ZERO);
                    if (event.isEmpty()) { break; }
                    acceptProtocol(event.orElseThrow()); publish(); hadEvents = true;
                }
                long now = nowNanos();
                if (hadEvents || now - lastRetry >= RETRY_NANOS) {
                    var pending = ledger.pending();
                    int count = Math.min(16, pending.size());
                    for (int i = 0; i < count && lifecycle == Lifecycle.RUNNING; i++) {
                        var request = pending.get((int) (((long) retryCursor + i) % pending.size()));
                        dispatch(request); publish();
                    }
                    if (!pending.isEmpty()) { retryCursor = (retryCursor + count) % pending.size(); }
                    lastRetry = nowNanos();
                }
                lock.lockInterruptibly();
                try {
                    if (lifecycle != Lifecycle.RUNNING) { break; }
                    inFlight = inbox.pollFirst();
                } finally { lock.unlock(); }
                if (inFlight != null) {
                    var admission = ledger.receive(inFlight);
                    publish();
                    Result result;
                    if (admission.kind() == HallRequestLedger.AdmissionKind.REJECTED) {
                        result = result(inFlight, Outcome.REJECTED, Optional.empty(), admission.detail());
                    } else if (admission.kind() == HallRequestLedger.AdmissionKind.DUPLICATE) {
                        var known = ledger.get(inFlight.getId()).orElseThrow();
                        result = result(inFlight, Outcome.DUPLICATE, known.command(),
                                admission.detail() + " Canónica=" + known.canonicalRequestId());
                    } else {
                        result = dispatch(inFlight);
                    }
                    publish(); results.add(result); inFlight = null;
                    // Una entrada por vuelta: los eventos tienen oportunidad de avanzar.
                    continue;
                }
                // Un wake de Stop/cierre pudo consumirse al drenar eventos.
                // Revalidar antes de volver a dormir evita perder ese control.
                // Si llega después de esta lectura, su wake permanece en la cola.
                lock.lockInterruptibly();
                try {
                    if (stopRequested || !resetInbox.isEmpty() || lifecycle != Lifecycle.RUNNING
                            || (pause != null && pause.isRequested())) { continue; }
                } finally { lock.unlock(); }
                // Los eventos despiertan por cola; el temporizador solo reintenta recibidas.
                var event = ledger.pending().isEmpty()
                        ? mailbox.await() : mailbox.await(Duration.ofNanos(RETRY_NANOS));
                if (event.isPresent()) { acceptProtocol(event.orElseThrow()); publish(); }
            }
        } catch (InterruptedException exception) {
            failure = exception; Thread.currentThread().interrupt();
        } catch (RuntimeException | Error exception) {
            failure = exception;
        } finally {
            if (pause != null) { pause.participantTerminated(0, failure); }
            for (var elevator : elevators) { elevator.unsubscribeProtocol(mailbox); }
            if (inFlight != null) {
                results.add(result(inFlight, Outcome.CANCELLED, Optional.empty(),
                        "Fallo durante el procesamiento; revisar el historial publicado."));
            }
            ledger.close(); publish();
            List<ResetTicket> abandonedResets;
            lock.lock();
            try {
                abandonedResets = List.copyOf(resetInbox);
                resetInbox.clear();
                while (!inbox.isEmpty()) {
                    results.add(result(inbox.removeFirst(), Outcome.CANCELLED, Optional.empty(),
                            "Manejador cerrado antes de procesar la entrada."));
                }
                lifecycle = failure == null ? Lifecycle.TERMINATED : Lifecycle.FAILED;
            } finally { lock.unlock(); }
            trace.managerState(epoch, failure == null ? "MANAGER_TERMINATED" : "MANAGER_FAILED", failure);
            var error = new IllegalStateException("Manejador terminó antes de adoptar reset.", failure);
            for (var reset : abandonedResets) { reset.response().completeExceptionally(error); }
            if (stopConfirmation != null && !stopConfirmation.isDone()) {
                stopConfirmation.completeExceptionally(new IllegalStateException("Manejador terminado antes de confirmar Stop.", failure));
            }
        }
    }

    private void publish() {
        var next = ledger.snapshot();
        var previous = published.getAndSet(next);
        for (var entry : next.entrySet()) {
            if (!entry.getValue().equals(previous.get(entry.getKey()))) {
                updates.add(entry.getValue()); trace.call(entry.getValue());
            }
        }
    }

    private List<ElevatorSelector.Candidate> eligibleCandidates(HallRequest request,
            List<ElevatorSelector.Candidate> candidates) {
        var rejected = ledger.rejectedCabins(request.getId());
        var awaiting = ledger.awaitingAdmissionCabins();
        return candidates.stream().filter(c -> !rejected.contains(c.planning().elevator().id())
                && !awaiting.contains(c.planning().elevator().id())).toList();
    }

    private Result pending(HallRequest request, String detail) {
        ledger.pendingReason(request.getId(), detail);
        return result(request, Outcome.PENDING, Optional.empty(), detail);
    }

    private void acceptProtocol(String text) {
        var parsed = protocol.decodeResponse(text);
        if (parsed.isSuccess()) { ledger.onProtocol(parsed.message().orElseThrow()); }
        // Un error de respuesta se descarta; nunca se contesta ERROR a ERROR.
    }

    private Result dispatch(HallRequest request) {
        if (request.getEpoch() != epoch) {
            return result(request, Outcome.REJECTED, Optional.empty(), "Época ajena.");
        }
        var validation = request.validateAgainst(config);
        if (!validation.isValid()) {
            return result(request, Outcome.REJECTED, Optional.empty(), validation.errors().toString());
        }
        var initial = captureCandidates();
        if (selector.rank(config, request, eligibleCandidates(request, initial), nowNanos()).choices().isEmpty()) {
            return pending(request, "Conservada: no hay cabinas disponibles, confirmadas o estimables para despachar.");
        }
        long commandId = commandIds.getAsLong();
        if (commandId < 1) {
            throw new IllegalStateException("Generador de comandos agotado o inválido.");
        }
        // El generador ya terminó: todos los costos siguientes usan un instante nuevo.
        for (int attempt = 0; attempt < MAX_SELECTION_ATTEMPTS; attempt++) {
            var candidates = captureCandidates();
            var ranking = selector.rank(config, request, eligibleCandidates(request, candidates), nowNanos());
            if (ranking.choices().isEmpty()) {
                return pending(request, "Conservada: no hay cabinas disponibles, confirmadas o estimables para despachar.");
            }
            var selected = ranking.choices().get(0);
            // Revalidar TODAS: también puede mejorar una cabina antes descartada.
            if (!areCurrent(candidates)) { continue; }
            var elevator = elevators.get(selected.elevatorId() - 1);
            var command = new Command.Pickup(commandId, selected.elevatorId(), request);
            var submitted = elevator.managerChannel().sendIfCurrent(protocol.encode(ProtocolCommands.fromDomain(command)),
                    request.getCreatedAt(), selected.candidate().planning());
            if (submitted == ElevatorWorker.Submission.ENQUEUED) {
                ledger.dispatched(request.getId(), command);
                return result(request, Outcome.DISPATCHED, Optional.of(command),
                        "Enviado por menor ETA=" + selected.estimate().pickupNanos().orElseThrow()
                        + " ns; desempate por ID ascendente. Admisión y servicio pendientes de confirmación.");
            }
            // No se envió: volver a estimar todas las opciones, no usar un ranking viejo.
        }
        return pending(request, "Conservada: tres intentos sin encolar por cambios de estado; se reintentará.");
    }

    private List<ElevatorSelector.Candidate> captureCandidates() {
        return elevators.stream().map(e -> new ElevatorSelector.Candidate(
                e.planningSnapshot(), e.lifecycle())).toList();
    }

    private boolean areCurrent(List<ElevatorSelector.Candidate> candidates) {
        for (int i = 0; i < elevators.size(); i++) {
            var elevator = elevators.get(i);
            var candidate = candidates.get(i);
            if (elevator.lifecycle() != candidate.lifecycle()
                    || elevator.planningSnapshot() != candidate.planning()) { return false; }
        }
        return true;
    }

    private Result result(HallRequest request, Outcome outcome,
            Optional<Command.Pickup> command, String detail) {
        var result = new Result(request, outcome, command, detail, Thread.currentThread().getName());
        trace.decision(result);
        return result;
    }

    private static long timeoutNanos(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative()) { throw new IllegalArgumentException("Tiempo negativo."); }
        return timeout.toNanos();
    }

    private static void rejectBlockingOnEdt(long nanos) {
        if (nanos > 0 && SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("No esperar desde el EDT.");
        }
    }
}
```

## src/main/java/com/elevatormanager/model/ElevatorState.java

```java
package com.elevatormanager.model;

import com.elevatormanager.model.request.CabinRequest;
import com.elevatormanager.model.request.HallRequest;
import com.elevatormanager.model.request.Request;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;

/**
 * Estado privado, con un único escritor: su ElevatorWorker.
 * Los demás hilos solo reciben snapshots inmutables.
 */
public final class ElevatorState {
    private final int id;
    private final SimulationConfig config;
    private long epoch;
    private long revision;
    private int currentFloor = 1;
    private Direction direction = Direction.UP;
    private ElevatorPhase phase = ElevatorPhase.IDLE;
    private int nextFloor;
    private int committedFloor;
    private long legStartedNanos;
    private long dwellStartedNanos;
    private long heldDwellRemainingNanos;
    private boolean resetting;
    private final Map<UUID, CabinRequest> cabinRequests = new LinkedHashMap<>();
    private final Map<UUID, HallRequest> hallRequests = new LinkedHashMap<>();
    private final Map<UUID, Request> admissionOrder = new LinkedHashMap<>();

    public ElevatorState(int id, SimulationConfig config, long epoch) {
        this.config = Objects.requireNonNull(config, "config");
        if (id < 1 || id > config.elevatorCount()) {
            throw new IllegalArgumentException("ID fuera de 1.." + config.elevatorCount());
        }
        if (epoch < 0) {
            throw new IllegalArgumentException("La época debe ser no negativa.");
        }
        this.id = id;
        this.epoch = epoch;
    }

    public int getId() { return id; }
    public SimulationConfig getConfig() { return config; }
    public long getEpoch() { return epoch; }
    public long getRevision() { return revision; }
    public int getCurrentFloor() { return currentFloor; }
    public Direction getDirection() { return direction; }
    public ElevatorPhase getPhase() { return phase; }
    public boolean isResetting() { return resetting; }

    /** Adopta época y cancela servicio; conserva exactamente el tramo físico iniciado. */
    public void beginReset(long newEpoch) {
        if (newEpoch <= epoch) { throw new IllegalArgumentException("Reset requiere una época mayor."); }
        if (resetting) { throw new IllegalStateException("Ya hay un retorno en curso."); }
        long nextRevision = Math.incrementExact(revision);
        cabinRequests.clear();
        hallRequests.clear();
        admissionOrder.clear();
        epoch = newEpoch;
        resetting = true;
        heldDwellRemainingNanos = 0;
        if (phase == ElevatorPhase.MOVING) {
            // No altera legStartedNanos, nextFloor ni sentido del tramo parcial.
            committedFloor = nextFloor;
        } else {
            phase = ElevatorPhase.IDLE;
            nextFloor = 0;
            committedFloor = 0;
        }
        revision = nextRevision;
    }

    /** T65: otra generación reemplaza el retorno sin alterar el tramo físico en curso. */
    public void supersedeReset(long newEpoch) {
        if (!resetting || newEpoch <= epoch) { throw new IllegalArgumentException("Sustitución de reset inválida."); }
        revision = Math.incrementExact(revision); epoch = newEpoch;
    }

    /** Confirmar solo tras retorno físico y sin agregar una parada de pasajeros. */
    public void finishReset() {
        if (!resetting || phase != ElevatorPhase.IDLE || currentFloor != 1) {
            throw new IllegalStateException("Reset sin retorno físico confirmado.");
        }
        long nextRevision = Math.incrementExact(revision);
        resetting = false;
        direction = Direction.UP;
        committedFloor = 0;
        revision = nextRevision;
    }

    public OptionalInt getCommittedFloor() {
        return committedFloor == 0 ? OptionalInt.empty() : OptionalInt.of(committedFloor);
    }

    /** Primitiva física; el planificador autoriza el objetivo antes de invocarla. */
    public boolean beginJourney(int targetFloor, long nowNanos) {
        if (targetFloor < 1 || targetFloor > config.floorCount()) {
            throw new IllegalArgumentException("Objetivo fuera del edificio.");
        }
        if (phase != ElevatorPhase.IDLE || committedFloor != 0) {
            throw new IllegalStateException("Ya existe movimiento o recorrido comprometido.");
        }
        if (targetFloor == currentFloor) { return false; }
        long nextRevision = Math.incrementExact(revision);
        Direction travelDirection = targetFloor > currentFloor ? Direction.UP : Direction.DOWN;
        int frontier = targetFloor;
        for (CabinRequest request : cabinRequests.values()) {
            frontier = travelDirection == Direction.UP
                    ? Math.max(frontier, request.getFloor()) : Math.min(frontier, request.getFloor());
        }
        direction = travelDirection;
        committedFloor = frontier;
        startLeg(nowNanos);
        revision = nextRevision;
        return true;
    }

    /** Inicia solo el siguiente tramo del compromiso vigente, sin cambiar de sentido. */
    public void beginNextLeg(long nowNanos) {
        if (phase != ElevatorPhase.IDLE || committedFloor == 0) {
            throw new IllegalStateException("No existe un siguiente tramo autorizado.");
        }
        long nextRevision = Math.incrementExact(revision);
        startLeg(nowNanos);
        revision = nextRevision;
    }

    private void startLeg(long nowNanos) {
        long candidate = (long) currentFloor + direction.floorDelta();
        if (candidate < 1 || candidate > config.floorCount()
                || (direction == Direction.UP && candidate > committedFloor)
                || (direction == Direction.DOWN && candidate < committedFloor)) {
            throw new IllegalStateException("Tramo fuera del recorrido comprometido.");
        }
        nextFloor = (int) candidate;
        legStartedNanos = nowNanos;
        phase = ElevatorPhase.MOVING;
    }

    /** Lectura temporal pura. La diferencia soporta el wrap normal de nanoTime. */
    public long movementRemainingNanos(long nowNanos) {
        if (phase != ElevatorPhase.MOVING) { return 0L; }
        return remaining(legStartedNanos, config.movementTimeMillis(), nowNanos);
    }

    public long dwellRemainingNanos(long nowNanos) {
        if (phase != ElevatorPhase.DWELLING) { return 0L; }
        return remaining(dwellStartedNanos, config.dwellTimeMillis(), nowNanos);
    }

    /** Stop congela solo una parada abierta; jamás un tramo entre pisos. */
    public void holdDwellForStop(long nowNanos) {
        if (phase != ElevatorPhase.DWELLING) { return; }
        heldDwellRemainingNanos = dwellRemainingNanos(nowNanos);
        phase = ElevatorPhase.HELD;
        revision = Math.incrementExact(revision);
    }

    public void resumeAfterStop(long nowNanos) {
        if (phase != ElevatorPhase.HELD) { return; }
        long duration = config.dwellTimeMillis() * 1_000_000L;
        dwellStartedNanos = nowNanos - (duration - heldDwellRemainingNanos);
        phase = ElevatorPhase.DWELLING;
        revision = Math.incrementExact(revision);
    }

    public long phaseRemainingNanos(long nowNanos) {
        if (phase == ElevatorPhase.HELD) { return heldDwellRemainingNanos; }
        return phase == ElevatorPhase.DWELLING
                ? dwellRemainingNanos(nowNanos) : movementRemainingNanos(nowNanos);
    }

    private static long remaining(long startedNanos, long durationMillis, long nowNanos) {
        long elapsed = nowNanos - startedNanos;
        if (elapsed < 0) {
            throw new IllegalArgumentException("Reloj retrocedido o intervalo no representable.");
        }
        long duration = durationMillis * 1_000_000L;
        return elapsed >= duration ? 0L : duration - elapsed;
    }

    /** Abre una parada solo si existe servicio compatible en este piso. */
    public List<Request> beginDwell(Direction serviceDirection, long nowNanos) {
        Objects.requireNonNull(serviceDirection, "serviceDirection");
        if (resetting) { throw new IllegalStateException("No hay servicio durante reset."); }
        if (phase != ElevatorPhase.IDLE) {
            throw new IllegalStateException("La parada exige una cabina alineada y libre.");
        }
        if (committedFloor != 0 && serviceDirection != direction) {
            throw new IllegalStateException("No se puede invertir un recorrido comprometido.");
        }
        List<Request> served = compatibleRequests(serviceDirection);
        if (served.isEmpty()) { return served; }
        long nextRevision = Math.incrementExact(revision);
        direction = serviceDirection;
        dwellStartedNanos = nowNanos;
        phase = ElevatorPhase.DWELLING;
        removeServed(served);
        revision = nextRevision;
        return served;
    }

    /** Incorpora a una parada todavía abierta, sin reiniciar su duración. */
    public List<Request> serveDuringDwell(long nowNanos) {
        if (phase != ElevatorPhase.DWELLING || dwellRemainingNanos(nowNanos) == 0) {
            return List.of();
        }
        List<Request> served = compatibleRequests(direction);
        if (!served.isEmpty()) {
            long nextRevision = Math.incrementExact(revision);
            removeServed(served);
            revision = nextRevision;
        }
        return served;
    }

    public boolean completeDwellIfDue(long nowNanos) {
        if (phase != ElevatorPhase.DWELLING || dwellRemainingNanos(nowNanos) != 0) {
            return false;
        }
        long nextRevision = Math.incrementExact(revision);
        phase = ElevatorPhase.IDLE;
        revision = nextRevision;
        return true;
    }

    private List<Request> compatibleRequests(Direction serviceDirection) {
        List<Request> served = new ArrayList<>();
        for (CabinRequest request : cabinRequests.values()) {
            if (request.getFloor() == currentFloor) { served.add(request); }
        }
        for (HallRequest request : hallRequests.values()) {
            if (request.getFloor() == currentFloor && request.getDirection() == serviceDirection) {
                served.add(request);
            }
        }
        return List.copyOf(served);
    }

    private void removeServed(List<Request> served) {
        for (Request request : served) {
            cabinRequests.remove(request.getId());
            hallRequests.remove(request.getId());
            admissionOrder.remove(request.getId());
        }
    }

    /** Confirma como máximo una llegada. El tiempo sobrante no adelanta otro piso. */
    public boolean completeLegIfDue(long nowNanos) {
        if (phase != ElevatorPhase.MOVING || movementRemainingNanos(nowNanos) != 0) {
            return false;
        }
        long nextRevision = Math.incrementExact(revision);
        currentFloor = nextFloor;
        nextFloor = 0;
        phase = ElevatorPhase.IDLE;
        if (currentFloor == committedFloor) { committedFloor = 0; }
        revision = nextRevision;
        return true;
    }

    /** Capturar solo desde el hilo propietario; referenceNanos usa reloj monotónico. */
    public ElevatorSnapshot snapshot(long referenceNanos) {
        return new ElevatorSnapshot(id, config, epoch, revision, currentFloor,
                nextFloor == 0 ? OptionalInt.empty() : OptionalInt.of(nextFloor), direction, phase,
                phase == ElevatorPhase.HELD ? Optional.of(ElevatorPhase.DWELLING) : Optional.empty(),
                phase == ElevatorPhase.HELD ? Optional.of(ElevatorSnapshot.HoldReason.STOP) : Optional.empty(),
                resetting ? ElevatorSnapshot.Mode.RESET_RETURN : ElevatorSnapshot.Mode.NORMAL,
                switch (phase) {
                    case MOVING -> config.movementTimeMillis() * 1_000_000L;
                    case DWELLING, HELD -> config.dwellTimeMillis() * 1_000_000L;
                    default -> 0L;
                },
                phaseRemainingNanos(referenceNanos), referenceNanos,
                getCommittedFloor(), getCabinRequests(), getHallRequests());
    }

    /** Copia de destinos internos, en orden de incorporación; no es orden de ruta. */
    public List<CabinRequest> getCabinRequests() {
        return List.copyOf(cabinRequests.values());
    }

    /** Copia de recogidas ya dirigidas a esta cabina por el manejador. */
    public List<HallRequest> getHallRequests() {
        return List.copyOf(hallRequests.values());
    }

    /** Orden global de admisión local; no es el orden de las paradas del barrido. */
    public List<Request> getPendingRequestsInAdmissionOrder() {
        return List.copyOf(admissionOrder.values());
    }

    /**
     * Incorpora trabajo local validado. Devuelve false para repetición exacta
     * de una identidad pendiente; rechaza inconsistencias sin cambiar el estado.
     * El llamador debe comprobar previamente autorización global y asignación.
     */
    public boolean addPendingRequest(Request request) {
        Objects.requireNonNull(request, "request");
        if (resetting) { throw new IllegalArgumentException("No se admite servicio durante reset."); }
        if (!(request instanceof CabinRequest) && !(request instanceof HallRequest)) {
            throw new IllegalArgumentException("Tipo de solicitud no soportado.");
        }
        if (request.getEpoch() != epoch) {
            throw new IllegalArgumentException("La solicitud pertenece a otra época.");
        }
        ValidationResult validation = request.validateAgainst(config);
        if (!validation.isValid()) {
            throw new IllegalArgumentException("Solicitud inválida: " + validation.errors());
        }
        if (request instanceof CabinRequest cabin && cabin.getElevatorId() != id) {
            throw new IllegalArgumentException("El destino pertenece a otra cabina.");
        }
        Request previous = cabinRequests.get(request.getId());
        if (previous == null) {
            previous = hallRequests.get(request.getId());
        }
        if (previous != null) {
            if (!sameContent(previous, request)) {
                throw new IllegalArgumentException("UUID reutilizado con otro contenido.");
            }
            return false;
        }
        boolean duplicate = request instanceof CabinRequest
                ? cabinRequests.values().stream().anyMatch(r -> r.getFloor() == request.getFloor())
                : hallRequests.values().stream().anyMatch(r -> r.getFloor() == request.getFloor()
                        && r.getDirection() == ((HallRequest) request).getDirection());
        if (duplicate) {
            throw new IllegalArgumentException("Duplicado funcional: debe resolverse en la admisión.");
        }
        long nextRevision = Math.incrementExact(revision);
        if (request instanceof CabinRequest cabin) {
            cabinRequests.put(cabin.getId(), cabin);
        } else {
            hallRequests.put(request.getId(), (HallRequest) request);
        }
        admissionOrder.put(request.getId(), request);
        if (request instanceof CabinRequest
                && (phase == ElevatorPhase.MOVING || phase == ElevatorPhase.DWELLING
                || committedFloor != 0)) {
            int floor = request.getFloor();
            if (direction == Direction.UP && floor > currentFloor) {
                committedFloor = Math.max(committedFloor == 0 ? currentFloor : committedFloor, floor);
            } else if (direction == Direction.DOWN && floor < currentFloor) {
                committedFloor = Math.min(committedFloor == 0 ? currentFloor : committedFloor, floor);
            }
        }
        revision = nextRevision;
        return true;
    }

    private static boolean sameContent(Request first, Request second) {
        if (first.getClass() != second.getClass() || first.getEpoch() != second.getEpoch()
                || first.getFloor() != second.getFloor()
                || !first.getCreatedAt().equals(second.getCreatedAt())) {
            return false;
        }
        if (first instanceof CabinRequest cabin) {
            return cabin.getElevatorId() == ((CabinRequest) second).getElevatorId();
        }
        return ((HallRequest) first).getDirection() == ((HallRequest) second).getDirection();
    }
}
```

## src/main/java/com/elevatormanager/protocol/ProtocolIdentities.java

```java
package com.elevatormanager.protocol;

import java.util.HashMap;
import java.util.Map;

/** Registro corto bajo monitor, sin acceder a actores ni ejecutar callbacks. Una instancia por sesión. */
public final class ProtocolIdentities {
    private final Map<Long, ProtocolMessage> admitted = new HashMap<>();
    public synchronized boolean reserve(ProtocolMessage message) {
        ProtocolMessage old = admitted.get(message.header().id());
        if (old != null) { return old.equals(message); }
        admitted.put(message.header().id(), message); return true;
    }
    public synchronized boolean conflicts(ProtocolMessage message) {
        ProtocolMessage old = admitted.get(message.header().id());
        return old != null && !old.equals(message);
    }
    public synchronized void release(ProtocolMessage message) { admitted.remove(message.header().id(), message); }
}
```

## src/main/java/com/elevatormanager/protocol/ProtocolReplyTracker.java

```java
package com.elevatormanager.protocol;

import com.elevatormanager.model.SimulationConfig;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import static com.elevatormanager.protocol.ProtocolMessage.*;

/** Correlación por sesión/ID/cabina/época; completar un futuro nunca se hace bajo el monitor. */
public final class ProtocolReplyTracker {
    public record Reply(Header header, Ack ack, Optional<State> state, Terminal terminal) { }
    private static final class Pending {
        final ProtocolMessage command;
        final CompletableFuture<Reply> future = new CompletableFuture<>();
        Ack ack;
        State state;
        Pending(ProtocolMessage command) { this.command = command; }
    }
    private final ProtocolCodec codec;
    private final Map<Long, Pending> pending = new HashMap<>();
    private final Map<Integer, State> current = new HashMap<>();
    private long epoch;
    private boolean closed;
    private long malformed;
    private long ignored;

    public ProtocolReplyTracker(SimulationConfig config, long epoch) {
        codec = new ProtocolCodec(config);
        if (epoch < 0) { throw new IllegalArgumentException("Época negativa."); }
        this.epoch = epoch;
    }

    public CompletableFuture<Reply> register(ProtocolMessage command) {
        Objects.requireNonNull(command, "command");
        if (!(command instanceof Control || command instanceof Pickup || command instanceof Destination)) {
            throw new IllegalArgumentException("Se requiere una orden.");
        }
        codec.encode(command);
        synchronized (this) {
            if (closed) { throw new IllegalStateException("Canal cerrado."); }
            Pending old = pending.get(command.header().id());
            if (old != null) {
                if (!old.command.equals(command)) { throw new IllegalArgumentException("ID de comando en conflicto."); }
                return old.future.copy();
            }
            Pending entry = new Pending(command); pending.put(command.header().id(), entry);
            return entry.future.copy();
        }
    }

    public synchronized void adoptEpoch(long value) {
        if (value < epoch) { throw new IllegalArgumentException("La época no retrocede."); }
        if (value != epoch) { epoch = value; current.clear(); }
    }

    public synchronized Optional<State> state(int elevatorId) { return Optional.ofNullable(current.get(elevatorId)); }
    public synchronized long malformed() { return malformed; }
    public synchronized long ignored() { return ignored; }

    /** Respuestas viejas pueden cerrar su futuro histórico; nunca instalan estado vigente. */
    public void receive(String text) {
        var parsed = codec.decodeResponse(text);
        if (!parsed.isSuccess()) { synchronized (this) { malformed++; } return; }
        ProtocolMessage m = parsed.message().orElseThrow();
        Pending complete = null; Reply reply = null; Throwable failure = null;
        synchronized (this) {
            if (closed) { ignored++; return; }
            Pending entry = pending.get(m.header().id());
            boolean correlated = entry != null && entry.command.header().equals(m.header());
            if (m instanceof State state) {
                // Un STATE correlacionado solo vale si fue solicitado; el espontáneo usa ID 0.
                if (state.header().id() == 0 || correlated && entry.ack != null && !entry.future.isDone()) {
                    State old = current.get(state.header().elevatorId());
                    if (state.header().epoch() == epoch && (old == null || state.revision() >= old.revision())) {
                        current.put(state.header().elevatorId(), state);
                    }
                    if (correlated && entry.ack != null) { entry.state = state; }
                } else { ignored++; }
                return;
            }
            if (!correlated || entry.future.isDone()) { ignored++; return; }
            if (m instanceof Ack ack) {
                if (entry.command instanceof Control && ack.kind() != AckKind.ACCEPTED) { ignored++; return; }
                if (entry.ack == null) { entry.ack = ack; } return;
            }
            if (m instanceof ProtocolMessage.Error error) {
                complete = entry; failure = new Rejected(error);
            } else if (m instanceof Terminal terminal) {
                if (entry.ack == null) { ignored++; return; }
                if (terminal.kind() != TerminalKind.COMPLETED) {
                    complete = entry; failure = new IllegalStateException("Orden " + terminal.header().id()
                            + ": " + terminal.kind() + " " + terminal.reason() + " " + terminal.error());
                } else {
                    boolean needsState = entry.command instanceof Control c
                            && (c.kind() == ControlKind.STATUS || c.kind() == ControlKind.RESET);
                    if (needsState && entry.state == null) { ignored++; return; }
                    if (entry.command instanceof Control c && c.kind() == ControlKind.RESET
                            && (entry.state.floor() != 1 || entry.state.direction() != com.elevatormanager.model.Direction.UP
                            || entry.state.phase() != com.elevatormanager.model.ElevatorPhase.IDLE
                            || entry.state.mode() != Mode.NORMAL || !entry.state.internal().isEmpty()
                            || !entry.state.external().isEmpty())) { ignored++; return; }
                    complete = entry; reply = new Reply(m.header(), entry.ack, Optional.ofNullable(entry.state), terminal);
                }
            }
        }
        if (complete != null) {
            if (failure == null) { complete.future.complete(reply); }
            else { complete.future.completeExceptionally(failure); }
        }
    }

    public void fail(long id, Throwable error) {
        Pending entry; synchronized (this) { entry = pending.get(id); }
        if (entry != null) { entry.future.completeExceptionally(error); }
    }

    public void close() {
        List<Pending> unfinished;
        synchronized (this) { closed = true; unfinished = pending.values().stream().filter(p -> !p.future.isDone()).toList(); }
        for (Pending entry : unfinished) { entry.future.completeExceptionally(new IllegalStateException("Canal de respuestas cerrado.")); }
    }

    public static final class Rejected extends IllegalStateException {
        private static final long serialVersionUID = 1L;
        private final ProtocolMessage.Error response;
        public Rejected(ProtocolMessage.Error response) { super(response.code().name()); this.response = response; }
        public ProtocolMessage.Error response() { return response; }
    }
}
```

## src/main/java/com/elevatormanager/simulation/ElevatorWorker.java

```java
package com.elevatormanager.simulation;

import com.elevatormanager.logging.TraceMailbox;
import com.elevatormanager.simulation.time.PauseCoordinator;
import com.elevatormanager.model.ElevatorSnapshot;
import com.elevatormanager.model.ElevatorPhase;
import com.elevatormanager.model.ElevatorState;
import com.elevatormanager.model.SimulationConfig;
import com.elevatormanager.model.Direction;
import com.elevatormanager.model.request.Request;
import com.elevatormanager.protocol.Command;
import com.elevatormanager.protocol.ProtocolCodec;
import com.elevatormanager.protocol.ProtocolCommands;
import com.elevatormanager.protocol.ProtocolDecodeResult;
import com.elevatormanager.protocol.ProtocolMessage;
import com.elevatormanager.protocol.ProtocolIdentities;
import com.elevatormanager.protocol.ProtocolMessage.ErrorCode;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * T64: buzones de texto V1; parseo/admisión en el actor y reset prioritario.
 * No expone el estado mutable ni ejecuta callbacks de consumidores.
 */
public final class ElevatorWorker implements AutoCloseable {
    public enum Lifecycle { NEW, RUNNING, CLOSING, TERMINATED, FAILED }
    public enum Outcome { ACCEPTED, REJECTED, DUPLICATE }

    /** Aceptación en agenda, nunca confirmación de llegada o servicio. */
    public record Result(Command command, Outcome outcome, String detail,
            Optional<UUID> canonicalRequestId, Optional<CommandLedger.RequestStatus> requestStatus,
            boolean replay) {
        public Result(Command command, Outcome outcome, String detail) {
            this(command, outcome, detail, Optional.empty(), Optional.empty(), false);
        }
    }

    /** Hecho de servicio: apertura de parada o incorporación a una parada abierta. */
    public record Service(int elevatorId, long epoch, long revision, int floor,
            Direction direction, long referenceNanos, boolean openedStop, List<Request> requests) {
        public Service { requests = List.copyOf(requests); }
    }

    private final TraceMailbox trace;
    private final PauseCoordinator pause; // null: actor independiente, nanoTime directo.
    private boolean pauseView;
    private final ElevatorState state;
    private final CommandLedger ledger;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition available = lock.newCondition();
    /** El sobre solo lleva texto y fecha contextual; nunca el Command original. */
    private enum Origin { LEGACY, MANAGER, CONTROLLER }
    private record TextFrame(String text, Instant createdAt, Origin origin, boolean control) {
        TextFrame(String text, Instant date) { this(text, date, Origin.LEGACY, false); }
        TextFrame { Objects.requireNonNull(text, "text"); Objects.requireNonNull(createdAt, "createdAt"); }
    }
    private final ArrayDeque<TextFrame> controlInbox = new ArrayDeque<>();
    private final Map<ProtocolMessage.ControlKind, ProtocolMessage.Control> controls = new HashMap<>();
    private final Map<Long, ProtocolMessage> admittedProtocol = new HashMap<>();
    private final Map<WireKey, ProtocolMessage.State> wireStates = new HashMap<>();
    private ProtocolIdentities identities = new ProtocolIdentities();
    private Origin processingOrigin = Origin.LEGACY;
    private boolean protocolStarted;

    /** Capacidad del emisor por objeto interno, nunca un token autodeclarado. */
    public final class CommandChannel {
        private final Origin origin;
        private CommandChannel(Origin origin) { this.origin = origin; }
        public boolean send(String text, Instant date) {
            return enqueue(new TextFrame(text, date, origin, false), null, false) == Submission.ENQUEUED;
        }
        public Submission sendIfCurrent(String text, Instant date, PlanningSnapshot expected) {
            Objects.requireNonNull(expected, "expected");
            return enqueue(new TextFrame(text, date, origin, false), expected, false);
        }
        public boolean control(String text) {
            lock.lock();
            try {
                if (lifecycle != Lifecycle.RUNNING) { return false; }
                controlInbox.addLast(new TextFrame(text, Instant.EPOCH, origin, true));
                available.signalAll();
            } finally { lock.unlock(); }
            if (pause != null) { pause.wakeControls(); }
            return true;
        }
    }
    private final CommandChannel managerChannel = new CommandChannel(Origin.MANAGER);
    private final CommandChannel controllerChannel = new CommandChannel(Origin.CONTROLLER);
    public CommandChannel managerChannel() { return managerChannel; }
    public CommandChannel controllerChannel() { return controllerChannel; }

    /** Solo composición antes de start; el registro no llama a otros actores. */
    public void useProtocolIdentities(ProtocolIdentities registry) {
        lock.lock();
        try {
            if (lifecycle != Lifecycle.NEW) { throw new IllegalStateException("La sesión ya comenzó."); }
            identities = Objects.requireNonNull(registry, "registry");
        } finally { lock.unlock(); }
    }
    private record ResetFrame(TextFrame frame, CompletableFuture<ElevatorSnapshot> response) { }
    private record Decoded(Command command, Result rejection) { }
    public record ProtocolReception(long frames, long malformed, String processedBy) { }
    private final AtomicReference<ProtocolReception> protocolReception =
            new AtomicReference<>(new ProtocolReception(0, 0, ""));
    private final ProtocolCodec protocol;
    private final ProtocolMailbox protocolOutput = new ProtocolMailbox();
    private final CopyOnWriteArrayList<ProtocolMailbox> protocolSubscribers = new CopyOnWriteArrayList<>();
    private final List<String> protocolBatch = new ArrayList<>();
    private record WireKey(long epoch, long id) { }
    private final Map<WireKey, ProtocolMessage> wireAdmissions = new HashMap<>();
    private final Map<WireKey, ProtocolMessage.Terminal> wireTerminals = new HashMap<>();
    private final ArrayDeque<TextFrame> inbox = new ArrayDeque<>();
    private final BlockingQueue<Result> results = new LinkedBlockingQueue<>();
    private final BlockingQueue<Service> services = new LinkedBlockingQueue<>();
    private final BlockingQueue<ExecutionEvent> events = new LinkedBlockingQueue<>();
    private final CopyOnWriteArrayList<ExecutionEventMailbox> subscribers = new CopyOnWriteArrayList<>();
    private long eventSequence;
    // Solo el actor construye y entrega este lote antes de la siguiente transición.
    private final List<ExecutionEvent> eventBatch = new ArrayList<>();
    private final AtomicReference<PlanningSnapshot> published;
    private final Thread thread;
    private volatile Lifecycle lifecycle = Lifecycle.NEW;
    private volatile Throwable failure;
    private boolean stopRequested;
    private boolean stopped;
    private boolean resumeRequested;
    private CompletableFuture<Void> stopConfirmation;
    private record ResetTicket(Command.Reset command, CompletableFuture<ElevatorSnapshot> response) { }
    private record ResetOperation(Command.Reset command, CompletableFuture<ElevatorSnapshot> completion) { }
    private final ArrayDeque<ResetFrame> resetInbox = new ArrayDeque<>();
    private ResetOperation lastReset;
    private boolean resetHeld;
    private Optional<Direction> completedSweep = Optional.empty();

    public ElevatorWorker(int id, SimulationConfig config, long epoch) {
        this(id, config, epoch, TraceMailbox.disabled());
    }

    public ElevatorWorker(int id, SimulationConfig config, long epoch, TraceMailbox trace) {
        this(id, config, epoch, trace, null);
    }

    /** Sesión coordinada: todos los actores deben compartir exactamente esta barrera. */
    public ElevatorWorker(int id, SimulationConfig config, long epoch, TraceMailbox trace,
            PauseCoordinator pause) {
        this.pause = pause;
        this.trace = Objects.requireNonNull(trace, "trace");
        protocol = new ProtocolCodec(config);
        state = new ElevatorState(id, config, epoch);
        ledger = new CommandLedger(state);
        published = new AtomicReference<>(capture(nowNanos()));
        thread = new Thread(this::runLoop, "elevator-worker-" + id);
    }

    /** Solo arranca una vez. Un cierre previo también impide arrancar. */
    public boolean start() {
        lock.lock();
        try {
            if (lifecycle != Lifecycle.NEW) { return false; }
            lifecycle = Lifecycle.RUNNING;
            try {
                thread.start();
            } catch (RuntimeException | Error exception) {
                failure = exception;
                lifecycle = Lifecycle.FAILED;
                throw exception;
            }
            return true;
        } finally {
            lock.unlock();
        }
    }

    /** true solo confirma encolado; el resultado de admisión llega por separado. */
    public boolean submit(Command command) {
        TextFrame frame = encode(command);
        return enqueue(frame, null, command instanceof Command.Reset) == Submission.ENQUEUED;
    }

    /** Entrada de texto real. true confirma encolado, nunca parseo/admisión. */
    public boolean submitText(String text) { return submitText(text, Instant.EPOCH); }

    /** createdAt es contexto de sesión, no parte del contenido operativo V1. */
    public boolean submitText(String text, Instant createdAt) {
        return enqueue(new TextFrame(text, createdAt), null, false) == Submission.ENQUEUED;
    }

    public Submission submitTextIfCurrent(String text, Instant createdAt, PlanningSnapshot expected) {
        Objects.requireNonNull(expected, "expected");
        return enqueue(new TextFrame(text, createdAt), expected, false);
    }

    private Submission enqueue(TextFrame frame, PlanningSnapshot expected, boolean reset) {
        lock.lock();
        try {
            if (lifecycle != Lifecycle.RUNNING) { return Submission.UNAVAILABLE; }
            if (expected != null && published.get() != expected) { return Submission.STALE; }
            if (reset) {
                resetInbox.addLast(new ResetFrame(frame, new CompletableFuture<>()));
                available.signalAll();
                return Submission.ENQUEUED;
            }
            if (resetHeld || !resetInbox.isEmpty()) { return Submission.UNAVAILABLE; }
            inbox.addLast(frame);
            available.signal();
            return Submission.ENQUEUED;
        } finally { lock.unlock(); }
    }

    /** Serializa antes de cruzar el buzón prioritario; futuro de retorno físico. */
    public CompletableFuture<ElevatorSnapshot> requestReset(Command.Reset command) {
        return requestResetText(encode(command).text());
    }

    /** El actor valida que el texto sea RESET; un cuerpo inválido no cambia época. */
    public CompletableFuture<ElevatorSnapshot> requestResetText(String text) {
        TextFrame frame = new TextFrame(text, Instant.EPOCH);
        lock.lock();
        try {
            if (lifecycle != Lifecycle.RUNNING) {
                return CompletableFuture.failedFuture(new IllegalStateException("Cabina no disponible."));
            }
            var response = new CompletableFuture<ElevatorSnapshot>();
            resetInbox.addLast(new ResetFrame(frame, response));
            available.signalAll();
            return response.copy();
        } finally { lock.unlock(); }
    }

    /** Canal textual propio, independiente de Result/Service/ExecutionEvent. */
    public Optional<String> awaitProtocolMessage(Duration timeout) throws InterruptedException {
        return protocolOutput.await(timeout);
    }

    public void subscribeProtocol(ProtocolMailbox mailbox) {
        protocolSubscribers.addIfAbsent(Objects.requireNonNull(mailbox, "mailbox"));
    }

    public void unsubscribeProtocol(ProtocolMailbox mailbox) { protocolSubscribers.remove(mailbox); }
    public ProtocolReception protocolReception() { return protocolReception.get(); }

    /** Suscripción adicional sin callbacks. No reproduce eventos anteriores al registro. */
    public void subscribeEvents(ExecutionEventMailbox mailbox) {
        subscribers.addIfAbsent(Objects.requireNonNull(mailbox, "mailbox"));
    }

    /** Puede quedar un último lote ya capturado por el publicador concurrente. */
    public void unsubscribeEvents(ExecutionEventMailbox mailbox) { subscribers.remove(mailbox); }

    public enum Submission { ENQUEUED, STALE, UNAVAILABLE }

    /**
     * Compara y encola bajo el lock local. STALE/UNAVAILABLE no encolan nada.
     * No reserva el estado ni confirma admisión; el buzón previo puede contener trabajo.
     */
    public Submission submitIfCurrent(Command command, PlanningSnapshot expected) {
        Objects.requireNonNull(expected, "expected");
        return enqueue(encode(command), expected, command instanceof Command.Reset);
    }

    /** Control prioritario; confirma tras llegada segura y publicación de eventos. */
    public CompletableFuture<Void> requestStop() {
        lock.lock();
        try {
            if (lifecycle != Lifecycle.RUNNING || resetHeld || !resetInbox.isEmpty()) {
                return CompletableFuture.failedFuture(new IllegalStateException("Cabina no disponible para Stop."));
            }
            if (!stopRequested) {
                stopRequested = true;
                stopConfirmation = new CompletableFuture<>();
            }
            available.signalAll();
            return stopConfirmation.copy();
        } finally { lock.unlock(); }
    }

    /** Continúa el mismo actor; no crea hilos ni vuelve a admitir comandos previos. */
    public boolean resume() {
        lock.lock();
        try {
            if (lifecycle != Lifecycle.RUNNING || !stopRequested || !stopped
                    || state.isResetting() || !resetInbox.isEmpty()) { return false; }
            resetHeld = false;
            stopRequested = false;
            resumeRequested = true;
            available.signalAll();
            return true;
        } finally { lock.unlock(); }
    }

    public ElevatorSnapshot snapshot() { return published.get().elevator(); }

    /** Estado, orden de admisión y barrido capturados juntos por el actor. */
    public PlanningSnapshot planningSnapshot() { return published.get(); }

    private PlanningSnapshot capture(long now) {
        ElevatorSnapshot snapshot = state.snapshot(now);
        if (pauseView && (snapshot.phase() == ElevatorPhase.MOVING || snapshot.phase() == ElevatorPhase.DWELLING)) {
            snapshot = new ElevatorSnapshot(snapshot.id(), snapshot.config(), snapshot.epoch(), snapshot.revision(),
                    snapshot.currentFloor(), snapshot.nextFloor(), snapshot.direction(), ElevatorPhase.HELD,
                    Optional.of(snapshot.phase()), Optional.of(ElevatorSnapshot.HoldReason.PAUSE), snapshot.mode(),
                    snapshot.phaseDurationNanos(), snapshot.remainingNanos(), snapshot.referenceNanos(),
                    snapshot.committedFloor(), snapshot.cabinRequests(), snapshot.hallRequests());
        }
        return new PlanningSnapshot(snapshot,
                state.getPendingRequestsInAdmissionOrder(), completedSweep);
    }
    public Lifecycle lifecycle() { return lifecycle; }
    public Optional<Throwable> failure() { return Optional.ofNullable(failure); }
    public String threadName() { return thread.getName(); }
    public Thread.State threadState() { return thread.getState(); }

    /** Canal de un solo consumidor lógico. No invocar con espera desde el EDT. */
    public Optional<Result> awaitResult(Duration timeout) throws InterruptedException {
        return Optional.ofNullable(results.poll(timeoutNanos(timeout), TimeUnit.NANOSECONDS));
    }

    /** Canal fiable con un consumidor lógico; no esperar desde el EDT. */
    public Optional<Service> awaitService(Duration timeout) throws InterruptedException {
        return Optional.ofNullable(services.poll(timeoutNanos(timeout), TimeUnit.NANOSECONDS));
    }

    /** Flujo completo, fiable en memoria, con un único consumidor lógico por cabina.
     * No esperar desde el EDT. No existe orden global entre cabinas.
     */
    public Optional<ExecutionEvent> awaitEvent(Duration timeout) throws InterruptedException {
        return Optional.ofNullable(events.poll(timeoutNanos(timeout), TimeUnit.NANOSECONDS));
    }

    /** Cierre local prioritario, idempotente y sin esperar al trabajador. */
    @Override
    public void close() {
        lock.lock();
        try {
            if (lifecycle == Lifecycle.NEW) {
                lifecycle = Lifecycle.TERMINATED;
            } else if (lifecycle == Lifecycle.RUNNING) {
                lifecycle = Lifecycle.CLOSING;
                available.signalAll();
            }
        } finally {
            lock.unlock();
        }
    }

    /** Espera terminación real fuera del EDT y sin conservar el lock del buzón. */
    public boolean awaitTermination(Duration timeout) throws InterruptedException {
        if (Thread.currentThread() == thread) {
            throw new IllegalStateException("Un trabajador no espera su propia terminación.");
        }
        long nanos = timeoutNanos(timeout);
        if (lifecycle == Lifecycle.NEW) { return false; }
        if (nanos > 0) { TimeUnit.NANOSECONDS.timedJoin(thread, nanos); }
        return !thread.isAlive();
    }

    /** Señal breve: despierta espera de tramo, parada o reposo para llegar al checkpoint. */
    public void wakeForPause() {
        lock.lock();
        try { available.signalAll(); }
        finally { lock.unlock(); }
    }

    private long nowNanos() { return pause == null ? System.nanoTime() : pause.nowNanos(); }

    private void publishPauseView(boolean value) {
        lock.lock();
        try {
            pauseView = value;
            published.set(capture(nowNanos()));
        } finally { lock.unlock(); }
    }

    private void runLoop() {
        try {
            while (true) {
                if (pause != null && !pause.checkpoint(state.getId(),
                        () -> publishPauseView(true), () -> publishPauseView(false), this::pausedControls)) { break; }
                Result result = null;
                CompletableFuture<Void> stoppedAck = null;
                List<Runnable> deferred = new ArrayList<>();
                List<Service> notifications = new ArrayList<>();
                lock.lockInterruptibly();
                try {
                    if (lifecycle != Lifecycle.RUNNING) { break; }
                    // Cierra la carrera entre checkpoint y adquisición del lock del actor.
                    if (pause != null && pause.isRequested()) { continue; }
                    long now = nowNanos();
                    if (!controlInbox.isEmpty()) {
                        processControlFrame(controlInbox.removeFirst(), now, deferred);
                    } else if (!resetInbox.isEmpty()) {
                        processResetFrame(resetInbox.removeFirst(), now, deferred);
                    } else if (state.isResetting()) {
                        if (state.getPhase() == ElevatorPhase.MOVING) {
                            if (state.completeLegIfDue(now)) {
                                published.set(capture(now));
                                emit(ExecutionEvent.Type.ARRIVED, now);
                                continueReset(now, deferred);
                            } else { available.awaitNanos(state.phaseRemainingNanos(now)); }
                        } else { continueReset(now, deferred); }
                    } else if (stopRequested) {
                        // Nunca drenar inbox ni abrir servicio después de solicitar Stop.
                        if (state.getPhase() == ElevatorPhase.MOVING) {
                            if (state.completeLegIfDue(now)) {
                                if (state.getCommittedFloor().isEmpty()) {
                                    completedSweep = Optional.of(state.getDirection());
                                }
                                published.set(capture(now));
                                emit(ExecutionEvent.Type.ARRIVED, now);
                            } else {
                                available.awaitNanos(state.phaseRemainingNanos(now));
                            }
                        }
                        if (state.getPhase() != ElevatorPhase.MOVING) {
                            if (!stopped) {
                                state.holdDwellForStop(now);
                                published.set(capture(now));
                                stopped = true;
                                emit(ExecutionEvent.Type.CONTROL_STOPPED, now);
                            }
                            if (!stopConfirmation.isDone()) { stoppedAck = stopConfirmation; }
                            else { available.await(); }
                        }
                    } else if (resumeRequested) {
                        resumeRequested = false;
                        stopped = false;
                        state.resumeAfterStop(now);
                        published.set(capture(now));
                        emit(ExecutionEvent.Type.CONTROL_RESUMED, now);
                        continueWork(now, notifications);
                    } else if (state.getPhase() == ElevatorPhase.MOVING
                            && state.completeLegIfDue(now)) {
                        if (state.getCommittedFloor().isEmpty()) {
                            completedSweep = Optional.of(state.getDirection());
                        }
                        published.set(capture(now));
                        emit(ExecutionEvent.Type.ARRIVED, now);
                        continueWork(now, notifications);
                    } else if (state.getPhase() == ElevatorPhase.DWELLING
                            && state.completeDwellIfDue(now)) {
                        published.set(capture(now));
                        emit(ExecutionEvent.Type.STOP_ENDED, now);
                        continueWork(now, notifications);
                    } else if (!inbox.isEmpty()) {
                        Decoded decoded = decode(inbox.removeFirst());
                        if (decoded.command() instanceof Command.Reset) {
                            result = rejectServiceControl(decoded.command(), now);
                        } else if (decoded.command() != null) {
                            result = admit(decoded.command());
                        } else { result = decoded.rejection(); }
                        continueWork(nowNanos(), notifications);
                    } else if (state.getPhase() == ElevatorPhase.MOVING
                            || state.getPhase() == ElevatorPhase.DWELLING) {
                        long remaining = state.phaseRemainingNanos(nowNanos());
                        if (remaining > 0) { available.awaitNanos(remaining); }
                    } else {
                        available.await();
                    }
                } finally {
                    lock.unlock();
                }
                completeControls();
                flushEvents();
                for (Runnable action : deferred) { action.run(); }
                if (stoppedAck != null) { stoppedAck.complete(null); }
                if (result != null) { results.add(result); }
                for (Service notification : notifications) { services.add(notification); }
            }
        } catch (InterruptedException exception) {
            failure = exception;
            Thread.currentThread().interrupt();
        } catch (RuntimeException | Error exception) {
            failure = exception;
        } finally {
            if (pause != null) { pause.participantTerminated(state.getId(), failure); }
            List<TextFrame> abandoned;
            List<ResetFrame> abandonedResets;
            lock.lock();
            try {
                lifecycle = failure == null ? Lifecycle.TERMINATED : Lifecycle.FAILED;
                abandoned = new ArrayList<>(inbox);
                inbox.clear();
                abandonedResets = new ArrayList<>(resetInbox);
                resetInbox.clear();
            } finally {
                lock.unlock();
            }
            while (!controlInbox.isEmpty()) {
                TextFrame frame = controlInbox.removeFirst();
                var parsed = protocol.decodeCommand(frame.text());
                if (parsed.message().orElse(null) instanceof ProtocolMessage.Control control
                        && control.kind() == ProtocolMessage.ControlKind.SHUTDOWN && frame.origin() == Origin.CONTROLLER) {
                    processingOrigin = frame.origin(); acceptControl(control);
                } else if (parsed.isSuccess()) {
                    wire(new ProtocolMessage.Error(parsed.message().orElseThrow().header(), ErrorCode.WORKER_UNAVAILABLE));
                } else { wire(parsed.failure().orElseThrow().asErrorMessage()); }
            }
            cancelControls(ProtocolMessage.CancelReason.SHUTDOWN);
            for (TextFrame frame : abandoned) {
                rejectAbandoned(frame);
            }
            for (ResetFrame ticket : abandonedResets) { rejectAbandoned(ticket.frame()); }
            for (CommandLedger.Completion pending : ledger.clearForReset()) {
                wire(new ProtocolMessage.Terminal(header(pending.command()), failure == null
                        ? ProtocolMessage.TerminalKind.CANCELLED : ProtocolMessage.TerminalKind.FAILED,
                        failure == null ? Optional.of(ProtocolMessage.CancelReason.SHUTDOWN) : Optional.empty(),
                        failure == null ? Optional.empty() : Optional.of(ErrorCode.INTERNAL_ERROR)));
            }
            if (lastReset != null && !lastReset.completion().isDone()) {
                wire(new ProtocolMessage.Terminal(header(lastReset.command()), failure == null
                        ? ProtocolMessage.TerminalKind.CANCELLED : ProtocolMessage.TerminalKind.FAILED,
                        failure == null ? Optional.of(ProtocolMessage.CancelReason.SHUTDOWN) : Optional.empty(),
                        failure == null ? Optional.empty() : Optional.of(ErrorCode.INTERNAL_ERROR)));
            }
            ProtocolMessage.Control shutdown = controls.remove(ProtocolMessage.ControlKind.SHUTDOWN);
            if (shutdown != null) { controlCompleted(shutdown); }
            emit(failure == null ? ExecutionEvent.Type.WORKER_TERMINATED
                    : ExecutionEvent.Type.WORKER_FAILED, nowNanos());
            flushEvents();
            var resetFailure = new IllegalStateException("Cabina terminada antes de completar reset.", failure);
            for (ResetFrame ticket : abandonedResets) { ticket.response().completeExceptionally(resetFailure); }
            if (lastReset != null && !lastReset.completion().isDone()) {
                lastReset.completion().completeExceptionally(resetFailure);
            }
            if (stopConfirmation != null && !stopConfirmation.isDone()) {
                stopConfirmation.completeExceptionally(new IllegalStateException("Cabina terminada antes de confirmar Stop.", failure));
            }
        }
    }

    private boolean pausedControls() {
        List<Runnable> deferred = new ArrayList<>();
        lock.lock();
        try {
            while (!controlInbox.isEmpty()) { processControlFrame(controlInbox.removeFirst(), nowNanos(), deferred); }
            completeControls();
        } finally { lock.unlock(); }
        flushEvents();
        deferred.forEach(Runnable::run);
        return lifecycle == Lifecycle.RUNNING;
    }

    private void processControlFrame(TextFrame frame, long now, List<Runnable> deferred) {
        Decoded decoded = decode(frame);
        if (decoded.command() instanceof Command.Reset reset) {
            processReset(new ResetTicket(reset, new CompletableFuture<>()), now, deferred);
        }
    }

    private void replayProtocol(ProtocolMessage command) {
        WireKey key = new WireKey(command.header().epoch(), command.header().id());
        ProtocolMessage ack = wireAdmissions.get(key);
        if (ack != null) { wire(ack); }
        if (wireStates.containsKey(key)) { wire(wireStates.get(key)); }
        if (wireTerminals.containsKey(key)) { wire(wireTerminals.get(key)); }
    }

    private boolean acceptControl(ProtocolMessage.Control command) {
        if (!identities.reserve(command)) { wire(new ProtocolMessage.Error(command.header(), ErrorCode.ID_CONFLICT)); return false; }
        admittedProtocol.put(command.header().id(), command);
        controls.put(command.kind(), command);
        var ack = new ProtocolMessage.Ack(command.header(), ProtocolMessage.AckKind.ACCEPTED, Optional.empty());
        wireAdmissions.put(new WireKey(command.header().epoch(), command.header().id()), ack); wire(ack);
        return true;
    }

    private void handleControl(ProtocolMessage.Control command) {
        var kind = command.kind();
        boolean valid = switch (kind) {
            case STATUS, SHUTDOWN -> true;
            case START -> !state.isResetting() && ((!protocolStarted && !stopRequested) || stopped && stopRequested);
            case STOP -> !stopRequested && !state.isResetting();
            case PAUSE -> pause != null && pause.isRequested();
            case RESUME -> pause != null && !pause.isRequested() && !stopRequested && !state.isResetting();
            case RESET -> false;
        };
        if (!valid || controls.containsKey(kind)) { wire(new ProtocolMessage.Error(command.header(), ErrorCode.INVALID_STATE)); return; }
        if (!acceptControl(command)) { return; }
        switch (kind) {
            case STATUS -> {
                controls.remove(kind);
                if (wireState(command.header())) { controlTerminal(command, ProtocolMessage.TerminalKind.COMPLETED, Optional.empty(), Optional.empty()); }
                else { controlTerminal(command, ProtocolMessage.TerminalKind.FAILED, Optional.empty(), Optional.of(ErrorCode.MESSAGE_TOO_LONG)); }
            }
            case START -> { protocolStarted = true; if (stopRequested) { resume(); } }
            case STOP -> requestStop();
            case SHUTDOWN -> { lifecycle = Lifecycle.CLOSING; available.signalAll(); }
            default -> { }
        }
    }

    private void completeControls() {
        var ready = new ArrayList<ProtocolMessage.Control>();
        for (var command : controls.values()) {
            boolean complete = switch (command.kind()) {
                case START -> !stopRequested && !resumeRequested;
                case STOP -> stopped;
                case PAUSE -> pauseView;
                case RESUME -> !pauseView && pause != null && !pause.isRequested();
                default -> false;
            };
            if (complete) { ready.add(command); }
        }
        for (var command : ready) { controls.remove(command.kind()); controlCompleted(command); }
    }

    private void cancelControls(ProtocolMessage.CancelReason reason) {
        for (var command : List.copyOf(controls.values())) {
            if (command.kind() != ProtocolMessage.ControlKind.SHUTDOWN) {
                controls.remove(command.kind());
                controlTerminal(command, ProtocolMessage.TerminalKind.CANCELLED, Optional.of(reason), Optional.empty());
            }
        }
    }

    private void controlCompleted(ProtocolMessage.Control command) {
        if (command.kind() != ProtocolMessage.ControlKind.SHUTDOWN) { wireState(command.header()); }
        controlTerminal(command, ProtocolMessage.TerminalKind.COMPLETED, Optional.empty(), Optional.empty());
    }
    private void controlTerminal(ProtocolMessage.Control command, ProtocolMessage.TerminalKind kind,
            Optional<ProtocolMessage.CancelReason> reason, Optional<ErrorCode> error) {
        wire(new ProtocolMessage.Terminal(command.header(), kind, reason, error));
    }

    private boolean wireState(ProtocolMessage.Header header) {
        var snapshot = capture(nowNanos()).elevator();
        var internal = snapshot.cabinRequests().stream().map(Request::getFloor).distinct().sorted().toList();
        var external = snapshot.hallRequests().stream()
                .map(r -> new ProtocolMessage.ExternalCall(r.getFloor(), r.getDirection())).distinct()
                .sorted(java.util.Comparator.comparingInt(ProtocolMessage.ExternalCall::floor)
                        .thenComparing(ProtocolMessage.ExternalCall::direction)).toList();
        long nanos = snapshot.remainingNanos();
        var dto = new ProtocolMessage.State(header, snapshot.revision(), snapshot.currentFloor(), snapshot.nextFloor(),
                snapshot.direction(), snapshot.phase(), snapshot.suspendedPhase(),
                snapshot.holdReason().map(r -> ProtocolMessage.HoldReason.valueOf(r.name())),
                ProtocolMessage.Mode.valueOf(snapshot.mode().name()), nanos == 0 ? 0 : (nanos - 1) / 1_000_000 + 1,
                internal, external);
        try { wire(dto); return true; }
        catch (ProtocolCodec.EncodingException error) { return false; }
    }

    private TextFrame encode(Command command) {
        Objects.requireNonNull(command, "command");
        Instant createdAt = command instanceof Command.Pickup pickup ? pickup.request().getCreatedAt()
                : command instanceof Command.Destination destination ? destination.request().getCreatedAt() : Instant.EPOCH;
        return new TextFrame(protocol.encodeSyntax(ProtocolCommands.fromDomain(command)), createdAt);
    }

    /** Solo se invoca en el hilo del actor, también al rechazar entradas abandonadas. */
    private Decoded decode(TextFrame frame) {
        ProtocolDecodeResult parsed = protocol.decodeCommand(frame.text());
        ProtocolReception previous = protocolReception.get();
        protocolReception.set(new ProtocolReception(previous.frames() + 1,
                previous.malformed() + (parsed.isSuccess() ? 0 : 1), Thread.currentThread().getName()));
        processingOrigin = frame.origin();
        if (parsed.isSuccess()) {
            ProtocolMessage message = parsed.message().orElseThrow();
            if (frame.origin() != Origin.LEGACY) {
                boolean authorized = frame.origin() == Origin.MANAGER ? message instanceof ProtocolMessage.Pickup
                        : !(message instanceof ProtocolMessage.Pickup);
                if (!authorized) { wire(new ProtocolMessage.Error(message.header(), ErrorCode.UNAUTHORIZED_CONTROL)); return new Decoded(null, null); }
                if (message.header().elevatorId() != state.getId()) { wire(new ProtocolMessage.Error(message.header(), ErrorCode.UNKNOWN_ELEVATOR)); return new Decoded(null, null); }
                if (identities.conflicts(message)) { wire(new ProtocolMessage.Error(message.header(), ErrorCode.ID_CONFLICT)); return new Decoded(null, null); }
                ProtocolMessage previousCommand = admittedProtocol.get(message.header().id());
                if (previousCommand != null) {
                    if (!previousCommand.equals(message)) { wire(new ProtocolMessage.Error(message.header(), ErrorCode.ID_CONFLICT)); }
                    else { replayProtocol(message); }
                    return new Decoded(null, null);
                }
                boolean reset = message instanceof ProtocolMessage.Control c && c.kind() == ProtocolMessage.ControlKind.RESET;
                boolean shutdown = message instanceof ProtocolMessage.Control c && c.kind() == ProtocolMessage.ControlKind.SHUTDOWN;
                long expected = state.getEpoch();
                if (reset && message.header().epoch() == expected) {
                    wire(new ProtocolMessage.Error(message.header(), ErrorCode.INVALID_STATE)); return new Decoded(null, null);
                }
                if (reset && expected < Long.MAX_VALUE) { expected++; }
                if (!shutdown && message.header().epoch() != expected) {
                    wire(new ProtocolMessage.Error(message.header(), message.header().epoch() < expected
                            ? ErrorCode.STALE_EPOCH : ErrorCode.FUTURE_EPOCH)); return new Decoded(null, null);
                }
            }
            if (message instanceof ProtocolMessage.Control control && control.kind() != ProtocolMessage.ControlKind.RESET) {
                if (!frame.control() || frame.origin() != Origin.CONTROLLER) {
                    wire(new ProtocolMessage.Error(message.header(), frame.origin() == Origin.MANAGER
                            ? ErrorCode.UNAUTHORIZED_CONTROL : ErrorCode.INVALID_STATE));
                } else { handleControl(control); }
                return new Decoded(null, null);
            }
            if (frame.control() && !(message instanceof ProtocolMessage.Control)) {
                wire(new ProtocolMessage.Error(message.header(), ErrorCode.INVALID_STATE)); return new Decoded(null, null);
            }
            if (message instanceof ProtocolMessage.Pickup || message instanceof ProtocolMessage.Destination
                    || message instanceof ProtocolMessage.Control control
                    && control.kind() == ProtocolMessage.ControlKind.RESET) {
                return new Decoded(ProtocolCommands.toDomain(message, frame.createdAt()), null);
            }
            wire(new ProtocolMessage.Error(message.header(), ErrorCode.INVALID_STATE));
            return new Decoded(null, null); // Controles adicionales se integran en T65.
        }
        ProtocolDecodeResult.Failure error = parsed.failure().orElseThrow();
        // Conserva Result/ExecutionEvent para clientes tipados antiguos, reconstruidos
        // del texto; el DTO de esta segunda lectura NUNCA se admite ni mueve la cabina.
        ProtocolDecodeResult syntax = protocol.decodeCommandSyntax(frame.text());
        if (syntax.isSuccess()) {
            ProtocolMessage message = syntax.message().orElseThrow();
            if (message instanceof ProtocolMessage.Pickup || message instanceof ProtocolMessage.Destination
                    || message instanceof ProtocolMessage.Control control
                    && control.kind() == ProtocolMessage.ControlKind.RESET) {
                Command command = ProtocolCommands.toDomain(message, frame.createdAt());
                Result rejected = new Result(command, Outcome.REJECTED, error.detail());
                emitAdmission(rejected, nowNanos(), error.code());
                return new Decoded(null, rejected);
            }
        }
        wire(error.asErrorMessage());
        return new Decoded(null, null);
    }

    private Result rejectServiceControl(Command command, long now) {
        Result rejected = new Result(command, Outcome.REJECTED, "RESET requiere el buzón prioritario.");
        emitAdmission(rejected, now, ErrorCode.INVALID_STATE);
        return rejected;
    }

    private void processResetFrame(ResetFrame frame, long now, List<Runnable> deferred) {
        Decoded decoded = decode(frame.frame());
        if (decoded.command() instanceof Command.Reset reset) {
            processReset(new ResetTicket(reset, frame.response()), now, deferred);
        } else {
            Result rejected = decoded.rejection();
            if (decoded.command() != null) {
                rejected = new Result(decoded.command(), Outcome.REJECTED, "El buzón prioritario solo admite RESET.");
                emitAdmission(rejected, now, ErrorCode.INVALID_STATE);
            }
            Result result = rejected;
            if (result != null) { deferred.add(() -> results.add(result)); }
            deferred.add(() -> frame.response().completeExceptionally(
                    new IllegalArgumentException("Mensaje RESET inválido; consultar respuesta de protocolo.")));
        }
    }

    private void rejectAbandoned(TextFrame frame) {
        Decoded decoded = decode(frame);
        if (decoded.command() != null) {
            Result rejected = new Result(decoded.command(), Outcome.REJECTED, "Trabajador finalizado.");
            emitAdmission(rejected, nowNanos(), ErrorCode.WORKER_UNAVAILABLE);
            results.add(rejected);
        } else if (decoded.rejection() != null) { results.add(decoded.rejection()); }
    }

    private ErrorCode rejectionCode(Command command) {
        if (command.elevatorId() != state.getId()) { return ErrorCode.UNKNOWN_ELEVATOR; }
        if (command.epoch() < state.getEpoch()) { return ErrorCode.STALE_EPOCH; }
        if (command instanceof Command.Reset) { return ErrorCode.INVALID_STATE; }
        if (command.epoch() > state.getEpoch()) { return ErrorCode.FUTURE_EPOCH; }
        return ErrorCode.ID_CONFLICT;
    }

    private static ProtocolMessage.Header header(Command command) {
        return new ProtocolMessage.Header(command.commandId(), command.epoch(), command.elevatorId());
    }

    private void wire(ProtocolMessage message) {
        if (message instanceof ProtocolMessage.State state && state.header().id() > 0) {
            wireStates.put(new WireKey(state.header().epoch(), state.header().id()), state);
        }
        if (message instanceof ProtocolMessage.Terminal terminal) {
            wireTerminals.putIfAbsent(new WireKey(terminal.header().epoch(), terminal.header().id()), terminal);
        }
        protocolBatch.add(protocol.encode(message));
    }

    private void completed(Command command) {
        if (command instanceof Command.Reset) { wireState(header(command)); }
        wire(new ProtocolMessage.Terminal(header(command), ProtocolMessage.TerminalKind.COMPLETED,
                Optional.empty(), Optional.empty()));
    }

    /** Admisión del control: cierre > reset > Stop > servicio. Solo escribe el actor. */
    private void processReset(ResetTicket ticket, long now, List<Runnable> deferred) {
        Command.Reset command = ticket.command();
        Origin resetOrigin = processingOrigin;
        if (command.elevatorId() != state.getId() || !command.validateAgainst(state.getConfig()).isValid()) {
            rejectReset(ticket, "Reset dirigido a otra cabina.", now, deferred);
            return;
        }
        if (lastReset != null && lastReset.command().sameContent(command)) {
            var replay = new Result(command, Outcome.DUPLICATE, "Repetición de reset sin reiniciar retorno.",
                    Optional.empty(), Optional.empty(), true);
            emitAdmission(replay, now);
            deferred.add(() -> results.add(replay));
            var operation = lastReset;
            deferred.add(() -> operation.completion().whenComplete((snapshot, error) -> {
                if (error == null) { ticket.response().complete(snapshot); }
                else { ticket.response().completeExceptionally(error); }
            }));
            return;
        }
        if ((state.isResetting() && processingOrigin == Origin.LEGACY) || command.epoch() <= state.getEpoch()) {
            rejectReset(ticket, "Reset en curso o época no creciente.", now, deferred);
            return;
        }
        if (processingOrigin != Origin.LEGACY && !identities.reserve(ProtocolCommands.fromDomain(command))) {
            var rejected = new Result(command, Outcome.REJECTED, "ID de sesión en conflicto.");
            emitAdmission(rejected, now, ErrorCode.ID_CONFLICT); deferred.add(() -> results.add(rejected));
            deferred.add(() -> ticket.response().completeExceptionally(new IllegalArgumentException("ID_CONFLICT"))); return;
        }
        if (state.isResetting()) {
            var oldReset = lastReset;
            wire(new ProtocolMessage.Terminal(header(oldReset.command()), ProtocolMessage.TerminalKind.CANCELLED,
                    Optional.of(ProtocolMessage.CancelReason.SUPERSEDED), Optional.empty()));
            deferred.add(() -> oldReset.completion().completeExceptionally(new IllegalStateException("Reset sustituido.")));
        }
        cancelControls(ProtocolMessage.CancelReason.SUPERSEDED);
        // Cancelaciones conservan el snapshot y la correlación de la época anterior.
        for (var cancelled : ledger.clearForReset()) {
            eventBatch.add(new ExecutionEvent(++eventSequence, ExecutionEvent.Type.COMMAND_CANCELLED,
                    state.snapshot(now), Optional.of(cancelled.command()),
                    Optional.of(cancelled.canonicalRequestId()), "Cancelado por reset prioritario."));
            wire(new ProtocolMessage.Terminal(header(cancelled.command()), ProtocolMessage.TerminalKind.CANCELLED,
                    Optional.of(ProtocolMessage.CancelReason.RESET), Optional.empty()));
        }
        while (!inbox.isEmpty()) {
            Decoded decoded = decode(inbox.removeFirst());
            if (decoded.command() != null) {
                var rejected = new Result(decoded.command(), Outcome.REJECTED, "Descartado del inbox por reset.");
                emitAdmission(rejected, now, ErrorCode.STALE_EPOCH);
                deferred.add(() -> results.add(rejected));
            } else if (decoded.rejection() != null) {
                deferred.add(() -> results.add(decoded.rejection()));
            }
        }
        if (stopConfirmation != null && !stopConfirmation.isDone()) {
            var replaced = stopConfirmation;
            deferred.add(() -> replaced.completeExceptionally(new IllegalStateException("Stop sustituido por reset.")));
        }
        processingOrigin = resetOrigin;
        stopRequested = false;
        stopped = false;
        resumeRequested = false;
        resetHeld = true;
        completedSweep = Optional.empty();
        if (state.isResetting()) { state.supersedeReset(command.epoch()); }
        else { state.beginReset(command.epoch()); }
        lastReset = new ResetOperation(command, new CompletableFuture<>());
        var operation = lastReset;
        deferred.add(() -> operation.completion().whenComplete((snapshot, error) -> {
            if (error == null) { ticket.response().complete(snapshot); }
            else { ticket.response().completeExceptionally(error); }
        }));
        published.set(capture(now));
        var accepted = new Result(command, Outcome.ACCEPTED, "Reset adoptado; retorno pendiente.");
        emitAdmission(accepted, now);
        emitReset(ExecutionEvent.Type.RESET_STARTED, now);
        deferred.add(() -> results.add(accepted));
        continueReset(now, deferred);
    }

    private void rejectReset(ResetTicket ticket, String detail, long now, List<Runnable> deferred) {
        var rejected = new Result(ticket.command(), Outcome.REJECTED, detail);
        emitAdmission(rejected, now);
        deferred.add(() -> results.add(rejected));
        deferred.add(() -> ticket.response().completeExceptionally(new IllegalArgumentException(detail)));
    }

    private void continueReset(long now, List<Runnable> deferred) {
        if (state.getPhase() == ElevatorPhase.MOVING) { return; }
        Direction previous = state.getDirection();
        if (state.getCurrentFloor() == 1) {
            state.finishReset();
            stopRequested = true;
            stopped = true;
            stopConfirmation = CompletableFuture.completedFuture(null);
            published.set(capture(now));
            emitDirectionChange(previous, now);
            emitReset(ExecutionEvent.Type.RESET_COMPLETED, now);
            var operation = lastReset;
            var confirmation = published.get().elevator();
            deferred.add(() -> operation.completion().complete(confirmation));
        } else {
            if (state.getCommittedFloor().isPresent()) { state.beginNextLeg(now); }
            else { state.beginJourney(1, now); }
            published.set(capture(now));
            emitDirectionChange(previous, now);
            emit(ExecutionEvent.Type.DEPARTED, now);
        }
    }

    private void emitReset(ExecutionEvent.Type type, long now) {
        if (type == ExecutionEvent.Type.RESET_COMPLETED) { completed(lastReset.command()); }
        eventBatch.add(new ExecutionEvent(++eventSequence, type, state.snapshot(now),
                Optional.of(lastReset.command()), Optional.empty(), ""));
    }

    /** Sección local breve: sin I/O, callbacks ni bloqueos de otras cabinas. */
    private Result admit(Command command) {
        if (lastReset != null && command.epoch() == lastReset.command().epoch()
                && command.commandId() == lastReset.command().commandId()) {
            var rejected = new Result(command, Outcome.REJECTED, "ID reservado por el reset de esta época.");
            emitAdmission(rejected, nowNanos());
            return rejected;
        }
        ProtocolMessage dto = ProtocolCommands.fromDomain(command);
        if (processingOrigin != Origin.LEGACY && !identities.reserve(dto)) {
            var rejected = new Result(command, Outcome.REJECTED, "ID de sesión en conflicto.");
            emitAdmission(rejected, nowNanos(), ErrorCode.ID_CONFLICT); return rejected;
        }
        CommandLedger.Decision decision = ledger.admit(command);
        if (processingOrigin != Origin.LEGACY && decision.kind() == CommandLedger.Kind.REJECTED) { identities.release(dto); }
        published.set(capture(nowNanos()));
        Outcome outcome = switch (decision.kind()) {
            case ADDED -> Outcome.ACCEPTED;
            case DUPLICATE -> Outcome.DUPLICATE;
            case REJECTED -> Outcome.REJECTED;
        };
        Result result = new Result(command, outcome, decision.detail(), decision.canonicalRequestId(),
                decision.requestStatus(), decision.replay());
        long now = nowNanos();
        emitAdmission(result, now);
        emitCompletions(now);
        return result;
    }

    /** Transiciones locales: sin callbacks ni entrega de eventos bajo el lock. */
    private void continueWork(long now, List<Service> notifications) {
        if (state.getPhase() == ElevatorPhase.MOVING) { return; }
        if (state.getPhase() == ElevatorPhase.DWELLING) {
            List<Request> served = state.serveDuringDwell(now);
            if (!served.isEmpty()) { recordService(served, false, now, notifications); }
            return;
        }
        Direction previousDirection = state.getDirection();
        List<Request> served = state.beginDwell(state.getDirection(), now);
        if (!served.isEmpty()) {
            recordService(served, true, now, notifications);
            return;
        }
        if (state.getCommittedFloor().isPresent()) {
            completedSweep = Optional.empty();
            state.beginNextLeg(now);
        } else {
            served = state.beginDwell(state.getDirection().opposite(), now);
            if (!served.isEmpty()) {
                emitDirectionChange(previousDirection, now);
                recordService(served, true, now, notifications);
                return;
            }
            var target = DirectionalAgenda.selectTarget(state.getCurrentFloor(),
                    state.getPendingRequestsInAdmissionOrder(), completedSweep);
            if (target.isPresent()) { state.beginJourney(target.getAsInt(), now); }
            completedSweep = Optional.empty();
        }
        published.set(capture(now));
        emitDirectionChange(previousDirection, now);
        if (state.getPhase() == ElevatorPhase.MOVING) {
            emit(ExecutionEvent.Type.DEPARTED, now);
        }
    }

    private void recordService(List<Request> served, boolean openedStop, long now,
            List<Service> notifications) {
        ledger.markServed(served);
        published.set(capture(now));
        if (openedStop) { emit(ExecutionEvent.Type.STOP_STARTED, now); }
        emitCompletions(now);
        notifications.add(new Service(state.getId(), state.getEpoch(), state.getRevision(),
                state.getCurrentFloor(), state.getDirection(), now, openedStop, served));
    }

    private void emitAdmission(Result result, long now) {
        emitAdmission(result, now, rejectionCode(result.command()));
    }

    private void emitAdmission(Result result, long now, ErrorCode rejection) {
        WireKey key = new WireKey(result.command().epoch(), result.command().commandId());
        if (result.outcome() == Outcome.REJECTED) {
            wire(new ProtocolMessage.Error(header(result.command()), rejection));
        } else {
            if (processingOrigin != Origin.LEGACY) {
                ProtocolMessage dto = ProtocolCommands.fromDomain(result.command());
                identities.reserve(dto); admittedProtocol.put(dto.header().id(), dto);
            }
            ProtocolMessage ack = wireAdmissions.get(key);
            if (!result.replay() || ack == null) {
                boolean coalesced = result.outcome() == Outcome.DUPLICATE && !result.replay()
                        && result.canonicalRequestId().isPresent();
                ack = new ProtocolMessage.Ack(header(result.command()), coalesced
                        ? ProtocolMessage.AckKind.COALESCED : ProtocolMessage.AckKind.ACCEPTED,
                        coalesced ? result.canonicalRequestId() : Optional.empty());
                wireAdmissions.put(key, ack);
            }
            wire(ack);
            if (result.replay() && wireTerminals.containsKey(key)) { wire(wireTerminals.get(key)); }
        }
        ExecutionEvent.Type type = result.replay() ? ExecutionEvent.Type.COMMAND_REPLAYED
                : switch (result.outcome()) {
                    case ACCEPTED -> ExecutionEvent.Type.COMMAND_ACCEPTED;
                    case DUPLICATE -> ExecutionEvent.Type.COMMAND_DUPLICATE;
                    case REJECTED -> ExecutionEvent.Type.COMMAND_REJECTED;
                };
        eventBatch.add(new ExecutionEvent(++eventSequence, type, state.snapshot(now),
                Optional.of(result.command()), result.canonicalRequestId(), result.detail()));
    }

    private void emitCompletions(long now) {
        for (CommandLedger.Completion completion : ledger.drainCompletions()) {
            completed(completion.command());
            eventBatch.add(new ExecutionEvent(++eventSequence,
                    ExecutionEvent.Type.COMMAND_COMPLETED, state.snapshot(now),
                    Optional.of(completion.command()), Optional.of(completion.canonicalRequestId()),
                    "Solicitud canónica atendida."));
        }
    }

    private void emitDirectionChange(Direction previous, long now) {
        if (previous != state.getDirection()) { emit(ExecutionEvent.Type.DIRECTION_CHANGED, now); }
    }

    private void emit(ExecutionEvent.Type type, long now) {
        ExecutionEvent event = new ExecutionEvent(++eventSequence, type, state.snapshot(now),
                Optional.empty(), Optional.empty(), "");
        eventBatch.add(event);
        var snapshot = event.snapshot();
        var h = new ProtocolMessage.Header(event.sequence(), event.epoch(), event.elevatorId());
        switch (type) {
            case DEPARTED -> wire(new ProtocolMessage.Move(h, snapshot.currentFloor(),
                    snapshot.nextFloor().orElseThrow(), snapshot.direction()));
            case ARRIVED -> wire(new ProtocolMessage.Arrived(h, snapshot.currentFloor()));
            case DIRECTION_CHANGED -> wire(new ProtocolMessage.DirectionChanged(h,
                    snapshot.direction().opposite(), snapshot.direction()));
            case STOP_STARTED -> wire(new ProtocolMessage.StoppedAt(h,
                    snapshot.currentFloor(), snapshot.direction()));
            default -> { }
        }
    }

    /** Sin lock del buzón, callbacks, I/O ni espera de consumidores. */
    private void flushEvents() {
        for (ExecutionEvent event : eventBatch) {
            events.add(event);
            trace.cabin(event, failure);
            for (var subscriber : subscribers) { subscriber.publish(event); }
        }
        eventBatch.clear();
        if (!admittedProtocol.isEmpty() && lifecycle == Lifecycle.RUNNING) {
            wireState(new ProtocolMessage.Header(0, state.getEpoch(), state.getId()));
        }
        for (String frame : protocolBatch) {
            protocolOutput.publish(frame);
            for (ProtocolMailbox subscriber : protocolSubscribers) { subscriber.publish(frame); }
        }
        protocolBatch.clear();
    }

    private static long timeoutNanos(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative()) { throw new IllegalArgumentException("Tiempo negativo."); }
        return timeout.toNanos();
    }
}
```

## src/main/java/com/elevatormanager/simulation/ProtocolMailbox.java

```java
package com.elevatormanager.simulation;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** Canal de cadenas con un consumidor lógico; publicar no ejecuta callbacks. */
public final class ProtocolMailbox {
    private final LinkedBlockingQueue<Optional<String>> queue = new LinkedBlockingQueue<>();

    // Solo los actores de este paquete producen respuestas del receptor.
    void publish(String frame) { queue.add(Optional.of(Objects.requireNonNull(frame, "frame"))); }

    /** Señal sin mensaje para entradas/cierre de un actor consumidor. */
    public void wake() { queue.add(Optional.empty()); }

    public Optional<String> await() throws InterruptedException {
        if (javax.swing.SwingUtilities.isEventDispatchThread()) { throw new IllegalStateException("No bloquear el EDT."); }
        return queue.take();
    }

    /** ZERO permite sondeo desde EDT. No esperar desde el hilo gráfico. */
    public Optional<String> await(Duration timeout) throws InterruptedException {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative()) { throw new IllegalArgumentException("Tiempo negativo."); }
        long nanos = timeout.toNanos();
        if (nanos > 0 && javax.swing.SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("No bloquear el EDT esperando protocolo.");
        }
        var frame = queue.poll(nanos, TimeUnit.NANOSECONDS);
        return frame == null ? Optional.empty() : frame;
    }
}
```

## src/main/java/com/elevatormanager/simulation/ProtocolResponsePump.java

```java
package com.elevatormanager.simulation;

import com.elevatormanager.protocol.ProtocolReplyTracker;
import java.time.Duration;

/** Un consumidor por sesión; ningún callback de futuros se ejecuta en una cabina. */
final class ProtocolResponsePump implements AutoCloseable {
    final ProtocolMailbox mailbox = new ProtocolMailbox();
    final ProtocolReplyTracker tracker;
    private final Thread thread;
    private volatile boolean closing;
    private boolean started;
    ProtocolResponsePump(ProtocolReplyTracker tracker) {
        this.tracker = tracker;
        thread = new Thread(this::run, "protocol-responses");
    }
    synchronized void start() { if (!started) { started = true; thread.start(); } }
    private void run() {
        try {
            while (true) {
                var frame = closing ? mailbox.await(Duration.ZERO) : mailbox.await();
                if (frame.isPresent()) { tracker.receive(frame.orElseThrow()); }
                else if (closing) { break; }
            }
        } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
        finally { tracker.close(); }
    }
    @Override public void close() { closing = true; mailbox.wake(); }
    boolean isConsumerThread() { return Thread.currentThread() == thread; }
    void join() throws InterruptedException { if (started) { thread.join(); } else { tracker.close(); } }
}
```

## src/main/java/com/elevatormanager/simulation/SimulationController.java

```java
package com.elevatormanager.simulation;

import com.elevatormanager.io.BatchDiagnostic;
import com.elevatormanager.simulation.time.PauseCoordinator;
import com.elevatormanager.io.BatchReadResult;
import com.elevatormanager.io.RequestFileReader;
import java.nio.file.Path;
import java.io.IOException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import com.elevatormanager.logging.SessionLog;
import com.elevatormanager.logging.TraceMailbox;
import com.elevatormanager.dispatch.HallCallSnapshot;
import com.elevatormanager.dispatch.ManagerWorker;
import com.elevatormanager.model.ElevatorSnapshot;
import com.elevatormanager.model.SimulationConfig;
import com.elevatormanager.model.SimulationState;
import com.elevatormanager.model.request.CabinRequest;
import com.elevatormanager.model.request.HallRequest;
import com.elevatormanager.model.request.Request;
import com.elevatormanager.protocol.Command;
import com.elevatormanager.protocol.ProtocolCodec;
import com.elevatormanager.protocol.ProtocolMessage;
import com.elevatormanager.protocol.ProtocolReplyTracker;
import com.elevatormanager.protocol.ProtocolIdentities;
import com.elevatormanager.protocol.ProtocolCommands;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import javax.swing.SwingUtilities;

/** T45: sesión con controles serializados e importación validada fuera del EDT. */
public final class SimulationController implements AutoCloseable {
    public enum Outcome { APPLIED, UNCHANGED, ENQUEUED, REJECTED }

    /** ENQUEUED confirma envío, nunca admisión local ni servicio físico. */
    public record Result(Outcome outcome, SimulationState state, String detail,
            Optional<Command.Destination> command, long epoch) { }

    @FunctionalInterface interface BatchLoader {
        BatchReadResult read(Path path, SimulationConfig config) throws Exception;
    }
    private final UUID sessionId = UUID.randomUUID();
    private final BatchLoader batchLoader;
    private final AtomicReference<Thread> importThread = new AtomicReference<>();
    private final ThreadPoolExecutor importExecutor = new ThreadPoolExecutor(1, 1, 0L,
            TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1), task -> {
                var thread = new Thread(task, "simulation-import-" + sessionId);
                importThread.set(thread);
                return thread;
            });
    private static final class PendingImport {
        final long generation;
        final long epoch;
        final CompletableFuture<BatchImportResult> completion;
        Future<?> task;
        PendingImport(long generation, long epoch, CompletableFuture<BatchImportResult> completion) {
            this.generation = generation; this.epoch = epoch; this.completion = completion;
        }
    }
    private volatile long importGeneration;
    private PendingImport pendingImport; // Solo lo modifica el coordinador.
    private volatile Optional<ImportedRequestBatch> preparedBatch = Optional.empty();
    private volatile Optional<BatchDispatchReport> lastBatchDispatch = Optional.empty();
    private final Optional<SessionLog> sessionLog;
    private final TraceMailbox trace;
    private final SimulationConfig config;
    private final ProtocolCodec protocol;
    private final ProtocolResponsePump responses;
    private CompletableFuture<Result> startCompletion;
    private CompletableFuture<Result> resumeCompletion;
    private volatile boolean starting;
    private final PauseCoordinator pauseCoordinator;
    private volatile long epoch;
    private final List<ElevatorWorker> elevators;
    private final ManagerWorker manager;
    private final ExecutionEventMailbox events = new ExecutionEventMailbox();
    private final AtomicLong commandIds = new AtomicLong();
    private final AtomicReference<Thread> coordinatorThread = new AtomicReference<>();
    private final ExecutorService coordinator = Executors.newSingleThreadExecutor(task -> {
        var thread = new Thread(task, "simulation-controller");
        coordinatorThread.set(thread);
        return thread;
    });
    private final AtomicBoolean closeRequested = new AtomicBoolean();
    private final AtomicBoolean shutdownStarted = new AtomicBoolean();
    private final CompletableFuture<Result> closeCompletion = new CompletableFuture<>();
    private final List<AutoCloseable> ownedResources = new java.util.ArrayList<>();
    private final Thread shutdownThread = new Thread(this::finishClose, "simulation-shutdown");
    private volatile SimulationState state = SimulationState.RESET;
    private volatile Throwable failure;
    private CompletableFuture<Result> pauseCompletion;
    private CompletableFuture<Result> stopCompletion;
    private CompletableFuture<Result> resetCompletion;
    private boolean workersStarted;

    public SimulationController(SimulationConfig config, long epoch) {
        this(config, epoch, Optional.empty(), new RequestFileReader()::read);
    }

    /** Transfiere el logging al controlador si la construcción termina correctamente. */
    public SimulationController(SimulationConfig config, long epoch, SessionLog sessionLog) {
        this(config, epoch, Optional.of(Objects.requireNonNull(sessionLog, "sessionLog")), new RequestFileReader()::read);
    }

    /** Punto de prueba del paquete; el lector de producción sigue siendo RequestFileReader. */
    static SimulationController withBatchLoader(SimulationConfig config, long epoch, BatchLoader loader) {
        return new SimulationController(config, epoch, Optional.empty(), loader);
    }

    private SimulationController(SimulationConfig config, long epoch, Optional<SessionLog> sessionLog,
            BatchLoader batchLoader) {
        this.batchLoader = Objects.requireNonNull(batchLoader, "batchLoader");
        this.sessionLog = sessionLog;
        this.config = Objects.requireNonNull(config, "config");
        protocol = new ProtocolCodec(config);
        responses = new ProtocolResponsePump(new ProtocolReplyTracker(config, epoch));
        if (epoch < 0) { throw new IllegalArgumentException("Época negativa."); }
        this.epoch = epoch;
        pauseCoordinator = new PauseCoordinator(Math.addExact(config.elevatorCount(), 1));
        trace = sessionLog.map(SessionLog::mailbox).orElse(TraceMailbox.disabled());
        elevators = java.util.stream.IntStream.rangeClosed(1, config.elevatorCount())
                .mapToObj(id -> new ElevatorWorker(id, config, epoch, trace, pauseCoordinator)).toList();
        var protocolIdentities = new ProtocolIdentities();
        elevators.forEach(e -> {
            e.useProtocolIdentities(protocolIdentities);
            e.subscribeEvents(events);
            e.subscribeProtocol(responses.mailbox);
        });
        manager = new ManagerWorker(config, epoch, elevators, this::nextCommandId, trace, pauseCoordinator);
        sessionLog.ifPresent(log -> {
            log.claim(config.elevatorCount());
            ownedResources.add(log);
        });
    }

    /** Vista reciente no destructiva; no consume awaitEvent ni afecta al log completo. */
    public Optional<com.elevatormanager.logging.RecentEventBuffer> recentLogEvents() {
        return sessionLog.map(SessionLog::recentEvents);
    }

    public SimulationConfig config() { return config; }
    /** Observación textual adicional; no consume el seguimiento tipado existente. */
    public void subscribeProtocol(ProtocolMailbox mailbox) {
        elevators.forEach(e -> e.subscribeProtocol(mailbox));
    }

    public void unsubscribeProtocol(ProtocolMailbox mailbox) {
        elevators.forEach(e -> e.unsubscribeProtocol(mailbox));
    }

    public List<ElevatorWorker.ProtocolReception> protocolReceptions() {
        return elevators.stream().map(ElevatorWorker::protocolReception).toList();
    }

    public long epoch() { return epoch; }

    /** T59: reloj activo compartido con referenceNanos de los snapshots.
     * Solo observación; consultar fuera del EDT al capturar el conjunto visual.
     */
    public long activeTimeNanos() { return pauseCoordinator.nowNanos(); }

    /** T60: referencia de tiempo activo para pintura; lectura volatile sin locks. */
    public com.elevatormanager.simulation.time.PausableClock.View timeView() {
        return pauseCoordinator.timeView();
    }
    public UUID sessionId() { return sessionId; }
    public Optional<ImportedRequestBatch> preparedBatch() { return preparedBatch; }
    public Optional<BatchDispatchReport> lastBatchDispatch() { return lastBatchDispatch; }
    public SimulationState state() { return state; }
    public Optional<Throwable> failure() {
        return failure != null ? Optional.of(failure) : sessionLog.flatMap(SessionLog::failure);
    }

    /** Copias inmutables por cabina; no representan un instante físico global único. */
    /** Lectura O(1) de una instantánea ya publicada, sin recorrer llamadas ni cabinas. */
    public ElevatorSnapshot snapshot(int elevatorId) {
        if (elevatorId < 1 || elevatorId > elevators.size()) throw new IllegalArgumentException("Cabina fuera de rango.");
        return elevators.get(elevatorId - 1).snapshot();
    }

    public List<ElevatorSnapshot> snapshots() {
        return elevators.stream().map(ElevatorWorker::snapshot).toList();
    }

    /** Vista de la época vigente, incluso mientras el manejador adopta el reset. */
    public Map<UUID, HallCallSnapshot> calls() {
        long currentEpoch = epoch;
        var current = new java.util.HashMap<UUID, HallCallSnapshot>();
        manager.calls().forEach((id, call) -> {
            if (call.request().getEpoch() == currentEpoch) { current.put(id, call); }
        });
        return Map.copyOf(current);
    }

    /** Observación de salud sin exponer los trabajadores ni sus canales de control. */
    public List<ElevatorWorker.Lifecycle> elevatorLifecycles() {
        return elevators.stream().map(ElevatorWorker::lifecycle).toList();
    }

    public ManagerWorker.Lifecycle managerLifecycle() { return manager.lifecycle(); }

    /** RESET arranca una vez; STOPPED continúa. Start repetido no reenvía trabajo. */
    public CompletableFuture<Result> start() {
        return scheduleAsync(() -> {
            if (state == SimulationState.RUNNING) {
                return CompletableFuture.completedFuture(result(Outcome.UNCHANGED, "La sesión ya está en ejecución."));
            }
            if (startCompletion != null && !startCompletion.isDone()) {
                return startCompletion.thenApply(done -> done.outcome() == Outcome.APPLIED
                        ? result(Outcome.UNCHANGED, "El arranque concurrente ya fue aplicado.") : done);
            }
            if (state != SimulationState.RESET && state != SimulationState.STOPPED) {
                return CompletableFuture.completedFuture(result(Outcome.REJECTED, "Start no está permitido en " + state));
            }
            boolean fromReset = state == SimulationState.RESET;
            boolean continuing = workersStarted;
            if (fromReset) { invalidateImport(BatchImportResult.Outcome.REJECTED, "Start invalidó la lectura pendiente."); }
            var completion = new CompletableFuture<Result>(); startCompletion = completion;
            starting = true; completion.whenComplete((done, error) -> starting = false);
            long expectedEpoch = epoch;
            try {
                startWorkersOnce();
                var tickets = controls(ProtocolMessage.ControlKind.START);
                CompletableFuture.allOf(tickets.toArray(CompletableFuture[]::new)).whenComplete((ignored, error) -> schedule(() -> {
                    if (completion.isDone() || epoch != expectedEpoch || closeRequested.get()
                            || (state != SimulationState.RESET && state != SimulationState.STOPPED)) {
                        completion.complete(result(Outcome.REJECTED, "Start fuera de vigencia."));
                    } else if (error != null) {
                        failure = error; completion.completeExceptionally(error); beginClose();
                    } else {
                        if (continuing && !manager.resume()) {
                            var cause = new IllegalStateException("Manejador no reanudable.");
                            failure = cause; completion.completeExceptionally(cause); beginClose();
                            return result(Outcome.REJECTED, "No se pudo reanudar el manejador.");
                        }
                        state = SimulationState.RUNNING; trace.managerState(epoch, "SIMULATION_RUNNING", null);
                        if (fromReset) { releasePreparedBatch(); }
                        completion.complete(result(Outcome.APPLIED, continuing ? "Continuación de trabajadores y pendientes existentes." : "Trabajadores iniciados una sola vez."));
                    }
                    return result(Outcome.UNCHANGED, "Confirmación START procesada.");
                }));
            } catch (RuntimeException | Error error) {
                failure = error; completion.completeExceptionally(error); beginClose();
            }
            return completion;
        });
    }

    /** Consulta por texto, correlacionada; no sustituye el snapshot preciso de animación. */
    public CompletableFuture<ProtocolMessage.State> queryStatus(int elevatorId) {
        if (closeRequested.get()) { return CompletableFuture.failedFuture(new IllegalStateException("Sesión cerrada.")); }
        var result = new CompletableFuture<ProtocolMessage.State>();
        schedule(() -> {
            if (!workersStarted || elevatorId < 1 || elevatorId > elevators.size() || closeRequested.get()) {
                result.completeExceptionally(new IllegalStateException("Canal de cabina no disponible."));
            } else {
                sendControl(elevatorId, ProtocolMessage.ControlKind.STATUS, epoch).whenComplete((reply, error) -> {
                    if (error != null) { result.completeExceptionally(error); }
                    else { result.complete(reply.state().orElseThrow()); }
                });
            }
            return result(Outcome.UNCHANGED, "STATUS solicitado.");
        }).whenComplete((receipt, error) -> {
            if (error != null) { result.completeExceptionally(error); }
            else if (receipt.outcome() == Outcome.REJECTED) { result.completeExceptionally(new IllegalStateException(receipt.detail())); }
        });
        return result.copy();
    }

    public Optional<ProtocolMessage.State> protocolState(int elevatorId) { return responses.tracker.state(elevatorId); }

    private CompletableFuture<ProtocolReplyTracker.Reply> sendControl(int elevatorId,
            ProtocolMessage.ControlKind kind, long generation) {
        return sendControl(new ProtocolMessage.Control(new ProtocolMessage.Header(nextCommandId(), generation, elevatorId), kind));
    }

    private CompletableFuture<ProtocolReplyTracker.Reply> sendControl(ProtocolMessage.Control command) {
        var future = responses.tracker.register(command);
        if (!elevators.get(command.header().elevatorId() - 1).controllerChannel().control(protocol.encode(command))) {
            responses.tracker.fail(command.header().id(), new IllegalStateException("Cabina no disponible."));
        }
        return future;
    }

    private List<CompletableFuture<ProtocolReplyTracker.Reply>> controls(ProtocolMessage.ControlKind kind) {
        var list = new java.util.ArrayList<CompletableFuture<ProtocolReplyTracker.Reply>>();
        for (int id = 1; id <= elevators.size(); id++) { list.add(sendControl(id, kind, epoch)); }
        return list;
    }

    /** Progreso de la barrera; leerlo no espera ni consume eventos. */
    public PauseCoordinator.Status pauseStatus() { return pauseCoordinator.status(); }

    /** RUNNING -> PAUSING -> PAUSED. El resultado público no cancela la operación privada. */
    public CompletableFuture<Result> pause() {
        return scheduleAsync(() -> {
            if (starting) { return startCompletion.thenCompose(ignored -> pause()); }
            if (state == SimulationState.PAUSING) { return pauseCompletion; }
            if (state == SimulationState.PAUSED) {
                return CompletableFuture.completedFuture(result(Outcome.UNCHANGED, "La sesión ya está pausada."));
            }
            if (state != SimulationState.RUNNING) {
                return CompletableFuture.completedFuture(result(Outcome.REJECTED, "Pausa no permitida en " + state));
            }
            state = SimulationState.PAUSING;
            trace.managerState(epoch, "SIMULATION_PAUSING", null);
            var completion = new CompletableFuture<Result>();
            pauseCompletion = completion;
            var nativeBarrier = pauseCoordinator.requestPause();
            var pauseTickets = controls(ProtocolMessage.ControlKind.PAUSE);
            var allPause = new java.util.ArrayList<CompletableFuture<?>>();
            allPause.add(nativeBarrier); allPause.addAll(pauseTickets);
            var barrier = CompletableFuture.allOf(allPause.toArray(CompletableFuture[]::new));
            manager.wakeForPause();
            elevators.forEach(ElevatorWorker::wakeForPause);
            barrier.whenComplete((ignored, error) -> schedule(() -> {
                if (state != SimulationState.PAUSING || pauseCompletion != completion || completion.isDone()) {
                    return result(Outcome.UNCHANGED, "Confirmación de pausa fuera de vigencia.");
                }
                if (error != null || manager.lifecycle() != ManagerWorker.Lifecycle.RUNNING
                        || elevators.stream().anyMatch(e -> e.lifecycle() != ElevatorWorker.Lifecycle.RUNNING)) {
                    Throwable cause = error != null ? error : new IllegalStateException("Participante de pausa no disponible.");
                    failure = cause;
                    completion.completeExceptionally(cause);
                    beginClose();
                } else {
                    state = SimulationState.PAUSED;
                    trace.managerState(epoch, "SIMULATION_PAUSED", null);
                    completion.complete(result(Outcome.APPLIED, "Manejador y todas las cabinas reconocieron la pausa."));
                }
                return result(Outcome.UNCHANGED, "Barrera de pausa procesada.");
            }));
            return completion;
        });
    }

    /** PAUSED -> RUNNING; libera a todos con el mismo reloj y sin reenviar comandos. */
    public CompletableFuture<Result> resume() {
        return scheduleAsync(() -> {
            if (state == SimulationState.RUNNING) { return CompletableFuture.completedFuture(result(Outcome.UNCHANGED, "La sesión ya está activa.")); }
            if (state != SimulationState.PAUSED) { return CompletableFuture.completedFuture(result(Outcome.REJECTED, "Reanudar requiere PAUSED: " + state)); }
            state = SimulationState.RUNNING; trace.managerState(epoch, "SIMULATION_RUNNING", null);
            if (!pauseCoordinator.resume()) { throw new IllegalStateException("Barrera de reanudación incompleta."); }
            var completion = new CompletableFuture<Result>(); resumeCompletion = completion;
            long generation = epoch;
            var replies = controls(ProtocolMessage.ControlKind.RESUME);
            CompletableFuture.allOf(replies.toArray(CompletableFuture[]::new)).whenComplete((ignored, error) -> schedule(() -> {
                if (state != SimulationState.RUNNING || epoch != generation) {
                    completion.complete(result(Outcome.REJECTED, "Reanudación sustituida por otro control."));
                } else if (error != null) { failure = error; completion.completeExceptionally(error); beginClose(); }
                else { trace.managerState(epoch, "SIMULATION_RESUMED", null); completion.complete(result(Outcome.APPLIED, "Mismos actores, comandos y tiempo restante; ejecución reanudada.")); }
                return result(Outcome.UNCHANGED, "RESUME procesado.");
            }));
            return completion;
        });
    }

    /** RUNNING -> STOPPING -> STOPPED; el futuro confirma toda la barrera. */
    public CompletableFuture<Result> stop() {
        return scheduleAsync(() -> {
            if (starting) { return startCompletion.thenCompose(ignored -> stop()); }
            if (state == SimulationState.STOPPING) { return stopCompletion; }
            if (state == SimulationState.RESET || state == SimulationState.STOPPED) {
                return CompletableFuture.completedFuture(result(Outcome.UNCHANGED, "No hay avance que detener."));
            }
            if (state != SimulationState.RUNNING && state != SimulationState.PAUSING && state != SimulationState.PAUSED) {
                return CompletableFuture.completedFuture(result(Outcome.REJECTED, "Stop no permitido en " + state));
            }
            boolean fromPause = state == SimulationState.PAUSING || state == SimulationState.PAUSED;
            state = SimulationState.STOPPING;
            trace.managerState(epoch, "SIMULATION_STOPPING", null);
            var completion = new CompletableFuture<Result>();
            stopCompletion = completion;
            var acknowledgements = new java.util.ArrayList<CompletableFuture<Void>>();
            acknowledgements.add(manager.requestStop());
            for (int id = 1; id <= elevators.size(); id++) {
                acknowledgements.add(sendControl(id, ProtocolMessage.ControlKind.STOP, epoch).thenApply(reply -> null));
            }
            if (fromPause) { supersedePause("STOP"); }
            CompletableFuture.allOf(acknowledgements.toArray(CompletableFuture[]::new))
                    .whenComplete((ignored, error) -> schedule(() -> {
                        if (state == SimulationState.STOPPING && stopCompletion == completion) {
                            if (error != null) {
                                failure = error;
                                completion.completeExceptionally(error);
                                beginClose();
                            } else {
                                state = SimulationState.STOPPED;
                                trace.managerState(epoch, "SIMULATION_STOPPED", null);
                                completion.complete(result(Outcome.APPLIED, "Despacho y cabinas detenidos; pendientes conservados."));
                            }
                        }
                        return result(Outcome.UNCHANGED, "Confirmación de control procesada.");
                    }));
            return completion;
        });
    }

    /** Solo coordinador: se llama DESPUÉS de encolar el control en los N+1 actores. */
    private void supersedePause(String control) {
        if (pauseCompletion != null && !pauseCompletion.isDone()) {
            pauseCompletion.complete(result(Outcome.REJECTED, "Pausa sustituida por " + control + "."));
        }
        trace.managerState(epoch, "PAUSE_SUPERSEDED_BY_" + control, null);
        pauseCoordinator.releaseForControl("Pausa sustituida por " + control);
    }

    /** Una época por operación; RESET se confirma solo tras limpieza y N retornos. */
    public CompletableFuture<Result> reset() {
        return scheduleAsync(() -> {
            if (state == SimulationState.RESETTING) { return resetCompletion; }
            if (state != SimulationState.RESET && state != SimulationState.RUNNING
                    && state != SimulationState.STOPPING && state != SimulationState.STOPPED
                    && state != SimulationState.PAUSING && state != SimulationState.PAUSED) {
                return CompletableFuture.completedFuture(result(Outcome.REJECTED, "Reset no permitido en " + state));
            }
            boolean fromPause = state == SimulationState.PAUSING || state == SimulationState.PAUSED;
            final long targetEpoch;
            final List<Command.Reset> commands;
            try {
                targetEpoch = Math.incrementExact(epoch);
                var controls = new java.util.ArrayList<Command.Reset>();
                for (int id = 1; id <= elevators.size(); id++) {
                    controls.add(new Command.Reset(nextCommandId(), targetEpoch, id));
                }
                commands = List.copyOf(controls);
            } catch (ArithmeticException error) {
                return CompletableFuture.completedFuture(result(Outcome.REJECTED, "Época o IDs agotados; cerrar la sesión."));
            }
            var completion = new CompletableFuture<Result>();
            resetCompletion = completion;
            try {
                invalidateImport(BatchImportResult.Outcome.REJECTED, "Reset invalidó la lectura pendiente.");
                preparedBatch = Optional.empty();
                lastBatchDispatch = Optional.empty();
                startWorkersOnce();
                state = SimulationState.RESETTING;
                epoch = targetEpoch;
                responses.tracker.adoptEpoch(targetEpoch);
                trace.managerState(epoch, "SIMULATION_RESETTING", null);
                if (stopCompletion != null && !stopCompletion.isDone()) {
                    stopCompletion.complete(result(Outcome.REJECTED, "Stop sustituido por reset global."));
                }
                var managerAck = manager.requestReset(targetEpoch);
                var cabinAcks = new java.util.ArrayList<CompletableFuture<ElevatorSnapshot>>();
                for (int i = 0; i < elevators.size(); i++) {
                    final int index = i;
                    var command = (ProtocolMessage.Control) ProtocolCommands.fromDomain(commands.get(i));
                    cabinAcks.add(sendControl(command).thenApply(reply -> {
                        var proof = reply.state().orElseThrow();
                        if (proof.header().epoch() != targetEpoch || proof.header().elevatorId() != index + 1) {
                            throw new IllegalStateException("Confirmación textual de otra cabina/época.");
                        }
                        return elevators.get(index).snapshot();
                    }));
                }
                if (fromPause) { supersedePause("RESET"); }
                var participants = new java.util.ArrayList<CompletableFuture<?>>();
                participants.add(managerAck);
                participants.addAll(cabinAcks);
                // Un participante fallido dispara cierre; no espera a un viaje largo de otro.
                for (var participant : participants) {
                    participant.whenComplete((value, error) -> {
                        if (error != null) {
                            schedule(() -> finishReset(completion, targetEpoch, error, managerAck, cabinAcks));
                        }
                    });
                }
                CompletableFuture.allOf(participants.toArray(CompletableFuture[]::new))
                        .whenComplete((ignored, error) -> schedule(() ->
                                finishReset(completion, targetEpoch, error, managerAck, cabinAcks)));
            } catch (RuntimeException | Error error) {
                failure = error;
                completion.completeExceptionally(error);
                beginClose();
            }
            return completion;
        });
    }

    /** Solo se llama en el coordinador. Las lecturas getNow nunca esperan. */
    private Result finishReset(CompletableFuture<Result> completion, long targetEpoch, Throwable error,
            CompletableFuture<Long> managerAck, List<CompletableFuture<ElevatorSnapshot>> cabinAcks) {
        if (state != SimulationState.RESETTING || epoch != targetEpoch
                || resetCompletion != completion || completion.isDone()) {
            return result(Outcome.UNCHANGED, "Confirmación de reset fuera de vigencia.");
        }
        try {
            if (error != null) { throw new IllegalStateException("Participante de reset fallido.", error); }
            if (!Long.valueOf(targetEpoch).equals(managerAck.getNow(null)) || manager.epoch() != targetEpoch
                    || manager.lifecycle() != ManagerWorker.Lifecycle.RUNNING || !manager.calls().isEmpty()) {
                throw new IllegalStateException("El manejador no confirmó limpieza de la época vigente.");
            }
            if (cabinAcks.size() != elevators.size()) { throw new IllegalStateException("Barrera incompleta."); }
            for (int i = 0; i < cabinAcks.size(); i++) {
                var snapshot = cabinAcks.get(i).getNow(null);
                if (snapshot == null || snapshot.id() != i + 1 || snapshot.epoch() != targetEpoch
                        || snapshot.currentFloor() != 1 || snapshot.direction() != com.elevatormanager.model.Direction.UP
                        || snapshot.phase() != com.elevatormanager.model.ElevatorPhase.IDLE
                        || snapshot.mode() != ElevatorSnapshot.Mode.NORMAL || snapshot.nextFloor().isPresent()
                        || snapshot.committedFloor().isPresent() || !snapshot.cabinRequests().isEmpty()
                        || !snapshot.hallRequests().isEmpty()
                        || elevators.get(i).lifecycle() != ElevatorWorker.Lifecycle.RUNNING) {
                    throw new IllegalStateException("Cabina sin retorno físico confirmado en la época vigente.");
                }
            }
            state = SimulationState.RESET;
            trace.managerState(epoch, "SIMULATION_RESET", null);
            completion.complete(result(Outcome.APPLIED, "Manejador limpio y todas las cabinas en 1/UP; esperando Start."));
        } catch (RuntimeException | Error exception) {
            failure = exception;
            completion.completeExceptionally(exception);
            beginClose();
        }
        return result(Outcome.UNCHANGED, "Confirmación de reset procesada.");
    }

    private void startWorkersOnce() {
        if (workersStarted) { return; }
        responses.start();
        for (var elevator : elevators) {
            if (!elevator.start()) { throw new IllegalStateException("Cabina ya iniciada."); }
        }
        if (!manager.start()) { throw new IllegalStateException("Manejador ya iniciado."); }
        workersStarted = true;
    }

    /** Valida y enruta usando Request y su validación polimórfica. */
    public CompletableFuture<Result> submit(Request request) {
        Objects.requireNonNull(request, "request");
        return schedule(() -> submitNow(request));
    }

    private Result submitNow(Request request) {
        if (closeRequested.get()) { return result(Outcome.REJECTED, "Cierre solicitado; solicitud no enviada."); }
        if (!state.acceptsManualRequests()) {
            return result(Outcome.REJECTED, "Las solicitudes requieren RUNNING: " + state);
        }
        if (request.getEpoch() != epoch) {
            return result(Outcome.REJECTED, "La solicitud pertenece a otra época.");
        }
        var validation = request.validateAgainst(config);
        if (!validation.isValid()) {
            return result(Outcome.REJECTED, validation.errors().toString());
        }
        if (request instanceof HallRequest hall) {
            return result(manager.submit(hall) ? Outcome.ENQUEUED : Outcome.REJECTED,
                    "Envío al manejador; consultar seguimiento de llamadas.");
        }
        if (request instanceof CabinRequest cabin) {
            var command = new Command.Destination(nextCommandId(), cabin.getElevatorId(), cabin);
            boolean sent = elevators.get(cabin.getElevatorId() - 1).controllerChannel().send(
                    protocol.encode(ProtocolCommands.fromDomain(command)), cabin.getCreatedAt());
            return new Result(sent ? Outcome.ENQUEUED : Outcome.REJECTED, state,
                    "Envío a cabina; la aceptación y el servicio se notifican por eventos.",
                    sent ? Optional.of(command) : Optional.empty(), epoch);
        }
        return result(Outcome.REJECTED, "Tipo de solicitud no soportado.");
    }


    /** Apto para EDT: solo agenda captura de contexto; toda lectura se realiza en el ejecutor I/O. */
    public CompletableFuture<BatchImportResult> importRequests(Path path) {
        Objects.requireNonNull(path, "path");
        var completion = new CompletableFuture<BatchImportResult>();
        executeImportAction(() -> beginImport(path, completion), completion);
        return completion.copy();
    }

    /** Cancelación explícita de la lectura vigente; conserva el lote previamente confirmado. */
    public CompletableFuture<BatchImportResult> cancelImport() {
        var completion = new CompletableFuture<BatchImportResult>();
        executeImportAction(() -> {
            invalidateImport(BatchImportResult.Outcome.CANCELLED, "Lectura cancelada explícitamente.");
            completion.complete(importResult(BatchImportResult.Outcome.CANCELLED, importGeneration,
                    "Cancelación procesada; lote confirmado conservado.", Optional.empty(), List.of()));
        }, completion);
        return completion.copy();
    }

    /** Revalidar también al aplicar una respuesta diferida en la interfaz. */
    public boolean isCurrent(BatchImportResult response) {
        Objects.requireNonNull(response, "response");
        return sessionId.equals(response.sessionId()) && response.generation() == importGeneration
                && response.epoch() == epoch && response.state() == state && !closeRequested.get();
    }

    private void executeImportAction(Runnable action, CompletableFuture<BatchImportResult> completion) {
        try {
            coordinator.execute(() -> {
                if (closeRequested.get()) {
                    completion.complete(importRejected("Cierre solicitado; importación descartada."));
                    return;
                }
                try { action.run(); }
                catch (RuntimeException | Error error) { completion.completeExceptionally(error); }
            });
        } catch (RejectedExecutionException error) {
            completion.complete(importRejected("La sesión está cerrándose o cerrada."));
        }
    }

    private void beginImport(Path path, CompletableFuture<BatchImportResult> completion) {
        if (starting || !state.allowsRequestImport()) {
            completion.complete(importRejected("La importación requiere RESET: " + state));
            return;
        }
        invalidateImport(BatchImportResult.Outcome.SUPERSEDED, "Sustituida por una lectura más reciente.");
        var pending = new PendingImport(importGeneration, epoch, completion);
        pendingImport = pending;
        try {
            pending.task = importExecutor.submit(() -> {
                BatchReadResult parsed;
                try { parsed = Objects.requireNonNull(batchLoader.read(path, config), "Resultado nulo del lector."); }
                catch (Throwable error) {
                    if (error instanceof InterruptedException) { Thread.currentThread().interrupt(); }
                    Exception cause = error instanceof Exception exception ? exception : new IOException("Fallo del lector.", error);
                    parsed = BatchReadResult.invalid(List.of(new BatchDiagnostic(BatchDiagnostic.Code.READ_ERROR,
                            java.util.OptionalInt.empty(), "No se pudo completar la lectura del lote.",
                            "Archivo legible y lector disponible.", Optional.of(cause))));
                }
                BatchReadResult result = parsed;
                try { coordinator.execute(() -> completeImport(pending, result)); }
                catch (RejectedExecutionException error) {
                    // beginClose ya resolvió la operación; no se confirma nada desde el hilo I/O.
                    pending.completion.complete(importRejected("Cierre antes de confirmar la lectura."));
                }
            });
        } catch (RejectedExecutionException error) {
            pendingImport = null;
            completion.complete(importRejected("Ejecutor de lectura no disponible; lote anterior conservado."));
        }
    }

    /** Único punto de confirmación; no se ejecuta I/O ni se admiten solicitudes aquí. */
    private void completeImport(PendingImport pending, BatchReadResult parsed) {
        if (pendingImport != pending || pending.generation != importGeneration) { return; }
        pendingImport = null;
        if (closeRequested.get() || starting || state != SimulationState.RESET || epoch != pending.epoch) {
            pending.completion.complete(importRejected("El contexto cambió durante la lectura; lote no aplicado."));
            return;
        }
        if (!parsed.isValid()) {
            pending.completion.complete(importResult(BatchImportResult.Outcome.INVALID, pending.generation,
                    "Archivo rechazado; lote confirmado anterior conservado.", Optional.empty(), parsed.diagnostics()));
            return;
        }
        if (!parsed.batch().orElseThrow().config().equals(config)) {
            pending.completion.complete(importRejected("Configuración del lote incompatible con la sesión."));
            return;
        }
        try {
            var confirmed = ImportedRequestBatch.confirm(parsed.batch().orElseThrow(), epoch);
            if (closeRequested.get()) {
                pending.completion.complete(importRejected("Cierre solicitado antes de publicar el lote confirmado."));
                return;
            }
            preparedBatch = Optional.of(confirmed); // Sustitución completa tras construir y validar.
            pending.completion.complete(importResult(BatchImportResult.Outcome.IMPORTED, pending.generation,
                    "Lote confirmado en RESET; no se ejecuta hasta Start.", Optional.of(confirmed), List.of()));
        } catch (RuntimeException | Error error) { pending.completion.completeExceptionally(error); }
    }

    /** Invalida antes de interrumpir; un lector tardío jamás puede recuperar su contexto. */
    private void invalidateImport(BatchImportResult.Outcome outcome, String detail) {
        importGeneration = Math.incrementExact(importGeneration);
        PendingImport old = pendingImport;
        pendingImport = null;
        if (old != null) {
            old.completion.complete(importResult(outcome, old.generation, detail, Optional.empty(), List.of()));
            if (old.task != null) { old.task.cancel(true); }
        }
        importExecutor.purge();
    }

    /** Solo Start efectivo desde RESET entra aquí, antes de otras admisiones del coordinador. */
    private void releasePreparedBatch() {
        Optional<ImportedRequestBatch> pending = preparedBatch;
        preparedBatch = Optional.empty(); // Consumido incluso si posteriormente falla algún envío.
        if (pending.isEmpty()) { return; }
        ImportedRequestBatch batch = pending.orElseThrow();
        var entries = new java.util.ArrayList<BatchDispatchReport.Entry>();
        try {
            for (int i = 0; i < batch.requests().size(); i++) {
                Request request = batch.requests().get(i);
                entries.add(new BatchDispatchReport.Entry(batch.source().entries().get(i).lineNumber(),
                        request.getId(), submitNow(request)));
            }
        } catch (RuntimeException | Error error) {
            for (int i = entries.size(); i < batch.requests().size(); i++) {
                entries.add(new BatchDispatchReport.Entry(batch.source().entries().get(i).lineNumber(),
                        batch.requests().get(i).getId(), result(Outcome.REJECTED, "Envío interrumpido por fallo; no se reintentará el lote.")));
            }
            lastBatchDispatch = Optional.of(new BatchDispatchReport(batch, entries));
            throw error;
        }
        lastBatchDispatch = Optional.of(new BatchDispatchReport(batch, entries));
    }

    private BatchImportResult importRejected(String detail) {
        return importResult(BatchImportResult.Outcome.REJECTED, importGeneration, detail, Optional.empty(), List.of());
    }
    private BatchImportResult importResult(BatchImportResult.Outcome outcome, long generation,
            String detail, Optional<ImportedRequestBatch> batch, List<BatchDiagnostic> diagnostics) {
        return new BatchImportResult(outcome, state, epoch, sessionId, generation, detail, batch, diagnostics);
    }

    /** Consumidor de la época vigente. ZERO permite sondeo en EDT sin esperar. */
    public Optional<ExecutionEvent> awaitEvent(Duration timeout) throws InterruptedException {
        long budget = checkedNanos(timeout);
        long began = System.nanoTime();
        int discarded = 0;
        while (true) {
            var event = events.await(Duration.ofNanos(remaining(began, budget)));
            if (event.isEmpty() || isCurrent(event.orElseThrow())) { return event; }
            // No prolongar el plazo ni drenar un historial enorme en un sondeo EDT.
            if ((budget > 0 && remaining(began, budget) == 0)
                    || (budget == 0 && ++discarded == 256)) { return Optional.empty(); }
        }
    }

    public boolean isCurrent(Result response) {
        Objects.requireNonNull(response, "response");
        long currentEpoch = epoch;
        return response.epoch() == currentEpoch
                && response.command().map(command -> command.epoch() == currentEpoch).orElse(true);
    }

    /** Revalidar también al aplicar un evento ya extraído antes de un reset concurrente. */
    public boolean isCurrent(ExecutionEvent event) {
        Objects.requireNonNull(event, "event");
        long currentEpoch = epoch;
        return event.epoch() == currentEpoch
                && event.command().map(command -> command.epoch() == currentEpoch).orElse(true);
    }

    /** APPLIED transfiere propiedad; un recurso rechazado sigue siendo del llamador. */
    public CompletableFuture<Result> registerResource(AutoCloseable resource) {
        Objects.requireNonNull(resource, "resource");
        return schedule(() -> {
            if (ownedResources.stream().anyMatch(known -> known == resource)) {
                return result(Outcome.UNCHANGED, "Recurso ya registrado por identidad.");
            }
            ownedResources.add(resource);
            return result(Outcome.APPLIED, "Recurso incorporado al cierre de la sesión.");
        });
    }

    /** Solicitud idempotente; no espera actores ni recursos en el hilo llamador. */
    @Override public void close() { closeAsync(); }

    /** El futuro confirma liberación real. La copia visible no cancela el cierre privado. */
    public CompletableFuture<Result> closeAsync() {
        if (closeRequested.compareAndSet(false, true)) {
            try { coordinator.execute(this::beginClose); }
            catch (RejectedExecutionException exception) {
                // Un fallo del motor pudo iniciar el mismo cierre entre el CAS y execute.
                if (!shutdownStarted.get()) {
                    failure = exception;
                    closeCompletion.completeExceptionally(exception);
                }
            }
        }
        return closeCompletion.copy();
    }

    private void beginClose() {
        closeRequested.set(true);
        if (!shutdownStarted.compareAndSet(false, true)) { return; }
        state = SimulationState.CLOSING;
        trace.managerState(epoch, "SIMULATION_CLOSING", null);
        invalidateImport(BatchImportResult.Outcome.REJECTED, "Cierre invalidó la lectura pendiente.");
        preparedBatch = Optional.empty();
        importExecutor.shutdownNow();
        if (resetCompletion != null && !resetCompletion.isDone()) {
            resetCompletion.complete(result(Outcome.REJECTED, "Reset interrumpido por cierre."));
        }
        if (stopCompletion != null && !stopCompletion.isDone()) {
            stopCompletion.complete(result(Outcome.REJECTED, "Stop interrumpido por cierre."));
        }
        if (pauseCoordinator.isRequested()) { trace.managerState(epoch, "PAUSE_SUPERSEDED_BY_CLOSE", null); }
        if (pauseCompletion != null && !pauseCompletion.isDone()) {
            pauseCompletion.complete(result(Outcome.REJECTED, "Pausa interrumpida por cierre."));
        }
        if (startCompletion != null && !startCompletion.isDone()) {
            startCompletion.complete(result(Outcome.REJECTED, "Start interrumpido por cierre."));
        }
        if (resumeCompletion != null && !resumeCompletion.isDone()) {
            resumeCompletion.complete(result(Outcome.REJECTED, "Reanudación interrumpida por cierre."));
        }
        if (workersStarted) {
            try { controls(ProtocolMessage.ControlKind.SHUTDOWN); }
            catch (ArithmeticException exhausted) { failure = exhausted; } // El cierre nativo no depende de otro ID.
        }
        // Cierre primero la compuerta: ningún checkpoint liberado admite más servicio.
        pauseCoordinator.close();
        manager.close();
        elevators.forEach(ElevatorWorker::close);
        coordinator.shutdown();
        try { shutdownThread.start(); }
        catch (RuntimeException | Error exception) {
            failure = exception;
            closeCompletion.completeExceptionally(exception);
        }
    }

    /** Propiedad de recursos transferida al cerrador tras terminar el coordinador. */
    private void finishClose() {
        boolean interrupted = false;
        try {
            boolean coordinatorEnded = false;
            while (!coordinatorEnded) {
                try { coordinatorEnded = coordinator.awaitTermination(1, TimeUnit.DAYS); }
                catch (InterruptedException exception) { interrupted = true; }
            }
            boolean managerEnded = false;
            while (!managerEnded) {
                try { managerEnded = manager.awaitTermination(Duration.ofDays(1)); }
                catch (InterruptedException exception) { interrupted = true; }
            }
            for (var elevator : elevators) {
                boolean ended = false;
                while (!ended) {
                    try { ended = elevator.awaitTermination(Duration.ofDays(1)); }
                    catch (InterruptedException exception) { interrupted = true; }
                }
            }
            responses.close();
            boolean protocolEnded = false;
            while (!protocolEnded) {
                try { responses.join(); protocolEnded = true; }
                catch (InterruptedException exception) { interrupted = true; }
            }
            boolean importsEnded = false;
            while (!importsEnded) {
                try { importsEnded = importExecutor.awaitTermination(1, TimeUnit.DAYS); }
                catch (InterruptedException exception) { interrupted = true; }
            }
            var errors = new java.util.ArrayList<Throwable>();
            // Orden inverso de adquisición; intentar todos aunque falle uno.
            for (int i = ownedResources.size() - 1; i >= 0; i--) {
                try { ownedResources.get(i).close(); }
                catch (Exception | LinkageError exception) { errors.add(exception); }
            }
            if (!errors.isEmpty()) {
                var error = new IllegalStateException("No se confirmó la liberación de todos los recursos.");
                errors.forEach(error::addSuppressed);
                throw error;
            }
            ownedResources.clear();
            state = SimulationState.CLOSED;
            closeCompletion.complete(result(Outcome.APPLIED, "Trabajadores terminados y recursos liberados."));
        } catch (RuntimeException | Error exception) {
            if (failure != null && failure != exception) { exception.addSuppressed(failure); }
            failure = exception;
            closeCompletion.completeExceptionally(exception);
        } finally {
            if (interrupted) { Thread.currentThread().interrupt(); }
        }
    }

    /** Espera acotada de cierre real y salida del cerrador; nunca publica CLOSED por timeout. */
    public boolean awaitTermination(Duration timeout) throws InterruptedException {
        long budget = checkedNanos(timeout);
        if (budget > 0 && (Thread.currentThread() == coordinatorThread.get()
                || Thread.currentThread() == shutdownThread || Thread.currentThread() == importThread.get()
                || responses.isConsumerThread())) {
            throw new IllegalStateException("No esperar el cierre desde un hilo que debe terminarlo.");
        }
        if (!closeRequested.get()) { return false; }
        long began = System.nanoTime();
        try { closeCompletion.get(budget, TimeUnit.NANOSECONDS); }
        catch (TimeoutException | ExecutionException exception) { return false; }
        long remaining = remaining(began, budget);
        if (remaining > 0) { TimeUnit.NANOSECONDS.timedJoin(shutdownThread, remaining); }
        return state == SimulationState.CLOSED && coordinator.isTerminated() && importExecutor.isTerminated()
                && !shutdownThread.isAlive();
    }

    private CompletableFuture<Result> schedule(Supplier<Result> operation) {
        return scheduleAsync(() -> CompletableFuture.completedFuture(operation.get()));
    }

    private CompletableFuture<Result> scheduleAsync(Supplier<CompletionStage<Result>> operation) {
        var future = new CompletableFuture<Result>();
        try {
            coordinator.execute(() -> {
                if (closeRequested.get()) {
                    future.complete(result(Outcome.REJECTED, "Cierre solicitado; acción descartada."));
                    return;
                }
                try {
                    operation.get().whenComplete((value, error) -> {
                        if (error == null) { future.complete(value); }
                        else { future.completeExceptionally(error); }
                    });
                } catch (RuntimeException | Error exception) { future.completeExceptionally(exception); }
            });
        } catch (RejectedExecutionException exception) {
            future.complete(result(Outcome.REJECTED, "La sesión se está cerrando o está cerrada."));
        }
        return future;
    }

    private Result result(Outcome outcome, String detail) {
        return new Result(outcome, state, detail, Optional.empty(), epoch);
    }

    private long nextCommandId() {
        return commandIds.updateAndGet(previous -> Math.incrementExact(previous));
    }

    private static long remaining(long began, long budget) {
        return Math.max(0L, budget - (System.nanoTime() - began));
    }

    private static long checkedNanos(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative()) { throw new IllegalArgumentException("Tiempo negativo."); }
        long nanos = timeout.toNanos();
        if (nanos > 0 && SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("No esperar desde el EDT.");
        }
        return nanos;
    }
}
```

## src/main/java/com/elevatormanager/simulation/time/PauseCoordinator.java

```java
package com.elevatormanager.simulation.time;

import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * T55: barrera de N+1 actores sobre un único reloj de tiempo activo.
 * ID 0: manejador; IDs 1..N: cabinas. Los callbacks y la finalización de
 * futuros ocurren fuera del lock; ningún actor espera con su lock retenido.
 */
public final class PauseCoordinator implements AutoCloseable {
    public record Status(long generation, boolean requested, int acknowledged, int participants) { }

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition changed = lock.newCondition();
    private final PausableClock clock = new PausableClock();
    private final boolean[] acknowledged;
    private final boolean[] terminated;
    private long generation;
    private long controlWake;
    private int count;
    private boolean requested;
    private boolean closed;
    private CompletableFuture<Void> completion;

    public PauseCoordinator(int participants) {
        if (participants < 2) { throw new IllegalArgumentException("Se requiere manejador y alguna cabina."); }
        acknowledged = new boolean[participants];
        terminated = new boolean[participants];
    }

    /** Marcas lógicas: compartir esta instancia entre estado, snapshots y estimador. */
    public long nowNanos() { return clock.nowNanos(); }

    /** T60: publicación temporal O(1), sin adquirir los locks de barrera/reloj. */
    public PausableClock.View timeView() { return clock.view(); }

    public Status status() {
        lock.lock();
        try { return new Status(generation, requested, count, acknowledged.length); }
        finally { lock.unlock(); }
    }

    /** Congela el tiempo antes de solicitar reconocimiento a los actores. */
    public CompletableFuture<Void> requestPause() {
        lock.lock();
        try {
            if (closed) { return CompletableFuture.failedFuture(new IllegalStateException("Barrera cerrada.")); }
            if (requested) { return completion.copy(); }
            for (boolean ended : terminated) {
                if (ended) {
                    return CompletableFuture.failedFuture(new IllegalStateException("Participante terminado."));
                }
            }
            generation = Math.incrementExact(generation);
            Arrays.fill(acknowledged, false);
            count = 0;
            completion = new CompletableFuture<>();
            clock.pause();
            requested = true;
            return completion.copy();
        } finally { lock.unlock(); }
    }

    public boolean isRequested() {
        lock.lock();
        try { return requested; }
        finally { lock.unlock(); }
    }

    /**
     * Punto seguro del actor. Publica su captura congelada antes de reconocer,
     * espera sin sondeo y restaura la captura al salir por reanudación.
     * false indica cierre. Los callbacks pertenecen al propio hilo del actor.
     */
    public boolean checkpoint(int participant, Runnable onPaused, Runnable onResumed)
            throws InterruptedException {
        return checkpoint(participant, onPaused, onResumed, () -> true);
    }

    /** Señal de controles: no libera el reloj ni el reconocimiento de la pausa. */
    public void wakeControls() {
        lock.lock();
        try { controlWake++; changed.signalAll(); }
        finally { lock.unlock(); }
    }

    public boolean checkpoint(int participant, Runnable onPaused, Runnable onResumed, java.util.function.BooleanSupplier onControl)
            throws InterruptedException {
        checkId(participant);
        Objects.requireNonNull(onControl, "onControl");
        Objects.requireNonNull(onPaused, "onPaused");
        Objects.requireNonNull(onResumed, "onResumed");
        long ticket;
        lock.lockInterruptibly();
        try {
            if (closed) { return false; }
            if (!requested) { return true; }
            ticket = generation;
        } finally { lock.unlock(); }

        onPaused.run();
        CompletableFuture<Void> ready = null;
        lock.lockInterruptibly();
        try {
            if (!closed && requested && generation == ticket && !acknowledged[participant]) {
                acknowledged[participant] = true;
                if (++count == acknowledged.length) { ready = completion; }
            }
        } finally { lock.unlock(); }
        if (ready != null) { ready.complete(null); }

        while (true) {
            long observed;
            lock.lockInterruptibly();
            try {
                if (closed) { return false; }
                if (!requested || generation != ticket) { break; }
                observed = controlWake;
            } finally { lock.unlock(); }
            if (!onControl.getAsBoolean()) { return false; }
            lock.lockInterruptibly();
            try {
                if (!closed && requested && generation == ticket && observed == controlWake) { changed.await(); }
            } finally { lock.unlock(); }
        }
        onResumed.run();
        return true;
    }

    /** Liberación común, solo después de reconocer TODOS los participantes. */
    public boolean resume() {
        lock.lock();
        try {
            if (closed || !requested || count != acknowledged.length) { return false; }
            for (boolean ended : terminated) { if (ended) { return false; } }
            clock.resume();
            requested = false;
            changed.signalAll();
            return true;
        } finally { lock.unlock(); }
    }

    /**
     * T56: libera una pausa incluso parcial por un control de mayor prioridad.
     * El llamador debe ENCOLAR Stop/Reset en todos los actores antes de invocar.
     * No equivale a Resume: el control prioritario gobierna la siguiente iteración.
     */
    public boolean releaseForControl(String reason) {
        Objects.requireNonNull(reason, "reason");
        CompletableFuture<Void> pending;
        lock.lock();
        try {
            if (closed || !requested) { return false; }
            clock.resume();
            requested = false;
            changed.signalAll();
            pending = completion;
        } finally { lock.unlock(); }
        pending.completeExceptionally(new java.util.concurrent.CancellationException(reason));
        return true;
    }

    /** Un actor finalizado no puede dejar una barrera pendiente para siempre. */
    public void participantTerminated(int participant, Throwable failure) {
        checkId(participant);
        CompletableFuture<Void> pending;
        lock.lock();
        try {
            terminated[participant] = true;
            pending = requested ? completion : null;
        } finally { lock.unlock(); }
        if (pending != null) {
            pending.completeExceptionally(new IllegalStateException("Participante " + participant + " terminado.", failure));
        }
    }

    /** Despierta actores y cancela la barrera; el reloj sigue legible para eventos de cierre. */
    @Override public void close() {
        CompletableFuture<Void> pending;
        lock.lock();
        try {
            if (closed) { return; }
            closed = true;
            requested = false;
            clock.resume();
            changed.signalAll();
            pending = completion;
        } finally { lock.unlock(); }
        if (pending != null) {
            pending.completeExceptionally(new IllegalStateException("Barrera interrumpida por cierre."));
        }
    }

    private void checkId(int id) {
        if (id < 0 || id >= acknowledged.length) { throw new IllegalArgumentException("Participante fuera de rango."); }
    }
}
```

## src/test/java/com/elevatormanager/simulation/ProtocolIntegrationScenarios.java

```java
package com.elevatormanager.simulation;

import com.elevatormanager.dispatch.HallCallSnapshot;
import com.elevatormanager.dispatch.HallRequestLedger;
import com.elevatormanager.model.Direction;
import com.elevatormanager.model.ElevatorPhase;
import com.elevatormanager.model.SimulationConfig;
import com.elevatormanager.model.SimulationState;
import com.elevatormanager.model.request.CabinRequest;
import com.elevatormanager.model.request.HallRequest;
import com.elevatormanager.protocol.Command;
import com.elevatormanager.protocol.ProtocolCodec;
import com.elevatormanager.protocol.ProtocolIdentities;
import com.elevatormanager.protocol.ProtocolReplyTracker;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import static com.elevatormanager.protocol.ProtocolMessage.*;
import com.elevatormanager.protocol.ProtocolMessage;

/** T65: correlación adversarial y actores reales; sin JUnit requerido por el main. */
public final class ProtocolIntegrationScenarios {
    private static final SimulationConfig CONFIG = new SimulationConfig(2, 8, 1000, 500);
    private static final ProtocolCodec CODEC = new ProtocolCodec(CONFIG);
    private static final Duration LIMIT = Duration.ofSeconds(6);
    private static final Header H = new Header(10, 0, 1);
    private ProtocolIntegrationScenarios() { }

    public static void ackAloneNeverCompletesTicket() {
        var t = tracker(); var f = t.register(control(H, ControlKind.STATUS)); feed(t, ack(H)); check(!f.isDone(), "ACK solo admite");
    }
    public static void statusRequiresStateAndCompletion() throws Exception {
        var t = tracker(); var f = t.register(control(H, ControlKind.STATUS)); feed(t, ack(H)); feed(t, state(H, 3, 1));
        check(!f.isDone(), "STATE sin terminal"); feed(t, done(H)); equal(1, get(f).state().orElseThrow().floor());
    }
    public static void completionBeforeAckIsIgnored() throws Exception {
        var t = tracker(); var f = t.register(control(H, ControlKind.STOP)); feed(t, done(H)); check(!f.isDone(), "Terminal prematuro");
        feed(t, ack(H)); feed(t, done(H)); get(f);
    }
    public static void stateBeforeAckCannotSatisfyStatus() throws Exception {
        var t = tracker(); var f = t.register(control(H, ControlKind.STATUS)); feed(t, state(H, 3, 1));
        check(t.state(1).isEmpty(), "STATE sin ACK no se instala"); feed(t, ack(H)); feed(t, done(H)); check(!f.isDone(), "Falta STATE posterior al ACK");
        feed(t, state(H, 3, 1)); feed(t, done(H)); get(f);
    }
    public static void wrongCommandIdCannotCompleteTicket() {
        var t = tracker(); var f = t.register(control(H, ControlKind.STOP)); Header other = new Header(11, 0, 1);
        feed(t, ack(other)); feed(t, done(other)); check(!f.isDone(), "ID exacto");
    }
    public static void wrongCabinCannotCompleteTicket() {
        var t = tracker(); var f = t.register(control(H, ControlKind.STOP)); Header other = new Header(10, 0, 2);
        feed(t, ack(other)); feed(t, done(other)); check(!f.isDone(), "Cabina exacta");
    }
    public static void wrongEpochCannotCompleteTicket() {
        var t = tracker(); var f = t.register(control(H, ControlKind.STOP)); Header other = new Header(10, 1, 1);
        feed(t, ack(other)); feed(t, done(other)); check(!f.isDone(), "Época exacta");
    }
    public static void duplicateTerminalCompletesOnlyOnce() throws Exception {
        var t = tracker(); var f = t.register(control(H, ControlKind.STOP)); var count = new java.util.concurrent.atomic.AtomicInteger();
        f.thenRun(count::incrementAndGet); feed(t, ack(H)); feed(t, done(H)); feed(t, done(H)); equal(1, count.get()); get(f);
    }
    public static void coalescedAckCannotAdmitControl() {
        var t = tracker(); var f = t.register(control(H, ControlKind.STOP));
        feed(t, new Ack(H, AckKind.COALESCED, Optional.of(id(1)))); feed(t, done(H)); check(!f.isDone(), "Un control no se coalesce");
    }
    public static void errorBeforeAckIsARealRejection() throws Exception {
        var t = tracker(); var f = t.register(control(H, ControlKind.STATUS)); feed(t, new ProtocolMessage.Error(H, ErrorCode.INVALID_STATE));
        check(failure(f) instanceof ProtocolReplyTracker.Rejected, "ERROR estructurado");
    }
    public static void failedTerminalFailsTicket() throws Exception {
        var t = tracker(); var f = t.register(control(H, ControlKind.STOP)); feed(t, ack(H));
        feed(t, new Terminal(H, TerminalKind.FAILED, Optional.empty(), Optional.of(ErrorCode.INTERNAL_ERROR))); failure(f);
    }
    public static void cancelledTerminalFailsTicket() throws Exception {
        var t = tracker(); var f = t.register(control(H, ControlKind.STOP)); feed(t, ack(H));
        feed(t, new Terminal(H, TerminalKind.CANCELLED, Optional.of(CancelReason.SUPERSEDED), Optional.empty())); failure(f);
    }
    public static void unknownErrorHeaderDoesNotRejectSomeoneElse() {
        var t = tracker(); var f = t.register(control(H, ControlKind.STOP));
        feed(t, new ProtocolMessage.Error(Header.UNKNOWN, ErrorCode.INVALID_FORMAT)); check(!f.isDone(), "Sin correlación no completar ajeno");
    }
    public static void malformedResponsesDoNotEscapeOrGenerateLoops() throws Exception {
        var t = tracker(); var f = t.register(control(H, ControlKind.STOP));
        for (String text : List.of("", "V2 ERROR", "V1 ACK 10 EPOCH 0 ELEVATOR 1 BOGUS")) { t.receive(text); }
        equal(3L, t.malformed()); feed(t, ack(H)); feed(t, done(H)); get(f);
    }
    public static void sameTicketCanBeReRegisteredWithoutNewExecution() throws Exception {
        var t = tracker(); var command = control(H, ControlKind.STOP); var first = t.register(command); var second = t.register(command);
        feed(t, ack(H)); feed(t, done(H)); equal(get(first), get(second));
    }
    public static void registeringChangedContentWithSameIdIsRejected() {
        var t = tracker(); t.register(control(H, ControlKind.STOP));
        illegal(() -> t.register(control(H, ControlKind.START)));
    }
    public static void cancellingPublicFutureDoesNotCancelPrivateTicket() throws Exception {
        var t = tracker(); var command = control(H, ControlKind.STOP); var publicCopy = t.register(command); publicCopy.cancel(true);
        feed(t, ack(H)); feed(t, done(H)); get(t.register(command));
    }
    public static void closeFailsOutstandingAndRefusesNewTickets() throws Exception {
        var t = tracker(); var f = t.register(control(H, ControlKind.STOP)); t.close(); failure(f);
        illegal(() -> t.register(control(new Header(11, 0, 1), ControlKind.STATUS)));
    }
    public static void twoCabinsRequireTheirOwnExactResponses() throws Exception {
        var t = tracker(); Header b = new Header(11, 0, 2); var aFuture = t.register(control(H, ControlKind.STOP));
        var bFuture = t.register(control(b, ControlKind.STOP)); feed(t, ack(H)); feed(t, done(H)); get(aFuture);
        check(!bFuture.isDone(), "N respuestas de la misma cabina no son N cabinas"); feed(t, ack(b)); feed(t, done(b)); get(bFuture);
    }
    public static void resetRequiresCorrelatedStateNotSpontaneousState() throws Exception {
        var t = tracker(); Header reset = new Header(20, 1, 1); t.adoptEpoch(1); var f = t.register(control(reset, ControlKind.RESET));
        feed(t, ack(reset)); feed(t, state(new Header(0, 1, 1), 4, 1)); feed(t, done(reset)); check(!f.isDone(), "Falta STATE del reset");
        feed(t, state(reset, 4, 1)); feed(t, done(reset)); get(f);
    }
    public static void resetCompletionAtAnotherFloorIsIgnored() throws Exception {
        var t = tracker(); Header reset = new Header(20, 1, 1); var f = t.register(control(reset, ControlKind.RESET));
        feed(t, ack(reset)); feed(t, state(reset, 4, 3)); feed(t, done(reset)); check(!f.isDone(), "No hay teleportación por terminal");
        feed(t, state(reset, 5, 1)); feed(t, done(reset)); get(f);
    }
    public static void oldEpochStateNeverOverwritesNewState() {
        var t = tracker(); t.adoptEpoch(1); feed(t, state(new Header(0, 1, 1), 4, 1));
        feed(t, state(new Header(0, 0, 1), Long.MAX_VALUE, 7)); equal(1, t.state(1).orElseThrow().floor());
    }
    public static void lowerRevisionStateDoesNotOverwriteNewerState() {
        var t = tracker(); feed(t, state(new Header(0, 0, 1), 5, 3)); feed(t, state(new Header(0, 0, 1), 4, 2));
        equal(3, t.state(1).orElseThrow().floor());
    }
    public static void equalRevisionMayPublishSmallerRemaining() {
        var t = tracker(); Header h = new Header(0, 0, 1); feed(t, moving(h, 5, 100)); feed(t, moving(h, 5, 80));
        equal(80L, t.state(1).orElseThrow().remainingMillis());
    }
    public static void unsolicitedCorrelatedStateIsIgnored() {
        var t = tracker(); feed(t, state(new Header(99, 0, 1), 5, 7)); check(t.state(1).isEmpty(), "No se solicitó ese ID");
    }
    public static void historicalReplyCanFinishWithoutInstallingOldState() throws Exception {
        var t = tracker(); var f = t.register(control(H, ControlKind.STATUS)); feed(t, ack(H)); t.adoptEpoch(1);
        feed(t, state(new Header(0, 1, 1), 1, 1)); feed(t, state(H, 100, 7)); feed(t, done(H));
        equal(7, get(f).state().orElseThrow().floor()); equal(1, t.state(1).orElseThrow().floor());
    }
    public static void replayAfterCompletedStatusCannotRollbackCache() throws Exception {
        var t = tracker(); var f = t.register(control(H, ControlKind.STATUS)); feed(t, ack(H)); feed(t, state(H, 3, 1)); feed(t, done(H)); get(f);
        feed(t, state(new Header(0, 0, 1), 4, 3)); feed(t, state(H, 3, 1)); equal(3, t.state(1).orElseThrow().floor());
    }
    public static void sessionIdentityIncludesDestinationAndEpoch() {
        var ids = new ProtocolIdentities(); var a = control(H, ControlKind.STATUS); check(ids.reserve(a), "Primera reserva");
        check(ids.conflicts(control(new Header(10, 0, 2), ControlKind.STATUS)), "Destinatario incluido");
        check(ids.conflicts(control(new Header(10, 1, 1), ControlKind.STATUS)), "Época incluida");
    }
    public static void rejectedReservationCannotRemoveAnotherOwner() {
        var ids = new ProtocolIdentities(); var a = control(H, ControlKind.STATUS); var b = control(H, ControlKind.STOP);
        ids.reserve(a); ids.release(b); check(ids.conflicts(b), "Liberación compara contenido completo");
    }
    public static void managerAckMeansAssignedNotServed() {
        var f = ledger(); f.ledger.onProtocol(ack(f.h)); equal(HallCallSnapshot.Status.ASSIGNED, f.status());
    }
    public static void managerArrivalCannotMeanService() {
        var f = ledger(); f.ledger.onProtocol(ack(f.h)); f.ledger.onProtocol(new Arrived(new Header(1, 0, 1), 3));
        equal(HallCallSnapshot.Status.ASSIGNED, f.status());
    }
    public static void managerRequiresCompatibleStopBeforeCompleted() {
        var f = ledger(); f.ledger.onProtocol(ack(f.h)); f.ledger.onProtocol(done(f.h)); equal(HallCallSnapshot.Status.ASSIGNED, f.status());
        f.ledger.onProtocol(new StoppedAt(new Header(1, 0, 1), 3, Direction.UP)); f.ledger.onProtocol(done(f.h)); equal(HallCallSnapshot.Status.SERVED, f.status());
    }
    public static void managerWrongDirectionStopCannotServeCall() {
        var f = ledger(); f.ledger.onProtocol(ack(f.h)); f.ledger.onProtocol(new StoppedAt(new Header(1, 0, 1), 3, Direction.DOWN));
        f.ledger.onProtocol(done(f.h)); equal(HallCallSnapshot.Status.ASSIGNED, f.status());
    }
    public static void managerOldAttemptResponseCannotAssignNewAttempt() {
        var f = ledger(); f.ledger.onProtocol(new ProtocolMessage.Error(f.h, ErrorCode.INVALID_STATE));
        var next = new Command.Pickup(11, 2, f.request); f.ledger.dispatched(f.request.getId(), next);
        f.ledger.onProtocol(ack(f.h)); equal(HallCallSnapshot.Status.DISPATCHED, f.status());
    }
    public static void managerOldEpochTerminalCannotServeNewEpoch() {
        var f = ledger(); f.ledger.onProtocol(ack(new Header(10, 1, 1))); equal(HallCallSnapshot.Status.DISPATCHED, f.status());
    }
    public static void managerDuplicateCompletionDoesNotReopenService() {
        var f = ledger(); f.ledger.onProtocol(ack(f.h)); f.ledger.onProtocol(new StoppedAt(new Header(1, 0, 1), 3, Direction.UP));
        f.ledger.onProtocol(done(f.h)); f.ledger.onProtocol(done(f.h)); equal(HallCallSnapshot.Status.SERVED, f.status());
    }
    public static void managerFailedServiceIsNotAutomaticallyReassigned() {
        var f = ledger(); f.ledger.onProtocol(ack(f.h)); f.ledger.onProtocol(new Terminal(f.h, TerminalKind.FAILED, Optional.empty(), Optional.of(ErrorCode.INTERNAL_ERROR)));
        equal(HallCallSnapshot.Status.UNCONFIRMED, f.status()); check(f.ledger.pending().isEmpty(), "No duplicar recogida incierta");
    }
    public static void statusRunsOnWorkerAndHasExactCorrelation() throws Exception {
        try (Rig r = new Rig()) { r.control(10, 0, ControlKind.STATUS); State s = r.replyState(10); equal(H, s.header()); equal(1, s.floor()); r.done(10); equal(r.w.threadName(), r.w.protocolReception().processedBy()); }
    }
    public static void statusWorksWhileSafelyStopped() throws Exception {
        try (Rig r = new Rig()) { r.control(10, 0, ControlKind.STOP); r.done(10); r.control(11, 0, ControlKind.STATUS); equal(1, r.replyState(11).floor()); r.done(11); }
    }
    public static void statusReplayPreservesOriginalSnapshot() throws Exception {
        try (Rig r = new Rig()) {
            r.control(10, 0, ControlKind.STATUS); State old = r.replyState(10); r.done(10);
            r.destination(20, 3); r.done(20); r.control(10, 0, ControlKind.STATUS); equal(old, r.replyState(10)); r.done(10); equal(3, r.w.snapshot().currentFloor());
        }
    }
    public static void controllerPortCannotSendPickup() throws Exception {
        try (Rig r = new Rig()) { r.w.controllerChannel().send(pickup(10), Instant.EPOCH); r.error(ErrorCode.UNAUTHORIZED_CONTROL); }
    }
    public static void managerPortCannotSendDestinationOrControl() throws Exception {
        try (Rig r = new Rig()) {
            r.w.managerChannel().send(destination(10, 3), Instant.EPOCH); r.error(ErrorCode.UNAUTHORIZED_CONTROL);
            r.w.managerChannel().control(CODEC.encode(control(H, ControlKind.STATUS))); r.error(ErrorCode.UNAUTHORIZED_CONTROL);
        }
    }
    public static void wrongRoleCannotReplayAnAuthorizedStatus() throws Exception {
        try (Rig r = new Rig()) {
            r.control(10, 0, ControlKind.STATUS); r.done(10);
            r.w.managerChannel().control(CODEC.encode(control(H, ControlKind.STATUS))); r.error(ErrorCode.UNAUTHORIZED_CONTROL);
        }
    }
    public static void sameIdWithChangedContentIsRejectedBeforeEpoch() throws Exception {
        try (Rig r = new Rig()) { r.control(10, 0, ControlKind.STATUS); r.done(10); r.control(10, 1, ControlKind.STATUS); r.error(ErrorCode.ID_CONFLICT); }
    }
    public static void sharedIdentityRegistryDetectsCrossCabinConflict() throws Exception {
        var registry = new ProtocolIdentities(); var a = new ElevatorWorker(1, CONFIG, 0); var b = new ElevatorWorker(2, CONFIG, 0);
        a.useProtocolIdentities(registry); b.useProtocolIdentities(registry); a.start(); b.start();
        try {
            a.controllerChannel().control(CODEC.encode(control(H, ControlKind.STATUS))); read(a, m -> m instanceof Terminal t && t.header().id() == 10);
            b.controllerChannel().control(CODEC.encode(control(new Header(10, 0, 2), ControlKind.STATUS)));
            read(b, m -> m instanceof ProtocolMessage.Error e && e.code() == ErrorCode.ID_CONFLICT);
        } finally { a.close(); b.close(); check(a.awaitTermination(LIMIT) && b.awaitTermination(LIMIT), "Join"); }
    }
    public static void strictResetRejectsSkippedOrEqualEpoch() throws Exception {
        try (Rig r = new Rig()) { r.control(10, 2, ControlKind.RESET); r.error(ErrorCode.FUTURE_EPOCH); r.control(11, 0, ControlKind.RESET); r.error(ErrorCode.INVALID_STATE); equal(0L, r.w.snapshot().epoch()); }
    }
    public static void resetPublishesFinalStateBeforeCompletion() throws Exception {
        try (Rig r = new Rig()) {
            r.destination(1, 3); r.done(1); r.control(10, 1, ControlKind.RESET); State state = r.replyState(10);
            equal(1, state.floor()); equal(Direction.UP, state.direction()); equal(1L, state.header().epoch()); r.done(10);
        }
    }
    public static void oldResetReplayCannotMoveOrChangeEpochAgain() throws Exception {
        try (Rig r = new Rig()) {
            r.control(10, 1, ControlKind.RESET); r.done(10); long revision = r.w.snapshot().revision();
            r.control(10, 1, ControlKind.RESET); r.done(10); equal(revision, r.w.snapshot().revision()); equal(1L, r.w.snapshot().epoch());
        }
    }
    public static void laterResetSupersedesReturnWithoutTeleportation() throws Exception {
        try (Rig r = new Rig()) {
            r.destination(1, 6); read(r.w, m -> m instanceof Move v && v.from() == 2);
            r.control(10, 1, ControlKind.RESET); read(r.w, m -> m instanceof Ack a && a.header().id() == 10);
            r.control(11, 2, ControlKind.RESET);
            Terminal old = (Terminal) read(r.w, m -> m instanceof Terminal t && t.header().id() == 10 && t.kind() == TerminalKind.CANCELLED);
            equal(Optional.of(CancelReason.SUPERSEDED), old.reason()); r.done(11); equal(2L, r.w.snapshot().epoch()); equal(1, r.w.snapshot().currentFloor());
        }
    }
    public static void strictStaleQueryCannotChangeCurrentState() throws Exception {
        try (Rig r = new Rig()) { r.control(10, 1, ControlKind.RESET); r.done(10); r.control(11, 0, ControlKind.STATUS); r.error(ErrorCode.STALE_EPOCH); equal(1L, r.w.snapshot().epoch()); }
    }
    public static void stopAndStartCommandsControlPhysicalWork() throws Exception {
        try (Rig r = new Rig()) {
            r.destination(1, 5); read(r.w, m -> m instanceof Move); r.control(10, 0, ControlKind.STOP);
            State stopped = r.replyState(10); r.done(10); equal(2, stopped.floor()); check(stopped.internal().contains(5), "Destino conservado");
            r.control(11, 0, ControlKind.START); r.done(11); r.done(1); equal(5, r.w.snapshot().currentFloor());
        }
    }
    public static void duplicateNewStopIsInvalidButReplayIsIdempotent() throws Exception {
        try (Rig r = new Rig()) { r.control(10, 0, ControlKind.STOP); r.done(10); r.control(11, 0, ControlKind.STOP); r.error(ErrorCode.INVALID_STATE); r.control(10, 0, ControlKind.STOP); r.done(10); }
    }
    public static void shutdownCommandTerminatesActualThread() throws Exception {
        try (Rig r = new Rig()) { r.control(10, 0, ControlKind.SHUTDOWN); r.done(10); check(r.w.awaitTermination(LIMIT), "SHUTDOWN no solo ACK"); }
    }
    public static void shutdownCanCloseAnotherEpoch() throws Exception {
        try (Rig r = new Rig()) { r.control(10, 999, ControlKind.SHUTDOWN); r.done(10); check(r.w.awaitTermination(LIMIT), "Cierre autorizado independiente de época"); }
    }
    public static void controllerStatusAndCacheUseParsedText() throws Exception {
        try (var owned = new OwnedController()) { var c = owned.c; get(c.start()); State status = get(c.queryStatus(1)); equal(1, status.floor()); equal(0L, status.header().epoch()); check(c.protocolState(1).isPresent(), "Caché pública de texto"); }
    }
    public static void statusDuringPauseDoesNotAdvanceClockOrRemaining() throws Exception {
        try (var owned = new OwnedController()) { var c = owned.c;
            get(c.start()); get(c.submit(new CabinRequest(id(1), 0, 6, Instant.EPOCH, 1)));
            await(() -> c.snapshot(1).phase() == ElevatorPhase.MOVING); get(c.pause());
            long clock = c.timeView().activeNanos(), remaining = c.snapshot(1).remainingNanos();
            State status = get(c.queryStatus(1)); equal(ElevatorPhase.HELD, status.phase()); equal(Optional.of(HoldReason.PAUSE), status.hold());
            equal(clock, c.timeView().activeNanos()); equal(remaining, c.snapshot(1).remainingNanos()); equal(SimulationState.PAUSED, c.state());
            get(c.resume()); equal(SimulationState.RUNNING, c.state());
        }
    }
    public static void resetFromPauseReturnsPhysicallyAndClearsCachedGeneration() throws Exception {
        try (var owned = new OwnedController()) { var c = owned.c;
            get(c.start()); get(c.submit(new CabinRequest(id(1), 0, 6, Instant.EPOCH, 1)));
            await(() -> c.snapshot(1).currentFloor() >= 3); get(c.pause()); get(c.queryStatus(1)); get(c.reset());
            equal(SimulationState.RESET, c.state()); equal(1L, c.epoch());
            State status = get(c.queryStatus(1)); equal(1, status.floor()); equal(1L, status.header().epoch()); check(status.internal().isEmpty(), "Sin servicio viejo");
        }
    }
    public static void resetWaitsForAllCabinReturnsNotJustAcks() throws Exception {
        try (var owned = new OwnedController()) { var c = owned.c;
            var mailbox = new ProtocolMailbox(); c.subscribeProtocol(mailbox); get(c.start());
            get(c.submit(new CabinRequest(id(1), 0, 6, Instant.EPOCH, 1))); await(() -> c.snapshot(1).currentFloor() >= 3);
            var reset = c.reset();
            long end = System.nanoTime() + LIMIT.toNanos(); boolean ack = false;
            while (!ack && System.nanoTime() < end) {
                var frame = mailbox.await(Duration.ofMillis(100));
                if (frame.isPresent()) { var m = CODEC.decodeResponse(frame.orElseThrow()).message().orElseThrow(); ack = m instanceof Ack && m.header().epoch() == 1; }
            }
            check(ack, "ACK de reset observado"); check(!reset.isDone(), "ACK no termina viaje de retorno"); get(reset);
            check(c.snapshots().stream().allMatch(s -> s.currentFloor() == 1 && s.epoch() == 1), "Barrera completa");
        }
    }
    public static void resetFromStoppedClearsPendingDestinations() throws Exception {
        try (var owned = new OwnedController()) { var c = owned.c;
            get(c.start()); get(c.submit(new CabinRequest(id(1), 0, 6, Instant.EPOCH, 1)));
            await(() -> c.snapshot(1).phase() == ElevatorPhase.MOVING); get(c.stop());
            check(get(c.queryStatus(1)).internal().contains(6), "Stop conserva servicio"); get(c.reset()); check(get(c.queryStatus(1)).internal().isEmpty(), "Reset elimina servicio");
        }
    }
    public static void controllerCloseInPauseJoinsResponseConsumer() throws Exception {
        var before = responseThreads(); var c = controller(); get(c.start()); get(c.pause()); get(c.queryStatus(1)); get(c.closeAsync());
        equal(SimulationState.CLOSED, c.state()); equal(before, responseThreads());
    }
    public static void queryingClosedControllerFailsWithoutHanging() throws Exception {
        var c = controller(); get(c.start()); get(c.closeAsync()); failure(c.queryStatus(1));
    }
    public static void invalidStatusTargetDoesNotAffectHealthySession() throws Exception {
        try (var owned = new OwnedController()) { var c = owned.c; get(c.start()); failure(c.queryStatus(99)); equal(1, get(c.queryStatus(1)).floor()); }
    }

    public static void queriesRacingCloseNeverLeaveFuturePending() throws Exception {
        try (var owned = new OwnedController()) {
            var c = owned.c; get(c.start());
            List<CompletableFuture<State>> queries = new ArrayList<>();
            for (int i = 0; i < 8; i++) { queries.add(c.queryStatus(1)); }
            get(c.closeAsync());
            for (var query : queries) {
                try { get(query); } catch (java.util.concurrent.ExecutionException expected) { }
                check(query.isDone(), "Consulta terminada con resultado o rechazo");
            }
        }
    }
    public static void stopQueuedBehindInitialStartStillStopsSession() throws Exception {
        try (var owned = new OwnedController()) {
            var c = owned.c; var start = c.start(); var stop = c.stop();
            get(start); equal(SimulationState.STOPPED, get(stop).state());
        }
    }

    private static final class OwnedController implements AutoCloseable {
        final SimulationController c = controller();
        @Override public void close() throws Exception {
            get(c.closeAsync()); check(c.awaitTermination(LIMIT), "Join del controlador completo");
        }
    }

    private static ProtocolReplyTracker tracker() { return new ProtocolReplyTracker(CONFIG, 0); }
    private static Control control(Header h, ControlKind k) { return new Control(h, k); }
    private static Ack ack(Header h) { return new Ack(h, AckKind.ACCEPTED, Optional.empty()); }
    private static Terminal done(Header h) { return new Terminal(h, TerminalKind.COMPLETED, Optional.empty(), Optional.empty()); }
    private static State state(Header h, long revision, int floor) {
        return new State(h, revision, floor, OptionalInt.empty(), Direction.UP, ElevatorPhase.IDLE,
                Optional.empty(), Optional.empty(), Mode.NORMAL, 0, List.of(), List.of());
    }
    private static State moving(Header h, long revision, long remaining) {
        return new State(h, revision, 2, OptionalInt.of(3), Direction.UP, ElevatorPhase.MOVING,
                Optional.empty(), Optional.empty(), Mode.NORMAL, remaining, List.of(), List.of());
    }
    private static void feed(ProtocolReplyTracker tracker, ProtocolMessage message) { tracker.receive(CODEC.encode(message)); }
    private static UUID id(long n) { return new UUID(0, n); }
    private static String destination(long n, int floor) { return CODEC.encode(new Destination(new Header(n, 0, 1), id(n), floor)); }
    private static String pickup(long n) { return CODEC.encode(new Pickup(new Header(n, 0, 1), id(n), 3, Direction.UP)); }
    private static SimulationController controller() { return new SimulationController(new SimulationConfig(2, 8, 150, 100), 0); }
    private static List<Long> responseThreads() {
        return Thread.getAllStackTraces().keySet().stream().filter(t -> t.isAlive() && t.getName().equals("protocol-responses")).map(Thread::getId).sorted().toList();
    }
    private static final class LedgerFixture {
        final HallRequest request = new HallRequest(id(1), 0, 3, Instant.EPOCH, Direction.UP);
        final HallRequestLedger ledger = new HallRequestLedger(CONFIG, 0);
        final Header h = H;
        LedgerFixture() { ledger.receive(request); ledger.dispatched(request.getId(), new Command.Pickup(10, 1, request)); }
        HallCallSnapshot.Status status() { return ledger.get(request.getId()).orElseThrow().status(); }
    }
    private static LedgerFixture ledger() { return new LedgerFixture(); }
    private static final class Rig implements AutoCloseable {
        final ElevatorWorker w = new ElevatorWorker(1, CONFIG, 0);
        Rig() { check(w.start(), "Arranque"); }
        void control(long n, long epoch, ControlKind kind) { check(w.controllerChannel().control(CODEC.encode(new Control(new Header(n, epoch, 1), kind))), "Control encolado"); }
        void destination(long n, int floor) { check(w.controllerChannel().send(ProtocolIntegrationScenarios.destination(n, floor), Instant.EPOCH), "Servicio encolado"); }
        State replyState(long n) throws Exception { return (State) read(w, m -> m instanceof State s && s.header().id() == n); }
        void done(long n) throws Exception { read(w, m -> m instanceof Terminal t && t.header().id() == n && t.kind() == TerminalKind.COMPLETED); }
        void error(ErrorCode code) throws Exception { read(w, m -> m instanceof ProtocolMessage.Error e && e.code() == code); }
        @Override public void close() throws Exception { w.close(); check(w.awaitTermination(LIMIT), "Join cabina"); check(w.failure().isEmpty(), "Cabina sana"); }
    }
    private static ProtocolMessage read(ElevatorWorker w, Predicate<ProtocolMessage> predicate) throws Exception {
        long end = System.nanoTime() + LIMIT.toNanos(); List<ProtocolMessage> seen = new ArrayList<>();
        while (System.nanoTime() < end) {
            var wire = w.awaitProtocolMessage(Duration.ofMillis(100));
            if (wire.isPresent()) {
                var p = CODEC.decodeResponse(wire.orElseThrow()); check(p.isSuccess(), "Respuesta malformada: " + wire.orElseThrow());
                ProtocolMessage m = p.message().orElseThrow(); seen.add(m); if (predicate.test(m)) { return m; }
            }
        }
        throw new AssertionError("Sin respuesta; vistas=" + seen);
    }
    private static <T> T get(CompletableFuture<T> f) throws Exception { return f.get(6, TimeUnit.SECONDS); }
    private static Throwable failure(CompletableFuture<?> f) throws Exception {
        try { get(f); throw new AssertionError("Se esperaba fallo"); } catch (java.util.concurrent.ExecutionException expected) { return expected.getCause(); }
    }
    private static void illegal(Runnable action) { try { action.run(); } catch (IllegalArgumentException | IllegalStateException expected) { return; } throw new AssertionError("Se esperaba rechazo"); }
    private static void await(BooleanSupplier condition) throws Exception {
        long end = System.nanoTime() + LIMIT.toNanos(); while (!condition.getAsBoolean()) { check(System.nanoTime() < end, "Plazo agotado"); Thread.sleep(2); }
    }
    private static void check(boolean value, String detail) { if (!value) { throw new AssertionError(detail); } }
    private static void equal(Object a, Object b) { if (!java.util.Objects.equals(a, b)) { throw new AssertionError("Esperado " + a + "; recibido " + b); } }
    public static void main(String[] args) throws Exception {
        int count = 0;
        for (var m : ProtocolIntegrationScenarios.class.getDeclaredMethods()) {
            if (java.lang.reflect.Modifier.isPublic(m.getModifiers()) && java.lang.reflect.Modifier.isStatic(m.getModifiers()) && m.getParameterCount() == 0) {
                try { m.invoke(null); count++; System.out.println("T65_OK " + m.getName()); }
                catch (java.lang.reflect.InvocationTargetException error) { throw new AssertionError("Fallo " + m.getName(), error.getCause()); }
            }
        }
        System.out.println("T65_RESULT scenarios=" + count + "; failures=0");
    }
}
```

## src/test/java/com/elevatormanager/simulation/ProtocolIntegrationTest.java

```java
package com.elevatormanager.simulation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Los mismos escenarios reales y de correlación adversarial, sin duplicar conteos. */
@Timeout(60)
class ProtocolIntegrationTest {
    @Test void ackAloneNeverCompletesTicket() throws Exception {
        ProtocolIntegrationScenarios.ackAloneNeverCompletesTicket();
    }
    @Test void statusRequiresStateAndCompletion() throws Exception {
        ProtocolIntegrationScenarios.statusRequiresStateAndCompletion();
    }
    @Test void completionBeforeAckIsIgnored() throws Exception {
        ProtocolIntegrationScenarios.completionBeforeAckIsIgnored();
    }
    @Test void stateBeforeAckCannotSatisfyStatus() throws Exception {
        ProtocolIntegrationScenarios.stateBeforeAckCannotSatisfyStatus();
    }
    @Test void wrongCommandIdCannotCompleteTicket() throws Exception {
        ProtocolIntegrationScenarios.wrongCommandIdCannotCompleteTicket();
    }
    @Test void wrongCabinCannotCompleteTicket() throws Exception {
        ProtocolIntegrationScenarios.wrongCabinCannotCompleteTicket();
    }
    @Test void wrongEpochCannotCompleteTicket() throws Exception {
        ProtocolIntegrationScenarios.wrongEpochCannotCompleteTicket();
    }
    @Test void duplicateTerminalCompletesOnlyOnce() throws Exception {
        ProtocolIntegrationScenarios.duplicateTerminalCompletesOnlyOnce();
    }
    @Test void coalescedAckCannotAdmitControl() throws Exception {
        ProtocolIntegrationScenarios.coalescedAckCannotAdmitControl();
    }
    @Test void errorBeforeAckIsARealRejection() throws Exception {
        ProtocolIntegrationScenarios.errorBeforeAckIsARealRejection();
    }
    @Test void failedTerminalFailsTicket() throws Exception {
        ProtocolIntegrationScenarios.failedTerminalFailsTicket();
    }
    @Test void cancelledTerminalFailsTicket() throws Exception {
        ProtocolIntegrationScenarios.cancelledTerminalFailsTicket();
    }
    @Test void unknownErrorHeaderDoesNotRejectSomeoneElse() throws Exception {
        ProtocolIntegrationScenarios.unknownErrorHeaderDoesNotRejectSomeoneElse();
    }
    @Test void malformedResponsesDoNotEscapeOrGenerateLoops() throws Exception {
        ProtocolIntegrationScenarios.malformedResponsesDoNotEscapeOrGenerateLoops();
    }
    @Test void sameTicketCanBeReRegisteredWithoutNewExecution() throws Exception {
        ProtocolIntegrationScenarios.sameTicketCanBeReRegisteredWithoutNewExecution();
    }
    @Test void registeringChangedContentWithSameIdIsRejected() throws Exception {
        ProtocolIntegrationScenarios.registeringChangedContentWithSameIdIsRejected();
    }
    @Test void cancellingPublicFutureDoesNotCancelPrivateTicket() throws Exception {
        ProtocolIntegrationScenarios.cancellingPublicFutureDoesNotCancelPrivateTicket();
    }
    @Test void closeFailsOutstandingAndRefusesNewTickets() throws Exception {
        ProtocolIntegrationScenarios.closeFailsOutstandingAndRefusesNewTickets();
    }
    @Test void twoCabinsRequireTheirOwnExactResponses() throws Exception {
        ProtocolIntegrationScenarios.twoCabinsRequireTheirOwnExactResponses();
    }
    @Test void resetRequiresCorrelatedStateNotSpontaneousState() throws Exception {
        ProtocolIntegrationScenarios.resetRequiresCorrelatedStateNotSpontaneousState();
    }
    @Test void resetCompletionAtAnotherFloorIsIgnored() throws Exception {
        ProtocolIntegrationScenarios.resetCompletionAtAnotherFloorIsIgnored();
    }
    @Test void oldEpochStateNeverOverwritesNewState() throws Exception {
        ProtocolIntegrationScenarios.oldEpochStateNeverOverwritesNewState();
    }
    @Test void lowerRevisionStateDoesNotOverwriteNewerState() throws Exception {
        ProtocolIntegrationScenarios.lowerRevisionStateDoesNotOverwriteNewerState();
    }
    @Test void equalRevisionMayPublishSmallerRemaining() throws Exception {
        ProtocolIntegrationScenarios.equalRevisionMayPublishSmallerRemaining();
    }
    @Test void unsolicitedCorrelatedStateIsIgnored() throws Exception {
        ProtocolIntegrationScenarios.unsolicitedCorrelatedStateIsIgnored();
    }
    @Test void historicalReplyCanFinishWithoutInstallingOldState() throws Exception {
        ProtocolIntegrationScenarios.historicalReplyCanFinishWithoutInstallingOldState();
    }
    @Test void replayAfterCompletedStatusCannotRollbackCache() throws Exception {
        ProtocolIntegrationScenarios.replayAfterCompletedStatusCannotRollbackCache();
    }
    @Test void sessionIdentityIncludesDestinationAndEpoch() throws Exception {
        ProtocolIntegrationScenarios.sessionIdentityIncludesDestinationAndEpoch();
    }
    @Test void rejectedReservationCannotRemoveAnotherOwner() throws Exception {
        ProtocolIntegrationScenarios.rejectedReservationCannotRemoveAnotherOwner();
    }
    @Test void managerAckMeansAssignedNotServed() throws Exception {
        ProtocolIntegrationScenarios.managerAckMeansAssignedNotServed();
    }
    @Test void managerArrivalCannotMeanService() throws Exception {
        ProtocolIntegrationScenarios.managerArrivalCannotMeanService();
    }
    @Test void managerRequiresCompatibleStopBeforeCompleted() throws Exception {
        ProtocolIntegrationScenarios.managerRequiresCompatibleStopBeforeCompleted();
    }
    @Test void managerWrongDirectionStopCannotServeCall() throws Exception {
        ProtocolIntegrationScenarios.managerWrongDirectionStopCannotServeCall();
    }
    @Test void managerOldAttemptResponseCannotAssignNewAttempt() throws Exception {
        ProtocolIntegrationScenarios.managerOldAttemptResponseCannotAssignNewAttempt();
    }
    @Test void managerOldEpochTerminalCannotServeNewEpoch() throws Exception {
        ProtocolIntegrationScenarios.managerOldEpochTerminalCannotServeNewEpoch();
    }
    @Test void managerDuplicateCompletionDoesNotReopenService() throws Exception {
        ProtocolIntegrationScenarios.managerDuplicateCompletionDoesNotReopenService();
    }
    @Test void managerFailedServiceIsNotAutomaticallyReassigned() throws Exception {
        ProtocolIntegrationScenarios.managerFailedServiceIsNotAutomaticallyReassigned();
    }
    @Test void statusRunsOnWorkerAndHasExactCorrelation() throws Exception {
        ProtocolIntegrationScenarios.statusRunsOnWorkerAndHasExactCorrelation();
    }
    @Test void statusWorksWhileSafelyStopped() throws Exception {
        ProtocolIntegrationScenarios.statusWorksWhileSafelyStopped();
    }
    @Test void statusReplayPreservesOriginalSnapshot() throws Exception {
        ProtocolIntegrationScenarios.statusReplayPreservesOriginalSnapshot();
    }
    @Test void controllerPortCannotSendPickup() throws Exception {
        ProtocolIntegrationScenarios.controllerPortCannotSendPickup();
    }
    @Test void managerPortCannotSendDestinationOrControl() throws Exception {
        ProtocolIntegrationScenarios.managerPortCannotSendDestinationOrControl();
    }
    @Test void wrongRoleCannotReplayAnAuthorizedStatus() throws Exception {
        ProtocolIntegrationScenarios.wrongRoleCannotReplayAnAuthorizedStatus();
    }
    @Test void sameIdWithChangedContentIsRejectedBeforeEpoch() throws Exception {
        ProtocolIntegrationScenarios.sameIdWithChangedContentIsRejectedBeforeEpoch();
    }
    @Test void sharedIdentityRegistryDetectsCrossCabinConflict() throws Exception {
        ProtocolIntegrationScenarios.sharedIdentityRegistryDetectsCrossCabinConflict();
    }
    @Test void strictResetRejectsSkippedOrEqualEpoch() throws Exception {
        ProtocolIntegrationScenarios.strictResetRejectsSkippedOrEqualEpoch();
    }
    @Test void resetPublishesFinalStateBeforeCompletion() throws Exception {
        ProtocolIntegrationScenarios.resetPublishesFinalStateBeforeCompletion();
    }
    @Test void oldResetReplayCannotMoveOrChangeEpochAgain() throws Exception {
        ProtocolIntegrationScenarios.oldResetReplayCannotMoveOrChangeEpochAgain();
    }
    @Test void laterResetSupersedesReturnWithoutTeleportation() throws Exception {
        ProtocolIntegrationScenarios.laterResetSupersedesReturnWithoutTeleportation();
    }
    @Test void strictStaleQueryCannotChangeCurrentState() throws Exception {
        ProtocolIntegrationScenarios.strictStaleQueryCannotChangeCurrentState();
    }
    @Test void stopAndStartCommandsControlPhysicalWork() throws Exception {
        ProtocolIntegrationScenarios.stopAndStartCommandsControlPhysicalWork();
    }
    @Test void duplicateNewStopIsInvalidButReplayIsIdempotent() throws Exception {
        ProtocolIntegrationScenarios.duplicateNewStopIsInvalidButReplayIsIdempotent();
    }
    @Test void shutdownCommandTerminatesActualThread() throws Exception {
        ProtocolIntegrationScenarios.shutdownCommandTerminatesActualThread();
    }
    @Test void shutdownCanCloseAnotherEpoch() throws Exception {
        ProtocolIntegrationScenarios.shutdownCanCloseAnotherEpoch();
    }
    @Test void controllerStatusAndCacheUseParsedText() throws Exception {
        ProtocolIntegrationScenarios.controllerStatusAndCacheUseParsedText();
    }
    @Test void statusDuringPauseDoesNotAdvanceClockOrRemaining() throws Exception {
        ProtocolIntegrationScenarios.statusDuringPauseDoesNotAdvanceClockOrRemaining();
    }
    @Test void resetFromPauseReturnsPhysicallyAndClearsCachedGeneration() throws Exception {
        ProtocolIntegrationScenarios.resetFromPauseReturnsPhysicallyAndClearsCachedGeneration();
    }
    @Test void resetWaitsForAllCabinReturnsNotJustAcks() throws Exception {
        ProtocolIntegrationScenarios.resetWaitsForAllCabinReturnsNotJustAcks();
    }
    @Test void resetFromStoppedClearsPendingDestinations() throws Exception {
        ProtocolIntegrationScenarios.resetFromStoppedClearsPendingDestinations();
    }
    @Test void controllerCloseInPauseJoinsResponseConsumer() throws Exception {
        ProtocolIntegrationScenarios.controllerCloseInPauseJoinsResponseConsumer();
    }
    @Test void queryingClosedControllerFailsWithoutHanging() throws Exception {
        ProtocolIntegrationScenarios.queryingClosedControllerFailsWithoutHanging();
    }
    @Test void invalidStatusTargetDoesNotAffectHealthySession() throws Exception {
        ProtocolIntegrationScenarios.invalidStatusTargetDoesNotAffectHealthySession();
    }
    @Test void queriesRacingCloseNeverLeaveFuturePending() throws Exception {
        ProtocolIntegrationScenarios.queriesRacingCloseNeverLeaveFuturePending();
    }
    @Test void stopQueuedBehindInitialStartStillStopsSession() throws Exception {
        ProtocolIntegrationScenarios.stopQueuedBehindInitialStartStillStopsSession();
    }
}
```

## src/test/java/com/elevatormanager/simulation/RequestImportScenarios.java

```java
package com.elevatormanager.simulation;

import com.elevatormanager.io.BatchDiagnostic;
import com.elevatormanager.io.BatchReadResult;
import com.elevatormanager.io.RequestFileReader;
import com.elevatormanager.model.SimulationConfig;
import com.elevatormanager.model.SimulationState;
import com.elevatormanager.model.request.CabinRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import javax.swing.SwingUtilities;
import static com.elevatormanager.simulation.ExecutionEvent.Type.*;
import static com.elevatormanager.simulation.BatchImportResult.Outcome.*;

/** T45: confirmación serializada, carreras con lectores retenidos y actores reales. */
public final class RequestImportScenarios {
    private static final SimulationConfig CONFIG = new SimulationConfig(2,8,5,5);
    private static final RequestFileReader READER = new RequestFileReader();
    private RequestImportScenarios() { }

    public static void initialImportDoesNotStartActors() throws Exception {
        inDirectory(dir -> {
            try (var rig = new Rig(CONFIG)) {
                var before = rig.c.snapshots();
                var result = get(rig.c.importRequests(file(dir,"a","CABIN 1 4\nHALL 3 UP")));
                check(result.outcome()==IMPORTED && rig.c.state()==SimulationState.RESET,"Confirmado en RESET");
                check(rig.c.snapshots().equals(before) && rig.c.elevatorLifecycles().stream().allMatch(x -> x==ElevatorWorker.Lifecycle.NEW),"Sin movimiento ni arranque");
                check(rig.c.managerLifecycle()==com.elevatormanager.dispatch.ManagerWorker.Lifecycle.NEW,"Manejador sin iniciar");
            }
        });
    }
    public static void importAfterResetKeepsCabinsIdle() throws Exception {
        inDirectory(dir -> {
            try (var rig = new Rig(CONFIG)) {
                get(rig.c.start()); get(rig.c.submit(cabin(0,3,1))); rig.until(COMMAND_COMPLETED,3);
                get(rig.c.reset()); var before=rig.c.snapshots();
                check(get(rig.c.importRequests(file(dir,"a","CABIN 1 7"))).outcome()==IMPORTED,"Importación tras reset");
                check(rig.c.snapshots().equals(before) && rig.c.state()==SimulationState.RESET,"Confirmar no reanuda actores retenidos");
            }
        });
    }
    public static void startingServicesMixedImportedBatch() throws Exception {
        inDirectory(dir -> {
            try (var rig=new Rig(CONFIG)) {
                var batch=get(rig.c.importRequests(file(dir,"a","CABIN 1 4\nHALL 3 UP\nCABIN 2 6"))).batch().orElseThrow();
                get(rig.c.start()); rig.served(batch);
                check(rig.c.preparedBatch().isEmpty() && rig.c.lastBatchDispatch().orElseThrow().entries().size()==3,"Lote consumido e informado");
            }
        });
    }
    public static void repeatedStartNeverReleasesBatchAgain() throws Exception {
        inDirectory(dir -> {
            try(var rig=new Rig(CONFIG)) {
                var batch=get(rig.c.importRequests(file(dir,"a","CABIN 1 4"))).batch().orElseThrow(); get(rig.c.start());
                var report=rig.c.lastBatchDispatch().orElseThrow();
                for(int i=0;i<20;i++) { check(get(rig.c.start()).outcome()==SimulationController.Outcome.UNCHANGED,"Start repetido"); }
                check(rig.c.lastBatchDispatch().orElseThrow()==report,"No regenera informe ni solicitudes"); rig.served(batch);
            }
        });
    }
    public static void stopStartContinuesOriginalBatchRequests() throws Exception {
        inDirectory(dir -> {
            try(var rig=new Rig(new SimulationConfig(2,8,100,5))) {
                var batch=get(rig.c.importRequests(file(dir,"a","CABIN 1 5"))).batch().orElseThrow();get(rig.c.start());rig.until(DEPARTED,1);
                get(rig.c.stop());var report=rig.c.lastBatchDispatch().orElseThrow();get(rig.c.start());rig.served(batch);
                check(rig.c.lastBatchDispatch().orElseThrow()==report,"Continúa pendientes sin reimportar");
            }
        });
    }
    public static void validReplacementIsAtomicAndUsesFreshIdentities() throws Exception {
        inDirectory(dir -> {
            try(var rig=new Rig(CONFIG)) {
                var a=get(rig.c.importRequests(file(dir,"a","CABIN 1 2"))).batch().orElseThrow();
                var b=get(rig.c.importRequests(file(dir,"b","CABIN 2 6\nHALL 3 UP"))).batch().orElseThrow();
                check(rig.c.preparedBatch().orElseThrow()==b && !a.id().equals(b.id()),"Reemplazo completo");
                check(b.requests().stream().noneMatch(r -> r.getId().equals(a.requests().get(0).getId())),"Identidades nuevas");
            }
        });
    }
    public static void invalidFinalLinePreservesPreviousBatch() throws Exception {
        inDirectory(dir -> {
            try(var rig=new Rig(CONFIG)) {
                var old=get(rig.c.importRequests(file(dir,"a","CABIN 1 2"))).batch().orElseThrow();
                var result=get(rig.c.importRequests(file(dir,"b","# final\nHALL 3 UP\nCABIN 1 8\nHALL 8 UP")));
                check(result.outcome()==INVALID && result.diagnostics().get(0).lineNumber().orElseThrow()==4,"Error final");
                check(rig.c.preparedBatch().orElseThrow()==old,"No sustituye parcial");
            }
        });
    }
    public static void missingFilePreservesPreviousBatch() throws Exception {
        inDirectory(dir -> {
            try(var rig=new Rig(CONFIG)) {
                var old=get(rig.c.importRequests(file(dir,"a","CABIN 1 2"))).batch().orElseThrow();
                var bad=get(rig.c.importRequests(dir.resolve("ausente")));
                check(bad.outcome()==INVALID && bad.diagnostics().get(0).code()==BatchDiagnostic.Code.READ_ERROR,"Error de lectura");
                check(rig.c.preparedBatch().orElseThrow()==old,"Conserva anterior");
            }
        });
    }
    public static void emptyFilePreservesPreviousBatch() throws Exception {
        inDirectory(dir -> {
            try(var rig=new Rig(CONFIG)) {
                var old=get(rig.c.importRequests(file(dir,"a","CABIN 1 2"))).batch().orElseThrow();
                var bad=get(rig.c.importRequests(file(dir,"b","# vacío\n\n")));
                check(bad.outcome()==INVALID && bad.diagnostics().get(0).code()==BatchDiagnostic.Code.EMPTY_BATCH,"Vacío no es vaciado");
                check(rig.c.preparedBatch().orElseThrow()==old,"Mantiene preparado");
            }
        });
    }
    public static void duplicateEntriesRemainDistinctAtConfirmation() throws Exception {
        inDirectory(dir -> {
            try(var rig=new Rig(CONFIG)) {
                var batch=get(rig.c.importRequests(file(dir,"a","CABIN 1 6\nCABIN 1 006\nHALL 3 UP\nHALL 003 UP"))).batch().orElseThrow();
                check(batch.requests().size()==4 && batch.requests().stream().map(r->r.getId()).distinct().count()==4,"Cuatro identidades sin deduplicar archivo");
                get(rig.c.start());check(rig.c.lastBatchDispatch().orElseThrow().entries().size()==4,"Cada entrada se presenta al dominio");
            }
        });
    }
    public static void runningImportIsRejectedBeforeReading() throws Exception {
        var reads=new AtomicInteger();
        try(var rig=new Rig(CONFIG,(p,c)->{reads.incrementAndGet();return parsed("CABIN 1 2",c);})) {
            get(rig.c.start());check(get(rig.c.importRequests(Path.of("a"))).outcome()==REJECTED && reads.get()==0,"No lee desde RUNNING");
        }
    }
    public static void stoppedImportIsRejectedBeforeReading() throws Exception {
        var reads=new AtomicInteger();
        try(var rig=new Rig(CONFIG,(p,c)->{reads.incrementAndGet();return parsed("CABIN 1 2",c);})) {
            get(rig.c.start());get(rig.c.stop());check(get(rig.c.importRequests(Path.of("a"))).state()==SimulationState.STOPPED && reads.get()==0,"No lee desde STOPPED");
        }
    }
    public static void stoppingImportIsRejectedBeforeReading() throws Exception {
        var reads=new AtomicInteger();
        try(var rig=new Rig(new SimulationConfig(2,8,60_000,5),(p,c)->{reads.incrementAndGet();return parsed("CABIN 1 2",c);})) {
            get(rig.c.start());get(rig.c.submit(cabin(0,8,1)));rig.until(DEPARTED,1);rig.c.stop();
            var response=get(rig.c.importRequests(Path.of("a")));
            check(response.outcome()==REJECTED && response.state()==SimulationState.STOPPING && reads.get()==0,"No lee desde STOPPING");
        }
    }
    public static void resettingImportIsRejectedBeforeReading() throws Exception {
        var reads=new AtomicInteger();
        try(var rig=new Rig(new SimulationConfig(2,8,60_000,5),(p,c)->{reads.incrementAndGet();return parsed("CABIN 1 2",c);})) {
            get(rig.c.start());get(rig.c.submit(cabin(0,8,1)));rig.until(DEPARTED,1);rig.c.reset();
            var response=get(rig.c.importRequests(Path.of("a")));
            check(response.outcome()==REJECTED && response.state()==SimulationState.RESETTING && reads.get()==0,"No lee durante retorno físico");
        }
    }
    public static void closingAndClosedImportsAreRejected() throws Exception {
        var reads=new AtomicInteger();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        var c=SimulationController.withBatchLoader(CONFIG,0,(p,config)->{reads.incrementAndGet();return parsed("CABIN 1 2",config);});
        try {
            get(c.registerResource(()->{entered.countDown();check(release.await(10,TimeUnit.SECONDS),"Liberar recurso");}));
            var closed=c.closeAsync();await(entered);
            check(get(c.importRequests(Path.of("a"))).outcome()==REJECTED,"Rechaza CLOSING");release.countDown();get(closed);
            check(get(c.importRequests(Path.of("a"))).state()==SimulationState.CLOSED && reads.get()==0,"Rechaza CLOSED sin lectura");
        } finally {release.countDown();get(c.closeAsync());check(c.awaitTermination(Duration.ofSeconds(10)),"Cerrador termina");}
    }
    public static void startDuringReadInvalidatesLateSuccess() throws Exception {
        var gate=new GateLoader(1);
        try(var rig=new Rig(CONFIG,gate)) {
            try {
                var pending=rig.c.importRequests(Path.of("a"));await(gate.entered);get(rig.c.start());
                check(get(pending).outcome()==REJECTED && rig.c.preparedBatch().isEmpty(),"Start invalida de inmediato");
                gate.release.countDown();check(rig.c.lastBatchDispatch().isEmpty(),"No había lote confirmado que ejecutar");
            } finally {gate.release.countDown();}
        }
    }
    public static void resetDuringReadRejectsOldEpochEvenBackInReset() throws Exception {
        var gate=new GateLoader(1);
        try(var rig=new Rig(CONFIG,gate)) {
            try {
                var pending=rig.c.importRequests(Path.of("a"));await(gate.entered);get(rig.c.reset());
                check(get(pending).outcome()==REJECTED && rig.c.state()==SimulationState.RESET && rig.c.epoch()==1,"Nueva época RESET");
                gate.release.countDown();var fresh=get(rig.c.importRequests(Path.of("b"))).batch().orElseThrow();
                check(fresh.epoch()==1 && fresh.requests().get(0).getFloor()==6,"Solo confirma lectura nueva");
            } finally {gate.release.countDown();}
        }
    }
    public static void resetClearsConfirmedBatchBeforeNextStart() throws Exception {
        inDirectory(dir -> {
            try(var rig=new Rig(CONFIG)) {
                get(rig.c.importRequests(file(dir,"a","CABIN 1 4")));get(rig.c.reset());
                check(rig.c.preparedBatch().isEmpty() && rig.c.lastBatchDispatch().isEmpty(),"Reset elimina lote");get(rig.c.start());
                check(rig.c.lastBatchDispatch().isEmpty(),"No revive después de Start");
            }
        });
    }
    public static void closeWaitsForActualReaderTermination() throws Exception {
        var gate=new GateLoader(1);var c=SimulationController.withBatchLoader(CONFIG,0,gate);
        try {
            var pending=c.importRequests(Path.of("a"));await(gate.entered);var closing=c.closeAsync();
            check(get(pending).outcome()==REJECTED,"Cierre resuelve importación pendiente");
            check(!c.awaitTermination(Duration.ZERO) && !closing.isDone(),"No afirma CLOSED mientras lector sigue vivo");
            gate.release.countDown();get(closing);check(c.awaitTermination(Duration.ofSeconds(10)),"Cierre real confirmado");
            check(!gate.thread.get().isAlive(),"Hilo I/O terminado");
        } finally {gate.release.countDown();get(c.closeAsync());c.awaitTermination(Duration.ofSeconds(10));}
    }
    public static void newestReadSupersedesAndPreservesOldBatchWhilePending() throws Exception {
        var gate=new GateLoader(2);
        try(var rig=new Rig(CONFIG,gate)) {
            try {
                var old=get(rig.c.importRequests(Path.of("a"))).batch().orElseThrow();
                var obsolete=rig.c.importRequests(Path.of("b"));await(gate.entered);var latest=rig.c.importRequests(Path.of("c"));
                check(get(obsolete).outcome()==SUPERSEDED && rig.c.preparedBatch().orElseThrow()==old,"Mantiene confirmado mientras lee");
                gate.release.countDown();var fresh=get(latest).batch().orElseThrow();
                check(rig.c.preparedBatch().orElseThrow()==fresh && fresh.requests().get(0).getFloor()==7,"Gana petición nueva");
            } finally {gate.release.countDown();}
        }
    }
    public static void repeatedSupersedingKeepsOneQueuedRead() throws Exception {
        var gate=new GateLoader(1);
        try(var rig=new Rig(CONFIG,gate)) {
            try {
                var first=rig.c.importRequests(Path.of("a"));await(gate.entered);
                var futures=new ArrayList<CompletableFuture<BatchImportResult>>();
                for(int i=0;i<20;i++) {futures.add(rig.c.importRequests(Path.of("c")));}
                check(get(first).outcome()==SUPERSEDED,"Primera invalidada");
                for(int i=0;i<19;i++) {check(get(futures.get(i)).outcome()==SUPERSEDED,"Pendiente sustituida");}
                gate.release.countDown();check(get(futures.get(19)).outcome()==IMPORTED,"Última confirmada");
                check(gate.calls.get()==2 && gate.threads.size()==1,"Solo lector en curso y último en cola; mismo hilo");
            } finally {gate.release.countDown();}
        }
    }
    public static void explicitCancellationKeepsPreviousBatch() throws Exception {
        var gate=new GateLoader(2);
        try(var rig=new Rig(CONFIG,gate)) {
            try {
                var old=get(rig.c.importRequests(Path.of("a"))).batch().orElseThrow();var pending=rig.c.importRequests(Path.of("b"));await(gate.entered);
                check(get(rig.c.cancelImport()).outcome()==CANCELLED && get(pending).outcome()==CANCELLED,"Cancelación explícita");
                check(rig.c.preparedBatch().orElseThrow()==old,"No borra lote previo");gate.release.countDown();
                check(get(rig.c.importRequests(Path.of("c"))).outcome()==IMPORTED,"Puede leer después");
            } finally {gate.release.countDown();}
        }
    }
    public static void cancellingVisibleFutureDoesNotCancelOperation() throws Exception {
        var gate=new GateLoader(1);
        try(var rig=new Rig(CONFIG,gate)) {
            try {
                var observer=rig.c.importRequests(Path.of("a"));await(gate.entered);check(observer.cancel(true),"Observador cancelado");
                gate.release.countDown();until(()->rig.c.preparedBatch().isPresent());
                check(observer.isCancelled() && rig.c.preparedBatch().orElseThrow().requests().get(0).getFloor()==2,"Operación privada sigue vigente");
            } finally {gate.release.countDown();}
        }
    }
    public static void incompatibleConfigurationCannotReplaceBatch() throws Exception {
        try(var rig=new Rig(CONFIG,(p,c)->parsed("CABIN 1 2",p.toString().equals("bad")?new SimulationConfig(1,4,1,1):c))) {
            var old=get(rig.c.importRequests(Path.of("a"))).batch().orElseThrow();var result=get(rig.c.importRequests(Path.of("bad")));
            check(result.outcome()==REJECTED && rig.c.preparedBatch().orElseThrow()==old,"Revalida configuración");
        }
    }
    public static void loaderExceptionIsInvalidAndKeepsOriginalCause() throws Exception {
        var cause=new IOException("fallo inyectado");
        try(var rig=new Rig(CONFIG,(p,c)->{if(p.toString().equals("bad")){throw cause;}return parsed("CABIN 1 2",c);})) {
            var result=get(rig.c.importRequests(Path.of("bad")));
            check(result.outcome()==INVALID && result.diagnostics().get(0).cause().orElseThrow()==cause,"Causa por identidad");
            check(get(rig.c.importRequests(Path.of("a"))).outcome()==IMPORTED && rig.c.failure().isEmpty(),"Error de archivo no mata sesión");
        }
    }
    public static void nullLoaderResultCannotLeaveUnresolvedFuture() throws Exception {
        try(var rig=new Rig(CONFIG,(p,c)->null)) {
            var result=get(rig.c.importRequests(Path.of("a")));
            check(result.outcome()==INVALID && result.diagnostics().get(0).cause().orElseThrow() instanceof NullPointerException,"Lector nulo se informa");
        }
    }
    public static void edtAndCoordinatorStayResponsiveDuringRead() throws Exception {
        var gate=new GateLoader(1);
        try(var rig=new Rig(CONFIG,gate)) {
            try {
                var future=new AtomicReference<CompletableFuture<BatchImportResult>>();
                SwingUtilities.invokeAndWait(()->future.set(rig.c.importRequests(Path.of("a"))));await(gate.entered);
                var ping=new CompletableFuture<Boolean>();SwingUtilities.invokeLater(()->ping.complete(SwingUtilities.isEventDispatchThread()));
                check(get(ping) && gate.thread.get().getName().startsWith("simulation-import-"),"EDT libre e I/O en hilo propio");
                check(get(rig.c.stop()).outcome()==SimulationController.Outcome.UNCHANGED,"Coordinador no bloqueado por I/O");
                get(rig.c.cancelImport());gate.release.countDown();check(get(future.get()).outcome()==CANCELLED,"Cancelación responde");
            } finally {gate.release.countDown();}
        }
    }
    public static void confirmationDoesNotConsumeCommandIds() throws Exception {
        inDirectory(dir -> {
            try(var rig=new Rig(CONFIG)) {
                get(rig.c.importRequests(file(dir,"a","CABIN 1 2")));get(rig.c.importRequests(file(dir,"b","CABIN 2 3")));
                get(rig.c.importRequests(file(dir,"bad","HALL 8 UP")));get(rig.c.start());
                check(rig.c.lastBatchDispatch().orElseThrow().entries().get(0).result().command().orElseThrow().commandId()==CONFIG.elevatorCount()+1L,"Importar no consume IDs; START usa uno por cabina antes del primer servicio");
            }
        });
    }
    public static void receiptsAreBoundToTheirSession() throws Exception {
        try(var a=new Rig(CONFIG,(p,c)->parsed("CABIN 1 2",c));var b=new Rig(CONFIG,(p,c)->parsed("CABIN 1 2",c))) {
            var result=get(a.c.importRequests(Path.of("a")));
            check(a.c.isCurrent(result) && !b.c.isCurrent(result),"Misma época no autoriza otra sesión");get(a.c.start());
            check(!a.c.isCurrent(result),"Respuesta anterior a Start ya no representa lote preparado");
        }
    }
    public static void delayedReceiptIsRejectedAfterReplacement() throws Exception {
        try(var rig=new Rig(CONFIG,(p,c)->parsed("CABIN 1 2",c))) {
            var old=get(rig.c.importRequests(Path.of("a")));var fresh=get(rig.c.importRequests(Path.of("b")));
            check(!rig.c.isCurrent(old) && rig.c.isCurrent(fresh),"Generación invalida respuesta tardía");
        }
    }
    public static void confirmedRequestsShareTimeAndHaveUniqueIds() throws Exception {
        try(var rig=new Rig(CONFIG,(p,c)->parsed("CABIN 1 2\nCABIN 1 2\nHALL 3 UP",c))) {
            var batch=get(rig.c.importRequests(Path.of("a"))).batch().orElseThrow();
            check(batch.requests().stream().allMatch(r->r.getEpoch()==0 && r.getCreatedAt().equals(batch.confirmedAt())),"Un Instant de confirmación y época");
            check(batch.requests().stream().map(r->r.getId()).distinct().count()==3,"UUID por instrucción, incluidos repetidos");
        }
    }
    public static void dispatchReportPreservesOrderWithoutClaimingService() throws Exception {
        try(var rig=new Rig(CONFIG,(p,c)->parsed("# origen\nCABIN 1 7\nHALL 2 UP\n\nCABIN 2 6\nHALL 5 DOWN",c))) {
            var batch=get(rig.c.importRequests(Path.of("a"))).batch().orElseThrow();get(rig.c.start());var report=rig.c.lastBatchDispatch().orElseThrow();
            check(report.entries().stream().map(BatchDispatchReport.Entry::lineNumber).toList().equals(List.of(2,3,5,6)),"Orden de presentación");
            check(report.entries().stream().allMatch(e->e.result().outcome()==SimulationController.Outcome.ENQUEUED),"Solo confirma envío");
            for(int i=0;i<4;i++) {check(report.entries().get(i).requestId().equals(batch.requests().get(i).getId()),"UUID correlacionado");}
            check(report.entries().get(1).result().command().isEmpty(),"HALL aún no es orden asignada al confirmar envío");
        }
    }
    public static void importingAfterResetKeepsActorThreadIdentities() throws Exception {
        try(var rig=new Rig(CONFIG,(p,c)->parsed("CABIN 1 2",c))) {
            get(rig.c.start());var before=actorThreads();get(rig.c.reset());get(rig.c.importRequests(Path.of("a")));get(rig.c.start());
            check(actorThreads().equals(before) && before.size()==3,"No crea nuevas cabinas ni manejador");
        }
    }
    public static void importingAfterCompletedResetUsesNewEpoch() throws Exception {
        try(var rig=new Rig(CONFIG,(p,c)->parsed("CABIN 1 3",c))) {
            get(rig.c.reset());var batch=get(rig.c.importRequests(Path.of("a"))).batch().orElseThrow();
            check(batch.epoch()==1 && batch.requests().get(0).getEpoch()==1,"Época actual confirmada");get(rig.c.start());rig.served(batch);
        }
    }
    public static void closeClearsConfirmedBatch() throws Exception {
        try(var rig=new Rig(CONFIG,(p,c)->parsed("CABIN 1 2",c))) {
            get(rig.c.importRequests(Path.of("a")));get(rig.c.closeAsync());check(rig.c.preparedBatch().isEmpty(),"Cierre no conserva lote ejecutable");
        }
    }
    public static void importedAndManualRequestsUseSameAdmissionPath() throws Exception {
        try(var rig=new Rig(CONFIG,(p,c)->parsed("CABIN 1 2",c))) {
            var batch=get(rig.c.importRequests(Path.of("a"))).batch().orElseThrow();get(rig.c.start());rig.served(batch);
            var manual=cabin(0,4,1);var submitted=get(rig.c.submit(manual));rig.until(COMMAND_COMPLETED,4);
            check(submitted.command().orElseThrow().commandId()>rig.c.lastBatchDispatch().orElseThrow().entries().get(0).result().command().orElseThrow().commandId(),"Generador compartido sin reinicio");
        }
    }
    public static void cancellationWithoutReadDoesNotEraseBatch() throws Exception {
        try(var rig=new Rig(CONFIG,(p,c)->parsed("CABIN 1 2",c))) {
            var batch=get(rig.c.importRequests(Path.of("a"))).batch().orElseThrow();get(rig.c.cancelImport());
            check(rig.c.preparedBatch().orElseThrow()==batch,"Cancelar selector sin lectura no borra preparado");
        }
    }
    public static void staleInvalidResultCannotEraseNewerBatch() throws Exception {
        var gate=new GateLoader(2,true);
        try(var rig=new Rig(CONFIG,gate)) {
            try {
                get(rig.c.importRequests(Path.of("a")));var obsolete=rig.c.importRequests(Path.of("b"));await(gate.entered);
                var latest=rig.c.importRequests(Path.of("c"));check(get(obsolete).outcome()==SUPERSEDED,"Error viejo invalidado");
                gate.release.countDown();var fresh=get(latest).batch().orElseThrow();check(rig.c.preparedBatch().orElseThrow()==fresh,"Error tardío no borra nuevo");
            } finally {gate.release.countDown();}
        }
    }
    public static void publicRecordsAreImmutableAndValidateCorrelation() throws Exception {
        try(var rig=new Rig(CONFIG,(p,c)->parsed("CABIN 1 2",c))) {
            var result=get(rig.c.importRequests(Path.of("a")));var batch=result.batch().orElseThrow();
            expect(UnsupportedOperationException.class,()->batch.requests().clear());
            expect(IllegalArgumentException.class,()->new ImportedRequestBatch(batch.id(),0,batch.confirmedAt(),batch.source(),List.of(cabin(0,3,1))));
            expect(IllegalArgumentException.class,()->new BatchImportResult(IMPORTED,SimulationState.RUNNING,0,rig.c.sessionId(),1,"mal",Optional.of(batch),List.of()));
            get(rig.c.start());var report=rig.c.lastBatchDispatch().orElseThrow();expect(UnsupportedOperationException.class,()->report.entries().clear());
            expect(IllegalArgumentException.class,()->new BatchDispatchReport(batch,List.of()));
            expect(IllegalArgumentException.class,()->new BatchDispatchReport(batch,List.of(new BatchDispatchReport.Entry(1,batch.requests().get(0).getId(),
                    new SimulationController.Result(SimulationController.Outcome.APPLIED,SimulationState.RUNNING,"No es un envío",Optional.empty(),0)))));
            expect(NullPointerException.class,()->rig.c.importRequests(null));
        }
    }
    public static void repeatedSessionsReleaseEveryOwnedThread() throws Exception {
        for(int i=0;i<10;i++) {
            var rig=new Rig(CONFIG,(p,c)->parsed("CABIN 1 2",c));
            List<Thread> owned;
            try {
                for(int j=0;j<3;j++) {get(rig.c.importRequests(Path.of("a")));}get(rig.c.start());
                owned=Thread.getAllStackTraces().keySet().stream().filter(t->t.getName().equals("simulation-import-"+rig.c.sessionId())
                        || t.getName().equals("simulation-controller") || t.getName().startsWith("elevator-worker-") || t.getName().startsWith("manager-worker-")).toList();
            } finally {rig.close();}
            check(owned.stream().noneMatch(Thread::isAlive),"Sin hilos de sesión tras cerrar");
        }
    }

    private static final class GateLoader implements SimulationController.BatchLoader {
        final int blockedCall;final boolean invalidBlocked;final AtomicInteger calls=new AtomicInteger();
        final CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        final AtomicReference<Thread> thread=new AtomicReference<>();
        final java.util.Set<Thread> threads=java.util.concurrent.ConcurrentHashMap.newKeySet();
        GateLoader(int blockedCall){this(blockedCall,false);}
        GateLoader(int blockedCall,boolean invalidBlocked){this.blockedCall=blockedCall;this.invalidBlocked=invalidBlocked;}
        @Override public BatchReadResult read(Path path,SimulationConfig config) {
            int call=calls.incrementAndGet();threads.add(Thread.currentThread());
            String text=path.toString().equals("a")?"CABIN 1 2":path.toString().equals("b")?"CABIN 1 6":"CABIN 1 7";
            var parsed=parsed(call==blockedCall && invalidBlocked?"HALL 8 UP":text,config);
            if(call==blockedCall) {
                thread.set(Thread.currentThread());entered.countDown();boolean interrupted=false;
                try {
                    long deadline=deadline();
                    while(true) {
                        long left=deadline-System.nanoTime();check(left>0,"Lector retenido no liberado");
                        try {check(release.await(left,TimeUnit.NANOSECONDS),"Liberar lector");break;}
                        catch(InterruptedException ignored){interrupted=true;}
                    }
                } finally {if(interrupted){Thread.currentThread().interrupt();}}
            }
            return parsed;
        }
    }
    private static final class Rig implements AutoCloseable {
        final SimulationController c;
        Rig(SimulationConfig config){c=new SimulationController(config,0);}
        Rig(SimulationConfig config,SimulationController.BatchLoader loader){c=SimulationController.withBatchLoader(config,0,loader);}
        void until(ExecutionEvent.Type type,int floor)throws Exception {
            long deadline=deadline();while(true){var event=next(deadline);if(event.type()==type && event.snapshot().currentFloor()==floor){return;}}
        }
        ExecutionEvent next(long deadline)throws Exception {
            long left=deadline-System.nanoTime();check(left>0,"Plazo de evento agotado");
            return c.awaitEvent(Duration.ofNanos(left)).orElseThrow(()->new AssertionError("Falta evento"));
        }
        void served(ImportedRequestBatch batch)throws Exception {
            var pending=new HashSet<UUID>();batch.requests().forEach(r->pending.add(r.getId()));long deadline=deadline();
            while(!pending.isEmpty()) {var event=next(deadline);if(event.type()==COMMAND_COMPLETED){event.canonicalRequestId().ifPresent(pending::remove);}}
        }
        @Override public void close()throws Exception {get(c.closeAsync());check(c.awaitTermination(Duration.ofSeconds(10)),"Cierre real");check(c.failure().isEmpty(),"Sin fallos de sesión");}
    }
    private static java.util.Set<Thread> actorThreads(){var set=new HashSet<Thread>();Thread.getAllStackTraces().keySet().stream().filter(t->t.getName().startsWith("elevator-worker-") || t.getName().startsWith("manager-worker-")).forEach(set::add);return set;}
    private static BatchReadResult parsed(String text,SimulationConfig config){return READER.parse(text.getBytes(StandardCharsets.UTF_8),config);}
    private static CabinRequest cabin(long epoch,int floor,int id){return new CabinRequest(UUID.randomUUID(),epoch,floor,Instant.now(),id);}
    private static Path file(Path directory,String name,String text)throws IOException{return Files.writeString(directory.resolve(name+".txt"),text,StandardCharsets.UTF_8);}
    private static long deadline(){return System.nanoTime()+TimeUnit.SECONDS.toNanos(10);}
    private static void await(CountDownLatch latch)throws InterruptedException{check(latch.await(10,TimeUnit.SECONDS),"Barrera no alcanzada");}
    private static void until(BooleanSupplier condition){long deadline=deadline();while(!condition.getAsBoolean()){check(System.nanoTime()<deadline,"Condición no alcanzada");LockSupport.parkNanos(1_000_000);}}
    private static <T>T get(CompletableFuture<T> future)throws Exception{return future.get(15,TimeUnit.SECONDS);}
    private static void check(boolean condition,String message){if(!condition){throw new AssertionError(message);}}
    private interface Action{void run()throws Exception;}
    private interface DirectoryAction{void run(Path path)throws Exception;}
    private static void expect(Class<? extends Throwable> type,Action action)throws Exception{try{action.run();}catch(Throwable error){if(type.isInstance(error)){return;}throw new AssertionError("Excepción inesperada",error);}throw new AssertionError("Falta excepción "+type.getSimpleName());}
    private static void inDirectory(DirectoryAction action)throws Exception{Path directory=Files.createTempDirectory("t45-");try{action.run(directory);}finally{try(var paths=Files.walk(directory)){for(Path path:paths.sorted(Comparator.reverseOrder()).toList()){Files.delete(path);}}}}
    public static void main(String[]args)throws Exception {
        initialImportDoesNotStartActors();importAfterResetKeepsCabinsIdle();startingServicesMixedImportedBatch();repeatedStartNeverReleasesBatchAgain();
        stopStartContinuesOriginalBatchRequests();validReplacementIsAtomicAndUsesFreshIdentities();invalidFinalLinePreservesPreviousBatch();
        missingFilePreservesPreviousBatch();emptyFilePreservesPreviousBatch();duplicateEntriesRemainDistinctAtConfirmation();
        runningImportIsRejectedBeforeReading();stoppedImportIsRejectedBeforeReading();stoppingImportIsRejectedBeforeReading();resettingImportIsRejectedBeforeReading();
        closingAndClosedImportsAreRejected();startDuringReadInvalidatesLateSuccess();resetDuringReadRejectsOldEpochEvenBackInReset();
        resetClearsConfirmedBatchBeforeNextStart();closeWaitsForActualReaderTermination();newestReadSupersedesAndPreservesOldBatchWhilePending();
        repeatedSupersedingKeepsOneQueuedRead();explicitCancellationKeepsPreviousBatch();cancellingVisibleFutureDoesNotCancelOperation();
        incompatibleConfigurationCannotReplaceBatch();loaderExceptionIsInvalidAndKeepsOriginalCause();nullLoaderResultCannotLeaveUnresolvedFuture();
        edtAndCoordinatorStayResponsiveDuringRead();confirmationDoesNotConsumeCommandIds();receiptsAreBoundToTheirSession();delayedReceiptIsRejectedAfterReplacement();
        confirmedRequestsShareTimeAndHaveUniqueIds();dispatchReportPreservesOrderWithoutClaimingService();importingAfterResetKeepsActorThreadIdentities();
        importingAfterCompletedResetUsesNewEpoch();closeClearsConfirmedBatch();importedAndManualRequestsUseSameAdmissionPath();
        cancellationWithoutReadDoesNotEraseBatch();staleInvalidResultCannotEraseNewerBatch();publicRecordsAreImmutableAndValidateCorrelation();repeatedSessionsReleaseEveryOwnedThread();
        System.out.println("T45: 40 escenarios satisfactorios.");
    }
}
```

## src/test/java/com/elevatormanager/simulation/TextTransportScenarios.java

```java
package com.elevatormanager.simulation;

import com.elevatormanager.dispatch.HallCallSnapshot;
import com.elevatormanager.dispatch.ManagerWorker;
import com.elevatormanager.model.Direction;
import com.elevatormanager.model.ElevatorPhase;
import com.elevatormanager.model.SimulationConfig;
import com.elevatormanager.model.SimulationState;
import com.elevatormanager.model.request.CabinRequest;
import com.elevatormanager.model.request.HallRequest;
import com.elevatormanager.protocol.Command;
import com.elevatormanager.protocol.ProtocolCodec;
import com.elevatormanager.protocol.ProtocolCommands;
import com.elevatormanager.protocol.ProtocolMessage;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import static com.elevatormanager.protocol.ProtocolMessage.*;

/** Actor real: el cuerpo textual determina la agenda y las respuestas vienen de su hilo. */
public final class TextTransportScenarios {
    private static final Duration LIMIT = Duration.ofSeconds(6);
    private static final Instant DATE = Instant.parse("2026-11-02T00:00:00Z");
    private TextTransportScenarios() { }

    public static void rawDestinationIsParsedOnActor() throws Exception {
        try (Rig r = new Rig()) {
            check(r.w.submitText(destination(1, 3)), "Encolado");
            r.terminal(1, TerminalKind.COMPLETED);
            equal(3, r.w.snapshot().currentFloor());
            equal(r.w.threadName(), r.w.protocolReception().processedBy());
            equal(1L, r.w.protocolReception().frames());
        }
    }
    public static void rawPickupUsesTransmittedDirectionAndUuid() throws Exception {
        try (Rig r = new Rig()) {
            String line = pickup(1, 3, Direction.DOWN);
            check(r.w.submitText(line), "Encolado");
            var result = r.w.awaitResult(LIMIT).orElseThrow();
            var command = (Command.Pickup) result.command();
            equal(id(1), command.request().getId()); equal(Direction.DOWN, command.request().getDirection());
            r.terminal(1, TerminalKind.COMPLETED); equal(Direction.DOWN, r.w.snapshot().direction());
        }
    }
    public static void changedTextChangesPhysicalDestination() throws Exception {
        try (Rig r = new Rig()) {
            String changed = destination(1, 3).replace(" 3", " 4");
            r.w.submitText(changed); r.terminal(1, TerminalKind.COMPLETED);
            equal(4, r.w.snapshot().currentFloor());
            var result = (Command.Destination) r.w.awaitResult(LIMIT).orElseThrow().command();
            equal(4, result.request().getFloor());
        }
    }
    public static void compatibilitySubmitReconstructsDistinctObjects() throws Exception {
        try (Rig r = new Rig()) {
            var original = new Command.Destination(1, 1, new CabinRequest(id(1), 0, 2, DATE, 1));
            check(r.w.submit(original), "Encolado compatible");
            Command reconstructed = r.w.awaitResult(LIMIT).orElseThrow().command();
            check(reconstructed != original, "No se pasa el Command original");
            check(((Command.Destination) reconstructed).request() != original.request(), "Nueva solicitud desde texto");
            check(original.sameContent(reconstructed), "Contenido conservado");
        }
    }
    public static void explicitTimestampContextSurvives() throws Exception {
        try (Rig r = new Rig()) {
            r.w.submitText(destination(1, 2), DATE);
            var c = (Command.Destination) r.w.awaitResult(LIMIT).orElseThrow().command();
            equal(DATE, c.request().getCreatedAt());
        }
    }
    public static void defaultTimestampIsStableNotWallClock() throws Exception {
        try (Rig r = new Rig(10_000)) {
            r.w.submitText(destination(1, 3)); r.w.submitText(destination(1, 3));
            var first = r.w.awaitResult(LIMIT).orElseThrow(); var second = r.w.awaitResult(LIMIT).orElseThrow();
            equal(Instant.EPOCH, ((Command.Destination) first.command()).request().getCreatedAt());
            check(second.replay(), "Reintento no inventa otra fecha");
        }
    }
    public static void ackPrecedesPhysicalCompletion() throws Exception {
        try (Rig r = new Rig(300)) {
            r.w.submitText(destination(1, 3));
            r.read(m -> m instanceof Ack); check(r.w.snapshot().currentFloor() < 3, "ACK no es llegada");
            r.terminal(1, TerminalKind.COMPLETED); equal(3, r.w.snapshot().currentFloor());
        }
    }
    public static void moveArrivalAndStopAreTextualEvents() throws Exception {
        try (Rig r = new Rig()) {
            r.w.submitText(destination(1, 2)); r.terminal(1, TerminalKind.COMPLETED);
            check(r.seen.stream().anyMatch(m -> m instanceof Move v && v.from() == 1 && v.to() == 2), "MOVE real");
            check(r.seen.stream().anyMatch(m -> m instanceof Arrived v && v.floor() == 2), "ARRIVED real");
            check(r.seen.stream().anyMatch(m -> m instanceof StoppedAt v && v.floor() == 2), "Parada real");
        }
    }
    public static void directionChangesAreReportedInText() throws Exception {
        try (Rig r = new Rig()) {
            r.w.submitText(pickup(1, 3, Direction.DOWN)); r.terminal(1, TerminalKind.COMPLETED);
            check(r.seen.stream().anyMatch(m -> m instanceof DirectionChanged v
                    && v.from() == Direction.UP && v.to() == Direction.DOWN), "Inversión publicada");
        }
    }
    public static void malformedCommandDoesNotMutateAndWorkerRecovers() throws Exception {
        try (Rig r = new Rig()) {
            long revision = r.w.snapshot().revision();
            r.w.submitText(prefix(1) + "FLY"); r.error(ErrorCode.UNKNOWN_COMMAND);
            equal(revision, r.w.snapshot().revision());
            r.w.submitText(destination(2, 2)); r.terminal(2, TerminalKind.COMPLETED);
            check(r.w.failure().isEmpty(), "Trabajador vivo");
        }
    }
    public static void invalidFloorIsRejectedBeforeMotion() throws Exception {
        try (Rig r = new Rig()) {
            r.w.submitText(destination(1, 9)); r.error(ErrorCode.INVALID_FLOOR);
            equal(1, r.w.snapshot().currentFloor()); check(r.w.snapshot().cabinRequests().isEmpty(), "Sin agenda parcial");
        }
    }
    public static void impossibleDirectionIsRejected() throws Exception {
        try (Rig r = new Rig()) { r.w.submitText(pickup(1, 8, Direction.UP)); r.error(ErrorCode.INVALID_DIRECTION); }
    }
    public static void unknownConfiguredElevatorIsRejected() throws Exception {
        try (Rig r = new Rig()) {
            r.w.submitText(destination(1, 3).replace("ELEVATOR 1", "ELEVATOR 9"));
            var error = r.error(ErrorCode.UNKNOWN_ELEVATOR); equal(9, error.header().elevatorId());
        }
    }
    public static void wrongExistingTargetCannotMoveThisCabin() throws Exception {
        try (Rig r = new Rig()) {
            r.w.submitText(destination(1, 3).replace("ELEVATOR 1", "ELEVATOR 2"));
            r.error(ErrorCode.UNKNOWN_ELEVATOR); equal(1, r.w.snapshot().currentFloor());
        }
    }
    public static void staleAndFutureServiceEpochsAreRejected() throws Exception {
        try (Rig r = new Rig()) {
            r.w.submitText(destination(1, 3).replace("EPOCH 0", "EPOCH 1")); r.error(ErrorCode.FUTURE_EPOCH);
            r.w.requestResetText("V1 CMD 10 EPOCH 1 ELEVATOR 1 RESET").get(6, TimeUnit.SECONDS);
            check(r.w.resume(), "Liberar después de reset");
            r.w.submitText(destination(2, 3)); r.error(ErrorCode.STALE_EPOCH);
        }
    }
    public static void badUuidDoesNotCreateRequest() throws Exception {
        try (Rig r = new Rig()) {
            r.w.submitText(prefix(1) + "DESTINATION 123 3"); r.error(ErrorCode.INVALID_ARGUMENT);
            check(r.w.snapshot().cabinRequests().isEmpty(), "Sin UUID inventado");
        }
    }
    public static void ackInjectedIntoCommandQueueIsNotExecuted() throws Exception {
        try (Rig r = new Rig()) {
            r.w.submitText("V1 ACK 1 EPOCH 0 ELEVATOR 1 ACCEPTED"); r.error(ErrorCode.INVALID_FORMAT);
            equal(0L, r.w.snapshot().revision());
        }
    }
    public static void extraTokensDoNotPartiallyExecute() throws Exception {
        try (Rig r = new Rig()) {
            r.w.submitText(destination(1, 3) + " EXTRA"); r.error(ErrorCode.INVALID_FORMAT);
            check(r.w.snapshot().cabinRequests().isEmpty(), "Rechazo atómico");
        }
    }
    public static void oversizedFrameIsRejectedOnActor() throws Exception {
        try (Rig r = new Rig()) {
            r.w.submitText("X".repeat(ProtocolCodec.MAX_FRAME_BYTES + 1)); r.error(ErrorCode.MESSAGE_TOO_LONG);
            equal(r.w.threadName(), r.w.protocolReception().processedBy());
            r.w.submitText(destination(2, 2)); r.terminal(2, TerminalKind.COMPLETED);
        }
    }
    public static void framingNormalizationIsAppliedAtReceiver() throws Exception {
        try (Rig r = new Rig()) {
            r.w.submitText(destination(1, 2).replace(" ", "   ") + "\r\n"); r.terminal(1, TerminalKind.COMPLETED);
            equal(2, r.w.snapshot().currentFloor());
        }
    }
    public static void malformedBurstCannotKillActor() throws Exception {
        try (Rig r = new Rig()) {
            for (int i = 1; i <= 100; i++) { r.w.submitText(prefix(i) + "BOGUS"); }
            int errors = 0;
            while (errors < 100) { r.read(m -> m instanceof ProtocolMessage.Error); errors++; }
            r.w.submitText(destination(101, 2)); r.terminal(101, TerminalKind.COMPLETED);
            equal(100L, r.w.protocolReception().malformed());
        }
    }
    public static void conditionalSendChecksSnapshotBeforeEnqueue() throws Exception {
        try (Rig r = new Rig(10_000)) {
            var initial = r.w.planningSnapshot();
            equal(ElevatorWorker.Submission.ENQUEUED,
                    r.w.submitTextIfCurrent(destination(1, 3), DATE, initial));
            r.w.awaitResult(LIMIT).orElseThrow();
            equal(ElevatorWorker.Submission.STALE,
                    r.w.submitTextIfCurrent(destination(2, 4), DATE, initial));
            equal(1L, r.w.protocolReception().frames());
        }
    }
    public static void compatibleConditionalSendAlsoSerializes() throws Exception {
        try (Rig r = new Rig(10_000)) {
            var original = new Command.Destination(1, 1, new CabinRequest(id(1), 0, 3, DATE, 1));
            equal(ElevatorWorker.Submission.ENQUEUED, r.w.submitIfCurrent(original, r.w.planningSnapshot()));
            var parsed = r.w.awaitResult(LIMIT).orElseThrow().command();
            check(parsed != original && original.sameContent(parsed), "Ruta compatible también cruza como texto");
        }
    }
    public static void enqueueIsNotAdmissionWhileStopped() throws Exception {
        try (Rig r = new Rig()) {
            r.w.requestStop().get(6, TimeUnit.SECONDS);
            check(r.w.submitText(destination(1, 3)), "Solo encolado");
            equal(0L, r.w.protocolReception().frames());
            check(r.w.awaitProtocolMessage(Duration.ZERO).isEmpty(), "Sin ACK antes de leer");
            check(r.w.resume(), "Reanudar"); r.terminal(1, TerminalKind.COMPLETED);
        }
    }
    public static void serviceFifoIsPreserved() throws Exception {
        try (Rig r = new Rig(10_000)) {
            for (int i = 1; i <= 5; i++) { r.w.submitText(destination(i, i + 1)); }
            List<Long> accepted = new ArrayList<>();
            for (int i = 0; i < 5; i++) { accepted.add(r.read(m -> m instanceof Ack).header().id()); }
            equal(List.of(1L, 2L, 3L, 4L, 5L), accepted);
        }
    }
    public static void aliasesHaveCanonicalAckAndEachOwnTerminal() throws Exception {
        try (Rig r = new Rig(100)) {
            r.w.submitText(destination(1, 2)); r.w.submitText(destination(2, 2));
            Ack first = (Ack) r.read(m -> m instanceof Ack); Ack second = (Ack) r.read(m -> m instanceof Ack);
            equal(AckKind.ACCEPTED, first.kind()); equal(AckKind.COALESCED, second.kind());
            equal(Optional.of(id(1)), second.canonicalRequestId());
            r.terminal(1, TerminalKind.COMPLETED); r.terminal(2, TerminalKind.COMPLETED);
        }
    }
    public static void sameCommandReplayDoesNotDuplicatePhysicalService() throws Exception {
        try (Rig r = new Rig(100)) {
            String frame = destination(1, 2); r.w.submitText(frame); r.w.submitText(frame);
            r.read(m -> m instanceof Ack); r.read(m -> m instanceof Ack);
            r.terminal(1, TerminalKind.COMPLETED);
            var service = r.w.awaitService(LIMIT).orElseThrow(); equal(1, service.requests().size());
            check(r.w.awaitService(Duration.ZERO).isEmpty(), "Un servicio físico");
        }
    }
    public static void servedReplayReturnsKnownTerminalWithoutAnotherStop() throws Exception {
        try (Rig r = new Rig()) {
            String frame = destination(1, 1); r.w.submitText(frame); r.terminal(1, TerminalKind.COMPLETED);
            r.w.awaitService(LIMIT).orElseThrow();
            r.w.submitText(frame); r.read(m -> m instanceof Ack); r.terminal(1, TerminalKind.COMPLETED);
            check(r.w.awaitService(Duration.ZERO).isEmpty(), "Replay textual sin servicio nuevo");
        }
    }
    public static void conflictingIdCannotReplaceDestination() throws Exception {
        try (Rig r = new Rig(100)) {
            r.w.submitText(destination(1, 2)); r.w.submitText(destination(1, 3)); r.error(ErrorCode.ID_CONFLICT);
            r.terminal(1, TerminalKind.COMPLETED); equal(2, r.w.snapshot().currentFloor());
        }
    }
    public static void resetQueueAlsoCarriesAndParsesText() throws Exception {
        try (Rig r = new Rig()) {
            r.w.requestResetText("V1 CMD 10 EPOCH 1 ELEVATOR 1 RESET").get(6, TimeUnit.SECONDS);
            r.terminal(10, TerminalKind.COMPLETED); equal(1L, r.w.snapshot().epoch());
            equal(r.w.threadName(), r.w.protocolReception().processedBy());
        }
    }
    public static void resetCancelsActiveCommandBeforeReturnCompletes() throws Exception {
        try (Rig r = new Rig(100)) {
            r.w.submitText(destination(1, 5)); r.read(m -> m instanceof Move);
            r.w.requestResetText("V1 CMD 10 EPOCH 1 ELEVATOR 1 RESET").get(6, TimeUnit.SECONDS);
            Terminal cancelled = r.terminal(1, TerminalKind.CANCELLED);
            equal(Optional.of(CancelReason.RESET), cancelled.reason()); equal(0L, cancelled.header().epoch());
            r.terminal(10, TerminalKind.COMPLETED); equal(1, r.w.snapshot().currentFloor());
        }
    }
    public static void resetDiscardsQueuedTextBeforeAdmission() throws Exception {
        try (Rig r = new Rig()) {
            r.w.requestStop().get(6, TimeUnit.SECONDS); r.w.submitText(destination(1, 5));
            r.w.requestResetText("V1 CMD 10 EPOCH 1 ELEVATOR 1 RESET").get(6, TimeUnit.SECONDS);
            r.error(ErrorCode.STALE_EPOCH); r.terminal(10, TerminalKind.COMPLETED);
            equal(1, r.w.snapshot().currentFloor()); check(r.w.snapshot().cabinRequests().isEmpty(), "Inbox viejo no revive");
        }
    }
    public static void malformedResetFailsFutureWithoutAdoptingEpoch() throws Exception {
        try (Rig r = new Rig()) {
            expectFutureFailure(r.w.requestResetText("V1 CMD 10 EPOCH 1 ELEVATOR 1 RESET EXTRA"));
            r.error(ErrorCode.INVALID_FORMAT); equal(0L, r.w.snapshot().epoch());
        }
    }
    public static void serviceCannotEnterThroughResetPriority() throws Exception {
        try (Rig r = new Rig()) {
            expectFutureFailure(r.w.requestResetText(destination(1, 3)));
            r.error(ErrorCode.INVALID_STATE); check(r.w.snapshot().cabinRequests().isEmpty(), "No admitir servicio por control");
        }
    }
    public static void resetCannotEnterThroughOrdinaryServiceQueue() throws Exception {
        try (Rig r = new Rig()) {
            r.w.submitText("V1 CMD 10 EPOCH 1 ELEVATOR 1 RESET"); r.error(ErrorCode.INVALID_STATE);
            equal(0L, r.w.snapshot().epoch());
        }
    }
    public static void controlsPendingT65AreRejectedWithoutFakeAck() throws Exception {
        try (Rig r = new Rig()) {
            for (String verb : List.of("STOP", "START", "PAUSE", "RESUME", "STATUS", "SHUTDOWN")) {
                r.w.submitText(prefix(1) + verb); r.error(ErrorCode.INVALID_STATE);
            }
            check(r.w.failure().isEmpty(), "Actor sigue vivo");
        }
    }
    public static void textualObserversDoNotStealNativeEvents() throws Exception {
        try (Rig r = new Rig()) {
            ProtocolMailbox first = new ProtocolMailbox(), second = new ProtocolMailbox();
            r.w.subscribeProtocol(first); r.w.subscribeProtocol(second);
            r.w.submitText(destination(1, 1)); r.terminal(1, TerminalKind.COMPLETED);
            equal(first.await(LIMIT), second.await(LIMIT));
            equal(ExecutionEvent.Type.COMMAND_ACCEPTED, r.w.awaitEvent(LIMIT).orElseThrow().type());
            equal(ElevatorWorker.Outcome.ACCEPTED, r.w.awaitResult(LIMIT).orElseThrow().outcome());
        }
    }
    public static void unsubscribeStopsFutureFrames() throws Exception {
        try (Rig r = new Rig()) {
            ProtocolMailbox mailbox = new ProtocolMailbox(); r.w.subscribeProtocol(mailbox); r.w.unsubscribeProtocol(mailbox);
            r.w.submitText(destination(1, 1)); r.terminal(1, TerminalKind.COMPLETED);
            check(mailbox.await(Duration.ZERO).isEmpty(), "Sin frames nuevos");
        }
    }
    public static void closeCancelsAcceptedPendingText() throws Exception {
        try (Rig r = new Rig(10_000)) {
            r.w.submitText(destination(1, 5)); r.read(m -> m instanceof Ack);
            r.w.close(); check(r.w.awaitTermination(LIMIT), "Cierre real");
            Terminal cancelled = r.terminal(1, TerminalKind.CANCELLED); equal(Optional.of(CancelReason.SHUTDOWN), cancelled.reason());
        }
    }
    public static void closedOrNewWorkerRefusesToEnqueue() throws Exception {
        var config = new SimulationConfig(1, 8, 25, 25); var w = new ElevatorWorker(1, config, 0);
        check(!w.submitText(destination(1, 3)), "NEW no recibe"); w.close();
        check(!w.submitText(destination(1, 3)), "TERMINATED no recibe");
        equal(0L, w.protocolReception().frames());
    }
    public static void concurrentProducersKeepEveryCorrelation() throws Exception {
        try (Rig r = new Rig(10_000)) {
            var go = new CountDownLatch(1); var done = new CountDownLatch(4); var failure = new AtomicReference<Throwable>();
            List<Thread> producers = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                final int index = i;
                Thread t = new Thread(() -> {
                    try { go.await(); for (int j = 1; j <= 6; j++) { check(r.w.submitText(destination(index * 6 + j, 5)), "Encolado concurrente"); } }
                    catch (Throwable error) { failure.set(error); }
                    finally { done.countDown(); }
                }, "t64-producer-" + i);
                producers.add(t); t.start();
            }
            go.countDown(); check(done.await(6, TimeUnit.SECONDS), "Productores finalizan");
            for (Thread t : producers) { t.join(1000); check(!t.isAlive(), "Productor unido"); }
            if (failure.get() != null) { throw new AssertionError(failure.get()); }
            var ids = new HashSet<Long>();
            for (int i = 0; i < 24; i++) { ids.add(r.read(m -> m instanceof Ack).header().id()); }
            equal(24, ids.size()); equal(24L, r.w.protocolReception().frames());
        }
    }
    public static void mailboxProtectsEdtAndNegativeTimeout() throws Exception {
        var mailbox = new ProtocolMailbox();
        try { mailbox.await(Duration.ofNanos(-1)); throw new AssertionError("Timeout inválido aceptado"); }
        catch (IllegalArgumentException expected) { }
        var failure = new AtomicReference<Throwable>();
        javax.swing.SwingUtilities.invokeAndWait(() -> {
            try { mailbox.await(Duration.ofMillis(1)); failure.set(new AssertionError("EDT bloqueado")); }
            catch (IllegalStateException expected) { }
            catch (InterruptedException error) { failure.set(error); }
        });
        if (failure.get() != null) { throw new AssertionError(failure.get()); }
        check(mailbox.await(Duration.ZERO).isEmpty(), "Sondeo vacío");
    }
    public static void managerDispatchUsesTextAndStillConfirmsService() throws Exception {
        SimulationConfig config = new SimulationConfig(2, 8, 25, 80);
        var a = new ElevatorWorker(1, config, 0); var b = new ElevatorWorker(2, config, 0);
        var ids = new AtomicLong(); var manager = new ManagerWorker(config, 0, List.of(a, b), ids::incrementAndGet);
        try {
            a.start(); b.start(); manager.start(); var request = new HallRequest(id(50), 0, 3, DATE, Direction.UP);
            check(manager.submit(request), "Manejador recibe llamada");
            var result = manager.awaitResult(LIMIT).orElseThrow(); equal(ManagerWorker.Outcome.DISPATCHED, result.outcome());
            var sender = result.command().orElseThrow(); var receiver = sender.elevatorId() == 1 ? a : b;
            var reconstructed = receiver.awaitResult(LIMIT).orElseThrow().command();
            check(reconstructed != sender && reconstructed.sameContent(sender), "Texto real en despacho normal");
            await(() -> manager.call(request.getId()).map(c -> c.status() == HallCallSnapshot.Status.SERVED).orElse(false));
            equal(receiver.threadName(), receiver.protocolReception().processedBy());
        } finally {
            manager.close(); check(manager.awaitTermination(LIMIT), "Manejador cerrado");
            a.close(); b.close(); check(a.awaitTermination(LIMIT) && b.awaitTermination(LIMIT), "Cabinas cerradas");
        }
    }
    public static void controllerDestinationsAndResetUseText() throws Exception {
        var c = new SimulationController(new SimulationConfig(2, 8, 25, 80), 0); var mailbox = new ProtocolMailbox();
        var codec = new ProtocolCodec(c.config()); c.subscribeProtocol(mailbox);
        try {
            c.start().get(6, TimeUnit.SECONDS);
            equal(SimulationController.Outcome.ENQUEUED, c.submit(new CabinRequest(id(70), 0, 3, DATE, 2)).get(6, TimeUnit.SECONDS).outcome());
            await(() -> c.snapshot(2).currentFloor() == 3 && c.snapshot(2).phase() == ElevatorPhase.DWELLING);
            equal(2L, c.protocolReceptions().get(1).frames()); // START y destino, ambos textuales.
            check(codec.decodeResponse(mailbox.await(LIMIT).orElseThrow()).message().orElseThrow() instanceof Ack, "ACK de destino normal");
            c.reset().get(6, TimeUnit.SECONDS); equal(SimulationState.RESET, c.state());
            check(c.protocolReceptions().stream().allMatch(s -> s.frames() >= 1), "Cada reset cruza como texto");
        } finally { c.closeAsync().get(6, TimeUnit.SECONDS); }
    }
    public static void pauseStopAndResumeRetainTextualWork() throws Exception {
        var c = new SimulationController(new SimulationConfig(1, 8, 150, 80), 0);
        try {
            c.start().get(6, TimeUnit.SECONDS); c.submit(new CabinRequest(id(80), 0, 5, DATE, 1)).get(6, TimeUnit.SECONDS);
            await(() -> c.snapshot(1).phase() == ElevatorPhase.MOVING);
            c.pause().get(6, TimeUnit.SECONDS); equal(SimulationState.PAUSED, c.state());
            long remaining = c.snapshot(1).remainingNanos(); equal(1, c.snapshot(1).cabinRequests().size());
            equal(remaining, c.snapshot(1).remainingNanos());
            c.resume().get(6, TimeUnit.SECONDS); c.stop().get(6, TimeUnit.SECONDS);
            equal(SimulationState.STOPPED, c.state()); equal(1, c.snapshot(1).cabinRequests().size());
            c.start().get(6, TimeUnit.SECONDS); await(() -> c.snapshot(1).currentFloor() == 5);
            check(c.protocolReceptions().get(0).frames() >= 6, "Controles textuales sin reenviar el destino.");
        } finally { c.closeAsync().get(6, TimeUnit.SECONDS); }
    }
    public static void syntaxCodecNeverGrantsAdmission() {
        var codec = new ProtocolCodec(new SimulationConfig(1, 2, 1, 1));
        var message = new Destination(new Header(1, 0, 99), id(1), 8);
        String text = codec.encodeSyntax(message);
        check(codec.decodeCommandSyntax(text).isSuccess(), "Forma válida sin edificio");
        equal(ErrorCode.UNKNOWN_ELEVATOR, codec.decodeCommand(text).failure().orElseThrow().code());
        try { codec.encode(message); throw new AssertionError("Codificador estricto omitió edificio"); }
        catch (ProtocolCodec.EncodingException expected) { equal(ErrorCode.UNKNOWN_ELEVATOR, expected.failure().code()); }
    }

    public static void closeTerminatesAcceptedResetInText() throws Exception {
        try (Rig r = new Rig(200)) {
            r.w.submitText(destination(1, 5));
            r.read(m -> m instanceof Move v && v.from() == 2);
            var reset = r.w.requestResetText("V1 CMD 10 EPOCH 1 ELEVATOR 1 RESET");
            r.read(m -> m instanceof Ack a && a.header().id() == 10);
            r.w.close(); check(r.w.awaitTermination(LIMIT), "Join durante retorno");
            Terminal terminal = r.terminal(10, TerminalKind.CANCELLED);
            equal(Optional.of(CancelReason.SHUTDOWN), terminal.reason()); expectFutureFailure(reset);
        }
    }
    public static void closeRejectsQueuedTextWithoutFakeAcceptance() throws Exception {
        try (Rig r = new Rig()) {
            r.w.requestStop().get(6, TimeUnit.SECONDS); r.w.submitText(destination(1, 5));
            r.w.close(); check(r.w.awaitTermination(LIMIT), "Join con inbox pendiente");
            r.error(ErrorCode.WORKER_UNAVAILABLE);
            check(r.seen.stream().noneMatch(m -> m instanceof Ack), "Nunca se admitió la entrada");
        }
    }

    private static String prefix(long id) { return "V1 CMD " + id + " EPOCH 0 ELEVATOR 1 "; }
    private static String destination(long id, int floor) { return prefix(id) + "DESTINATION " + id(id) + " " + floor; }
    private static String pickup(long id, int floor, Direction direction) { return prefix(id) + "PICKUP " + id(id) + " " + floor + " " + direction; }
    private static UUID id(long value) { return new UUID(0, value); }
    private static void check(boolean condition, String detail) { if (!condition) { throw new AssertionError(detail); } }
    private static void equal(Object expected, Object actual) {
        if (!java.util.Objects.equals(expected, actual)) { throw new AssertionError("Esperado " + expected + "; recibido " + actual); }
    }
    private static void await(BooleanSupplier condition) throws Exception {
        long end = System.nanoTime() + LIMIT.toNanos();
        while (!condition.getAsBoolean()) { check(System.nanoTime() < end, "Plazo agotado"); Thread.sleep(2); }
    }
    private static void expectFutureFailure(java.util.concurrent.CompletableFuture<?> future) throws Exception {
        try { future.get(6, TimeUnit.SECONDS); throw new AssertionError("Se esperaba rechazo"); }
        catch (java.util.concurrent.ExecutionException expected) { }
    }
    private static final class Rig implements AutoCloseable {
        final SimulationConfig config;
        final ElevatorWorker w;
        final ProtocolCodec codec;
        final List<ProtocolMessage> seen = new ArrayList<>();
        Rig() { this(25); }
        Rig(long move) {
            config = new SimulationConfig(2, 8, move, 150); codec = new ProtocolCodec(config);
            w = new ElevatorWorker(1, config, 0); check(w.start(), "Arranque");
        }
        ProtocolMessage read(Predicate<ProtocolMessage> filter) throws Exception {
            long end = System.nanoTime() + LIMIT.toNanos();
            while (System.nanoTime() < end) {
                var text = w.awaitProtocolMessage(Duration.ofMillis(100));
                if (text.isPresent()) {
                    var parsed = codec.decodeResponse(text.orElseThrow());
                    check(parsed.isSuccess(), "Respuesta V1 válida: " + text.orElseThrow());
                    ProtocolMessage message = parsed.message().orElseThrow(); seen.add(message);
                    if (filter.test(message)) { return message; }
                }
            }
            throw new AssertionError("No llegó respuesta esperada; vistos=" + seen);
        }
        Terminal terminal(long id, TerminalKind kind) throws Exception {
            return (Terminal) read(m -> m instanceof Terminal t && t.header().id() == id && t.kind() == kind);
        }
        ProtocolMessage.Error error(ErrorCode code) throws Exception {
            return (ProtocolMessage.Error) read(m -> m instanceof ProtocolMessage.Error e && e.code() == code);
        }
        @Override public void close() throws Exception {
            w.close(); check(w.awaitTermination(LIMIT), "Join real"); check(w.failure().isEmpty(), "Actor sin fallo");
        }
    }
    public static void main(String[] args) throws Exception {
        int passed = 0;
        for (var method : TextTransportScenarios.class.getDeclaredMethods()) {
            if (java.lang.reflect.Modifier.isPublic(method.getModifiers())
                    && java.lang.reflect.Modifier.isStatic(method.getModifiers()) && method.getParameterCount() == 0) {
                try { method.invoke(null); passed++; System.out.println("T64_OK " + method.getName()); }
                catch (java.lang.reflect.InvocationTargetException ex) { throw new AssertionError("Fallo " + method.getName(), ex.getCause()); }
            }
        }
        System.out.println("T64_RESULT scenarios=" + passed + "; failures=0");
    }
}
```

## src/test/java/com/elevatormanager/ui/AnimationSynchronizationScenarios.java

```java
package com.elevatormanager.ui;

import com.elevatormanager.model.*;
import com.elevatormanager.model.request.CabinRequest;
import com.elevatormanager.simulation.SimulationController;
import com.elevatormanager.simulation.time.PausableClock;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import javax.swing.SwingUtilities;

/** T60: controles temporales con reloj virtual, capturas bloqueadas y actores reales. */
public final class AnimationSynchronizationScenarios {
    private static final long MS = 1_000_000L;
    private static final UUID SESSION = UUID.randomUUID();
    private static final SimulationConfig CONFIG = new SimulationConfig(2, 6, 1000, 100);
    private AnimationSynchronizationScenarios() { }

    public static void oldMovingFrameFreezesAtActualPauseInstant() throws Exception { edt(() -> {
        var r = new VirtualRig(); r.show(moving(1, Direction.UP, 0, 1000 * MS, 0, false));
        r.wall.set(300 * MS); r.view.set(new PausableClock.View(300 * MS, 300 * MS, true));
        near(r.panel.displayedPosition(1), 1.3);
        r.wall.addAndGet(60_000 * MS); near(r.panel.displayedPosition(1), 1.3);
    }); }
    public static void lateHeldFrameDoesNotMovePausedPosition() throws Exception { edt(() -> {
        var r = new VirtualRig(); r.show(moving(1, Direction.UP, 0, 1000 * MS, 0, false));
        r.view.set(new PausableClock.View(300 * MS, 300 * MS, true)); r.wall.set(9000 * MS);
        double before = r.panel.displayedPosition(1);
        r.show(moving(1, Direction.UP, 300 * MS, 700 * MS, 0, true));
        near(r.panel.displayedPosition(1), before);
    }); }
    public static void oldHeldFrameContinuesWithoutPausedWallTime() throws Exception { edt(() -> {
        var r = new VirtualRig(); r.show(moving(1, Direction.UP, 300 * MS, 700 * MS, 0, true));
        r.wall.set(9000 * MS); r.view.set(new PausableClock.View(300 * MS, 9000 * MS, false));
        near(r.panel.displayedPosition(1), 1.3);
        r.wall.addAndGet(200 * MS); near(r.panel.displayedPosition(1), 1.5);
    }); }
    public static void repeatedPauseCyclesPreservePositionAndBudget() throws Exception { edt(() -> {
        var r = new VirtualRig(); r.show(moving(1, Direction.UP, 0, 1000 * MS, 0, false));
        r.wall.set(200 * MS); r.view.set(new PausableClock.View(200 * MS, 200 * MS, true));
        r.wall.set(5200 * MS); near(r.panel.displayedPosition(1), 1.2);
        r.view.set(new PausableClock.View(200 * MS, 5200 * MS, false));
        r.wall.set(5400 * MS); near(r.panel.displayedPosition(1), 1.4);
        r.view.set(new PausableClock.View(400 * MS, 5400 * MS, true));
        r.wall.set(15400 * MS); near(r.panel.displayedPosition(1), 1.4);
        r.view.set(new PausableClock.View(400 * MS, 15400 * MS, false));
        r.wall.set(16000 * MS); near(r.panel.displayedPosition(1), 2);
    }); }
    public static void stopFinishesOnlyPublishedAdjacentLeg() throws Exception { edt(() -> {
        var r = new VirtualRig(); r.show(moving(2, Direction.UP, 0, 1000 * MS, 0, false));
        r.wall.set(600 * MS); near(r.panel.displayedPosition(1), 2.6);
        r.wall.set(1000 * MS); near(r.panel.displayedPosition(1), 3);
        r.show(idle(3, 0)); r.wall.set(9000 * MS); near(r.panel.displayedPosition(1), 3);
    }); }
    public static void stopFromPausedMovementReleasesHeldProjection() throws Exception { edt(() -> {
        var r = new VirtualRig(); r.show(moving(2, Direction.UP, 300 * MS, 700 * MS, 0, true));
        r.wall.set(9000 * MS); r.view.set(new PausableClock.View(300 * MS, 9000 * MS, false));
        r.wall.set(9400 * MS); near(r.panel.displayedPosition(1), 2.7);
        r.wall.set(9700 * MS); near(r.panel.displayedPosition(1), 3);
    }); }
    public static void startFromStoppedDoesNotResetPosition() throws Exception { edt(() -> {
        var r = new VirtualRig(); r.show(idle(3, 0)); r.wall.set(8000 * MS);
        near(r.panel.displayedPosition(1), 3);
        r.show(moving(3, Direction.UP, 8000 * MS, 1000 * MS, 0, false));
        r.wall.set(8250 * MS); near(r.panel.displayedPosition(1), 3.25);
    }); }
    public static void resetRetainsPhysicalLegAcrossEpochChange() throws Exception { edt(() -> {
        var r = new VirtualRig(); r.show(moving(3, Direction.UP, 0, 1000 * MS, 0, false));
        r.wall.set(400 * MS); double before = r.panel.displayedPosition(1);
        var stale = r.frame(moving(1, Direction.UP, 0, 1000 * MS, 0, false));
        r.panel.render(stale, 1); near(r.panel.displayedPosition(1), before);
        r.show(moving(3, Direction.UP, 0, 1000 * MS, 1, false));
        near(r.panel.displayedPosition(1), before); check(r.panel.displayedFloor(1) == 3, "Reset no coloca cabina en piso 1");
    }); }
    public static void resetReturnMovesDownThroughIntermediatePositions() throws Exception { edt(() -> {
        var r = new VirtualRig(); r.show(moving(4, Direction.DOWN, 1000 * MS, 1000 * MS, 1, false));
        r.wall.set(1250 * MS); near(r.panel.displayedPosition(1), 3.75);
        r.wall.set(2000 * MS); near(r.panel.displayedPosition(1), 3);
        r.show(moving(3, Direction.DOWN, 2000 * MS, 1000 * MS, 1, false));
        r.wall.set(2500 * MS); near(r.panel.displayedPosition(1), 2.5);
    }); }
    public static void resetFromPausedHeldFramePreservesRemainder() throws Exception { edt(() -> {
        var r = new VirtualRig(); r.show(moving(3, Direction.UP, 400 * MS, 600 * MS, 0, true));
        r.wall.set(9000 * MS); r.view.set(new PausableClock.View(400 * MS, 9000 * MS, false));
        r.wall.set(9300 * MS); near(r.panel.displayedPosition(1), 3.7);
        r.show(moving(3, Direction.UP, 700 * MS, 300 * MS, 1, false));
        near(r.panel.displayedPosition(1), 3.7);
    }); }
    public static void closeFreezesEvenWhenLastFrameWasHeld() throws Exception { edt(() -> {
        var r = new VirtualRig(); r.show(moving(2, Direction.UP, 300 * MS, 700 * MS, 0, true));
        r.view.set(new PausableClock.View(300 * MS, 9000 * MS, false)); r.wall.set(9200 * MS);
        near(r.panel.displayedPosition(1), 2.5); r.panel.freezeAnimation();
        r.wall.set(60000 * MS); near(r.panel.displayedPosition(1), 2.5);
        r.show(idle(6, 0)); near(r.panel.displayedPosition(1), 2.5);
        r.panel.freezeAnimation(); near(r.panel.displayedPosition(1), 2.5);
    }); }
    public static void invalidationDiscardsLateCapture() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var second = new CountDownLatch(1); var releaseSecond = new CountDownLatch(1);
        var calls = new AtomicInteger(); var clock = new AtomicLong();
        var pump = new UiSnapshotPump(() -> {
            if (calls.incrementAndGet() == 1) { entered.countDown(); hold(release); }
            else { second.countDown(); hold(releaseSecond); }
            return emptyFrame();
        }, clock::get);
        try {
            pump.request(); check(entered.await(3, TimeUnit.SECONDS), "Captura iniciada");
            pump.invalidate(); release.countDown(); clock.set(UiSnapshotPump.INTERVAL_NANOS);
            await(() -> { pump.request(); return second.getCount() == 0; });
            check(pump.latest().isEmpty(), "Captura invalidada no reaparece antes de la siguiente");
        } finally { release.countDown(); releaseSecond.countDown(); pump.closeAsync().get(3, TimeUnit.SECONDS); }
    }
    public static void freshCaptureWaitsForOneWorkerAndBypassesGate() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var calls = new AtomicInteger(); var active = new AtomicInteger(); var maximum = new AtomicInteger();
        var pump = new UiSnapshotPump(() -> {
            int count = calls.incrementAndGet(); maximum.accumulateAndGet(active.incrementAndGet(), Math::max);
            try { if (count == 1) { entered.countDown(); hold(release); } return emptyFrame(); }
            finally { active.decrementAndGet(); }
        }, () -> 0L);
        try {
            pump.request(); check(entered.await(3, TimeUnit.SECONDS), "Captura retenida");
            pump.requestFresh(); release.countDown(); await(() -> calls.get() == 2 && pump.latest().isPresent());
            check(maximum.get() == 1, "Un solo capturador incluso con solicitud urgente");
        } finally { release.countDown(); pump.closeAsync().get(3, TimeUnit.SECONDS); }
    }
    public static void invalidatedFailureCannotReplaceFreshStatus() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1); var calls = new AtomicInteger();
        var second = new CountDownLatch(1); var releaseSecond = new CountDownLatch(1);
        var pump = new UiSnapshotPump(() -> {
            if (calls.incrementAndGet() == 1) { entered.countDown(); hold(release); throw new IllegalStateException("captura antigua"); }
            second.countDown(); hold(releaseSecond); return emptyFrame();
        }, () -> 0L);
        try {
            pump.request(); check(entered.await(3, TimeUnit.SECONDS), "Primera captura"); pump.requestFresh();
            release.countDown(); check(second.await(3, TimeUnit.SECONDS), "Segunda captura retenida");
            check(pump.failure().isEmpty(), "Error antiguo descartado antes del éxito siguiente");
            releaseSecond.countDown(); await(() -> pump.latest().isPresent());
        } finally { release.countDown(); releaseSecond.countDown(); pump.closeAsync().get(3, TimeUnit.SECONDS); }
    }
    public static void closeCancelsPendingFreshCapture() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1); var calls = new AtomicInteger();
        var pump = new UiSnapshotPump(() -> { calls.incrementAndGet(); entered.countDown(); hold(release); return emptyFrame(); }, () -> 0L);
        try {
            pump.request(); check(entered.await(3, TimeUnit.SECONDS), "Captura retenida"); pump.requestFresh();
            var close = pump.closeAsync(); release.countDown(); close.get(3, TimeUnit.SECONDS);
            check(calls.get() == 1 && pump.latest().isEmpty(), "Cierre no ejecuta la captura pendiente");
        } finally { release.countDown(); pump.closeAsync().get(3, TimeUnit.SECONDS); }
    }
    public static void realPauseResumeKeepsExactPosition() throws Exception {
        try (var r = new RealRig(3000, 200)) {
            r.startAndGo(1, 6); await(() -> r.c.snapshot(1).phase() == ElevatorPhase.MOVING);
            Thread.sleep(100); var movingFrame = UiSnapshotPump.capture(r.c); r.c.pause().get(5, TimeUnit.SECONDS);
            r.render(movingFrame); double frozen = r.position(1); Thread.sleep(80); near(r.position(1), frozen);
            r.render(UiSnapshotPump.capture(r.c)); near(r.position(1), frozen);
            r.c.resume().get(5, TimeUnit.SECONDS); Thread.sleep(60);
            check(r.position(1) > frozen && r.position(1) < 2, "Reanuda un HELD antiguo usando solo tiempo activo");
        }
    }
    public static void realDwellAndIdleRemainFixedWhilePaused() throws Exception {
        try (var r = new RealRig(300, 1000)) {
            r.startAndGo(1, 1); await(() -> r.c.snapshot(1).phase() == ElevatorPhase.DWELLING);
            r.c.pause().get(5, TimeUnit.SECONDS); r.render(UiSnapshotPump.capture(r.c));
            Thread.sleep(80); near(r.position(1), 1); near(r.position(2), 1);
            check(r.c.snapshot(1).suspendedPhase().orElseThrow() == ElevatorPhase.DWELLING, "Parada real pausada");
        }
    }
    public static void realStopFromPauseCompletesAdjacentLeg() throws Exception {
        try (var r = new RealRig(350, 100)) {
            r.startAndGo(1, 6); await(() -> r.c.snapshot(1).phase() == ElevatorPhase.MOVING);
            r.c.pause().get(5, TimeUnit.SECONDS); r.render(UiSnapshotPump.capture(r.c));
            double frozen = r.position(1); var stopped = r.c.stop();
            await(() -> !r.c.timeView().paused()); Thread.sleep(50);
            check(r.position(1) > frozen && r.position(1) <= 2, "Stop libera el resto del tramo pausado");
            stopped.get(5, TimeUnit.SECONDS); r.render(UiSnapshotPump.capture(r.c)); near(r.position(1), 2);
            check(r.c.snapshot(1).cabinRequests().size() == 1, "Stop no sirve ni elimina el destino");
        }
    }
    public static void realResetFromPauseShowsPhysicalReturn() throws Exception {
        try (var r = new RealRig(250, 100)) {
            r.startAndGo(1, 6); await(() -> r.c.snapshot(1).currentFloor() >= 3
                    && r.c.snapshot(1).phase() == ElevatorPhase.MOVING);
            r.c.pause().get(5, TimeUnit.SECONDS); r.render(UiSnapshotPump.capture(r.c));
            double before = r.position(1); var reset = r.c.reset(); var sawDown = new AtomicBoolean();
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(6);
            while (!reset.isDone()) {
                var f = UiSnapshotPump.capture(r.c);
                if (f != null) {
                    r.render(f); var s = f.snapshots().get(0); double position = r.position(1);
                    if (s.mode() == ElevatorSnapshot.Mode.RESET_RETURN && s.direction() == Direction.DOWN
                            && s.phase() == ElevatorPhase.MOVING && position > s.nextFloor().orElseThrow()
                            && position < s.currentFloor()) sawDown.set(true);
                }
                check(System.nanoTime() < end, "Plazo de retorno físico"); Thread.sleep(10);
            }
            reset.get(3, TimeUnit.SECONDS); r.render(UiSnapshotPump.capture(r.c));
            check(before > 2 && sawDown.get(), "Retorno observado entre pisos, sin teleportar a 1");
            near(r.position(1), 1); check(r.c.epoch() == 1 && r.c.snapshot(1).direction() == Direction.UP, "Reset final 1/UP");
        }
    }
    public static void systemButtonsSynchronizeAndCloseAnimation() throws Exception {
        var panel = onEdt(() -> new SystemControlPanel(cfg -> new SimulationController(cfg, 0), Optional::empty));
        try {
            edt(() -> {
                var f = panel.configurationPanel();
                f.input(SimulationConfig.Field.ELEVATORS).setText("2"); f.input(SimulationConfig.Field.FLOORS).setText("4");
                f.input(SimulationConfig.Field.MOVEMENT).setText("300"); f.input(SimulationConfig.Field.DWELL).setText("300");
                f.validateButton().doClick(0);
            });
            await(() -> value(() -> panel.controller().isPresent())); var c = onEdt(() -> panel.controller().orElseThrow());
            click(panel, SystemControlPanel.Action.START); await(() -> c.state() == SimulationState.RUNNING);
            await(() -> value(() -> panel.cabinDashboard().cabin(1).destinationButton(4).isEnabled()));
            edt(() -> panel.cabinDashboard().cabin(1).destinationButton(4).doClick(0));
            await(() -> c.snapshot(1).phase() == ElevatorPhase.MOVING);
            click(panel, SystemControlPanel.Action.PAUSE); await(() -> c.state() == SimulationState.PAUSED);
            await(() -> value(() -> panel.snapshotPump().latest().isPresent()));
            double frozen = onEdt(() -> { panel.updateView(); return panel.buildingPanel().displayedPosition(1); });
            Thread.sleep(80); near(onEdt(() -> panel.buildingPanel().displayedPosition(1)), frozen);
            click(panel, SystemControlPanel.Action.RESUME); await(() -> c.state() == SimulationState.RUNNING);
            click(panel, SystemControlPanel.Action.STOP); await(() -> c.state() == SimulationState.STOPPED);
            click(panel, SystemControlPanel.Action.START); await(() -> c.state() == SimulationState.RUNNING);
            click(panel, SystemControlPanel.Action.RESET); await(() -> c.state() == SimulationState.RESET);
            await(() -> value(() -> { panel.updateView(); return panel.buildingPanel().displayedPosition(1) == 1; }));
            check(c.epoch() == 1, "Botón Reset renueva época sin reemplazar sesión");
        } finally {
            var closing = onEdt(panel::closeAsync); double frozen = onEdt(() -> panel.buildingPanel().displayedPosition(1));
            closing.get(5, TimeUnit.SECONDS); near(onEdt(() -> panel.buildingPanel().displayedPosition(1)), frozen);
            edt(panel::closed);
        }
    }

    private static final class VirtualRig {
        final AtomicLong wall = new AtomicLong();
        final AtomicReference<PausableClock.View> view = new AtomicReference<>(new PausableClock.View(0, 0, false));
        final BuildingPanel panel = new BuildingPanel(wall::get);
        VirtualRig() { panel.bind(SESSION, CONFIG); panel.useTimeView(view::get); panel.setSize(800, 500); }
        UiSnapshotPump.Frame frame(ElevatorSnapshot s) {
            return new UiSnapshotPump.Frame(SESSION, s.epoch(), List.of(s, idle(2, 1, s.epoch())), Map.of(),
                    Optional.of(new UiSnapshotPump.Timing(0, 0, true)));
        }
        void show(ElevatorSnapshot s) { panel.render(frame(s), s.epoch()); }
    }
    private static final class RealRig implements AutoCloseable {
        final SimulationController c;
        final BuildingPanel panel;
        RealRig(long movement, long dwell) throws Exception {
            c = new SimulationController(new SimulationConfig(2, 6, movement, dwell), 0);
            panel = onEdt(() -> { var p = new BuildingPanel(); p.bind(c.sessionId(), c.config()); p.useTimeView(c::timeView); return p; });
        }
        void startAndGo(int id, int floor) throws Exception {
            c.start().get(5, TimeUnit.SECONDS);
            c.submit(new CabinRequest(UUID.randomUUID(), c.epoch(), floor, Instant.now(), id)).get(5, TimeUnit.SECONDS);
        }
        void render(UiSnapshotPump.Frame f) throws Exception { if (f != null) edt(() -> panel.render(f, c.epoch())); }
        double position(int id) throws Exception { return onEdt(() -> panel.displayedPosition(id)); }
        @Override public void close() throws Exception { c.closeAsync().get(5, TimeUnit.SECONDS); }
    }
    private static ElevatorSnapshot moving(int floor, Direction direction, long reference, long remaining, long epoch, boolean held) {
        return new ElevatorSnapshot(1, CONFIG, epoch, 1, floor, OptionalInt.of(floor + direction.floorDelta()), direction,
                held ? ElevatorPhase.HELD : ElevatorPhase.MOVING, held ? Optional.of(ElevatorPhase.MOVING) : Optional.empty(),
                held ? Optional.of(ElevatorSnapshot.HoldReason.PAUSE) : Optional.empty(),
                epoch > 0 ? ElevatorSnapshot.Mode.RESET_RETURN : ElevatorSnapshot.Mode.NORMAL,
                1000 * MS, remaining, reference, OptionalInt.empty(), List.of(), List.of());
    }
    private static ElevatorSnapshot idle(int floor, long epoch) { return idle(1, floor, epoch); }
    private static ElevatorSnapshot idle(int id, int floor, long epoch) {
        return new ElevatorSnapshot(id, CONFIG, epoch, 2, floor, OptionalInt.empty(), Direction.UP, ElevatorPhase.IDLE,
                Optional.empty(), Optional.empty(), ElevatorSnapshot.Mode.NORMAL, 0, 0, 0, OptionalInt.empty(), List.of(), List.of());
    }
    private static UiSnapshotPump.Frame emptyFrame() { return new UiSnapshotPump.Frame(SESSION, 0, List.of(), Map.of()); }
    private static void click(SystemControlPanel p, SystemControlPanel.Action a) throws Exception {
        await(() -> value(() -> { p.updateView(); return p.button(a).isEnabled(); }));
        edt(() -> p.button(a).doClick(0));
    }
    private static void hold(CountDownLatch latch) {
        boolean interrupted = false;
        while (true) {
            try { latch.await(); break; } catch (InterruptedException e) { interrupted = true; }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }
    private static void near(double actual, double expected) {
        if (Math.abs(actual - expected) > 1e-9) throw new AssertionError("Esperado " + expected + ", obtenido " + actual);
    }
    private static void check(boolean condition, String detail) { if (!condition) throw new AssertionError(detail); }
    private static <T> T onEdt(Callable<T> action) throws Exception {
        var task = new FutureTask<>(action); SwingUtilities.invokeAndWait(task); return task.get(8, TimeUnit.SECONDS);
    }
    private static void edt(Runnable action) throws Exception { onEdt(() -> { action.run(); return null; }); }
    private static boolean value(Callable<Boolean> action) {
        try { return onEdt(action); } catch (Exception e) { throw new RuntimeException(e); }
    }
    private static void await(BooleanSupplier condition) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (!condition.getAsBoolean()) { check(System.nanoTime() < end, "Plazo agotado"); Thread.sleep(5); }
    }
    public static void main(String[] args) throws Exception {
        oldMovingFrameFreezesAtActualPauseInstant(); lateHeldFrameDoesNotMovePausedPosition();
        oldHeldFrameContinuesWithoutPausedWallTime(); repeatedPauseCyclesPreservePositionAndBudget();
        stopFinishesOnlyPublishedAdjacentLeg(); stopFromPausedMovementReleasesHeldProjection();
        startFromStoppedDoesNotResetPosition(); resetRetainsPhysicalLegAcrossEpochChange();
        resetReturnMovesDownThroughIntermediatePositions(); resetFromPausedHeldFramePreservesRemainder();
        closeFreezesEvenWhenLastFrameWasHeld(); invalidationDiscardsLateCapture();
        freshCaptureWaitsForOneWorkerAndBypassesGate(); invalidatedFailureCannotReplaceFreshStatus();
        closeCancelsPendingFreshCapture(); realPauseResumeKeepsExactPosition(); realDwellAndIdleRemainFixedWhilePaused();
        realStopFromPauseCompletesAdjacentLeg(); realResetFromPauseShowsPhysicalReturn();
        systemButtonsSynchronizeAndCloseAnimation(); System.out.println("T60: 20 escenarios de sincronización satisfactorios.");
    }
}
```

## src/test/java/com/elevatormanager/ui/SystemControlsScenarios.java

```java
package com.elevatormanager.ui;

import com.elevatormanager.model.*;
import com.elevatormanager.model.SimulationConfig.Field;
import com.elevatormanager.model.request.CabinRequest;
import com.elevatormanager.simulation.SimulationController;
import com.elevatormanager.ui.SystemControlPanel.Action;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import javax.swing.SwingUtilities;

/** T48: controles Swing en EDT y sesiones reales; selección inyectada sin diálogo nativo. */
public final class SystemControlsScenarios {
    private SystemControlsScenarios() { }
    public static void initialControlsRequireConfiguration() throws Exception {
        try (var rig = new Rig()) {
            edt(() -> { for (Action a : Action.values()) check(rig.enabled(a) == (a == Action.CLOSE), "Sin sesión " + a); });
        }
    }

    public static void invalidConfigurationDoesNotOpenSession() throws Exception {
        try (var rig = new Rig()) {
            edt(() -> rig.panel.configurationPanel().validateConfiguration());
            check(rig.opened.get() == 0 && rig.controller() == null, "No crea sesión inválida");
        }
    }

    public static void preparationRunsOutsideEdtAndLocksFields() throws Exception {
        try (var rig = new Rig()) {
            rig.prepare();
            check(rig.opened.get() == 1 && !rig.openedOnEdt.get(), "Apertura fuera de EDT");
            edt(() -> check(!rig.panel.configurationPanel().input(Field.ELEVATORS).isEnabled(), "Configuración bloqueada"));
        }
    }

    public static void resetEnablesOnlyLegalControls() throws Exception {
        try (var rig = new Rig()) {
            rig.prepare();
            edt(() -> { check(rig.enabled(Action.START) && rig.enabled(Action.RESET) && rig.enabled(Action.LOAD), "RESET habilita");
                check(!rig.enabled(Action.STOP), "RESET no Stop"); });
        }
    }

    public static void startStopAndContinuePreserveSession() throws Exception {
        try (var rig = new Rig()) {
            rig.prepare(); var original = rig.controller();
            rig.click(Action.START); rig.awaitState(SimulationState.RUNNING);
            rig.click(Action.STOP); rig.awaitState(SimulationState.STOPPED);
            edt(() -> check(rig.enabled(Action.START) && !rig.enabled(Action.LOAD), "STOPPED continúa sin importar"));
            rig.click(Action.START); rig.awaitState(SimulationState.RUNNING);
            check(rig.controller() == original && rig.opened.get() == 1, "Mismos actores y sesión");
        }
    }

    public static void stoppingIndicatorAndResetRemainAvailable() throws Exception {
        try (var rig = new Rig()) {
            rig.prepare(); rig.moving();
            edt(() -> { rig.panel.button(Action.STOP).doClick(0);
                check(rig.panel.stateText().contains("Deteniendo"), "STOPPING visible");
                check(!rig.enabled(Action.START) && !rig.enabled(Action.LOAD) && rig.enabled(Action.RESET), "Controles durante Stop"); });
            rig.awaitState(SimulationState.STOPPED);
        }
    }

    public static void resettingIndicatorWaitsForReturn() throws Exception {
        try (var rig = new Rig()) {
            rig.prepare(); rig.moving();
            edt(() -> { rig.panel.button(Action.RESET).doClick(0);
                check(rig.panel.stateText().contains("Reseteando"), "RESETTING visible");
                check(!rig.enabled(Action.START) && !rig.enabled(Action.LOAD), "No inicia ni importa durante retorno"); });
            rig.awaitState(SimulationState.RESET);
            check(rig.controller().snapshots().stream().allMatch(s -> s.currentFloor() == 1), "Retorno real confirmado");
        }
    }

    public static void importPreparesWithoutMoving() throws Exception {
        try (var rig = new Rig()) {
            rig.prepare(); rig.select("CABIN 1 4\n"); rig.click(Action.LOAD);
            waitFor(() -> rig.controller().preparedBatch().isPresent());
            waitFor(() -> onEdt(() -> rig.enabled(Action.LOAD)));
            check(rig.controller().state() == SimulationState.RESET && rig.controller().snapshots().get(0).currentFloor() == 1, "Preparación sin viaje");
            edt(() -> check(rig.panel.stateText().contains("Lote preparado: 1"), "Cantidad visible"));
        }
    }

    public static void loadDisabledInRunningAndStopped() throws Exception {
        try (var rig = new Rig()) {
            rig.prepare(); rig.click(Action.START); rig.awaitState(SimulationState.RUNNING);
            edt(() -> check(!rig.enabled(Action.LOAD), "RUNNING no carga"));
            rig.click(Action.STOP); rig.awaitState(SimulationState.STOPPED);
            edt(() -> check(!rig.enabled(Action.LOAD), "STOPPED no carga"));
        }
    }

    public static void cancelSelectionPreservesPreparedBatch() throws Exception {
        try (var rig = new Rig()) {
            rig.prepare(); rig.select("CABIN 1 4\n"); rig.click(Action.LOAD);
            waitFor(() -> onEdt(() -> rig.enabled(Action.LOAD)));
            var old = rig.controller().preparedBatch().orElseThrow(); rig.selected.set(Optional.empty());
            rig.click(Action.LOAD);
            edt(() -> check(rig.panel.detailText().contains("cancelada"), "Cancelación visible"));
            check(rig.controller().preparedBatch().orElseThrow() == old, "Conserva lote");
        }
    }

    public static void invalidImportKeepsPreviousBatchAndShowsLine() throws Exception {
        try (var rig = new Rig()) {
            rig.prepare(); rig.select("CABIN 1 4\n"); rig.click(Action.LOAD);
            waitFor(() -> onEdt(() -> rig.enabled(Action.LOAD)));
            var old = rig.controller().preparedBatch().orElseThrow(); rig.select("CABIN 1 4\nCABIN 1 99\n");
            rig.click(Action.LOAD); waitFor(() -> onEdt(() -> rig.enabled(Action.LOAD)));
            check(rig.controller().preparedBatch().orElseThrow() == old, "Atómico");
            edt(() -> check(rig.panel.detailText().contains("Línea 2"), "Diagnóstico visible"));
        }
    }

    public static void resetClearsImportedBatch() throws Exception {
        try (var rig = new Rig()) {
            rig.prepare(); rig.select("CABIN 1 4\n"); rig.click(Action.LOAD);
            waitFor(() -> onEdt(() -> rig.enabled(Action.LOAD)));
            rig.click(Action.RESET); rig.awaitState(SimulationState.RESET);
            check(rig.controller().preparedBatch().isEmpty(), "Reset elimina lote");
            edt(() -> check(rig.panel.stateText().contains("Lote preparado: 0"), "Cantidad actualizada"));
        }
    }

    public static void closeWithoutConfigurationCompletes() throws Exception {
        try (var rig = new Rig()) {
            var done = onEdt(() -> rig.panel.closeAsync()); done.get(5, TimeUnit.SECONDS);
            edt(() -> { rig.panel.closed(); check(!rig.enabled(Action.CLOSE), "Cerrada sin sesión"); });
        }
    }

    public static void closeDuringPreparationReleasesLateSession() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var created = new AtomicReference<SimulationController>();
        try (var rig = new Rig(config -> {
            entered.countDown(); check(release.await(5, TimeUnit.SECONDS), "Liberar apertura");
            var c = new SimulationController(config, 0); created.set(c); return c;
        })) {
            rig.confirm(); check(entered.await(5, TimeUnit.SECONDS), "Apertura activa");
            var done = onEdt(() -> rig.panel.closeAsync());
            check(!done.isDone(), "Cierre espera apertura"); release.countDown(); done.get(5, TimeUnit.SECONDS);
            check(created.get().awaitTermination(Duration.ofSeconds(5)), "Cerró sesión tardía y actores");
            edt(rig.panel::closed);
        } finally { release.countDown(); }
    }

    public static void preparationFailureAllowsCorrectionAndRetry() throws Exception {
        var attempts = new AtomicInteger();
        try (var rig = new Rig(config -> {
            if (attempts.incrementAndGet() == 1) throw new java.io.IOException("fallo de apertura inyectado");
            return new SimulationController(config, 0);
        })) {
            rig.confirm(); waitFor(() -> onEdt(() -> rig.panel.configurationPanel().input(Field.ELEVATORS).isEnabled()));
            edt(() -> check(rig.panel.detailText().contains("fallo de apertura"), "Fallo visible"));
            rig.prepare(); check(attempts.get() == 2, "Reintento válido");
        }
    }

    public static void closeDuringRunningTerminatesWorkers() throws Exception {
        try (var rig = new Rig()) {
            rig.prepare(); rig.moving(); var c = rig.controller();
            onEdt(() -> rig.panel.closeAsync()).get(5, TimeUnit.SECONDS);
            check(c.awaitTermination(Duration.ofSeconds(5)), "Cierre real con movimiento"); edt(rig.panel::closed);
        }
    }

    public static void closeDuringStopIgnoresLateControlReceipt() throws Exception {
        try (var rig = new Rig()) {
            rig.prepare(); rig.moving();
            var done = onEdt(() -> { rig.panel.button(Action.STOP).doClick(0); return rig.panel.closeAsync(); });
            done.get(5, TimeUnit.SECONDS);
            edt(() -> check(rig.panel.detailText().contains("Cerrando"), "Stop tardío no restaura UI"));
            edt(rig.panel::closed);
        }
    }

    public static void closeDuringResetTerminatesWorkers() throws Exception {
        try (var rig = new Rig()) {
            rig.prepare(); rig.moving();
            onEdt(() -> { rig.panel.button(Action.RESET).doClick(0); return rig.panel.closeAsync(); }).get(5, TimeUnit.SECONDS);
            check(rig.controller().awaitTermination(Duration.ofSeconds(5)), "Cierre durante retorno"); edt(rig.panel::closed);
        }
    }

    public static void repeatedConfirmationDoesNotCreateSecondSession() throws Exception {
        try (var rig = new Rig()) {
            rig.prepare();
            edt(() -> { rig.panel.configurationPanel().validateConfiguration(); rig.panel.configurationPanel().validateConfiguration(); });
            check(rig.opened.get() == 1, "Una sesión");
        }
    }

    public static void realLogFilesAreCreatedAndReleased() throws Exception {
        Path directory = Files.createTempDirectory("t48-log-");
        try {
            try (var rig = new Rig(config -> SystemControlPanel.openSession(config, directory))) {
                rig.prepare(); rig.click(Action.START); rig.awaitState(SimulationState.RUNNING);
                rig.click(Action.STOP); rig.awaitState(SimulationState.STOPPED);
                onEdt(() -> rig.panel.closeAsync()).get(5, TimeUnit.SECONDS); edt(rig.panel::closed);
                check(Files.size(directory.resolve("elevador1.log")) > 0 && Files.size(directory.resolve("manejador.log")) > 0, "Logs reales");
            }
            try (var log = com.elevatormanager.logging.SessionLog.open(directory, 1, java.io.OutputStream.nullOutputStream())) {
                check(log.isAlive(), "Lock liberado para reapertura");
            }
        } finally { deleteTree(directory); }
    }

    public static void offEdtAccessIsRejected() throws Exception {
        try { new SystemControlPanel(); throw new AssertionError("Constructor fuera EDT"); }
        catch (IllegalStateException expected) { }
        try (var rig = new Rig()) {
            try { rig.panel.closeAsync(); throw new AssertionError("Cierre visual fuera EDT"); }
            catch (IllegalStateException expected) { }
            try { rig.panel.controller(); throw new AssertionError("Lectura fuera EDT"); }
            catch (IllegalStateException expected) { }
        }
    }

    public static void edtRespondsDuringPreparation() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        try (var rig = new Rig(config -> {
            entered.countDown(); check(release.await(5, TimeUnit.SECONDS), "Liberar preparación"); return new SimulationController(config, 0);
        })) {
            rig.confirm(); check(entered.await(5, TimeUnit.SECONDS), "Preparación iniciada");
            edt(() -> check(rig.enabled(Action.CLOSE) && !rig.enabled(Action.START), "EDT disponible durante apertura"));
            release.countDown(); waitFor(() -> rig.controller() != null);
        } finally { release.countDown(); }
    }

    public static void closeFailureKeepsPanelAndRetryAvailable() throws Exception {
        try (var rig = new Rig()) {
            rig.prepare();
            rig.controller().registerResource(() -> { throw new java.io.IOException("cierre inyectado"); }).get(5, TimeUnit.SECONDS);
            var result = onEdt(() -> rig.panel.closeAsync());
            try { result.get(5, TimeUnit.SECONDS); throw new AssertionError("Cierre fallido silencioso"); }
            catch (java.util.concurrent.ExecutionException expected) {
                edt(() -> rig.panel.closeFailed(expected.getCause()));
            }
            edt(() -> { check(rig.enabled(Action.CLOSE) && !rig.enabled(Action.START), "No dispone panel ni habilita trabajo");
                check(rig.panel.detailText().contains("No se confirmó"), "Fallo de cierre visible"); });
            rig.expectedCloseFailure = true;
        }
    }

    public static void resetSupersedesStopWithoutRestoringOldState() throws Exception {
        try (var rig = new Rig()) {
            rig.prepare(); rig.moving();
            edt(() -> { rig.panel.button(Action.STOP).doClick(0); rig.panel.button(Action.RESET).doClick(0); });
            rig.awaitState(SimulationState.RESET);
            edt(() -> check(rig.enabled(Action.START) && rig.enabled(Action.LOAD) && !rig.enabled(Action.STOP), "Estado vigente tras Stop sustituido"));
        }
    }

    public static void loadFailureAllowsRetry() throws Exception {
        try (var rig = new Rig()) {
            rig.prepare(); rig.selected.set(Optional.of(rig.directory.resolve("ausente.txt")));
            rig.click(Action.LOAD); waitFor(() -> onEdt(() -> rig.enabled(Action.LOAD)));
            edt(() -> check(rig.panel.detailText().length() > 0 && rig.enabled(Action.START), "Error recuperable"));
            rig.select("CABIN 1 4\n"); rig.click(Action.LOAD);
            waitFor(() -> rig.controller().preparedBatch().isPresent());
        }
    }

    public static void importedBatchRunsThroughStartButton() throws Exception {
        try (var rig = new Rig()) {
            rig.prepare(); rig.select("CABIN 1 4\n"); rig.click(Action.LOAD);
            waitFor(() -> onEdt(() -> rig.enabled(Action.LOAD)));
            rig.click(Action.START); rig.awaitState(SimulationState.RUNNING);
            waitFor(() -> rig.controller().snapshots().get(0).currentFloor() == 4);
            check(rig.controller().preparedBatch().isEmpty() && rig.controller().lastBatchDispatch().isPresent(), "Start consume lote real");
        }
    }

    public static void logOpeningFailureDoesNotPublishPartialSession() throws Exception {
        Path file = Files.createTempFile("t48-no-directory-", ".tmp");
        try (var rig = new Rig(config -> SystemControlPanel.openSession(config, file))) {
            rig.confirm();
            waitFor(() -> onEdt(() -> rig.panel.configurationPanel().input(Field.ELEVATORS).isEnabled()));
            check(rig.controller() == null, "No publica sesión con logging incompleto");
            edt(() -> check(!rig.enabled(Action.START) && rig.enabled(Action.CLOSE), "Recuperable, sin arranque parcial"));
        } finally { Files.deleteIfExists(file); }
    }

    public static void buttonAndWindowRequestShareOneCloseHandler() throws Exception {
        try (var rig = new Rig()) {
            rig.prepare(); var disposed = new AtomicInteger();
            var handler = new AtomicReference<WindowCloseHandler>();
            edt(() -> {
                handler.set(new WindowCloseHandler(rig.panel::closeAsync, () -> { },
                        () -> { rig.panel.closed(); disposed.incrementAndGet(); }, rig.panel::closeFailed));
                rig.panel.onCloseRequest(handler.get()::requestClose);
                rig.panel.button(Action.CLOSE).doClick(0);
                handler.get().requestClose(); // Misma ruta utilizada por la X.
            });
            waitFor(() -> disposed.get() == 1);
            edt(() -> handler.get().requestClose());
            check(disposed.get() == 1 && rig.controller().awaitTermination(Duration.ofSeconds(5)), "Cierre único confirmado");
        }
    }

    private static final class Rig implements AutoCloseable {
        final Path directory = Files.createTempDirectory("t48-controls-");
        final AtomicReference<Optional<Path>> selected = new AtomicReference<>(Optional.empty());
        final AtomicInteger opened = new AtomicInteger();
        final AtomicBoolean openedOnEdt = new AtomicBoolean();
        final SystemControlPanel panel;
        boolean expectedCloseFailure;
        Rig() throws Exception { this(config -> new SimulationController(config, 0)); }
        Rig(SystemControlPanel.SessionOpener opener) throws Exception {
            panel = onEdt(() -> new SystemControlPanel(config -> {
                opened.incrementAndGet(); openedOnEdt.set(SwingUtilities.isEventDispatchThread()); return opener.open(config);
            }, selected::get));
        }
        void confirm() throws Exception {
            edt(() -> {
                var form = panel.configurationPanel();
                String[] values = {"1", "4", "350", "40"};
                for (var field : Field.values()) form.input(field).setText(values[field.ordinal()]);
                form.validateConfiguration();
            });
        }
        void prepare() throws Exception { confirm(); waitFor(() -> controller() != null); }
        SimulationController controller() { return onEdt(() -> panel.controller().orElse(null)); }
        boolean enabled(Action action) { panel.updateView(); return panel.button(action).isEnabled(); }
        void click(Action action) throws Exception { edt(() -> { panel.updateView(); panel.button(action).doClick(0); }); }
        void awaitState(SimulationState state) throws Exception {
            waitFor(() -> controller().state() == state && onEdt(() -> {
                panel.updateView();
                return switch (state) {
                    case RUNNING -> panel.button(Action.STOP).isEnabled();
                    case PAUSED -> panel.button(Action.RESUME).isEnabled();
                    case RESET, STOPPED -> panel.button(Action.START).isEnabled();
                    default -> true;
                };
            }));
        }
        void moving() throws Exception {
            click(Action.START); awaitState(SimulationState.RUNNING);
            var c = controller();
            c.submit(new CabinRequest(UUID.randomUUID(), c.epoch(), 4, Instant.now(), 1)).get(5, TimeUnit.SECONDS);
            waitFor(() -> c.snapshots().get(0).phase() == ElevatorPhase.MOVING);
        }
        void select(String text) throws Exception {
            Path file = directory.resolve(UUID.randomUUID() + ".txt"); Files.writeString(file, text); selected.set(Optional.of(file));
        }
        @Override public void close() throws Exception {
            try { onEdt(() -> panel.closeAsync()).get(8, TimeUnit.SECONDS); }
            catch (ExecutionException error) { if (!expectedCloseFailure) throw error; }
            finally { edt(panel::closed); deleteTree(directory); }
        }
    }

    private static void deleteTree(Path path) throws Exception {
        try (var paths = Files.walk(path)) {
            for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        }
    }
    private static void check(boolean condition, String text) { if (!condition) throw new AssertionError(text); }
    private static void waitFor(BooleanSupplier condition) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(6);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > until) throw new AssertionError("Tiempo agotado esperando condición");
            Thread.sleep(5);
        }
    }
    private static void edt(Runnable action) throws Exception { onEdt(() -> { action.run(); return null; }); }
    private static <T> T onEdt(Supplier<T> action) {
        if (SwingUtilities.isEventDispatchThread()) return action.get();
        var value = new AtomicReference<T>(); var failure = new AtomicReference<Throwable>();
        try { SwingUtilities.invokeAndWait(() -> {
            try { value.set(action.get()); } catch (Throwable error) { failure.set(error); }
        }); } catch (Exception error) { throw new AssertionError(error); }
        if (failure.get() instanceof Error error) throw error;
        if (failure.get() != null) throw new IllegalStateException(failure.get());
        return value.get();
    }
    public static void main(String[] args) throws Exception {
        initialControlsRequireConfiguration();
        invalidConfigurationDoesNotOpenSession();
        preparationRunsOutsideEdtAndLocksFields();
        resetEnablesOnlyLegalControls();
        startStopAndContinuePreserveSession();
        stoppingIndicatorAndResetRemainAvailable();
        resettingIndicatorWaitsForReturn();
        importPreparesWithoutMoving();
        loadDisabledInRunningAndStopped();
        cancelSelectionPreservesPreparedBatch();
        invalidImportKeepsPreviousBatchAndShowsLine();
        resetClearsImportedBatch();
        closeWithoutConfigurationCompletes();
        closeDuringPreparationReleasesLateSession();
        preparationFailureAllowsCorrectionAndRetry();
        closeDuringRunningTerminatesWorkers();
        closeDuringStopIgnoresLateControlReceipt();
        closeDuringResetTerminatesWorkers();
        repeatedConfirmationDoesNotCreateSecondSession();
        realLogFilesAreCreatedAndReleased();
        offEdtAccessIsRejected();
        edtRespondsDuringPreparation();
        closeFailureKeepsPanelAndRetryAvailable();
        resetSupersedesStopWithoutRestoringOldState();
        loadFailureAllowsRetry();
        importedBatchRunsThroughStartButton();
        logOpeningFailureDoesNotPublishPartialSession();
        buttonAndWindowRequestShareOneCloseHandler();
        System.out.println("T48: 28 escenarios satisfactorios.");
    }
}
```
