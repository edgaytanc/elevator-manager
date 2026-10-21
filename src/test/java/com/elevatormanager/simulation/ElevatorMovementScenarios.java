package com.elevatormanager.simulation;

import com.elevatormanager.model.Direction;
import com.elevatormanager.model.ElevatorPhase;
import com.elevatormanager.model.ElevatorSnapshot;
import com.elevatormanager.model.ElevatorState;
import com.elevatormanager.model.SimulationConfig;
import com.elevatormanager.model.request.CabinRequest;
import com.elevatormanager.protocol.Command;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Predicate;

/** Física con reloj explícito; integración del trabajador con plazo acotado. */
public final class ElevatorMovementScenarios {
    private static final long LEG = 100_000_000L;
    private ElevatorMovementScenarios() { }

    public static void adjacentAndDeadline() {
        var state = state(5);
        check(state.beginJourney(4, 0), "Inicia recorrido.");
        var departure = state.snapshot(0);
        check(departure.currentFloor() == 1 && departure.nextFloor().orElseThrow() == 2,
                "Tramo adyacente, sin teletransporte.");
        check(!state.completeLegIfDue(LEG - 1), "No llega anticipadamente.");
        check(state.getCurrentFloor() == 1, "Conserva piso durante tránsito.");
        check(state.completeLegIfDue(LEG), "Llega al vencer plazo.");
        check(state.getCurrentFloor() == 2, "Solo un piso.");
        check(!state.completeLegIfDue(LEG), "No repite llegada.");
    }

    public static void lateWakeupDoesNotSkipFloors() {
        var state = state(5);
        state.beginJourney(5, 0);
        long late = 20 * LEG;
        state.completeLegIfDue(late);
        check(state.getCurrentFloor() == 2, "Despertar tardío confirma solo un tramo.");
        state.beginNextLeg(late);
        check(!state.completeLegIfDue(late + LEG - 1), "Siguiente tramo exige duración completa.");
        check(state.completeLegIfDue(late + LEG), "Llega después del nuevo plazo.");
        check(state.getCurrentFloor() == 3, "Piso siguiente correcto.");
    }

    public static void upwardAndDownwardJourney() {
        var state = state(4);
        state.beginJourney(4, 0);
        for (int floor = 2; floor <= 4; floor++) {
            long now = (floor - 1) * LEG;
            check(state.completeLegIfDue(now), "Llegada ascendente.");
            check(state.getCurrentFloor() == floor, "Secuencia ascendente.");
            if (floor < 4) { state.beginNextLeg(now); }
        }
        check(state.getCommittedFloor().isEmpty(), "Compromiso físico alcanzado.");
        state.beginJourney(1, 3 * LEG);
        check(state.snapshot(3 * LEG).nextFloor().orElseThrow() == 3, "Bajada adyacente.");
        for (int floor = 3; floor >= 1; floor--) {
            long now = (7 - floor) * LEG;
            check(state.completeLegIfDue(now), "Llegada descendente.");
            check(state.getCurrentFloor() == floor, "Secuencia descendente.");
            if (floor > 1) { state.beginNextLeg(now); }
        }
        check(state.getDirection() == Direction.DOWN && state.getPhase() == ElevatorPhase.IDLE,
                "IDLE conserva dirección del recorrido.");
    }

    public static void invalidTargetsAreAtomic() {
        var state = state(4);
        reject(IllegalArgumentException.class, () -> state.beginJourney(0, 0));
        reject(IllegalArgumentException.class, () -> state.beginJourney(5, 0));
        reject(IllegalStateException.class, () -> state.beginNextLeg(0));
        check(state.getRevision() == 0 && state.getCurrentFloor() == 1,
                "Rechazos no modifican estado.");
        var huge = new ElevatorState(1, new SimulationConfig(1, Integer.MAX_VALUE, 100, 1), 0);
        huge.beginJourney(Integer.MAX_VALUE, 0);
        check(huge.snapshot(0).nextFloor().orElseThrow() == 2, "Objetivo extremo sin overflow.");
    }

    public static void commitmentCannotBeReplacedMidJourney() {
        var state = state(5);
        state.beginJourney(5, 0);
        state.addPendingRequest(new CabinRequest(UUID.randomUUID(), 0, 1, Instant.now(), 1));
        check(state.movementRemainingNanos(LEG / 2) == LEG / 2,
                "Nueva solicitud no reinicia el tiempo del tramo.");
        reject(IllegalStateException.class, () -> state.beginJourney(1, 0));
        state.completeLegIfDue(LEG);
        reject(IllegalStateException.class, () -> state.beginJourney(1, LEG));
        check(state.getCommittedFloor().orElseThrow() == 5 && state.getDirection() == Direction.UP,
                "No invierte en el tramo ni entre tramos comprometidos.");
    }

    public static void snapshotsDoNotConfirmArrivals() {
        var state = state(3);
        state.beginJourney(3, 0);
        var before = state.snapshot(0);
        var halfway = state.snapshot(LEG / 2);
        check(halfway.remainingNanos() == LEG / 2 && halfway.progress() == 0.5,
                "Progreso y restante coherentes.");
        check(before.remainingNanos() == LEG, "Snapshot anterior intacto.");
        long revision = state.getRevision();
        var expired = state.snapshot(2 * LEG);
        check(expired.remainingNanos() == 0 && expired.currentFloor() == 1,
                "Consultar después del plazo no confirma llegada.");
        check(state.getRevision() == revision, "Lectura no muta revisión.");
    }

    public static void clockWrapAndMaximumDuration() {
        var state = state(3);
        long start = Long.MAX_VALUE - LEG / 2;
        state.beginJourney(2, start);
        check(state.completeLegIfDue(start + LEG), "Diferencia monotónica soporta wrap.");
        var maximum = new ElevatorState(1,
                new SimulationConfig(1, 2, SimulationConfig.MAX_TIME_MILLIS, 1), 0);
        maximum.beginJourney(2, 0);
        long duration = SimulationConfig.MAX_TIME_MILLIS * 1_000_000L;
        check(maximum.snapshot(0).remainingNanos() == duration, "Conversión máxima exacta.");
        check(!maximum.completeLegIfDue(duration - 1), "No vence un nanosegundo antes.");
        check(maximum.completeLegIfDue(duration), "Vence sin sumar un deadline desbordable.");
    }

    public static void singleFloorAndCurrentFloor() {
        var state = state(1);
        check(!state.beginJourney(1, 0), "Mismo piso no inicia tramo.");
        check(state.snapshot(0).nextFloor().isEmpty() && state.getRevision() == 0,
                "Edificio de un piso permanece alineado.");
        reject(IllegalArgumentException.class, () -> state.beginJourney(2, 0));
    }

    public static void workerMovesAndStartsService() throws Exception {
        var worker = new ElevatorWorker(1, new SimulationConfig(1, 5, 40, 10_000), 0);
        try {
            worker.start();
            long beginning = System.nanoTime();
            worker.submit(destination(1, 3));
            check(worker.awaitResult(Duration.ofSeconds(5)).orElseThrow().outcome()
                    == ElevatorWorker.Outcome.ACCEPTED, "Admite antes de atender.");
            worker.submit(destination(2, 1));
            check(worker.awaitResult(Duration.ofSeconds(5)).orElseThrow().outcome()
                    == ElevatorWorker.Outcome.ACCEPTED, "Incorpora otro pendiente durante viaje.");
            var arrived = await(worker,
                    s -> s.currentFloor() == 3 && s.phase() == ElevatorPhase.DWELLING);
            check(System.nanoTime() - beginning >= 80_000_000L, "Dos tramos completos.");
            check(arrived.cabinRequests().size() == 1
                    && arrived.cabinRequests().get(0).getFloor() == 1,
                    "Servicio retira solo el destino alcanzado; conserva el pendiente detrás.");
            check(arrived.nextFloor().isEmpty() && arrived.committedFloor().isEmpty(),
                    "Fin físico del objetivo.");
            check(arrived.direction() == Direction.UP, "Pendiente detrás no interrumpe subida.");
        } finally { close(worker); }
    }

    public static void closeWakesTimedWait() throws Exception {
        var worker = new ElevatorWorker(1, new SimulationConfig(1, 3, 60_000, 100), 0);
        try {
            worker.start(); worker.submit(destination(1, 3));
            worker.awaitResult(Duration.ofSeconds(5)).orElseThrow();
            await(worker, s -> s.phase() == ElevatorPhase.MOVING);
        } finally { close(worker); }
        check(worker.snapshot().currentFloor() == 1, "Cerrar no inventa llegada.");
        check(worker.failure().isEmpty(), "Cierre normal sin interrupción inesperada.");
    }

    public static void main(String[] args) throws Exception {
        adjacentAndDeadline(); lateWakeupDoesNotSkipFloors(); upwardAndDownwardJourney();
        invalidTargetsAreAtomic(); commitmentCannotBeReplacedMidJourney();
        snapshotsDoNotConfirmArrivals(); clockWrapAndMaximumDuration(); singleFloorAndCurrentFloor();
        workerMovesAndStartsService(); closeWakesTimedWait();
        System.out.println("T23: 10 escenarios satisfactorios.");
    }

    private static ElevatorState state(int floors) {
        return new ElevatorState(1, new SimulationConfig(1, floors, 100, 100), 0);
    }

    private static Command destination(long commandId, int floor) {
        return new Command.Destination(commandId, 1,
                new CabinRequest(UUID.randomUUID(), 0, floor, Instant.now(), 1));
    }

    private static ElevatorSnapshot await(ElevatorWorker worker, Predicate<ElevatorSnapshot> condition) {
        long beginning = System.nanoTime();
        while (System.nanoTime() - beginning < 5_000_000_000L) {
            var snapshot = worker.snapshot();
            if (condition.test(snapshot)) { return snapshot; }
            check(worker.failure().isEmpty(), "Trabajador sin fallo.");
            LockSupport.parkNanos(1_000_000L);
        }
        throw new AssertionError("No alcanzó el estado esperado dentro del plazo.");
    }

    private static void close(ElevatorWorker worker) throws Exception {
        worker.close();
        check(worker.awaitTermination(Duration.ofSeconds(5)), "Trabajador terminado.");
    }

    private static void reject(Class<? extends RuntimeException> type, Runnable action) {
        try { action.run(); }
        catch (RuntimeException exception) {
            if (type.isInstance(exception)) { return; }
            throw new AssertionError("Tipo de rechazo inesperado.", exception);
        }
        throw new AssertionError("Se esperaba rechazo.");
    }

    private static void check(boolean condition, String message) {
        if (!condition) { throw new AssertionError(message); }
    }
}
