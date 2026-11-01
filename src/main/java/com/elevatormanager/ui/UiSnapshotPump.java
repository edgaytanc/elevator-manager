package com.elevatormanager.ui;

import com.elevatormanager.dispatch.HallCallSnapshot;
import com.elevatormanager.model.ElevatorSnapshot;
import com.elevatormanager.model.SimulationState;
import com.elevatormanager.simulation.SimulationController;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import javax.swing.SwingUtilities;

/** T52: captura fuera del EDT, una tarea en curso y una sola publicación reemplazable. */
public final class UiSnapshotPump {
    public static final long INTERVAL_NANOS = 100_000_000L;
    public record Frame(UUID sessionId, long epoch, List<ElevatorSnapshot> snapshots,
            Map<Integer, List<HallCallSnapshot>> assigned, Optional<Timing> timing) {
        /** Compatibilidad de capturas sin muestra temporal: solo progreso ya publicado. */
        public Frame(UUID sessionId, long epoch, List<ElevatorSnapshot> snapshots,
                Map<Integer, List<HallCallSnapshot>> assigned) {
            this(sessionId, epoch, snapshots, assigned, Optional.empty());
        }
        public Frame {
            Objects.requireNonNull(timing, "timing");
            Objects.requireNonNull(sessionId, "sessionId"); snapshots = List.copyOf(snapshots);
            var copy = new HashMap<Integer, List<HallCallSnapshot>>();
            assigned.forEach((id, calls) -> copy.put(id, List.copyOf(calls)));
            assigned = Map.copyOf(copy);
        }
    }
    /** Ancla entre relojes distintos; wallNanos nunca se resta de referenceNanos. */
    public record Timing(long activeNanos, long wallNanos, boolean advancing) {
        public long at(long currentWallNanos) {
            return activeNanos + (advancing ? Math.max(0L, currentWallNanos - wallNanos) : 0L);
        }
    }
    private final Supplier<Frame> capture;
    private final RefreshGate gate;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(task -> new Thread(task, "ui-snapshot-capture"));
    private final AtomicReference<Frame> latest = new AtomicReference<>();
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private boolean inFlight;
    private boolean closed;
    private CompletableFuture<Void> termination;

    public UiSnapshotPump(SimulationController controller) { this(() -> capture(controller), System::nanoTime); }
    UiSnapshotPump(Supplier<Frame> capture, LongSupplier clock) {
        this.capture = Objects.requireNonNull(capture, "capture");
        gate = new RefreshGate(INTERVAL_NANOS, clock);
    }

    public synchronized void request() {
        if (closed || inFlight || !gate.due()) return;
        inFlight = true;
        executor.execute(() -> {
            try {
                if (SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Captura en EDT.");
                Frame frame = capture.get();
                synchronized (this) {
                    if (!closed && frame != null) { latest.set(frame); failure.set(null); }
                }
            } catch (RuntimeException | Error error) { failure.set(error); }
            finally { synchronized (this) { inFlight = false; } }
        });
    }

    public Optional<Frame> latest() { return Optional.ofNullable(latest.get()); }
    public Optional<Throwable> failure() { return Optional.ofNullable(failure.get()); }

    static Frame capture(SimulationController controller) {
        if (SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Agrupar snapshots fuera del EDT.");
        long epoch = controller.epoch();
        var snapshots = controller.snapshots();
        var assigned = new HashMap<Integer, List<HallCallSnapshot>>();
        controller.calls().values().forEach(call -> {
            if (call.status() == HallCallSnapshot.Status.DISPATCHED || call.status() == HallCallSnapshot.Status.ASSIGNED
                    || call.status() == HallCallSnapshot.Status.UNCONFIRMED) {
                call.command().ifPresent(command -> assigned.computeIfAbsent(command.elevatorId(), id -> new ArrayList<>()).add(call));
            }
        });
        // La lectura del reloj y cualquier lock del motor permanecen fuera del EDT.
        SimulationState state = controller.state();
        long active = controller.activeTimeNanos();
        long wall = System.nanoTime();
        boolean advancing = state == SimulationState.RUNNING || state == SimulationState.STOPPING
                || state == SimulationState.RESETTING;
        var timing = new Timing(active, wall, advancing);
        // No es una captura atómica global. Descarta también transiciones durante el muestreo.
        return epoch == controller.epoch() && state == controller.state()
                ? new Frame(controller.sessionId(), epoch, snapshots, assigned, Optional.of(timing)) : null;
    }

    /** No espera al trabajador en el llamador; una captura tardía no vuelve a publicarse. */
    public synchronized CompletableFuture<Void> closeAsync() {
        if (termination != null) return termination.copy();
        closed = true; latest.set(null); executor.shutdownNow();
        termination = CompletableFuture.runAsync(() -> {
            try { while (!executor.awaitTermination(1, TimeUnit.SECONDS)) { /* Fuera del EDT. */ } }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new CompletionException(error); }
        });
        return termination.copy();
    }
}
