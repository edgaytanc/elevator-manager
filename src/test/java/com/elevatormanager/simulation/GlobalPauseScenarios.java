package com.elevatormanager.simulation;

import com.elevatormanager.dispatch.HallCallSnapshot;
import com.elevatormanager.model.Direction;
import com.elevatormanager.model.ElevatorPhase;
import com.elevatormanager.model.ElevatorSnapshot;
import com.elevatormanager.model.SimulationConfig;
import com.elevatormanager.model.SimulationState;
import com.elevatormanager.model.request.CabinRequest;
import com.elevatormanager.model.request.HallRequest;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import javax.swing.SwingUtilities;

/** T55: controlador y actores reales; sin abrir ventanas ni sustituir el motor. */
public final class GlobalPauseScenarios {
    private static final Duration LIMIT = Duration.ofSeconds(8);
    private GlobalPauseScenarios() { }

    public static void pauseAndResumeRequireValidState() throws Exception {
        try (var rig = new Session(1, 5, 50, 40)) {
            var c = rig.controller;
            check(get(c.pause()).outcome() == SimulationController.Outcome.REJECTED, "Pausa rechazada en RESET");
            check(get(c.resume()).outcome() == SimulationController.Outcome.REJECTED, "Resume rechazado en RESET");
            get(c.start()); get(c.stop());
            check(get(c.pause()).outcome() == SimulationController.Outcome.REJECTED, "STOPPED usa Start");
            check(get(c.resume()).outcome() == SimulationController.Outcome.REJECTED, "Resume no sustituye Start");
        }
    }

    public static void singleCabinIdleBarrier() throws Exception { idleBarrier(1); }
    public static void manyCabinsIdleBarrier() throws Exception { idleBarrier(6); }

    private static void idleBarrier(int count) throws Exception {
        try (var rig = new Session(count, 5, 50, 40)) {
            var c = rig.controller;
            get(c.start());
            check(get(c.pause()).outcome() == SimulationController.Outcome.APPLIED, "Pausa aplicada");
            check(c.state() == SimulationState.PAUSED, "Estado confirmado");
            check(c.pauseStatus().acknowledged() == count + 1, "Todos los actores reconocieron");
            var snapshots = c.snapshots();
            check(snapshots.stream().allMatch(s -> s.currentFloor() == 1 && s.phase() == ElevatorPhase.IDLE), "Reposo conservado");
            long reference = snapshots.get(0).referenceNanos();
            check(snapshots.stream().allMatch(s -> s.referenceNanos() == reference), "Una base temporal congelada");
            Thread.sleep(30);
            check(c.snapshots().equals(snapshots), "Reposo completamente estable");
            get(c.resume());
            check(c.state() == SimulationState.RUNNING, "Reanudado");
        }
    }

    public static void partialMovementPreservesRemainingTimeAndCommand() throws Exception {
        try (var rig = new Session(1, 5, 800, 30)) {
            var c = rig.controller;
            get(c.start());
            var request = cabin(1, 3);
            get(c.submit(request));
            var departure = event(c, e -> e.type() == ExecutionEvent.Type.DEPARTED);
            Thread.sleep(200);
            get(c.pause());
            var frozen = c.snapshot(1);
            held(frozen, ElevatorPhase.MOVING);
            check(frozen.currentFloor() == 1 && frozen.nextFloor().orElseThrow() == 2, "Mismo tramo parcial");
            check(frozen.remainingNanos() > 0 && frozen.remainingNanos() < 700_000_000L, "Progreso parcial capturado");
            check(frozen.cabinRequests().stream().anyMatch(r -> r.getId().equals(request.getId())), "Destino sigue encendido");
            drain(c);
            Thread.sleep(850); // Más que el tramo completo, sin consumir tiempo simulado.
            check(c.snapshot(1).equals(frozen), "Sin avance durante la pausa");
            check(c.awaitEvent(Duration.ZERO).isEmpty(), "Sin llegadas ni servicios nuevos");
            get(c.resume());
            await(() -> c.snapshot(1).phase() == ElevatorPhase.MOVING, "Restaurar fase de movimiento");
            check(c.snapshot(1).remainingNanos() <= frozen.remainingNanos(), "No reiniciar presupuesto");
            var arrival = event(c, e -> e.type() == ExecutionEvent.Type.ARRIVED && e.snapshot().currentFloor() == 2);
            check(arrival.referenceNanos() - frozen.referenceNanos() >= frozen.remainingNanos(), "Consume el resto completo");
            check(arrival.referenceNanos() - departure.referenceNanos() >= 800_000_000L, "Tramo activo completo");
            var completion = event(c, e -> e.type() == ExecutionEvent.Type.COMMAND_COMPLETED
                    && e.canonicalRequestId().filter(request.getId()::equals).isPresent());
            check(completion.snapshot().currentFloor() == 3, "Mismo comando llega al destino final");
            check(completion.snapshot().cabinRequests().isEmpty(), "Luz apagada al atender");
        }
    }

    public static void partialDwellPreservesRemainingTime() throws Exception {
        try (var rig = new Session(1, 3, 50, 800)) {
            var c = rig.controller;
            get(c.start()); get(c.submit(cabin(1, 1)));
            event(c, e -> e.type() == ExecutionEvent.Type.STOP_STARTED);
            Thread.sleep(150);
            get(c.pause());
            var frozen = c.snapshot(1);
            held(frozen, ElevatorPhase.DWELLING);
            check(frozen.remainingNanos() > 0 && frozen.remainingNanos() < 750_000_000L, "Parada parcial");
            drain(c); Thread.sleep(850);
            check(c.snapshot(1).equals(frozen) && c.awaitEvent(Duration.ZERO).isEmpty(), "Parada congelada");
            get(c.resume());
            await(() -> c.snapshot(1).phase() == ElevatorPhase.DWELLING, "Restaurar parada");
            check(c.snapshot(1).remainingNanos() <= frozen.remainingNanos(), "No reiniciar parada");
            var end = event(c, e -> e.type() == ExecutionEvent.Type.STOP_ENDED);
            check(end.referenceNanos() - frozen.referenceNanos() >= frozen.remainingNanos(), "Resto de parada completo");
        }
    }

    public static void movingCabinsFreezeOnOneTimeDomain() throws Exception {
        try (var rig = new Session(2, 6, 1200, 30)) {
            var c = rig.controller;
            get(c.start()); get(c.submit(cabin(1, 5))); get(c.submit(cabin(2, 4)));
            await(() -> c.snapshots().stream().allMatch(s -> s.phase() == ElevatorPhase.MOVING), "Dos cabinas en paralelo");
            Thread.sleep(30); get(c.pause());
            var frozen = c.snapshots();
            frozen.forEach(s -> held(s, ElevatorPhase.MOVING));
            check(frozen.get(0).referenceNanos() == frozen.get(1).referenceNanos(), "Misma marca congelada");
            Thread.sleep(80);
            check(c.snapshots().equals(frozen) && c.pauseStatus().acknowledged() == 3, "Ambas inmóviles y reconocidas");
        }
    }

    public static void repeatedControlsAreIdempotent() throws Exception {
        try (var rig = new Session(2, 4, 50, 30)) {
            var c = rig.controller;
            get(c.start()); get(c.pause());
            long generation = c.pauseStatus().generation();
            check(get(c.pause()).outcome() == SimulationController.Outcome.UNCHANGED, "Pausa repetida");
            check(c.pauseStatus().generation() == generation, "Misma generación");
            check(get(c.start()).outcome() == SimulationController.Outcome.REJECTED, "Start no sustituye Resume");
            check(get(c.resume()).outcome() == SimulationController.Outcome.APPLIED, "Primer Resume");
            check(get(c.resume()).outcome() == SimulationController.Outcome.UNCHANGED, "Resume repetido");
        }
    }

    public static void rapidCyclesKeepSameSessionAndActors() throws Exception {
        try (var rig = new Session(3, 5, 100, 30)) {
            var c = rig.controller;
            get(c.start());
            UUID session = c.sessionId();
            Set<Thread> actors = actorThreads();
            for (int i = 1; i <= 20; i++) {
                get(c.pause());
                check(c.pauseStatus().generation() == i && c.pauseStatus().acknowledged() == 4, "ACK vigentes por ciclo");
                get(c.resume());
            }
            check(c.sessionId().equals(session) && c.epoch() == 0, "Misma sesión y época");
            check(actorThreads().equals(actors), "Mismos objetos Thread, sin reemplazarlos");
        }
    }

    public static void hallLedgerAndQueuedCommandsSurvivePause() throws Exception {
        try (var rig = new Session(2, 6, 150, 30)) {
            var c = rig.controller;
            get(c.start());
            var first = cabin(1, 5);
            var second = cabin(2, 4);
            var hall = new HallRequest(UUID.randomUUID(), 0, 3, Instant.now(), Direction.UP);
            get(c.submit(first)); get(c.submit(second)); get(c.submit(hall));
            get(c.pause());
            var calls = c.calls(); var snapshots = c.snapshots();
            Thread.sleep(200);
            check(c.calls().equals(calls) && c.snapshots().equals(snapshots), "Ledger y agendas estables");
            get(c.resume());
            var pending = new HashSet<>(Set.of(first.getId(), second.getId(), hall.getId()));
            while (!pending.isEmpty()) {
                var done = event(c, e -> e.type() == ExecutionEvent.Type.COMMAND_COMPLETED);
                check(pending.remove(done.canonicalRequestId().orElseThrow()), "Sin duplicar ni perder comandos");
            }
            await(() -> c.calls().containsKey(hall.getId())
                    && c.calls().get(hall.getId()).status() == HallCallSnapshot.Status.SERVED, "Llamada atendida tras Resume");
        }
    }

    public static void manualRequestsAndImportsAreRejectedWhilePaused() throws Exception {
        try (var rig = new Session(1, 4, 50, 30)) {
            var c = rig.controller;
            get(c.start()); get(c.pause());
            check(get(c.submit(cabin(1, 3))).outcome() == SimulationController.Outcome.REJECTED, "Sin admisión manual pausada");
            var imported = c.importRequests(Path.of("t55-no-debe-leerse.txt")).get(8, TimeUnit.SECONDS);
            check(imported.outcome() == BatchImportResult.Outcome.REJECTED, "Importación requiere RESET");
            check(c.state() == SimulationState.PAUSED && c.snapshot(1).cabinRequests().isEmpty(), "Sin efectos secundarios");
        }
    }

    public static void pausedStopAndResetUsePriorityControl() throws Exception {
        try (var rig = new Session(1, 4, 50, 30)) {
            var c = rig.controller;
            get(c.start()); get(c.pause());
            check(get(c.stop()).outcome() == SimulationController.Outcome.APPLIED, "Stop sustituye pausa");
            check(c.state() == SimulationState.STOPPED && c.epoch() == 0, "Stop conserva época");
            get(c.start()); get(c.pause());
            check(get(c.reset()).outcome() == SimulationController.Outcome.APPLIED, "Reset sustituye pausa");
            check(c.state() == SimulationState.RESET && c.epoch() == 1, "Reset sin Resume manual");
        }
    }

    public static void edtCanRequestPauseAndResumeWithoutWaiting() throws Exception {
        try (var rig = new Session(4, 4, 50, 30)) {
            var c = rig.controller;
            get(c.start());
            var requested = new AtomicReference<CompletableFuture<SimulationController.Result>>();
            SwingUtilities.invokeAndWait(() -> requested.set(c.pause()));
            check(get(requested.get()).state() == SimulationState.PAUSED, "Petición desde EDT");
            SwingUtilities.invokeAndWait(() -> requested.set(c.resume()));
            check(get(requested.get()).state() == SimulationState.RUNNING, "Reanudación desde EDT");
        }
    }

    public static void cancelledPublicFutureDoesNotStrandPause() throws Exception {
        try (var rig = new Session(8, 4, 50, 30)) {
            var c = rig.controller;
            get(c.start()); c.pause().cancel(true);
            await(() -> c.state() == SimulationState.PAUSED, "Pausa privada no cancelada");
            check(c.pauseStatus().acknowledged() == 9, "Todos reconocidos");
            get(c.resume());
        }
    }

    public static void simultaneousPauseRequestsShareOneGeneration() throws Exception {
        try (var rig = new Session(4, 4, 50, 30)) {
            var c = rig.controller;
            get(c.start());
            var pool = java.util.concurrent.Executors.newFixedThreadPool(6);
            try {
                var futures = new java.util.ArrayList<java.util.concurrent.Future<SimulationController.Result>>();
                for (int i = 0; i < 24; i++) { futures.add(pool.submit(() -> get(c.pause()))); }
                for (var future : futures) {
                    var result = future.get(8, TimeUnit.SECONDS);
                    check(result.state() == SimulationState.PAUSED
                            && result.outcome() != SimulationController.Outcome.REJECTED, "Misma pausa concurrente");
                }
                check(c.pauseStatus().generation() == 1, "Una generación para 24 peticiones");
            } finally { pool.shutdownNow(); }
            get(c.resume());
        }
    }

    public static void closingPausedSessionReleasesEveryActor() throws Exception {
        try (var rig = new Session(3, 5, 300, 30)) {
            var c = rig.controller;
            get(c.start()); get(c.submit(cabin(1, 5))); get(c.pause());
            get(c.closeAsync());
            check(c.state() == SimulationState.CLOSED, "Cierre sin Resume manual");
            check(c.elevatorLifecycles().stream().allMatch(s -> s == ElevatorWorker.Lifecycle.TERMINATED), "Cabinas terminadas");
            check(c.managerLifecycle() == com.elevatormanager.dispatch.ManagerWorker.Lifecycle.TERMINATED, "Manejador terminado");
            check(c.failure().isEmpty(), "Cierre pausado sin fallo");
        }
    }

    public static void closeRacingPauseAlwaysCompletesBothFutures() throws Exception {
        try (var rig = new Session(12, 4, 50, 30)) {
            var c = rig.controller;
            get(c.start());
            var pause = c.pause(); var close = c.closeAsync();
            get(close);
            check(pause.get(8, TimeUnit.SECONDS) != null && c.state() == SimulationState.CLOSED, "Ambas operaciones resueltas");
            check(c.failure().isEmpty(), "La sustitución por cierre no es fallo de motor");
        }
    }

    public static void sessionsHaveIndependentPauseDomains() throws Exception {
        try (var first = new Session(1, 4, 60, 20); var second = new Session(1, 4, 60, 20)) {
            var a = first.controller; var b = second.controller;
            get(a.start()); get(b.start()); get(a.pause());
            get(b.submit(cabin(1, 3)));
            event(b, e -> e.type() == ExecutionEvent.Type.COMMAND_COMPLETED);
            check(a.state() == SimulationState.PAUSED && a.snapshot(1).currentFloor() == 1,
                    "La pausa de una sesión no detiene otra");
        }
    }

    private static void held(ElevatorSnapshot snapshot, ElevatorPhase phase) {
        check(snapshot.phase() == ElevatorPhase.HELD && snapshot.suspendedPhase().orElseThrow() == phase
                && snapshot.holdReason().orElseThrow() == ElevatorSnapshot.HoldReason.PAUSE, "Captura HELD/PAUSE con fase original");
    }
    private static Set<Thread> actorThreads() {
        var threads = new HashSet<Thread>();
        Thread.getAllStackTraces().keySet().stream().filter(t -> t.isAlive()
                && (t.getName().startsWith("elevator-worker-") || t.getName().startsWith("manager-worker-")))
                .forEach(threads::add);
        return Set.copyOf(threads);
    }
    private static CabinRequest cabin(int id, int floor) {
        return new CabinRequest(UUID.randomUUID(), 0, floor, Instant.now(), id);
    }
    private static SimulationController.Result get(CompletableFuture<SimulationController.Result> future) throws Exception {
        return future.get(8, TimeUnit.SECONDS);
    }
    private static ExecutionEvent event(SimulationController c, Predicate<ExecutionEvent> predicate) throws Exception {
        long began = System.nanoTime();
        while (true) {
            long left = LIMIT.toNanos() - (System.nanoTime() - began);
            check(left > 0, "Plazo de evento agotado");
            var event = c.awaitEvent(Duration.ofNanos(left));
            if (event.isPresent() && predicate.test(event.orElseThrow())) { return event.orElseThrow(); }
        }
    }
    private static void drain(SimulationController c) throws Exception {
        while (c.awaitEvent(Duration.ZERO).isPresent()) { }
    }
    private static void await(BooleanSupplier condition, String detail) throws Exception {
        long began = System.nanoTime();
        while (!condition.getAsBoolean()) {
            check(System.nanoTime() - began < LIMIT.toNanos(), detail);
            Thread.sleep(1);
        }
    }
    private static final class Session implements AutoCloseable {
        final SimulationController controller;
        Session(int cabins, int floors, long movement, long dwell) {
            controller = new SimulationController(new SimulationConfig(cabins, floors, movement, dwell), 0);
        }
        @Override public void close() throws Exception {
            controller.close();
            check(controller.awaitTermination(LIMIT), "Cierre real y acotado del fixture");
        }
    }
    private static void check(boolean condition, String detail) {
        if (!condition) { throw new AssertionError(detail); }
    }

    public static void main(String[] args) throws Exception {
        pauseAndResumeRequireValidState();
        singleCabinIdleBarrier();
        manyCabinsIdleBarrier();
        partialMovementPreservesRemainingTimeAndCommand();
        partialDwellPreservesRemainingTime();
        movingCabinsFreezeOnOneTimeDomain();
        repeatedControlsAreIdempotent();
        rapidCyclesKeepSameSessionAndActors();
        hallLedgerAndQueuedCommandsSurvivePause();
        manualRequestsAndImportsAreRejectedWhilePaused();
        pausedStopAndResetUsePriorityControl();
        edtCanRequestPauseAndResumeWithoutWaiting();
        cancelledPublicFutureDoesNotStrandPause();
        simultaneousPauseRequestsShareOneGeneration();
        closingPausedSessionReleasesEveryActor();
        closeRacingPauseAlwaysCompletesBothFutures();
        sessionsHaveIndependentPauseDomains();
        System.out.println("T55: 17 escenarios de sesión satisfactorios.");
    }
}
