package com.elevatormanager.dispatch;

import com.elevatormanager.model.Direction;
import com.elevatormanager.model.ElevatorPhase;
import com.elevatormanager.model.ElevatorSnapshot;
import com.elevatormanager.model.SimulationConfig;
import com.elevatormanager.model.request.CabinRequest;
import com.elevatormanager.model.request.HallRequest;
import com.elevatormanager.protocol.Command;
import com.elevatormanager.simulation.ElevatorWorker;
import com.elevatormanager.simulation.ExecutionEvent;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import static com.elevatormanager.dispatch.HallCallSnapshot.Status.*;
import static com.elevatormanager.simulation.ExecutionEvent.Type.*;

/** T32: registro determinista y coordinación con cabinas e hilos reales. */
public final class PendingConfirmationScenarios {
    private static final Duration LIMIT = Duration.ofSeconds(8);
    private PendingConfirmationScenarios() { }

    public static void fullLifecycleRequiresCompatibleCompletion() {
        var f = new F(); var r = hall(3, Direction.UP);
        f.ledger.receive(r); check(f.get(r).status() == RECEIVED, "Recibida.");
        var command = f.send(r, 1, 1); check(f.get(r).status() == DISPATCHED, "Envío no es admisión.");
        f.accept(command); check(f.get(r).status() == ASSIGNED, "Admisión no es servicio.");
        f.complete(command); check(f.get(r).status() == SERVED, "Servicio confirmado.");
        check(f.ledger.pending().isEmpty(), "Atendida sale de pendientes.");
    }

    public static void pendingAliasesShareOneCanonicalCall() {
        var f = new F(); var a = hall(3, Direction.UP); var b = hall(3, Direction.UP);
        check(f.ledger.receive(a).kind() == HallRequestLedger.AdmissionKind.NEW, "Nueva.");
        check(f.ledger.receive(b).kind() == HallRequestLedger.AdmissionKind.DUPLICATE, "Alias funcional.");
        check(f.ledger.pending().size() == 1 && f.get(b).canonicalRequestId().equals(a.getId()), "Una canónica pendiente.");
        var command = f.send(a, 1, 1); f.accept(command); f.complete(command);
        check(f.get(a).status() == SERVED && f.get(b).status() == SERVED, "Ambas identidades comparten servicio.");
    }

    public static void aliasAfterAssignmentDoesNotRedispatch() {
        var f = new F(); var a = hall(3, Direction.UP); f.ledger.receive(a);
        var command = f.send(a, 1, 1); f.accept(command);
        var b = hall(3, Direction.UP); f.ledger.receive(b);
        check(f.get(b).status() == ASSIGNED && f.get(b).command().orElseThrow().sameContent(command), "Hereda asignación.");
        check(f.get(b).attempts() == 1 && f.ledger.pending().isEmpty(), "No crea otro envío.");
    }

    public static void oppositeDirectionsRemainSeparate() {
        var f = new F(); f.ledger.receive(hall(3, Direction.UP)); f.ledger.receive(hall(3, Direction.DOWN));
        check(f.ledger.pending().size() == 2, "Botones opuestos son llamadas distintas.");
    }

    public static void identityConflictPreservesOriginal() {
        var f = new F(); var r = hall(3, Direction.UP); f.ledger.receive(r);
        var conflict = new HallRequest(r.getId(), 0, 4, r.getCreatedAt(), Direction.UP);
        check(f.ledger.receive(conflict).kind() == HallRequestLedger.AdmissionKind.REJECTED, "Rechaza UUID alterado.");
        check(f.get(r).request().getFloor() == 3 && f.ledger.pending().size() == 1, "No reemplaza original.");
    }

    public static void servedIdentityReplayDoesNotReopenService() {
        var f = new F(); var r = hall(3, Direction.UP); f.ledger.receive(r);
        var command = f.send(r, 1, 1); f.accept(command); f.complete(command);
        check(f.ledger.receive(r).kind() == HallRequestLedger.AdmissionKind.DUPLICATE, "Repetición histórica.");
        check(f.get(r).status() == SERVED && f.ledger.pending().isEmpty(), "No reabre ni despacha otra vez.");
    }

    public static void freshIdentityCanRequestServedFloorAgain() {
        var f = new F(); var r = hall(3, Direction.UP); f.ledger.receive(r);
        var command = f.send(r, 1, 1); f.accept(command); f.complete(command);
        var fresh = hall(3, Direction.UP);
        check(f.ledger.receive(fresh).kind() == HallRequestLedger.AdmissionKind.NEW, "Nueva pulsación después del servicio.");
        check(f.get(fresh).canonicalRequestId().equals(fresh.getId()) && f.get(r).status() == SERVED, "Historial independiente.");
    }

    public static void arrivalAndStopAloneDoNotCompleteCall() {
        var f = new F(); var r = hall(3, Direction.UP); f.ledger.receive(r);
        var command = f.send(r, 1, 1); f.accept(command);
        f.event(ARRIVED, command, 3, Direction.UP, r.getId());
        f.event(STOP_STARTED, command, 3, Direction.UP, r.getId());
        check(f.get(r).status() == ASSIGNED, "Ni llegada ni apertura sin correlación final completan la llamada.");
    }

    public static void completionBeforeAdmissionIsIgnored() {
        var f = new F(); var r = hall(3, Direction.UP); f.ledger.receive(r);
        var command = f.send(r, 1, 1); f.complete(command);
        check(f.get(r).status() == DISPATCHED, "Exige confirmación de admisión primero.");
        f.accept(command); f.complete(command); check(f.get(r).status() == SERVED, "Secuencia válida completa.");
    }

    public static void wrongFloorCompletionIsUnconfirmed() {
        var f = new F(); var r = hall(3, Direction.UP); f.ledger.receive(r);
        var command = f.send(r, 1, 1); f.accept(command);
        f.event(COMMAND_COMPLETED, command, 4, Direction.UP, r.getId());
        check(f.get(r).status() == UNCONFIRMED && f.ledger.pending().isEmpty(), "Sin prueba compatible no inventa servicio ni reasigna.");
    }

    public static void wrongDirectionCompletionIsUnconfirmed() {
        var f = new F(); var r = hall(3, Direction.UP); f.ledger.receive(r);
        var command = f.send(r, 1, 1); f.accept(command);
        f.event(COMMAND_COMPLETED, command, 3, Direction.DOWN, r.getId());
        check(f.get(r).status() == UNCONFIRMED, "Recogida requiere el sentido solicitado.");
    }

    public static void unknownCommandCanonicalAndEpochCannotComplete() {
        var f = new F(); var r = hall(3, Direction.UP); f.ledger.receive(r);
        var command = f.send(r, 1, 1); f.accept(command);
        f.event(COMMAND_COMPLETED, new Command.Pickup(99, 1, r), 3, Direction.UP, r.getId());
        f.event(COMMAND_COMPLETED, command, 3, Direction.UP, UUID.randomUUID());
        var foreign = new HallRequest(r.getId(), 1, 3, r.getCreatedAt(), Direction.UP);
        var foreignCommand = new Command.Pickup(1, 1, foreign);
        f.ledger.onEvent(new ExecutionEvent(999, COMMAND_COMPLETED, snapshot(1, 1, f.config, 3, Direction.UP, true),
                Optional.of(foreignCommand), Optional.of(r.getId()), "Época antigua/ajena."));
        check(f.get(r).status() == ASSIGNED, "No acepta eventos ajenos ni identidad canónica distinta.");
        f.complete(command); check(f.get(r).status() == SERVED, "Evento ajeno no contamina la secuencia vigente.");
    }

    public static void duplicateEventIsIdempotent() {
        var f = new F(); var r = hall(3, Direction.UP); f.ledger.receive(r);
        var command = f.send(r, 1, 1); f.accept(command);
        var event = f.event(COMMAND_COMPLETED, command, 3, Direction.UP, r.getId());
        var before = f.ledger.snapshot(); f.ledger.onEvent(event);
        check(before.equals(f.ledger.snapshot()), "Mismo evento no completa dos veces.");
    }

    public static void rejectionRetainsCallAndAvoidsRejectingCabin() {
        var f = new F(); var r = hall(3, Direction.UP); f.ledger.receive(r);
        var command = f.send(r, 1, 1);
        f.event(COMMAND_REJECTED, command, 1, Direction.UP, null);
        check(f.get(r).status() == RECEIVED && f.ledger.rejectedCabins(r.getId()).contains(1), "Retenida; evita rechazo persistente.");
        var retry = f.send(r, 2, 2); f.accept(retry); f.complete(retry);
        check(f.get(r).status() == SERVED && f.get(r).attempts() == 2, "Nuevo ID y otra cabina, un servicio confirmado.");
    }

    public static void oldAttemptCannotOverwriteNewAssignment() {
        var f = new F(); var r = hall(3, Direction.UP); f.ledger.receive(r);
        var old = f.send(r, 1, 1); f.event(COMMAND_REJECTED, old, 1, Direction.UP, null);
        var current = f.send(r, 2, 2); f.accept(current); f.complete(old);
        check(f.get(r).status() == ASSIGNED && f.get(r).command().orElseThrow().elevatorId() == 2, "Ignora intento anterior.");
        f.complete(current); check(f.get(r).status() == SERVED, "Solo intento vigente completa.");
    }

    public static void normalTerminationRequeuesUnservedCall() {
        var f = new F(); var r = hall(3, Direction.UP); f.ledger.receive(r);
        var command = f.send(r, 1, 1); f.accept(command); f.terminal(WORKER_TERMINATED, 1);
        check(f.get(r).status() == RECEIVED && f.get(r).command().isEmpty(), "Retiene la llamada al terminar cabina.");
    }

    public static void failedCabinDoesNotGuessServiceOrRedispatch() {
        var f = new F(); var r = hall(3, Direction.UP); f.ledger.receive(r);
        var command = f.send(r, 1, 1); f.accept(command); f.terminal(WORKER_FAILED, 1);
        check(f.get(r).status() == UNCONFIRMED && f.ledger.pending().isEmpty(), "Fallo incierto exige revisión.");
        var alias = hall(3, Direction.UP); f.ledger.receive(alias);
        check(f.get(alias).status() == UNCONFIRMED, "Una repetición no evade la incertidumbre.");
    }

    public static void terminationAfterCompletionPreservesServedHistory() {
        var f = new F(); var r = hall(3, Direction.UP); f.ledger.receive(r);
        var command = f.send(r, 1, 1); f.accept(command); f.complete(command); f.terminal(WORKER_TERMINATED, 1);
        check(f.get(r).status() == SERVED, "Terminal no reabre una llamada ya atendida.");
    }

    public static void managerCloseDistinguishesUnsentAndInFlight() {
        var f = new F(); var unsent = hall(2, Direction.UP); var sent = hall(3, Direction.UP);
        f.ledger.receive(unsent); f.ledger.receive(sent); f.send(sent, 1, 1); f.ledger.close();
        check(f.get(unsent).status() == CANCELLED && f.get(sent).status() == UNCONFIRMED,
                "Cerrar manejador no cancela físicamente lo ya enviado.");
    }

    public static void snapshotsAreImmutableAndReservationsEndOnAdmission() {
        var f = new F(); var r = hall(3, Direction.UP); f.ledger.receive(r);
        var command = f.send(r, 1, 1); var retained = f.ledger.snapshot();
        check(f.ledger.awaitingAdmissionCabins().contains(1), "Reserva hasta que agenda confirme.");
        f.accept(command); check(f.ledger.awaitingAdmissionCabins().isEmpty(), "Admisión libera reserva provisional.");
        check(retained.get(r.getId()).status() == DISPATCHED, "Vista antigua no cambia.");
        expect(UnsupportedOperationException.class, () -> retained.clear());
        check(f.ledger.get(UUID.randomUUID()).isEmpty(), "Identidad desconocida sin vista ficticia.");
    }

    public static void receiverCanonicalCanDifferFromGlobalCanonical() {
        var f = new F(); var r = hall(3, Direction.UP); f.ledger.receive(r);
        var command = f.send(r, 1, 1); var receiver = UUID.randomUUID();
        f.event(COMMAND_DUPLICATE, command, 1, Direction.UP, receiver);
        f.event(COMMAND_COMPLETED, command, 3, Direction.UP, receiver);
        check(f.get(r).status() == SERVED && f.get(r).canonicalRequestId().equals(r.getId()),
                "Canónica de cabina correlacionada sin reemplazar la canónica global.");
    }

    public static void historicalAliasNeverAttachesToNewCall() {
        var f = new F(); var a = hall(3, Direction.UP); var alias = hall(3, Direction.UP);
        f.ledger.receive(a); f.ledger.receive(alias);
        var command = f.send(a, 1, 1); f.accept(command); f.complete(command);
        var fresh = hall(3, Direction.UP); f.ledger.receive(fresh); f.ledger.receive(alias);
        check(f.get(alias).status() == SERVED && f.get(alias).canonicalRequestId().equals(a.getId())
                && f.get(fresh).status() == RECEIVED, "Repetición histórica no se incorpora a una nueva pulsación.");
    }

    public static void realBurstDeduplicatesAcrossCabinsAndRetriesWhenTheyStart() throws Exception {
        try (var rig = new Rig(2, false, 40)) {
            var requests = new ArrayList<HallRequest>();
            for (int i = 0; i < 24; i++) { var r = hall(2, Direction.UP); requests.add(r); check(rig.manager.submit(r), "Entrada encolada."); }
            for (int i = 0; i < 24; i++) {
                var result = rig.manager.awaitResult(LIMIT).orElseThrow();
                check(result.outcome() == (i == 0 ? ManagerWorker.Outcome.PENDING : ManagerWorker.Outcome.DUPLICATE), "Una pendiente y 23 alias.");
            }
            check(rig.ids.get() == 0, "Sin cabinas iniciadas no consume comandos.");
            for (var worker : rig.elevators) { worker.start(); }
            waitState(rig.manager, requests.get(0), SERVED);
            check(rig.ids.get() == 1 && rig.manager.calls().size() == 24, "Un solo comando global, 24 identidades retenidas.");
            for (var r : requests) { check(rig.manager.call(r.getId()).orElseThrow().status() == SERVED, "Alias comparte atención."); }
        }
    }

    public static void realEventSubscriptionDoesNotStealOriginalEvents() throws Exception {
        try (var rig = new Rig(1, true, 40)) {
            var r = hall(2, Direction.UP); rig.manager.submit(r);
            var result = rig.manager.awaitResult(LIMIT).orElseThrow();
            check(result.outcome() == ManagerWorker.Outcome.DISPATCHED, "Envío inicial.");
            waitState(rig.manager, r, SERVED);
            boolean originalCompletion = false;
            while (true) {
                var event = rig.elevators.get(0).awaitEvent(Duration.ZERO);
                if (event.isEmpty()) { break; }
                if (event.orElseThrow().type() == COMMAND_COMPLETED) { originalCompletion = true; }
            }
            check(originalCompletion, "El flujo original conserva su propia confirmación.");
            check(rig.elevators.get(0).awaitResult(LIMIT).orElseThrow().outcome() == ElevatorWorker.Outcome.ACCEPTED,
                    "El canal Result original tampoco se consume.");
            expect(UnsupportedOperationException.class, () -> rig.manager.calls().clear());
        }
    }

    public static void realReceiverRejectionAutomaticallyUsesAnotherCabin() throws Exception {
        var config = new SimulationConfig(2, 8, 40, 30);
        var a = new ElevatorWorker(1, config, 0); var b = new ElevatorWorker(2, config, 0);
        var ids = new AtomicLong(); a.start(); b.start();
        try {
            // Inyección deliberada: historial previo con ID 1 desconocido por la secuencia nueva.
            a.submit(new Command.Destination(1, 1, new CabinRequest(UUID.randomUUID(), 0, 1, Instant.EPOCH, 1)));
            a.awaitResult(LIMIT).orElseThrow();
            // Terminar la parada auxiliar: ambas cabinas deben partir libres en piso 1.
            while (a.awaitEvent(LIMIT).orElseThrow().type() != STOP_ENDED) { }
            try (var manager = new ManagerWorker(config, 0, List.of(a, b), ids::incrementAndGet)) {
                manager.start(); var r = hall(3, Direction.UP); manager.submit(r);
                check(manager.awaitResult(LIMIT).orElseThrow().outcome() == ManagerWorker.Outcome.DISPATCHED, "Primer envío puede ser rechazado por receptor.");
                waitState(manager, r, SERVED);
                var state = manager.call(r.getId()).orElseThrow();
                check(state.attempts() == 2 && state.command().orElseThrow().elevatorId() == 2,
                        "Rechazo confirmado vuelve a pendientes y elige otra cabina.");
                manager.close(); check(manager.awaitTermination(LIMIT), "Manejador termina.");
            }
        } finally { a.close(); b.close(); check(a.awaitTermination(LIMIT) && b.awaitTermination(LIMIT), "Cabinas terminan."); }
    }

    public static void realNormalClosureReassignsOnlyUnservedCall() throws Exception {
        try (var rig = new Rig(2, true, 1000)) {
            var r = hall(2, Direction.UP); rig.manager.submit(r); rig.manager.awaitResult(LIMIT).orElseThrow();
            waitState(rig.manager, r, ASSIGNED);
            rig.elevators.get(0).close(); check(rig.elevators.get(0).awaitTermination(LIMIT), "Primera cabina termina antes de atender.");
            waitState(rig.manager, r, SERVED);
            var state = rig.manager.call(r.getId()).orElseThrow();
            check(state.attempts() == 2 && state.command().orElseThrow().elevatorId() == 2,
                    "Terminal ordenado permite reasignar sin perder llamada.");
        }
    }

    public static void main(String[] args) throws Exception {
        fullLifecycleRequiresCompatibleCompletion(); pendingAliasesShareOneCanonicalCall(); aliasAfterAssignmentDoesNotRedispatch();
        oppositeDirectionsRemainSeparate(); identityConflictPreservesOriginal(); servedIdentityReplayDoesNotReopenService();
        freshIdentityCanRequestServedFloorAgain(); arrivalAndStopAloneDoNotCompleteCall(); completionBeforeAdmissionIsIgnored();
        wrongFloorCompletionIsUnconfirmed(); wrongDirectionCompletionIsUnconfirmed(); unknownCommandCanonicalAndEpochCannotComplete();
        duplicateEventIsIdempotent(); rejectionRetainsCallAndAvoidsRejectingCabin(); oldAttemptCannotOverwriteNewAssignment();
        normalTerminationRequeuesUnservedCall(); failedCabinDoesNotGuessServiceOrRedispatch(); terminationAfterCompletionPreservesServedHistory();
        managerCloseDistinguishesUnsentAndInFlight(); snapshotsAreImmutableAndReservationsEndOnAdmission();
        receiverCanonicalCanDifferFromGlobalCanonical(); historicalAliasNeverAttachesToNewCall();
        realBurstDeduplicatesAcrossCabinsAndRetriesWhenTheyStart(); realEventSubscriptionDoesNotStealOriginalEvents();
        realReceiverRejectionAutomaticallyUsesAnotherCabin(); realNormalClosureReassignsOnlyUnservedCall();
        System.out.println("T32: 26 escenarios satisfactorios; pendientes, alias, confirmaciones y reintentos con hilos reales.");
    }

    private static void waitState(ManagerWorker manager, HallRequest request, HallCallSnapshot.Status wanted) throws Exception {
        long started = System.nanoTime();
        while (true) {
            if (manager.call(request.getId()).map(s -> s.status() == wanted).orElse(false)) { return; }
            long remaining = LIMIT.toNanos() - (System.nanoTime() - started);
            check(remaining > 0, "Falta estado " + wanted + ": " + manager.call(request.getId()) + "; fallo=" + manager.failure());
            var update = manager.awaitUpdate(Duration.ofNanos(remaining)).orElseThrow();
            if (update.request().getId().equals(request.getId()) && update.status() == wanted) { return; }
        }
    }

    private static HallRequest hall(int floor, Direction direction) {
        return new HallRequest(UUID.randomUUID(), 0, floor, Instant.EPOCH, direction);
    }
    private static ElevatorSnapshot snapshot(int id, long epoch, SimulationConfig config, int floor, Direction direction, boolean dwelling) {
        return new ElevatorSnapshot(id, config, epoch, 0, floor, OptionalInt.empty(), direction,
                dwelling ? ElevatorPhase.DWELLING : ElevatorPhase.IDLE, Optional.empty(), Optional.empty(),
                ElevatorSnapshot.Mode.NORMAL, dwelling ? config.dwellTimeMillis() * 1_000_000L : 0,
                dwelling ? config.dwellTimeMillis() * 1_000_000L : 0, 0, OptionalInt.empty(), List.of(), List.of());
    }
    private static void check(boolean value, String message) { if (!value) { throw new AssertionError(message); } }
    private static void expect(Class<? extends Throwable> type, Runnable action) {
        try { action.run(); } catch (Throwable e) { if (type.isInstance(e)) { return; } throw new AssertionError(e); }
        throw new AssertionError("Falta excepción " + type.getSimpleName());
    }

    private static final class F {
        private final SimulationConfig config = new SimulationConfig(2, 8, 100, 50);
        private final HallRequestLedger ledger = new HallRequestLedger(config, 0);
        private long sequence;
        private HallCallSnapshot get(HallRequest request) { return ledger.get(request.getId()).orElseThrow(); }
        private Command.Pickup send(HallRequest request, int elevator, long id) {
            var command = new Command.Pickup(id, elevator, request); ledger.dispatched(request.getId(), command); return command;
        }
        private void accept(Command.Pickup command) { event(COMMAND_ACCEPTED, command, 1, Direction.UP, command.request().getId()); }
        private void complete(Command.Pickup command) {
            event(COMMAND_COMPLETED, command, command.request().getFloor(), command.request().getDirection(), command.request().getId());
        }
        private ExecutionEvent event(ExecutionEvent.Type type, Command.Pickup command, int floor, Direction direction, UUID receiver) {
            var event = new ExecutionEvent(++sequence, type, snapshot(command.elevatorId(), 0, config, floor, direction,
                    type == COMMAND_COMPLETED || type == STOP_STARTED), Optional.of(command), Optional.ofNullable(receiver), "Prueba.");
            ledger.onEvent(event); return event;
        }
        private void terminal(ExecutionEvent.Type type, int elevator) {
            ledger.onEvent(new ExecutionEvent(++sequence, type, snapshot(elevator, 0, config, 1, Direction.UP, false), Optional.empty(), Optional.empty(), "Terminal."));
        }
    }

    private static final class Rig implements AutoCloseable {
        private final List<ElevatorWorker> elevators = new ArrayList<>();
        private final AtomicLong ids = new AtomicLong();
        private final ManagerWorker manager;
        private Rig(int count, boolean startCabins, long movement) {
            var config = new SimulationConfig(count, 8, movement, 30);
            for (int i = 1; i <= count; i++) {
                var worker = new ElevatorWorker(i, config, 0); elevators.add(worker);
                if (startCabins) { worker.start(); }
            }
            manager = new ManagerWorker(config, 0, elevators, ids::incrementAndGet); manager.start();
        }
        @Override public void close() throws Exception {
            manager.close(); check(manager.awaitTermination(LIMIT) && manager.failure().isEmpty(), "Manejador termina sin fallo.");
            for (var worker : elevators) { worker.close(); }
            for (var worker : elevators) { check(worker.awaitTermination(LIMIT) && worker.failure().isEmpty(), "Cabina termina sin fallo."); }
        }
    }
}
