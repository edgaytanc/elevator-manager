package com.elevatormanager.simulation;

import com.elevatormanager.logging.SessionLog;
import com.elevatormanager.model.*;
import com.elevatormanager.model.request.CabinRequest;
import com.elevatormanager.simulation.time.PauseCoordinator;
import java.io.OutputStream;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.*;

/** T56: prioridad real sobre actores pausados, sin invocar Resume para Stop/Reset/cierre. */
public final class PausePriorityScenarios {
    private static final Duration LIMIT = Duration.ofSeconds(8);
    private PausePriorityScenarios() { }

    public static void higherControlReleasesPartialBarrier() throws Exception {
        try (var gate = new PauseCoordinator(3)) {
            var future = gate.requestPause();
            check(gate.releaseForControl("STOP"), "Permite sustituir con cero ACK");
            check(future.isCompletedExceptionally() && !gate.isRequested(), "Barrera parcial resuelta");
            check(!gate.releaseForControl("STOP") && !gate.resume(), "No reanuda ni libera dos veces");
            check(gate.checkpoint(0, () -> { throw new AssertionError("No debe pausar"); }, () -> { }), "Checkpoint liberado");
            gate.requestPause();
            check(gate.status().generation() == 2 && gate.status().acknowledged() == 0, "Nueva generación limpia");
        }
    }

    public static void higherControlWakesAlreadyBlockedActor() throws Exception {
        try (var gate = new PauseCoordinator(3)) {
            var future = gate.requestPause();
            var failure = new AtomicReference<Throwable>();
            Thread actor = new Thread(() -> {
                try { gate.checkpoint(0, () -> { }, () -> { }); }
                catch (Throwable error) { failure.set(error); }
            }, "t56-priority-gate");
            actor.start();
            try {
                await(() -> gate.status().acknowledged() == 1, "ACK parcial");
                check(!future.isDone(), "Los ausentes no reconocen");
                gate.releaseForControl("RESET"); actor.join(3000);
                check(!actor.isAlive() && failure.get() == null, "Actor liberado sin interrumpirlo");
            } finally { gate.close(); actor.interrupt(); actor.join(3000); }
        }
    }

    public static void stopPausedMovementFinishesLegWithoutServing() throws Exception {
        try (var r = new Rig(1, 5, 450, 80)) {
            var c = r.c;
            get(c.start()); get(c.submit(cabin(c, 2)));
            event(c, e -> e.type() == ExecutionEvent.Type.DEPARTED);
            Thread.sleep(70); get(c.pause());
            var frozen = c.snapshot(1); drain(c);
            get(c.stop());
            var stopped = c.snapshot(1);
            check(c.state() == SimulationState.STOPPED && stopped.currentFloor() == 2, "Stop termina el tramo adyacente");
            check(stopped.cabinRequests().size() == 1, "No atiende al llegar por Stop");
            var events = drain(c);
            check(events.stream().noneMatch(e -> e.type() == ExecutionEvent.Type.COMMAND_COMPLETED
                    || e.type() == ExecutionEvent.Type.STOP_STARTED), "Sin servicio entre liberar pausa y adoptar Stop");
            var arrival = events.stream().filter(e -> e.type() == ExecutionEvent.Type.ARRIVED).findFirst().orElseThrow();
            check(arrival.referenceNanos() - frozen.referenceNanos() >= frozen.remainingNanos(), "Resto físico simulado");
            get(c.start()); event(c, e -> e.type() == ExecutionEvent.Type.COMMAND_COMPLETED);
            check(c.snapshot(1).cabinRequests().isEmpty(), "Start atiende el destino conservado");
        }
    }

    public static void stopPausedDwellPreservesBudgetForStart() throws Exception {
        try (var r = new Rig(1, 4, 40, 600)) {
            var c = r.c;
            get(c.start()); get(c.submit(cabin(c, 1)));
            event(c, e -> e.type() == ExecutionEvent.Type.STOP_STARTED);
            Thread.sleep(80); get(c.pause()); var frozen = c.snapshot(1);
            get(c.stop());
            var stopped = c.snapshot(1);
            check(stopped.phase() == ElevatorPhase.HELD && stopped.holdReason().orElseThrow() == ElevatorSnapshot.HoldReason.STOP,
                    "HELD/STOP sustituye HELD/PAUSE");
            check(stopped.remainingNanos() > 0 && stopped.remainingNanos() <= frozen.remainingNanos(), "Presupuesto conservado");
            Thread.sleep(80); check(c.snapshot(1).equals(stopped), "Stop no consume parada");
            get(c.start());
            var resumed = event(c, e -> e.type() == ExecutionEvent.Type.CONTROL_RESUMED);
            var end = event(c, e -> e.type() == ExecutionEvent.Type.STOP_ENDED);
            check(end.referenceNanos() - resumed.referenceNanos() >= stopped.remainingNanos(), "Start consume el restante completo");
        }
    }

    public static void resetPausedMovementReturnsPhysicallyAndCancels() throws Exception {
        try (var r = new Rig(1, 5, 300, 30)) {
            var c = r.c;
            get(c.start()); var request = cabin(c, 5); get(c.submit(request));
            event(c, e -> e.type() == ExecutionEvent.Type.DEPARTED);
            Thread.sleep(50); get(c.pause()); var frozen = c.snapshot(1);
            drain(c); get(c.reset());
            initial(c); check(c.epoch() == 1, "Una época nueva");
            var events = drain(c);
            var arrival = events.stream().filter(e -> e.type() == ExecutionEvent.Type.ARRIVED).findFirst().orElseThrow();
            check(arrival.snapshot().currentFloor() == frozen.nextFloor().orElseThrow(), "Completa el tramo congelado antes de retornar");
            check(arrival.referenceNanos() - frozen.referenceNanos() >= frozen.remainingNanos(), "Sin teleportar el tramo pendiente");
            check(events.stream().noneMatch(e -> e.type() == ExecutionEvent.Type.COMMAND_COMPLETED
                    && e.canonicalRequestId().filter(request.getId()::equals).isPresent()), "No completa trabajo cancelado");
        }
    }

    public static void resetPausedDwellDoesNotRequireResume() throws Exception {
        try (var r = new Rig(1, 4, 40, 700)) {
            var c = r.c;
            get(c.start()); get(c.submit(cabin(c, 1)));
            event(c, e -> e.type() == ExecutionEvent.Type.STOP_STARTED);
            get(c.pause()); get(c.reset()); initial(c);
            check(!c.pauseStatus().requested(), "Compuerta liberada por Reset");
        }
    }

    public static void pauseStopRaceResolvesEveryOperation() throws Exception {
        try (var r = new Rig(8, 4, 40, 20)) {
            var c = r.c; get(c.start());
            var pause = c.pause(); var stop = c.stop();
            get(stop); check(get(pause).outcome() != null, "Pausa reconocida o sustituida");
            check(c.state() == SimulationState.STOPPED && !c.pauseStatus().requested(), "Stop vence la carrera");
        }
    }

    public static void pauseResetRaceResolvesEveryOperation() throws Exception {
        try (var r = new Rig(8, 4, 40, 20)) {
            var c = r.c; get(c.start());
            var pause = c.pause(); var reset = c.reset();
            get(reset); get(pause); initial(c);
            Thread.sleep(20); initial(c); // Confirmaciones de pausa tardías no restauran PAUSED.
        }
    }

    public static void resetSupersedesStopOriginatingInPause() throws Exception {
        try (var r = new Rig(1, 5, 350, 30)) {
            var c = r.c; get(c.start()); get(c.submit(cabin(c, 5)));
            event(c, e -> e.type() == ExecutionEvent.Type.DEPARTED); get(c.pause());
            var stop = c.stop(); var reset = c.reset();
            get(reset); get(stop); initial(c);
            check(c.epoch() == 1 && c.failure().isEmpty(), "Reset vence Stop sin fallos ni dos épocas");
        }
    }

    public static void closeSupersedesResetOriginatingInPause() throws Exception {
        try (var r = new Rig(2, 5, 350, 30)) {
            var c = r.c; get(c.start()); get(c.submit(cabin(c, 5))); get(c.pause());
            var reset = c.reset(); var close = c.closeAsync();
            get(close); get(reset);
            check(c.state() == SimulationState.CLOSED && c.failure().isEmpty(), "Cierre prioritario, sin Resume");
            check(c.elevatorLifecycles().stream().allMatch(l -> l == ElevatorWorker.Lifecycle.TERMINATED), "Actores finalizados");
        }
    }

    public static void pauseIsRejectedWhileStopping() throws Exception {
        try (var r = new Rig(1, 5, 450, 30)) {
            var c = r.c; get(c.start()); get(c.submit(cabin(c, 5)));
            event(c, e -> e.type() == ExecutionEvent.Type.DEPARTED);
            var stop = c.stop(); var pause = get(c.pause());
            check(pause.outcome() == SimulationController.Outcome.REJECTED, "No congela un Stop en vuelo");
            get(stop); check(!c.pauseStatus().requested(), "Sin reloj retenido");
        }
    }

    public static void repeatedResetDuringPausedReturnUsesOneEpoch() throws Exception {
        try (var r = new Rig(1, 5, 300, 30)) {
            var c = r.c; get(c.start()); get(c.submit(cabin(c, 5)));
            event(c, e -> e.type() == ExecutionEvent.Type.DEPARTED); get(c.pause());
            var first = c.reset(); var second = c.reset();
            get(first); get(second); initial(c); check(c.epoch() == 1, "Reset idempotente durante retorno");
        }
    }

    public static void transitionsAndPriorityReasonsAreLogged() throws Exception {
        try (var r = new Rig(1, 4, 40, 30)) {
            var c = r.c;
            get(c.start()); get(c.pause()); get(c.pause()); get(c.stop());
            get(c.start()); get(c.pause()); get(c.reset());
            get(c.start()); get(c.pause()); get(c.resume()); get(c.pause());
            get(c.closeAsync());
            String log = Files.readString(r.directory.resolve("manejador.log"));
            for (String name : List.of("SIMULATION_PAUSING", "SIMULATION_PAUSED", "SIMULATION_RESUMED",
                    "PAUSE_SUPERSEDED_BY_STOP", "PAUSE_SUPERSEDED_BY_RESET", "PAUSE_SUPERSEDED_BY_CLOSE",
                    "SIMULATION_STOPPING", "SIMULATION_STOPPED", "SIMULATION_RESETTING", "SIMULATION_RESET", "SIMULATION_CLOSING")) {
                check(log.contains("evento=" + name + ";"), "Registro completo: " + name);
            }
            check(count(log, "SIMULATION_PAUSED") == 4, "Una transición por pausa efectiva, no por clic repetido");
            check(log.indexOf("evento=SIMULATION_PAUSING;") < log.indexOf("evento=SIMULATION_PAUSED;"), "Orden de reconocimiento");
        }
    }

    public static void supersededPausesNeverPublishLatePausedTransitions() throws Exception {
        try (var r = new Rig(8, 4, 40, 20)) {
            var c = r.c;
            int confirmed = 0;
            for (int i = 0; i < 12; i++) {
                get(c.start()); var pause = c.pause(); var stop = c.stop();
                get(stop);
                if (get(pause).outcome() == SimulationController.Outcome.APPLIED) { confirmed++; }
                check(c.state() == SimulationState.STOPPED, "No revive la pausa sustituida");
            }
            get(c.closeAsync());
            check(count(Files.readString(r.directory.resolve("manejador.log")), "SIMULATION_PAUSED") == confirmed,
                    "Solo pausas confirmadas, sin ACK tardíos publicados");
        }
    }

    private static long count(String text, String event) {
        return text.lines().filter(line -> line.contains("evento=" + event + ";")).count();
    }
    private static CabinRequest cabin(SimulationController c, int floor) {
        return new CabinRequest(UUID.randomUUID(), c.epoch(), floor, Instant.now(), 1);
    }
    private static SimulationController.Result get(CompletableFuture<SimulationController.Result> f) throws Exception {
        return f.get(8, TimeUnit.SECONDS);
    }
    private static void initial(SimulationController c) {
        check(c.state() == SimulationState.RESET && c.snapshots().stream().allMatch(s -> s.currentFloor() == 1
                && s.direction() == Direction.UP && s.phase() == ElevatorPhase.IDLE && s.cabinRequests().isEmpty()
                && s.hallRequests().isEmpty() && s.nextFloor().isEmpty()), "Retorno 1/UP completo y limpio");
    }
    private static ExecutionEvent event(SimulationController c, Predicate<ExecutionEvent> wanted) throws Exception {
        long started = System.nanoTime();
        while (true) {
            long left = LIMIT.toNanos() - (System.nanoTime() - started); check(left > 0, "Plazo de evento");
            var event = c.awaitEvent(Duration.ofNanos(left));
            if (event.isPresent() && wanted.test(event.orElseThrow())) { return event.orElseThrow(); }
        }
    }
    private static List<ExecutionEvent> drain(SimulationController c) throws Exception {
        var list = new ArrayList<ExecutionEvent>();
        Optional<ExecutionEvent> event;
        while ((event = c.awaitEvent(Duration.ZERO)).isPresent()) { list.add(event.orElseThrow()); }
        return list;
    }
    private static void await(BooleanSupplier condition, String detail) throws Exception {
        long started = System.nanoTime();
        while (!condition.getAsBoolean()) { check(System.nanoTime() - started < LIMIT.toNanos(), detail); Thread.sleep(1); }
    }
    private static final class Rig implements AutoCloseable {
        final Path directory = Files.createTempDirectory("t56-priority-");
        final SimulationController c;
        Rig(int n, int floors, int movement, int dwell) throws Exception {
            var log = SessionLog.open(directory, n, OutputStream.nullOutputStream());
            try { c = new SimulationController(new SimulationConfig(n, floors, movement, dwell), 0, log); }
            catch (RuntimeException | Error e) { log.close(); throw e; }
        }
        @Override public void close() throws Exception {
            c.close(); check(c.awaitTermination(LIMIT), "Cierre real del fixture");
            try (var paths = Files.walk(directory)) {
                for (var path : paths.sorted(Comparator.reverseOrder()).toList()) { Files.deleteIfExists(path); }
            }
        }
    }
    private static void check(boolean value, String detail) { if (!value) { throw new AssertionError(detail); } }

    public static void main(String[] args) throws Exception {
        higherControlReleasesPartialBarrier();
        higherControlWakesAlreadyBlockedActor();
        stopPausedMovementFinishesLegWithoutServing();
        stopPausedDwellPreservesBudgetForStart();
        resetPausedMovementReturnsPhysicallyAndCancels();
        resetPausedDwellDoesNotRequireResume();
        pauseStopRaceResolvesEveryOperation();
        pauseResetRaceResolvesEveryOperation();
        resetSupersedesStopOriginatingInPause();
        closeSupersedesResetOriginatingInPause();
        pauseIsRejectedWhileStopping();
        repeatedResetDuringPausedReturnUsesOneEpoch();
        transitionsAndPriorityReasonsAreLogged();
        supersededPausesNeverPublishLatePausedTransitions();
        System.out.println("T56: 14 escenarios de prioridades y trazas satisfactorios.");
    }
}
