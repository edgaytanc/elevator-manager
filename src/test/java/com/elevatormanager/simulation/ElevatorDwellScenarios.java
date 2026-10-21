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
import java.util.List;
import java.util.UUID;

/** Escenarios deterministas de parada e integración con el hilo real. */
public final class ElevatorDwellScenarios {
    private static final long MOVE = 100_000_000L;
    private static final long DWELL = 200_000_000L;
    private static final Duration LIMIT = Duration.ofSeconds(5);
    private ElevatorDwellScenarios() { }

    public static void cabinServedAtStopStart() {
        var state = state();
        var request = cabin(1);
        state.addPendingRequest(request);
        var before = state.snapshot(0);
        var served = state.beginDwell(Direction.UP, 0);
        check(served.equals(List.of(request)), "Identidad atendida correcta.");
        check(state.getCabinRequests().isEmpty(), "Luz apagada al iniciar servicio.");
        check(before.cabinRequests().size() == 1, "Snapshot anterior intacto.");
        var snapshot = state.snapshot(0);
        check(snapshot.phase() == ElevatorPhase.DWELLING && snapshot.nextFloor().isEmpty(),
                "Parada alineada, sin siguiente tramo.");
        check(snapshot.remainingNanos() == DWELL, "Duración completa configurada.");
        reject(UnsupportedOperationException.class, served::clear);
    }

    public static void oppositeHallRemainsPending() {
        var state = atThirdFloor();
        var up = hall(3, Direction.UP);
        var down = hall(3, Direction.DOWN);
        state.addPendingRequest(up); state.addPendingRequest(down);
        check(state.beginDwell(Direction.UP, 2 * MOVE).equals(List.of(up)), "Solo recoge UP.");
        check(state.getHallRequests().equals(List.of(down)), "DOWN permanece pendiente.");
    }

    public static void compatibleRequestsShareOneStop() {
        var state = atThirdFloor();
        var cabin = cabin(3);
        var up = hall(3, Direction.UP);
        var other = cabin(4);
        state.addPendingRequest(cabin); state.addPendingRequest(up); state.addPendingRequest(other);
        check(state.beginDwell(Direction.UP, 2 * MOVE).size() == 2, "Servicio combinado.");
        check(state.getCabinRequests().equals(List.of(other)), "Otro piso no se atiende.");
        check(state.snapshot(2 * MOVE).phaseDurationNanos() == DWELL, "Una sola duración.");
    }

    public static void noServiceDoesNotOpenStop() {
        var state = atThirdFloor();
        state.addPendingRequest(hall(3, Direction.DOWN));
        long revision = state.getRevision();
        check(state.beginDwell(Direction.UP, 2 * MOVE).isEmpty(), "Sin servicio compatible.");
        check(state.getPhase() == ElevatorPhase.IDLE && state.getRevision() == revision,
                "No abre parada vacía ni modifica revisión.");
        check(state.getDirection() == Direction.UP, "No cambia sentido sin servicio.");
    }

    public static void dwellCompletesOnceAtDeadline() {
        var state = state();
        state.addPendingRequest(cabin(1)); state.beginDwell(Direction.UP, 0);
        check(!state.completeDwellIfDue(DWELL - 1), "No termina anticipadamente.");
        check(state.getPhase() == ElevatorPhase.DWELLING, "Conserva parada.");
        check(state.completeDwellIfDue(DWELL), "Termina al vencer.");
        check(!state.completeDwellIfDue(DWELL), "No termina dos veces.");
        check(state.getPhase() == ElevatorPhase.IDLE && state.getCurrentFloor() == 1,
                "Fin de parada no desplaza.");
    }

    public static void compatibleArrivalDoesNotRestartTimer() {
        var state = state();
        state.addPendingRequest(cabin(1)); state.beginDwell(Direction.UP, 0);
        var next = hall(1, Direction.UP);
        state.addPendingRequest(next);
        check(state.serveDuringDwell(DWELL / 2).equals(List.of(next)), "Incorpora compatible.");
        check(state.dwellRemainingNanos(DWELL / 2) == DWELL / 2, "No reinicia reloj.");
        check(state.completeDwellIfDue(DWELL), "Vence en el plazo original.");
        check(state.serveDuringDwell(DWELL).isEmpty(), "No repite servicio.");
    }

    public static void oppositeDirectionRequiresAnotherStop() {
        var state = atThirdFloor();
        state.addPendingRequest(hall(3, Direction.UP));
        state.beginDwell(Direction.UP, 2 * MOVE);
        var down = hall(3, Direction.DOWN);
        state.addPendingRequest(down);
        check(state.serveDuringDwell(2 * MOVE + DWELL / 2).isEmpty(), "No mezcla sentidos.");
        reject(IllegalStateException.class, () -> state.beginDwell(Direction.DOWN, 2 * MOVE));
        state.completeDwellIfDue(2 * MOVE + DWELL);
        check(state.beginDwell(Direction.DOWN, 2 * MOVE + DWELL).equals(List.of(down)),
                "Abre otra parada con sentido DOWN.");
        check(state.dwellRemainingNanos(2 * MOVE + DWELL) == DWELL, "Nueva duración completa.");
    }

    public static void expiredStopCannotAcceptLateService() {
        var state = state();
        state.addPendingRequest(cabin(1)); state.beginDwell(Direction.UP, 0);
        var late = cabin(1);
        state.addPendingRequest(late);
        check(state.serveDuringDwell(DWELL).isEmpty(), "No sirve dentro de una parada vencida.");
        check(state.getCabinRequests().equals(List.of(late)), "Conserva solicitud tardía.");
        state.completeDwellIfDue(DWELL);
        check(state.beginDwell(Direction.UP, DWELL).equals(List.of(late)), "Nueva parada válida.");
        check(state.dwellRemainingNanos(DWELL) == DWELL, "No hereda duración agotada.");
    }

    public static void motionAndCommittedDirectionAreProtected() {
        var state = state();
        state.addPendingRequest(cabin(2));
        state.beginJourney(4, 0);
        reject(IllegalStateException.class, () -> state.beginDwell(Direction.UP, 0));
        state.completeLegIfDue(MOVE);
        reject(IllegalStateException.class, () -> state.beginDwell(Direction.DOWN, MOVE));
        state.beginDwell(Direction.UP, MOVE);
        check(state.getCommittedFloor().orElseThrow() == 4, "Parada conserva compromiso.");
        reject(IllegalStateException.class, () -> state.beginNextLeg(MOVE));
        reject(IllegalStateException.class, () -> state.beginJourney(1, MOVE));
        state.completeDwellIfDue(MOVE + DWELL);
        state.beginNextLeg(MOVE + DWELL);
        check(state.snapshot(MOVE + DWELL).nextFloor().orElseThrow() == 3,
                "Después del servicio continúa el tramo comprometido.");
    }

    public static void dwellSupportsClockWrapAndMaximum() {
        var state = state();
        long start = Long.MAX_VALUE - DWELL / 2;
        state.addPendingRequest(cabin(1)); state.beginDwell(Direction.UP, start);
        check(state.completeDwellIfDue(start + DWELL), "Wrap monotónico correcto.");
        var maximum = new ElevatorState(1,
                new SimulationConfig(1, 1, 1, SimulationConfig.MAX_TIME_MILLIS), 0);
        maximum.addPendingRequest(cabin(1)); maximum.beginDwell(Direction.UP, 0);
        long duration = SimulationConfig.MAX_TIME_MILLIS * 1_000_000L;
        check(maximum.snapshot(0).remainingNanos() == duration, "Máximo exacto en snapshot.");
        check(!maximum.completeDwellIfDue(duration - 1), "No vence antes del máximo.");
        check(maximum.completeDwellIfDue(duration), "Vence sin overflow.");
    }

    public static void workerApproachesAndServesRequestedDirection() throws Exception {
        var worker = worker(20, 60_000);
        try {
            worker.start();
            var down = hall(3, Direction.DOWN);
            send(worker, new Command.Pickup(1, 1, down));
            var service = service(worker);
            check(service.floor() == 3 && service.direction() == Direction.DOWN,
                    "Sube para aproximarse y recoge en DOWN al terminar compromiso.");
            check(service.requests().equals(List.of(down)) && service.openedStop(),
                    "Evento identifica solicitud servida.");
            check(worker.snapshot().hallRequests().isEmpty(), "Llamada atendida retirada.");
            reject(UnsupportedOperationException.class, service.requests()::clear);
        } finally { close(worker); }
    }

    public static void workerSeparatesOppositeServices() throws Exception {
        var worker = worker(200, 40);
        try {
            worker.start();
            var up = hall(3, Direction.UP);
            var down = hall(3, Direction.DOWN);
            send(worker, new Command.Pickup(1, 1, up));
            send(worker, new Command.Pickup(2, 1, down));
            var first = service(worker);
            var second = service(worker);
            check(first.direction() == Direction.UP && first.requests().equals(List.of(up)),
                    "Primer servicio UP.");
            check(second.direction() == Direction.DOWN && second.requests().equals(List.of(down)),
                    "Segundo servicio DOWN, con otra parada.");
            check(first.openedStop() && second.openedStop(), "Dos aperturas distintas.");
            check(second.referenceNanos() - first.referenceNanos() >= 40_000_000L,
                    "La primera parada consume su duración antes de invertir.");
        } finally { close(worker); }
    }

    public static void workerJoinsOpenStopWithoutNewOpening() throws Exception {
        var worker = worker(20, 60_000);
        try {
            worker.start(); send(worker, new Command.Destination(1, 1, cabin(1)));
            var first = service(worker);
            var up = hall(1, Direction.UP);
            send(worker, new Command.Pickup(2, 1, up));
            var joined = service(worker);
            check(first.openedStop() && !joined.openedStop(), "Se incorpora sin abrir otra parada.");
            check(joined.requests().equals(List.of(up)), "Identidad incorporada.");
            var snapshot = worker.snapshot();
            check(snapshot.currentFloor() == 1 && snapshot.phase() == ElevatorPhase.DWELLING,
                    "Servicio en piso actual sin desplazamiento.");
            check(snapshot.remainingNanos() <= 60_000_000_000L, "Restante acotado.");
            check(joined.elevatorId() == 1 && joined.epoch() == 0
                    && joined.revision() > first.revision(), "Evento correlacionable por cabina y época.");
        } finally { close(worker); }
    }

    public static void workerContinuesAfterFullDwell() throws Exception {
        var worker = worker(20, 30);
        try {
            worker.start();
            send(worker, new Command.Destination(1, 1, cabin(2)));
            send(worker, new Command.Destination(2, 1, cabin(3)));
            var first = service(worker);
            var second = service(worker);
            check(first.floor() == 2 && second.floor() == 3, "Continúa con el siguiente pendiente.");
            check(second.referenceNanos() - first.referenceNanos() >= 50_000_000L,
                    "Espera parada completa y luego traslado completo.");
            check(worker.awaitService(Duration.ZERO).isEmpty(), "Sin repetición inmediata de servicio.");
        } finally { close(worker); }
    }

    public static void closeWakesDwellWait() throws Exception {
        var worker = worker(20, 60_000);
        worker.start();
        try {
            send(worker, new Command.Destination(1, 1, cabin(1)));
            service(worker);
        } finally { close(worker); }
        check(worker.failure().isEmpty() && worker.snapshot().currentFloor() == 1,
                "Cierra durante parada sin inventar movimiento ni fallo.");
    }

    public static void main(String[] args) throws Exception {
        cabinServedAtStopStart(); oppositeHallRemainsPending(); compatibleRequestsShareOneStop();
        noServiceDoesNotOpenStop(); dwellCompletesOnceAtDeadline();
        compatibleArrivalDoesNotRestartTimer(); oppositeDirectionRequiresAnotherStop();
        expiredStopCannotAcceptLateService(); motionAndCommittedDirectionAreProtected();
        dwellSupportsClockWrapAndMaximum(); workerApproachesAndServesRequestedDirection();
        workerSeparatesOppositeServices(); workerJoinsOpenStopWithoutNewOpening();
        workerContinuesAfterFullDwell(); closeWakesDwellWait();
        System.out.println("T24: 15 escenarios satisfactorios.");
    }

    private static ElevatorState state() {
        return new ElevatorState(1, new SimulationConfig(1, 5, 100, 200), 0);
    }

    private static ElevatorState atThirdFloor() {
        var state = state();
        state.beginJourney(3, 0); state.completeLegIfDue(MOVE);
        state.beginNextLeg(MOVE); state.completeLegIfDue(2 * MOVE);
        return state;
    }

    private static CabinRequest cabin(int floor) {
        return new CabinRequest(UUID.randomUUID(), 0, floor, Instant.now(), 1);
    }

    private static HallRequest hall(int floor, Direction direction) {
        return new HallRequest(UUID.randomUUID(), 0, floor, Instant.now(), direction);
    }

    private static ElevatorWorker worker(long movementMillis, long dwellMillis) {
        return new ElevatorWorker(1, new SimulationConfig(1, 5, movementMillis, dwellMillis), 0);
    }

    private static void send(ElevatorWorker worker, Command command) throws Exception {
        check(worker.submit(command), "Mensaje encolado.");
        check(worker.awaitResult(LIMIT).orElseThrow().outcome() == ElevatorWorker.Outcome.ACCEPTED,
                "Comando admitido.");
    }

    private static ElevatorWorker.Service service(ElevatorWorker worker) throws Exception {
        return worker.awaitService(LIMIT).orElseThrow(() -> new AssertionError("Falta servicio."));
    }

    private static void close(ElevatorWorker worker) throws Exception {
        worker.close(); check(worker.awaitTermination(LIMIT), "Trabajador finalizado.");
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
