package com.elevatormanager.simulation;

import com.elevatormanager.model.ElevatorSnapshot;
import com.elevatormanager.model.ElevatorPhase;
import com.elevatormanager.model.ElevatorState;
import com.elevatormanager.model.SimulationConfig;
import com.elevatormanager.protocol.Command;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * T23: un hilo y un buzón por cabina, con desplazamiento por tramos.
 * Al alcanzar el primer objetivo espera el servicio que incorporará T24.
 * No expone el estado mutable ni ejecuta callbacks de consumidores.
 */
public final class ElevatorWorker implements AutoCloseable {
    public enum Lifecycle { NEW, RUNNING, CLOSING, TERMINATED, FAILED }
    public enum Outcome { ACCEPTED, REJECTED }

    /** Aceptación en agenda, nunca confirmación de llegada o servicio. */
    public record Result(Command command, Outcome outcome, String detail) { }

    private final ElevatorState state;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition available = lock.newCondition();
    private final ArrayDeque<Command> inbox = new ArrayDeque<>();
    private final BlockingQueue<Result> results = new LinkedBlockingQueue<>();
    private final AtomicReference<ElevatorSnapshot> published;
    private final Thread thread;
    private volatile Lifecycle lifecycle = Lifecycle.NEW;
    private volatile Throwable failure;
    private boolean awaitingService;

    public ElevatorWorker(int id, SimulationConfig config, long epoch) {
        state = new ElevatorState(id, config, epoch);
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
                lock.lockInterruptibly();
                try {
                    if (lifecycle != Lifecycle.RUNNING) { break; }
                    long now = System.nanoTime();
                    if (state.getPhase() == ElevatorPhase.MOVING
                            && state.completeLegIfDue(now)) {
                        published.set(state.snapshot(now));
                        if (state.getCommittedFloor().isPresent()) {
                            state.beginNextLeg(now);
                            published.set(state.snapshot(now));
                        } else {
                            awaitingService = true;
                        }
                    } else if (!inbox.isEmpty()) {
                        result = admit(inbox.removeFirst());
                    } else if (state.getPhase() == ElevatorPhase.MOVING) {
                        long remaining = state.movementRemainingNanos(System.nanoTime());
                        if (remaining > 0) { available.awaitNanos(remaining); }
                    } else {
                        available.await();
                    }
                } finally {
                    lock.unlock();
                }
                if (result != null) { results.add(result); }
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
        try {
            if (command.elevatorId() != state.getId()) {
                throw new IllegalArgumentException("Destinatario distinto de esta cabina.");
            }
            if (command instanceof Command.Reset) {
                throw new IllegalArgumentException("Reset operativo pendiente de fase 6.");
            }
            if (command.epoch() != state.getEpoch()) {
                throw new IllegalArgumentException("Época distinta de la vigente.");
            }
            if (!command.validateAgainst(state.getConfig()).isValid()) {
                throw new IllegalArgumentException("Comando inválido para este edificio.");
            }
            boolean added;
            if (command instanceof Command.Pickup pickup) {
                added = state.addPendingRequest(pickup.request());
            } else if (command instanceof Command.Destination destination) {
                added = state.addPendingRequest(destination.request());
            } else {
                throw new IllegalArgumentException("Comando no soportado.");
            }
            published.set(state.snapshot(System.nanoTime()));
            if (added && !awaitingService && state.getPhase() == ElevatorPhase.IDLE
                    && state.getCommittedFloor().isEmpty()) {
                int target = command instanceof Command.Pickup pickup
                        ? pickup.request().getFloor()
                        : ((Command.Destination) command).request().getFloor();
                if (!state.beginJourney(target, System.nanoTime())) {
                    awaitingService = true;
                }
                published.set(state.snapshot(System.nanoTime()));
            }
            return new Result(command, Outcome.ACCEPTED,
                    added ? "Incorporado a la agenda." : "Identidad ya pendiente.");
        } catch (IllegalArgumentException exception) {
            return new Result(command, Outcome.REJECTED, exception.getMessage());
        }
    }

    private static long timeoutNanos(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative()) { throw new IllegalArgumentException("Tiempo negativo."); }
        return timeout.toNanos();
    }
}
