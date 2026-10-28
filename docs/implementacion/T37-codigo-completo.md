# T37 — Código Java completo

Los nueve archivos nuevos o modificados se presentan íntegros. Las rutas son relativas a la raíz Maven; los originales .java están incluidos en el ZIP.

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

## src/main/java/com/elevatormanager/simulation/CommandLedger.java

```java
package com.elevatormanager.simulation;

import com.elevatormanager.model.Direction;
import com.elevatormanager.model.ElevatorState;
import com.elevatormanager.model.request.CabinRequest;
import com.elevatormanager.model.request.HallRequest;
import com.elevatormanager.model.request.Request;
import com.elevatormanager.protocol.Command;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Registro privado del actor; no es thread-safe ni se comparte con productores. */
public final class CommandLedger {
    public enum Kind { ADDED, DUPLICATE, REJECTED }
    public enum RequestStatus { PENDING, SERVED }

    public record Decision(Kind kind, String detail, Optional<UUID> canonicalRequestId,
            Optional<RequestStatus> requestStatus, boolean replay) { }

    /** Un comando distinto queda satisfecho por un único servicio canónico. */
    public record Completion(Command command, UUID canonicalRequestId) { }

    private final List<Completion> completions = new ArrayList<>();
    private final Map<UUID, List<Command>> awaitingService = new HashMap<>();

    /** Solo el actor consume estas confirmaciones; una retransmisión no las recrea. */
    public List<Completion> drainCompletions() {
        List<Completion> ready = List.copyOf(completions);
        completions.clear();
        return ready;
    }

    private record CommandKey(long epoch, long id) { }
    private record FunctionalKey(boolean cabin, int floor, Direction direction) { }
    private record History(Command command, Decision decision) { }
    private record KnownRequest(Request original, Canonical canonical) { }

    private static final class Canonical {
        private final Request request;
        private RequestStatus status = RequestStatus.PENDING;
        private Canonical(Request request) { this.request = request; }
    }

    private final ElevatorState state;
    private final Map<CommandKey, History> commands = new HashMap<>();
    private final Map<UUID, KnownRequest> requests = new HashMap<>();
    private final Map<FunctionalKey, Canonical> active = new HashMap<>();

    public CommandLedger(ElevatorState state) {
        this.state = Objects.requireNonNull(state, "state");
        if (!state.getPendingRequestsInAdmissionOrder().isEmpty()) {
            throw new IllegalArgumentException("El registro se crea con una agenda vacía.");
        }
    }

    /** Cancela comandos aún pendientes y libera identidades al cambiar de época. */
    public List<Completion> clearForReset() {
        List<Completion> cancelled = new ArrayList<>();
        awaitingService.forEach((id, waiting) -> waiting.forEach(c -> cancelled.add(new Completion(c, id))));
        commands.clear();
        requests.clear();
        active.clear();
        awaitingService.clear();
        completions.clear();
        return List.copyOf(cancelled);
    }

    /** Solo el actor ejecuta esta operación, serializada con movimiento y servicio. */
    public Decision admit(Command command) {
        Objects.requireNonNull(command, "command");
        // Mensajes ajenos o no autorizados no reservan identidades locales.
        if (command.elevatorId() != state.getId()) { return reject("Destinatario incorrecto."); }
        if (command instanceof Command.Reset) { return reject("Reset debe pasar por el control prioritario del trabajador."); }
        if (command.epoch() != state.getEpoch()) { return reject("Época distinta de la vigente."); }
        if (!command.validateAgainst(state.getConfig()).isValid()) {
            return reject("Comando inválido para este edificio.");
        }
        var key = new CommandKey(command.epoch(), command.commandId());
        History previous = commands.get(key);
        Request request = requestOf(command);
        if (previous != null) {
            if (!previous.command().sameContent(command)) {
                return reject("commandId reutilizado con otro contenido.");
            }
            Decision old = previous.decision();
            Optional<RequestStatus> status = old.canonicalRequestId()
                    .map(id -> requests.get(id).canonical().status);
            return new Decision(old.kind(), old.detail(), old.canonicalRequestId(), status, true);
        }
        Decision decision;
        try {
            KnownRequest known = requests.get(request.getId());
            if (known != null) {
                if (!sameRequest(known.original(), request)) {
                    throw new IllegalArgumentException("UUID reutilizado con otro contenido.");
                }
                decision = decision(Kind.DUPLICATE, "Solicitud ya registrada.", known.canonical());
            } else {
                Canonical canonical = active.get(functionalKey(request));
                if (canonical != null) {
                    requests.put(request.getId(), new KnownRequest(request, canonical));
                    decision = decision(Kind.DUPLICATE, "Duplicado de una solicitud pendiente.", canonical);
                } else {
                    if (!state.addPendingRequest(request)) {
                        throw new IllegalStateException("Agenda y registro de solicitudes incoherentes.");
                    }
                    canonical = new Canonical(request);
                    requests.put(request.getId(), new KnownRequest(request, canonical));
                    active.put(functionalKey(request), canonical);
                    decision = decision(Kind.ADDED, "Incorporado a la agenda.", canonical);
                }
            }
        } catch (IllegalArgumentException exception) {
            decision = reject(exception.getMessage());
        }
        commands.put(key, new History(command, decision));
        if (decision.kind() != Kind.REJECTED) {
            UUID canonical = decision.canonicalRequestId().orElseThrow();
            if (decision.requestStatus().orElseThrow() == RequestStatus.SERVED) {
                completions.add(new Completion(command, canonical));
            } else {
                awaitingService.computeIfAbsent(canonical, ignored -> new ArrayList<>()).add(command);
            }
        }
        return decision;
    }

    /** Marca únicamente las identidades canónicas que el modelo acaba de servir. */
    public void markServed(List<Request> served) {
        for (Request request : served) {
            KnownRequest known = requests.get(request.getId());
            if (known == null || !sameRequest(known.canonical().request, request)) {
                throw new IllegalStateException("Servicio sin solicitud canónica registrada.");
            }
        }
        for (Request request : served) {
            Canonical canonical = requests.get(request.getId()).canonical();
            canonical.status = RequestStatus.SERVED;
            active.remove(functionalKey(request), canonical);
            List<Command> waiting = awaitingService.remove(request.getId());
            if (waiting != null) {
                for (Command command : waiting) {
                    completions.add(new Completion(command, request.getId()));
                }
            }
        }
    }

    private static Decision decision(Kind kind, String detail, Canonical canonical) {
        return new Decision(kind, detail, Optional.of(canonical.request.getId()),
                Optional.of(canonical.status), false);
    }

    private static Decision reject(String detail) {
        return new Decision(Kind.REJECTED, detail, Optional.empty(), Optional.empty(), false);
    }

    private static Request requestOf(Command command) {
        if (command instanceof Command.Destination destination) { return destination.request(); }
        if (command instanceof Command.Pickup pickup) { return pickup.request(); }
        throw new IllegalArgumentException("Comando sin solicitud de servicio.");
    }

    private static FunctionalKey functionalKey(Request request) {
        return request instanceof HallRequest hall
                ? new FunctionalKey(false, request.getFloor(), hall.getDirection())
                : new FunctionalKey(true, request.getFloor(), null);
    }

    /** Request.equals solo compara UUID; aquí también se protege su contenido inmutable. */
    private static boolean sameRequest(Request first, Request second) {
        if (first.getClass() != second.getClass() || !first.getId().equals(second.getId())
                || first.getEpoch() != second.getEpoch() || first.getFloor() != second.getFloor()
                || !first.getCreatedAt().equals(second.getCreatedAt())) { return false; }
        if (first instanceof CabinRequest cabin) {
            return cabin.getElevatorId() == ((CabinRequest) second).getElevatorId();
        }
        return ((HallRequest) first).getDirection() == ((HallRequest) second).getDirection();
    }
}
```

## src/main/java/com/elevatormanager/simulation/ElevatorWorker.java

```java
package com.elevatormanager.simulation;

import com.elevatormanager.model.ElevatorSnapshot;
import com.elevatormanager.model.ElevatorPhase;
import com.elevatormanager.model.ElevatorState;
import com.elevatormanager.model.SimulationConfig;
import com.elevatormanager.model.Direction;
import com.elevatormanager.model.request.Request;
import com.elevatormanager.protocol.Command;
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
 * T37: reset prioritario por cabina, retorno físico y controles serializados.
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

    private final ElevatorState state;
    private final CommandLedger ledger;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition available = lock.newCondition();
    private final ArrayDeque<Command> inbox = new ArrayDeque<>();
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
    private final ArrayDeque<ResetTicket> resetInbox = new ArrayDeque<>();
    private ResetOperation lastReset;
    private boolean resetHeld;
    private Optional<Direction> completedSweep = Optional.empty();

    public ElevatorWorker(int id, SimulationConfig config, long epoch) {
        state = new ElevatorState(id, config, epoch);
        ledger = new CommandLedger(state);
        published = new AtomicReference<>(capture(System.nanoTime()));
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
        Objects.requireNonNull(command, "command");
        lock.lock();
        try {
            if (lifecycle != Lifecycle.RUNNING) { return false; }
            if (command instanceof Command.Reset reset) {
                resetInbox.addLast(new ResetTicket(reset, new CompletableFuture<>()));
                available.signalAll();
                return true;
            }
            if (resetHeld || !resetInbox.isEmpty()) { return false; }
            inbox.addLast(command);
            available.signal();
            return true;
        } finally {
            lock.unlock();
        }
    }

    /** El futuro confirma piso 1/UP; aceptar el control no significa terminar el retorno. */
    public CompletableFuture<ElevatorSnapshot> requestReset(Command.Reset command) {
        Objects.requireNonNull(command, "command");
        lock.lock();
        try {
            if (lifecycle != Lifecycle.RUNNING) {
                return CompletableFuture.failedFuture(new IllegalStateException("Cabina no disponible."));
            }
            var response = new CompletableFuture<ElevatorSnapshot>();
            resetInbox.addLast(new ResetTicket(command, response));
            available.signalAll();
            return response.copy();
        } finally { lock.unlock(); }
    }

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
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(expected, "expected");
        lock.lock();
        try {
            if (lifecycle != Lifecycle.RUNNING) { return Submission.UNAVAILABLE; }
            if (published.get() != expected) { return Submission.STALE; }
            if (command instanceof Command.Reset reset) {
                resetInbox.addLast(new ResetTicket(reset, new CompletableFuture<>()));
                available.signalAll();
                return Submission.ENQUEUED;
            }
            if (resetHeld || !resetInbox.isEmpty()) { return Submission.UNAVAILABLE; }
            inbox.addLast(command);
            available.signal();
            return Submission.ENQUEUED;
        } finally { lock.unlock(); }
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
        return new PlanningSnapshot(state.snapshot(now),
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

    private void runLoop() {
        try {
            while (true) {
                Result result = null;
                CompletableFuture<Void> stoppedAck = null;
                List<Runnable> deferred = new ArrayList<>();
                List<Service> notifications = new ArrayList<>();
                lock.lockInterruptibly();
                try {
                    if (lifecycle != Lifecycle.RUNNING) { break; }
                    long now = System.nanoTime();
                    if (!resetInbox.isEmpty()) {
                        processReset(resetInbox.removeFirst(), now, deferred);
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
                        result = admit(inbox.removeFirst());
                        continueWork(System.nanoTime(), notifications);
                    } else if (state.getPhase() == ElevatorPhase.MOVING
                            || state.getPhase() == ElevatorPhase.DWELLING) {
                        long remaining = state.phaseRemainingNanos(System.nanoTime());
                        if (remaining > 0) { available.awaitNanos(remaining); }
                    } else {
                        available.await();
                    }
                } finally {
                    lock.unlock();
                }
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
            List<Command> abandoned;
            List<ResetTicket> abandonedResets;
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
            for (Command command : abandoned) {
                Result rejected = new Result(command, Outcome.REJECTED, "Trabajador finalizado.");
                emitAdmission(rejected, System.nanoTime());
                results.add(rejected);
            }
            for (ResetTicket ticket : abandonedResets) {
                Result rejected = new Result(ticket.command(), Outcome.REJECTED, "Cabina finalizada antes de procesar reset.");
                emitAdmission(rejected, System.nanoTime());
                results.add(rejected);
            }
            emit(failure == null ? ExecutionEvent.Type.WORKER_TERMINATED
                    : ExecutionEvent.Type.WORKER_FAILED, System.nanoTime());
            flushEvents();
            var resetFailure = new IllegalStateException("Cabina terminada antes de completar reset.", failure);
            for (ResetTicket ticket : abandonedResets) { ticket.response().completeExceptionally(resetFailure); }
            if (lastReset != null && !lastReset.completion().isDone()) {
                lastReset.completion().completeExceptionally(resetFailure);
            }
            if (stopConfirmation != null && !stopConfirmation.isDone()) {
                stopConfirmation.completeExceptionally(new IllegalStateException("Cabina terminada antes de confirmar Stop.", failure));
            }
        }
    }

    /** Admisión del control: cierre > reset > Stop > servicio. Solo escribe el actor. */
    private void processReset(ResetTicket ticket, long now, List<Runnable> deferred) {
        Command.Reset command = ticket.command();
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
        if (state.isResetting() || command.epoch() <= state.getEpoch()) {
            rejectReset(ticket, "Reset en curso o época no creciente.", now, deferred);
            return;
        }
        // Cancelaciones conservan el snapshot y la correlación de la época anterior.
        for (var cancelled : ledger.clearForReset()) {
            eventBatch.add(new ExecutionEvent(++eventSequence, ExecutionEvent.Type.COMMAND_CANCELLED,
                    state.snapshot(now), Optional.of(cancelled.command()),
                    Optional.of(cancelled.canonicalRequestId()), "Cancelado por reset prioritario."));
        }
        while (!inbox.isEmpty()) {
            var rejected = new Result(inbox.removeFirst(), Outcome.REJECTED, "Descartado del inbox por reset.");
            emitAdmission(rejected, now);
            deferred.add(() -> results.add(rejected));
        }
        if (stopConfirmation != null && !stopConfirmation.isDone()) {
            var replaced = stopConfirmation;
            deferred.add(() -> replaced.completeExceptionally(new IllegalStateException("Stop sustituido por reset.")));
        }
        stopRequested = false;
        stopped = false;
        resumeRequested = false;
        resetHeld = true;
        completedSweep = Optional.empty();
        state.beginReset(command.epoch());
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
        eventBatch.add(new ExecutionEvent(++eventSequence, type, state.snapshot(now),
                Optional.of(lastReset.command()), Optional.empty(), ""));
    }

    /** Sección local breve: sin I/O, callbacks ni bloqueos de otras cabinas. */
    private Result admit(Command command) {
        if (lastReset != null && command.epoch() == lastReset.command().epoch()
                && command.commandId() == lastReset.command().commandId()) {
            var rejected = new Result(command, Outcome.REJECTED, "ID reservado por el reset de esta época.");
            emitAdmission(rejected, System.nanoTime());
            return rejected;
        }
        CommandLedger.Decision decision = ledger.admit(command);
        published.set(capture(System.nanoTime()));
        Outcome outcome = switch (decision.kind()) {
            case ADDED -> Outcome.ACCEPTED;
            case DUPLICATE -> Outcome.DUPLICATE;
            case REJECTED -> Outcome.REJECTED;
        };
        Result result = new Result(command, outcome, decision.detail(), decision.canonicalRequestId(),
                decision.requestStatus(), decision.replay());
        long now = System.nanoTime();
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
        eventBatch.add(new ExecutionEvent(++eventSequence, type, state.snapshot(now),
                Optional.empty(), Optional.empty(), ""));
    }

    /** Sin lock del buzón, callbacks, I/O ni espera de consumidores. */
    private void flushEvents() {
        for (ExecutionEvent event : eventBatch) {
            events.add(event);
            for (var subscriber : subscribers) { subscriber.publish(event); }
        }
        eventBatch.clear();
    }

    private static long timeoutNanos(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative()) { throw new IllegalArgumentException("Tiempo negativo."); }
        return timeout.toNanos();
    }
}
```

## src/main/java/com/elevatormanager/simulation/ExecutionEvent.java

```java
package com.elevatormanager.simulation;

import com.elevatormanager.model.ElevatorSnapshot;
import com.elevatormanager.protocol.Command;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Hecho inmutable capturado por el actor, antes de ejecutar la siguiente transición.
 * sequence ordena eventos de una cabina; revision ordena cambios de su modelo.
 * referenceNanos pertenece al reloj monotónico de esta JVM, no a una fecha civil.
 */
public record ExecutionEvent(long sequence, Type type, ElevatorSnapshot snapshot,
        Optional<Command> command, Optional<UUID> canonicalRequestId, String detail) {
    public enum Type {
        COMMAND_ACCEPTED, COMMAND_DUPLICATE, COMMAND_REJECTED, COMMAND_REPLAYED,
        DEPARTED, ARRIVED, STOP_STARTED, STOP_ENDED, DIRECTION_CHANGED,
        COMMAND_COMPLETED, WORKER_TERMINATED, WORKER_FAILED, CONTROL_STOPPED, CONTROL_RESUMED,
        COMMAND_CANCELLED, RESET_STARTED, RESET_COMPLETED
    }

    public ExecutionEvent {
        if (sequence < 1) { throw new IllegalArgumentException("Secuencia no positiva."); }
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(canonicalRequestId, "canonicalRequestId");
        Objects.requireNonNull(detail, "detail");
    }

    public int elevatorId() { return snapshot.id(); }
    public long epoch() { return snapshot.epoch(); }
    public long referenceNanos() { return snapshot.referenceNanos(); }
}
```

## src/test/java/com/elevatormanager/simulation/CabinResetScenarios.java

```java
package com.elevatormanager.simulation;

import com.elevatormanager.model.*;
import com.elevatormanager.model.request.CabinRequest;
import com.elevatormanager.model.request.HallRequest;
import com.elevatormanager.protocol.Command;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Predicate;
import static com.elevatormanager.simulation.ExecutionEvent.Type.*;

/** T37: reset local con reloj explícito y actores reales; no coordina reset global. */
public final class CabinResetScenarios {
    private static final Duration LIMIT = Duration.ofSeconds(8);
    private CabinResetScenarios() { }

    public static void modelPreservesPartialLeg() {
        var s = new ElevatorState(1, new SimulationConfig(1, 5, 100, 200), 0);
        s.addPendingRequest(request(1, 5, 0)); s.beginJourney(5, 0); s.beginReset(1);
        var p = s.snapshot(40_000_000L);
        check(p.currentFloor() == 1 && p.nextFloor().orElseThrow() == 2, "No teletransporta");
        check(p.remainingNanos() == 60_000_000L && p.mode() == ElevatorSnapshot.Mode.RESET_RETURN, "Restante original");
        check(p.cabinRequests().isEmpty() && p.epoch() == 1, "Agenda invalidada");
        check(!s.completeLegIfDue(99_999_999L), "No llega antes del tiempo");
        check(s.completeLegIfDue(100_000_000L), "Tramo termina en instante original");
        s.beginJourney(1, 100_000_000L); check(s.completeLegIfDue(200_000_000L), "Retorno físico");
        s.finishReset(); check(s.getCurrentFloor() == 1 && s.getDirection() == Direction.UP, "Fin en 1/UP");
    }

    public static void modelDiscardsDwell() {
        var s = modelAtDwell(); s.beginReset(1);
        check(s.getPhase() == ElevatorPhase.IDLE && s.snapshot(5).remainingNanos() == 0, "Cancela duración de parada");
        s.finishReset();
    }

    public static void modelDiscardsHeldDwell() {
        var s = modelAtDwell(); s.holdDwellForStop(1); s.beginReset(1);
        check(s.getPhase() == ElevatorPhase.IDLE, "Reset libera HELD"); s.finishReset();
    }

    public static void modelRejectsNonIncreasingEpochAtomically() {
        var s = modelAtDwell(); var before = s.snapshot(1);
        expectIllegal(() -> s.beginReset(0));
        check(s.snapshot(1).equals(before), "Época inválida sin mutación");
    }

    public static void modelDoesNotFinishBeforeFloorOne() {
        var s = new ElevatorState(1, new SimulationConfig(1, 5, 10, 10), 0);
        s.beginJourney(3, 0); s.completeLegIfDue(10_000_000L); s.beginReset(1);
        expectIllegal(s::finishReset);
        expectIllegal(() -> s.addPendingRequest(request(1, 3, 1)));
        expectIllegal(() -> s.beginDwell(Direction.UP, 10_000_000L));
    }

    public static void idleResetConfirmsAndHolds() throws Exception {
        try (var r = new Rig(5, 30, 10)) {
            var done = get(r.w.requestReset(reset(100, 1))); r.drain();
            check(done.currentFloor() == 1 && done.direction() == Direction.UP && done.epoch() == 1, "Confirmación física");
            check(r.count(RESET_STARTED) == 1 && r.count(RESET_COMPLETED) == 1 && r.count(DEPARTED) == 0, "Reset sin viaje innecesario");
            check(!r.w.submit(destination(1, 1, 2)), "Retenida después del reset");
            check(r.w.resume(), "Continuación explícita habilitada");
        }
    }

    public static void elevatedIdleReturnsThroughEveryFloor() throws Exception {
        try (var r = new Rig(5, 30, 10)) {
            r.go(5); int from = r.trace.size(); get(r.w.requestReset(reset(100, 1))); r.drain();
            check(r.floors(from).equals(List.of(4, 3, 2, 1)), "Retorno adyacente completo");
            r.noServiceAfterReset(); r.verifyPhysicalTimes();
        }
    }

    public static void upwardResetFinishesPartialThenReturns() throws Exception {
        try (var r = new Rig(5, 160, 10)) {
            r.send(destination(1, 0, 5)); r.until(DEPARTED);
            get(r.w.requestReset(reset(100, 1))); r.drain();
            check(r.floors(0).equals(List.of(2, 1)), "Completa subida iniciada y regresa");
            r.noServiceAfterReset(); r.verifyPhysicalTimes();
        }
    }

    public static void downwardResetKeepsCurrentLeg() throws Exception {
        try (var r = new Rig(5, 60, 10)) {
            r.go(5); int from = r.trace.size(); r.send(destination(2, 0, 1)); r.until(DEPARTED);
            get(r.w.requestReset(reset(100, 1))); r.drain();
            check(r.floors(from).equals(List.of(4, 3, 2, 1)), "No repite ni salta el tramo bajando");
            r.noServiceAfterReset(); r.verifyPhysicalTimes();
        }
    }

    public static void resetCancelsOpenDwellWithoutWaiting() throws Exception {
        try (var r = new Rig(5, 20, 60_000)) {
            r.send(destination(1, 0, 3)); r.until(COMMAND_COMPLETED);
            get(r.w.requestReset(reset(100, 1))); r.drain();
            check(r.count(STOP_ENDED) == 0, "No espera ni termina artificialmente la parada cancelada");
            check(r.count(COMMAND_CANCELLED) == 0 && r.count(COMMAND_COMPLETED) == 1, "No cancela ni repite servicio ya realizado");
            check(r.w.snapshot().currentFloor() == 1, "Retorno antes del minuto de parada"); r.noServiceAfterReset();
        }
    }

    public static void resetAdvancesFromStoppedCabin() throws Exception {
        try (var r = new Rig(5, 20, 10)) {
            r.go(4); r.w.requestStop().get(8, TimeUnit.SECONDS);
            check(get(r.w.requestReset(reset(100, 1))).currentFloor() == 1, "Reset avanza desde Stop");
        }
    }

    public static void resetAdvancesFromHeldDwell() throws Exception {
        try (var r = new Rig(5, 20, 60_000)) {
            r.send(destination(1, 0, 1)); r.until(COMMAND_COMPLETED);
            r.w.requestStop().get(8, TimeUnit.SECONDS);
            check(r.w.snapshot().phase() == ElevatorPhase.HELD, "Parada congelada observada");
            var s = get(r.w.requestReset(reset(100, 1)));
            check(s.phase() == ElevatorPhase.IDLE && s.holdReason().isEmpty(), "Descarta congelación");
        }
    }

    public static void resetHasPriorityOverQueuedService() throws Exception {
        try (var r = new Rig(5, 20, 10)) {
            r.w.requestStop().get(8, TimeUnit.SECONDS);
            for (int i = 1; i <= 100; i++) { check(r.w.submit(destination(i, 0, 5)), "Entrada preservada antes de reset"); }
            get(r.w.requestReset(reset(200, 1)));
            int rejected = 0, accepted = 0;
            for (int i = 0; i < 101; i++) {
                var result = r.w.awaitResult(LIMIT).orElseThrow();
                if (result.outcome() == ElevatorWorker.Outcome.REJECTED) { rejected++; }
                if (result.outcome() == ElevatorWorker.Outcome.ACCEPTED) { accepted++; }
            }
            r.drain(); check(rejected == 100 && accepted == 1, "Descarta inbox; admite solo reset");
            check(r.count(DEPARTED) == 0, "No ejecuta trabajo descartado");
        }
    }

    public static void admittedAliasesAreCancelledExplicitly() throws Exception {
        try (var r = new Rig(5, 200, 10)) {
            var first = destination(1, 0, 5); r.send(first); r.until(DEPARTED);
            r.send(destination(2, 0, 5));
            get(r.w.requestReset(reset(100, 1))); r.drain();
            var cancelled = r.trace.stream().filter(e -> e.type() == COMMAND_CANCELLED).toList();
            check(cancelled.size() == 2, "Cancela canónica y alias pendientes");
            check(cancelled.stream().allMatch(e -> e.epoch() == 0 && e.canonicalRequestId().orElseThrow().equals(first.request().getId())), "Correlación anterior intacta");
            r.noServiceAfterReset();
        }
    }

    public static void resetSupersedesPendingStop() throws Exception {
        try (var r = new Rig(5, 300, 10)) {
            r.send(destination(1, 0, 5)); r.until(DEPARTED);
            var stop = r.w.requestStop(); var reset = r.w.requestReset(reset(100, 1));
            expectFailure(stop); get(reset);
            check(r.w.snapshot().currentFloor() == 1, "Stop no impide retornar");
        }
    }

    public static void repeatedResetSharesPhysicalOperation() throws Exception {
        try (var r = new Rig(5, 120, 10)) {
            r.go(4); var command = reset(100, 1);
            var a = r.w.requestReset(command); var b = r.w.requestReset(command);
            check(get(a).equals(get(b)), "Misma confirmación para repeticiones"); r.drain();
            check(r.count(RESET_STARTED) == 1 && r.count(RESET_COMPLETED) == 1, "Una operación física");
        }
    }

    public static void completedResetReplayDoesNotResetAgain() throws Exception {
        try (var r = new Rig(5, 20, 10)) {
            var command = reset(100, 1); var done = get(r.w.requestReset(command));
            check(r.w.resume(), "Continúa"); r.send(destination(101, 1, 2)); r.until(COMMAND_COMPLETED);
            check(get(r.w.requestReset(command)).equals(done), "Replay devuelve confirmación histórica");
            r.drain(); check(r.w.snapshot().currentFloor() == 2 && r.count(RESET_STARTED) == 1, "Replay no mueve ni adopta otra época");
        }
    }

    public static void nonIncreasingResetIsRejected() throws Exception {
        try (var r = new Rig(5, 20, 10)) {
            expectFailure(r.w.requestReset(reset(100, 0))); r.drain();
            check(r.w.snapshot().epoch() == 0 && r.count(RESET_STARTED) == 0, "Reset inválido sin adopción");
        }
    }

    public static void wrongTargetIsRejected() throws Exception {
        try (var r = new Rig(5, 20, 10)) {
            expectFailure(r.w.requestReset(new Command.Reset(100, 1, 2)));
            check(r.w.snapshot().epoch() == 0, "Cabina ajena rechazada");
        }
    }

    public static void distinctResetWhileReturningIsRejected() throws Exception {
        try (var r = new Rig(5, 100, 10)) {
            r.go(5); var active = r.w.requestReset(reset(100, 1)); r.until(RESET_STARTED);
            expectFailure(r.w.requestReset(reset(101, 2)));
            check(get(active).epoch() == 1, "No reemplaza época en retorno");
        }
    }

    public static void staleServiceCannotReappearAfterReset() throws Exception {
        try (var r = new Rig(5, 20, 10)) {
            get(r.w.requestReset(reset(100, 1))); check(r.w.resume(), "Continúa");
            check(r.send(destination(1, 0, 5)).outcome() == ElevatorWorker.Outcome.REJECTED, "Servicio de época anterior rechazado");
            check(r.w.snapshot().cabinRequests().isEmpty(), "No restaura pendientes antiguos");
        }
    }

    public static void newEpochMayReuseOldServiceIdentity() throws Exception {
        try (var r = new Rig(5, 80, 10)) {
            var old = destination(1, 0, 5); r.send(old); r.until(DEPARTED);
            get(r.w.requestReset(reset(100, 1))); check(r.w.resume(), "Continúa");
            var renewed = new CabinRequest(old.request().getId(), 1, 2, Instant.now(), 1);
            check(r.send(new Command.Destination(1, 1, renewed)).outcome() == ElevatorWorker.Outcome.ACCEPTED, "Historial anterior liberado");
            var e = r.until(COMMAND_COMPLETED); check(e.epoch() == 1, "Servicio pertenece a época nueva");
        }
    }

    public static void resetCommandIdCannotBecomeServiceCommand() throws Exception {
        try (var r = new Rig(5, 20, 10)) {
            get(r.w.requestReset(reset(100, 1))); check(r.w.resume(), "Continúa");
            check(r.send(destination(100, 1, 3)).outcome() == ElevatorWorker.Outcome.REJECTED, "ID de reset reservado");
        }
    }

    public static void closeDuringResetDoesNotConfirmSuccess() throws Exception {
        try (var r = new Rig(5, 160, 10)) {
            r.go(5); var f = r.w.requestReset(reset(100, 1)); r.until(RESET_STARTED);
            r.w.close(); expectFailure(f); check(r.w.awaitTermination(LIMIT), "Cierre despierta retorno"); r.drain();
            check(r.count(RESET_COMPLETED) == 0, "No confirma retorno interrumpido");
        }
    }

    public static void unavailableWorkerRejectsReset() throws Exception {
        var w = new ElevatorWorker(1, new SimulationConfig(1, 1, 1, 1), 0);
        expectFailure(w.requestReset(reset(100, 1))); w.close();
        expectFailure(w.requestReset(reset(101, 2))); check(w.awaitTermination(LIMIT), "Sin hilo creado");
    }

    public static void submitRoutesResetThroughPriorityLane() throws Exception {
        try (var r = new Rig(5, 20, 10)) {
            r.w.requestStop().get(8, TimeUnit.SECONDS);
            check(r.w.submit(reset(100, 1)), "Reset por submit");
            r.until(RESET_COMPLETED); check(r.w.snapshot().epoch() == 1, "Pasó por control prioritario");
        }
    }

    public static void conditionalSubmitAlsoRoutesReset() throws Exception {
        try (var r = new Rig(5, 20, 10)) {
            r.w.requestStop().get(8, TimeUnit.SECONDS);
            var s = r.w.planningSnapshot();
            check(r.w.submitIfCurrent(reset(100, 1), s) == ElevatorWorker.Submission.ENQUEUED, "Envío condicionado de reset");
            r.until(RESET_COMPLETED);
        }
    }

    public static void successiveResetEpochsKeepActorAlive() throws Exception {
        try (var r = new Rig(1, 1, 1)) {
            long revision = 0;
            for (int i = 1; i <= 100; i++) {
                var s = get(r.w.requestReset(reset(100, i)));
                check(s.epoch() == i && s.revision() > revision, "Época y revisión crecientes"); revision = s.revision();
            }
            r.drain(); check(r.count(RESET_STARTED) == 100 && r.count(RESET_COMPLETED) == 100, "Un fin por época");
        }
    }

    public static void anotherCabinRemainsIndependent() throws Exception {
        var config = new SimulationConfig(2, 5, 100, 10);
        var a = new ElevatorWorker(1, config, 0); var b = new ElevatorWorker(2, config, 0);
        try {
            a.start(); b.start(); a.submit(destination(1, 0, 5));
            untilWorker(a, STOP_ENDED);
            var resetting = a.requestReset(reset(100, 1)); untilWorker(a, RESET_STARTED);
            var request = new CabinRequest(UUID.randomUUID(), 0, 2, Instant.now(), 2);
            b.submit(new Command.Destination(1, 2, request)); untilWorker(b, COMMAND_COMPLETED);
            check(!resetting.isDone() && b.snapshot().epoch() == 0, "B atiende sin reset global ni bloqueo");
            get(resetting);
        } finally { a.close(); b.close(); check(a.awaitTermination(LIMIT) && b.awaitTermination(LIMIT), "Ambos terminan"); }
    }

    public static void controlsAndServiceAreGatedDuringReset() throws Exception {
        try (var r = new Rig(5, 100, 10)) {
            r.go(5); var f = r.w.requestReset(reset(100, 1)); r.until(RESET_STARTED);
            check(!r.w.submit(destination(101, 1, 2)), "No admite servicio en retorno");
            check(!r.w.resume(), "No continúa antes de llegar"); expectFailure(r.w.requestStop());
            check(r.w.submitIfCurrent(destination(102, 1, 2), r.w.planningSnapshot()) != ElevatorWorker.Submission.ENQUEUED, "Sin envío condicionado en retorno");
            get(f);
        }
    }

    public static void callerCannotCancelInternalResetBarrier() throws Exception {
        try (var r = new Rig(5, 80, 10)) {
            r.go(4); var command = reset(100, 1); var visible = r.w.requestReset(command);
            visible.cancel(false);
            check(get(r.w.requestReset(command)).currentFloor() == 1, "Copia cancelada no interrumpe reset real");
        }
    }

    public static void externalAgendaIsCancelledWithoutPickup() throws Exception {
        try (var r = new Rig(5, 160, 10)) {
            var hall = new HallRequest(UUID.randomUUID(), 0, 4, Instant.now(), Direction.UP);
            r.send(new Command.Pickup(1, 1, hall)); r.until(DEPARTED);
            get(r.w.requestReset(reset(100, 1))); r.drain();
            check(r.w.snapshot().hallRequests().isEmpty(), "Agenda externa vacía al retornar");
            check(r.trace.stream().anyMatch(e -> e.type() == COMMAND_CANCELLED
                    && e.command().orElseThrow() instanceof Command.Pickup
                    && e.canonicalRequestId().orElseThrow().equals(hall.getId())), "Recogida cancelada y correlacionada");
            r.noServiceAfterReset();
        }
    }

    private static ElevatorState modelAtDwell() {
        var s = new ElevatorState(1, new SimulationConfig(1, 5, 10, 200), 0);
        s.addPendingRequest(request(1, 1, 0)); s.beginDwell(Direction.UP, 0); return s;
    }
    private static CabinRequest request(int id, int floor, long epoch) { return new CabinRequest(UUID.randomUUID(), epoch, floor, Instant.now(), id); }
    private static Command.Destination destination(long id, long epoch, int floor) { return new Command.Destination(id, 1, request(1, floor, epoch)); }
    private static Command.Reset reset(long id, long epoch) { return new Command.Reset(id, epoch, 1); }
    private static ElevatorSnapshot get(CompletableFuture<ElevatorSnapshot> f) throws Exception { return f.get(8, TimeUnit.SECONDS); }
    private static void expectFailure(CompletableFuture<?> f) throws Exception {
        try { f.get(8, TimeUnit.SECONDS); throw new AssertionError("Debía fallar"); }
        catch (ExecutionException expected) { check(expected.getCause() != null, "Causa informada"); }
    }
    private static void expectIllegal(Runnable action) {
        try { action.run(); throw new AssertionError("Debía rechazar"); }
        catch (IllegalArgumentException | IllegalStateException expected) { }
    }
    private static void check(boolean ok, String detail) { if (!ok) { throw new AssertionError(detail); } }
    private static void untilWorker(ElevatorWorker w, ExecutionEvent.Type type) throws Exception {
        long began = System.nanoTime();
        while (true) {
            long left = LIMIT.toNanos() - (System.nanoTime() - began); check(left > 0, "Plazo de evento");
            var e = w.awaitEvent(Duration.ofNanos(left)).orElseThrow(); check(e.type() != WORKER_FAILED, "Cabina sana");
            if (e.type() == type) { return; }
        }
    }

    private static final class Rig implements AutoCloseable {
        final ElevatorWorker w; final List<ExecutionEvent> trace = new ArrayList<>();
        Rig(int floors, long move, long dwell) {
            w = new ElevatorWorker(1, new SimulationConfig(1, floors, move, dwell), 0); w.start();
        }
        ElevatorWorker.Result send(Command command) throws Exception {
            check(w.submit(command), "Comando encolado");
            long began = System.nanoTime();
            while (true) {
                long left = LIMIT.toNanos() - (System.nanoTime() - began); check(left > 0, "Plazo de admisión");
                var result = w.awaitResult(Duration.ofNanos(left)).orElseThrow();
                if (result.command().sameContent(command)) { return result; }
            }
        }
        void go(int floor) throws Exception { send(destination(1, 0, floor)); until(STOP_ENDED); }
        ExecutionEvent until(ExecutionEvent.Type type) throws Exception { return until(e -> e.type() == type); }
        ExecutionEvent until(Predicate<ExecutionEvent> predicate) throws Exception {
            long began = System.nanoTime();
            while (true) {
                long left = LIMIT.toNanos() - (System.nanoTime() - began); check(left > 0, "Plazo de evento");
                var e = w.awaitEvent(Duration.ofNanos(left)).orElseThrow(); trace.add(e);
                check(e.type() != WORKER_FAILED, "Cabina sana: " + w.failure());
                if (predicate.test(e)) { return e; }
            }
        }
        void drain() throws Exception {
            while (true) { var e = w.awaitEvent(Duration.ZERO); if (e.isEmpty()) { return; } trace.add(e.orElseThrow()); }
        }
        long count(ExecutionEvent.Type type) { return trace.stream().filter(e -> e.type() == type).count(); }
        List<Integer> floors(int from) { return trace.subList(from, trace.size()).stream().filter(e -> e.type() == ARRIVED).map(e -> e.snapshot().currentFloor()).toList(); }
        void noServiceAfterReset() {
            boolean started = false;
            for (var e : trace) {
                if (e.type() == RESET_STARTED) { started = true; }
                if (started) { check(e.type() != STOP_STARTED && e.type() != COMMAND_COMPLETED, "Reset no atiende pasajeros"); }
            }
        }
        void verifyPhysicalTimes() {
            ExecutionEvent departed = null;
            for (var e : trace) {
                if (e.type() == DEPARTED) { departed = e; }
                if (e.type() == ARRIVED) {
                    check(departed != null, "Llegada con salida previa");
                    check(e.snapshot().currentFloor() == departed.snapshot().nextFloor().orElseThrow(), "Tramo adyacente");
                    check(e.referenceNanos() - departed.referenceNanos() >= e.snapshot().config().movementTimeMillis() * 1_000_000L, "Tiempo físico mínimo");
                    departed = null;
                }
            }
        }
        @Override public void close() throws Exception {
            w.close(); check(w.awaitTermination(LIMIT), "Hilo terminado");
            check(w.failure().isEmpty(), "Sin fallo de actor: " + w.failure());
        }
    }

    public static void main(String[] args) throws Exception {
        modelPreservesPartialLeg(); modelDiscardsDwell(); modelDiscardsHeldDwell();
        modelRejectsNonIncreasingEpochAtomically(); modelDoesNotFinishBeforeFloorOne();
        idleResetConfirmsAndHolds(); elevatedIdleReturnsThroughEveryFloor(); upwardResetFinishesPartialThenReturns();
        downwardResetKeepsCurrentLeg(); resetCancelsOpenDwellWithoutWaiting(); resetAdvancesFromStoppedCabin();
        resetAdvancesFromHeldDwell(); resetHasPriorityOverQueuedService(); admittedAliasesAreCancelledExplicitly();
        resetSupersedesPendingStop(); repeatedResetSharesPhysicalOperation(); completedResetReplayDoesNotResetAgain();
        nonIncreasingResetIsRejected(); wrongTargetIsRejected(); distinctResetWhileReturningIsRejected();
        staleServiceCannotReappearAfterReset(); newEpochMayReuseOldServiceIdentity(); resetCommandIdCannotBecomeServiceCommand();
        closeDuringResetDoesNotConfirmSuccess(); unavailableWorkerRejectsReset(); submitRoutesResetThroughPriorityLane();
        conditionalSubmitAlsoRoutesReset(); successiveResetEpochsKeepActorAlive(); anotherCabinRemainsIndependent();
        controlsAndServiceAreGatedDuringReset(); callerCannotCancelInternalResetBarrier();
        externalAgendaIsCancelledWithoutPickup();
        System.out.println("T37: 32 escenarios satisfactorios.");
    }
}
```

## src/test/java/com/elevatormanager/simulation/CabinResetTest.java

```java
package com.elevatormanager.simulation;

import org.junit.jupiter.api.Test;

class CabinResetTest {
    @Test void modelPreservesPartialLeg() throws Exception {
        CabinResetScenarios.modelPreservesPartialLeg();
    }
    @Test void modelDiscardsDwell() throws Exception {
        CabinResetScenarios.modelDiscardsDwell();
    }
    @Test void modelDiscardsHeldDwell() throws Exception {
        CabinResetScenarios.modelDiscardsHeldDwell();
    }
    @Test void modelRejectsNonIncreasingEpochAtomically() throws Exception {
        CabinResetScenarios.modelRejectsNonIncreasingEpochAtomically();
    }
    @Test void modelDoesNotFinishBeforeFloorOne() throws Exception {
        CabinResetScenarios.modelDoesNotFinishBeforeFloorOne();
    }
    @Test void idleResetConfirmsAndHolds() throws Exception {
        CabinResetScenarios.idleResetConfirmsAndHolds();
    }
    @Test void elevatedIdleReturnsThroughEveryFloor() throws Exception {
        CabinResetScenarios.elevatedIdleReturnsThroughEveryFloor();
    }
    @Test void upwardResetFinishesPartialThenReturns() throws Exception {
        CabinResetScenarios.upwardResetFinishesPartialThenReturns();
    }
    @Test void downwardResetKeepsCurrentLeg() throws Exception {
        CabinResetScenarios.downwardResetKeepsCurrentLeg();
    }
    @Test void resetCancelsOpenDwellWithoutWaiting() throws Exception {
        CabinResetScenarios.resetCancelsOpenDwellWithoutWaiting();
    }
    @Test void resetAdvancesFromStoppedCabin() throws Exception {
        CabinResetScenarios.resetAdvancesFromStoppedCabin();
    }
    @Test void resetAdvancesFromHeldDwell() throws Exception {
        CabinResetScenarios.resetAdvancesFromHeldDwell();
    }
    @Test void resetHasPriorityOverQueuedService() throws Exception {
        CabinResetScenarios.resetHasPriorityOverQueuedService();
    }
    @Test void admittedAliasesAreCancelledExplicitly() throws Exception {
        CabinResetScenarios.admittedAliasesAreCancelledExplicitly();
    }
    @Test void resetSupersedesPendingStop() throws Exception {
        CabinResetScenarios.resetSupersedesPendingStop();
    }
    @Test void repeatedResetSharesPhysicalOperation() throws Exception {
        CabinResetScenarios.repeatedResetSharesPhysicalOperation();
    }
    @Test void completedResetReplayDoesNotResetAgain() throws Exception {
        CabinResetScenarios.completedResetReplayDoesNotResetAgain();
    }
    @Test void nonIncreasingResetIsRejected() throws Exception {
        CabinResetScenarios.nonIncreasingResetIsRejected();
    }
    @Test void wrongTargetIsRejected() throws Exception {
        CabinResetScenarios.wrongTargetIsRejected();
    }
    @Test void distinctResetWhileReturningIsRejected() throws Exception {
        CabinResetScenarios.distinctResetWhileReturningIsRejected();
    }
    @Test void staleServiceCannotReappearAfterReset() throws Exception {
        CabinResetScenarios.staleServiceCannotReappearAfterReset();
    }
    @Test void newEpochMayReuseOldServiceIdentity() throws Exception {
        CabinResetScenarios.newEpochMayReuseOldServiceIdentity();
    }
    @Test void resetCommandIdCannotBecomeServiceCommand() throws Exception {
        CabinResetScenarios.resetCommandIdCannotBecomeServiceCommand();
    }
    @Test void closeDuringResetDoesNotConfirmSuccess() throws Exception {
        CabinResetScenarios.closeDuringResetDoesNotConfirmSuccess();
    }
    @Test void unavailableWorkerRejectsReset() throws Exception {
        CabinResetScenarios.unavailableWorkerRejectsReset();
    }
    @Test void submitRoutesResetThroughPriorityLane() throws Exception {
        CabinResetScenarios.submitRoutesResetThroughPriorityLane();
    }
    @Test void conditionalSubmitAlsoRoutesReset() throws Exception {
        CabinResetScenarios.conditionalSubmitAlsoRoutesReset();
    }
    @Test void successiveResetEpochsKeepActorAlive() throws Exception {
        CabinResetScenarios.successiveResetEpochsKeepActorAlive();
    }
    @Test void anotherCabinRemainsIndependent() throws Exception {
        CabinResetScenarios.anotherCabinRemainsIndependent();
    }
    @Test void controlsAndServiceAreGatedDuringReset() throws Exception {
        CabinResetScenarios.controlsAndServiceAreGatedDuringReset();
    }
    @Test void callerCannotCancelInternalResetBarrier() throws Exception {
        CabinResetScenarios.callerCannotCancelInternalResetBarrier();
    }
    @Test void externalAgendaIsCancelledWithoutPickup() throws Exception {
        CabinResetScenarios.externalAgendaIsCancelledWithoutPickup();
    }
}
```

## src/test/java/com/elevatormanager/simulation/ElevatorWorkerScenarios.java

```java
package com.elevatormanager.simulation;

import com.elevatormanager.model.Direction;
import com.elevatormanager.model.ElevatorPhase;
import com.elevatormanager.model.SimulationConfig;
import com.elevatormanager.model.request.CabinRequest;
import com.elevatormanager.protocol.Command;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

/** Escenarios reutilizables desde JUnit y desde javac/java sin dependencias. */
public final class ElevatorWorkerScenarios {
    private static final Duration LIMIT = Duration.ofSeconds(5);
    private ElevatorWorkerScenarios() { }

    public static void initialState() throws Exception {
        try (var worker = worker(1)) {
            var snapshot = worker.snapshot();
            check(snapshot.currentFloor() == 1 && snapshot.direction() == Direction.UP,
                    "Inicio en piso 1 y UP.");
            check(snapshot.phase() == ElevatorPhase.IDLE && snapshot.revision() == 0,
                    "Estado inicial intacto.");
            check(worker.threadState() == Thread.State.NEW, "Constructor no arranca hilo.");
            check(!worker.submit(destination(1, 2)), "No encola antes de start.");
        }
    }

    public static void blockingAndWakeup() throws Exception {
        var worker = worker(1);
        try {
            worker.start();
            long beginning = System.nanoTime();
            while (worker.threadState() != Thread.State.WAITING) {
                check(System.nanoTime() - beginning < LIMIT.toNanos(), "Espera bloqueada.");
                LockSupport.parkNanos(1_000_000L);
            }
            check(worker.submit(destination(1, 2)), "Envío admitido al buzón.");
            accepted(worker);
            check(worker.snapshot().cabinRequests().size() == 1, "Señal despierta al consumidor.");
            check(worker.snapshot().currentFloor() == 1, "No cambia de piso antes del plazo.");
        } finally { finish(worker); }
    }

    public static void independentWorkers() throws Exception {
        var first = worker(1);
        var second = worker(2);
        var go = new CountDownLatch(1);
        var done = new CountDownLatch(2);
        var error = new AtomicReference<Throwable>();
        Thread a = producer(first, 1, go, done, error);
        Thread b = producer(second, 2, go, done, error);
        try {
            first.start(); second.start();
            check(!first.threadName().equals(second.threadName()), "Hilos identificables distintos.");
            a.start(); b.start(); go.countDown();
            check(done.await(5, TimeUnit.SECONDS), "Ambos productores terminan.");
            if (error.get() != null) { throw new AssertionError("Productor falló.", error.get()); }
            for (int i = 0; i < 100; i++) { accepted(first); accepted(second); }
            check(first.snapshot().cabinRequests().size() == 100, "Agenda propia 1.");
            check(second.snapshot().cabinRequests().size() == 100, "Agenda propia 2.");
            check(first.snapshot().cabinRequests().stream().allMatch(r -> r.getElevatorId() == 1),
                    "Sin mezcla de cabinas.");
        } finally {
            go.countDown(); a.join(5_000); b.join(5_000);
            finish(first); finish(second);
        }
    }

    public static void rejectionDoesNotKillWorker() throws Exception {
        var worker = worker(1);
        try {
            worker.start();
            worker.submit(destination(2, 2));
            check(result(worker).outcome() == ElevatorWorker.Outcome.REJECTED, "Rechaza destinatario.");
            worker.submit(new Command.Reset(9, 0, 1));
            check(result(worker).outcome() == ElevatorWorker.Outcome.REJECTED, "Reset con época no creciente.");
            worker.submit(new Command.Destination(10, 1,
                    new CabinRequest(UUID.randomUUID(), 1, 2, Instant.now(), 1)));
            check(result(worker).outcome() == ElevatorWorker.Outcome.REJECTED, "Rechaza otra época.");
            worker.submit(destination(1, 2)); accepted(worker);
            check(worker.failure().isEmpty(), "Rechazos recuperables no matan al trabajador.");
        } finally { finish(worker); }
    }

    public static void immutablePublication() throws Exception {
        var worker = worker(1);
        try {
            var previous = worker.snapshot();
            worker.start(); worker.submit(destination(1, 2)); accepted(worker);
            check(previous.cabinRequests().isEmpty(), "Snapshot anterior permanece intacto.");
            check(worker.snapshot().revision() > previous.revision(), "Revisión creciente.");
            try {
                worker.snapshot().cabinRequests().clear();
                throw new AssertionError("Colección modificable.");
            } catch (UnsupportedOperationException expected) { /* Copia inmutable. */ }
        } finally { finish(worker); }
    }

    public static void closeAccountsForQueuedCommands() throws Exception {
        var worker = worker(1);
        worker.start();
        try {
            for (int floor = 2; floor <= 101; floor++) {
                check(worker.submit(destination(1, floor)), "Encolado previo al cierre.");
            }
            worker.close();
            check(worker.awaitTermination(LIMIT), "Cierre despierta y termina.");
            for (int i = 0; i < 100; i++) { result(worker); }
            check(worker.awaitResult(Duration.ZERO).isEmpty(), "Un resultado por entrada.");
            check(!worker.submit(destination(1, 200)), "Cierre rechaza nuevas entradas.");
            check(worker.lifecycle() == ElevatorWorker.Lifecycle.TERMINATED, "Terminación normal.");
        } finally { finish(worker); }
    }

    public static void singleStartAndCloseBeforeStart() throws Exception {
        var worker = worker(1);
        try {
            check(worker.start() && !worker.start(), "Start idempotente.");
        } finally { finish(worker); }
        check(!worker.start(), "No reinicia hilo finalizado.");
        var neverStarted = worker(1);
        neverStarted.close(); neverStarted.close();
        check(!neverStarted.start() && neverStarted.awaitTermination(Duration.ZERO),
                "Cierre antes de start no crea un hilo.");
    }

    public static void main(String[] args) throws Exception {
        initialState(); blockingAndWakeup(); independentWorkers(); rejectionDoesNotKillWorker();
        immutablePublication(); closeAccountsForQueuedCommands(); singleStartAndCloseBeforeStart();
        System.out.println("T22: 7 escenarios satisfactorios.");
    }

    private static ElevatorWorker worker(int id) {
        return new ElevatorWorker(id, new SimulationConfig(2, 500, 10_000, 100), 0);
    }

    private static Command destination(int id, int floor) {
        return new Command.Destination(floor, id,
                new CabinRequest(UUID.randomUUID(), 0, floor, Instant.now(), id));
    }

    private static Thread producer(ElevatorWorker worker, int id, CountDownLatch go,
            CountDownLatch done, AtomicReference<Throwable> error) {
        return new Thread(() -> {
            try {
                check(go.await(5, TimeUnit.SECONDS), "Inicio coordinado.");
                for (int floor = 2; floor <= 101; floor++) {
                    check(worker.submit(destination(id, floor)), "Envío concurrente.");
                }
            } catch (Throwable exception) { error.compareAndSet(null, exception); }
            finally { done.countDown(); }
        }, "test-producer-" + id);
    }

    private static ElevatorWorker.Result result(ElevatorWorker worker) throws Exception {
        return worker.awaitResult(LIMIT).orElseThrow(() -> new AssertionError("Falta resultado."));
    }

    private static void accepted(ElevatorWorker worker) throws Exception {
        check(result(worker).outcome() == ElevatorWorker.Outcome.ACCEPTED, "Aceptación en agenda.");
    }

    private static void finish(ElevatorWorker worker) throws Exception {
        worker.close();
        check(worker.awaitTermination(LIMIT), "No quedan trabajadores vivos.");
    }

    private static void check(boolean condition, String message) {
        if (!condition) { throw new AssertionError(message); }
    }
}
```

## src/test/java/com/elevatormanager/simulation/ConcurrentAdmissionScenarios.java

```java
package com.elevatormanager.simulation;

import com.elevatormanager.model.Direction;
import com.elevatormanager.model.ElevatorPhase;
import com.elevatormanager.model.ElevatorState;
import com.elevatormanager.model.SimulationConfig;
import com.elevatormanager.model.request.CabinRequest;
import com.elevatormanager.model.request.HallRequest;
import com.elevatormanager.model.request.Request;
import com.elevatormanager.protocol.Command;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntFunction;

/** Identidades y alias deterministas; carreras mediante barreras, sin sleeps. */
public final class ConcurrentAdmissionScenarios {
    private static final Duration LIMIT = Duration.ofSeconds(5);
    private ConcurrentAdmissionScenarios() { }

    public static void firstAdmissionIsCanonical() {
        var f = new Fixture(); var request = cabin(2);
        var result = f.ledger.admit(destination(1, request));
        check(result.kind() == CommandLedger.Kind.ADDED && !result.replay(), "Admisión inicial.");
        check(result.canonicalRequestId().orElseThrow().equals(request.getId()), "UUID canónico.");
        check(result.requestStatus().orElseThrow() == CommandLedger.RequestStatus.PENDING,
                "Aceptar no significa atender.");
        check(f.state.getCabinRequests().equals(List.of(request)), "Una entrada en agenda.");
    }

    public static void pendingReplayDoesNotMutateState() {
        var f = new Fixture(); var command = destination(1, cabin(2));
        var first = f.ledger.admit(command); long revision = f.state.getRevision();
        var original = ((Command.Destination) command).request();
        var copy = new CabinRequest(original.getId(), original.getEpoch(), original.getFloor(),
                original.getCreatedAt(), original.getElevatorId());
        var replay = f.ledger.admit(destination(1, copy));
        check(replay.replay() && replay.kind() == first.kind(), "Reproduce decisión original.");
        check(f.state.getRevision() == revision && f.state.getCabinRequests().size() == 1,
                "No reinserta ni cambia revisión.");
    }

    public static void servedReplayDoesNotReinsert() {
        var f = new Fixture(); var command = destination(1, cabin(1));
        var initial = f.ledger.admit(command); f.serveCurrent();
        var replay = f.ledger.admit(command);
        check(replay.replay() && replay.requestStatus().orElseThrow() == CommandLedger.RequestStatus.SERVED,
                "Retransmisión informa servicio previo.");
        check(initial.requestStatus().orElseThrow() == CommandLedger.RequestStatus.PENDING,
                "Decisión anterior es una captura inmutable.");
        check(f.state.getCabinRequests().isEmpty(), "No reactiva un pedido servido.");
    }

    public static void commandIdConflictPreservesOriginal() {
        var f = new Fixture(); var original = destination(1, cabin(2));
        f.ledger.admit(original);
        check(f.ledger.admit(destination(1, cabin(3))).kind() == CommandLedger.Kind.REJECTED,
                "Rechaza commandId reutilizado.");
        check(f.ledger.admit(original).replay(), "Conflicto no reemplaza registro original.");
        check(f.state.getCabinRequests().get(0).getFloor() == 2, "Destino original intacto.");
    }

    public static void commandReplayPreservesCanonicalMetadata() {
        var f = new Fixture(); var request = cabin(2);
        f.ledger.admit(destination(1, request));
        var changed = new CabinRequest(request.getId(), 0, 2,
                request.getCreatedAt().plusNanos(1), 1);
        check(f.ledger.admit(destination(1, changed)).replay(), "Respeta Command.sameContent operativo.");
        check(f.state.getCabinRequests().get(0).getCreatedAt().equals(request.getCreatedAt()),
                "Reproducir la decisión no sobrescribe metadata canónica.");
    }

    public static void sameUuidWithNewCommandIsDuplicate() {
        var f = new Fixture(); var request = cabin(2);
        f.ledger.admit(destination(1, request));
        var duplicate = f.ledger.admit(destination(2, request));
        check(duplicate.kind() == CommandLedger.Kind.DUPLICATE && !duplicate.replay(),
                "Otro comando para UUID conocido.");
        check(duplicate.canonicalRequestId().orElseThrow().equals(request.getId()), "Misma canónica.");
    }

    public static void uuidContentCannotChange() {
        var f = new Fixture(); var request = cabin(2);
        f.ledger.admit(destination(1, request));
        var changed = new CabinRequest(request.getId(), 0, 3, request.getCreatedAt(), 1);
        check(f.ledger.admit(destination(2, changed)).kind() == CommandLedger.Kind.REJECTED,
                "UUID no cambia de piso.");
        var metadata = new CabinRequest(request.getId(), 0, 2, request.getCreatedAt().plusNanos(1), 1);
        check(f.ledger.admit(destination(3, metadata)).kind() == CommandLedger.Kind.REJECTED,
                "Una admisión nueva no redefine metadata de un UUID conocido.");
        check(f.state.getCabinRequests().equals(List.of(request)), "Agenda conserva original.");
    }

    public static void uuidTypeCannotChange() {
        var f = new Fixture(); var request = cabin(2);
        f.ledger.admit(destination(1, request));
        var hall = new HallRequest(request.getId(), 0, 2, request.getCreatedAt(), Direction.UP);
        check(f.ledger.admit(new Command.Pickup(2, 1, hall)).kind() == CommandLedger.Kind.REJECTED,
                "UUID no cambia de tipo.");
    }

    public static void functionalDuplicateCreatesAlias() {
        var f = new Fixture(); var first = cabin(2); var alias = cabin(2);
        f.ledger.admit(destination(1, first));
        var duplicate = f.ledger.admit(destination(2, alias));
        check(duplicate.kind() == CommandLedger.Kind.DUPLICATE, "Duplicado funcional controlado.");
        check(duplicate.canonicalRequestId().orElseThrow().equals(first.getId()), "Alias vinculado.");
        check(f.state.getPendingRequestsInAdmissionOrder().equals(List.of(first)),
                "Alias no duplica agenda ni reordena.");
    }

    public static void cabinAndHallDirectionsAreDifferentKeys() {
        var f = new Fixture();
        check(f.ledger.admit(destination(1, cabin(2))).kind() == CommandLedger.Kind.ADDED, "Interna.");
        check(f.ledger.admit(new Command.Pickup(2, 1, hall(2, Direction.UP))).kind()
                == CommandLedger.Kind.ADDED, "Externa UP distinta.");
        check(f.ledger.admit(new Command.Pickup(3, 1, hall(2, Direction.DOWN))).kind()
                == CommandLedger.Kind.ADDED, "Externa DOWN distinta.");
        check(f.state.getPendingRequestsInAdmissionOrder().size() == 3, "Tres identidades canónicas.");
    }

    public static void servedAliasDoesNotAttachToNewRequest() {
        var f = new Fixture(); var first = cabin(1); var alias = cabin(1);
        f.ledger.admit(destination(1, first));
        var aliasCommand = destination(2, alias);
        f.ledger.admit(aliasCommand); f.serveCurrent();
        var fresh = cabin(1); f.ledger.admit(destination(3, fresh));
        var replay = f.ledger.admit(aliasCommand);
        check(replay.requestStatus().orElseThrow() == CommandLedger.RequestStatus.SERVED,
                "Alias conserva resultado histórico.");
        check(replay.canonicalRequestId().orElseThrow().equals(first.getId()), "No migra al pedido nuevo.");
        f.ledger.markServed(List.of(first));
        var newest = f.ledger.admit(destination(4, cabin(1)));
        check(newest.canonicalRequestId().orElseThrow().equals(fresh.getId()),
                "Confirmación vieja no libera la clave de la nueva canónica.");
    }

    public static void freshIdentityCanRequestServedFloorAgain() {
        var f = new Fixture(); var first = cabin(1);
        f.ledger.admit(destination(1, first)); f.serveCurrent();
        var fresh = cabin(1); var result = f.ledger.admit(destination(2, fresh));
        check(result.kind() == CommandLedger.Kind.ADDED, "Piso servido vuelve a solicitarse.");
        check(result.canonicalRequestId().orElseThrow().equals(fresh.getId()), "Nueva identidad.");
        check(f.state.getCabinRequests().equals(List.of(fresh)), "Nuevo pendiente real.");
    }

    public static void invalidRoutingEpochAndResetDoNotReserveId() {
        var f = new Fixture();
        var wrong = new CabinRequest(UUID.randomUUID(), 0, 2, Instant.now(), 2);
        check(f.ledger.admit(new Command.Destination(10, 2, wrong)).kind()
                == CommandLedger.Kind.REJECTED, "Destinatario ajeno.");
        check(f.ledger.admit(destination(10, cabin(2))).kind() == CommandLedger.Kind.ADDED,
                "Destinatario ajeno no reserva ID local.");
        var old = new CabinRequest(UUID.randomUUID(), 1, 3, Instant.now(), 1);
        check(f.ledger.admit(destination(11, old)).kind() == CommandLedger.Kind.REJECTED, "Otra época.");
        check(f.ledger.admit(destination(11, cabin(3))).kind() == CommandLedger.Kind.ADDED,
                "Época ajena no reserva ID vigente.");
        check(f.ledger.admit(new Command.Reset(12, 1, 1)).kind() == CommandLedger.Kind.REJECTED,
                "Reset requiere el canal prioritario, no el registro de servicio.");
        check(f.ledger.admit(destination(12, cabin(4))).kind() == CommandLedger.Kind.ADDED,
                "Reset rechazado no reserva comando de servicio.");
        check(f.ledger.admit(destination(13, cabin(201))).kind() == CommandLedger.Kind.REJECTED,
                "Piso inválido.");
        check(f.ledger.admit(destination(13, cabin(5))).kind() == CommandLedger.Kind.ADDED,
                "Dato inválido no reserva ID.");
    }

    public static void rejectedIdentityDecisionCanBeReplayed() {
        var f = new Fixture(); var original = cabin(1);
        f.ledger.admit(destination(1, original));
        var bad = destination(2, new CabinRequest(original.getId(), 0, 2, original.getCreatedAt(), 1));
        check(f.ledger.admit(bad).kind() == CommandLedger.Kind.REJECTED, "Rechazo por identidad.");
        f.serveCurrent();
        var replay = f.ledger.admit(bad);
        check(replay.replay() && replay.kind() == CommandLedger.Kind.REJECTED,
                "Rechazo estable aunque cambie estado operativo.");
    }

    public static void concurrentFunctionalBurstHasOneCanonical() throws Exception {
        var worker = worker(1);
        try {
            worker.start();
            List<ElevatorWorker.Result> results = burst(worker, 6, 30,
                    i -> destination(i + 1, cabin(2)));
            check(count(results, ElevatorWorker.Outcome.ACCEPTED) == 1, "Una admisión canónica.");
            check(count(results, ElevatorWorker.Outcome.DUPLICATE) == 179, "179 alias reconocidos.");
            check(results.stream().map(r -> r.canonicalRequestId().orElseThrow()).distinct().count() == 1,
                    "Todos comparten UUID canónico.");
            check(worker.snapshot().cabinRequests().size() == 1, "Un solo pendiente durante movimiento.");
            check(worker.snapshot().phase() == ElevatorPhase.MOVING, "Inserción con tramo activo.");
        } finally { close(worker); }
    }

    public static void concurrentExactRetriesExecuteAdmissionOnce() throws Exception {
        var worker = worker(1); var command = destination(1, cabin(2));
        try {
            worker.start();
            List<ElevatorWorker.Result> results = burst(worker, 6, 20, i -> command);
            check(count(results, ElevatorWorker.Outcome.ACCEPTED) == 120, "Cada envío obtiene resultado.");
            check(results.stream().filter(ElevatorWorker.Result::replay).count() == 119,
                    "Una decisión nueva y 119 reproducciones.");
            check(worker.snapshot().cabinRequests().size() == 1, "No repite inserción.");
        } finally { close(worker); }
    }

    public static void concurrentUniqueInsertionPublishesCoherentSnapshots() throws Exception {
        var worker = worker(1);
        var done = new AtomicBoolean(); var started = new CountDownLatch(1);
        var failure = new AtomicReference<Throwable>();
        var reader = new Thread(() -> {
            long revision = -1;
            try {
                do {
                    var snapshot = worker.snapshot();
                    check(snapshot.revision() >= revision, "Revisión publicada no retrocede.");
                    revision = snapshot.revision();
                    var ids = new HashSet<UUID>();
                    snapshot.cabinRequests().forEach(r -> check(ids.add(r.getId()), "Snapshot sin UUID repetido."));
                    check(snapshot.currentFloor() == 1, "No llega antes de un minuto.");
                    if (snapshot.phase() == ElevatorPhase.MOVING) {
                        check(snapshot.nextFloor().orElseThrow() == 2, "Tramo coherente.");
                    }
                    started.countDown();
                    Thread.yield();
                } while (!done.get());
            } catch (Throwable exception) { failure.set(exception); started.countDown(); }
        }, "t26-snapshot-reader");
        try {
            worker.start(); reader.start();
            check(started.await(5, TimeUnit.SECONDS), "Lector iniciado.");
            var results = burst(worker, 4, 25, i -> destination(i + 1, cabin(i + 2)));
            check(count(results, ElevatorWorker.Outcome.ACCEPTED) == 100, "100 inserciones sin pérdida.");
            check(worker.snapshot().cabinRequests().size() == 100, "Agenda completa.");
        } finally {
            done.set(true); reader.join(5_000);
            check(!reader.isAlive(), "Lector terminado."); close(worker);
        }
        if (failure.get() != null) { throw new AssertionError("Publicación incoherente.", failure.get()); }
    }

    public static void concurrentInsertionDuringDwellDoesNotRestartStop() throws Exception {
        var worker = worker(1);
        try {
            worker.start(); accepted(worker, destination(1, cabin(1))); service(worker);
            var before = worker.snapshot();
            var results = burst(worker, 4, 20, i -> destination(i + 100, cabin(i % 10 + 2)));
            check(count(results, ElevatorWorker.Outcome.ACCEPTED) == 10, "Diez claves pendientes distintas.");
            check(count(results, ElevatorWorker.Outcome.DUPLICATE) == 70, "Duplicados durante parada.");
            var after = worker.snapshot();
            check(after.phase() == ElevatorPhase.DWELLING && after.nextFloor().isEmpty(),
                    "No inicia movimiento con parada pendiente.");
            check(after.remainingNanos() <= before.remainingNanos(), "No reinicia duración.");
            check(after.cabinRequests().size() == 10, "Conserva pendientes nuevos.");
        } finally { close(worker); }
    }

    public static void workerReplayAfterServiceAndFreshRequest() throws Exception {
        var worker = worker(1); var command = destination(1, cabin(1));
        try {
            worker.start(); accepted(worker, command); var first = service(worker);
            var replay = send(worker, command);
            check(replay.replay() && replay.requestStatus().orElseThrow() == CommandLedger.RequestStatus.SERVED,
                    "Replay conoce servicio previo.");
            var duplicate = send(worker, destination(2, ((Command.Destination) command).request()));
            check(duplicate.outcome() == ElevatorWorker.Outcome.DUPLICATE, "UUID servido no se reactiva.");
            check(worker.awaitService(Duration.ZERO).isEmpty(), "No hubo otro servicio por retransmitir.");
            var fresh = cabin(1); accepted(worker, destination(3, fresh));
            var next = service(worker);
            check(!next.openedStop() && next.requests().equals(List.of(fresh)),
                    "Solicitud nueva se sirve dentro de la parada abierta.");
            check(!first.requests().get(0).getId().equals(fresh.getId()), "Identidades diferentes.");
            check(worker.snapshot().cabinRequests().isEmpty(), "Ambas solicitudes atendidas.");
        } finally { close(worker); }
    }

    public static void commandIdsAreScopedToEachWorker() throws Exception {
        var first = worker(1); var second = worker(2);
        try {
            first.start(); second.start();
            var a = send(first, destination(1, cabin(2)));
            var bRequest = new CabinRequest(UUID.randomUUID(), 0, 2, Instant.now(), 2);
            var b = send(second, new Command.Destination(1, 2, bRequest));
            check(a.outcome() == ElevatorWorker.Outcome.ACCEPTED
                    && b.outcome() == ElevatorWorker.Outcome.ACCEPTED, "Mismo ID en distintas cabinas.");
            check(!a.canonicalRequestId().equals(b.canonicalRequestId()), "Registros independientes.");
        } finally { close(first); close(second); }
    }

    public static void concurrentCloseAccountsForEveryEnqueuedMessage() throws Exception {
        var worker = worker(1); var go = new CountDownLatch(1); var done = new CountDownLatch(4);
        var queued = new AtomicInteger(1); var error = new AtomicReference<Throwable>();
        var producers = new ArrayList<Thread>();
        try {
            worker.start(); check(worker.submit(destination(999_999, cabin(2))), "Entrada inicial.");
            for (int p = 0; p < 4; p++) {
                final int producer = p;
                var thread = new Thread(() -> {
                    try {
                        check(go.await(5, TimeUnit.SECONDS), "Barrera de cierre.");
                        for (int i = 0; i < 50; i++) {
                            if (worker.submit(destination(producer * 50 + i + 1, cabin(2)))) {
                                queued.incrementAndGet();
                            }
                        }
                    } catch (Throwable exception) { error.compareAndSet(null, exception); }
                    finally { done.countDown(); }
                }, "t26-close-producer-" + p);
                producers.add(thread); thread.start();
            }
            go.countDown(); worker.close();
            check(done.await(5, TimeUnit.SECONDS), "Productores terminados.");
            check(worker.awaitTermination(LIMIT), "Cierre termina hilo real.");
            for (int i = 0; i < queued.get(); i++) { worker.awaitResult(LIMIT).orElseThrow(); }
            check(worker.awaitResult(Duration.ZERO).isEmpty(), "Exactamente un resultado por encolado.");
            check(!worker.submit(destination(999_998, cabin(3))), "Después de cierre no encola.");
            if (error.get() != null) { throw new AssertionError("Productor falló.", error.get()); }
        } finally {
            go.countDown(); for (Thread thread : producers) { thread.join(5_000); }
            close(worker);
        }
    }

    public static void main(String[] args) throws Exception {
        firstAdmissionIsCanonical(); pendingReplayDoesNotMutateState(); servedReplayDoesNotReinsert();
        commandIdConflictPreservesOriginal(); commandReplayPreservesCanonicalMetadata();
        sameUuidWithNewCommandIsDuplicate(); uuidContentCannotChange(); uuidTypeCannotChange();
        functionalDuplicateCreatesAlias(); cabinAndHallDirectionsAreDifferentKeys();
        servedAliasDoesNotAttachToNewRequest(); freshIdentityCanRequestServedFloorAgain();
        invalidRoutingEpochAndResetDoNotReserveId(); rejectedIdentityDecisionCanBeReplayed();
        concurrentFunctionalBurstHasOneCanonical(); concurrentExactRetriesExecuteAdmissionOnce();
        concurrentUniqueInsertionPublishesCoherentSnapshots(); concurrentInsertionDuringDwellDoesNotRestartStop();
        workerReplayAfterServiceAndFreshRequest(); commandIdsAreScopedToEachWorker();
        concurrentCloseAccountsForEveryEnqueuedMessage();
        System.out.println("T26: 21 escenarios satisfactorios.");
    }

    private static final class Fixture {
        private final ElevatorState state = new ElevatorState(1, new SimulationConfig(2, 200, 100, 200), 0);
        private final CommandLedger ledger = new CommandLedger(state);
        private void serveCurrent() { ledger.markServed(state.beginDwell(Direction.UP, 0)); }
    }

    private static List<ElevatorWorker.Result> burst(ElevatorWorker worker, int producers, int count,
            IntFunction<Command> factory) throws Exception {
        var go = new CountDownLatch(1); var done = new CountDownLatch(producers);
        var error = new AtomicReference<Throwable>(); var threads = new ArrayList<Thread>();
        try {
            for (int p = 0; p < producers; p++) {
                final int producer = p;
                var thread = new Thread(() -> {
                    try {
                        check(go.await(5, TimeUnit.SECONDS), "Inicio concurrente.");
                        for (int i = 0; i < count; i++) {
                            check(worker.submit(factory.apply(producer * count + i)), "Mensaje encolado.");
                        }
                    } catch (Throwable exception) { error.compareAndSet(null, exception); }
                    finally { done.countDown(); }
                }, "t26-producer-" + p);
                threads.add(thread); thread.start();
            }
            go.countDown(); check(done.await(5, TimeUnit.SECONDS), "Ráfaga terminada.");
            if (error.get() != null) { throw new AssertionError("Productor falló.", error.get()); }
            var results = new ArrayList<ElevatorWorker.Result>();
            for (int i = 0; i < producers * count; i++) { results.add(worker.awaitResult(LIMIT).orElseThrow()); }
            return List.copyOf(results);
        } finally {
            go.countDown(); for (Thread thread : threads) { thread.join(5_000); }
        }
    }

    private static long count(List<ElevatorWorker.Result> results, ElevatorWorker.Outcome outcome) {
        return results.stream().filter(r -> r.outcome() == outcome).count();
    }

    private static CabinRequest cabin(int floor) {
        return new CabinRequest(UUID.randomUUID(), 0, floor, Instant.now(), 1);
    }

    private static HallRequest hall(int floor, Direction direction) {
        return new HallRequest(UUID.randomUUID(), 0, floor, Instant.now(), direction);
    }

    private static Command destination(long id, CabinRequest request) {
        return new Command.Destination(id, 1, request);
    }

    private static ElevatorWorker worker(int id) {
        return new ElevatorWorker(id, new SimulationConfig(2, 200, 60_000, 60_000), 0);
    }

    private static ElevatorWorker.Result send(ElevatorWorker worker, Command command) throws Exception {
        check(worker.submit(command), "Mensaje encolado.");
        return worker.awaitResult(LIMIT).orElseThrow();
    }

    private static void accepted(ElevatorWorker worker, Command command) throws Exception {
        check(send(worker, command).outcome() == ElevatorWorker.Outcome.ACCEPTED, "Admisión nueva.");
    }

    private static ElevatorWorker.Service service(ElevatorWorker worker) throws Exception {
        return worker.awaitService(LIMIT).orElseThrow(() -> new AssertionError("Falta servicio."));
    }

    private static void close(ElevatorWorker worker) throws Exception {
        worker.close(); check(worker.awaitTermination(LIMIT), "Trabajador terminado.");
    }

    private static void check(boolean condition, String message) {
        if (!condition) { throw new AssertionError(message); }
    }
}
```

## src/test/java/com/elevatormanager/simulation/ExecutionEventScenarios.java

```java
package com.elevatormanager.simulation;

import com.elevatormanager.model.Direction;
import com.elevatormanager.model.ElevatorPhase;
import com.elevatormanager.model.SimulationConfig;
import com.elevatormanager.model.request.CabinRequest;
import com.elevatormanager.model.request.HallRequest;
import com.elevatormanager.protocol.Command;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import static com.elevatormanager.simulation.ExecutionEvent.Type.*;

/** Escenarios con hilo real, plazos acotados y sin dependencias de JUnit. */
public final class ExecutionEventScenarios {
    private static final Duration LIMIT = Duration.ofSeconds(5);
    private ExecutionEventScenarios() { }

    public static void travelHasOrderedAdjacentEvents() throws Exception {
        try (var w = worker(20, 30)) {
            w.start(); send(w, cabin(1, 3));
            var events = through(w, e -> e.type() == STOP_ENDED);
            check(types(events).equals(List.of(COMMAND_ACCEPTED, DEPARTED, ARRIVED,
                    DEPARTED, ARRIVED, STOP_STARTED, COMMAND_COMPLETED, STOP_ENDED)), "Orden de viaje.");
            check(events.get(1).snapshot().currentFloor() == 1
                    && events.get(1).snapshot().nextFloor().orElseThrow() == 2, "Salida adyacente.");
            check(events.get(2).snapshot().currentFloor() == 2
                    && events.get(2).snapshot().phase() == ElevatorPhase.IDLE, "Llegada antes de otro tramo.");
            check(count(events, COMMAND_COMPLETED) == 1, "Pasar por piso 2 no completa.");
            ordered(events);
        }
    }

    public static void approachChangesDirectionBeforeStop() throws Exception {
        try (var w = worker(20, 60_000)) {
            w.start(); var request = new HallRequest(UUID.randomUUID(), 0, 3, Instant.now(), Direction.DOWN);
            send(w, new Command.Pickup(1, 1, request));
            var events = through(w, e -> e.type() == COMMAND_COMPLETED);
            int n = events.size();
            check(events.get(n - 4).type() == ARRIVED && events.get(n - 3).type() == DIRECTION_CHANGED
                    && events.get(n - 2).type() == STOP_STARTED, "Llegada, inversión, parada.");
            check(events.get(n - 3).snapshot().direction() == Direction.DOWN, "Nuevo sentido.");
            check(events.get(n - 1).canonicalRequestId().orElseThrow().equals(request.getId()), "UUID servido.");
        }
    }

    public static void aliasesCompleteEachDistinctCommandOnce() throws Exception {
        try (var w = worker(100, 60_000)) {
            w.start(); var first = cabin(1, 2); var alias = cabin(2, 2);
            send(w, first); send(w, alias);
            var events = completions(w, 2);
            check(count(events, COMMAND_ACCEPTED) == 1 && count(events, COMMAND_DUPLICATE) == 1,
                    "Una admisión funcional.");
            var done = events.stream().filter(e -> e.type() == COMMAND_COMPLETED).toList();
            check(done.get(0).command().orElseThrow().commandId() == 1
                    && done.get(1).command().orElseThrow().commandId() == 2, "Orden de comandos asociados.");
            check(done.stream().allMatch(e -> e.canonicalRequestId().orElseThrow().equals(first.request().getId())),
                    "Alias conservan canónica.");
            check(count(events, STOP_STARTED) == 1 && w.awaitService(LIMIT).orElseThrow().requests().size() == 1,
                    "Una sola atención física.");
        }
    }

    public static void pendingReplayDoesNotCompleteTwice() throws Exception {
        try (var w = worker(100, 60_000)) {
            w.start(); var c = cabin(1, 2); send(w, c); send(w, c);
            var events = completions(w, 1);
            check(count(events, COMMAND_REPLAYED) == 1 && count(events, COMMAND_ACCEPTED) == 1,
                    "Retransmisión diferenciada.");
            w.close(); check(w.awaitTermination(LIMIT), "Cierre.");
            events.addAll(through(w, e -> e.type() == WORKER_TERMINATED));
            check(count(events, COMMAND_COMPLETED) == 1, "Sin finalización repetida.");
        }
    }

    public static void lateDistinctCommandUsesHistoricalService() throws Exception {
        try (var w = worker(20, 60_000)) {
            w.start(); var c = cabin(1, 1); send(w, c); completions(w, 1);
            send(w, new Command.Destination(2, 1, c.request()));
            var events = completions(w, 1);
            check(types(events).equals(List.of(COMMAND_DUPLICATE, COMMAND_COMPLETED)), "Satisfecho históricamente.");
            check(events.get(1).command().orElseThrow().commandId() == 2, "Nuevo comando confirmado.");
            check(w.awaitService(Duration.ZERO).isPresent() && w.awaitService(Duration.ZERO).isEmpty(),
                    "Solo servicio original.");
        }
    }

    public static void servedReplayIsOnlyReplay() throws Exception {
        try (var w = worker(20, 60_000)) {
            w.start(); var c = cabin(1, 1); send(w, c); completions(w, 1); send(w, c);
            check(event(w).type() == COMMAND_REPLAYED, "Respuesta histórica sin otro éxito.");
            w.close(); check(w.awaitTermination(LIMIT), "Cierre.");
            check(count(through(w, e -> e.type() == WORKER_TERMINATED), COMMAND_COMPLETED) == 0,
                    "No vuelve a completar.");
        }
    }

    public static void rejectedCommandHasNoCompletion() throws Exception {
        try (var w = worker(20, 60_000)) {
            w.start(); send(w, new Command.Reset(1, 0, 1));
            var e = event(w);
            check(e.type() == COMMAND_REJECTED && e.command().orElseThrow().commandId() == 1
                    && e.canonicalRequestId().isEmpty() && !e.detail().isBlank(), "Rechazo correlacionable.");
            w.close(); check(w.awaitTermination(LIMIT), "Cierre.");
            check(count(through(w, x -> x.type() == WORKER_TERMINATED), COMMAND_COMPLETED) == 0,
                    "Rechazo no simula atención.");
        }
    }

    public static void conflictingIdDoesNotReplaceCompletion() throws Exception {
        try (var w = worker(100, 60_000)) {
            w.start(); var c = cabin(1, 2); send(w, c); send(w, cabin(1, 3));
            var events = completions(w, 1);
            check(count(events, COMMAND_REJECTED) == 1, "Conflicto rechazado.");
            var done = events.get(events.size() - 1);
            check(done.command().orElseThrow().equals(c) && done.snapshot().currentFloor() == 2,
                    "Original permanece correlacionado.");
        }
    }

    public static void currentFloorDoesNotInventTravel() throws Exception {
        try (var w = worker(20, 60_000)) {
            w.start(); send(w, cabin(1, 1));
            check(types(completions(w, 1)).equals(List.of(COMMAND_ACCEPTED, STOP_STARTED, COMMAND_COMPLETED)),
                    "Atención directa sin salida ni llegada ficticia.");
        }
    }

    public static void joiningDwellDoesNotOpenAnotherStop() throws Exception {
        try (var w = worker(20, 60_000)) {
            w.start(); send(w, cabin(1, 1)); var first = completions(w, 1);
            var r = new HallRequest(UUID.randomUUID(), 0, 1, Instant.now(), Direction.UP);
            send(w, new Command.Pickup(2, 1, r)); var joined = completions(w, 1);
            check(types(joined).equals(List.of(COMMAND_ACCEPTED, COMMAND_COMPLETED)), "Sin nueva apertura.");
            check(joined.get(1).snapshot().remainingNanos() <= first.get(1).snapshot().remainingNanos(),
                    "No reinicia duración.");
        }
    }

    public static void eventsRetainImmutableSnapshots() throws Exception {
        try (var w = worker(20, 60_000)) {
            w.start(); send(w, cabin(1, 2)); var first = event(w); completions(w, 1);
            check(first.snapshot().currentFloor() == 1 && first.snapshot().cabinRequests().size() == 1,
                    "Admisión histórica intacta tras servicio.");
            boolean immutable = false;
            try { first.snapshot().cabinRequests().clear(); }
            catch (UnsupportedOperationException expected) { immutable = true; }
            check(immutable, "Colecciones inmutables.");
        }
    }

    public static void sequencesAreLocalToEachCabin() throws Exception {
        var config = new SimulationConfig(2, 5, 20, 60_000);
        try (var a = new ElevatorWorker(1, config, 0); var b = new ElevatorWorker(2, config, 0)) {
            a.start(); b.start(); send(a, cabin(1, 1));
            send(b, new Command.Destination(1, 2, new CabinRequest(UUID.randomUUID(), 0, 1, Instant.now(), 2)));
            var ea = completions(a, 1); var eb = completions(b, 1); ordered(ea); ordered(eb);
            check(ea.get(0).sequence() == 1 && eb.get(0).sequence() == 1, "Secuencias independientes.");
            check(ea.stream().allMatch(e -> e.elevatorId() == 1 && e.epoch() == 0)
                    && eb.stream().allMatch(e -> e.elevatorId() == 2), "Cabina y época correctas.");
        }
    }

    public static void closeDoesNotCompleteUnservedJourney() throws Exception {
        var w = worker(60_000, 60_000);
        w.start(); send(w, cabin(1, 5)); var events = through(w, e -> e.type() == DEPARTED);
        w.close(); check(w.awaitTermination(LIMIT), "Cierre despierta espera.");
        events.addAll(through(w, e -> e.type() == WORKER_TERMINATED));
        check(count(events, COMMAND_COMPLETED) == 0 && count(events, ARRIVED) == 0,
                "Cierre no inventa llegada ni servicio.");
        ordered(events);
    }

    public static void idleAndTimeoutContract() throws Exception {
        try (var w = worker(20, 20)) {
            w.start(); check(w.awaitEvent(Duration.ZERO).isEmpty(), "Reposo sin eventos artificiales.");
            boolean rejected = false;
            try { w.awaitEvent(Duration.ofNanos(-1)); }
            catch (IllegalArgumentException expected) { rejected = true; }
            check(rejected, "Timeout negativo rechazado.");
        }
    }

    public static void concurrentRetransmissionsHaveOneCompletion() throws Exception {
        try (var w = worker(20, 60_000)) {
            w.start(); var c = cabin(1, 1); var gate = new CountDownLatch(1);
            var error = new AtomicReference<Throwable>(); var threads = new ArrayList<Thread>();
            for (int i = 0; i < 24; i++) {
                var t = new Thread(() -> {
                    try { gate.await(); check(w.submit(c), "Encolado concurrente."); }
                    catch (Throwable e) { error.compareAndSet(null, e); }
                }); threads.add(t); t.start();
            }
            gate.countDown();
            for (var t : threads) { t.join(5000); check(!t.isAlive(), "Productor termina."); }
            if (error.get() != null) { throw new AssertionError(error.get()); }
            for (int i = 0; i < 24; i++) { check(w.awaitResult(LIMIT).isPresent(), "Resultado por envío."); }
            w.close(); check(w.awaitTermination(LIMIT), "Cierre.");
            var events = through(w, e -> e.type() == WORKER_TERMINATED);
            check(count(events, COMMAND_ACCEPTED) == 1 && count(events, COMMAND_REPLAYED) == 23
                    && count(events, COMMAND_COMPLETED) == 1, "Ejecución única con 24 envíos.");
            ordered(events);
        }
    }

    public static void nextJourneyFollowsStopEndAndDirectionChange() throws Exception {
        try (var w = worker(100, 40)) {
            w.start(); send(w, cabin(1, 2)); send(w, cabin(2, 1));
            var events = completions(w, 2);
            int end = types(events).indexOf(STOP_ENDED);
            check(end > 0 && events.get(end + 1).type() == DIRECTION_CHANGED
                    && events.get(end + 2).type() == DEPARTED, "Fin parada, inversión y salida.");
            check(events.get(end + 2).snapshot().direction() == Direction.DOWN, "Regreso DOWN.");
            ordered(events);
        }
    }

    public static void rejectedHistoryHasReplayEvent() throws Exception {
        try (var w = worker(100, 60_000)) {
            w.start(); var first = cabin(1, 2); send(w, first);
            var invalidIdentity = new Command.Destination(2, 1,
                    new CabinRequest(first.request().getId(), 0, 3, first.request().getCreatedAt(), 1));
            send(w, invalidIdentity); send(w, invalidIdentity);
            var events = completions(w, 1);
            check(count(events, COMMAND_REJECTED) == 1 && count(events, COMMAND_REPLAYED) == 1,
                    "Rechazo registrado también se retransmite sin mutación.");
            check(count(events, COMMAND_COMPLETED) == 1, "Solo termina original válido.");
        }
    }

    public static void routingRejectionIdentifiesActualWorker() throws Exception {
        try (var w = worker(20, 20)) {
            w.start(); var wrong = new Command.Destination(1, 2,
                    new CabinRequest(UUID.randomUUID(), 0, 2, Instant.now(), 2));
            send(w, wrong); var e = event(w);
            check(e.type() == COMMAND_REJECTED && e.elevatorId() == 1
                    && e.command().orElseThrow().elevatorId() == 2, "Emisor y destinatario distinguibles.");
        }
    }

    public static void ledgerServiceIsIdempotentForCompletions() {
        var state = new com.elevatormanager.model.ElevatorState(1,
                new SimulationConfig(1, 5, 20, 20), 0);
        var ledger = new CommandLedger(state); var c = cabin(1, 1); ledger.admit(c);
        var served = state.beginDwell(Direction.UP, 0); ledger.markServed(served);
        check(ledger.drainCompletions().size() == 1, "Primera confirmación.");
        ledger.markServed(served);
        check(ledger.drainCompletions().isEmpty(), "Confirmación repetida no recrea finalización.");
        ledger.admit(c);
        check(ledger.drainCompletions().isEmpty(), "Replay tampoco recrea finalización.");
    }

    public static void closeAccountsForEveryQueuedAdmissionEvent() throws Exception {
        var w = worker(60_000, 60_000); w.start();
        int submitted = 0;
        for (int i = 1; i <= 200; i++) { if (w.submit(cabin(i, 5))) { submitted++; } }
        w.close(); check(w.awaitTermination(LIMIT), "Cierre con buzón ocupado.");
        var events = through(w, e -> e.type() == WORKER_TERMINATED);
        long responses = events.stream().filter(e -> e.command().isPresent()).count();
        check(responses == submitted && count(events, COMMAND_COMPLETED) == 0,
                "Todo envío tiene admisión o rechazo al cerrar, sin servicio ficticio.");
        ordered(events);
    }

    public static void main(String[] args) throws Exception {
        travelHasOrderedAdjacentEvents(); approachChangesDirectionBeforeStop();
        aliasesCompleteEachDistinctCommandOnce(); pendingReplayDoesNotCompleteTwice();
        lateDistinctCommandUsesHistoricalService(); servedReplayIsOnlyReplay();
        rejectedCommandHasNoCompletion(); conflictingIdDoesNotReplaceCompletion();
        currentFloorDoesNotInventTravel(); joiningDwellDoesNotOpenAnotherStop();
        eventsRetainImmutableSnapshots(); sequencesAreLocalToEachCabin();
        closeDoesNotCompleteUnservedJourney(); idleAndTimeoutContract();
        concurrentRetransmissionsHaveOneCompletion(); nextJourneyFollowsStopEndAndDirectionChange();
        rejectedHistoryHasReplayEvent(); routingRejectionIdentifiesActualWorker();
        ledgerServiceIsIdempotentForCompletions(); closeAccountsForEveryQueuedAdmissionEvent();
        System.out.println("T27: 20 escenarios satisfactorios.");
    }

    private static ElevatorWorker worker(long move, long dwell) {
        return new ElevatorWorker(1, new SimulationConfig(1, 5, move, dwell), 0);
    }
    private static Command.Destination cabin(long id, int floor) {
        return new Command.Destination(id, 1, new CabinRequest(UUID.randomUUID(), 0, floor, Instant.now(), 1));
    }
    private static void send(ElevatorWorker w, Command c) throws Exception {
        check(w.submit(c), "Encolado."); check(w.awaitResult(LIMIT).isPresent(), "Respuesta.");
    }
    private static ExecutionEvent event(ElevatorWorker w) throws Exception {
        return w.awaitEvent(LIMIT).orElseThrow(() -> new AssertionError("Falta evento."));
    }
    private static ArrayList<ExecutionEvent> through(ElevatorWorker w, Predicate<ExecutionEvent> stop)
            throws Exception {
        var events = new ArrayList<ExecutionEvent>(); long start = System.nanoTime();
        while (System.nanoTime() - start < LIMIT.toNanos()) {
            var e = event(w); events.add(e); if (stop.test(e)) { return events; }
        }
        throw new AssertionError("No apareció el evento esperado.");
    }
    private static ArrayList<ExecutionEvent> completions(ElevatorWorker w, int count) throws Exception {
        int[] remaining = {count};
        return through(w, e -> e.type() == COMMAND_COMPLETED && --remaining[0] == 0);
    }
    private static List<ExecutionEvent.Type> types(List<ExecutionEvent> events) {
        return events.stream().map(ExecutionEvent::type).toList();
    }
    private static long count(List<ExecutionEvent> events, ExecutionEvent.Type type) {
        return events.stream().filter(e -> e.type() == type).count();
    }
    private static void ordered(List<ExecutionEvent> events) {
        for (int i = 1; i < events.size(); i++) {
            var a = events.get(i - 1); var b = events.get(i);
            check(b.sequence() == a.sequence() + 1, "Secuencia consecutiva.");
            check(b.snapshot().revision() >= a.snapshot().revision(), "Revisión no retrocede.");
            check(b.referenceNanos() - a.referenceNanos() >= 0, "Reloj monotónico.");
        }
    }
    private static void check(boolean condition, String message) {
        if (!condition) { throw new AssertionError(message); }
    }
}
```
