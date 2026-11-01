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
