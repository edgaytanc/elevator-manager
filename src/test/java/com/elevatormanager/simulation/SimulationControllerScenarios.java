package com.elevatormanager.simulation;

import com.elevatormanager.model.Direction;
import com.elevatormanager.model.SimulationConfig;
import com.elevatormanager.model.SimulationState;
import com.elevatormanager.model.request.CabinRequest;
import com.elevatormanager.model.request.HallRequest;
import com.elevatormanager.model.request.Request;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.swing.SwingUtilities;
import static com.elevatormanager.simulation.SimulationController.Outcome.*;
import static com.elevatormanager.simulation.ExecutionEvent.Type.*;

/** Escenarios ejecutables con Java 17, también utilizados por JUnit. */
public final class SimulationControllerScenarios {
    private static final Duration LIMIT = Duration.ofSeconds(8);
    private SimulationControllerScenarios() { }

    public static void initialState() throws Exception {
        var c = create(3, 5);
        try {
            check(c.state() == SimulationState.RESET, "Estado inicial RESET");
            check(c.elevatorLifecycles().stream().allMatch(l -> l == ElevatorWorker.Lifecycle.NEW), "Sin arranque implícito");
            check(c.snapshots().size() == 3 && c.snapshots().stream().allMatch(s ->
                    s.currentFloor() == 1 && s.direction() == Direction.UP), "Tres cabinas en 1/UP");
            check(c.managerLifecycle() == com.elevatormanager.dispatch.ManagerWorker.Lifecycle.NEW, "Manejador nuevo");
            try { c.snapshots().clear(); throw new AssertionError("Lista mutable"); }
            catch (UnsupportedOperationException expected) { }
        } finally { finish(c); }
    }

    public static void repeatedStart() throws Exception {
        var c = create(2, 5);
        try {
            check(get(c.start()).outcome() == APPLIED, "Primer Start aplicado");
            for (int i = 0; i < 50; i++) { check(get(c.start()).outcome() == UNCHANGED, "Start idempotente"); }
            check(c.elevatorLifecycles().stream().allMatch(l -> l == ElevatorWorker.Lifecycle.RUNNING), "Siguen los mismos trabajadores");
        } finally { finish(c); }
    }

    public static void concurrentStart() throws Exception {
        var c = create(4, 5);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(8);
        try {
            var gate = new CountDownLatch(1);
            var results = new ArrayList<java.util.concurrent.Future<SimulationController.Result>>();
            for (int i = 0; i < 32; i++) {
                results.add(pool.submit(() -> { gate.await(); return get(c.start()); }));
            }
            gate.countDown();
            int applied = 0;
            for (var f : results) {
                var r = f.get(8, TimeUnit.SECONDS);
                check(r.outcome() == APPLIED || r.outcome() == UNCHANGED, "Arranque sin rechazos");
                if (r.outcome() == APPLIED) { applied++; }
            }
            check(applied == 1, "Exactamente un arranque entre 32 productores");
        } finally { pool.shutdownNow(); finish(c); }
    }

    public static void rejectsBeforeStart() throws Exception {
        var c = create(1, 5);
        try {
            check(get(c.submit(cabin(1, 2))).outcome() == REJECTED, "Destino rechazado en RESET");
            check(get(c.submit(hall(2))).outcome() == REJECTED, "Externa rechazada en RESET");
            get(c.start());
            check(c.awaitEvent(Duration.ZERO).isEmpty(), "Rechazos no generan comandos");
        } finally { finish(c); }
    }

    public static void routesPolymorphically() throws Exception {
        var c = create(2, 5);
        try {
            get(c.start());
            Request a = cabin(2, 4); Request b = hall(3);
            check(get(c.submit(a)).outcome() == ENQUEUED, "Interna enviada");
            check(get(c.submit(b)).outcome() == ENQUEUED, "Externa enviada");
            var trace = completed(c, 2);
            check(trace.stream().anyMatch(e -> e.type() == COMMAND_COMPLETED
                    && e.canonicalRequestId().orElseThrow().equals(a.getId()) && e.elevatorId() == 2), "Destino en su cabina");
            check(trace.stream().anyMatch(e -> e.type() == COMMAND_COMPLETED
                    && e.canonicalRequestId().orElseThrow().equals(b.getId())
                    && e.snapshot().direction() == Direction.UP), "Externa asignada y atendida");
        } finally { finish(c); }
    }

    public static void validatesContext() throws Exception {
        var c = create(1, 5);
        try {
            get(c.start());
            List<Request> invalid = List.of(cabin(2, 3), cabin(1, 6),
                    new HallRequest(UUID.randomUUID(), 0, 5, Instant.now(), Direction.UP),
                    new HallRequest(UUID.randomUUID(), 0, 1, Instant.now(), Direction.DOWN),
                    new CabinRequest(UUID.randomUUID(), 9, 2, Instant.now(), 1));
            for (var r : invalid) { check(get(c.submit(r)).outcome() == REJECTED, "Validación contextual"); }
            check(c.awaitEvent(Duration.ZERO).isEmpty() && c.calls().isEmpty(), "Sin admisiones parciales");
        } finally { finish(c); }
    }

    public static void repeatedStartDoesNotReplay() throws Exception {
        var c = create(1, 5);
        try {
            get(c.start()); get(c.submit(cabin(1, 3)));
            for (int i = 0; i < 30; i++) { get(c.start()); }
            var trace = completed(c, 1);
            untilStopEnded(c, trace);
            check(trace.stream().filter(e -> e.type() == COMMAND_ACCEPTED).count() == 1, "Un comando admitido");
            check(trace.stream().filter(e -> e.type() == COMMAND_COMPLETED).count() == 1, "Un servicio");
            check(c.awaitEvent(Duration.ZERO).isEmpty(), "Sin repeticiones pendientes");
        } finally { finish(c); }
    }

    public static void sharedCommandSequence() throws Exception {
        var c = create(1, 5);
        try {
            get(c.start()); get(c.submit(cabin(1, 4))); get(c.submit(hall(2)));
            var trace = completed(c, 2);
            var ids = new HashSet<Long>();
            long accepted = trace.stream().filter(e -> e.type() == COMMAND_ACCEPTED)
                    .peek(e -> check(ids.add(e.command().orElseThrow().commandId()), "ID único entre productores"))
                    .count();
            check(accepted == 2, "Dos comandos admitidos sin colisión");
        } finally { finish(c); }
    }

    public static void duplicatesRemainCanonical() throws Exception {
        var c = create(1, 5);
        try {
            get(c.start()); var request = cabin(1, 3);
            get(c.submit(request)); completed(c, 1);
            get(c.submit(request));
            var event = nextMatching(c, COMMAND_COMPLETED, COMMAND_DUPLICATE);
            check(event.type() == COMMAND_DUPLICATE, "Reenvío atendido no vuelve a servir");
        } finally { finish(c); }
    }

    public static void oneFloor() throws Exception {
        var c = create(1, 1);
        try {
            get(c.start());
            check(get(c.submit(hall(1))).outcome() == REJECTED, "Sin externa imposible");
            get(c.submit(cabin(1, 1)));
            check(completed(c, 1).stream().noneMatch(e -> e.type() == DEPARTED), "Servicio sin movimiento");
        } finally { finish(c); }
    }

    public static void edtDoesNotWait() throws Exception {
        var c = create(1, 5);
        try {
            var started = new java.util.concurrent.atomic.AtomicReference<CompletableFuture<SimulationController.Result>>();
            SwingUtilities.invokeAndWait(() -> {
                started.set(c.start());
                try { c.awaitTermination(Duration.ofMillis(1)); throw new AssertionError("Espera en EDT"); }
                catch (IllegalStateException expected) { }
                catch (InterruptedException e) { throw new AssertionError(e); }
                try { c.awaitEvent(Duration.ofMillis(1)); throw new AssertionError("Eventos bloqueantes en EDT"); }
                catch (IllegalStateException expected) { }
                catch (InterruptedException e) { throw new AssertionError(e); }
            });
            check(get(started.get()).outcome() == APPLIED, "Start desde EDT");
            get(c.submit(cabin(1, 5)));
            var pulse = new CountDownLatch(1);
            SwingUtilities.invokeLater(pulse::countDown);
            check(pulse.await(2, TimeUnit.SECONDS), "EDT responde durante el viaje");
        } finally { finish(c); }
    }

    public static void closeAndRejectRestart() throws Exception {
        var c = create(2, 5);
        get(c.start()); get(c.submit(cabin(1, 5)));
        c.close(); c.close();
        check(c.awaitTermination(LIMIT), "Terminación confirmada");
        check(c.state() == SimulationState.CLOSED, "CLOSED tras terminación");
        check(get(c.start()).outcome() == REJECTED, "No reinicia sesión cerrada");
        check(get(c.submit(cabin(1, 2))).outcome() == REJECTED, "No admite tras cerrar");
        check(c.elevatorLifecycles().stream().allMatch(l -> l == ElevatorWorker.Lifecycle.TERMINATED), "Cabinas terminadas");
        check(c.failure().isEmpty(), "Sin fallo de coordinación");
    }

    public static void closeBeforeStart() throws Exception {
        var c = create(1, 5);
        finish(c);
        check(get(c.start()).outcome() == REJECTED, "Cierre previo impide Start");
    }

    public static void validatesArgumentsAndTimeouts() throws Exception {
        try { new SimulationController(null, 0); throw new AssertionError("null"); }
        catch (NullPointerException expected) { }
        try { new SimulationController(new SimulationConfig(1, 1, 1, 1), -1); throw new AssertionError("época"); }
        catch (IllegalArgumentException expected) { }
        var c = create(1, 5);
        try {
            try { c.submit(null); throw new AssertionError("null request"); }
            catch (NullPointerException expected) { }
            try { c.awaitTermination(Duration.ofNanos(-1)); throw new AssertionError("negativo"); }
            catch (IllegalArgumentException expected) { }
            check(!c.awaitTermination(Duration.ZERO), "Sesión nueva no está cerrada");
        } finally { finish(c); }
    }

    private static SimulationController create(int n, int floors) {
        return new SimulationController(new SimulationConfig(n, floors, 30, 15), 0);
    }
    private static CabinRequest cabin(int id, int floor) {
        return new CabinRequest(UUID.randomUUID(), 0, floor, Instant.now(), id);
    }
    private static HallRequest hall(int floor) {
        return new HallRequest(UUID.randomUUID(), 0, floor, Instant.now(), Direction.UP);
    }
    private static SimulationController.Result get(CompletableFuture<SimulationController.Result> f) throws Exception {
        return f.get(8, TimeUnit.SECONDS);
    }
    private static List<ExecutionEvent> completed(SimulationController c, int count) throws Exception {
        var trace = new ArrayList<ExecutionEvent>();
        long began = System.nanoTime();
        while (count > 0) {
            long left = LIMIT.toNanos() - (System.nanoTime() - began);
            check(left > 0, "Plazo de servicio");
            var e = c.awaitEvent(Duration.ofNanos(left)).orElseThrow(() -> new AssertionError("Falta evento"));
            check(e.type() != WORKER_FAILED, "Trabajador sano"); trace.add(e);
            if (e.type() == COMMAND_COMPLETED) { count--; }
        }
        return trace;
    }
    private static ExecutionEvent nextMatching(SimulationController c, ExecutionEvent.Type a,
            ExecutionEvent.Type b) throws Exception {
        long began = System.nanoTime();
        while (true) {
            long left = LIMIT.toNanos() - (System.nanoTime() - began);
            check(left > 0, "Plazo de evento");
            var e = c.awaitEvent(Duration.ofNanos(left)).orElseThrow();
            if (e.type() == a || e.type() == b) { return e; }
        }
    }
    private static void untilStopEnded(SimulationController c, List<ExecutionEvent> trace) throws Exception {
        trace.add(nextMatching(c, STOP_ENDED, WORKER_FAILED));
        check(trace.get(trace.size() - 1).type() == STOP_ENDED, "Parada terminada");
    }
    private static void finish(SimulationController c) throws Exception {
        c.close(); check(c.awaitTermination(LIMIT), "Sin hilos vivos tras cierre");
    }
    private static void check(boolean value, String detail) {
        if (!value) { throw new AssertionError(detail); }
    }

    public static void main(String[] args) throws Exception {
        initialState(); repeatedStart(); concurrentStart(); rejectsBeforeStart();
        routesPolymorphically(); validatesContext(); repeatedStartDoesNotReplay();
        sharedCommandSequence(); duplicatesRemainCanonical(); oneFloor(); edtDoesNotWait();
        closeAndRejectRestart(); closeBeforeStart(); validatesArgumentsAndTimeouts();
        System.out.println("T35: 14 escenarios satisfactorios.");
    }
}
