package com.elevatormanager.dispatch;

import com.elevatormanager.model.Direction;
import com.elevatormanager.model.ElevatorPhase;
import com.elevatormanager.model.ElevatorSnapshot;
import com.elevatormanager.model.ElevatorState;
import com.elevatormanager.model.SimulationConfig;
import com.elevatormanager.model.request.CabinRequest;
import com.elevatormanager.model.request.HallRequest;
import com.elevatormanager.protocol.Command;
import com.elevatormanager.simulation.ElevatorWorker;
import com.elevatormanager.simulation.ExecutionEvent;
import com.elevatormanager.simulation.PlanningSnapshot;
import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/** T31: clasificación determinista y carreras controladas con trabajadores reales. */
public final class ElevatorSelectionScenarios {
    private static final ElevatorSelector SELECTOR = new ElevatorSelector();
    private static final Duration LIMIT = Duration.ofSeconds(5);
    private static final long MS = 1_000_000L;
    private ElevatorSelectionScenarios() { }

    public static void smallerEtaWinsRegardlessOfId() {
        var config = config(2);
        var a = state(1, config, 1); var b = state(2, config, 3);
        var ranking = rank(config, hall(4, Direction.UP), candidate(a), candidate(b));
        check(ranking.choices().get(0).elevatorId() == 2, "ETA menor aunque el ID sea mayor.");
        check(ranking.choices().get(0).estimate().pickupNanos().orElseThrow().equals(BigInteger.valueOf(100 * MS)), "ETA=100ms.");
    }

    public static void equalCostsUseLowestIdIndependentOfInputOrder() {
        var config = config(3);
        var candidates = List.of(candidate(state(3, config, 1)), candidate(state(1, config, 1)), candidate(state(2, config, 1)));
        var first = SELECTOR.rank(config, hall(3, Direction.UP), candidates, 0);
        var reversed = List.of(candidates.get(2), candidates.get(1), candidates.get(0));
        check(first.equals(SELECTOR.rank(config, hall(3, Direction.UP), reversed, 0)), "Entrada estable: ranking idéntico.");
        check(first.choices().stream().map(ElevatorSelector.Choice::elevatorId).toList().equals(List.of(1, 2, 3)), "Desempate ascendente.");
    }

    public static void nearbyBusyCabinLosesToFartherIdleCabin() {
        var config = config(2); var near = state(1, config, 2); var far = state(2, config, 1);
        near.addPendingRequest(cabin(1, 5)); near.beginJourney(5, 0);
        var ranking = SELECTOR.rank(config, hall(3, Direction.DOWN), List.of(candidate(near), candidate(far)), 40 * MS);
        check(ranking.choices().get(0).elevatorId() == 2, "Libre más lejana gana.");
        check(ranking.choices().get(0).estimate().pickupNanos().orElseThrow().equals(BigInteger.valueOf(200 * MS)), "Lejana=200ms.");
        check(ranking.choices().get(1).estimate().pickupNanos().orElseThrow().equals(BigInteger.valueOf(510 * MS)), "Cercana=510ms.");
    }

    public static void lifecycleExcludesEvenCheapCabins() {
        var config = config(5); var candidates = new ArrayList<ElevatorSelector.Candidate>();
        var states = List.of(ElevatorWorker.Lifecycle.NEW, ElevatorWorker.Lifecycle.CLOSING,
                ElevatorWorker.Lifecycle.TERMINATED, ElevatorWorker.Lifecycle.FAILED, ElevatorWorker.Lifecycle.RUNNING);
        for (int i = 1; i <= 5; i++) { candidates.add(new ElevatorSelector.Candidate(capture(state(i, config, 1)), states.get(i - 1))); }
        var ranking = SELECTOR.rank(config, hall(1, Direction.UP), candidates, 0);
        check(ranking.choices().size() == 1 && ranking.choices().get(0).elevatorId() == 5, "Solo RUNNING elegible.");
        check(ranking.excluded().size() == 4 && ranking.excluded().stream().allMatch(e -> e.reason() == ElevatorSelector.Reason.NOT_RUNNING), "Motivos explícitos.");
    }

    public static void otherEpochAndConfigurationAreExcluded() {
        var config = config(3);
        var epoch = new ElevatorState(1, config, 1);
        var foreignConfig = new ElevatorState(2, new SimulationConfig(3, 9, 100, 50), 0);
        var ranking = rank(config, hall(2, Direction.UP), candidate(epoch), candidate(foreignConfig), candidate(state(3, config, 1)));
        check(ranking.choices().get(0).elevatorId() == 3 && ranking.excluded().size() == 2, "Solo sesión compatible.");
        check(ranking.excluded().get(0).reason() == ElevatorSelector.Reason.OTHER_EPOCH
                && ranking.excluded().get(1).reason() == ElevatorSelector.Reason.OTHER_CONFIGURATION, "Motivos de exclusión.");
    }

    public static void heldResetAndTerminatedPhasesAreExcluded() {
        var config = config(3); var candidates = new ArrayList<ElevatorSelector.Candidate>();
        for (int i = 1; i <= 3; i++) {
            var s = state(i, config, 1).snapshot(0);
            ElevatorSnapshot altered;
            if (i == 1) {
                altered = new ElevatorSnapshot(i, config, 0, 0, 1, s.nextFloor(), Direction.UP, ElevatorPhase.HELD,
                        Optional.of(ElevatorPhase.DWELLING), Optional.of(ElevatorSnapshot.HoldReason.PAUSE),
                        ElevatorSnapshot.Mode.NORMAL, 50 * MS, 30 * MS, 0, s.committedFloor(), List.of(), List.of());
            } else {
                altered = new ElevatorSnapshot(i, config, 0, 0, 1, s.nextFloor(), Direction.UP,
                        i == 2 ? ElevatorPhase.IDLE : ElevatorPhase.TERMINATED, Optional.empty(), Optional.empty(),
                        i == 2 ? ElevatorSnapshot.Mode.RESET_RETURN : ElevatorSnapshot.Mode.NORMAL,
                        0, 0, 0, s.committedFloor(), List.of(), List.of());
            }
            candidates.add(new ElevatorSelector.Candidate(new PlanningSnapshot(altered, List.of(), Optional.empty()), ElevatorWorker.Lifecycle.RUNNING));
        }
        var ranking = SELECTOR.rank(config, hall(2, Direction.UP), candidates, 0);
        check(ranking.choices().isEmpty() && ranking.excluded().size() == 3, "Ningún estado físico no disponible recibe costo.");
    }

    public static void costsBeyondLongAreComparedExactly() {
        var config = new SimulationConfig(2, Integer.MAX_VALUE, SimulationConfig.MAX_TIME_MILLIS, 50);
        var a = state(1, config, 1);
        // Publicar en piso 2 sin recorrer una duración enorme en tiempo real.
        var b = state(2, config, 1); b.beginJourney(2, 0);
        b.completeLegIfDue(config.movementTimeMillis() * MS);
        var ranking = SELECTOR.rank(config, hall(Integer.MAX_VALUE, Direction.DOWN),
                List.of(candidate(a), candidate(b)), 0);
        var winner = ranking.choices().get(0);
        check(winner.elevatorId() == 2 && winner.estimate().pickupNanos().orElseThrow().compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0,
                "Sin saturar dos costos mayores que long.");
    }

    public static void commonObservationTimeAgesActivePhases() {
        var config = config(2);
        var moving = state(2, config, 1); moving.addPendingRequest(cabin(2, 4)); moving.beginJourney(4, 0);
        var ranking = SELECTOR.rank(config, hall(2, Direction.UP),
                List.of(candidate(state(1, config, 1)), candidate(moving)), 80 * MS);
        check(ranking.choices().get(0).elevatorId() == 2, "La fase activa conserva solo 20 ms frente a 100 de la libre.");
        check(ranking.choices().get(0).estimate().pickupNanos().orElseThrow().equals(BigInteger.valueOf(20 * MS)), "Referencia común.");
    }

    public static void emptyRankingInvalidCallsAndDuplicateIds() {
        var config = config(2); var request = hall(2, Direction.UP);
        check(SELECTOR.rank(config, request, List.of(), 0).choices().isEmpty(), "Sin candidatas no hay ganador.");
        var a = candidate(state(1, config, 1));
        expect(IllegalArgumentException.class, () -> SELECTOR.rank(config, request, List.of(a, a), 0));
        expect(IllegalArgumentException.class, () -> SELECTOR.rank(config, hall(8, Direction.UP), List.of(a), 0));
    }

    public static void rankingAndSourceAreImmutable() {
        var config = config(1); var a = candidate(state(1, config, 1)); var request = hall(3, Direction.UP);
        var first = SELECTOR.rank(config, request, List.of(a), 0);
        check(first.equals(SELECTOR.rank(config, request, List.of(a), 0)), "Selector puro y determinista.");
        expect(UnsupportedOperationException.class, () -> first.choices().clear());
        expect(UnsupportedOperationException.class, () -> first.excluded().clear());
        check(a.planning().admissionOrder().isEmpty(), "No inserta en la agenda original.");
    }

    public static void conditionalSubmissionRejectsStaleCapture() throws Exception {
        try (var rig = new Rig(1)) {
            var worker = rig.elevators.get(0); var old = worker.planningSnapshot();
            var first = new Command.Destination(rig.ids.incrementAndGet(), 1, cabin(1, 1));
            check(worker.submit(first), "Publica un estado nuevo.");
            worker.awaitResult(LIMIT).orElseThrow();
            var staleCommand = new Command.Pickup(rig.ids.incrementAndGet(), 1, hall(2, Direction.UP));
            check(worker.submitIfCurrent(staleCommand, old) == ElevatorWorker.Submission.STALE, "Captura antigua no encola.");
            check(worker.awaitResult(Duration.ZERO).isEmpty(), "El rechazo no crea admisión fantasma.");
        }
    }

    public static void conditionalSubmissionEnqueuesOnceAndDetectsClosure() throws Exception {
        try (var rig = new Rig(1)) {
            var worker = rig.elevators.get(0);
            var command = new Command.Pickup(rig.ids.incrementAndGet(), 1, hall(2, Direction.UP));
            check(worker.submitIfCurrent(command, worker.planningSnapshot()) == ElevatorWorker.Submission.ENQUEUED, "Captura vigente encola.");
            check(worker.awaitResult(LIMIT).orElseThrow().command().sameContent(command), "Una admisión del mismo comando.");
            check(worker.awaitResult(Duration.ZERO).isEmpty(), "Sin segunda admisión.");
            var capture = worker.planningSnapshot(); worker.close(); check(worker.awaitTermination(LIMIT), "Cabina cerrada.");
            check(worker.submitIfCurrent(command, capture) == ElevatorWorker.Submission.UNAVAILABLE, "No encola al cerrar.");
        }
    }

    public static void managerActuallyDispatchesToLowerEta() throws Exception {
        try (var rig = new Rig(2)) {
            rig.moveTo(2, 3);
            try (var manager = rig.manager(rig.ids::incrementAndGet)) {
                manager.start(); manager.submit(hall(4, Direction.UP));
                var result = manager.awaitResult(LIMIT).orElseThrow();
                check(result.command().orElseThrow().elevatorId() == 2, "Manejador usa ETA, no cursor circular.");
                check(result.detail().contains("ETA="), "Decisión explica costo.");
                check(rig.elevators.get(1).awaitResult(LIMIT).orElseThrow().outcome() == ElevatorWorker.Outcome.ACCEPTED,
                        "Comando realmente admitido en ganadora.");
                manager.close(); check(manager.awaitTermination(LIMIT), "Manejador termina.");
            }
        }
    }

    public static void managerTieUsesIdWithReversedList() throws Exception {
        try (var rig = new Rig(3); var manager = rig.manager(rig.ids::incrementAndGet)) {
            manager.start(); manager.submit(hall(1, Direction.UP));
            var result = manager.awaitResult(LIMIT).orElseThrow();
            check(result.command().orElseThrow().elevatorId() == 1, "Desempata por ID pese a lista invertida.");
            manager.close(); check(manager.awaitTermination(LIMIT), "Manejador termina.");
        }
    }

    public static void winnerClosingBeforeSendTriggersReselection() throws Exception {
        try (var rig = new Rig(2)) {
            var generated = new AtomicLong();
            try (var manager = rig.manager(() -> {
                generated.incrementAndGet(); rig.elevators.get(0).close(); return rig.ids.incrementAndGet();
            })) {
                manager.start(); manager.submit(hall(1, Direction.UP));
                var result = manager.awaitResult(LIMIT).orElseThrow();
                check(result.command().orElseThrow().elevatorId() == 2, "No usa ganadora cerrada durante decisión.");
                check(generated.get() == 1, "Reintento sin encolar reutiliza ID reservado.");
                check(rig.elevators.get(0).awaitResult(Duration.ZERO).isEmpty(), "Cabina cerrada no recibió recogida.");
                manager.close(); check(manager.awaitTermination(LIMIT), "Manejador termina.");
            }
        }
    }

    public static void changedWinnerIsReestimatedRatherThanJustSkipped() throws Exception {
        try (var rig = new Rig(2)) {
            try (var manager = rig.manager(() -> {
                long id = rig.ids.incrementAndGet();
                try { rig.moveTo(1, 3); } catch (Exception e) { throw new IllegalStateException(e); }
                return id;
            })) {
                manager.start(); manager.submit(hall(3, Direction.UP));
                var result = manager.awaitResult(LIMIT).orElseThrow();
                check(result.command().orElseThrow().elevatorId() == 1, "Ganadora modificada sigue siendo la mejor tras recalcular.");
                check(result.detail().contains("ETA=0 ns"), "ETA actualizada a cero, no costo anterior.");
                manager.close(); check(manager.awaitTermination(LIMIT), "Manejador termina.");
            }
        }
    }

    public static void previouslyLosingCabinIsRevalidatedToo() throws Exception {
        try (var rig = new Rig(2)) {
            rig.moveTo(1, 2); rig.moveTo(2, 5);
            try (var manager = rig.manager(() -> {
                long id = rig.ids.incrementAndGet();
                try { rig.moveTo(2, 3); } catch (Exception e) { throw new IllegalStateException(e); }
                return id;
            })) {
                manager.start(); manager.submit(hall(3, Direction.UP));
                var result = manager.awaitResult(LIMIT).orElseThrow();
                check(result.command().orElseThrow().elevatorId() == 2, "Reevalúa la perdedora que mejoró.");
                manager.close(); check(manager.awaitTermination(LIMIT), "Manejador termina.");
            }
        }
    }

    public static void allCabinsClosingBeforeSendRejectsWithoutDispatch() throws Exception {
        try (var rig = new Rig(2)) {
            try (var manager = rig.manager(() -> {
                for (var e : rig.elevators) { e.close(); }
                return rig.ids.incrementAndGet();
            })) {
                manager.start(); manager.submit(hall(2, Direction.UP));
                var result = manager.awaitResult(LIMIT).orElseThrow();
                check(result.outcome() == ManagerWorker.Outcome.PENDING && result.command().isEmpty(), "Llamada conservada sin envío.");
                for (var e : rig.elevators) { check(e.awaitResult(Duration.ZERO).isEmpty(), "Sin mensajes en cabinas cerradas."); }
                manager.close(); check(manager.awaitTermination(LIMIT), "Manejador termina.");
            }
        }
    }

    public static void main(String[] args) throws Exception {
        smallerEtaWinsRegardlessOfId(); equalCostsUseLowestIdIndependentOfInputOrder(); nearbyBusyCabinLosesToFartherIdleCabin();
        lifecycleExcludesEvenCheapCabins(); otherEpochAndConfigurationAreExcluded(); heldResetAndTerminatedPhasesAreExcluded();
        costsBeyondLongAreComparedExactly(); commonObservationTimeAgesActivePhases(); emptyRankingInvalidCallsAndDuplicateIds();
        rankingAndSourceAreImmutable(); conditionalSubmissionRejectsStaleCapture(); conditionalSubmissionEnqueuesOnceAndDetectsClosure();
        managerActuallyDispatchesToLowerEta(); managerTieUsesIdWithReversedList(); winnerClosingBeforeSendTriggersReselection();
        changedWinnerIsReestimatedRatherThanJustSkipped(); previouslyLosingCabinIsRevalidatedToo(); allCabinsClosingBeforeSendRejectsWithoutDispatch();
        System.out.println("T31: 18 escenarios satisfactorios; selección por ETA, desempate y revalidación concurrente.");
    }

    private static SimulationConfig config(int count) { return new SimulationConfig(count, 8, 100, 50); }
    private static ElevatorSelector.Ranking rank(SimulationConfig config, HallRequest request, ElevatorSelector.Candidate... candidates) {
        return SELECTOR.rank(config, request, List.of(candidates), 0);
    }
    private static ElevatorState state(int id, SimulationConfig config, int floor) {
        var state = new ElevatorState(id, config, 0);
        long now = 0;
        if (state.beginJourney(floor, now)) {
            while (true) {
                now += config.movementTimeMillis() * MS; state.completeLegIfDue(now);
                if (state.getCommittedFloor().isEmpty()) { break; }
                state.beginNextLeg(now);
            }
        }
        return state;
    }
    private static PlanningSnapshot capture(ElevatorState state) {
        return new PlanningSnapshot(state.snapshot(0), state.getPendingRequestsInAdmissionOrder(), Optional.empty());
    }
    private static ElevatorSelector.Candidate candidate(ElevatorState state) {
        return new ElevatorSelector.Candidate(capture(state), ElevatorWorker.Lifecycle.RUNNING);
    }
    private static HallRequest hall(int floor, Direction direction) {
        return new HallRequest(UUID.randomUUID(), 0, floor, Instant.EPOCH, direction);
    }
    private static CabinRequest cabin(int id, int floor) {
        return new CabinRequest(UUID.randomUUID(), 0, floor, Instant.EPOCH, id);
    }
    private static void check(boolean value, String message) { if (!value) { throw new AssertionError(message); } }
    private static void expect(Class<? extends Throwable> type, Runnable action) {
        try { action.run(); } catch (Throwable e) { if (type.isInstance(e)) { return; } throw new AssertionError(e); }
        throw new AssertionError("Falta excepción " + type.getSimpleName());
    }

    private static final class Rig implements AutoCloseable {
        private final SimulationConfig config;
        private final List<ElevatorWorker> elevators = new ArrayList<>();
        private final AtomicLong ids = new AtomicLong();
        private Rig(int count) {
            config = config(count);
            for (int i = 1; i <= count; i++) {
                var worker = new ElevatorWorker(i, config, 0); worker.start(); elevators.add(worker);
            }
        }
        private ManagerWorker manager(LongSupplier sequence) {
            var reversed = new ArrayList<ElevatorWorker>();
            for (int i = elevators.size() - 1; i >= 0; i--) { reversed.add(elevators.get(i)); }
            return new ManagerWorker(config, 0, reversed, sequence);
        }
        private void moveTo(int id, int floor) throws Exception {
            var worker = elevators.get(id - 1);
            long commandId = ids.incrementAndGet();
            check(worker.submit(new Command.Destination(commandId, id, cabin(id, floor))), "Destino auxiliar enviado.");
            check(worker.awaitResult(LIMIT).orElseThrow().outcome() == ElevatorWorker.Outcome.ACCEPTED, "Destino auxiliar admitido.");
            long started = System.nanoTime(); boolean served = false;
            while (true) {
                long remaining = LIMIT.toNanos() - (System.nanoTime() - started);
                check(remaining > 0, "Plazo auxiliar.");
                var event = worker.awaitEvent(Duration.ofNanos(remaining)).orElseThrow();
                check(event.type() != ExecutionEvent.Type.WORKER_FAILED, "Cabina sana.");
                if (event.type() == ExecutionEvent.Type.COMMAND_COMPLETED
                        && event.command().orElseThrow().commandId() == commandId) { served = true; }
                if (served && event.type() == ExecutionEvent.Type.STOP_ENDED) { return; }
            }
        }
        @Override public void close() throws Exception {
            for (var worker : elevators) { worker.close(); }
            for (var worker : elevators) { check(worker.awaitTermination(LIMIT) && worker.failure().isEmpty(), "Cabina terminada sin fallo."); }
        }
    }
}
