package com.elevatormanager.dispatch;

import com.elevatormanager.model.Direction;
import com.elevatormanager.model.ElevatorPhase;
import com.elevatormanager.model.ElevatorState;
import com.elevatormanager.model.SimulationConfig;
import com.elevatormanager.model.request.CabinRequest;
import com.elevatormanager.model.request.HallRequest;
import com.elevatormanager.model.request.Request;
import com.elevatormanager.protocol.Command;
import com.elevatormanager.simulation.DirectionalAgenda;
import com.elevatormanager.simulation.ElevatorWorker;
import com.elevatormanager.simulation.ExecutionEvent;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import static com.elevatormanager.model.Direction.*;
import static com.elevatormanager.simulation.ExecutionEvent.Type.*;

/** T33: fronteras temporales con reloj explícito e integración con actores reales. */
public final class DirectionPickupScenarios {
    private static final long MS = 1_000_000L;
    private static final Duration LIMIT = Duration.ofSeconds(8);
    private DirectionPickupScenarios() { }

    public static void ascendingCompatibleCallPrecedesDestination() {
        var m = new Model(1); var call = hall(3, UP);
        m.add(cabin(6)); m.begin(6); m.add(call); m.finishSweep();
        m.expectStops("3/UP", "6/UP"); m.expectServedOnce(call);
    }

    public static void descendingCompatibleCallPrecedesDestination() {
        var m = new Model(7); var call = hall(5, DOWN);
        m.add(cabin(2)); m.begin(2); m.add(call); m.finishSweep();
        m.expectStops("5/DOWN", "2/DOWN"); m.expectServedOnce(call);
    }

    public static void oppositeAheadWaitsForAscendingBoundary() {
        var m = new Model(1); var call = hall(3, DOWN);
        m.add(cabin(6)); m.begin(6); m.add(call); m.finishSweep();
        m.expectStops("6/UP"); m.expectPending(call);
        m.begin(3); m.finishSweep(); m.serveHere(DOWN); m.finishDwell();
        m.expectStops("6/UP", "3/DOWN"); m.expectServedOnce(call);
    }

    public static void oppositeAheadWaitsForDescendingBoundary() {
        var m = new Model(7); var call = hall(5, UP);
        m.add(cabin(2)); m.begin(2); m.add(call); m.finishSweep();
        m.expectStops("2/DOWN"); m.expectPending(call);
        m.begin(5); m.finishSweep(); m.serveHere(UP); m.finishDwell();
        m.expectStops("2/DOWN", "5/UP"); m.expectServedOnce(call);
    }

    public static void callBehindDoesNotReverseAscendingCommitment() {
        var m = new Model(3); var call = hall(2, DOWN);
        m.add(cabin(7)); m.begin(7); m.add(call);
        check(m.state.getCommittedFloor().orElseThrow() == 7 && m.state.getDirection() == UP, "Mantiene compromiso ascendente.");
        m.finishSweep(); m.expectStops("7/UP"); m.expectPending(call);
        m.begin(2); m.finishSweep(); m.expectStops("7/UP", "2/DOWN"); m.expectServedOnce(call);
    }

    public static void callBehindDoesNotReverseDescendingCommitment() {
        var m = new Model(6); var call = hall(7, UP);
        m.add(cabin(2)); m.begin(2); m.add(call);
        check(m.state.getCommittedFloor().orElseThrow() == 2 && m.state.getDirection() == DOWN, "Mantiene compromiso descendente.");
        m.finishSweep(); m.expectStops("2/DOWN"); m.expectPending(call);
        m.begin(7); m.finishSweep(); m.expectStops("2/DOWN", "7/UP"); m.expectServedOnce(call);
    }

    public static void oppositeAtOpenUpStopWaitsForReturn() {
        var m = new Model(3); var call = hall(3, DOWN);
        m.add(cabin(3)); m.serveHere(UP); m.now += 10 * MS;
        m.add(cabin(6)); m.add(call);
        check(m.state.serveDuringDwell(m.now).isEmpty(), "Parada UP no recoge DOWN en el mismo piso.");
        m.finishDwell(); m.state.beginNextLeg(m.now); m.finishSweep(); m.expectPending(call);
        m.begin(3); m.finishSweep();
        m.expectStops("3/UP", "6/UP", "3/DOWN"); m.expectServedOnce(call);
    }

    public static void oppositeAtOpenDownStopWaitsForReturn() {
        var m = new Model(6); var call = hall(6, UP);
        m.add(cabin(6)); m.serveHere(DOWN); m.now += 10 * MS;
        m.add(cabin(2)); m.add(call);
        check(m.state.serveDuringDwell(m.now).isEmpty(), "Parada DOWN no recoge UP en el mismo piso.");
        m.finishDwell(); m.state.beginNextLeg(m.now); m.finishSweep(); m.expectPending(call);
        m.begin(6); m.finishSweep();
        m.expectStops("6/DOWN", "2/DOWN", "6/UP"); m.expectServedOnce(call);
    }

    public static void compatibleCallJoinsOpenStopWithoutRestart() {
        var m = new Model(3); m.add(cabin(3)); m.serveHere(UP);
        m.now += 10 * MS; long remaining = m.state.dwellRemainingNanos(m.now);
        var call = hall(3, UP); m.add(call);
        check(m.state.serveDuringDwell(m.now).equals(List.of(call)), "Se incorpora a parada compatible.");
        check(m.state.dwellRemainingNanos(m.now) == remaining && remaining == 40 * MS, "No reinicia los 50 ms de parada.");
        m.expectStops("3/UP"); check(m.state.getHallRequests().isEmpty(), "Recogida retirada de pendientes.");
        m.finishDwell();
    }

    public static void departedFloorIsNotServedDuringMovement() {
        var m = new Model(3); var call = hall(3, UP);
        m.add(cabin(6)); m.begin(6); m.add(call);
        expect(IllegalStateException.class, () -> m.state.beginDwell(UP, m.now));
        m.finishSweep(); m.expectStops("6/UP"); m.expectPending(call);
        m.begin(3); m.finishSweep(); m.expectPending(call);
        m.serveHere(UP); m.finishDwell();
        m.expectStops("6/UP", "3/UP"); m.expectServedOnce(call);
    }

    public static void bothDirectionsAtOneFloorHaveSeparateServices() {
        var m = new Model(1); var up = hall(3, UP); var down = hall(3, DOWN);
        m.add(cabin(6)); m.begin(6); m.add(up); m.add(down); m.finishSweep();
        m.expectStops("3/UP", "6/UP"); m.expectPending(down); m.expectServedOnce(up);
        m.begin(3); m.finishSweep();
        m.expectStops("3/UP", "6/UP", "3/DOWN"); m.expectServedOnce(down);
    }

    public static void externalBeyondFrontierDoesNotSkipReturn() {
        var m = new Model(1); var ahead = hall(6, UP); var back = hall(2, DOWN);
        m.add(cabin(4)); m.begin(4); m.add(ahead); m.add(back);
        check(m.state.getCommittedFloor().orElseThrow() == 4, "La llamada externa no amplía el barrido vigente.");
        m.finishSweep();
        int next = DirectionalAgenda.selectTarget(4, m.state.getPendingRequestsInAdmissionOrder(), Optional.of(UP)).orElseThrow();
        check(next == 2, "Completa el regreso pendiente antes de ir a la externa más alta.");
        m.begin(next); m.finishSweep(); m.begin(6); m.finishSweep();
        m.expectStops("4/UP", "2/DOWN", "6/UP"); m.expectServedOnce(ahead); m.expectServedOnce(back);
    }

    public static void internalExtensionFinishesBeforeOppositePickup() {
        var m = new Model(1); var call = hall(2, DOWN);
        m.add(cabin(4)); m.begin(4); m.now += 25 * MS;
        long remaining = m.state.movementRemainingNanos(m.now);
        m.add(cabin(7)); m.add(call);
        check(m.state.getCommittedFloor().orElseThrow() == 7 && m.state.movementRemainingNanos(m.now) == remaining,
                "Interna amplía compromiso sin reiniciar tramo.");
        m.finishSweep(); m.expectStops("4/UP", "7/UP"); m.expectPending(call);
        m.begin(2); m.finishSweep(); m.expectStops("4/UP", "7/UP", "2/DOWN");
    }

    public static void oppositeAtTopChangesDirectionWithoutInventingLeg() {
        var m = new Model(1); var call = hall(8, DOWN);
        m.add(cabin(8)); m.begin(8); m.add(call); m.finishSweep(); m.expectPending(call);
        int arrivals = m.arrivals; m.serveHere(DOWN); m.finishDwell();
        m.expectStops("8/UP", "8/DOWN"); m.expectServedOnce(call);
        check(m.arrivals == arrivals && arrivals == 7, "Inversión alineada en frontera sin movimiento ficticio.");
    }

    public static void idleCabinCanServeOppositeHistoricalDirection() {
        var m = new Model(4); var call = hall(4, DOWN);
        check(m.state.getDirection() == UP, "Dirección histórica UP.");
        m.add(call); m.serveHere(DOWN); m.finishDwell();
        m.expectStops("4/DOWN"); m.expectServedOnce(call);
        check(m.arrivals == 0, "Libre en el piso no necesita viajar para atender DOWN.");
    }

    public static void managerCollectsCompatibleBeforeCommittedDestination() throws Exception {
        try (var rig = new RealRig("compatible-en-subida")) {
            var destination = rig.destination(6); var call = rig.hall(3, UP);
            rig.finish(List.of(destination, call));
            rig.expectStops("3/UP", "6/UP"); rig.assertCommitment(UP, 6); rig.assertHallAdmission(call, UP, 6);
            rig.assertServed(call); rig.verify();
        }
    }

    public static void managerDefersOppositeUntilReturn() throws Exception {
        try (var rig = new RealRig("contraria-espera-regreso")) {
            var destination = rig.destination(6); var call = rig.hall(3, DOWN);
            rig.finish(List.of(destination, call));
            rig.expectStops("6/UP", "3/DOWN"); rig.assertCommitment(UP, 6); rig.assertHallAdmission(call, UP, 6);
            rig.assertServed(call); rig.verify();
        }
    }

    public static void managerSeparatesBothDirectionsAtSameFloor() throws Exception {
        try (var rig = new RealRig("dos-sentidos-en-piso-3")) {
            var destination = rig.destination(6); var up = rig.hall(3, UP); var down = rig.hall(3, DOWN);
            rig.finish(List.of(destination, up, down));
            rig.expectStops("3/UP", "6/UP", "3/DOWN"); rig.assertCommitment(UP, 6);
            rig.assertHallAdmission(up, UP, 6); rig.assertHallAdmission(down, UP, 6);
            rig.assertServed(up); rig.assertServed(down);
            check(!rig.manager.call(up.getId()).orElseThrow().canonicalRequestId()
                    .equals(rig.manager.call(down.getId()).orElseThrow().canonicalRequestId()), "Canónicas distintas por sentido.");
            rig.verify();
        }
    }

    public static void managerCollectsDescendingBeforeCommittedDestination() throws Exception {
        try (var rig = new RealRig("compatible-en-bajada")) {
            var setup = rig.destination(7); rig.finish(List.of(setup)); rig.trace.clear();
            var destination = rig.destination(2); var call = rig.hall(5, DOWN);
            rig.finish(List.of(destination, call));
            rig.expectStops("5/DOWN", "2/DOWN"); rig.assertCommitment(DOWN, 2); rig.assertHallAdmission(call, DOWN, 2);
            rig.assertServed(call); rig.verify();
        }
    }

    public static void managerDoesNotReverseForCallBehind() throws Exception {
        try (var rig = new RealRig("llamada-detras-del-barrido")) {
            var setup = rig.destination(3); rig.finish(List.of(setup)); rig.trace.clear();
            var destination = rig.destination(7); var call = rig.hall(2, DOWN);
            rig.finish(List.of(destination, call));
            rig.expectStops("7/UP", "2/DOWN"); rig.assertCommitment(UP, 7);
            var admitted = rig.trace.stream().filter(e -> e.type() == COMMAND_ACCEPTED
                    && requestOf(e).map(r -> r.getId().equals(call.getId())).orElse(false)).findFirst().orElseThrow();
            check(admitted.snapshot().direction() == UP
                    && admitted.snapshot().committedFloor().orElseThrow() == 7
                    && admitted.snapshot().currentFloor() > call.getFloor(),
                    "La llamada estaba realmente detrás durante el compromiso ascendente.");
            rig.assertServed(call); rig.verify();
        }
    }

    public static void main(String[] args) throws Exception {
        ascendingCompatibleCallPrecedesDestination(); descendingCompatibleCallPrecedesDestination();
        oppositeAheadWaitsForAscendingBoundary(); oppositeAheadWaitsForDescendingBoundary();
        callBehindDoesNotReverseAscendingCommitment(); callBehindDoesNotReverseDescendingCommitment();
        oppositeAtOpenUpStopWaitsForReturn(); oppositeAtOpenDownStopWaitsForReturn();
        compatibleCallJoinsOpenStopWithoutRestart(); departedFloorIsNotServedDuringMovement();
        bothDirectionsAtOneFloorHaveSeparateServices(); externalBeyondFrontierDoesNotSkipReturn();
        internalExtensionFinishesBeforeOppositePickup(); oppositeAtTopChangesDirectionWithoutInventingLeg();
        idleCabinCanServeOppositeHistoricalDirection(); managerCollectsCompatibleBeforeCommittedDestination();
        managerDefersOppositeUntilReturn(); managerSeparatesBothDirectionsAtSameFloor();
        managerCollectsDescendingBeforeCommittedDestination(); managerDoesNotReverseForCallBehind();
        System.out.println("T33: 20 escenarios satisfactorios; 15 de reloj explícito y 5 de integración con actores reales.");
    }

    private record Stop(int floor, Direction direction, List<Request> requests) {
        private Stop { requests = List.copyOf(requests); }
        private String label() { return floor + "/" + direction; }
    }

    /** El caso elige los barridos; las primitivas físicas y el servicio son de producción. */
    private static final class Model {
        private final ElevatorState state = new ElevatorState(1, new SimulationConfig(1, 8, 100, 50), 0);
        private final List<Stop> stops = new ArrayList<>();
        private long now;
        private int arrivals;
        private Model(int floor) {
            if (state.beginJourney(floor, now)) { finishSweep(); }
            arrivals = 0; stops.clear();
        }
        private void add(Request request) { state.addPendingRequest(request); }
        private void begin(int floor) { check(state.beginJourney(floor, now), "Comienza recorrido no vacío."); }
        private void serveHere(Direction direction) {
            var served = state.beginDwell(direction, now);
            if (!served.isEmpty()) { stops.add(new Stop(state.getCurrentFloor(), state.getDirection(), served)); }
        }
        private void finishDwell() {
            if (state.getPhase() == ElevatorPhase.DWELLING) {
                now += state.dwellRemainingNanos(now);
                check(state.completeDwellIfDue(now), "Termina espera completa.");
            }
        }
        private void finishSweep() {
            for (int i = 0; state.getPhase() == ElevatorPhase.MOVING || state.getCommittedFloor().isPresent(); i++) {
                check(i < 50, "Barrido finito.");
                now += state.movementRemainingNanos(now);
                check(state.completeLegIfDue(now), "Llegada física adyacente."); arrivals++;
                serveHere(state.getDirection()); finishDwell();
                if (state.getCommittedFloor().isPresent()) { state.beginNextLeg(now); }
            }
        }
        private void expectStops(String... expected) {
            check(stops.stream().map(Stop::label).toList().equals(List.of(expected)), "Paradas esperadas " + List.of(expected) + "; observadas " + stops);
        }
        private void expectPending(HallRequest request) { check(state.getHallRequests().contains(request), "Llamada diferida permanece pendiente."); }
        private void expectServedOnce(HallRequest request) {
            long count = stops.stream().flatMap(s -> s.requests().stream()).filter(r -> r.getId().equals(request.getId())).count();
            check(count == 1 && !state.getHallRequests().contains(request), "Una atención y ninguna pendiente fantasma.");
        }
    }

    private static final class RealRig implements AutoCloseable {
        private final String name;
        private final long origin = System.nanoTime();
        private final AtomicLong ids = new AtomicLong();
        private final ElevatorWorker worker = new ElevatorWorker(1, new SimulationConfig(1, 8, 200, 40), 0);
        private final ManagerWorker manager = new ManagerWorker(worker.snapshot().config(), 0, List.of(worker), ids::incrementAndGet);
        private final List<ExecutionEvent> trace = new ArrayList<>();
        private RealRig(String name) { this.name = name; worker.start(); manager.start(); }
        private CabinRequest destination(int floor) throws Exception {
            var request = cabin(floor);
            check(worker.submit(new Command.Destination(ids.incrementAndGet(), 1, request)), "Destino encolado.");
            check(worker.awaitResult(LIMIT).orElseThrow().outcome() == ElevatorWorker.Outcome.ACCEPTED, "Destino admitido.");
            until(e -> e.type() == DEPARTED); return request;
        }
        private HallRequest hall(int floor, Direction direction) throws Exception {
            var request = DirectionPickupScenarios.hall(floor, direction);
            check(manager.submit(request), "Llamada encolada en manejador.");
            var result = manager.awaitResult(LIMIT).orElseThrow();
            check(result.outcome() == ManagerWorker.Outcome.DISPATCHED || result.outcome() == ManagerWorker.Outcome.PENDING,
                    "Nueva llamada enviada o retenida para envío.");
            return request;
        }
        private void finish(List<? extends Request> required) throws Exception {
            until(e -> required.stream().allMatch(r -> trace.stream().anyMatch(t -> t.type() == COMMAND_COMPLETED
                    && requestOf(t).map(q -> q.getId().equals(r.getId())).orElse(false))));
            until(e -> e.type() == STOP_ENDED);
        }
        private void until(Predicate<ExecutionEvent> predicate) throws Exception {
            long started = System.nanoTime();
            while (true) {
                long remaining = LIMIT.toNanos() - (System.nanoTime() - started);
                check(remaining > 0, "Plazo de eventos agotado: " + name);
                var event = worker.awaitEvent(Duration.ofNanos(remaining)).orElseThrow(); trace.add(event);
                check(event.type() != WORKER_FAILED, "Fallo de cabina: " + worker.failure());
                if (predicate.test(event)) { return; }
            }
        }
        private void expectStops(String... expected) {
            var actual = trace.stream().filter(e -> e.type() == STOP_STARTED)
                    .map(e -> e.snapshot().currentFloor() + "/" + e.snapshot().direction()).toList();
            check(actual.equals(List.of(expected)), "Ruta esperada " + List.of(expected) + "; observada " + actual);
        }
        private void assertCommitment(Direction direction, int frontier) {
            boolean departed = false; boolean reached = false;
            for (var event : trace) {
                if (event.type() == DEPARTED) { departed = true; }
                if (!departed) { continue; }
                if (event.type() == ARRIVED && event.snapshot().currentFloor() == frontier) { reached = true; break; }
                check(event.type() != DIRECTION_CHANGED, "Sin inversión antes de alcanzar la frontera.");
                if (event.type() == DEPARTED) { check(event.snapshot().direction() == direction, "Conserva sentido comprometido."); }
            }
            check(reached, "Frontera alcanzada físicamente.");
        }
        private void assertHallAdmission(HallRequest request, Direction direction, int frontier) {
            var admitted = trace.stream().filter(e -> e.type() == COMMAND_ACCEPTED
                    && requestOf(e).map(r -> r.getId().equals(request.getId())).orElse(false)).findFirst().orElseThrow();
            var s = admitted.snapshot();
            check(s.direction() == direction && s.committedFloor().orElseThrow() == frontier, "Admitida durante el compromiso original.");
            check(direction == UP ? s.currentFloor() < request.getFloor() : s.currentFloor() > request.getFloor(),
                    "La prueba insertó la llamada antes de pasar por el piso; no aceptar una ventana perdida.");
        }
        private void assertServed(HallRequest request) throws Exception {
            long started = System.nanoTime();
            while (manager.call(request.getId()).map(s -> s.status() != HallCallSnapshot.Status.SERVED).orElse(true)) {
                long remaining = LIMIT.toNanos() - (System.nanoTime() - started); check(remaining > 0, "Falta confirmación global.");
                manager.awaitUpdate(Duration.ofNanos(remaining)).orElseThrow();
            }
            var call = manager.call(request.getId()).orElseThrow();
            check(call.attempts() == 1 && call.command().orElseThrow().elevatorId() == 1, "Una asignación a la cabina disponible.");
            long completed = trace.stream().filter(e -> e.type() == COMMAND_COMPLETED
                    && requestOf(e).map(r -> r.getId().equals(request.getId())).orElse(false)).count();
            check(completed == 1, "Una finalización correlacionada por llamada.");
        }
        private void verify() {
            ExecutionEvent previous = null; ExecutionEvent leg = null; ExecutionEvent stop = null;
            var completed = new HashSet<Long>();
            for (var event : trace) {
                var s = event.snapshot();
                if (previous != null) {
                    check(event.sequence() == previous.sequence() + 1, "Secuencia local consecutiva.");
                    check(event.referenceNanos() - previous.referenceNanos() >= 0, "Tiempo monotónico.");
                }
                if (event.type() == DEPARTED) {
                    check(leg == null && stop == null && s.phase() == ElevatorPhase.MOVING, "Salida sin parada/tramo solapado.");
                    check(s.nextFloor().orElseThrow() == s.currentFloor() + s.direction().floorDelta(), "Movimiento adyacente."); leg = event;
                } else if (event.type() == ARRIVED) {
                    check(leg != null && s.currentFloor() == leg.snapshot().nextFloor().orElseThrow(), "Llegada corresponde al tramo.");
                    check(event.referenceNanos() - leg.referenceNanos() >= 200 * MS, "No acorta movimiento."); leg = null;
                } else if (event.type() == STOP_STARTED) {
                    check(leg == null && stop == null && s.phase() == ElevatorPhase.DWELLING, "Parada alineada."); stop = event;
                } else if (event.type() == STOP_ENDED) {
                    check(stop != null && s.currentFloor() == stop.snapshot().currentFloor(), "Termina la misma parada.");
                    check(event.referenceNanos() - stop.referenceNanos() >= 40 * MS, "No acorta espera."); stop = null;
                } else if (event.type() == COMMAND_COMPLETED) {
                    var request = requestOf(event).orElseThrow();
                    check(s.phase() == ElevatorPhase.DWELLING && s.currentFloor() == request.getFloor(), "Servicio en piso compatible.");
                    if (request instanceof HallRequest hall) { check(s.direction() == hall.getDirection(), "Servicio con sentido pedido."); }
                    check(event.canonicalRequestId().isPresent() && completed.add(event.command().orElseThrow().commandId()), "Correlación y finalización única.");
                }
                previous = event;
            }
            check(leg == null && stop == null, "La prueba terminó recorridos y paradas.");
        }
        @Override public void close() throws Exception {
            manager.close(); worker.close();
            check(manager.awaitTermination(LIMIT) && worker.awaitTermination(LIMIT), "Ambos actores terminan.");
            check(manager.failure().isEmpty() && worker.failure().isEmpty(), "Sin fallos de actores.");
            while (true) { var event = worker.awaitEvent(Duration.ZERO); if (event.isEmpty()) { break; } trace.add(event.orElseThrow()); }
            System.out.println("T33_TRACE scenario=" + name);
            for (var event : trace) {
                var s = event.snapshot();
                System.out.printf(Locale.ROOT, "seq=%d t_ms=%.3f type=%s floor=%d next=%s direction=%s phase=%s command=%s request=%s%n",
                        event.sequence(), (event.referenceNanos() - origin) / 1_000_000.0, event.type(), s.currentFloor(),
                        s.nextFloor().isPresent() ? s.nextFloor().getAsInt() : "-", s.direction(), s.phase(),
                        event.command().map(c -> Long.toString(c.commandId())).orElse("-"),
                        requestOf(event).map(r -> r.getId().toString()).orElse("-"));
            }
        }
    }

    private static Optional<Request> requestOf(ExecutionEvent event) {
        var command = event.command().orElse(null);
        if (command instanceof Command.Pickup pickup) { return Optional.of(pickup.request()); }
        if (command instanceof Command.Destination destination) { return Optional.of(destination.request()); }
        return Optional.empty();
    }
    private static CabinRequest cabin(int floor) { return new CabinRequest(UUID.randomUUID(), 0, floor, Instant.EPOCH, 1); }
    private static HallRequest hall(int floor, Direction direction) { return new HallRequest(UUID.randomUUID(), 0, floor, Instant.EPOCH, direction); }
    private static void check(boolean value, String message) { if (!value) { throw new AssertionError(message); } }
    private static void expect(Class<? extends Throwable> type, Runnable action) {
        try { action.run(); } catch (Throwable e) { if (type.isInstance(e)) { return; } throw new AssertionError(e); }
        throw new AssertionError("Falta excepción " + type.getSimpleName());
    }
}
