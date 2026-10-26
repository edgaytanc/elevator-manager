package com.elevatormanager.dispatch;

import com.elevatormanager.model.ElevatorPhase;
import com.elevatormanager.model.ElevatorSnapshot;
import com.elevatormanager.model.SimulationConfig;
import com.elevatormanager.model.request.HallRequest;
import com.elevatormanager.protocol.Command;
import com.elevatormanager.simulation.ElevatorWorker;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;
import javax.swing.SwingUtilities;

/**
 * T29: actor de llamadas externas. Solo este hilo decide y envía Pickup.
 * El reparto circular es provisional hasta T30/T31; no estima costos.
 * No consume resultados, servicios ni eventos de las cabinas (T32).
 */
public final class ManagerWorker implements AutoCloseable {
    public enum Lifecycle { NEW, RUNNING, CLOSING, TERMINATED, FAILED }
    public enum Outcome { DISPATCHED, REJECTED, CANCELLED }

    /** DISPATCHED confirma envío al buzón, nunca admisión ni servicio. */
    public record Result(HallRequest request, Outcome outcome,
            Optional<Command.Pickup> command, String detail, String processedBy) { }

    private final SimulationConfig config;
    private final long epoch;
    private final List<ElevatorWorker> elevators;
    private final LongSupplier commandIds;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition available = lock.newCondition();
    private final ArrayDeque<HallRequest> inbox = new ArrayDeque<>();
    private final BlockingQueue<Result> results = new LinkedBlockingQueue<>();
    private final Thread thread;
    private volatile Lifecycle lifecycle = Lifecycle.NEW;
    private volatile Throwable failure;
    private int nextIndex;

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
            try { thread.start(); }
            catch (RuntimeException | Error exception) {
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
            available.signal();
            return true;
        } finally { lock.unlock(); }
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
                available.signalAll();
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

    private void runLoop() {
        HallRequest inFlight = null;
        try {
            while (true) {
                lock.lockInterruptibly();
                try {
                    while (lifecycle == Lifecycle.RUNNING && inbox.isEmpty()) {
                        available.await();
                    }
                    if (lifecycle != Lifecycle.RUNNING) { break; }
                    inFlight = inbox.removeFirst();
                } finally { lock.unlock(); }
                // Nunca conservar el lock propio al invocar a otra cabina.
                results.add(dispatch(inFlight));
                inFlight = null;
            }
        } catch (InterruptedException exception) {
            failure = exception;
            Thread.currentThread().interrupt();
        } catch (RuntimeException | Error exception) {
            failure = exception;
        } finally {
            if (inFlight != null) {
                results.add(result(inFlight, Outcome.CANCELLED, Optional.empty(),
                        "Fallo durante el despacho; comprobar el historial de la cabina."));
            }
            lock.lock();
            try {
                while (!inbox.isEmpty()) {
                    results.add(result(inbox.removeFirst(), Outcome.CANCELLED, Optional.empty(),
                            "Manejador cerrado antes del despacho."));
                }
                lifecycle = failure == null ? Lifecycle.TERMINATED : Lifecycle.FAILED;
            } finally { lock.unlock(); }
        }
    }

    private Result dispatch(HallRequest request) {
        if (request.getEpoch() != epoch) {
            return result(request, Outcome.REJECTED, Optional.empty(), "Época ajena.");
        }
        var validation = request.validateAgainst(config);
        if (!validation.isValid()) {
            return result(request, Outcome.REJECTED, Optional.empty(), validation.errors().toString());
        }
        for (int offset = 0; offset < elevators.size(); offset++) {
            int index = (int) (((long) nextIndex + offset) % elevators.size());
            var elevator = elevators.get(index);
            var snapshot = elevator.snapshot();
            if (elevator.lifecycle() != ElevatorWorker.Lifecycle.RUNNING
                    || !isAvailable(snapshot)) { continue; }
            long id = commandIds.getAsLong();
            if (id < 1) { throw new IllegalStateException("Generador de comandos agotado o inválido."); }
            var command = new Command.Pickup(id, snapshot.id(), request);
            if (elevator.submit(command)) {
                nextIndex = (index + 1) % elevators.size();
                return result(request, Outcome.DISPATCHED, Optional.of(command),
                        "Enviado al buzón; admisión y servicio se confirman por separado.");
            }
        }
        return result(request, Outcome.REJECTED, Optional.empty(),
                "No hay cabinas disponibles; el llamador conserva la solicitud para reintentar.");
    }

    private boolean isAvailable(ElevatorSnapshot snapshot) {
        return snapshot.epoch() == epoch && snapshot.mode() == ElevatorSnapshot.Mode.NORMAL
                && (snapshot.phase() == ElevatorPhase.IDLE
                || snapshot.phase() == ElevatorPhase.MOVING
                || snapshot.phase() == ElevatorPhase.DWELLING);
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
