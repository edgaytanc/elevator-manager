# T38 — Código Java completo

Las tres clases de producción modificadas y las dos clases de pruebas nuevas se presentan íntegramente. Las rutas son relativas a la raíz Maven; los originales .java también están incluidos en el ZIP.

## src/main/java/com/elevatormanager/dispatch/HallRequestLedger.java

```java
package com.elevatormanager.dispatch;

import com.elevatormanager.model.ElevatorPhase;
import com.elevatormanager.model.SimulationConfig;
import com.elevatormanager.model.request.HallRequest;
import com.elevatormanager.protocol.Command;
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

import com.elevatormanager.model.SimulationConfig;
import com.elevatormanager.model.request.HallRequest;
import com.elevatormanager.protocol.Command;
import com.elevatormanager.simulation.ElevatorWorker;
import com.elevatormanager.simulation.ExecutionEventMailbox;
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

    private final SimulationConfig config;
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
    private final ExecutionEventMailbox mailbox = new ExecutionEventMailbox();
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
        this.config = Objects.requireNonNull(config, "config");
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
                for (var elevator : elevators) { elevator.subscribeEvents(mailbox); }
                thread.start();
            }
            catch (RuntimeException | Error exception) {
                for (var elevator : elevators) { elevator.unsubscribeEvents(mailbox); }
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

    private void runLoop() {
        HallRequest inFlight = null;
        long lastRetry = System.nanoTime();
        try {
            while (lifecycle == Lifecycle.RUNNING) {
                if (!awaitDispatchPermission()) { break; }
                boolean hadEvents = false;
                for (int i = 0; i < 256; i++) {
                    var event = mailbox.await(Duration.ZERO);
                    if (event.isEmpty()) { break; }
                    ledger.onEvent(event.orElseThrow()); publish(); hadEvents = true;
                }
                long now = System.nanoTime();
                if (hadEvents || now - lastRetry >= RETRY_NANOS) {
                    var pending = ledger.pending();
                    int count = Math.min(16, pending.size());
                    for (int i = 0; i < count && lifecycle == Lifecycle.RUNNING; i++) {
                        var request = pending.get((int) (((long) retryCursor + i) % pending.size()));
                        dispatch(request); publish();
                    }
                    if (!pending.isEmpty()) { retryCursor = (retryCursor + count) % pending.size(); }
                    lastRetry = System.nanoTime();
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
                    if (stopRequested || !resetInbox.isEmpty() || lifecycle != Lifecycle.RUNNING) { continue; }
                } finally { lock.unlock(); }
                // Los eventos despiertan por cola; el temporizador solo reintenta recibidas.
                var event = ledger.pending().isEmpty()
                        ? mailbox.await() : mailbox.await(Duration.ofNanos(RETRY_NANOS));
                if (event.isPresent()) { ledger.onEvent(event.orElseThrow()); publish(); }
            }
        } catch (InterruptedException exception) {
            failure = exception; Thread.currentThread().interrupt();
        } catch (RuntimeException | Error exception) {
            failure = exception;
        } finally {
            for (var elevator : elevators) { elevator.unsubscribeEvents(mailbox); }
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
            if (!entry.getValue().equals(previous.get(entry.getKey()))) { updates.add(entry.getValue()); }
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

    private Result dispatch(HallRequest request) {
        if (request.getEpoch() != epoch) {
            return result(request, Outcome.REJECTED, Optional.empty(), "Época ajena.");
        }
        var validation = request.validateAgainst(config);
        if (!validation.isValid()) {
            return result(request, Outcome.REJECTED, Optional.empty(), validation.errors().toString());
        }
        var initial = captureCandidates();
        if (selector.rank(config, request, eligibleCandidates(request, initial), System.nanoTime()).choices().isEmpty()) {
            return pending(request, "Conservada: no hay cabinas disponibles, confirmadas o estimables para despachar.");
        }
        long commandId = commandIds.getAsLong();
        if (commandId < 1) {
            throw new IllegalStateException("Generador de comandos agotado o inválido.");
        }
        // El generador ya terminó: todos los costos siguientes usan un instante nuevo.
        for (int attempt = 0; attempt < MAX_SELECTION_ATTEMPTS; attempt++) {
            var candidates = captureCandidates();
            var ranking = selector.rank(config, request, eligibleCandidates(request, candidates), System.nanoTime());
            if (ranking.choices().isEmpty()) {
                return pending(request, "Conservada: no hay cabinas disponibles, confirmadas o estimables para despachar.");
            }
            var selected = ranking.choices().get(0);
            // Revalidar TODAS: también puede mejorar una cabina antes descartada.
            if (!areCurrent(candidates)) { continue; }
            var elevator = elevators.get(selected.elevatorId() - 1);
            var command = new Command.Pickup(commandId, selected.elevatorId(), request);
            var submitted = elevator.submitIfCurrent(command, selected.candidate().planning());
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
        return new Result(request, outcome, command, detail, Thread.currentThread().getName());
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

## src/main/java/com/elevatormanager/simulation/SimulationController.java

```java
package com.elevatormanager.simulation;

import com.elevatormanager.dispatch.HallCallSnapshot;
import com.elevatormanager.dispatch.ManagerWorker;
import com.elevatormanager.model.ElevatorSnapshot;
import com.elevatormanager.model.SimulationConfig;
import com.elevatormanager.model.SimulationState;
import com.elevatormanager.model.request.CabinRequest;
import com.elevatormanager.model.request.HallRequest;
import com.elevatormanager.model.request.Request;
import com.elevatormanager.protocol.Command;
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
import java.util.function.Supplier;
import javax.swing.SwingUtilities;

/** T38: propietario de una sesión; serializa controles y admisiones fuera del EDT. */
public final class SimulationController implements AutoCloseable {
    public enum Outcome { APPLIED, UNCHANGED, ENQUEUED, REJECTED }

    /** ENQUEUED confirma envío, nunca admisión local ni servicio físico. */
    public record Result(Outcome outcome, SimulationState state, String detail,
            Optional<Command.Destination> command, long epoch) { }

    private final SimulationConfig config;
    private volatile long epoch;
    private final List<ElevatorWorker> elevators;
    private final ManagerWorker manager;
    private final ExecutionEventMailbox events = new ExecutionEventMailbox();
    private final AtomicLong commandIds = new AtomicLong();
    private final ExecutorService coordinator = Executors.newSingleThreadExecutor(
            task -> new Thread(task, "simulation-controller"));
    private volatile SimulationState state = SimulationState.RESET;
    private volatile Throwable failure;
    private CompletableFuture<Result> stopCompletion;
    private CompletableFuture<Result> resetCompletion;
    private boolean workersStarted;

    public SimulationController(SimulationConfig config, long epoch) {
        this.config = Objects.requireNonNull(config, "config");
        if (epoch < 0) { throw new IllegalArgumentException("Época negativa."); }
        this.epoch = epoch;
        elevators = java.util.stream.IntStream.rangeClosed(1, config.elevatorCount())
                .mapToObj(id -> new ElevatorWorker(id, config, epoch)).toList();
        elevators.forEach(e -> e.subscribeEvents(events));
        manager = new ManagerWorker(config, epoch, elevators, this::nextCommandId);
    }

    public SimulationConfig config() { return config; }
    public long epoch() { return epoch; }
    public SimulationState state() { return state; }
    public Optional<Throwable> failure() { return Optional.ofNullable(failure); }

    /** Copias inmutables por cabina; no representan un instante físico global único. */
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
        return schedule(() -> {
            if (state == SimulationState.RUNNING) {
                return result(Outcome.UNCHANGED, "La sesión ya está en ejecución.");
            }
            if (state == SimulationState.STOPPED || (state == SimulationState.RESET && workersStarted)) {
                try {
                    for (var elevator : elevators) {
                        if (!elevator.resume()) { throw new IllegalStateException("Cabina no reanudable."); }
                    }
                    if (!manager.resume()) { throw new IllegalStateException("Manejador no reanudable."); }
                    state = SimulationState.RUNNING;
                    return result(Outcome.APPLIED, "Continuación de trabajadores y pendientes existentes.");
                } catch (RuntimeException | Error exception) {
                    failure = exception;
                    beginClose();
                    throw exception;
                }
            }
            if (state != SimulationState.RESET) {
                return result(Outcome.REJECTED, "Start no está permitido en " + state);
            }
            try {
                startWorkersOnce();
                state = SimulationState.RUNNING;
                return result(Outcome.APPLIED, "Trabajadores iniciados una sola vez.");
            } catch (RuntimeException | Error exception) {
                failure = exception;
                beginClose();
                throw exception;
            }
        });
    }

    /** RUNNING -> STOPPING -> STOPPED; el futuro confirma toda la barrera. */
    public CompletableFuture<Result> stop() {
        return scheduleAsync(() -> {
            if (state == SimulationState.STOPPING) { return stopCompletion; }
            if (state == SimulationState.RESET || state == SimulationState.STOPPED) {
                return CompletableFuture.completedFuture(result(Outcome.UNCHANGED, "No hay avance que detener."));
            }
            if (state != SimulationState.RUNNING) {
                return CompletableFuture.completedFuture(result(Outcome.REJECTED, "Stop no permitido en " + state));
            }
            state = SimulationState.STOPPING;
            var completion = new CompletableFuture<Result>();
            stopCompletion = completion;
            var acknowledgements = new java.util.ArrayList<CompletableFuture<Void>>();
            acknowledgements.add(manager.requestStop());
            for (var elevator : elevators) { acknowledgements.add(elevator.requestStop()); }
            CompletableFuture.allOf(acknowledgements.toArray(CompletableFuture[]::new))
                    .whenComplete((ignored, error) -> schedule(() -> {
                        if (state == SimulationState.STOPPING && stopCompletion == completion) {
                            if (error != null) {
                                failure = error;
                                completion.completeExceptionally(error);
                                beginClose();
                            } else {
                                state = SimulationState.STOPPED;
                                completion.complete(result(Outcome.APPLIED, "Despacho y cabinas detenidos; pendientes conservados."));
                            }
                        }
                        return result(Outcome.UNCHANGED, "Confirmación de control procesada.");
                    }));
            return completion;
        });
    }

    /** Una época por operación; RESET se confirma solo tras limpieza y N retornos. */
    public CompletableFuture<Result> reset() {
        return scheduleAsync(() -> {
            if (state == SimulationState.RESETTING) { return resetCompletion; }
            if (state != SimulationState.RESET && state != SimulationState.RUNNING
                    && state != SimulationState.STOPPING && state != SimulationState.STOPPED) {
                return CompletableFuture.completedFuture(result(Outcome.REJECTED, "Reset no permitido en " + state));
            }
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
                startWorkersOnce();
                state = SimulationState.RESETTING;
                epoch = targetEpoch;
                if (stopCompletion != null && !stopCompletion.isDone()) {
                    stopCompletion.complete(result(Outcome.REJECTED, "Stop sustituido por reset global."));
                }
                var managerAck = manager.requestReset(targetEpoch);
                var cabinAcks = new java.util.ArrayList<CompletableFuture<ElevatorSnapshot>>();
                for (int i = 0; i < elevators.size(); i++) {
                    cabinAcks.add(elevators.get(i).requestReset(commands.get(i)));
                }
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
        for (var elevator : elevators) {
            if (!elevator.start()) { throw new IllegalStateException("Cabina ya iniciada."); }
        }
        if (!manager.start()) { throw new IllegalStateException("Manejador ya iniciado."); }
        workersStarted = true;
    }

    /** Valida y enruta usando Request y su validación polimórfica. */
    public CompletableFuture<Result> submit(Request request) {
        Objects.requireNonNull(request, "request");
        return schedule(() -> {
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
                boolean sent = elevators.get(cabin.getElevatorId() - 1).submit(command);
                return new Result(sent ? Outcome.ENQUEUED : Outcome.REJECTED, state,
                        "Envío a cabina; la aceptación y el servicio se notifican por eventos.",
                        sent ? Optional.of(command) : Optional.empty(), epoch);
            }
            return result(Outcome.REJECTED, "Tipo de solicitud no soportado.");
        });
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

    /** Liberación local mínima; no equivale a Stop ni al reset físico. No hace join. */
    @Override public void close() {
        schedule(() -> {
            beginClose();
            return result(Outcome.APPLIED, "Cierre solicitado; falta confirmar terminaciones.");
        });
    }

    private void beginClose() {
        state = SimulationState.CLOSING;
        if (resetCompletion != null && !resetCompletion.isDone()) {
            resetCompletion.complete(result(Outcome.REJECTED, "Reset interrumpido por cierre."));
        }
        if (stopCompletion != null && !stopCompletion.isDone()) {
            stopCompletion.complete(result(Outcome.REJECTED, "Stop interrumpido por cierre."));
        }
        manager.close();
        elevators.forEach(ElevatorWorker::close);
        coordinator.shutdown();
    }

    /** Confirmación explícita fuera del EDT; un timeout nunca publica CLOSED. */
    public boolean awaitTermination(Duration timeout) throws InterruptedException {
        long budget = checkedNanos(timeout);
        long began = System.nanoTime();
        if (!coordinator.awaitTermination(budget, TimeUnit.NANOSECONDS)) { return false; }
        if (state != SimulationState.CLOSING && state != SimulationState.CLOSED) { return false; }
        if (!manager.awaitTermination(Duration.ofNanos(remaining(began, budget)))) { return false; }
        for (var elevator : elevators) {
            if (!elevator.awaitTermination(Duration.ofNanos(remaining(began, budget)))) { return false; }
        }
        state = SimulationState.CLOSED;
        return true;
    }

    private CompletableFuture<Result> schedule(Supplier<Result> operation) {
        return scheduleAsync(() -> CompletableFuture.completedFuture(operation.get()));
    }

    private CompletableFuture<Result> scheduleAsync(Supplier<CompletionStage<Result>> operation) {
        var future = new CompletableFuture<Result>();
        try {
            coordinator.execute(() -> {
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

## src/test/java/com/elevatormanager/simulation/GlobalResetScenarios.java

```java
package com.elevatormanager.simulation;

import com.elevatormanager.dispatch.*;
import com.elevatormanager.model.*;
import com.elevatormanager.model.request.*;
import com.elevatormanager.protocol.Command;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Predicate;
import javax.swing.SwingUtilities;
import static com.elevatormanager.simulation.ExecutionEvent.Type.*;
import static com.elevatormanager.simulation.SimulationController.Outcome.*;
import static com.elevatormanager.dispatch.HallCallSnapshot.Status.*;

/** T38: épocas, barrera global, mensajes tardíos y continuación de actores reales. */
public final class GlobalResetScenarios {
    private static final Duration LIMIT = Duration.ofSeconds(10);
    private GlobalResetScenarios() { }

    public static void initialResetConfirmsEveryParticipant() throws Exception {
        try (var r = new Rig(3, 5, 20, 10)) {
            check(get(r.c.reset()).outcome() == APPLIED && r.c.epoch() == 1, "Reset inicial avanza época");
            ready(r.c); r.drain();
            check(r.count(RESET_COMPLETED) == 3, "Tres confirmaciones físicas");
            check(r.c.managerLifecycle() == ManagerWorker.Lifecycle.RUNNING, "Manejador conserva hilo");
        }
    }

    public static void waitsForSlowestCabin() throws Exception {
        try (var r = new Rig(2, 6, 200, 10)) {
            get(r.c.start()); get(r.c.submit(cabin(1, 4, 0))); r.until(e -> e.elevatorId() == 1 && e.type() == STOP_ENDED);
            get(r.c.submit(cabin(1, 6, 0))); get(r.c.submit(cabin(2, 2, 0)));
            var reset = r.c.reset();
            r.until(e -> e.elevatorId() == 2 && e.type() == RESET_COMPLETED);
            check(r.c.state() == SimulationState.RESETTING && !reset.isDone(), "Una cabina no completa la barrera global");
            get(reset); ready(r.c);
        }
    }

    public static void resetFromStoppedReturnsWithoutStart() throws Exception {
        try (var r = new Rig(1, 5, 30, 10)) {
            get(r.c.start()); get(r.c.submit(cabin(1, 4, 0))); r.until(STOP_ENDED); get(r.c.stop());
            get(r.c.reset()); ready(r.c);
            check(r.c.snapshots().get(0).currentFloor() == 1, "Retorno desde detenido");
        }
    }

    public static void resetSupersedesGlobalStopping() throws Exception {
        try (var r = new Rig(1, 5, 300, 10)) {
            get(r.c.start()); get(r.c.submit(cabin(1, 5, 0))); r.until(DEPARTED);
            var stop = r.c.stop(); var reset = r.c.reset();
            check(get(stop).outcome() == REJECTED, "Stop sustituido sin falso éxito");
            get(reset); ready(r.c); check(r.c.failure().isEmpty(), "Confirmación Stop tardía ignorada");
        }
    }

    public static void openDwellIsCancelledGlobally() throws Exception {
        try (var r = new Rig(1, 5, 20, 60_000)) {
            get(r.c.start()); get(r.c.submit(cabin(1, 3, 0))); r.until(COMMAND_COMPLETED);
            get(r.c.reset()); ready(r.c); r.drain();
            check(r.trace.stream().filter(e -> e.epoch() == 1).noneMatch(e -> e.type() == STOP_STARTED || e.type() == COMMAND_COMPLETED), "No atiende servicio durante retorno");
        }
    }

    public static void repeatedResetSharesOneEpoch() throws Exception {
        try (var r = new Rig(1, 6, 150, 10)) {
            get(r.c.start()); get(r.c.submit(cabin(1, 5, 0))); r.until(STOP_ENDED);
            var pool = Executors.newFixedThreadPool(4); var gate = new CountDownLatch(1);
            try {
                var calls = new ArrayList<Future<CompletableFuture<SimulationController.Result>>>();
                for (int i = 0; i < 32; i++) { calls.add(pool.submit(() -> { gate.await(); return r.c.reset(); })); }
                gate.countDown();
                var futures = new ArrayList<CompletableFuture<SimulationController.Result>>();
                for (var f : calls) { futures.add(f.get(10, TimeUnit.SECONDS)); }
                for (var f : futures) { check(get(f).epoch() == 1, "Misma época de operación concurrente"); }
                r.drain(); check(r.c.epoch() == 1 && r.count(RESET_STARTED) == 1, "Un retorno, una época");
            } finally { gate.countDown(); pool.shutdownNow(); }
        }
    }

    public static void anotherResetAfterReadyCreatesNewEpoch() throws Exception {
        try (var r = new Rig(2, 3, 20, 10)) {
            get(r.c.reset()); get(r.c.reset()); ready(r.c); check(r.c.epoch() == 2, "Nueva operación tras finalizar");
            get(r.c.start()); get(r.c.submit(cabin(2, 2, 2))); r.until(COMMAND_COMPLETED);
            check(r.c.elevatorLifecycles().stream().allMatch(l -> l == ElevatorWorker.Lifecycle.RUNNING), "No se recrean hilos");
        }
    }

    public static void controlsAreRejectedWhileResetting() throws Exception {
        try (var r = new Rig(1, 5, 150, 10)) {
            get(r.c.start()); get(r.c.submit(cabin(1, 4, 0))); r.until(STOP_ENDED);
            var reset = r.c.reset();
            check(get(r.c.start()).outcome() == REJECTED, "Start en RESETTING rechazado");
            check(get(r.c.stop()).outcome() == REJECTED, "Stop no detiene retorno");
            check(get(r.c.submit(cabin(1, 2, 1))).outcome() == REJECTED, "Sin admisión manual durante reset");
            get(reset); ready(r.c);
        }
    }

    public static void oldAdmissionResponseCanBeRecognized() throws Exception {
        try (var r = new Rig(1, 3, 20, 10)) {
            get(r.c.start()); var old = get(r.c.submit(hall(2, 0)));
            get(r.c.reset()); check(old.epoch() == 0 && !r.c.isCurrent(old), "Respuesta externa tardía identificada por época");
            check(r.c.calls().isEmpty(), "No restaura seguimiento viejo");
        }
    }

    public static void extractedOldEventMustBeRevalidated() throws Exception {
        try (var r = new Rig(1, 3, 20, 10)) {
            get(r.c.start()); get(r.c.submit(cabin(1, 2, 0))); var old = r.until(DEPARTED);
            get(r.c.reset()); check(!r.c.isCurrent(old), "Evento extraído antes de reset ya no es vigente");
        }
    }

    public static void bufferedOldEventsAreNotReturnedAsCurrent() throws Exception {
        try (var r = new Rig(1, 1, 1, 60_000)) {
            get(r.c.start());
            for (int i = 0; i < 300; i++) { get(r.c.submit(cabin(1, 1, 0))); }
            get(r.c.reset());
            var event = r.until(RESET_COMPLETED);
            check(event.epoch() == 1 && r.trace.stream().allMatch(e -> e.epoch() == 1), "Historial viejo descartado al leer");
        }
    }

    public static void staleManualRequestsCannotReenterNewRun() throws Exception {
        try (var r = new Rig(1, 3, 20, 10)) {
            get(r.c.reset()); get(r.c.start());
            check(get(r.c.submit(hall(2, 0))).outcome() == REJECTED, "Externa antigua rechazada");
            check(get(r.c.submit(cabin(1, 2, 0))).outcome() == REJECTED, "Interna antigua rechazada");
            check(r.c.calls().isEmpty(), "Sin pendiente antiguo");
        }
    }

    public static void freshEpochMayReuseCancelledUuid() throws Exception {
        try (var r = new Rig(1, 5, 100, 10)) {
            get(r.c.start()); var old = cabin(1, 5, 0); get(r.c.submit(old)); r.until(DEPARTED);
            get(r.c.reset()); get(r.c.start());
            var fresh = new CabinRequest(old.getId(), 1, 2, Instant.now(), 1);
            check(get(r.c.submit(fresh)).outcome() == ENQUEUED, "Identidad válida en época nueva");
            check(r.until(COMMAND_COMPLETED).epoch() == 1, "Atención nueva y correlacionada");
        }
    }

    public static void commandIdsKeepGrowingAcrossReset() throws Exception {
        try (var r = new Rig(1, 3, 20, 10)) {
            get(r.c.start()); var old = get(r.c.submit(cabin(1, 2, 0))).command().orElseThrow();
            get(r.c.reset()); get(r.c.start()); var fresh = get(r.c.submit(cabin(1, 2, 1))).command().orElseThrow();
            check(fresh.commandId() > old.commandId() + 1, "Secuencia no reiniciada y reset con ID propio");
        }
    }

    public static void oneFloorResetAndContinuationWork() throws Exception {
        try (var r = new Rig(1, 1, 1, 5)) {
            get(r.c.reset()); get(r.c.start()); get(r.c.submit(cabin(1, 1, 1)));
            check(r.until(COMMAND_COMPLETED).snapshot().currentFloor() == 1, "Servicio sin viaje");
            check(get(r.c.submit(hall(1, 1))).outcome() == REJECTED, "Externa imposible rechazada");
        }
    }

    public static void exhaustedEpochDoesNotWrapOrStartWorkers() throws Exception {
        var c = new SimulationController(new SimulationConfig(1, 1, 1, 1), Long.MAX_VALUE);
        try {
            check(get(c.reset()).outcome() == REJECTED, "Época agotada informada");
            check(c.epoch() == Long.MAX_VALUE && c.state() == SimulationState.RESET, "Sin wrap ni cambio de estado");
            check(c.elevatorLifecycles().get(0) == ElevatorWorker.Lifecycle.NEW, "Sin inicio parcial");
        } finally { c.close(); check(c.awaitTermination(LIMIT), "Cierre posible"); }
    }

    public static void closeCancelsPendingGlobalReset() throws Exception {
        try (var r = new Rig(1, 5, 150, 10)) {
            get(r.c.start()); get(r.c.submit(cabin(1, 4, 0))); r.until(STOP_ENDED);
            var reset = r.c.reset(); r.until(RESET_STARTED); r.c.close();
            check(get(reset).outcome() == REJECTED, "Cierre invalida barrera pendiente");
            check(r.c.awaitTermination(LIMIT) && r.c.state() == SimulationState.CLOSED, "Cierre sin falso RESET");
        }
    }

    public static void closeAfterResetWakesHeldActors() throws Exception {
        try (var r = new Rig(3, 5, 20, 10)) {
            get(r.c.reset()); r.c.close(); check(r.c.awaitTermination(LIMIT), "Cierra actores retenidos tras retorno");
            check(get(r.c.reset()).outcome() == REJECTED, "No resetea sesión cerrada");
        }
    }

    public static void edtRemainsResponsiveDuringGlobalReset() throws Exception {
        try (var r = new Rig(1, 5, 120, 10)) {
            get(r.c.start()); get(r.c.submit(cabin(1, 4, 0))); r.until(STOP_ENDED);
            var future = new AtomicReference<CompletableFuture<SimulationController.Result>>();
            var pulse = new CountDownLatch(1);
            SwingUtilities.invokeAndWait(() -> { future.set(r.c.reset()); SwingUtilities.invokeLater(pulse::countDown); });
            check(pulse.await(2, TimeUnit.SECONDS), "EDT libre durante la barrera"); get(future.get()); ready(r.c);
        }
    }

    public static void rapidResetStartStopCyclesPreserveActors() throws Exception {
        try (var r = new Rig(2, 1, 1, 1)) {
            for (int i = 1; i <= 200; i++) {
                check(get(r.c.reset()).epoch() == i, "Época creciente en ciclo " + i);
                get(r.c.start()); get(r.c.stop());
            }
            get(r.c.reset()); ready(r.c); check(r.c.epoch() == 201, "Todas las barreras terminaron");
        }
    }

    public static void cancelledCallerFutureDoesNotCancelGlobalReset() throws Exception {
        try (var r = new Rig(1, 5, 120, 10)) {
            get(r.c.start()); get(r.c.submit(cabin(1, 4, 0))); r.until(STOP_ENDED);
            var first = r.c.reset(); first.cancel(false);
            check(get(r.c.reset()).outcome() == APPLIED && r.c.epoch() == 1, "Barrera privada conservada"); ready(r.c);
        }
    }

    public static void completedWorkDoesNotReappearAfterStart() throws Exception {
        try (var r = new Rig(1, 3, 20, 10)) {
            get(r.c.start()); get(r.c.submit(hall(2, 0))); r.until(COMMAND_COMPLETED);
            get(r.c.reset()); get(r.c.start()); r.drain();
            check(r.c.calls().isEmpty() && r.c.snapshots().get(0).hallRequests().isEmpty(), "No repone la llamada atendida");
            check(r.trace.stream().filter(e -> e.epoch() == 1).noneMatch(e -> e.type() == COMMAND_COMPLETED), "Sin atención nueva espontánea");
        }
    }

    public static void ledgerInvalidatesOpenTrackingButKeepsServedHistory() {
        var config = new SimulationConfig(1, 3, 10, 10); var ledger = new HallRequestLedger(config, 0);
        var served = hall(1, 0); var pending = hall(2, 0); var alias = hall(2, 0);
        ledger.receive(served); var command = new Command.Pickup(1, 1, served); ledger.dispatched(served.getId(), command);
        ledger.onEvent(accept(1, command, config)); ledger.onEvent(complete(2, command, config));
        ledger.receive(pending); ledger.receive(alias); ledger.cancelForReset();
        check(ledger.get(served.getId()).orElseThrow().status() == SERVED, "Servicio confirmado no se convierte en cancelado");
        check(ledger.get(pending.getId()).orElseThrow().status() == CANCELLED && ledger.get(alias.getId()).orElseThrow().status() == CANCELLED, "Canónica y alias invalidados");
    }

    public static void staleEventsCannotPoisonNewEpochOrSequence() {
        var config = new SimulationConfig(1, 3, 10, 10); var fresh = hall(1, 1);
        var old = new HallRequest(fresh.getId(), 0, 1, fresh.getCreatedAt(), Direction.UP);
        var ledger = new HallRequestLedger(config, 1); ledger.receive(fresh);
        var command = new Command.Pickup(1, 1, fresh); ledger.dispatched(fresh.getId(), command);
        var oldCommand = new Command.Pickup(1, 1, old);
        ledger.onEvent(accept(Long.MAX_VALUE - 1, oldCommand, config)); ledger.onEvent(complete(Long.MAX_VALUE, oldCommand, config));
        check(ledger.get(fresh.getId()).orElseThrow().status() == DISPATCHED, "Época antigua no acepta ni completa");
        ledger.onEvent(accept(1, command, config)); ledger.onEvent(complete(2, command, config));
        check(ledger.get(fresh.getId()).orElseThrow().status() == SERVED, "Secuencia vieja no bloquea hechos nuevos");
    }

    public static void staleAdmissionDoesNotReserveFreshIdentity() {
        var config = new SimulationConfig(1, 3, 10, 10); var old = hall(2, 0);
        var fresh = new HallRequest(old.getId(), 1, 2, Instant.now(), Direction.UP);
        var ledger = new HallRequestLedger(config, 1);
        check(ledger.receive(old).kind() == HallRequestLedger.AdmissionKind.REJECTED, "Rechazo por época");
        check(ledger.receive(fresh).kind() == HallRequestLedger.AdmissionKind.NEW, "Identidad no contaminada");
    }

    public static void managerResetWakesStopAndCancelsInbox() throws Exception {
        try (var rig = new ManagerRig(20)) {
            rig.m.requestStop().get(10, TimeUnit.SECONDS); var old = hall(2, 0); rig.m.submit(old);
            check(rig.m.requestReset(1).get(10, TimeUnit.SECONDS) == 1L, "Manejador detenido adopta reset");
            check(rig.m.awaitResult(LIMIT).orElseThrow().outcome() == ManagerWorker.Outcome.CANCELLED, "Inbox viejo cancelado");
            check(rig.m.calls().isEmpty() && !rig.m.submit(hall(2, 1)), "Sin admisión antes de continuar");
            expectFailure(rig.m.requestStop()); rig.w.requestReset(new Command.Reset(100, 1, 1)).get(10, TimeUnit.SECONDS);
            check(rig.w.resume() && rig.m.resume(), "Ambos continúan");
            var fresh = new HallRequest(old.getId(), 1, 2, Instant.now(), Direction.UP); rig.m.submit(fresh); awaitStatus(rig.m, fresh, SERVED);
        }
    }

    public static void managerResetWaitsForInFlightDispatch() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1); var ids = new AtomicLong();
        var config = new SimulationConfig(1, 5, 20, 10); var w = new ElevatorWorker(1, config, 0);
        var m = new ManagerWorker(config, 0, List.of(w), () -> {
            long id = ids.incrementAndGet();
            if (id == 1) {
                entered.countDown();
                try { check(release.await(10, TimeUnit.SECONDS), "Generador de prueba liberado"); }
                catch (InterruptedException e) { throw new AssertionError(e); }
            }
            return id;
        });
        try {
            w.start(); m.start(); m.submit(hall(3, 0)); check(entered.await(10, TimeUnit.SECONDS), "Despacho en vuelo observado");
            var reset = m.requestReset(1); check(!reset.isDone(), "No confirma mientras sigue en vuelo");
            w.requestReset(new Command.Reset(100, 1, 1)).get(10, TimeUnit.SECONDS); release.countDown(); reset.get(10, TimeUnit.SECONDS);
            check(m.calls().isEmpty() && m.epoch() == 1, "Adopción limpia después del despacho");
        } finally { release.countDown(); m.close(); w.close(); check(m.awaitTermination(LIMIT) && w.awaitTermination(LIMIT), "Actores terminados"); }
    }

    public static void managerIgnoresBufferedOldCompletions() throws Exception {
        try (var rig = new ManagerRig(80)) {
            var old = hall(3, 0); rig.m.submit(old); awaitStatus(rig.m, old, ASSIGNED);
            rig.m.requestStop().get(10, TimeUnit.SECONDS); rig.w.awaitService(LIMIT).orElseThrow();
            rig.m.requestReset(1).get(10, TimeUnit.SECONDS); rig.w.requestReset(new Command.Reset(100, 1, 1)).get(10, TimeUnit.SECONDS);
            check(rig.w.resume() && rig.m.resume(), "Continúa época nueva");
            var fresh = new HallRequest(old.getId(), 1, 2, Instant.now(), Direction.UP); rig.m.submit(fresh);
            var done = awaitStatus(rig.m, fresh, SERVED);
            check(done.request().getEpoch() == 1 && rig.m.calls().size() == 1, "Confirmación vieja no restaura seguimiento");
        }
    }

    public static void invalidManagerResetDoesNotKillActor() throws Exception {
        try (var rig = new ManagerRig(20)) {
            expectFailure(rig.m.requestReset(0)); expectFailure(rig.m.requestReset(-1));
            var fresh = hall(2, 0); check(rig.m.submit(fresh), "Sigue recibiendo tras rechazo"); awaitStatus(rig.m, fresh, SERVED);
        }
    }

    public static void replayedManagerResetDoesNotClearNewWork() throws Exception {
        try (var rig = new ManagerRig(20)) {
            rig.m.requestReset(1).get(10, TimeUnit.SECONDS); rig.w.requestReset(new Command.Reset(100, 1, 1)).get(10, TimeUnit.SECONDS);
            check(rig.w.resume() && rig.m.resume(), "Continúa"); var fresh = hall(2, 1); rig.m.submit(fresh); awaitStatus(rig.m, fresh, SERVED);
            rig.m.requestReset(1).get(10, TimeUnit.SECONDS);
            check(rig.m.call(fresh.getId()).orElseThrow().status() == SERVED, "Replay no vuelve a limpiar");
            var other = hall(3, 1); check(rig.m.submit(other), "Replay no detiene ejecución nueva"); awaitStatus(rig.m, other, SERVED);
        }
    }

    public static void closedManagerRejectsReset() throws Exception {
        var config = new SimulationConfig(1, 1, 1, 1); var w = new ElevatorWorker(1, config, 0);
        var m = new ManagerWorker(config, 0, List.of(w), new AtomicLong()::incrementAndGet);
        expectFailure(m.requestReset(1)); m.close(); w.close(); expectFailure(m.requestReset(1));
        check(m.awaitTermination(LIMIT) && w.awaitTermination(LIMIT), "Cierre de actores nuevos");
    }

    public static void oldManagerRequestsCannotReappearAfterResume() throws Exception {
        try (var rig = new ManagerRig(20)) {
            rig.m.requestReset(1).get(10, TimeUnit.SECONDS); rig.w.requestReset(new Command.Reset(100, 1, 1)).get(10, TimeUnit.SECONDS);
            check(rig.w.resume() && rig.m.resume(), "Continúa"); rig.m.submit(hall(2, 0));
            check(rig.m.awaitResult(LIMIT).orElseThrow().outcome() == ManagerWorker.Outcome.REJECTED, "Entrada antigua rechazada");
            check(rig.m.calls().isEmpty(), "No queda pendiente antiguo");
        }
    }

    public static void currentSnapshotDoesNotAuthorizeOldCommandEvent() throws Exception {
        var config = new SimulationConfig(1, 3, 10, 10); var c = new SimulationController(config, 1);
        try {
            var oldCommand = new Command.Pickup(1, 1, hall(2, 0));
            var current = new ElevatorState(1, config, 1).snapshot(0);
            var rejected = new ExecutionEvent(1, COMMAND_REJECTED, current, Optional.of(oldCommand), Optional.empty(), "época anterior");
            check(!c.isCurrent(rejected), "Snapshot nuevo no vuelve vigente un comando viejo");
            var physical = new ExecutionEvent(2, DIRECTION_CHANGED, current, Optional.empty(), Optional.empty(), "físico");
            check(c.isCurrent(physical), "Evento físico sin comando se conserva");
        } finally { c.close(); check(c.awaitTermination(LIMIT), "Cierre"); }
    }

    private static ExecutionEvent accept(long sequence, Command.Pickup command, SimulationConfig config) {
        var state = new ElevatorState(1, config, command.epoch()); state.addPendingRequest(command.request());
        return new ExecutionEvent(sequence, COMMAND_ACCEPTED, state.snapshot(0), Optional.of(command), Optional.of(command.request().getId()), "aceptado");
    }
    private static ExecutionEvent complete(long sequence, Command.Pickup command, SimulationConfig config) {
        var state = new ElevatorState(1, config, command.epoch()); state.addPendingRequest(command.request()); state.beginDwell(Direction.UP, 0);
        return new ExecutionEvent(sequence, COMMAND_COMPLETED, state.snapshot(0), Optional.of(command), Optional.of(command.request().getId()), "servido");
    }
    private static HallCallSnapshot awaitStatus(ManagerWorker m, HallRequest request, HallCallSnapshot.Status status) throws Exception {
        long began = System.nanoTime();
        while (true) {
            long left = LIMIT.toNanos() - (System.nanoTime() - began); check(left > 0, "Plazo de estado");
            var update = m.awaitUpdate(Duration.ofNanos(left)).orElseThrow();
            if (update.request().getId().equals(request.getId()) && update.request().getEpoch() == request.getEpoch() && update.status() == status) { return update; }
        }
    }
    private static CabinRequest cabin(int id, int floor, long epoch) { return new CabinRequest(UUID.randomUUID(), epoch, floor, Instant.now(), id); }
    private static HallRequest hall(int floor, long epoch) { return new HallRequest(UUID.randomUUID(), epoch, floor, Instant.now(), Direction.UP); }
    private static SimulationController.Result get(CompletableFuture<SimulationController.Result> future) throws Exception { return future.get(10, TimeUnit.SECONDS); }
    private static void expectFailure(CompletableFuture<?> future) throws Exception {
        try { future.get(10, TimeUnit.SECONDS); throw new AssertionError("Debía fallar"); }
        catch (ExecutionException expected) { check(expected.getCause() != null, "Causa disponible"); }
    }
    private static void check(boolean value, String detail) { if (!value) { throw new AssertionError(detail); } }
    private static void ready(SimulationController c) {
        check(c.state() == SimulationState.RESET && c.calls().isEmpty(), "RESET global limpio");
        check(c.snapshots().stream().allMatch(s -> s.epoch() == c.epoch() && s.currentFloor() == 1 && s.direction() == Direction.UP
                && s.phase() == ElevatorPhase.IDLE && s.mode() == ElevatorSnapshot.Mode.NORMAL
                && s.cabinRequests().isEmpty() && s.hallRequests().isEmpty()), "Todas en 1/UP y época vigente");
    }

    private static final class Rig implements AutoCloseable {
        final SimulationController c; final List<ExecutionEvent> trace = new ArrayList<>();
        Rig(int n, int floors, long move, long dwell) { c = new SimulationController(new SimulationConfig(n, floors, move, dwell), 0); }
        ExecutionEvent until(ExecutionEvent.Type type) throws Exception { return until(e -> e.type() == type); }
        ExecutionEvent until(Predicate<ExecutionEvent> predicate) throws Exception {
            long began = System.nanoTime();
            while (true) {
                long left = LIMIT.toNanos() - (System.nanoTime() - began); check(left > 0, "Plazo de evento");
                var event = c.awaitEvent(Duration.ofNanos(left)).orElseThrow(); trace.add(event);
                check(event.type() != WORKER_FAILED, "Cabina sin fallo"); if (predicate.test(event)) { return event; }
            }
        }
        void drain() throws Exception {
            while (true) { var event = c.awaitEvent(Duration.ZERO); if (event.isEmpty()) { return; } trace.add(event.orElseThrow()); }
        }
        long count(ExecutionEvent.Type type) { return trace.stream().filter(e -> e.type() == type).count(); }
        @Override public void close() throws Exception {
            c.close(); check(c.awaitTermination(LIMIT), "No quedan actores vivos"); check(c.failure().isEmpty(), "Controlador sin fallo: " + c.failure());
            check(c.elevatorLifecycles().stream().allMatch(l -> l == ElevatorWorker.Lifecycle.TERMINATED), "Cabinas terminadas sin fallos");
            check(c.managerLifecycle() == ManagerWorker.Lifecycle.TERMINATED, "Manejador terminado sin fallo");
        }
    }
    private static final class ManagerRig implements AutoCloseable {
        final ElevatorWorker w; final ManagerWorker m;
        ManagerRig(long movement) {
            var config = new SimulationConfig(1, 5, movement, 10); w = new ElevatorWorker(1, config, 0);
            m = new ManagerWorker(config, 0, List.of(w), new AtomicLong()::incrementAndGet); w.start(); m.start();
        }
        @Override public void close() throws Exception {
            m.close(); w.close(); check(m.awaitTermination(LIMIT) && w.awaitTermination(LIMIT), "Actores terminados");
            check(m.failure().isEmpty() && w.failure().isEmpty(), "Actores sanos");
        }
    }

    public static void main(String[] args) throws Exception {
        initialResetConfirmsEveryParticipant(); waitsForSlowestCabin(); resetFromStoppedReturnsWithoutStart();
        resetSupersedesGlobalStopping(); openDwellIsCancelledGlobally(); repeatedResetSharesOneEpoch();
        anotherResetAfterReadyCreatesNewEpoch(); controlsAreRejectedWhileResetting(); oldAdmissionResponseCanBeRecognized();
        extractedOldEventMustBeRevalidated(); bufferedOldEventsAreNotReturnedAsCurrent(); staleManualRequestsCannotReenterNewRun();
        freshEpochMayReuseCancelledUuid(); commandIdsKeepGrowingAcrossReset(); oneFloorResetAndContinuationWork();
        exhaustedEpochDoesNotWrapOrStartWorkers(); closeCancelsPendingGlobalReset(); closeAfterResetWakesHeldActors();
        edtRemainsResponsiveDuringGlobalReset(); rapidResetStartStopCyclesPreserveActors(); cancelledCallerFutureDoesNotCancelGlobalReset();
        completedWorkDoesNotReappearAfterStart(); ledgerInvalidatesOpenTrackingButKeepsServedHistory(); staleEventsCannotPoisonNewEpochOrSequence();
        staleAdmissionDoesNotReserveFreshIdentity(); managerResetWakesStopAndCancelsInbox(); managerResetWaitsForInFlightDispatch();
        managerIgnoresBufferedOldCompletions(); invalidManagerResetDoesNotKillActor(); replayedManagerResetDoesNotClearNewWork();
        closedManagerRejectsReset(); oldManagerRequestsCannotReappearAfterResume();
        currentSnapshotDoesNotAuthorizeOldCommandEvent();
        System.out.println("T38: 33 escenarios satisfactorios.");
    }
}
```

## src/test/java/com/elevatormanager/simulation/GlobalResetTest.java

```java
package com.elevatormanager.simulation;

import org.junit.jupiter.api.Test;

class GlobalResetTest {
    @Test void initialResetConfirmsEveryParticipant() throws Exception {
        GlobalResetScenarios.initialResetConfirmsEveryParticipant();
    }
    @Test void waitsForSlowestCabin() throws Exception {
        GlobalResetScenarios.waitsForSlowestCabin();
    }
    @Test void resetFromStoppedReturnsWithoutStart() throws Exception {
        GlobalResetScenarios.resetFromStoppedReturnsWithoutStart();
    }
    @Test void resetSupersedesGlobalStopping() throws Exception {
        GlobalResetScenarios.resetSupersedesGlobalStopping();
    }
    @Test void openDwellIsCancelledGlobally() throws Exception {
        GlobalResetScenarios.openDwellIsCancelledGlobally();
    }
    @Test void repeatedResetSharesOneEpoch() throws Exception {
        GlobalResetScenarios.repeatedResetSharesOneEpoch();
    }
    @Test void anotherResetAfterReadyCreatesNewEpoch() throws Exception {
        GlobalResetScenarios.anotherResetAfterReadyCreatesNewEpoch();
    }
    @Test void controlsAreRejectedWhileResetting() throws Exception {
        GlobalResetScenarios.controlsAreRejectedWhileResetting();
    }
    @Test void oldAdmissionResponseCanBeRecognized() throws Exception {
        GlobalResetScenarios.oldAdmissionResponseCanBeRecognized();
    }
    @Test void extractedOldEventMustBeRevalidated() throws Exception {
        GlobalResetScenarios.extractedOldEventMustBeRevalidated();
    }
    @Test void bufferedOldEventsAreNotReturnedAsCurrent() throws Exception {
        GlobalResetScenarios.bufferedOldEventsAreNotReturnedAsCurrent();
    }
    @Test void staleManualRequestsCannotReenterNewRun() throws Exception {
        GlobalResetScenarios.staleManualRequestsCannotReenterNewRun();
    }
    @Test void freshEpochMayReuseCancelledUuid() throws Exception {
        GlobalResetScenarios.freshEpochMayReuseCancelledUuid();
    }
    @Test void commandIdsKeepGrowingAcrossReset() throws Exception {
        GlobalResetScenarios.commandIdsKeepGrowingAcrossReset();
    }
    @Test void oneFloorResetAndContinuationWork() throws Exception {
        GlobalResetScenarios.oneFloorResetAndContinuationWork();
    }
    @Test void exhaustedEpochDoesNotWrapOrStartWorkers() throws Exception {
        GlobalResetScenarios.exhaustedEpochDoesNotWrapOrStartWorkers();
    }
    @Test void closeCancelsPendingGlobalReset() throws Exception {
        GlobalResetScenarios.closeCancelsPendingGlobalReset();
    }
    @Test void closeAfterResetWakesHeldActors() throws Exception {
        GlobalResetScenarios.closeAfterResetWakesHeldActors();
    }
    @Test void edtRemainsResponsiveDuringGlobalReset() throws Exception {
        GlobalResetScenarios.edtRemainsResponsiveDuringGlobalReset();
    }
    @Test void rapidResetStartStopCyclesPreserveActors() throws Exception {
        GlobalResetScenarios.rapidResetStartStopCyclesPreserveActors();
    }
    @Test void cancelledCallerFutureDoesNotCancelGlobalReset() throws Exception {
        GlobalResetScenarios.cancelledCallerFutureDoesNotCancelGlobalReset();
    }
    @Test void completedWorkDoesNotReappearAfterStart() throws Exception {
        GlobalResetScenarios.completedWorkDoesNotReappearAfterStart();
    }
    @Test void ledgerInvalidatesOpenTrackingButKeepsServedHistory() throws Exception {
        GlobalResetScenarios.ledgerInvalidatesOpenTrackingButKeepsServedHistory();
    }
    @Test void staleEventsCannotPoisonNewEpochOrSequence() throws Exception {
        GlobalResetScenarios.staleEventsCannotPoisonNewEpochOrSequence();
    }
    @Test void staleAdmissionDoesNotReserveFreshIdentity() throws Exception {
        GlobalResetScenarios.staleAdmissionDoesNotReserveFreshIdentity();
    }
    @Test void managerResetWakesStopAndCancelsInbox() throws Exception {
        GlobalResetScenarios.managerResetWakesStopAndCancelsInbox();
    }
    @Test void managerResetWaitsForInFlightDispatch() throws Exception {
        GlobalResetScenarios.managerResetWaitsForInFlightDispatch();
    }
    @Test void managerIgnoresBufferedOldCompletions() throws Exception {
        GlobalResetScenarios.managerIgnoresBufferedOldCompletions();
    }
    @Test void invalidManagerResetDoesNotKillActor() throws Exception {
        GlobalResetScenarios.invalidManagerResetDoesNotKillActor();
    }
    @Test void replayedManagerResetDoesNotClearNewWork() throws Exception {
        GlobalResetScenarios.replayedManagerResetDoesNotClearNewWork();
    }
    @Test void closedManagerRejectsReset() throws Exception {
        GlobalResetScenarios.closedManagerRejectsReset();
    }
    @Test void oldManagerRequestsCannotReappearAfterResume() throws Exception {
        GlobalResetScenarios.oldManagerRequestsCannotReappearAfterResume();
    }
    @Test void currentSnapshotDoesNotAuthorizeOldCommandEvent() throws Exception {
        GlobalResetScenarios.currentSnapshotDoesNotAuthorizeOldCommandEvent();
    }
}
```
