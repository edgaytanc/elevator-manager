package com.elevatormanager.dispatch;

import com.elevatormanager.model.Direction;
import com.elevatormanager.model.ElevatorPhase;
import com.elevatormanager.model.ElevatorSnapshot;
import com.elevatormanager.model.ElevatorState;
import com.elevatormanager.model.SimulationConfig;
import com.elevatormanager.model.request.CabinRequest;
import com.elevatormanager.model.request.HallRequest;
import com.elevatormanager.model.request.Request;
import com.elevatormanager.protocol.Command;
import com.elevatormanager.simulation.DirectionalAgenda;
import com.elevatormanager.simulation.ElevatorWorker;
import com.elevatormanager.simulation.PlanningSnapshot;
import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;

/** Casos calculados a mano y contraste con el modelo físico piso por piso. */
public final class PickupTimeEstimatorScenarios {
    private static final long MS = 1_000_000L;
    private static final PickupTimeEstimator ESTIMATOR = new PickupTimeEstimator();
    private PickupTimeEstimatorScenarios() { }

    public static void idleAndCurrentFloor() {
        var f = new Fixture(1, 8);
        expectMillis(f, hall(4, Direction.DOWN), 300);
        expectMillis(f, hall(1, Direction.UP), 0);
    }

    public static void movingUsesRemainingLeg() {
        var f = upward(5);
        f.now = 40 * MS;
        expectMillis(f, hall(3, Direction.UP), 160);
        expectMillis(f, hall(3, Direction.UP), f.now + 20 * MS, 140);
    }

    public static void compatibleIntermediateStopsAreChargedOnce() {
        var f = upward(5);
        f.state.addPendingRequest(cabin(2));
        f.state.addPendingRequest(hall(2, Direction.UP));
        f.now = 40 * MS;
        var estimate = expectMillis(f, hall(3, Direction.UP), 210);
        check(estimate.steps().stream().filter(s -> s.type() == PickupTimeEstimator.StepType.WAIT).count() == 1,
                "Destino y recogida compatibles en piso 2 comparten parada.");
    }

    public static void oppositeDirectionRequiresReturn() {
        var f = upward(5); f.now = 40 * MS;
        expectMillis(f, hall(3, Direction.DOWN), 610);
    }

    public static void departedFloorCannotBePickedImmediately() {
        var f = upward(5); f.now = 40 * MS;
        expectMillis(f, hall(1, Direction.UP), 810);
    }

    public static void downSweepIsSymmetric() {
        var f = new Fixture(1, 8); f.moveTo(5);
        f.state.addPendingRequest(cabin(1));
        f.state.beginJourney(1, f.now); f.now += 40 * MS;
        expectMillis(f, hall(3, Direction.UP), 610);
    }

    public static void joinsCompatibleOpenStop() {
        var f = new Fixture(1, 8); f.moveTo(3);
        f.state.addPendingRequest(cabin(3));
        f.state.beginDwell(Direction.UP, f.now); f.now += 20 * MS;
        expectMillis(f, hall(3, Direction.UP), 0);
    }

    public static void oppositeCallWaitsForCommittedDwellAndReturn() {
        var f = new Fixture(1, 8); f.moveTo(3);
        f.state.addPendingRequest(cabin(3)); f.state.beginDwell(Direction.UP, f.now);
        f.state.addPendingRequest(cabin(5)); f.now += 20 * MS;
        expectMillis(f, hall(3, Direction.DOWN), 480);
    }

    public static void completedSweepChangesTheProjectedRoute() {
        var f = new Fixture(1, 8); f.moveTo(5);
        f.state.addPendingRequest(cabin(5)); f.state.beginDwell(Direction.UP, f.now);
        f.state.addPendingRequest(hall(2, Direction.UP));
        f.state.addPendingRequest(hall(4, Direction.UP));
        f.sweep = Optional.of(Direction.UP); f.now += 20 * MS;
        expectMillis(f, hall(3, Direction.UP), 480);
        f.sweep = Optional.empty();
        expectMillis(f, hall(3, Direction.UP), 280);
    }

    public static void crossTypeAdmissionOrderBreaksTies() {
        var hallFirst = new Fixture(1, 8); hallFirst.moveTo(3);
        hallFirst.state.addPendingRequest(hall(2, Direction.UP));
        hallFirst.state.addPendingRequest(cabin(4));
        expectMillis(hallFirst, hall(5, Direction.DOWN), 500);
        var cabinFirst = new Fixture(1, 8); cabinFirst.moveTo(3);
        cabinFirst.state.addPendingRequest(cabin(4));
        cabinFirst.state.addPendingRequest(hall(2, Direction.UP));
        expectMillis(cabinFirst, hall(5, Direction.DOWN), 700);
    }

    public static void beyondFrontierDoesNotExtendExistingSweep() {
        var f = upward(3); f.now = 40 * MS;
        expectMillis(f, hall(6, Direction.UP), 510);
    }

    public static void nearButOccupiedCanCostMore() {
        var near = new Fixture(1, 8); near.moveTo(2);
        near.state.addPendingRequest(cabin(5)); near.state.beginJourney(5, near.now); near.now += 40 * MS;
        var far = new Fixture(1, 8);
        var request = hall(3, Direction.DOWN);
        var nearCost = expectMillis(near, request, 510).pickupNanos().orElseThrow();
        var farCost = expectMillis(far, request, 200).pickupNanos().orElseThrow();
        check(nearCost.compareTo(farCost) > 0, "Distancia no equivale a tiempo de recogida.");
    }

    public static void functionalDuplicateDoesNotAddAStop() {
        var f = upward(5); f.state.addPendingRequest(hall(3, Direction.UP)); f.now = 40 * MS;
        expectMillis(f, hall(3, Direction.UP), 160);
    }

    public static void invalidCallsAndUuidConflictsAreExplicit() {
        var f = new Fixture(1, 8);
        var existing = hall(2, Direction.UP); f.state.addPendingRequest(existing);
        for (var request : List.of(hall(9, Direction.UP), hall(8, Direction.UP), hall(1, Direction.DOWN),
                new HallRequest(UUID.randomUUID(), 1, 2, Instant.EPOCH, Direction.UP),
                new HallRequest(existing.getId(), 0, 3, existing.getCreatedAt(), Direction.UP))) {
            var result = ESTIMATOR.estimate(f.capture(), request);
            check(result.status() == PickupTimeEstimator.Status.INVALID_REQUEST
                    && result.pickupNanos().isEmpty(), "Invalidez no se transforma en costo cero.");
        }
    }

    public static void unavailableStatesHaveNoCost() {
        var f = upward(5); f.now = 40 * MS; var moving = f.capture().elevator();
        var held = new ElevatorSnapshot(moving.id(), moving.config(), 0, moving.revision(),
                moving.currentFloor(), moving.nextFloor(), moving.direction(), ElevatorPhase.HELD,
                Optional.of(ElevatorPhase.MOVING), Optional.of(ElevatorSnapshot.HoldReason.PAUSE),
                ElevatorSnapshot.Mode.NORMAL, moving.phaseDurationNanos(), moving.remainingNanos(),
                moving.referenceNanos(), moving.committedFloor(), moving.cabinRequests(), moving.hallRequests());
        var empty = new Fixture(1, 8).capture().elevator();
        var resetting = copyIdle(empty, ElevatorPhase.IDLE, ElevatorSnapshot.Mode.RESET_RETURN);
        var terminated = copyIdle(empty, ElevatorPhase.TERMINATED, ElevatorSnapshot.Mode.NORMAL);
        for (var snapshot : List.of(held, resetting, terminated)) {
            var order = snapshot == held ? f.capture().admissionOrder() : List.<Request>of();
            var result = ESTIMATOR.estimate(new PlanningSnapshot(snapshot, order, Optional.empty()), hall(2, Direction.UP));
            check(result.status() == PickupTimeEstimator.Status.UNAVAILABLE && result.pickupNanos().isEmpty(),
                    "Cabina no disponible sin costo numérico.");
        }
    }

    public static void overduePhaseAndNanoTimeWrapAreHandled() {
        var f = upward(5); f.now = 40 * MS;
        expectMillis(f, hall(3, Direction.UP), f.now + 200 * MS, 100);
        expect(IllegalArgumentException.class, () -> ESTIMATOR.estimate(f.capture(), hall(2, Direction.UP), f.now - 1));
        var state = new ElevatorState(1, f.state.getConfig(), 0);
        long start = Long.MAX_VALUE - 50 * MS;
        state.addPendingRequest(cabin(5)); state.beginJourney(5, start);
        var planning = new PlanningSnapshot(state.snapshot(start), state.getPendingRequestsInAdmissionOrder(), Optional.empty());
        var result = ESTIMATOR.estimate(planning, hall(3, Direction.UP), start + 70 * MS);
        check(result.pickupNanos().orElseThrow().equals(BigInteger.valueOf(130 * MS)), "Wrap normal de nanoTime.");
    }

    public static void hugeBuildingAndTimeDoNotOverflow() {
        var config = new SimulationConfig(1, Integer.MAX_VALUE, SimulationConfig.MAX_TIME_MILLIS, 50);
        var state = new ElevatorState(1, config, 0);
        var planning = new PlanningSnapshot(state.snapshot(0), List.of(), Optional.empty());
        var result = ESTIMATOR.estimate(planning, hall(Integer.MAX_VALUE, Direction.DOWN));
        var expected = BigInteger.valueOf(Integer.MAX_VALUE - 1L)
                .multiply(BigInteger.valueOf(SimulationConfig.MAX_TIME_MILLIS)).multiply(BigInteger.valueOf(MS));
        check(result.pickupNanos().orElseThrow().equals(expected), "Aritmética exacta más allá de long.");
        check(result.steps().size() == 2, "Un salto y recogida; sin iterar miles de millones de pisos.");
    }

    public static void inputAndTraceAreImmutable() {
        var f = upward(5); f.now = 40 * MS;
        var snapshot = f.capture(); var request = hall(3, Direction.DOWN);
        var a = ESTIMATOR.estimate(snapshot, request); var b = ESTIMATOR.estimate(snapshot, request);
        check(a.equals(b) && snapshot.equals(f.capture()), "Determinismo sin mutar el modelo.");
        expect(UnsupportedOperationException.class, () -> snapshot.admissionOrder().clear());
        expect(UnsupportedOperationException.class, () -> a.steps().clear());
        var sum = a.steps().stream().map(PickupTimeEstimator.Step::durationNanos).reduce(BigInteger.ZERO, BigInteger::add);
        check(sum.equals(a.pickupNanos().orElseThrow()), "Desglose coincide con ETA.");
        expect(IllegalArgumentException.class, () -> new PlanningSnapshot(snapshot.elevator(), List.of(), Optional.empty()));
        var wrong = new CabinRequest(snapshot.admissionOrder().get(0).getId(), 0, 6, Instant.EPOCH, 1);
        expect(IllegalArgumentException.class, () -> new PlanningSnapshot(snapshot.elevator(), List.of(wrong), Optional.empty()));
    }

    public static void publishedPlanningCaptureIsCoherent() throws Exception {
        var config = new SimulationConfig(1, 8, 100, 50);
        try (var worker = new ElevatorWorker(1, config, 0)) {
            worker.start();
            var outside = hall(2, Direction.UP);
            var inside = cabin(4);
            check(worker.submit(new Command.Pickup(1, 1, outside)), "Llamada enviada.");
            worker.awaitResult(Duration.ofSeconds(5)).orElseThrow();
            check(worker.submit(new Command.Destination(2, 1, inside)), "Destino enviado.");
            worker.awaitResult(Duration.ofSeconds(5)).orElseThrow();
            var planning = worker.planningSnapshot();
            // La propia construcción valida conjunto, identidad y contenido de pendientes.
            new PlanningSnapshot(planning.elevator(), planning.admissionOrder(), planning.completedSweep());
            var retained = planning;
            ESTIMATOR.estimate(planning, hall(3, Direction.DOWN));
            worker.close(); check(worker.awaitTermination(Duration.ofSeconds(5)), "Trabajador termina.");
            check(retained.equals(planning) && worker.failure().isEmpty(), "Captura retenida y motor sin fallos.");
        }
    }

    public static void generatedRoutesMatchPhysicalModel() {
        var random = new Random(30029);
        for (int i = 0; i < 60; i++) {
            var f = new Fixture(1, 8); f.moveTo(1 + random.nextInt(8));
            int a = 1 + random.nextInt(8); int b = a % 8 + 1;
            var requests = new ArrayList<Request>(List.of(cabin(a), cabin(b),
                    hall(2, Direction.UP), hall(6, Direction.DOWN)));
            Collections.shuffle(requests, random);
            for (var request : requests) { f.state.addPendingRequest(request); }
            if (i % 3 == 0) {
                int target = f.state.getCurrentFloor() == 8 ? 1 : 8;
                f.state.beginJourney(target, f.now); f.now += 37 * MS;
            } else if (i % 3 == 1) {
                if (f.state.getCabinRequests().stream().noneMatch(r -> r.getFloor() == f.state.getCurrentFloor())) {
                    f.state.addPendingRequest(cabin(f.state.getCurrentFloor()));
                }
                f.state.beginDwell(f.state.getDirection(), f.now); f.now += 17 * MS;
            }
            int floor = 1 + random.nextInt(8);
            Direction direction = floor == 1 ? Direction.UP : floor == 8 ? Direction.DOWN
                    : random.nextBoolean() ? Direction.UP : Direction.DOWN;
            var request = hall(floor, direction);
            var estimate = ESTIMATOR.estimate(f.capture(), request).pickupNanos().orElseThrow();
            long physical = physicalPickup(f, request);
            check(estimate.equals(BigInteger.valueOf(physical)), "Proyección contra modelo físico: caso " + i
                    + ", estimado=" + estimate + ", modelo=" + physical);
        }
    }

    /** Oráculo: usa las primitivas reales de ElevatorState y avanza un piso por transición. */
    private static long physicalPickup(Fixture f, HallRequest request) {
        HallRequest target = f.state.getHallRequests().stream().filter(r -> r.getFloor() == request.getFloor()
                && r.getDirection() == request.getDirection()).findFirst().orElse(request);
        if (target == request) { f.state.addPendingRequest(request); }
        long origin = f.now;
        for (int transitions = 0; transitions < 1000; transitions++) {
            if (f.state.getPhase() == ElevatorPhase.MOVING) {
                f.now += f.state.movementRemainingNanos(f.now); f.state.completeLegIfDue(f.now);
                if (f.state.getCommittedFloor().isEmpty()) { f.sweep = Optional.of(f.state.getDirection()); }
            } else if (f.state.getPhase() == ElevatorPhase.DWELLING) {
                if (f.state.serveDuringDwell(f.now).contains(target)) { return f.now - origin; }
                f.now += f.state.dwellRemainingNanos(f.now); f.state.completeDwellIfDue(f.now);
            }
            if (f.state.beginDwell(f.state.getDirection(), f.now).contains(target)) { return f.now - origin; }
            if (f.state.getPhase() == ElevatorPhase.DWELLING) { continue; }
            if (f.state.getCommittedFloor().isPresent()) {
                f.sweep = Optional.empty(); f.state.beginNextLeg(f.now);
            } else {
                if (f.state.beginDwell(f.state.getDirection().opposite(), f.now).contains(target)) { return f.now - origin; }
                if (f.state.getPhase() == ElevatorPhase.DWELLING) { continue; }
                var next = DirectionalAgenda.selectTarget(f.state.getCurrentFloor(), f.state.getPendingRequestsInAdmissionOrder(), f.sweep);
                f.state.beginJourney(next.orElseThrow(), f.now); f.sweep = Optional.empty();
            }
        }
        throw new AssertionError("Oráculo no terminó.");
    }

    public static void main(String[] args) throws Exception {
        idleAndCurrentFloor(); movingUsesRemainingLeg(); compatibleIntermediateStopsAreChargedOnce();
        oppositeDirectionRequiresReturn(); departedFloorCannotBePickedImmediately(); downSweepIsSymmetric();
        joinsCompatibleOpenStop(); oppositeCallWaitsForCommittedDwellAndReturn(); completedSweepChangesTheProjectedRoute();
        crossTypeAdmissionOrderBreaksTies(); beyondFrontierDoesNotExtendExistingSweep(); nearButOccupiedCanCostMore();
        functionalDuplicateDoesNotAddAStop(); invalidCallsAndUuidConflictsAreExplicit(); unavailableStatesHaveNoCost();
        overduePhaseAndNanoTimeWrapAreHandled(); hugeBuildingAndTimeDoNotOverflow(); inputAndTraceAreImmutable();
        publishedPlanningCaptureIsCoherent(); generatedRoutesMatchPhysicalModel();
        System.out.println("T30: 20 escenarios satisfactorios; incluye 60 rutas contrastadas con ElevatorState.");
    }

    private static PickupTimeEstimator.Estimate expectMillis(Fixture f, HallRequest request, long millis) {
        return expectMillis(f, request, f.now, millis);
    }
    private static PickupTimeEstimator.Estimate expectMillis(Fixture f, HallRequest request, long observation, long millis) {
        var result = ESTIMATOR.estimate(f.capture(), request, observation);
        check(result.status() == PickupTimeEstimator.Status.ESTIMATED
                && result.pickupNanos().orElseThrow().equals(BigInteger.valueOf(millis).multiply(BigInteger.valueOf(MS))),
                "Esperado " + millis + " ms; obtenido " + result);
        return result;
    }
    private static Fixture upward(int frontier) {
        var f = new Fixture(1, 8); f.state.addPendingRequest(cabin(frontier));
        f.state.beginJourney(frontier, 0); return f;
    }
    private static HallRequest hall(int floor, Direction direction) {
        return new HallRequest(UUID.randomUUID(), 0, floor, Instant.EPOCH, direction);
    }
    private static CabinRequest cabin(int floor) {
        return new CabinRequest(UUID.randomUUID(), 0, floor, Instant.EPOCH, 1);
    }
    private static ElevatorSnapshot copyIdle(ElevatorSnapshot s, ElevatorPhase phase, ElevatorSnapshot.Mode mode) {
        return new ElevatorSnapshot(s.id(), s.config(), s.epoch(), s.revision(), s.currentFloor(), s.nextFloor(),
                s.direction(), phase, Optional.empty(), Optional.empty(), mode, 0, 0,
                s.referenceNanos(), s.committedFloor(), s.cabinRequests(), s.hallRequests());
    }
    private static void check(boolean condition, String message) {
        if (!condition) { throw new AssertionError(message); }
    }
    private static void expect(Class<? extends Throwable> type, Runnable action) {
        try { action.run(); }
        catch (Throwable e) { if (type.isInstance(e)) { return; } throw new AssertionError(e); }
        throw new AssertionError("Falta excepción " + type.getSimpleName());
    }
    private static final class Fixture {
        private final ElevatorState state;
        private long now;
        private Optional<Direction> sweep = Optional.empty();
        private Fixture(int elevators, int floors) {
            state = new ElevatorState(1, new SimulationConfig(elevators, floors, 100, 50), 0);
        }
        private PlanningSnapshot capture() {
            return new PlanningSnapshot(state.snapshot(now), state.getPendingRequestsInAdmissionOrder(), sweep);
        }
        private void moveTo(int floor) {
            if (!state.beginJourney(floor, now)) { return; }
            while (true) {
                now += 100 * MS; state.completeLegIfDue(now);
                if (state.getCommittedFloor().isEmpty()) { return; }
                state.beginNextLeg(now);
            }
        }
    }
}
