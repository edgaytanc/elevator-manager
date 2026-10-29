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

    private final TraceMailbox trace;
    private final PauseCoordinator pause; // null: actor independiente, nanoTime directo.
    private boolean pauseView;
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
                        () -> publishPauseView(true), () -> publishPauseView(false))) { break; }
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
                emitAdmission(rejected, nowNanos());
                results.add(rejected);
            }
            for (ResetTicket ticket : abandonedResets) {
                Result rejected = new Result(ticket.command(), Outcome.REJECTED, "Cabina finalizada antes de procesar reset.");
                emitAdmission(rejected, nowNanos());
                results.add(rejected);
            }
            emit(failure == null ? ExecutionEvent.Type.WORKER_TERMINATED
                    : ExecutionEvent.Type.WORKER_FAILED, nowNanos());
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
            emitAdmission(rejected, nowNanos());
            return rejected;
        }
        CommandLedger.Decision decision = ledger.admit(command);
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
            trace.cabin(event, failure);
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
