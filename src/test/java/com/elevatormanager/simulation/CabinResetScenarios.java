package com.elevatormanager.simulation;

import com.elevatormanager.model.*;
import com.elevatormanager.model.request.CabinRequest;
import com.elevatormanager.model.request.HallRequest;
import com.elevatormanager.protocol.Command;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Predicate;
import static com.elevatormanager.simulation.ExecutionEvent.Type.*;

/** T37: reset local con reloj explícito y actores reales; no coordina reset global. */
public final class CabinResetScenarios {
    private static final Duration LIMIT = Duration.ofSeconds(8);
    private CabinResetScenarios() { }

    public static void modelPreservesPartialLeg() {
        var s = new ElevatorState(1, new SimulationConfig(1, 5, 100, 200), 0);
        s.addPendingRequest(request(1, 5, 0)); s.beginJourney(5, 0); s.beginReset(1);
        var p = s.snapshot(40_000_000L);
        check(p.currentFloor() == 1 && p.nextFloor().orElseThrow() == 2, "No teletransporta");
        check(p.remainingNanos() == 60_000_000L && p.mode() == ElevatorSnapshot.Mode.RESET_RETURN, "Restante original");
        check(p.cabinRequests().isEmpty() && p.epoch() == 1, "Agenda invalidada");
        check(!s.completeLegIfDue(99_999_999L), "No llega antes del tiempo");
        check(s.completeLegIfDue(100_000_000L), "Tramo termina en instante original");
        s.beginJourney(1, 100_000_000L); check(s.completeLegIfDue(200_000_000L), "Retorno físico");
        s.finishReset(); check(s.getCurrentFloor() == 1 && s.getDirection() == Direction.UP, "Fin en 1/UP");
    }

    public static void modelDiscardsDwell() {
        var s = modelAtDwell(); s.beginReset(1);
        check(s.getPhase() == ElevatorPhase.IDLE && s.snapshot(5).remainingNanos() == 0, "Cancela duración de parada");
        s.finishReset();
    }

    public static void modelDiscardsHeldDwell() {
        var s = modelAtDwell(); s.holdDwellForStop(1); s.beginReset(1);
        check(s.getPhase() == ElevatorPhase.IDLE, "Reset libera HELD"); s.finishReset();
    }

    public static void modelRejectsNonIncreasingEpochAtomically() {
        var s = modelAtDwell(); var before = s.snapshot(1);
        expectIllegal(() -> s.beginReset(0));
        check(s.snapshot(1).equals(before), "Época inválida sin mutación");
    }

    public static void modelDoesNotFinishBeforeFloorOne() {
        var s = new ElevatorState(1, new SimulationConfig(1, 5, 10, 10), 0);
        s.beginJourney(3, 0); s.completeLegIfDue(10_000_000L); s.beginReset(1);
        expectIllegal(s::finishReset);
        expectIllegal(() -> s.addPendingRequest(request(1, 3, 1)));
        expectIllegal(() -> s.beginDwell(Direction.UP, 10_000_000L));
    }

    public static void idleResetConfirmsAndHolds() throws Exception {
        try (var r = new Rig(5, 30, 10)) {
            var done = get(r.w.requestReset(reset(100, 1))); r.drain();
            check(done.currentFloor() == 1 && done.direction() == Direction.UP && done.epoch() == 1, "Confirmación física");
            check(r.count(RESET_STARTED) == 1 && r.count(RESET_COMPLETED) == 1 && r.count(DEPARTED) == 0, "Reset sin viaje innecesario");
            check(!r.w.submit(destination(1, 1, 2)), "Retenida después del reset");
            check(r.w.resume(), "Continuación explícita habilitada");
        }
    }

    public static void elevatedIdleReturnsThroughEveryFloor() throws Exception {
        try (var r = new Rig(5, 30, 10)) {
            r.go(5); int from = r.trace.size(); get(r.w.requestReset(reset(100, 1))); r.drain();
            check(r.floors(from).equals(List.of(4, 3, 2, 1)), "Retorno adyacente completo");
            r.noServiceAfterReset(); r.verifyPhysicalTimes();
        }
    }

    public static void upwardResetFinishesPartialThenReturns() throws Exception {
        try (var r = new Rig(5, 160, 10)) {
            r.send(destination(1, 0, 5)); r.until(DEPARTED);
            get(r.w.requestReset(reset(100, 1))); r.drain();
            check(r.floors(0).equals(List.of(2, 1)), "Completa subida iniciada y regresa");
            r.noServiceAfterReset(); r.verifyPhysicalTimes();
        }
    }

    public static void downwardResetKeepsCurrentLeg() throws Exception {
        try (var r = new Rig(5, 60, 10)) {
            r.go(5); int from = r.trace.size(); r.send(destination(2, 0, 1)); r.until(DEPARTED);
            get(r.w.requestReset(reset(100, 1))); r.drain();
            check(r.floors(from).equals(List.of(4, 3, 2, 1)), "No repite ni salta el tramo bajando");
            r.noServiceAfterReset(); r.verifyPhysicalTimes();
        }
    }

    public static void resetCancelsOpenDwellWithoutWaiting() throws Exception {
        try (var r = new Rig(5, 20, 60_000)) {
            r.send(destination(1, 0, 3)); r.until(COMMAND_COMPLETED);
            get(r.w.requestReset(reset(100, 1))); r.drain();
            check(r.count(STOP_ENDED) == 0, "No espera ni termina artificialmente la parada cancelada");
            check(r.count(COMMAND_CANCELLED) == 0 && r.count(COMMAND_COMPLETED) == 1, "No cancela ni repite servicio ya realizado");
            check(r.w.snapshot().currentFloor() == 1, "Retorno antes del minuto de parada"); r.noServiceAfterReset();
        }
    }

    public static void resetAdvancesFromStoppedCabin() throws Exception {
        try (var r = new Rig(5, 20, 10)) {
            r.go(4); r.w.requestStop().get(8, TimeUnit.SECONDS);
            check(get(r.w.requestReset(reset(100, 1))).currentFloor() == 1, "Reset avanza desde Stop");
        }
    }

    public static void resetAdvancesFromHeldDwell() throws Exception {
        try (var r = new Rig(5, 20, 60_000)) {
            r.send(destination(1, 0, 1)); r.until(COMMAND_COMPLETED);
            r.w.requestStop().get(8, TimeUnit.SECONDS);
            check(r.w.snapshot().phase() == ElevatorPhase.HELD, "Parada congelada observada");
            var s = get(r.w.requestReset(reset(100, 1)));
            check(s.phase() == ElevatorPhase.IDLE && s.holdReason().isEmpty(), "Descarta congelación");
        }
    }

    public static void resetHasPriorityOverQueuedService() throws Exception {
        try (var r = new Rig(5, 20, 10)) {
            r.w.requestStop().get(8, TimeUnit.SECONDS);
            for (int i = 1; i <= 100; i++) { check(r.w.submit(destination(i, 0, 5)), "Entrada preservada antes de reset"); }
            get(r.w.requestReset(reset(200, 1)));
            int rejected = 0, accepted = 0;
            for (int i = 0; i < 101; i++) {
                var result = r.w.awaitResult(LIMIT).orElseThrow();
                if (result.outcome() == ElevatorWorker.Outcome.REJECTED) { rejected++; }
                if (result.outcome() == ElevatorWorker.Outcome.ACCEPTED) { accepted++; }
            }
            r.drain(); check(rejected == 100 && accepted == 1, "Descarta inbox; admite solo reset");
            check(r.count(DEPARTED) == 0, "No ejecuta trabajo descartado");
        }
    }

    public static void admittedAliasesAreCancelledExplicitly() throws Exception {
        try (var r = new Rig(5, 200, 10)) {
            var first = destination(1, 0, 5); r.send(first); r.until(DEPARTED);
            r.send(destination(2, 0, 5));
            get(r.w.requestReset(reset(100, 1))); r.drain();
            var cancelled = r.trace.stream().filter(e -> e.type() == COMMAND_CANCELLED).toList();
            check(cancelled.size() == 2, "Cancela canónica y alias pendientes");
            check(cancelled.stream().allMatch(e -> e.epoch() == 0 && e.canonicalRequestId().orElseThrow().equals(first.request().getId())), "Correlación anterior intacta");
            r.noServiceAfterReset();
        }
    }

    public static void resetSupersedesPendingStop() throws Exception {
        try (var r = new Rig(5, 300, 10)) {
            r.send(destination(1, 0, 5)); r.until(DEPARTED);
            var stop = r.w.requestStop(); var reset = r.w.requestReset(reset(100, 1));
            expectFailure(stop); get(reset);
            check(r.w.snapshot().currentFloor() == 1, "Stop no impide retornar");
        }
    }

    public static void repeatedResetSharesPhysicalOperation() throws Exception {
        try (var r = new Rig(5, 120, 10)) {
            r.go(4); var command = reset(100, 1);
            var a = r.w.requestReset(command); var b = r.w.requestReset(command);
            check(get(a).equals(get(b)), "Misma confirmación para repeticiones"); r.drain();
            check(r.count(RESET_STARTED) == 1 && r.count(RESET_COMPLETED) == 1, "Una operación física");
        }
    }

    public static void completedResetReplayDoesNotResetAgain() throws Exception {
        try (var r = new Rig(5, 20, 10)) {
            var command = reset(100, 1); var done = get(r.w.requestReset(command));
            check(r.w.resume(), "Continúa"); r.send(destination(101, 1, 2)); r.until(COMMAND_COMPLETED);
            check(get(r.w.requestReset(command)).equals(done), "Replay devuelve confirmación histórica");
            r.drain(); check(r.w.snapshot().currentFloor() == 2 && r.count(RESET_STARTED) == 1, "Replay no mueve ni adopta otra época");
        }
    }

    public static void nonIncreasingResetIsRejected() throws Exception {
        try (var r = new Rig(5, 20, 10)) {
            expectFailure(r.w.requestReset(reset(100, 0))); r.drain();
            check(r.w.snapshot().epoch() == 0 && r.count(RESET_STARTED) == 0, "Reset inválido sin adopción");
        }
    }

    public static void wrongTargetIsRejected() throws Exception {
        try (var r = new Rig(5, 20, 10)) {
            expectFailure(r.w.requestReset(new Command.Reset(100, 1, 2)));
            check(r.w.snapshot().epoch() == 0, "Cabina ajena rechazada");
        }
    }

    public static void distinctResetWhileReturningIsRejected() throws Exception {
        try (var r = new Rig(5, 100, 10)) {
            r.go(5); var active = r.w.requestReset(reset(100, 1)); r.until(RESET_STARTED);
            expectFailure(r.w.requestReset(reset(101, 2)));
            check(get(active).epoch() == 1, "No reemplaza época en retorno");
        }
    }

    public static void staleServiceCannotReappearAfterReset() throws Exception {
        try (var r = new Rig(5, 20, 10)) {
            get(r.w.requestReset(reset(100, 1))); check(r.w.resume(), "Continúa");
            check(r.send(destination(1, 0, 5)).outcome() == ElevatorWorker.Outcome.REJECTED, "Servicio de época anterior rechazado");
            check(r.w.snapshot().cabinRequests().isEmpty(), "No restaura pendientes antiguos");
        }
    }

    public static void newEpochMayReuseOldServiceIdentity() throws Exception {
        try (var r = new Rig(5, 80, 10)) {
            var old = destination(1, 0, 5); r.send(old); r.until(DEPARTED);
            get(r.w.requestReset(reset(100, 1))); check(r.w.resume(), "Continúa");
            var renewed = new CabinRequest(old.request().getId(), 1, 2, Instant.now(), 1);
            check(r.send(new Command.Destination(1, 1, renewed)).outcome() == ElevatorWorker.Outcome.ACCEPTED, "Historial anterior liberado");
            var e = r.until(COMMAND_COMPLETED); check(e.epoch() == 1, "Servicio pertenece a época nueva");
        }
    }

    public static void resetCommandIdCannotBecomeServiceCommand() throws Exception {
        try (var r = new Rig(5, 20, 10)) {
            get(r.w.requestReset(reset(100, 1))); check(r.w.resume(), "Continúa");
            check(r.send(destination(100, 1, 3)).outcome() == ElevatorWorker.Outcome.REJECTED, "ID de reset reservado");
        }
    }

    public static void closeDuringResetDoesNotConfirmSuccess() throws Exception {
        try (var r = new Rig(5, 160, 10)) {
            r.go(5); var f = r.w.requestReset(reset(100, 1)); r.until(RESET_STARTED);
            r.w.close(); expectFailure(f); check(r.w.awaitTermination(LIMIT), "Cierre despierta retorno"); r.drain();
            check(r.count(RESET_COMPLETED) == 0, "No confirma retorno interrumpido");
        }
    }

    public static void unavailableWorkerRejectsReset() throws Exception {
        var w = new ElevatorWorker(1, new SimulationConfig(1, 1, 1, 1), 0);
        expectFailure(w.requestReset(reset(100, 1))); w.close();
        expectFailure(w.requestReset(reset(101, 2))); check(w.awaitTermination(LIMIT), "Sin hilo creado");
    }

    public static void submitRoutesResetThroughPriorityLane() throws Exception {
        try (var r = new Rig(5, 20, 10)) {
            r.w.requestStop().get(8, TimeUnit.SECONDS);
            check(r.w.submit(reset(100, 1)), "Reset por submit");
            r.until(RESET_COMPLETED); check(r.w.snapshot().epoch() == 1, "Pasó por control prioritario");
        }
    }

    public static void conditionalSubmitAlsoRoutesReset() throws Exception {
        try (var r = new Rig(5, 20, 10)) {
            r.w.requestStop().get(8, TimeUnit.SECONDS);
            var s = r.w.planningSnapshot();
            check(r.w.submitIfCurrent(reset(100, 1), s) == ElevatorWorker.Submission.ENQUEUED, "Envío condicionado de reset");
            r.until(RESET_COMPLETED);
        }
    }

    public static void successiveResetEpochsKeepActorAlive() throws Exception {
        try (var r = new Rig(1, 1, 1)) {
            long revision = 0;
            for (int i = 1; i <= 100; i++) {
                var s = get(r.w.requestReset(reset(100, i)));
                check(s.epoch() == i && s.revision() > revision, "Época y revisión crecientes"); revision = s.revision();
            }
            r.drain(); check(r.count(RESET_STARTED) == 100 && r.count(RESET_COMPLETED) == 100, "Un fin por época");
        }
    }

    public static void anotherCabinRemainsIndependent() throws Exception {
        var config = new SimulationConfig(2, 5, 100, 10);
        var a = new ElevatorWorker(1, config, 0); var b = new ElevatorWorker(2, config, 0);
        try {
            a.start(); b.start(); a.submit(destination(1, 0, 5));
            untilWorker(a, STOP_ENDED);
            var resetting = a.requestReset(reset(100, 1)); untilWorker(a, RESET_STARTED);
            var request = new CabinRequest(UUID.randomUUID(), 0, 2, Instant.now(), 2);
            b.submit(new Command.Destination(1, 2, request)); untilWorker(b, COMMAND_COMPLETED);
            check(!resetting.isDone() && b.snapshot().epoch() == 0, "B atiende sin reset global ni bloqueo");
            get(resetting);
        } finally { a.close(); b.close(); check(a.awaitTermination(LIMIT) && b.awaitTermination(LIMIT), "Ambos terminan"); }
    }

    public static void controlsAndServiceAreGatedDuringReset() throws Exception {
        try (var r = new Rig(5, 100, 10)) {
            r.go(5); var f = r.w.requestReset(reset(100, 1)); r.until(RESET_STARTED);
            check(!r.w.submit(destination(101, 1, 2)), "No admite servicio en retorno");
            check(!r.w.resume(), "No continúa antes de llegar"); expectFailure(r.w.requestStop());
            check(r.w.submitIfCurrent(destination(102, 1, 2), r.w.planningSnapshot()) != ElevatorWorker.Submission.ENQUEUED, "Sin envío condicionado en retorno");
            get(f);
        }
    }

    public static void callerCannotCancelInternalResetBarrier() throws Exception {
        try (var r = new Rig(5, 80, 10)) {
            r.go(4); var command = reset(100, 1); var visible = r.w.requestReset(command);
            visible.cancel(false);
            check(get(r.w.requestReset(command)).currentFloor() == 1, "Copia cancelada no interrumpe reset real");
        }
    }

    public static void externalAgendaIsCancelledWithoutPickup() throws Exception {
        try (var r = new Rig(5, 160, 10)) {
            var hall = new HallRequest(UUID.randomUUID(), 0, 4, Instant.now(), Direction.UP);
            r.send(new Command.Pickup(1, 1, hall)); r.until(DEPARTED);
            get(r.w.requestReset(reset(100, 1))); r.drain();
            check(r.w.snapshot().hallRequests().isEmpty(), "Agenda externa vacía al retornar");
            check(r.trace.stream().anyMatch(e -> e.type() == COMMAND_CANCELLED
                    && e.command().orElseThrow() instanceof Command.Pickup
                    && e.canonicalRequestId().orElseThrow().equals(hall.getId())), "Recogida cancelada y correlacionada");
            r.noServiceAfterReset();
        }
    }

    private static ElevatorState modelAtDwell() {
        var s = new ElevatorState(1, new SimulationConfig(1, 5, 10, 200), 0);
        s.addPendingRequest(request(1, 1, 0)); s.beginDwell(Direction.UP, 0); return s;
    }
    private static CabinRequest request(int id, int floor, long epoch) { return new CabinRequest(UUID.randomUUID(), epoch, floor, Instant.now(), id); }
    private static Command.Destination destination(long id, long epoch, int floor) { return new Command.Destination(id, 1, request(1, floor, epoch)); }
    private static Command.Reset reset(long id, long epoch) { return new Command.Reset(id, epoch, 1); }
    private static ElevatorSnapshot get(CompletableFuture<ElevatorSnapshot> f) throws Exception { return f.get(8, TimeUnit.SECONDS); }
    private static void expectFailure(CompletableFuture<?> f) throws Exception {
        try { f.get(8, TimeUnit.SECONDS); throw new AssertionError("Debía fallar"); }
        catch (ExecutionException expected) { check(expected.getCause() != null, "Causa informada"); }
    }
    private static void expectIllegal(Runnable action) {
        try { action.run(); throw new AssertionError("Debía rechazar"); }
        catch (IllegalArgumentException | IllegalStateException expected) { }
    }
    private static void check(boolean ok, String detail) { if (!ok) { throw new AssertionError(detail); } }
    private static void untilWorker(ElevatorWorker w, ExecutionEvent.Type type) throws Exception {
        long began = System.nanoTime();
        while (true) {
            long left = LIMIT.toNanos() - (System.nanoTime() - began); check(left > 0, "Plazo de evento");
            var e = w.awaitEvent(Duration.ofNanos(left)).orElseThrow(); check(e.type() != WORKER_FAILED, "Cabina sana");
            if (e.type() == type) { return; }
        }
    }

    private static final class Rig implements AutoCloseable {
        final ElevatorWorker w; final List<ExecutionEvent> trace = new ArrayList<>();
        Rig(int floors, long move, long dwell) {
            w = new ElevatorWorker(1, new SimulationConfig(1, floors, move, dwell), 0); w.start();
        }
        ElevatorWorker.Result send(Command command) throws Exception {
            check(w.submit(command), "Comando encolado");
            long began = System.nanoTime();
            while (true) {
                long left = LIMIT.toNanos() - (System.nanoTime() - began); check(left > 0, "Plazo de admisión");
                var result = w.awaitResult(Duration.ofNanos(left)).orElseThrow();
                if (result.command().sameContent(command)) { return result; }
            }
        }
        void go(int floor) throws Exception { send(destination(1, 0, floor)); until(STOP_ENDED); }
        ExecutionEvent until(ExecutionEvent.Type type) throws Exception { return until(e -> e.type() == type); }
        ExecutionEvent until(Predicate<ExecutionEvent> predicate) throws Exception {
            long began = System.nanoTime();
            while (true) {
                long left = LIMIT.toNanos() - (System.nanoTime() - began); check(left > 0, "Plazo de evento");
                var e = w.awaitEvent(Duration.ofNanos(left)).orElseThrow(); trace.add(e);
                check(e.type() != WORKER_FAILED, "Cabina sana: " + w.failure());
                if (predicate.test(e)) { return e; }
            }
        }
        void drain() throws Exception {
            while (true) { var e = w.awaitEvent(Duration.ZERO); if (e.isEmpty()) { return; } trace.add(e.orElseThrow()); }
        }
        long count(ExecutionEvent.Type type) { return trace.stream().filter(e -> e.type() == type).count(); }
        List<Integer> floors(int from) { return trace.subList(from, trace.size()).stream().filter(e -> e.type() == ARRIVED).map(e -> e.snapshot().currentFloor()).toList(); }
        void noServiceAfterReset() {
            boolean started = false;
            for (var e : trace) {
                if (e.type() == RESET_STARTED) { started = true; }
                if (started) { check(e.type() != STOP_STARTED && e.type() != COMMAND_COMPLETED, "Reset no atiende pasajeros"); }
            }
        }
        void verifyPhysicalTimes() {
            ExecutionEvent departed = null;
            for (var e : trace) {
                if (e.type() == DEPARTED) { departed = e; }
                if (e.type() == ARRIVED) {
                    check(departed != null, "Llegada con salida previa");
                    check(e.snapshot().currentFloor() == departed.snapshot().nextFloor().orElseThrow(), "Tramo adyacente");
                    check(e.referenceNanos() - departed.referenceNanos() >= e.snapshot().config().movementTimeMillis() * 1_000_000L, "Tiempo físico mínimo");
                    departed = null;
                }
            }
        }
        @Override public void close() throws Exception {
            w.close(); check(w.awaitTermination(LIMIT), "Hilo terminado");
            check(w.failure().isEmpty(), "Sin fallo de actor: " + w.failure());
        }
    }

    public static void main(String[] args) throws Exception {
        modelPreservesPartialLeg(); modelDiscardsDwell(); modelDiscardsHeldDwell();
        modelRejectsNonIncreasingEpochAtomically(); modelDoesNotFinishBeforeFloorOne();
        idleResetConfirmsAndHolds(); elevatedIdleReturnsThroughEveryFloor(); upwardResetFinishesPartialThenReturns();
        downwardResetKeepsCurrentLeg(); resetCancelsOpenDwellWithoutWaiting(); resetAdvancesFromStoppedCabin();
        resetAdvancesFromHeldDwell(); resetHasPriorityOverQueuedService(); admittedAliasesAreCancelledExplicitly();
        resetSupersedesPendingStop(); repeatedResetSharesPhysicalOperation(); completedResetReplayDoesNotResetAgain();
        nonIncreasingResetIsRejected(); wrongTargetIsRejected(); distinctResetWhileReturningIsRejected();
        staleServiceCannotReappearAfterReset(); newEpochMayReuseOldServiceIdentity(); resetCommandIdCannotBecomeServiceCommand();
        closeDuringResetDoesNotConfirmSuccess(); unavailableWorkerRejectsReset(); submitRoutesResetThroughPriorityLane();
        conditionalSubmitAlsoRoutesReset(); successiveResetEpochsKeepActorAlive(); anotherCabinRemainsIndependent();
        controlsAndServiceAreGatedDuringReset(); callerCannotCancelInternalResetBarrier();
        externalAgendaIsCancelledWithoutPickup();
        System.out.println("T37: 32 escenarios satisfactorios.");
    }
}
