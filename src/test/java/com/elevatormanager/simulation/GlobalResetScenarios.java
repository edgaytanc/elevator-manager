package com.elevatormanager.simulation;

import com.elevatormanager.dispatch.*;
import com.elevatormanager.model.*;
import com.elevatormanager.model.request.*;
import com.elevatormanager.protocol.Command;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Predicate;
import javax.swing.SwingUtilities;
import static com.elevatormanager.simulation.ExecutionEvent.Type.*;
import static com.elevatormanager.simulation.SimulationController.Outcome.*;
import static com.elevatormanager.dispatch.HallCallSnapshot.Status.*;

/** T38: épocas, barrera global, mensajes tardíos y continuación de actores reales. */
public final class GlobalResetScenarios {
    private static final Duration LIMIT = Duration.ofSeconds(10);
    private GlobalResetScenarios() { }

    public static void initialResetConfirmsEveryParticipant() throws Exception {
        try (var r = new Rig(3, 5, 20, 10)) {
            check(get(r.c.reset()).outcome() == APPLIED && r.c.epoch() == 1, "Reset inicial avanza época");
            ready(r.c); r.drain();
            check(r.count(RESET_COMPLETED) == 3, "Tres confirmaciones físicas");
            check(r.c.managerLifecycle() == ManagerWorker.Lifecycle.RUNNING, "Manejador conserva hilo");
        }
    }

    public static void waitsForSlowestCabin() throws Exception {
        try (var r = new Rig(2, 6, 200, 10)) {
            get(r.c.start()); get(r.c.submit(cabin(1, 4, 0))); r.until(e -> e.elevatorId() == 1 && e.type() == STOP_ENDED);
            get(r.c.submit(cabin(1, 6, 0))); get(r.c.submit(cabin(2, 2, 0)));
            var reset = r.c.reset();
            r.until(e -> e.elevatorId() == 2 && e.type() == RESET_COMPLETED);
            check(r.c.state() == SimulationState.RESETTING && !reset.isDone(), "Una cabina no completa la barrera global");
            get(reset); ready(r.c);
        }
    }

    public static void resetFromStoppedReturnsWithoutStart() throws Exception {
        try (var r = new Rig(1, 5, 30, 10)) {
            get(r.c.start()); get(r.c.submit(cabin(1, 4, 0))); r.until(STOP_ENDED); get(r.c.stop());
            get(r.c.reset()); ready(r.c);
            check(r.c.snapshots().get(0).currentFloor() == 1, "Retorno desde detenido");
        }
    }

    public static void resetSupersedesGlobalStopping() throws Exception {
        try (var r = new Rig(1, 5, 300, 10)) {
            get(r.c.start()); get(r.c.submit(cabin(1, 5, 0))); r.until(DEPARTED);
            var stop = r.c.stop(); var reset = r.c.reset();
            check(get(stop).outcome() == REJECTED, "Stop sustituido sin falso éxito");
            get(reset); ready(r.c); check(r.c.failure().isEmpty(), "Confirmación Stop tardía ignorada");
        }
    }

    public static void openDwellIsCancelledGlobally() throws Exception {
        try (var r = new Rig(1, 5, 20, 60_000)) {
            get(r.c.start()); get(r.c.submit(cabin(1, 3, 0))); r.until(COMMAND_COMPLETED);
            get(r.c.reset()); ready(r.c); r.drain();
            check(r.trace.stream().filter(e -> e.epoch() == 1).noneMatch(e -> e.type() == STOP_STARTED || e.type() == COMMAND_COMPLETED), "No atiende servicio durante retorno");
        }
    }

    public static void repeatedResetSharesOneEpoch() throws Exception {
        try (var r = new Rig(1, 6, 150, 10)) {
            get(r.c.start()); get(r.c.submit(cabin(1, 5, 0))); r.until(STOP_ENDED);
            var pool = Executors.newFixedThreadPool(4); var gate = new CountDownLatch(1);
            try {
                var calls = new ArrayList<Future<CompletableFuture<SimulationController.Result>>>();
                for (int i = 0; i < 32; i++) { calls.add(pool.submit(() -> { gate.await(); return r.c.reset(); })); }
                gate.countDown();
                var futures = new ArrayList<CompletableFuture<SimulationController.Result>>();
                for (var f : calls) { futures.add(f.get(10, TimeUnit.SECONDS)); }
                for (var f : futures) { check(get(f).epoch() == 1, "Misma época de operación concurrente"); }
                r.drain(); check(r.c.epoch() == 1 && r.count(RESET_STARTED) == 1, "Un retorno, una época");
            } finally { gate.countDown(); pool.shutdownNow(); }
        }
    }

    public static void anotherResetAfterReadyCreatesNewEpoch() throws Exception {
        try (var r = new Rig(2, 3, 20, 10)) {
            get(r.c.reset()); get(r.c.reset()); ready(r.c); check(r.c.epoch() == 2, "Nueva operación tras finalizar");
            get(r.c.start()); get(r.c.submit(cabin(2, 2, 2))); r.until(COMMAND_COMPLETED);
            check(r.c.elevatorLifecycles().stream().allMatch(l -> l == ElevatorWorker.Lifecycle.RUNNING), "No se recrean hilos");
        }
    }

    public static void controlsAreRejectedWhileResetting() throws Exception {
        try (var r = new Rig(1, 5, 150, 10)) {
            get(r.c.start()); get(r.c.submit(cabin(1, 4, 0))); r.until(STOP_ENDED);
            var reset = r.c.reset();
            check(get(r.c.start()).outcome() == REJECTED, "Start en RESETTING rechazado");
            check(get(r.c.stop()).outcome() == REJECTED, "Stop no detiene retorno");
            check(get(r.c.submit(cabin(1, 2, 1))).outcome() == REJECTED, "Sin admisión manual durante reset");
            get(reset); ready(r.c);
        }
    }

    public static void oldAdmissionResponseCanBeRecognized() throws Exception {
        try (var r = new Rig(1, 3, 20, 10)) {
            get(r.c.start()); var old = get(r.c.submit(hall(2, 0)));
            get(r.c.reset()); check(old.epoch() == 0 && !r.c.isCurrent(old), "Respuesta externa tardía identificada por época");
            check(r.c.calls().isEmpty(), "No restaura seguimiento viejo");
        }
    }

    public static void extractedOldEventMustBeRevalidated() throws Exception {
        try (var r = new Rig(1, 3, 20, 10)) {
            get(r.c.start()); get(r.c.submit(cabin(1, 2, 0))); var old = r.until(DEPARTED);
            get(r.c.reset()); check(!r.c.isCurrent(old), "Evento extraído antes de reset ya no es vigente");
        }
    }

    public static void bufferedOldEventsAreNotReturnedAsCurrent() throws Exception {
        try (var r = new Rig(1, 1, 1, 60_000)) {
            get(r.c.start());
            for (int i = 0; i < 300; i++) { get(r.c.submit(cabin(1, 1, 0))); }
            get(r.c.reset());
            var event = r.until(RESET_COMPLETED);
            check(event.epoch() == 1 && r.trace.stream().allMatch(e -> e.epoch() == 1), "Historial viejo descartado al leer");
        }
    }

    public static void staleManualRequestsCannotReenterNewRun() throws Exception {
        try (var r = new Rig(1, 3, 20, 10)) {
            get(r.c.reset()); get(r.c.start());
            check(get(r.c.submit(hall(2, 0))).outcome() == REJECTED, "Externa antigua rechazada");
            check(get(r.c.submit(cabin(1, 2, 0))).outcome() == REJECTED, "Interna antigua rechazada");
            check(r.c.calls().isEmpty(), "Sin pendiente antiguo");
        }
    }

    public static void freshEpochMayReuseCancelledUuid() throws Exception {
        try (var r = new Rig(1, 5, 100, 10)) {
            get(r.c.start()); var old = cabin(1, 5, 0); get(r.c.submit(old)); r.until(DEPARTED);
            get(r.c.reset()); get(r.c.start());
            var fresh = new CabinRequest(old.getId(), 1, 2, Instant.now(), 1);
            check(get(r.c.submit(fresh)).outcome() == ENQUEUED, "Identidad válida en época nueva");
            check(r.until(COMMAND_COMPLETED).epoch() == 1, "Atención nueva y correlacionada");
        }
    }

    public static void commandIdsKeepGrowingAcrossReset() throws Exception {
        try (var r = new Rig(1, 3, 20, 10)) {
            get(r.c.start()); var old = get(r.c.submit(cabin(1, 2, 0))).command().orElseThrow();
            get(r.c.reset()); get(r.c.start()); var fresh = get(r.c.submit(cabin(1, 2, 1))).command().orElseThrow();
            check(fresh.commandId() > old.commandId() + 1, "Secuencia no reiniciada y reset con ID propio");
        }
    }

    public static void oneFloorResetAndContinuationWork() throws Exception {
        try (var r = new Rig(1, 1, 1, 5)) {
            get(r.c.reset()); get(r.c.start()); get(r.c.submit(cabin(1, 1, 1)));
            check(r.until(COMMAND_COMPLETED).snapshot().currentFloor() == 1, "Servicio sin viaje");
            check(get(r.c.submit(hall(1, 1))).outcome() == REJECTED, "Externa imposible rechazada");
        }
    }

    public static void exhaustedEpochDoesNotWrapOrStartWorkers() throws Exception {
        var c = new SimulationController(new SimulationConfig(1, 1, 1, 1), Long.MAX_VALUE);
        try {
            check(get(c.reset()).outcome() == REJECTED, "Época agotada informada");
            check(c.epoch() == Long.MAX_VALUE && c.state() == SimulationState.RESET, "Sin wrap ni cambio de estado");
            check(c.elevatorLifecycles().get(0) == ElevatorWorker.Lifecycle.NEW, "Sin inicio parcial");
        } finally { c.close(); check(c.awaitTermination(LIMIT), "Cierre posible"); }
    }

    public static void closeCancelsPendingGlobalReset() throws Exception {
        try (var r = new Rig(1, 5, 150, 10)) {
            get(r.c.start()); get(r.c.submit(cabin(1, 4, 0))); r.until(STOP_ENDED);
            var reset = r.c.reset(); r.until(RESET_STARTED); r.c.close();
            check(get(reset).outcome() == REJECTED, "Cierre invalida barrera pendiente");
            check(r.c.awaitTermination(LIMIT) && r.c.state() == SimulationState.CLOSED, "Cierre sin falso RESET");
        }
    }

    public static void closeAfterResetWakesHeldActors() throws Exception {
        try (var r = new Rig(3, 5, 20, 10)) {
            get(r.c.reset()); r.c.close(); check(r.c.awaitTermination(LIMIT), "Cierra actores retenidos tras retorno");
            check(get(r.c.reset()).outcome() == REJECTED, "No resetea sesión cerrada");
        }
    }

    public static void edtRemainsResponsiveDuringGlobalReset() throws Exception {
        try (var r = new Rig(1, 5, 120, 10)) {
            get(r.c.start()); get(r.c.submit(cabin(1, 4, 0))); r.until(STOP_ENDED);
            var future = new AtomicReference<CompletableFuture<SimulationController.Result>>();
            var pulse = new CountDownLatch(1);
            SwingUtilities.invokeAndWait(() -> { future.set(r.c.reset()); SwingUtilities.invokeLater(pulse::countDown); });
            check(pulse.await(2, TimeUnit.SECONDS), "EDT libre durante la barrera"); get(future.get()); ready(r.c);
        }
    }

    public static void rapidResetStartStopCyclesPreserveActors() throws Exception {
        try (var r = new Rig(2, 1, 1, 1)) {
            for (int i = 1; i <= 200; i++) {
                check(get(r.c.reset()).epoch() == i, "Época creciente en ciclo " + i);
                get(r.c.start()); get(r.c.stop());
            }
            get(r.c.reset()); ready(r.c); check(r.c.epoch() == 201, "Todas las barreras terminaron");
        }
    }

    public static void cancelledCallerFutureDoesNotCancelGlobalReset() throws Exception {
        try (var r = new Rig(1, 5, 120, 10)) {
            get(r.c.start()); get(r.c.submit(cabin(1, 4, 0))); r.until(STOP_ENDED);
            var first = r.c.reset(); first.cancel(false);
            check(get(r.c.reset()).outcome() == APPLIED && r.c.epoch() == 1, "Barrera privada conservada"); ready(r.c);
        }
    }

    public static void completedWorkDoesNotReappearAfterStart() throws Exception {
        try (var r = new Rig(1, 3, 20, 10)) {
            get(r.c.start()); get(r.c.submit(hall(2, 0))); r.until(COMMAND_COMPLETED);
            get(r.c.reset()); get(r.c.start()); r.drain();
            check(r.c.calls().isEmpty() && r.c.snapshots().get(0).hallRequests().isEmpty(), "No repone la llamada atendida");
            check(r.trace.stream().filter(e -> e.epoch() == 1).noneMatch(e -> e.type() == COMMAND_COMPLETED), "Sin atención nueva espontánea");
        }
    }

    public static void ledgerInvalidatesOpenTrackingButKeepsServedHistory() {
        var config = new SimulationConfig(1, 3, 10, 10); var ledger = new HallRequestLedger(config, 0);
        var served = hall(1, 0); var pending = hall(2, 0); var alias = hall(2, 0);
        ledger.receive(served); var command = new Command.Pickup(1, 1, served); ledger.dispatched(served.getId(), command);
        ledger.onEvent(accept(1, command, config)); ledger.onEvent(complete(2, command, config));
        ledger.receive(pending); ledger.receive(alias); ledger.cancelForReset();
        check(ledger.get(served.getId()).orElseThrow().status() == SERVED, "Servicio confirmado no se convierte en cancelado");
        check(ledger.get(pending.getId()).orElseThrow().status() == CANCELLED && ledger.get(alias.getId()).orElseThrow().status() == CANCELLED, "Canónica y alias invalidados");
    }

    public static void staleEventsCannotPoisonNewEpochOrSequence() {
        var config = new SimulationConfig(1, 3, 10, 10); var fresh = hall(1, 1);
        var old = new HallRequest(fresh.getId(), 0, 1, fresh.getCreatedAt(), Direction.UP);
        var ledger = new HallRequestLedger(config, 1); ledger.receive(fresh);
        var command = new Command.Pickup(1, 1, fresh); ledger.dispatched(fresh.getId(), command);
        var oldCommand = new Command.Pickup(1, 1, old);
        ledger.onEvent(accept(Long.MAX_VALUE - 1, oldCommand, config)); ledger.onEvent(complete(Long.MAX_VALUE, oldCommand, config));
        check(ledger.get(fresh.getId()).orElseThrow().status() == DISPATCHED, "Época antigua no acepta ni completa");
        ledger.onEvent(accept(1, command, config)); ledger.onEvent(complete(2, command, config));
        check(ledger.get(fresh.getId()).orElseThrow().status() == SERVED, "Secuencia vieja no bloquea hechos nuevos");
    }

    public static void staleAdmissionDoesNotReserveFreshIdentity() {
        var config = new SimulationConfig(1, 3, 10, 10); var old = hall(2, 0);
        var fresh = new HallRequest(old.getId(), 1, 2, Instant.now(), Direction.UP);
        var ledger = new HallRequestLedger(config, 1);
        check(ledger.receive(old).kind() == HallRequestLedger.AdmissionKind.REJECTED, "Rechazo por época");
        check(ledger.receive(fresh).kind() == HallRequestLedger.AdmissionKind.NEW, "Identidad no contaminada");
    }

    public static void managerResetWakesStopAndCancelsInbox() throws Exception {
        try (var rig = new ManagerRig(20)) {
            rig.m.requestStop().get(10, TimeUnit.SECONDS); var old = hall(2, 0); rig.m.submit(old);
            check(rig.m.requestReset(1).get(10, TimeUnit.SECONDS) == 1L, "Manejador detenido adopta reset");
            check(rig.m.awaitResult(LIMIT).orElseThrow().outcome() == ManagerWorker.Outcome.CANCELLED, "Inbox viejo cancelado");
            check(rig.m.calls().isEmpty() && !rig.m.submit(hall(2, 1)), "Sin admisión antes de continuar");
            expectFailure(rig.m.requestStop()); rig.w.requestReset(new Command.Reset(100, 1, 1)).get(10, TimeUnit.SECONDS);
            check(rig.w.resume() && rig.m.resume(), "Ambos continúan");
            var fresh = new HallRequest(old.getId(), 1, 2, Instant.now(), Direction.UP); rig.m.submit(fresh); awaitStatus(rig.m, fresh, SERVED);
        }
    }

    public static void managerResetWaitsForInFlightDispatch() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1); var ids = new AtomicLong();
        var config = new SimulationConfig(1, 5, 20, 10); var w = new ElevatorWorker(1, config, 0);
        var m = new ManagerWorker(config, 0, List.of(w), () -> {
            long id = ids.incrementAndGet();
            if (id == 1) {
                entered.countDown();
                try { check(release.await(10, TimeUnit.SECONDS), "Generador de prueba liberado"); }
                catch (InterruptedException e) { throw new AssertionError(e); }
            }
            return id;
        });
        try {
            w.start(); m.start(); m.submit(hall(3, 0)); check(entered.await(10, TimeUnit.SECONDS), "Despacho en vuelo observado");
            var reset = m.requestReset(1); check(!reset.isDone(), "No confirma mientras sigue en vuelo");
            w.requestReset(new Command.Reset(100, 1, 1)).get(10, TimeUnit.SECONDS); release.countDown(); reset.get(10, TimeUnit.SECONDS);
            check(m.calls().isEmpty() && m.epoch() == 1, "Adopción limpia después del despacho");
        } finally { release.countDown(); m.close(); w.close(); check(m.awaitTermination(LIMIT) && w.awaitTermination(LIMIT), "Actores terminados"); }
    }

    public static void managerIgnoresBufferedOldCompletions() throws Exception {
        try (var rig = new ManagerRig(80)) {
            var old = hall(3, 0); rig.m.submit(old); awaitStatus(rig.m, old, ASSIGNED);
            rig.m.requestStop().get(10, TimeUnit.SECONDS); rig.w.awaitService(LIMIT).orElseThrow();
            rig.m.requestReset(1).get(10, TimeUnit.SECONDS); rig.w.requestReset(new Command.Reset(100, 1, 1)).get(10, TimeUnit.SECONDS);
            check(rig.w.resume() && rig.m.resume(), "Continúa época nueva");
            var fresh = new HallRequest(old.getId(), 1, 2, Instant.now(), Direction.UP); rig.m.submit(fresh);
            var done = awaitStatus(rig.m, fresh, SERVED);
            check(done.request().getEpoch() == 1 && rig.m.calls().size() == 1, "Confirmación vieja no restaura seguimiento");
        }
    }

    public static void invalidManagerResetDoesNotKillActor() throws Exception {
        try (var rig = new ManagerRig(20)) {
            expectFailure(rig.m.requestReset(0)); expectFailure(rig.m.requestReset(-1));
            var fresh = hall(2, 0); check(rig.m.submit(fresh), "Sigue recibiendo tras rechazo"); awaitStatus(rig.m, fresh, SERVED);
        }
    }

    public static void replayedManagerResetDoesNotClearNewWork() throws Exception {
        try (var rig = new ManagerRig(20)) {
            rig.m.requestReset(1).get(10, TimeUnit.SECONDS); rig.w.requestReset(new Command.Reset(100, 1, 1)).get(10, TimeUnit.SECONDS);
            check(rig.w.resume() && rig.m.resume(), "Continúa"); var fresh = hall(2, 1); rig.m.submit(fresh); awaitStatus(rig.m, fresh, SERVED);
            rig.m.requestReset(1).get(10, TimeUnit.SECONDS);
            check(rig.m.call(fresh.getId()).orElseThrow().status() == SERVED, "Replay no vuelve a limpiar");
            var other = hall(3, 1); check(rig.m.submit(other), "Replay no detiene ejecución nueva"); awaitStatus(rig.m, other, SERVED);
        }
    }

    public static void closedManagerRejectsReset() throws Exception {
        var config = new SimulationConfig(1, 1, 1, 1); var w = new ElevatorWorker(1, config, 0);
        var m = new ManagerWorker(config, 0, List.of(w), new AtomicLong()::incrementAndGet);
        expectFailure(m.requestReset(1)); m.close(); w.close(); expectFailure(m.requestReset(1));
        check(m.awaitTermination(LIMIT) && w.awaitTermination(LIMIT), "Cierre de actores nuevos");
    }

    public static void oldManagerRequestsCannotReappearAfterResume() throws Exception {
        try (var rig = new ManagerRig(20)) {
            rig.m.requestReset(1).get(10, TimeUnit.SECONDS); rig.w.requestReset(new Command.Reset(100, 1, 1)).get(10, TimeUnit.SECONDS);
            check(rig.w.resume() && rig.m.resume(), "Continúa"); rig.m.submit(hall(2, 0));
            check(rig.m.awaitResult(LIMIT).orElseThrow().outcome() == ManagerWorker.Outcome.REJECTED, "Entrada antigua rechazada");
            check(rig.m.calls().isEmpty(), "No queda pendiente antiguo");
        }
    }

    public static void currentSnapshotDoesNotAuthorizeOldCommandEvent() throws Exception {
        var config = new SimulationConfig(1, 3, 10, 10); var c = new SimulationController(config, 1);
        try {
            var oldCommand = new Command.Pickup(1, 1, hall(2, 0));
            var current = new ElevatorState(1, config, 1).snapshot(0);
            var rejected = new ExecutionEvent(1, COMMAND_REJECTED, current, Optional.of(oldCommand), Optional.empty(), "época anterior");
            check(!c.isCurrent(rejected), "Snapshot nuevo no vuelve vigente un comando viejo");
            var physical = new ExecutionEvent(2, DIRECTION_CHANGED, current, Optional.empty(), Optional.empty(), "físico");
            check(c.isCurrent(physical), "Evento físico sin comando se conserva");
        } finally { c.close(); check(c.awaitTermination(LIMIT), "Cierre"); }
    }

    private static ExecutionEvent accept(long sequence, Command.Pickup command, SimulationConfig config) {
        var state = new ElevatorState(1, config, command.epoch()); state.addPendingRequest(command.request());
        return new ExecutionEvent(sequence, COMMAND_ACCEPTED, state.snapshot(0), Optional.of(command), Optional.of(command.request().getId()), "aceptado");
    }
    private static ExecutionEvent complete(long sequence, Command.Pickup command, SimulationConfig config) {
        var state = new ElevatorState(1, config, command.epoch()); state.addPendingRequest(command.request()); state.beginDwell(Direction.UP, 0);
        return new ExecutionEvent(sequence, COMMAND_COMPLETED, state.snapshot(0), Optional.of(command), Optional.of(command.request().getId()), "servido");
    }
    private static HallCallSnapshot awaitStatus(ManagerWorker m, HallRequest request, HallCallSnapshot.Status status) throws Exception {
        long began = System.nanoTime();
        while (true) {
            long left = LIMIT.toNanos() - (System.nanoTime() - began); check(left > 0, "Plazo de estado");
            var update = m.awaitUpdate(Duration.ofNanos(left)).orElseThrow();
            if (update.request().getId().equals(request.getId()) && update.request().getEpoch() == request.getEpoch() && update.status() == status) { return update; }
        }
    }
    private static CabinRequest cabin(int id, int floor, long epoch) { return new CabinRequest(UUID.randomUUID(), epoch, floor, Instant.now(), id); }
    private static HallRequest hall(int floor, long epoch) { return new HallRequest(UUID.randomUUID(), epoch, floor, Instant.now(), Direction.UP); }
    private static SimulationController.Result get(CompletableFuture<SimulationController.Result> future) throws Exception { return future.get(10, TimeUnit.SECONDS); }
    private static void expectFailure(CompletableFuture<?> future) throws Exception {
        try { future.get(10, TimeUnit.SECONDS); throw new AssertionError("Debía fallar"); }
        catch (ExecutionException expected) { check(expected.getCause() != null, "Causa disponible"); }
    }
    private static void check(boolean value, String detail) { if (!value) { throw new AssertionError(detail); } }
    private static void ready(SimulationController c) {
        check(c.state() == SimulationState.RESET && c.calls().isEmpty(), "RESET global limpio");
        check(c.snapshots().stream().allMatch(s -> s.epoch() == c.epoch() && s.currentFloor() == 1 && s.direction() == Direction.UP
                && s.phase() == ElevatorPhase.IDLE && s.mode() == ElevatorSnapshot.Mode.NORMAL
                && s.cabinRequests().isEmpty() && s.hallRequests().isEmpty()), "Todas en 1/UP y época vigente");
    }

    private static final class Rig implements AutoCloseable {
        final SimulationController c; final List<ExecutionEvent> trace = new ArrayList<>();
        Rig(int n, int floors, long move, long dwell) { c = new SimulationController(new SimulationConfig(n, floors, move, dwell), 0); }
        ExecutionEvent until(ExecutionEvent.Type type) throws Exception { return until(e -> e.type() == type); }
        ExecutionEvent until(Predicate<ExecutionEvent> predicate) throws Exception {
            long began = System.nanoTime();
            while (true) {
                long left = LIMIT.toNanos() - (System.nanoTime() - began); check(left > 0, "Plazo de evento");
                var event = c.awaitEvent(Duration.ofNanos(left)).orElseThrow(); trace.add(event);
                check(event.type() != WORKER_FAILED, "Cabina sin fallo"); if (predicate.test(event)) { return event; }
            }
        }
        void drain() throws Exception {
            while (true) { var event = c.awaitEvent(Duration.ZERO); if (event.isEmpty()) { return; } trace.add(event.orElseThrow()); }
        }
        long count(ExecutionEvent.Type type) { return trace.stream().filter(e -> e.type() == type).count(); }
        @Override public void close() throws Exception {
            c.close(); check(c.awaitTermination(LIMIT), "No quedan actores vivos"); check(c.failure().isEmpty(), "Controlador sin fallo: " + c.failure());
            check(c.elevatorLifecycles().stream().allMatch(l -> l == ElevatorWorker.Lifecycle.TERMINATED), "Cabinas terminadas sin fallos");
            check(c.managerLifecycle() == ManagerWorker.Lifecycle.TERMINATED, "Manejador terminado sin fallo");
        }
    }
    private static final class ManagerRig implements AutoCloseable {
        final ElevatorWorker w; final ManagerWorker m;
        ManagerRig(long movement) {
            var config = new SimulationConfig(1, 5, movement, 10); w = new ElevatorWorker(1, config, 0);
            m = new ManagerWorker(config, 0, List.of(w), new AtomicLong()::incrementAndGet); w.start(); m.start();
        }
        @Override public void close() throws Exception {
            m.close(); w.close(); check(m.awaitTermination(LIMIT) && w.awaitTermination(LIMIT), "Actores terminados");
            check(m.failure().isEmpty() && w.failure().isEmpty(), "Actores sanos");
        }
    }

    public static void main(String[] args) throws Exception {
        initialResetConfirmsEveryParticipant(); waitsForSlowestCabin(); resetFromStoppedReturnsWithoutStart();
        resetSupersedesGlobalStopping(); openDwellIsCancelledGlobally(); repeatedResetSharesOneEpoch();
        anotherResetAfterReadyCreatesNewEpoch(); controlsAreRejectedWhileResetting(); oldAdmissionResponseCanBeRecognized();
        extractedOldEventMustBeRevalidated(); bufferedOldEventsAreNotReturnedAsCurrent(); staleManualRequestsCannotReenterNewRun();
        freshEpochMayReuseCancelledUuid(); commandIdsKeepGrowingAcrossReset(); oneFloorResetAndContinuationWork();
        exhaustedEpochDoesNotWrapOrStartWorkers(); closeCancelsPendingGlobalReset(); closeAfterResetWakesHeldActors();
        edtRemainsResponsiveDuringGlobalReset(); rapidResetStartStopCyclesPreserveActors(); cancelledCallerFutureDoesNotCancelGlobalReset();
        completedWorkDoesNotReappearAfterStart(); ledgerInvalidatesOpenTrackingButKeepsServedHistory(); staleEventsCannotPoisonNewEpochOrSequence();
        staleAdmissionDoesNotReserveFreshIdentity(); managerResetWakesStopAndCancelsInbox(); managerResetWaitsForInFlightDispatch();
        managerIgnoresBufferedOldCompletions(); invalidManagerResetDoesNotKillActor(); replayedManagerResetDoesNotClearNewWork();
        closedManagerRejectsReset(); oldManagerRequestsCannotReappearAfterResume();
        currentSnapshotDoesNotAuthorizeOldCommandEvent();
        System.out.println("T38: 33 escenarios satisfactorios.");
    }
}
