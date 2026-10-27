# T36 — Código Java completo

Versión íntegra de las cinco clases de producción modificadas y las dos clases de pruebas añadidas. Las rutas son relativas a la raíz Maven. Los archivos .java originales también están incluidos en el ZIP.

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
                ElevatorSnapshot.Mode.NORMAL,
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
 * T36: ejecución serializada, detención segura y continuación del mismo actor.
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
            inbox.addLast(command);
            available.signal();
            return true;
        } finally {
            lock.unlock();
        }
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
            inbox.addLast(command);
            available.signal();
            return Submission.ENQUEUED;
        } finally { lock.unlock(); }
    }

    /** Control prioritario; confirma tras llegada segura y publicación de eventos. */
    public CompletableFuture<Void> requestStop() {
        lock.lock();
        try {
            if (lifecycle != Lifecycle.RUNNING) {
                return CompletableFuture.failedFuture(new IllegalStateException("Cabina no disponible."));
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
            if (lifecycle != Lifecycle.RUNNING || !stopRequested || !stopped) { return false; }
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
                List<Service> notifications = new ArrayList<>();
                lock.lockInterruptibly();
                try {
                    if (lifecycle != Lifecycle.RUNNING) { break; }
                    long now = System.nanoTime();
                    if (stopRequested) {
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
            lock.lock();
            try {
                lifecycle = failure == null ? Lifecycle.TERMINATED : Lifecycle.FAILED;
                abandoned = new ArrayList<>(inbox);
                inbox.clear();
            } finally {
                lock.unlock();
            }
            for (Command command : abandoned) {
                Result rejected = new Result(command, Outcome.REJECTED, "Trabajador finalizado.");
                emitAdmission(rejected, System.nanoTime());
                results.add(rejected);
            }
            emit(failure == null ? ExecutionEvent.Type.WORKER_TERMINATED
                    : ExecutionEvent.Type.WORKER_FAILED, System.nanoTime());
            flushEvents();
            if (stopConfirmation != null && !stopConfirmation.isDone()) {
                stopConfirmation.completeExceptionally(new IllegalStateException("Cabina terminada antes de confirmar Stop.", failure));
            }
        }
    }

    /** Sección local breve: sin I/O, callbacks ni bloqueos de otras cabinas. */
    private Result admit(Command command) {
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
        COMMAND_COMPLETED, WORKER_TERMINATED, WORKER_FAILED, CONTROL_STOPPED, CONTROL_RESUMED
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
 * T36: actor de llamadas externas con barrera de despacho y continuación.
 * Consume una suscripción propia; mantiene intactos los canales anteriores.
 */
public final class ManagerWorker implements AutoCloseable {
    public enum Lifecycle { NEW, RUNNING, CLOSING, TERMINATED, FAILED }
    public enum Outcome { DISPATCHED, PENDING, DUPLICATE, REJECTED, CANCELLED }

    /** DISPATCHED confirma envío al buzón, nunca admisión ni servicio. */
    public record Result(HallRequest request, Outcome outcome,
            Optional<Command.Pickup> command, String detail, String processedBy) { }

    private final SimulationConfig config;
    private final long epoch;
    private final List<ElevatorWorker> elevators;
    private final LongSupplier commandIds;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition controlChanged = lock.newCondition();
    private boolean stopRequested;
    private boolean stopped;
    private CompletableFuture<Void> stopConfirmation;
    private final ExecutionEventMailbox mailbox = new ExecutionEventMailbox();
    private final HallRequestLedger ledger;
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
            if (lifecycle != Lifecycle.RUNNING) { return false; }
            inbox.addLast(request);
            mailbox.wake();
            return true;
        } finally { lock.unlock(); }
    }

    /** Confirma al salir del despacho en vuelo; conserva inbox, ledger y eventos. */
    public CompletableFuture<Void> requestStop() {
        lock.lock();
        try {
            if (lifecycle != Lifecycle.RUNNING) {
                return CompletableFuture.failedFuture(new IllegalStateException("Manejador no disponible."));
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
            if (lifecycle != Lifecycle.RUNNING || !stopRequested || !stopped) { return false; }
            stopRequested = false;
            stopped = false;
            controlChanged.signalAll();
            return true;
        } finally { lock.unlock(); }
    }

    /** Sin callbacks ni esperas externas bajo el lock local. */
    private boolean awaitDispatchPermission() throws InterruptedException {
        while (true) {
            CompletableFuture<Void> ack;
            lock.lockInterruptibly();
            try {
                if (lifecycle != Lifecycle.RUNNING) { return false; }
                if (!stopRequested) { return true; }
                stopped = true;
                ack = stopConfirmation;
            } finally { lock.unlock(); }
            ack.complete(null);
            lock.lockInterruptibly();
            try {
                // Una continuación seguida inmediatamente de otro Stop es una
                // barrera nueva, aunque el actor no alcanzara a despachar nada.
                if (stopRequested && stopConfirmation == ack && lifecycle == Lifecycle.RUNNING) {
                    controlChanged.await();
                }
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
                    if (stopRequested || lifecycle != Lifecycle.RUNNING) { continue; }
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
            lock.lock();
            try {
                while (!inbox.isEmpty()) {
                    results.add(result(inbox.removeFirst(), Outcome.CANCELLED, Optional.empty(),
                            "Manejador cerrado antes de procesar la entrada."));
                }
                lifecycle = failure == null ? Lifecycle.TERMINATED : Lifecycle.FAILED;
            } finally { lock.unlock(); }
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

/** T36: propietario de una sesión; serializa controles y admisiones fuera del EDT. */
public final class SimulationController implements AutoCloseable {
    public enum Outcome { APPLIED, UNCHANGED, ENQUEUED, REJECTED }

    /** ENQUEUED confirma envío, nunca admisión local ni servicio físico. */
    public record Result(Outcome outcome, SimulationState state, String detail,
            Optional<Command.Destination> command) { }

    private final SimulationConfig config;
    private final long epoch;
    private final List<ElevatorWorker> elevators;
    private final ManagerWorker manager;
    private final ExecutionEventMailbox events = new ExecutionEventMailbox();
    private final AtomicLong commandIds = new AtomicLong();
    private final ExecutorService coordinator = Executors.newSingleThreadExecutor(
            task -> new Thread(task, "simulation-controller"));
    private volatile SimulationState state = SimulationState.RESET;
    private volatile Throwable failure;
    private CompletableFuture<Result> stopCompletion;

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

    public Map<UUID, HallCallSnapshot> calls() { return manager.calls(); }

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
            if (state == SimulationState.STOPPED) {
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
                for (var elevator : elevators) {
                    if (!elevator.start()) { throw new IllegalStateException("Cabina ya iniciada."); }
                }
                if (!manager.start()) { throw new IllegalStateException("Manejador ya iniciado."); }
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
                        sent ? Optional.of(command) : Optional.empty());
            }
            return result(Outcome.REJECTED, "Tipo de solicitud no soportado.");
        });
    }

    /** Suscripción propia de la sesión; un consumidor lógico. ZERO permite sondeo en EDT. */
    public Optional<ExecutionEvent> awaitEvent(Duration timeout) throws InterruptedException {
        long nanos = checkedNanos(timeout);
        return events.await(Duration.ofNanos(nanos));
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
        return new Result(outcome, state, detail, Optional.empty());
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

## src/test/java/com/elevatormanager/simulation/StopContinuationScenarios.java

```java
package com.elevatormanager.simulation;

import com.elevatormanager.dispatch.ManagerWorker;
import com.elevatormanager.dispatch.HallCallSnapshot;
import com.elevatormanager.model.*;
import com.elevatormanager.model.request.*;
import com.elevatormanager.protocol.Command;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.swing.SwingUtilities;
import static com.elevatormanager.simulation.ExecutionEvent.Type.*;
import static com.elevatormanager.simulation.SimulationController.Outcome.*;

/** T36: modelo con reloj explícito y barreras con actores reales, sin sleeps. */
public final class StopContinuationScenarios {
    private static final Duration LIMIT = Duration.ofSeconds(8);
    private StopContinuationScenarios() { }

    public static void modelPreservesDwellRemaining() {
        var s = new ElevatorState(1, new SimulationConfig(1, 5, 10, 200), 0);
        s.addPendingRequest(cabin(1, 1)); s.beginDwell(Direction.UP, 0);
        s.holdDwellForStop(50_000_000L);
        var frozen = s.snapshot(9_000_000_000L);
        check(frozen.phase() == ElevatorPhase.HELD, "Parada congelada");
        check(frozen.remainingNanos() == 150_000_000L, "Restan exactamente 150 ms");
        check(frozen.holdReason().orElseThrow() == ElevatorSnapshot.HoldReason.STOP, "Motivo Stop");
        s.resumeAfterStop(10_000_000_000L);
        check(!s.completeDwellIfDue(10_149_999_999L), "No termina antes");
        check(s.completeDwellIfDue(10_150_000_000L), "Termina consumiendo solo el restante");
    }

    public static void modelDoesNotFreezeMovingLeg() {
        var s = new ElevatorState(1, new SimulationConfig(1, 5, 100, 50), 0);
        s.beginJourney(4, 0); s.holdDwellForStop(40_000_000L);
        check(s.getPhase() == ElevatorPhase.MOVING, "Stop no congela el tramo");
        check(s.completeLegIfDue(100_000_000L) && s.getCurrentFloor() == 2, "Llegada adyacente");
        check(s.getCommittedFloor().orElseThrow() == 4, "Compromiso conservado");
    }

    public static void modelDueDwellDoesNotRestart() {
        var s = new ElevatorState(1, new SimulationConfig(1, 1, 1, 20), 0);
        s.addPendingRequest(cabin(1, 1)); s.beginDwell(Direction.UP, 0);
        s.holdDwellForStop(20_000_000L);
        check(s.snapshot(100_000_000L).remainingNanos() == 0, "Restante agotado válido");
        s.resumeAfterStop(200_000_000L);
        check(s.completeDwellIfDue(200_000_000L), "No reinicia los 20 ms");
    }

    public static void stopInResetDoesNotStartWorkers() throws Exception {
        try (var rig = new Rig(1, 5, 30, 20)) {
            check(get(rig.c.stop()).outcome() == UNCHANGED, "Stop en RESET no-op");
            check(rig.c.state() == SimulationState.RESET, "Sigue RESET");
            check(rig.c.elevatorLifecycles().get(0) == ElevatorWorker.Lifecycle.NEW, "No arrancó");
        }
    }

    public static void idleStopHasGlobalBarrier() throws Exception {
        try (var rig = new Rig(4, 5, 30, 20)) {
            get(rig.c.start()); check(get(rig.c.stop()).state() == SimulationState.STOPPED, "Barrera global");
            check(rig.c.elevatorLifecycles().stream().allMatch(l -> l == ElevatorWorker.Lifecycle.RUNNING), "Hilos vivos y conservados");
            check(rig.c.managerLifecycle() == ManagerWorker.Lifecycle.RUNNING, "Mismo manejador");
            rig.drain();
            check(rig.trace.stream().filter(e -> e.type() == CONTROL_STOPPED).count() == 4, "Confirman cuatro cabinas");
            check(get(rig.c.start()).state() == SimulationState.RUNNING, "Continúa desde STOPPED");
        }
    }

    public static void movementStopsAtNextFloor() throws Exception {
        try (var rig = new Rig(1, 5, 200, 20)) {
            get(rig.c.start()); var r = cabin(1, 4); get(rig.c.submit(r)); rig.until(DEPARTED);
            get(rig.c.stop()); rig.drain();
            var s = rig.c.snapshots().get(0);
            check(s.currentFloor() == 2 && s.phase() == ElevatorPhase.IDLE, "Solo termina el tramo iniciado");
            check(s.committedFloor().orElseThrow() == 4 && s.cabinRequests().contains(r), "Conserva ruta y pendiente");
            check(rig.trace.stream().noneMatch(e -> e.type() == COMMAND_COMPLETED), "Sin servicio durante detención");
            rig.quiet(); get(rig.c.start()); rig.until(COMMAND_COMPLETED);
            check(rig.c.snapshots().get(0).currentFloor() == 4, "Continúa hasta destino original");
        }
    }

    public static void arrivalIsNotService() throws Exception {
        try (var rig = new Rig(1, 3, 200, 20)) {
            get(rig.c.start()); get(rig.c.submit(cabin(1, 2))); rig.until(DEPARTED);
            get(rig.c.stop()); rig.drain();
            check(rig.c.snapshots().get(0).currentFloor() == 2, "Llegó físicamente");
            check(rig.trace.stream().noneMatch(e -> e.type() == STOP_STARTED || e.type() == COMMAND_COMPLETED), "No abre parada nueva");
            get(rig.c.start()); rig.until(COMMAND_COMPLETED);
            check(rig.trace.stream().filter(e -> e.type() == DEPARTED).count() == 1, "No vuelve a viajar al piso actual");
        }
    }

    public static void dwellFreezesAndContinues() throws Exception {
        try (var rig = new Rig(1, 3, 30, 600)) {
            get(rig.c.start()); get(rig.c.submit(cabin(1, 1))); rig.until(COMMAND_COMPLETED);
            get(rig.c.stop()); rig.drain();
            var s = rig.c.snapshots().get(0);
            check(s.phase() == ElevatorPhase.HELD && s.remainingNanos() > 0, "Parada con restante congelado");
            rig.quiet(); check(rig.c.snapshots().get(0).equals(s), "Snapshot no avanza estando detenido");
            get(rig.c.start()); var resumed = rig.until(CONTROL_RESUMED); var end = rig.until(STOP_ENDED);
            check(end.referenceNanos() - resumed.referenceNanos() >= s.remainingNanos(), "Consume restante completo");
            check(rig.trace.stream().filter(e -> e.type() == COMMAND_COMPLETED).count() == 1, "No vuelve a servir");
        }
    }

    public static void repeatedStopIsIdempotent() throws Exception {
        try (var rig = new Rig(1, 5, 180, 20)) {
            get(rig.c.start()); get(rig.c.submit(cabin(1, 4))); rig.until(DEPARTED);
            var a = rig.c.stop(); var b = rig.c.stop();
            check(get(a).state() == SimulationState.STOPPED && get(b).state() == SimulationState.STOPPED, "Comparten barrera");
            check(get(rig.c.stop()).outcome() == UNCHANGED, "Stop ya detenido no-op");
            rig.drain();
            check(rig.trace.stream().filter(e -> e.type() == CONTROL_STOPPED).count() == 1, "Una confirmación física");
        }
    }

    public static void rejectsStartWhileStopping() throws Exception {
        try (var rig = new Rig(1, 5, 500, 20)) {
            get(rig.c.start()); get(rig.c.submit(cabin(1, 4))); rig.until(DEPARTED);
            var stopping = rig.c.stop();
            check(get(rig.c.start()).outcome() == REJECTED, "Start en STOPPING rechazado");
            check(get(stopping).state() == SimulationState.STOPPED, "Termina barrera original");
        }
    }

    public static void rejectsRequestsWhileStopping() throws Exception {
        try (var rig = new Rig(1, 5, 500, 20)) {
            get(rig.c.start()); get(rig.c.submit(cabin(1, 4))); rig.until(DEPARTED);
            var stopping = rig.c.stop();
            check(get(rig.c.submit(hall(2))).outcome() == REJECTED, "Externa en STOPPING rechazada");
            check(get(rig.c.submit(cabin(1, 3))).outcome() == REJECTED, "Interna en STOPPING rechazada");
            get(stopping);
        }
    }

    public static void rejectsRequestsWhileStopped() throws Exception {
        try (var rig = new Rig(1, 5, 30, 20)) {
            get(rig.c.start()); get(rig.c.stop());
            check(get(rig.c.submit(hall(2))).outcome() == REJECTED, "No acepta externa en STOPPED");
            check(get(rig.c.submit(cabin(1, 3))).outcome() == REJECTED, "No acepta interna en STOPPED");
            check(rig.c.calls().isEmpty(), "Sin admisiones residuales");
        }
    }

    public static void cabinInboxSurvivesStop() throws Exception {
        var w = new ElevatorWorker(1, new SimulationConfig(1, 5, 20, 10), 0);
        try {
            w.start(); getVoid(w.requestStop());
            var r = cabin(1, 3); check(w.submit(new Command.Destination(1, 1, r)), "Conserva comando en vuelo");
            check(w.awaitResult(Duration.ofMillis(80)).isEmpty(), "No admite inbox mientras está detenido");
            check(w.resume(), "Mismo trabajador continúa");
            check(w.awaitResult(LIMIT).orElseThrow().outcome() == ElevatorWorker.Outcome.ACCEPTED, "Admisión posterior");
            check(w.awaitService(LIMIT).orElseThrow().requests().contains(r), "Servicio posterior sin pérdida");
        } finally { w.close(); check(w.awaitTermination(LIMIT), "Cabina terminada"); }
    }

    public static void managerInboxSurvivesStop() throws Exception {
        var config = new SimulationConfig(1, 5, 20, 10);
        var w = new ElevatorWorker(1, config, 0);
        var ids = new AtomicLong(); var m = new ManagerWorker(config, 0, List.of(w), ids::incrementAndGet);
        try {
            w.start(); m.start(); getVoid(m.requestStop());
            var r = hall(3); m.submit(r);
            check(m.awaitResult(Duration.ofMillis(80)).isEmpty() && ids.get() == 0, "Sin despacho tras confirmar Stop");
            check(m.resume(), "Continúa manejador");
            check(m.awaitResult(LIMIT).orElseThrow().outcome() == ManagerWorker.Outcome.DISPATCHED, "Conservó inbox");
            awaitServed(m, r.getId());
        } finally { m.close(); w.close(); check(m.awaitTermination(LIMIT) && w.awaitTermination(LIMIT), "Actores terminados"); }
    }

    public static void inFlightDispatchIsCoveredByBarrier() throws Exception {
        var config = new SimulationConfig(1, 5, 20, 10);
        var w = new ElevatorWorker(1, config, 0);
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var m = new ManagerWorker(config, 0, List.of(w), () -> {
            entered.countDown();
            try { check(release.await(8, TimeUnit.SECONDS), "Liberar generador de prueba"); }
            catch (InterruptedException e) { throw new AssertionError(e); }
            return 1;
        });
        try {
            w.start(); getVoid(w.requestStop()); m.start(); var r = hall(3); m.submit(r);
            check(entered.await(8, TimeUnit.SECONDS), "Despacho en vuelo observado");
            var barrier = m.requestStop(); check(!barrier.isDone(), "No confirma mientras sigue el despacho");
            release.countDown(); getVoid(barrier);
            check(m.awaitResult(LIMIT).orElseThrow().outcome() == ManagerWorker.Outcome.DISPATCHED, "Envío previo contabilizado");
            check(w.snapshot().hallRequests().isEmpty(), "Comando espera en inbox detenido");
            check(w.resume() && m.resume(), "Continúan ambos actores");
            awaitServed(m, r.getId());
        } finally { release.countDown(); m.close(); w.close(); check(m.awaitTermination(LIMIT) && w.awaitTermination(LIMIT), "Actores terminados"); }
    }

    public static void severalCabinsAndHallCallsContinue() throws Exception {
        try (var rig = new Rig(3, 6, 80, 20)) {
            get(rig.c.start());
            var requests = List.<Request>of(cabin(1, 5), cabin(2, 4), cabin(3, 6), hall(2), hall(3));
            for (var r : requests) { get(rig.c.submit(r)); }
            get(rig.c.stop()); rig.drain(); rig.quiet();
            check(rig.c.state() == SimulationState.STOPPED && rig.c.epoch() == 0, "Estado y época conservados");
            get(rig.c.start());
            var done = new HashSet<UUID>();
            for (var e : rig.trace) {
                if (e.type() == COMMAND_COMPLETED) { check(done.add(e.canonicalRequestId().orElseThrow()), "Sin servicio duplicado"); }
            }
            while (done.size() < requests.size()) {
                var e = rig.until(COMMAND_COMPLETED);
                check(done.add(e.canonicalRequestId().orElseThrow()), "Cada identidad se sirve una vez");
            }
            check(done.equals(new HashSet<>(requests.stream().map(Request::getId).toList())), "Termina todo el lote");
        }
    }

    public static void repeatedCyclesPreserveCompletedWork() throws Exception {
        try (var rig = new Rig(1, 3, 20, 600)) {
            get(rig.c.start()); get(rig.c.submit(cabin(1, 1))); rig.until(COMMAND_COMPLETED);
            for (int i = 0; i < 10; i++) { get(rig.c.stop()); get(rig.c.start()); }
            rig.until(STOP_ENDED); rig.drain();
            check(rig.trace.stream().filter(e -> e.type() == COMMAND_COMPLETED).count() == 1, "Diez ciclos no duplican servicio");
            long resumed = rig.trace.stream().filter(e -> e.type() == CONTROL_RESUMED).count();
            check(resumed >= 1 && resumed <= 10, "Las continuaciones efectivas usan el mismo actor");
        }
    }

    public static void edtRemainsResponsiveDuringStop() throws Exception {
        try (var rig = new Rig(1, 5, 250, 20)) {
            get(rig.c.start()); get(rig.c.submit(cabin(1, 4))); rig.until(DEPARTED);
            var future = new AtomicReference<CompletableFuture<SimulationController.Result>>();
            var pulse = new CountDownLatch(1);
            SwingUtilities.invokeAndWait(() -> { future.set(rig.c.stop()); SwingUtilities.invokeLater(pulse::countDown); });
            check(pulse.await(2, TimeUnit.SECONDS), "EDT libre mientras termina el tramo");
            check(get(future.get()).state() == SimulationState.STOPPED, "Confirmación asíncrona");
        }
    }

    public static void closeDuringStoppingDoesNotLeaveFuturePending() throws Exception {
        try (var rig = new Rig(1, 5, 500, 20)) {
            get(rig.c.start()); get(rig.c.submit(cabin(1, 4))); rig.until(DEPARTED);
            var f = rig.c.stop(); rig.c.close();
            check(get(f).outcome() == REJECTED, "Cierre invalida Stop pendiente");
            check(rig.c.awaitTermination(LIMIT), "No espera toda la ruta");
            check(rig.c.state() == SimulationState.CLOSED, "Cierre confirmado");
        }
    }

    public static void closeWhileStoppedWakesAllActors() throws Exception {
        try (var rig = new Rig(2, 5, 30, 20)) {
            get(rig.c.start()); get(rig.c.stop()); rig.c.close();
            check(rig.c.awaitTermination(LIMIT), "Despierta manejador y cabinas detenidos");
            check(get(rig.c.start()).outcome() == REJECTED && get(rig.c.stop()).outcome() == REJECTED, "Sesión cerrada no reinicia");
        }
    }

    public static void rapidControlsDoNotLoseWakeups() throws Exception {
        for (boolean dwelling : new boolean[] {false, true}) {
            try (var rig = new Rig(1, 1, 1, 60_000)) {
                get(rig.c.start());
                if (dwelling) {
                    get(rig.c.submit(cabin(1, 1)));
                    rig.until(COMMAND_COMPLETED);
                }
                for (int i = 0; i < 2_000; i++) {
                    check(get(rig.c.stop()).state() == SimulationState.STOPPED, "Barrera rápida " + i);
                    check(get(rig.c.start()).state() == SimulationState.RUNNING, "Continuación rápida " + i);
                }
            }
        }
    }

    private static void awaitServed(ManagerWorker m, UUID id) throws Exception {
        long began = System.nanoTime();
        while (true) {
            long remaining = LIMIT.toNanos() - (System.nanoTime() - began);
            check(remaining > 0, "Plazo de seguimiento");
            var update = m.awaitUpdate(Duration.ofNanos(remaining)).orElseThrow();
            if (update.request().getId().equals(id) && update.status() == HallCallSnapshot.Status.SERVED) { return; }
        }
    }
    private static CabinRequest cabin(int id, int floor) { return new CabinRequest(UUID.randomUUID(), 0, floor, Instant.now(), id); }
    private static HallRequest hall(int floor) { return new HallRequest(UUID.randomUUID(), 0, floor, Instant.now(), Direction.UP); }
    private static SimulationController.Result get(CompletableFuture<SimulationController.Result> f) throws Exception { return f.get(8, TimeUnit.SECONDS); }
    private static void getVoid(CompletableFuture<Void> f) throws Exception { f.get(8, TimeUnit.SECONDS); }
    private static void check(boolean value, String detail) { if (!value) { throw new AssertionError(detail); } }

    private static final class Rig implements AutoCloseable {
        final SimulationController c;
        final List<ExecutionEvent> trace = new ArrayList<>();
        Rig(int n, int floors, long move, long dwell) { c = new SimulationController(new SimulationConfig(n, floors, move, dwell), 0); }
        ExecutionEvent until(ExecutionEvent.Type type) throws Exception {
            long began = System.nanoTime();
            while (true) {
                long left = LIMIT.toNanos() - (System.nanoTime() - began);
                check(left > 0, "Plazo de evento " + type);
                var e = c.awaitEvent(Duration.ofNanos(left)).orElseThrow(); trace.add(e);
                check(e.type() != WORKER_FAILED, "Cabina sin fallo");
                if (e.type() == type) { return e; }
            }
        }
        void drain() throws Exception {
            while (true) { var e = c.awaitEvent(Duration.ZERO); if (e.isEmpty()) { return; } trace.add(e.orElseThrow()); }
        }
        void quiet() throws Exception { check(c.awaitEvent(Duration.ofMillis(80)).isEmpty(), "Sin avance tras barrera Stop"); }
        @Override public void close() throws Exception {
            c.close(); check(c.awaitTermination(LIMIT), "Sesión terminada");
            check(c.failure().isEmpty(), "Coordinador sin fallos");
            check(c.elevatorLifecycles().stream().allMatch(l -> l == ElevatorWorker.Lifecycle.TERMINATED), "Cabinas sin fallos");
            check(c.managerLifecycle() == ManagerWorker.Lifecycle.TERMINATED, "Manejador sin fallo");
        }
    }

    public static void main(String[] args) throws Exception {
        modelPreservesDwellRemaining(); modelDoesNotFreezeMovingLeg(); modelDueDwellDoesNotRestart();
        stopInResetDoesNotStartWorkers(); idleStopHasGlobalBarrier(); movementStopsAtNextFloor();
        arrivalIsNotService(); dwellFreezesAndContinues(); repeatedStopIsIdempotent();
        rejectsStartWhileStopping(); rejectsRequestsWhileStopping(); rejectsRequestsWhileStopped();
        cabinInboxSurvivesStop(); managerInboxSurvivesStop(); inFlightDispatchIsCoveredByBarrier();
        severalCabinsAndHallCallsContinue(); repeatedCyclesPreserveCompletedWork();
        edtRemainsResponsiveDuringStop(); closeDuringStoppingDoesNotLeaveFuturePending(); closeWhileStoppedWakesAllActors();
        rapidControlsDoNotLoseWakeups();
        System.out.println("T36: 21 escenarios satisfactorios.");
    }
}
```

## src/test/java/com/elevatormanager/simulation/StopContinuationTest.java

```java
package com.elevatormanager.simulation;

import org.junit.jupiter.api.Test;

class StopContinuationTest {
    @Test void modelPreservesDwellRemaining() throws Exception {
        StopContinuationScenarios.modelPreservesDwellRemaining();
    }
    @Test void modelDoesNotFreezeMovingLeg() throws Exception {
        StopContinuationScenarios.modelDoesNotFreezeMovingLeg();
    }
    @Test void modelDueDwellDoesNotRestart() throws Exception {
        StopContinuationScenarios.modelDueDwellDoesNotRestart();
    }
    @Test void stopInResetDoesNotStartWorkers() throws Exception {
        StopContinuationScenarios.stopInResetDoesNotStartWorkers();
    }
    @Test void idleStopHasGlobalBarrier() throws Exception {
        StopContinuationScenarios.idleStopHasGlobalBarrier();
    }
    @Test void movementStopsAtNextFloor() throws Exception {
        StopContinuationScenarios.movementStopsAtNextFloor();
    }
    @Test void arrivalIsNotService() throws Exception {
        StopContinuationScenarios.arrivalIsNotService();
    }
    @Test void dwellFreezesAndContinues() throws Exception {
        StopContinuationScenarios.dwellFreezesAndContinues();
    }
    @Test void repeatedStopIsIdempotent() throws Exception {
        StopContinuationScenarios.repeatedStopIsIdempotent();
    }
    @Test void rejectsStartWhileStopping() throws Exception {
        StopContinuationScenarios.rejectsStartWhileStopping();
    }
    @Test void rejectsRequestsWhileStopping() throws Exception {
        StopContinuationScenarios.rejectsRequestsWhileStopping();
    }
    @Test void rejectsRequestsWhileStopped() throws Exception {
        StopContinuationScenarios.rejectsRequestsWhileStopped();
    }
    @Test void cabinInboxSurvivesStop() throws Exception {
        StopContinuationScenarios.cabinInboxSurvivesStop();
    }
    @Test void managerInboxSurvivesStop() throws Exception {
        StopContinuationScenarios.managerInboxSurvivesStop();
    }
    @Test void inFlightDispatchIsCoveredByBarrier() throws Exception {
        StopContinuationScenarios.inFlightDispatchIsCoveredByBarrier();
    }
    @Test void severalCabinsAndHallCallsContinue() throws Exception {
        StopContinuationScenarios.severalCabinsAndHallCallsContinue();
    }
    @Test void repeatedCyclesPreserveCompletedWork() throws Exception {
        StopContinuationScenarios.repeatedCyclesPreserveCompletedWork();
    }
    @Test void edtRemainsResponsiveDuringStop() throws Exception {
        StopContinuationScenarios.edtRemainsResponsiveDuringStop();
    }
    @Test void closeDuringStoppingDoesNotLeaveFuturePending() throws Exception {
        StopContinuationScenarios.closeDuringStoppingDoesNotLeaveFuturePending();
    }
    @Test void closeWhileStoppedWakesAllActors() throws Exception {
        StopContinuationScenarios.closeWhileStoppedWakesAllActors();
    }
    @Test void rapidControlsDoNotLoseWakeups() throws Exception {
        StopContinuationScenarios.rapidControlsDoNotLoseWakeups();
    }
}
```
