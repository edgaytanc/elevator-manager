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
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * T26: admisión concurrente serializada y deduplicación de comandos/solicitudes.
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
    private final AtomicReference<ElevatorSnapshot> published;
    private final Thread thread;
    private volatile Lifecycle lifecycle = Lifecycle.NEW;
    private volatile Throwable failure;
    private Optional<Direction> completedSweep = Optional.empty();

    public ElevatorWorker(int id, SimulationConfig config, long epoch) {
        state = new ElevatorState(id, config, epoch);
        ledger = new CommandLedger(state);
        published = new AtomicReference<>(state.snapshot(System.nanoTime()));
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

    public ElevatorSnapshot snapshot() { return published.get(); }
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
                List<Service> notifications = new ArrayList<>();
                lock.lockInterruptibly();
                try {
                    if (lifecycle != Lifecycle.RUNNING) { break; }
                    long now = System.nanoTime();
                    if (state.getPhase() == ElevatorPhase.MOVING
                            && state.completeLegIfDue(now)) {
                        if (state.getCommittedFloor().isEmpty()) {
                            completedSweep = Optional.of(state.getDirection());
                        }
                        published.set(state.snapshot(now));
                        continueWork(now, notifications);
                    } else if (state.getPhase() == ElevatorPhase.DWELLING
                            && state.completeDwellIfDue(now)) {
                        published.set(state.snapshot(now));
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
                results.add(new Result(command, Outcome.REJECTED, "Trabajador finalizado."));
            }
        }
    }

    /** Sección local breve: sin I/O, callbacks ni bloqueos de otras cabinas. */
    private Result admit(Command command) {
        CommandLedger.Decision decision = ledger.admit(command);
        published.set(state.snapshot(System.nanoTime()));
        Outcome outcome = switch (decision.kind()) {
            case ADDED -> Outcome.ACCEPTED;
            case DUPLICATE -> Outcome.DUPLICATE;
            case REJECTED -> Outcome.REJECTED;
        };
        return new Result(command, outcome, decision.detail(), decision.canonicalRequestId(),
                decision.requestStatus(), decision.replay());
    }

    /** Transiciones locales: sin callbacks ni entrega de eventos bajo el lock. */
    private void continueWork(long now, List<Service> notifications) {
        if (state.getPhase() == ElevatorPhase.MOVING) { return; }
        if (state.getPhase() == ElevatorPhase.DWELLING) {
            List<Request> served = state.serveDuringDwell(now);
            if (!served.isEmpty()) { recordService(served, false, now, notifications); }
            return;
        }
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
                recordService(served, true, now, notifications);
                return;
            }
            var target = DirectionalAgenda.selectTarget(state.getCurrentFloor(),
                    state.getPendingRequestsInAdmissionOrder(), completedSweep);
            if (target.isPresent()) { state.beginJourney(target.getAsInt(), now); }
            completedSweep = Optional.empty();
        }
        published.set(state.snapshot(now));
    }

    private void recordService(List<Request> served, boolean openedStop, long now,
            List<Service> notifications) {
        ledger.markServed(served);
        published.set(state.snapshot(now));
        notifications.add(new Service(state.getId(), state.getEpoch(), state.getRevision(),
                state.getCurrentFloor(), state.getDirection(), now, openedStop, served));
    }

    private static long timeoutNanos(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative()) { throw new IllegalArgumentException("Tiempo negativo."); }
        return timeout.toNanos();
    }
}
