package com.elevatormanager.simulation;

import com.elevatormanager.dispatch.ManagerWorker;
import com.elevatormanager.dispatch.HallCallSnapshot;
import com.elevatormanager.model.*;
import com.elevatormanager.model.request.*;
import com.elevatormanager.protocol.Command;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.swing.SwingUtilities;
import static com.elevatormanager.simulation.ExecutionEvent.Type.*;
import static com.elevatormanager.simulation.SimulationController.Outcome.*;

/** T36: modelo con reloj explícito y barreras con actores reales, sin sleeps. */
public final class StopContinuationScenarios {
    private static final Duration LIMIT = Duration.ofSeconds(8);
    private StopContinuationScenarios() { }

    public static void modelPreservesDwellRemaining() {
        var s = new ElevatorState(1, new SimulationConfig(1, 5, 10, 200), 0);
        s.addPendingRequest(cabin(1, 1)); s.beginDwell(Direction.UP, 0);
        s.holdDwellForStop(50_000_000L);
        var frozen = s.snapshot(9_000_000_000L);
        check(frozen.phase() == ElevatorPhase.HELD, "Parada congelada");
        check(frozen.remainingNanos() == 150_000_000L, "Restan exactamente 150 ms");
        check(frozen.holdReason().orElseThrow() == ElevatorSnapshot.HoldReason.STOP, "Motivo Stop");
        s.resumeAfterStop(10_000_000_000L);
        check(!s.completeDwellIfDue(10_149_999_999L), "No termina antes");
        check(s.completeDwellIfDue(10_150_000_000L), "Termina consumiendo solo el restante");
    }

    public static void modelDoesNotFreezeMovingLeg() {
        var s = new ElevatorState(1, new SimulationConfig(1, 5, 100, 50), 0);
        s.beginJourney(4, 0); s.holdDwellForStop(40_000_000L);
        check(s.getPhase() == ElevatorPhase.MOVING, "Stop no congela el tramo");
        check(s.completeLegIfDue(100_000_000L) && s.getCurrentFloor() == 2, "Llegada adyacente");
        check(s.getCommittedFloor().orElseThrow() == 4, "Compromiso conservado");
    }

    public static void modelDueDwellDoesNotRestart() {
        var s = new ElevatorState(1, new SimulationConfig(1, 1, 1, 20), 0);
        s.addPendingRequest(cabin(1, 1)); s.beginDwell(Direction.UP, 0);
        s.holdDwellForStop(20_000_000L);
        check(s.snapshot(100_000_000L).remainingNanos() == 0, "Restante agotado válido");
        s.resumeAfterStop(200_000_000L);
        check(s.completeDwellIfDue(200_000_000L), "No reinicia los 20 ms");
    }

    public static void stopInResetDoesNotStartWorkers() throws Exception {
        try (var rig = new Rig(1, 5, 30, 20)) {
            check(get(rig.c.stop()).outcome() == UNCHANGED, "Stop en RESET no-op");
            check(rig.c.state() == SimulationState.RESET, "Sigue RESET");
            check(rig.c.elevatorLifecycles().get(0) == ElevatorWorker.Lifecycle.NEW, "No arrancó");
        }
    }

    public static void idleStopHasGlobalBarrier() throws Exception {
        try (var rig = new Rig(4, 5, 30, 20)) {
            get(rig.c.start()); check(get(rig.c.stop()).state() == SimulationState.STOPPED, "Barrera global");
            check(rig.c.elevatorLifecycles().stream().allMatch(l -> l == ElevatorWorker.Lifecycle.RUNNING), "Hilos vivos y conservados");
            check(rig.c.managerLifecycle() == ManagerWorker.Lifecycle.RUNNING, "Mismo manejador");
            rig.drain();
            check(rig.trace.stream().filter(e -> e.type() == CONTROL_STOPPED).count() == 4, "Confirman cuatro cabinas");
            check(get(rig.c.start()).state() == SimulationState.RUNNING, "Continúa desde STOPPED");
        }
    }

    public static void movementStopsAtNextFloor() throws Exception {
        try (var rig = new Rig(1, 5, 200, 20)) {
            get(rig.c.start()); var r = cabin(1, 4); get(rig.c.submit(r)); rig.until(DEPARTED);
            get(rig.c.stop()); rig.drain();
            var s = rig.c.snapshots().get(0);
            check(s.currentFloor() == 2 && s.phase() == ElevatorPhase.IDLE, "Solo termina el tramo iniciado");
            check(s.committedFloor().orElseThrow() == 4 && s.cabinRequests().contains(r), "Conserva ruta y pendiente");
            check(rig.trace.stream().noneMatch(e -> e.type() == COMMAND_COMPLETED), "Sin servicio durante detención");
            rig.quiet(); get(rig.c.start()); rig.until(COMMAND_COMPLETED);
            check(rig.c.snapshots().get(0).currentFloor() == 4, "Continúa hasta destino original");
        }
    }

    public static void arrivalIsNotService() throws Exception {
        try (var rig = new Rig(1, 3, 200, 20)) {
            get(rig.c.start()); get(rig.c.submit(cabin(1, 2))); rig.until(DEPARTED);
            get(rig.c.stop()); rig.drain();
            check(rig.c.snapshots().get(0).currentFloor() == 2, "Llegó físicamente");
            check(rig.trace.stream().noneMatch(e -> e.type() == STOP_STARTED || e.type() == COMMAND_COMPLETED), "No abre parada nueva");
            get(rig.c.start()); rig.until(COMMAND_COMPLETED);
            check(rig.trace.stream().filter(e -> e.type() == DEPARTED).count() == 1, "No vuelve a viajar al piso actual");
        }
    }

    public static void dwellFreezesAndContinues() throws Exception {
        try (var rig = new Rig(1, 3, 30, 600)) {
            get(rig.c.start()); get(rig.c.submit(cabin(1, 1))); rig.until(COMMAND_COMPLETED);
            get(rig.c.stop()); rig.drain();
            var s = rig.c.snapshots().get(0);
            check(s.phase() == ElevatorPhase.HELD && s.remainingNanos() > 0, "Parada con restante congelado");
            rig.quiet(); check(rig.c.snapshots().get(0).equals(s), "Snapshot no avanza estando detenido");
            get(rig.c.start()); var resumed = rig.until(CONTROL_RESUMED); var end = rig.until(STOP_ENDED);
            check(end.referenceNanos() - resumed.referenceNanos() >= s.remainingNanos(), "Consume restante completo");
            check(rig.trace.stream().filter(e -> e.type() == COMMAND_COMPLETED).count() == 1, "No vuelve a servir");
        }
    }

    public static void repeatedStopIsIdempotent() throws Exception {
        try (var rig = new Rig(1, 5, 180, 20)) {
            get(rig.c.start()); get(rig.c.submit(cabin(1, 4))); rig.until(DEPARTED);
            var a = rig.c.stop(); var b = rig.c.stop();
            check(get(a).state() == SimulationState.STOPPED && get(b).state() == SimulationState.STOPPED, "Comparten barrera");
            check(get(rig.c.stop()).outcome() == UNCHANGED, "Stop ya detenido no-op");
            rig.drain();
            check(rig.trace.stream().filter(e -> e.type() == CONTROL_STOPPED).count() == 1, "Una confirmación física");
        }
    }

    public static void rejectsStartWhileStopping() throws Exception {
        try (var rig = new Rig(1, 5, 500, 20)) {
            get(rig.c.start()); get(rig.c.submit(cabin(1, 4))); rig.until(DEPARTED);
            var stopping = rig.c.stop();
            check(get(rig.c.start()).outcome() == REJECTED, "Start en STOPPING rechazado");
            check(get(stopping).state() == SimulationState.STOPPED, "Termina barrera original");
        }
    }

    public static void rejectsRequestsWhileStopping() throws Exception {
        try (var rig = new Rig(1, 5, 500, 20)) {
            get(rig.c.start()); get(rig.c.submit(cabin(1, 4))); rig.until(DEPARTED);
            var stopping = rig.c.stop();
            check(get(rig.c.submit(hall(2))).outcome() == REJECTED, "Externa en STOPPING rechazada");
            check(get(rig.c.submit(cabin(1, 3))).outcome() == REJECTED, "Interna en STOPPING rechazada");
            get(stopping);
        }
    }

    public static void rejectsRequestsWhileStopped() throws Exception {
        try (var rig = new Rig(1, 5, 30, 20)) {
            get(rig.c.start()); get(rig.c.stop());
            check(get(rig.c.submit(hall(2))).outcome() == REJECTED, "No acepta externa en STOPPED");
            check(get(rig.c.submit(cabin(1, 3))).outcome() == REJECTED, "No acepta interna en STOPPED");
            check(rig.c.calls().isEmpty(), "Sin admisiones residuales");
        }
    }

    public static void cabinInboxSurvivesStop() throws Exception {
        var w = new ElevatorWorker(1, new SimulationConfig(1, 5, 20, 10), 0);
        try {
            w.start(); getVoid(w.requestStop());
            var r = cabin(1, 3); check(w.submit(new Command.Destination(1, 1, r)), "Conserva comando en vuelo");
            check(w.awaitResult(Duration.ofMillis(80)).isEmpty(), "No admite inbox mientras está detenido");
            check(w.resume(), "Mismo trabajador continúa");
            check(w.awaitResult(LIMIT).orElseThrow().outcome() == ElevatorWorker.Outcome.ACCEPTED, "Admisión posterior");
            check(w.awaitService(LIMIT).orElseThrow().requests().contains(r), "Servicio posterior sin pérdida");
        } finally { w.close(); check(w.awaitTermination(LIMIT), "Cabina terminada"); }
    }

    public static void managerInboxSurvivesStop() throws Exception {
        var config = new SimulationConfig(1, 5, 20, 10);
        var w = new ElevatorWorker(1, config, 0);
        var ids = new AtomicLong(); var m = new ManagerWorker(config, 0, List.of(w), ids::incrementAndGet);
        try {
            w.start(); m.start(); getVoid(m.requestStop());
            var r = hall(3); m.submit(r);
            check(m.awaitResult(Duration.ofMillis(80)).isEmpty() && ids.get() == 0, "Sin despacho tras confirmar Stop");
            check(m.resume(), "Continúa manejador");
            check(m.awaitResult(LIMIT).orElseThrow().outcome() == ManagerWorker.Outcome.DISPATCHED, "Conservó inbox");
            awaitServed(m, r.getId());
        } finally { m.close(); w.close(); check(m.awaitTermination(LIMIT) && w.awaitTermination(LIMIT), "Actores terminados"); }
    }

    public static void inFlightDispatchIsCoveredByBarrier() throws Exception {
        var config = new SimulationConfig(1, 5, 20, 10);
        var w = new ElevatorWorker(1, config, 0);
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var m = new ManagerWorker(config, 0, List.of(w), () -> {
            entered.countDown();
            try { check(release.await(8, TimeUnit.SECONDS), "Liberar generador de prueba"); }
            catch (InterruptedException e) { throw new AssertionError(e); }
            return 1;
        });
        try {
            w.start(); getVoid(w.requestStop()); m.start(); var r = hall(3); m.submit(r);
            check(entered.await(8, TimeUnit.SECONDS), "Despacho en vuelo observado");
            var barrier = m.requestStop(); check(!barrier.isDone(), "No confirma mientras sigue el despacho");
            release.countDown(); getVoid(barrier);
            check(m.awaitResult(LIMIT).orElseThrow().outcome() == ManagerWorker.Outcome.DISPATCHED, "Envío previo contabilizado");
            check(w.snapshot().hallRequests().isEmpty(), "Comando espera en inbox detenido");
            check(w.resume() && m.resume(), "Continúan ambos actores");
            awaitServed(m, r.getId());
        } finally { release.countDown(); m.close(); w.close(); check(m.awaitTermination(LIMIT) && w.awaitTermination(LIMIT), "Actores terminados"); }
    }

    public static void severalCabinsAndHallCallsContinue() throws Exception {
        try (var rig = new Rig(3, 6, 80, 20)) {
            get(rig.c.start());
            var requests = List.<Request>of(cabin(1, 5), cabin(2, 4), cabin(3, 6), hall(2), hall(3));
            for (var r : requests) { get(rig.c.submit(r)); }
            get(rig.c.stop()); rig.drain(); rig.quiet();
            check(rig.c.state() == SimulationState.STOPPED && rig.c.epoch() == 0, "Estado y época conservados");
            get(rig.c.start());
            var done = new HashSet<UUID>();
            for (var e : rig.trace) {
                if (e.type() == COMMAND_COMPLETED) { check(done.add(e.canonicalRequestId().orElseThrow()), "Sin servicio duplicado"); }
            }
            while (done.size() < requests.size()) {
                var e = rig.until(COMMAND_COMPLETED);
                check(done.add(e.canonicalRequestId().orElseThrow()), "Cada identidad se sirve una vez");
            }
            check(done.equals(new HashSet<>(requests.stream().map(Request::getId).toList())), "Termina todo el lote");
        }
    }

    public static void repeatedCyclesPreserveCompletedWork() throws Exception {
        try (var rig = new Rig(1, 3, 20, 600)) {
            get(rig.c.start()); get(rig.c.submit(cabin(1, 1))); rig.until(COMMAND_COMPLETED);
            for (int i = 0; i < 10; i++) { get(rig.c.stop()); get(rig.c.start()); }
            rig.until(STOP_ENDED); rig.drain();
            check(rig.trace.stream().filter(e -> e.type() == COMMAND_COMPLETED).count() == 1, "Diez ciclos no duplican servicio");
            long resumed = rig.trace.stream().filter(e -> e.type() == CONTROL_RESUMED).count();
            check(resumed >= 1 && resumed <= 10, "Las continuaciones efectivas usan el mismo actor");
        }
    }

    public static void edtRemainsResponsiveDuringStop() throws Exception {
        try (var rig = new Rig(1, 5, 250, 20)) {
            get(rig.c.start()); get(rig.c.submit(cabin(1, 4))); rig.until(DEPARTED);
            var future = new AtomicReference<CompletableFuture<SimulationController.Result>>();
            var pulse = new CountDownLatch(1);
            SwingUtilities.invokeAndWait(() -> { future.set(rig.c.stop()); SwingUtilities.invokeLater(pulse::countDown); });
            check(pulse.await(2, TimeUnit.SECONDS), "EDT libre mientras termina el tramo");
            check(get(future.get()).state() == SimulationState.STOPPED, "Confirmación asíncrona");
        }
    }

    public static void closeDuringStoppingDoesNotLeaveFuturePending() throws Exception {
        try (var rig = new Rig(1, 5, 500, 20)) {
            get(rig.c.start()); get(rig.c.submit(cabin(1, 4))); rig.until(DEPARTED);
            var f = rig.c.stop(); rig.c.close();
            check(get(f).outcome() == REJECTED, "Cierre invalida Stop pendiente");
            check(rig.c.awaitTermination(LIMIT), "No espera toda la ruta");
            check(rig.c.state() == SimulationState.CLOSED, "Cierre confirmado");
        }
    }

    public static void closeWhileStoppedWakesAllActors() throws Exception {
        try (var rig = new Rig(2, 5, 30, 20)) {
            get(rig.c.start()); get(rig.c.stop()); rig.c.close();
            check(rig.c.awaitTermination(LIMIT), "Despierta manejador y cabinas detenidos");
            check(get(rig.c.start()).outcome() == REJECTED && get(rig.c.stop()).outcome() == REJECTED, "Sesión cerrada no reinicia");
        }
    }

    public static void rapidControlsDoNotLoseWakeups() throws Exception {
        for (boolean dwelling : new boolean[] {false, true}) {
            try (var rig = new Rig(1, 1, 1, 60_000)) {
                get(rig.c.start());
                if (dwelling) {
                    get(rig.c.submit(cabin(1, 1)));
                    rig.until(COMMAND_COMPLETED);
                }
                for (int i = 0; i < 2_000; i++) {
                    check(get(rig.c.stop()).state() == SimulationState.STOPPED, "Barrera rápida " + i);
                    check(get(rig.c.start()).state() == SimulationState.RUNNING, "Continuación rápida " + i);
                }
            }
        }
    }

    private static void awaitServed(ManagerWorker m, UUID id) throws Exception {
        long began = System.nanoTime();
        while (true) {
            long remaining = LIMIT.toNanos() - (System.nanoTime() - began);
            check(remaining > 0, "Plazo de seguimiento");
            var update = m.awaitUpdate(Duration.ofNanos(remaining)).orElseThrow();
            if (update.request().getId().equals(id) && update.status() == HallCallSnapshot.Status.SERVED) { return; }
        }
    }
    private static CabinRequest cabin(int id, int floor) { return new CabinRequest(UUID.randomUUID(), 0, floor, Instant.now(), id); }
    private static HallRequest hall(int floor) { return new HallRequest(UUID.randomUUID(), 0, floor, Instant.now(), Direction.UP); }
    private static SimulationController.Result get(CompletableFuture<SimulationController.Result> f) throws Exception { return f.get(8, TimeUnit.SECONDS); }
    private static void getVoid(CompletableFuture<Void> f) throws Exception { f.get(8, TimeUnit.SECONDS); }
    private static void check(boolean value, String detail) { if (!value) { throw new AssertionError(detail); } }

    private static final class Rig implements AutoCloseable {
        final SimulationController c;
        final List<ExecutionEvent> trace = new ArrayList<>();
        Rig(int n, int floors, long move, long dwell) { c = new SimulationController(new SimulationConfig(n, floors, move, dwell), 0); }
        ExecutionEvent until(ExecutionEvent.Type type) throws Exception {
            long began = System.nanoTime();
            while (true) {
                long left = LIMIT.toNanos() - (System.nanoTime() - began);
                check(left > 0, "Plazo de evento " + type);
                var e = c.awaitEvent(Duration.ofNanos(left)).orElseThrow(); trace.add(e);
                check(e.type() != WORKER_FAILED, "Cabina sin fallo");
                if (e.type() == type) { return e; }
            }
        }
        void drain() throws Exception {
            while (true) { var e = c.awaitEvent(Duration.ZERO); if (e.isEmpty()) { return; } trace.add(e.orElseThrow()); }
        }
        void quiet() throws Exception { check(c.awaitEvent(Duration.ofMillis(80)).isEmpty(), "Sin avance tras barrera Stop"); }
        @Override public void close() throws Exception {
            c.close(); check(c.awaitTermination(LIMIT), "Sesión terminada");
            check(c.failure().isEmpty(), "Coordinador sin fallos");
            check(c.elevatorLifecycles().stream().allMatch(l -> l == ElevatorWorker.Lifecycle.TERMINATED), "Cabinas sin fallos");
            check(c.managerLifecycle() == ManagerWorker.Lifecycle.TERMINATED, "Manejador sin fallo");
        }
    }

    public static void main(String[] args) throws Exception {
        modelPreservesDwellRemaining(); modelDoesNotFreezeMovingLeg(); modelDueDwellDoesNotRestart();
        stopInResetDoesNotStartWorkers(); idleStopHasGlobalBarrier(); movementStopsAtNextFloor();
        arrivalIsNotService(); dwellFreezesAndContinues(); repeatedStopIsIdempotent();
        rejectsStartWhileStopping(); rejectsRequestsWhileStopping(); rejectsRequestsWhileStopped();
        cabinInboxSurvivesStop(); managerInboxSurvivesStop(); inFlightDispatchIsCoveredByBarrier();
        severalCabinsAndHallCallsContinue(); repeatedCyclesPreserveCompletedWork();
        edtRemainsResponsiveDuringStop(); closeDuringStoppingDoesNotLeaveFuturePending(); closeWhileStoppedWakesAllActors();
        rapidControlsDoNotLoseWakeups();
        System.out.println("T36: 21 escenarios satisfactorios.");
    }
}
