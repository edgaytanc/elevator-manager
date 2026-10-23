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
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Casos de agenda con reloj explícito y tres recorridos por hilos reales. */
public final class DirectionalAgendaScenarios {
    private static final long MOVE = 100_000_000L;
    private static final long DWELL = 200_000_000L;
    private static final Duration LIMIT = Duration.ofSeconds(5);
    private DirectionalAgendaScenarios() { }

    public static void nearestAcrossRequestTypes() {
        var rig = new Rig(1);
        rig.state.addPendingRequest(cabin(7));
        rig.state.addPendingRequest(hall(3, Direction.UP));
        check(select(rig.state, Optional.empty()) == 3, "Externa próxima supera interna lejana.");
    }

    public static void tieUsesAdmissionInsteadOfTimestampOrType() {
        var rig = new Rig(5);
        var first = new HallRequest(UUID.randomUUID(), 0, 7, Instant.MAX, Direction.UP);
        var second = new CabinRequest(UUID.randomUUID(), 0, 3, Instant.MIN, 1);
        rig.state.addPendingRequest(first); rig.state.addPendingRequest(second);
        check(select(rig.state, Optional.empty()) == 7, "Empate por admisión, no createdAt o tipo.");
        var reverse = new Rig(5);
        reverse.state.addPendingRequest(second); reverse.state.addPendingRequest(first);
        check(select(reverse.state, Optional.empty()) == 3, "Orden inverso cambia desempate.");
    }

    public static void admissionViewIsImmutableAndRemovesServedRequests() {
        var rig = new Rig(3);
        var hall = hall(3, Direction.UP);
        var cabin = cabin(6);
        rig.state.addPendingRequest(hall); rig.state.addPendingRequest(cabin);
        var old = rig.state.getPendingRequestsInAdmissionOrder();
        reject(UnsupportedOperationException.class, old::clear);
        rig.state.beginDwell(Direction.UP, rig.now);
        check(old.equals(List.of(hall, cabin)), "Vista anterior conserva contenido.");
        check(rig.state.getPendingRequestsInAdmissionOrder().equals(List.of(cabin)),
                "Servicio elimina también el índice de admisión.");
    }

    public static void ascendingStopsAreSpatiallyOrdered() {
        var rig = new Rig(1);
        rig.state.addPendingRequest(cabin(8));
        rig.state.addPendingRequest(cabin(4));
        rig.state.addPendingRequest(cabin(6));
        rig.travel(8);
        check(rig.floors().equals(List.of(4, 6, 8)), "Sube en orden espacial, no de inserción.");
        check(rig.state.getPendingRequestsInAdmissionOrder().isEmpty(), "Sin pendientes fantasma.");
    }

    public static void descendingStopsAreSpatiallyOrdered() {
        var rig = new Rig(8);
        rig.state.addPendingRequest(cabin(2));
        rig.state.addPendingRequest(cabin(6));
        rig.state.addPendingRequest(cabin(4));
        int target = select(rig.state, Optional.empty());
        check(target == 6, "Objetivo inicial próximo.");
        rig.travel(target);
        check(rig.floors().equals(List.of(6, 4, 2)), "Baja en orden espacial.");
        check(rig.stops.stream().allMatch(s -> s.direction() == Direction.DOWN), "Servicio DOWN.");
    }

    public static void hallBehindDoesNotReverseCommitment() {
        var rig = new Rig(1);
        rig.state.addPendingRequest(cabin(8)); rig.state.beginJourney(8, rig.now);
        var behind = hall(2, Direction.DOWN);
        rig.state.addPendingRequest(behind);
        check(rig.state.getCommittedFloor().orElseThrow() == 8, "Conserva frontera 8.");
        rig.finishJourney();
        check(rig.floors().equals(List.of(8)), "No atiende DOWN al subir.");
        check(rig.state.getHallRequests().equals(List.of(behind)), "Llamada diferida conservada.");
        rig.travel(select(rig.state, Optional.of(Direction.UP)));
        check(rig.floors().equals(List.of(8, 2)), "Recoge después de completar subida.");
    }

    public static void compatibleHallIsCollectedOnTheWay() {
        var rig = new Rig(1);
        rig.state.addPendingRequest(cabin(8)); rig.state.beginJourney(8, rig.now);
        rig.state.addPendingRequest(hall(5, Direction.UP));
        rig.finishJourney();
        check(rig.floors().equals(List.of(5, 8)), "Recogida intermedia compatible.");
    }

    public static void oppositeHallWaitsUntilReturn() {
        var rig = new Rig(1);
        rig.state.addPendingRequest(cabin(8)); rig.state.beginJourney(8, rig.now);
        rig.state.addPendingRequest(hall(3, Direction.DOWN));
        rig.state.addPendingRequest(hall(5, Direction.UP));
        rig.finishJourney();
        rig.travel(select(rig.state, Optional.of(Direction.UP)));
        check(rig.floors().equals(List.of(5, 8, 3)), "Ruta 5/UP, 8/UP, 3/DOWN.");
        check(rig.stops.get(2).direction() == Direction.DOWN, "Dirección de recogida correcta.");
    }

    public static void internalExtendsMovingFrontierWithoutRestartingTime() {
        var rig = new Rig(1);
        rig.state.addPendingRequest(cabin(4)); rig.state.beginJourney(4, 0);
        rig.state.addPendingRequest(cabin(7));
        check(rig.state.getCommittedFloor().orElseThrow() == 7, "Interna amplía a 7.");
        check(rig.state.movementRemainingNanos(MOVE / 2) == MOVE / 2, "No reinicia tramo.");
        rig.finishJourney();
        check(rig.floors().equals(List.of(4, 7)), "Sirve ambas internas en el mismo barrido.");
    }

    public static void externalBeyondFrontierDoesNotExtendSweep() {
        var rig = new Rig(1);
        rig.state.addPendingRequest(cabin(4)); rig.state.beginJourney(4, 0);
        rig.state.addPendingRequest(hall(6, Direction.UP));
        rig.state.addPendingRequest(hall(2, Direction.DOWN));
        check(rig.state.getCommittedFloor().orElseThrow() == 4, "Externa no amplía a 6.");
        rig.finishJourney();
        check(select(rig.state, Optional.of(Direction.UP)) == 2, "Primero regreso pendiente.");
        check(rig.state.getHallRequests().size() == 2, "Ambas externas siguen pendientes.");
    }

    public static void initialFrontierIncludesExistingInternalsAhead() {
        var rig = new Rig(1);
        rig.state.addPendingRequest(hall(3, Direction.UP));
        rig.state.addPendingRequest(cabin(8));
        int target = select(rig.state, Optional.empty());
        rig.state.beginJourney(target, 0);
        check(target == 3 && rig.state.getCommittedFloor().orElseThrow() == 8,
                "Objetivo próximo y frontera interna lejana son conceptos distintos.");
        rig.finishJourney();
        check(rig.floors().equals(List.of(3, 8)), "No omite la recogida próxima.");
    }

    public static void internalExtendsDuringDwellWithoutRestartingStop() {
        var rig = new Rig(4);
        rig.state.addPendingRequest(cabin(4)); rig.state.beginDwell(Direction.UP, rig.now);
        rig.state.addPendingRequest(cabin(7));
        check(rig.state.getCommittedFloor().orElseThrow() == 7, "Amplía durante parada UP.");
        check(rig.state.dwellRemainingNanos(rig.now + DWELL / 2) == DWELL / 2,
                "No reinicia parada.");
        check(!rig.state.completeDwellIfDue(rig.now + DWELL - 1), "Conserva plazo.");
    }

    public static void requestAtFloorJustLeftIsDeferred() {
        var rig = new Rig(1);
        rig.state.addPendingRequest(cabin(4)); rig.state.beginJourney(4, 0);
        var left = hall(1, Direction.UP);
        rig.state.addPendingRequest(left);
        rig.finishJourney();
        check(rig.floors().equals(List.of(4)), "No recoge en piso ya dejado.");
        rig.travel(select(rig.state, Optional.of(Direction.UP)));
        check(rig.state.getHallRequests().equals(List.of(left)), "Regreso DOWN no sirve UP.");
        check(rig.state.beginDwell(Direction.UP, rig.now).equals(List.of(left)),
                "En frontera puede invertir para recoger UP.");
    }

    public static void returnCommitsFarthestPendingFloorBehind() {
        var rig = new Rig(8);
        rig.state.addPendingRequest(hall(6, Direction.DOWN));
        rig.state.addPendingRequest(hall(2, Direction.DOWN));
        rig.state.addPendingRequest(hall(7, Direction.UP));
        int target = select(rig.state, Optional.of(Direction.UP));
        check(target == 2, "Regreso compromete el extremo pendiente.");
        rig.travel(target);
        check(rig.floors().equals(List.of(6, 2)), "Compatibles ordenadas en el regreso.");
        check(rig.state.getHallRequests().size() == 1
                && rig.state.getHallRequests().get(0).getFloor() == 7, "UP diferida conservada.");
    }

    public static void idleDirectionDoesNotForceNextJourney() {
        var rig = new Rig(8);
        check(rig.state.getDirection() == Direction.UP, "Dirección histórica UP.");
        rig.state.addPendingRequest(cabin(5));
        rig.state.beginJourney(select(rig.state, Optional.empty()), rig.now);
        check(rig.state.getDirection() == Direction.DOWN, "Libre elige sentido del objetivo.");
        rig.state.addPendingRequest(cabin(2));
        check(rig.state.getCommittedFloor().orElseThrow() == 2, "Interna amplía también en DOWN.");
        check(rig.state.movementRemainingNanos(rig.now + MOVE / 2) == MOVE / 2,
                "Ampliar en DOWN no reinicia tramo.");
    }

    public static void emptySingleFloorAndExtremeFloors() {
        check(DirectionalAgenda.selectTarget(1, List.of(), Optional.empty()).isEmpty(), "Sin trabajo.");
        check(DirectionalAgenda.selectTarget(1, List.of(cabin(1)), Optional.empty()).orElseThrow() == 1,
                "Piso actual permitido para resolver servicio sin movimiento.");
        check(DirectionalAgenda.selectTarget(Integer.MAX_VALUE,
                List.of(cabin(1), cabin(Integer.MAX_VALUE - 1)), Optional.empty()).orElseThrow()
                == Integer.MAX_VALUE - 1, "Distancias extremas sin desbordamiento.");
    }

    public static void duplicateDoesNotReorderOrChangeCommitment() {
        var rig = new Rig(1);
        var first = cabin(4);
        var second = hall(2, Direction.UP);
        rig.state.addPendingRequest(first); rig.state.addPendingRequest(second);
        rig.state.beginJourney(4, 0);
        check(!rig.state.addPendingRequest(first), "Repetición exacta no reinserta.");
        long revision = rig.state.getRevision();
        reject(IllegalArgumentException.class, () -> rig.state.addPendingRequest(cabin(4)));
        check(rig.state.getPendingRequestsInAdmissionOrder().equals(List.of(first, second)),
                "Orden de admisión intacto.");
        check(rig.state.getRevision() == revision
                && rig.state.getCommittedFloor().orElseThrow() == 4, "Rechazo atómico.");
    }

    public static void workerClassicRoute() throws Exception {
        var worker = worker();
        try {
            worker.start();
            send(worker, new Command.Destination(1, 1, cabin(8)));
            send(worker, new Command.Pickup(2, 1, hall(5, Direction.UP)));
            send(worker, new Command.Pickup(3, 1, hall(3, Direction.DOWN)));
            var first = service(worker); var second = service(worker); var third = service(worker);
            check(List.of(first.floor(), second.floor(), third.floor()).equals(List.of(5, 8, 3)),
                    "Hilo real respeta recogida compatible y retorno.");
            check(third.direction() == Direction.DOWN && worker.failure().isEmpty(), "Recoge DOWN.");
        } finally { close(worker); }
    }

    public static void workerExtendsBeforeReturning() throws Exception {
        var worker = worker();
        try {
            worker.start();
            send(worker, new Command.Destination(1, 1, cabin(4)));
            send(worker, new Command.Destination(2, 1, cabin(7)));
            send(worker, new Command.Pickup(3, 1, hall(2, Direction.DOWN)));
            var first = service(worker); var second = service(worker); var third = service(worker);
            check(List.of(first.floor(), second.floor(), third.floor()).equals(List.of(4, 7, 2)),
                    "La ampliación interna se completa antes del regreso.");
        } finally { close(worker); }
    }

    public static void workerReturnStopsAreSpatiallyOrdered() throws Exception {
        var worker = worker();
        try {
            worker.start();
            send(worker, new Command.Destination(1, 1, cabin(6)));
            send(worker, new Command.Pickup(2, 1, hall(2, Direction.DOWN)));
            send(worker, new Command.Pickup(3, 1, hall(5, Direction.DOWN)));
            var first = service(worker); var second = service(worker); var third = service(worker);
            check(List.of(first.floor(), second.floor(), third.floor()).equals(List.of(6, 5, 2)),
                    "Regreso ordena 5 antes de 2 aunque 2 se admitió primero.");
            check(second.direction() == Direction.DOWN && third.direction() == Direction.DOWN,
                    "No mezcla sentidos en el retorno.");
        } finally { close(worker); }
    }

    public static void main(String[] args) throws Exception {
        nearestAcrossRequestTypes(); tieUsesAdmissionInsteadOfTimestampOrType();
        admissionViewIsImmutableAndRemovesServedRequests(); ascendingStopsAreSpatiallyOrdered();
        descendingStopsAreSpatiallyOrdered(); hallBehindDoesNotReverseCommitment();
        compatibleHallIsCollectedOnTheWay(); oppositeHallWaitsUntilReturn();
        internalExtendsMovingFrontierWithoutRestartingTime(); externalBeyondFrontierDoesNotExtendSweep();
        initialFrontierIncludesExistingInternalsAhead(); internalExtendsDuringDwellWithoutRestartingStop();
        requestAtFloorJustLeftIsDeferred(); returnCommitsFarthestPendingFloorBehind();
        idleDirectionDoesNotForceNextJourney(); emptySingleFloorAndExtremeFloors();
        duplicateDoesNotReorderOrChangeCommitment(); workerClassicRoute();
        workerExtendsBeforeReturning(); workerReturnStopsAreSpatiallyOrdered();
        System.out.println("T25: 20 escenarios satisfactorios.");
    }

    private record Stop(int floor, Direction direction, List<Request> requests) { }

    /** Avanza únicamente física y servicio; los casos eligen explícitamente cada barrido. */
    private static final class Rig {
        private final ElevatorState state = new ElevatorState(1, new SimulationConfig(1, 10, 100, 200), 0);
        private final List<Stop> stops = new ArrayList<>();
        private long now;

        private Rig(int floor) { if (floor != 1) { travel(floor); } }
        private void travel(int target) { state.beginJourney(target, now); finishJourney(); }
        private List<Integer> floors() { return stops.stream().map(Stop::floor).toList(); }

        private void finishJourney() {
            int guard = 0;
            while (state.getPhase() == ElevatorPhase.MOVING || state.getCommittedFloor().isPresent()) {
                check(++guard < 100, "Recorrido termina sin bucle.");
                if (state.getPhase() == ElevatorPhase.MOVING) {
                    now += state.movementRemainingNanos(now);
                    check(state.completeLegIfDue(now), "Llegada física.");
                }
                List<Request> served = state.beginDwell(state.getDirection(), now);
                if (!served.isEmpty()) {
                    stops.add(new Stop(state.getCurrentFloor(), state.getDirection(), served));
                    now += state.dwellRemainingNanos(now);
                    check(state.completeDwellIfDue(now), "Parada completa.");
                }
                if (state.getCommittedFloor().isPresent()) { state.beginNextLeg(now); }
            }
        }
    }

    private static int select(ElevatorState state, Optional<Direction> completed) {
        return DirectionalAgenda.selectTarget(state.getCurrentFloor(),
                state.getPendingRequestsInAdmissionOrder(), completed).orElseThrow();
    }

    private static CabinRequest cabin(int floor) {
        return new CabinRequest(UUID.randomUUID(), 0, floor, Instant.now(), 1);
    }

    private static HallRequest hall(int floor, Direction direction) {
        return new HallRequest(UUID.randomUUID(), 0, floor, Instant.now(), direction);
    }

    private static ElevatorWorker worker() {
        return new ElevatorWorker(1, new SimulationConfig(1, 10, 100, 20), 0);
    }

    private static void send(ElevatorWorker worker, Command command) throws Exception {
        check(worker.submit(command), "Comando encolado.");
        check(worker.awaitResult(LIMIT).orElseThrow().outcome() == ElevatorWorker.Outcome.ACCEPTED,
                "Comando aceptado.");
    }

    private static ElevatorWorker.Service service(ElevatorWorker worker) throws Exception {
        return worker.awaitService(LIMIT).orElseThrow(() -> new AssertionError("Falta servicio."));
    }

    private static void close(ElevatorWorker worker) throws Exception {
        worker.close(); check(worker.awaitTermination(LIMIT), "Trabajador terminado.");
    }

    private static void reject(Class<? extends RuntimeException> type, Runnable action) {
        try { action.run(); }
        catch (RuntimeException exception) {
            if (type.isInstance(exception)) { return; }
            throw new AssertionError("Rechazo inesperado.", exception);
        }
        throw new AssertionError("Se esperaba rechazo.");
    }

    private static void check(boolean condition, String message) {
        if (!condition) { throw new AssertionError(message); }
    }
}
