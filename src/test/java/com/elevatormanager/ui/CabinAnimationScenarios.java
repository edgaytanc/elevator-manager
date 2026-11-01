package com.elevatormanager.ui;

import com.elevatormanager.model.*;
import com.elevatormanager.model.request.CabinRequest;
import com.elevatormanager.simulation.SimulationController;
import java.awt.image.BufferedImage;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import javax.swing.SwingUtilities;

/** T59: reloj virtual para oráculos exactos y actores reales para verificar la frontera temporal. */
public final class CabinAnimationScenarios {
    private static final long MS = 1_000_000L;
    private static final long ACTIVE = 4_000 * MS;
    private static final long WALL = -9_000_000 * MS;
    private static final UUID SESSION = UUID.randomUUID();
    private static final SimulationConfig CONFIG = new SimulationConfig(2, 6, 1000, 100);
    private CabinAnimationScenarios() { }

    public static void upwardStartMiddleAndEnd() {
        var s = moving(1, 2, Direction.UP, ACTIVE, 1000 * MS);
        near(CabinMotion.position(s, ACTIVE), 2);
        near(CabinMotion.position(s, ACTIVE + 500 * MS), 2.5);
        near(CabinMotion.position(s, ACTIVE + 1000 * MS), 3);
    }
    public static void downwardInterpolation() {
        var s = moving(1, 5, Direction.DOWN, ACTIVE, 1000 * MS);
        near(CabinMotion.position(s, ACTIVE + 250 * MS), 4.75);
        near(CabinMotion.position(s, ACTIVE + 750 * MS), 4.25);
    }
    public static void clampsToPublishedLeg() {
        var s = moving(1, 2, Direction.UP, ACTIVE, 1000 * MS);
        near(CabinMotion.position(s, ACTIVE - 50 * MS), 2);
        near(CabinMotion.position(s, ACTIVE + 9000 * MS), 3);
    }
    public static void addsElapsedToCapturedProgress() {
        var s = moving(1, 2, Direction.UP, ACTIVE, 600 * MS);
        near(CabinMotion.position(s, ACTIVE), 2.4);
        near(CabinMotion.position(s, ACTIVE + 100 * MS), 2.5);
        near(CabinMotion.position(s, ACTIVE + 700 * MS), 3);
    }
    public static void heldMovementUsesOnlyCapturedProgress() {
        var s = moving(1, 2, Direction.UP, ACTIVE, 600 * MS);
        var held = new ElevatorSnapshot(s.id(), s.config(), s.epoch(), s.revision(), s.currentFloor(), s.nextFloor(),
                s.direction(), ElevatorPhase.HELD, Optional.of(ElevatorPhase.MOVING),
                Optional.of(ElevatorSnapshot.HoldReason.PAUSE), s.mode(), s.phaseDurationNanos(),
                s.remainingNanos(), s.referenceNanos(), s.committedFloor(), List.of(), List.of());
        near(CabinMotion.position(held, ACTIVE + 60_000 * MS), 2.4);
    }
    public static void idleAndDwellRemainAtLogicalFloor() {
        var idle = idle(1, 4);
        near(CabinMotion.position(idle, ACTIVE + 1000 * MS), 4);
        var dwell = new ElevatorSnapshot(1, CONFIG, 0, 1, 4, OptionalInt.empty(), Direction.UP,
                ElevatorPhase.DWELLING, Optional.empty(), Optional.empty(), ElevatorSnapshot.Mode.NORMAL,
                100 * MS, 80 * MS, ACTIVE, OptionalInt.empty(), List.of(), List.of());
        near(CabinMotion.position(dwell, ACTIVE + 5000 * MS), 4);
    }
    public static void unrelatedClockOriginsAreAnchored() throws Exception { edt(() -> {
        var clock = new AtomicLong(WALL + 125 * MS); var p = panel(clock);
        p.render(frame(moving(1, 2, Direction.UP, ACTIVE, 1000 * MS),
                new UiSnapshotPump.Timing(ACTIVE + 250 * MS, WALL, true)), 0);
        near(p.displayedPosition(1), 2.375);
    }); }
    public static void repeatedFrameDoesNotRestartMotion() throws Exception { edt(() -> {
        var clock = new AtomicLong(WALL); var p = panel(clock);
        var f = frame(moving(1, 2, Direction.UP, ACTIVE, 1000 * MS), timing()); p.render(f, 0);
        clock.addAndGet(250 * MS); p.render(f, 0); near(p.displayedPosition(1), 2.25);
        clock.addAndGet(250 * MS); p.render(f, 0); near(p.displayedPosition(1), 2.5);
    }); }
    public static void freshFrameOfSameLegDoesNotRestartMotion() throws Exception { edt(() -> {
        var clock = new AtomicLong(WALL + 250 * MS); var p = panel(clock);
        var snapshot = moving(1, 2, Direction.UP, ACTIVE, 1000 * MS);
        p.render(frame(snapshot, timing()), 0); near(p.displayedPosition(1), 2.25);
        p.render(frame(snapshot, new UiSnapshotPump.Timing(ACTIVE + 250 * MS, WALL + 250 * MS, true)), 0);
        clock.addAndGet(250 * MS); near(p.displayedPosition(1), 2.5);
    }); }
    public static void delayedDeliveryUsesCaptureAnchor() throws Exception { edt(() -> {
        var clock = new AtomicLong(WALL + 650 * MS); var p = panel(clock);
        p.render(frame(moving(1, 2, Direction.UP, ACTIVE, 1000 * MS), timing()), 0);
        near(p.displayedPosition(1), 2.65);
    }); }
    public static void waitsAtEndpointUntilModelPublishesArrival() throws Exception { edt(() -> {
        var clock = new AtomicLong(WALL + 8000 * MS); var p = panel(clock);
        var s = moving(1, 2, Direction.UP, ACTIVE, 1000 * MS);
        p.render(frame(s, timing()), 0);
        near(p.displayedPosition(1), 3); check(p.displayedFloor(1) == 2, "Llegada visual no confirma piso lógico");
        p.render(frame(idle(1, 3), timing()), 0);
        near(p.displayedPosition(1), 3); check(p.displayedFloor(1) == 3, "Solo el modelo confirma llegada");
    }); }
    public static void untimedFramesUseCapturedProgress() throws Exception { edt(() -> {
        var clock = new AtomicLong(WALL); var p = panel(clock);
        var s = moving(1, 2, Direction.UP, ACTIVE, 600 * MS);
        p.render(new UiSnapshotPump.Frame(SESSION, 0, List.of(s, idle(2, 1)), Map.of()), 0);
        clock.addAndGet(9000 * MS); near(p.displayedPosition(1), 2.4);
    }); }
    public static void nonAdvancingSampleDoesNotCountWallTime() throws Exception { edt(() -> {
        var clock = new AtomicLong(WALL + 9000 * MS); var p = panel(clock);
        p.render(frame(moving(1, 2, Direction.UP, ACTIVE, 1000 * MS),
                new UiSnapshotPump.Timing(ACTIVE + 350 * MS, WALL, false)), 0);
        near(p.displayedPosition(1), 2.35);
    }); }
    public static void clockWrapAndLargeDurationRemainBounded() {
        var t = new UiSnapshotPump.Timing(100, Long.MAX_VALUE - 10, true);
        check(t.at(Long.MIN_VALUE + 9) == 120, "Delta monotónico con wrap");
        check(new UiSnapshotPump.Timing(100, 1000, true).at(900) == 100, "Marca de pared futura no retrocede");
        var cfg = new SimulationConfig(1, 2, SimulationConfig.MAX_TIME_MILLIS, 1);
        long duration = cfg.movementTimeMillis() * MS;
        var s = new ElevatorSnapshot(1, cfg, 0, 0, 1, OptionalInt.of(2), Direction.UP, ElevatorPhase.MOVING,
                Optional.empty(), Optional.empty(), ElevatorSnapshot.Mode.NORMAL,
                duration, duration / 2, 0, OptionalInt.empty(), List.of(), List.of());
        near(CabinMotion.position(s, Long.MAX_VALUE), 2);
        var wrapped = moving(1, 2, Direction.UP, Long.MAX_VALUE - 100 * MS, 1000 * MS);
        near(CabinMotion.position(wrapped, Long.MIN_VALUE + 100 * MS - 1), 2.2);
    }
    public static void rasterMovesThroughIntermediateCoordinates() throws Exception { edt(() -> {
        var clock = new AtomicLong(WALL); var p = panel(clock);
        p.render(frame(moving(1, 2, Direction.UP, ACTIVE, 1000 * MS), timing()), 0);
        var start = p.geometry().cabinBounds(1, 2);
        clock.addAndGet(500 * MS); var middle = p.geometry().cabinBounds(1, p.displayedPosition(1));
        check(middle.y < start.y && middle.y > p.geometry().cabinBounds(1, 3).y, "Coordenada intermedia");
        var image = new BufferedImage(800, 500, BufferedImage.TYPE_INT_RGB); var g = image.createGraphics();
        try { p.paint(g); } finally { g.dispose(); }
        check(image.getRGB(middle.x + 5, middle.y + 5) == BuildingPanel.CABIN_COLOR.getRGB(), "Raster en posición interpolada");
        check(p.displayedFloor(1) == 2, "Raster no modifica el piso lógico");
    }); }
    public static void cabinsInterpolateIndependently() throws Exception { edt(() -> {
        var clock = new AtomicLong(WALL + 250 * MS); var p = panel(clock);
        var a = moving(1, 2, Direction.UP, ACTIVE, 1000 * MS);
        var b = moving(2, 5, Direction.DOWN, ACTIVE, 500 * MS);
        p.render(new UiSnapshotPump.Frame(SESSION, 0, List.of(a, b), Map.of(), Optional.of(timing())), 0);
        near(p.displayedPosition(1), 2.25); near(p.displayedPosition(2), 4.25);
    }); }
    public static void resizingDoesNotRestartTemporalProgress() throws Exception { edt(() -> {
        var clock = new AtomicLong(WALL + 500 * MS); var p = panel(clock);
        p.render(frame(moving(1, 2, Direction.UP, ACTIVE, 1000 * MS), timing()), 0);
        near(p.displayedPosition(1), 2.5); int oldY = p.geometry().floorY(p.displayedPosition(1));
        p.setSize(1000, 700); near(p.displayedPosition(1), 2.5);
        check(p.geometry().floorY(p.displayedPosition(1)) != oldY, "Se adapta geometría, no reloj");
    }); }
    public static void productionCaptureUsesTheModelClock() throws Exception {
        var c = new SimulationController(new SimulationConfig(2, 4, 10000, 100), 0);
        try {
            c.start().get(5, TimeUnit.SECONDS);
            c.submit(new CabinRequest(UUID.randomUUID(), 0, 4, Instant.now(), 2)).get(5, TimeUnit.SECONDS);
            await(() -> c.snapshot(2).phase() == ElevatorPhase.MOVING);
            var f = UiSnapshotPump.capture(c); check(f != null && f.timing().isPresent(), "Captura temporal publicada");
            var s = f.snapshots().get(1); var t = f.timing().orElseThrow();
            check(t.advancing() && t.activeNanos() - s.referenceNanos() >= 0, "Mismo dominio activo");
            var before = c.snapshot(2);
            edt(() -> {
                var clock = new AtomicLong(t.wallNanos() + 250 * MS);
                var p = new BuildingPanel(clock::get); p.bind(c.sessionId(), c.config()); p.setSize(800, 500);
                p.render(f, c.epoch());
                check(p.displayedPosition(2) > s.currentFloor()
                        && p.displayedPosition(2) <= s.nextFloor().orElseThrow(), "Modelo alimenta interpolación");
                p.refreshAnimation();
            });
            check(before.equals(c.snapshot(2)), "Representar no escribe en el actor");
        } finally { c.closeAsync().get(5, TimeUnit.SECONDS); }
    }

    private static BuildingPanel panel(AtomicLong clock) {
        var p = new BuildingPanel(clock::get); p.bind(SESSION, CONFIG); p.setSize(800, 500); return p;
    }
    private static ElevatorSnapshot moving(int id, int floor, Direction direction, long reference, long remaining) {
        return new ElevatorSnapshot(id, CONFIG, 0, 1, floor, OptionalInt.of(floor + direction.floorDelta()), direction,
                ElevatorPhase.MOVING, Optional.empty(), Optional.empty(), ElevatorSnapshot.Mode.NORMAL,
                1000 * MS, remaining, reference, OptionalInt.empty(), List.of(), List.of());
    }
    private static ElevatorSnapshot idle(int id, int floor) {
        return new ElevatorSnapshot(id, CONFIG, 0, 2, floor, OptionalInt.empty(), Direction.UP, ElevatorPhase.IDLE,
                Optional.empty(), Optional.empty(), ElevatorSnapshot.Mode.NORMAL,
                0, 0, ACTIVE, OptionalInt.empty(), List.of(), List.of());
    }
    private static UiSnapshotPump.Timing timing() { return new UiSnapshotPump.Timing(ACTIVE, WALL, true); }
    private static UiSnapshotPump.Frame frame(ElevatorSnapshot snapshot, UiSnapshotPump.Timing timing) {
        return new UiSnapshotPump.Frame(SESSION, 0, List.of(snapshot, idle(2, 1)), Map.of(), Optional.of(timing));
    }
    private static void near(double actual, double expected) {
        if (Math.abs(actual - expected) > 1e-9) throw new AssertionError("Esperado " + expected + "; obtenido " + actual);
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static void edt(Runnable action) throws Exception { SwingUtilities.invokeAndWait(action); }
    private static void await(BooleanSupplier condition) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > end) throw new AssertionError("Plazo agotado");
            Thread.sleep(5);
        }
    }
    public static void main(String[] args) throws Exception {
        upwardStartMiddleAndEnd(); downwardInterpolation(); clampsToPublishedLeg(); addsElapsedToCapturedProgress();
        heldMovementUsesOnlyCapturedProgress(); idleAndDwellRemainAtLogicalFloor(); unrelatedClockOriginsAreAnchored();
        repeatedFrameDoesNotRestartMotion(); freshFrameOfSameLegDoesNotRestartMotion(); delayedDeliveryUsesCaptureAnchor();
        waitsAtEndpointUntilModelPublishesArrival(); untimedFramesUseCapturedProgress(); nonAdvancingSampleDoesNotCountWallTime();
        clockWrapAndLargeDurationRemainBounded(); rasterMovesThroughIntermediateCoordinates(); cabinsInterpolateIndependently();
        resizingDoesNotRestartTemporalProgress(); productionCaptureUsesTheModelClock();
        System.out.println("T59: 18 escenarios satisfactorios.");
    }
}
