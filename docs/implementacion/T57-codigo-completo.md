# T57 — Código completo

Se añaden únicamente estas dos fuentes de pruebas. El código de producción permanece idéntico al proyecto T56 recibido e incorporado por David. El ZIP contiene todo el proyecto.

## src/test/java/com/elevatormanager/simulation/PausePhaseScenarios.java

```java
package com.elevatormanager.simulation;

import com.elevatormanager.dispatch.HallCallSnapshot;
import com.elevatormanager.model.*;
import com.elevatormanager.model.request.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** T57: aceptación temporal sobre actores reales, sin modificar relojes ni sustituir el motor. */
public final class PausePhaseScenarios {
    private static final Duration LIMIT = Duration.ofSeconds(10);
    private static final long TOLERANCE = TimeUnit.MILLISECONDS.toNanos(250);
    private PausePhaseScenarios() { }

    public static void upwardMovementEarlyPause() throws Exception { movement("up-early", false, 150, 1100); }
    public static void upwardMovementMiddlePause() throws Exception { movement("up-middle", false, 600, 120); }
    public static void upwardMovementLatePause() throws Exception { movement("up-late", false, 800, 120); }
    public static void downwardMovementMiddlePause() throws Exception { movement("down-middle", true, 600, 120); }
    public static void downwardMovementLatePause() throws Exception { movement("down-late", true, 800, 120); }

    private static void movement(String name, boolean down, long consumedMillis, long holdMillis) throws Exception {
        try (var rig = new Rig(1, 3, 1000, 20)) {
            var c = rig.c; get(c.start());
            if (down) {
                get(c.submit(cabin(c, 1, 2)));
                event(c, e -> e.type() == ExecutionEvent.Type.STOP_ENDED);
            }
            var request = cabin(c, 1, down ? 1 : 2);
            get(c.submit(request));
            var started = event(c, e -> e.type() == ExecutionEvent.Type.DEPARTED);
            Thread.sleep(consumedMillis);
            var frozen = pause(c, holdMillis).get(0);
            held(frozen, ElevatorPhase.MOVING);
            check(frozen.direction() == (down ? Direction.DOWN : Direction.UP), "Sentido del tramo");
            check(frozen.currentFloor() == (down ? 2 : 1) && frozen.nextFloor().orElseThrow() == (down ? 1 : 2), "Posición entre pisos");
            check(frozen.cabinRequests().stream().anyMatch(q -> q.getId().equals(request.getId())), "Identidad y pendiente conservados");
            var arrival = resumeAndMeasure(name, c, frozen, ExecutionEvent.Type.ARRIVED);
            check(arrival.referenceNanos() - started.referenceNanos() >= 1_000_000_000L, "Duración original completa en tiempo activo");
            event(c, e -> e.type() == ExecutionEvent.Type.COMMAND_COMPLETED
                    && e.canonicalRequestId().filter(request.getId()::equals).isPresent());
            check(c.snapshot(1).cabinRequests().isEmpty(), "Destino atendido una vez y luz apagada");
        }
    }

    public static void dwellEarlyPause() throws Exception { dwell("dwell-early", false, 150, 1100); }
    public static void dwellMiddlePause() throws Exception { dwell("dwell-middle", false, 600, 120); }
    public static void dwellLatePause() throws Exception { dwell("dwell-late", false, 800, 120); }
    public static void downwardDwellMiddlePause() throws Exception { dwell("dwell-down", true, 600, 120); }

    private static void dwell(String name, boolean down, long consumedMillis, long holdMillis) throws Exception {
        try (var rig = new Rig(1, 3, 20, 1000)) {
            var c = rig.c; get(c.start());
            if (down) {
                get(c.submit(cabin(c, 1, 2)));
                event(c, e -> e.type() == ExecutionEvent.Type.STOP_ENDED);
            }
            get(c.submit(cabin(c, 1, 1)));
            var started = event(c, e -> e.type() == ExecutionEvent.Type.STOP_STARTED);
            Thread.sleep(consumedMillis);
            var frozen = pause(c, holdMillis).get(0);
            held(frozen, ElevatorPhase.DWELLING);
            check(frozen.currentFloor() == 1 && frozen.nextFloor().isEmpty(), "Parada alineada");
            check(frozen.direction() == (down ? Direction.DOWN : Direction.UP), "Sentido de parada conservado");
            var ended = resumeAndMeasure(name, c, frozen, ExecutionEvent.Type.STOP_ENDED);
            check(ended.referenceNanos() - started.referenceNanos() >= 1_000_000_000L, "Parada activa completa sin contar pausa");
        }
    }

    public static void repeatedPausesWithinOneMovementKeepBudget() throws Exception { repeatedPhase(false); }
    public static void repeatedPausesWithinOneDwellKeepBudget() throws Exception { repeatedPhase(true); }

    private static void repeatedPhase(boolean dwell) throws Exception {
        try (var rig = new Rig(1, 3, dwell ? 20 : 1200, dwell ? 1200 : 20)) {
            var c = rig.c; get(c.start());
            long wallStarted = System.nanoTime();
            get(c.submit(cabin(c, 1, dwell ? 1 : 2)));
            var started = event(c, e -> e.type() == (dwell ? ExecutionEvent.Type.STOP_STARTED : ExecutionEvent.Type.DEPARTED));
            ElevatorSnapshot previous = null;
            long pausedWall = 0;
            for (int i = 1; i <= 4; i++) {
                Thread.sleep(170);
                long began = System.nanoTime();
                var frozen = pause(c, 80).get(0);
                pausedWall += System.nanoTime() - began;
                held(frozen, dwell ? ElevatorPhase.DWELLING : ElevatorPhase.MOVING);
                if (previous != null) { check(frozen.remainingNanos() < previous.remainingNanos(), "No recupera presupuesto entre ciclos"); }
                check(c.pauseStatus().generation() == i, "Generación vigente");
                previous = frozen;
                if (i < 4) { get(c.resume()); }
            }
            var last = Objects.requireNonNull(previous);
            var end = resumeAndMeasure(dwell ? "dwell-four-cycles" : "move-four-cycles", c, last,
                    dwell ? ExecutionEvent.Type.STOP_ENDED : ExecutionEvent.Type.ARRIVED);
            long activeTotal = end.referenceNanos() - started.referenceNanos();
            check(activeTotal >= 1_200_000_000L && activeTotal <= 1_200_000_000L + TOLERANCE,
                    "Cuatro ciclos no reinician el presupuesto original");
            check(System.nanoTime() - wallStarted >= 1_200_000_000L + 4 * 80_000_000L,
                    "El tiempo de pared incluye las pausas");
            check(pausedWall >= 4 * 80_000_000L, "Cuatro intervalos pausados reales");
        }
    }

    public static void idlePauseBeforeFirstRequestKeepsEmptyState() throws Exception {
        try (var rig = new Rig(1, 4, 25, 20)) {
            var c = rig.c; get(c.start());
            for (int i = 0; i < 3; i++) {
                var snapshot = pause(c, 40).get(0);
                check(snapshot.phase() == ElevatorPhase.IDLE && snapshot.currentFloor() == 1
                        && snapshot.cabinRequests().isEmpty() && snapshot.hallRequests().isEmpty(), "Sin trabajo inventado");
                get(c.resume());
            }
            var request = cabin(c, 1, 3); get(c.submit(request));
            event(c, e -> e.type() == ExecutionEvent.Type.COMMAND_COMPLETED
                    && e.canonicalRequestId().filter(request.getId()::equals).isPresent());
        }
    }

    public static void idlePauseAfterWorkPreservesFloorAndDirection() throws Exception {
        try (var rig = new Rig(1, 4, 25, 20)) {
            var c = rig.c; get(c.start());
            get(c.submit(cabin(c, 1, 3))); event(c, e -> e.type() == ExecutionEvent.Type.STOP_ENDED);
            get(c.submit(cabin(c, 1, 1))); event(c, e -> e.type() == ExecutionEvent.Type.STOP_ENDED);
            for (int i = 0; i < 3; i++) {
                var snapshot = pause(c, 40).get(0);
                check(snapshot.phase() == ElevatorPhase.IDLE && snapshot.currentFloor() == 1
                        && snapshot.direction() == Direction.DOWN && snapshot.cabinRequests().isEmpty(), "Reposo no cambia sentido ni piso");
                get(c.resume());
            }
        }
    }

    public static void mixedMovementDwellAndIdleShareOnePause() throws Exception {
        try (var rig = new Rig(3, 4, 1000, 2000)) {
            var c = rig.c; get(c.start());
            get(c.submit(cabin(c, 1, 3))); get(c.submit(cabin(c, 2, 1)));
            await(() -> c.snapshot(1).phase() == ElevatorPhase.MOVING && c.snapshot(2).phase() == ElevatorPhase.DWELLING,
                    "Cabinas en fases diferentes");
            Thread.sleep(100);
            var frozen = pause(c, 150);
            held(frozen.get(0), ElevatorPhase.MOVING); held(frozen.get(1), ElevatorPhase.DWELLING);
            check(frozen.get(2).phase() == ElevatorPhase.IDLE, "Tercera cabina libre");
            check(frozen.stream().map(ElevatorSnapshot::referenceNanos).distinct().count() == 1, "Congelación temporal común");
            long wallStarted = System.nanoTime(); get(c.resume());
            var movement = event(c, e -> e.elevatorId() == 1 && e.type() == ExecutionEvent.Type.ARRIVED);
            measure("mixed-moving", frozen.get(0), movement, System.nanoTime() - wallStarted);
            var dwell = event(c, e -> e.elevatorId() == 2 && e.type() == ExecutionEvent.Type.STOP_ENDED);
            measure("mixed-dwelling", frozen.get(1), dwell, System.nanoTime() - wallStarted);
            check(c.snapshot(3).currentFloor() == 1 && c.snapshot(3).phase() == ElevatorPhase.IDLE, "Cabina libre no se mueve");
        }
    }

    public static void oppositeHallCallsAndDestinationSurviveRepeatedPause() throws Exception {
        try (var rig = new Rig(1, 4, 700, 30)) {
            var c = rig.c; get(c.start());
            var inside = cabin(c, 1, 3);
            var up = new HallRequest(UUID.randomUUID(), c.epoch(), 2, Instant.now(), Direction.UP);
            var down = new HallRequest(UUID.randomUUID(), c.epoch(), 2, Instant.now(), Direction.DOWN);
            get(c.submit(inside)); get(c.submit(up)); get(c.submit(down));
            await(() -> c.snapshot(1).hallRequests().size() == 2, "Ambos sentidos admitidos");
            for (int i = 0; i < 2; i++) {
                Thread.sleep(80);
                var frozen = pause(c, 80).get(0);
                held(frozen, ElevatorPhase.MOVING);
                check(frozen.cabinRequests().size() == 1 && frozen.hallRequests().size() == 2, "Tres pendientes intactos");
                get(c.resume());
            }
            var pending = new HashSet<>(Set.of(inside.getId(), up.getId(), down.getId()));
            while (!pending.isEmpty()) {
                var completed = event(c, e -> e.type() == ExecutionEvent.Type.COMMAND_COMPLETED);
                check(pending.remove(completed.canonicalRequestId().orElseThrow()), "Cada identidad se completa una vez");
            }
            await(() -> c.calls().values().stream().allMatch(call -> call.status() == HallCallSnapshot.Status.SERVED),
                    "Ambos sentidos realmente atendidos");
            event(c, e -> e.type() == ExecutionEvent.Type.STOP_ENDED);
            check(drain(c).stream().noneMatch(e -> e.type() == ExecutionEvent.Type.COMMAND_COMPLETED), "Sin finalizaciones duplicadas");
        }
    }

    public static void minimumDurationsAllowPauseAtPhaseBoundaries() throws Exception {
        try (var rig = new Rig(1, 2, 1, 1)) {
            var c = rig.c; get(c.start());
            for (int i = 0; i < 12; i++) {
                var request = cabin(c, 1, i % 2 == 0 ? 2 : 1); get(c.submit(request));
                get(c.pause());
                var frozen = c.snapshot(1);
                Thread.sleep(4);
                check(c.snapshot(1).equals(frozen), "Congelación incluso en frontera de 1 ms");
                check(frozen.remainingNanos() >= 0 && frozen.remainingNanos() <= frozen.phaseDurationNanos(), "Restante dentro de rango");
                get(c.resume());
                event(c, e -> e.type() == ExecutionEvent.Type.COMMAND_COMPLETED
                        && e.canonicalRequestId().filter(request.getId()::equals).isPresent());
                await(() -> c.snapshot(1).phase() == ElevatorPhase.IDLE, "Fin de fase breve");
            }
        }
    }

    public static void singleFloorDwellCanPauseWithoutCreatingMovement() throws Exception {
        try (var rig = new Rig(1, 1, 1, 1000)) {
            var c = rig.c; get(c.start()); get(c.submit(cabin(c, 1, 1)));
            event(c, e -> e.type() == ExecutionEvent.Type.STOP_STARTED);
            Thread.sleep(600); var frozen = pause(c, 120).get(0);
            held(frozen, ElevatorPhase.DWELLING);
            resumeAndMeasure("single-floor-dwell", c, frozen, ExecutionEvent.Type.STOP_ENDED);
            check(c.snapshot(1).currentFloor() == 1 && c.snapshot(1).nextFloor().isEmpty(), "No inventa traslado en P=1");
        }
    }

    public static void manyIdleCabinsBlockAndKeepTheirThreadIdentities() throws Exception {
        try (var rig = new Rig(16, 4, 20, 20)) {
            var c = rig.c; get(c.start());
            Set<Thread> workers = actorThreads();
            check(workers.size() == 17, "Dieciséis cabinas y un manejador");
            for (int i = 0; i < 5; i++) {
                pause(c, 20);
                await(() -> workers.stream().allMatch(t -> t.getState() == Thread.State.WAITING), "Actores bloqueados, sin espera activa");
                get(c.resume());
            }
            check(actorThreads().equals(workers), "No recrea los hilos");
        }
    }

    private static List<ElevatorSnapshot> pause(SimulationController c, long holdMillis) throws Exception {
        check(get(c.pause()).state() == SimulationState.PAUSED, "Barrera confirmada");
        check(c.pauseStatus().acknowledged() == c.config().elevatorCount() + 1, "Todos los ACK vigentes");
        var frozen = c.snapshots(); var calls = c.calls();
        drain(c); // Solo eventos anteriores al reconocimiento global.
        Thread.sleep(holdMillis);
        check(c.snapshots().equals(frozen) && c.calls().equals(calls), "Piso, posición, sentido, pendientes y restante congelados");
        check(c.awaitEvent(Duration.ZERO).isEmpty(), "Sin transición física después de PAUSED");
        return frozen;
    }

    private static ExecutionEvent resumeAndMeasure(String name, SimulationController c,
            ElevatorSnapshot frozen, ExecutionEvent.Type endType) throws Exception {
        long wallStarted = System.nanoTime(); get(c.resume());
        var phase = frozen.suspendedPhase().orElseThrow();
        await(() -> c.snapshot(frozen.id()).phase() == phase, "Restaurar fase sin reiniciarla");
        var restored = c.snapshot(frozen.id());
        check(restored.remainingNanos() <= frozen.remainingNanos(), "Resume no aumenta el resto");
        check(restored.currentFloor() == frozen.currentFloor() && restored.nextFloor().equals(frozen.nextFloor())
                && restored.committedFloor().equals(frozen.committedFloor()), "Posición y frontera comprometida conservadas");
        var end = event(c, e -> e.elevatorId() == frozen.id() && e.type() == endType);
        measure(name, frozen, end, System.nanoTime() - wallStarted);
        return end;
    }

    private static void measure(String name, ElevatorSnapshot frozen, ExecutionEvent end, long wallNanos) {
        long active = end.referenceNanos() - frozen.referenceNanos();
        long remaining = frozen.remainingNanos();
        check(active >= remaining, "No termina anticipadamente: " + name);
        check(active - remaining <= TOLERANCE, "Exceso activo fuera de tolerancia: " + name);
        check(wallNanos <= remaining + TOLERANCE, "No espera de nuevo la duración completa: " + name);
        System.out.println("T57_TIMING|" + name + "|" + remaining + "|" + active + "|" + wallNanos + "|" + TOLERANCE);
    }
    private static void held(ElevatorSnapshot snapshot, ElevatorPhase phase) {
        check(snapshot.phase() == ElevatorPhase.HELD && snapshot.suspendedPhase().orElseThrow() == phase
                && snapshot.holdReason().orElseThrow() == ElevatorSnapshot.HoldReason.PAUSE, "Fase suspendida correcta");
        check(snapshot.remainingNanos() > 0 && snapshot.remainingNanos() < snapshot.phaseDurationNanos(), "Fase realmente parcial");
    }
    private static Set<Thread> actorThreads() {
        var actors = new HashSet<Thread>();
        Thread.getAllStackTraces().keySet().stream().filter(t -> t.isAlive()
                && (t.getName().startsWith("elevator-worker-") || t.getName().startsWith("manager-worker-"))).forEach(actors::add);
        return Set.copyOf(actors);
    }
    private static CabinRequest cabin(SimulationController c, int id, int floor) {
        return new CabinRequest(UUID.randomUUID(), c.epoch(), floor, Instant.now(), id);
    }
    private static SimulationController.Result get(CompletableFuture<SimulationController.Result> future) throws Exception {
        return future.get(10, TimeUnit.SECONDS);
    }
    private static ExecutionEvent event(SimulationController c, Predicate<ExecutionEvent> predicate) throws Exception {
        long began = System.nanoTime();
        while (true) {
            long left = LIMIT.toNanos() - (System.nanoTime() - began); check(left > 0, "Plazo de evento agotado");
            var event = c.awaitEvent(Duration.ofNanos(left));
            if (event.isPresent() && predicate.test(event.orElseThrow())) { return event.orElseThrow(); }
        }
    }
    private static List<ExecutionEvent> drain(SimulationController c) throws Exception {
        var events = new ArrayList<ExecutionEvent>(); Optional<ExecutionEvent> next;
        while ((next = c.awaitEvent(Duration.ZERO)).isPresent()) { events.add(next.orElseThrow()); }
        return events;
    }
    private static void await(BooleanSupplier condition, String detail) throws Exception {
        long began = System.nanoTime();
        while (!condition.getAsBoolean()) { check(System.nanoTime() - began < LIMIT.toNanos(), detail); Thread.sleep(1); }
    }
    private static final class Rig implements AutoCloseable {
        final SimulationController c;
        Rig(int elevators, int floors, long movement, long dwell) {
            c = new SimulationController(new SimulationConfig(elevators, floors, movement, dwell), 0);
        }
        @Override public void close() throws Exception {
            c.close(); check(c.awaitTermination(LIMIT), "Sin actores ni coordinadores abandonados");
            check(c.failure().isEmpty(), "Sin fallo de motor al terminar");
        }
    }
    private static void check(boolean value, String detail) { if (!value) { throw new AssertionError(detail); } }

    public static void main(String[] args) throws Exception {
        upwardMovementEarlyPause(); upwardMovementMiddlePause(); upwardMovementLatePause();
        downwardMovementMiddlePause(); downwardMovementLatePause();
        dwellEarlyPause(); dwellMiddlePause(); dwellLatePause(); downwardDwellMiddlePause();
        repeatedPausesWithinOneMovementKeepBudget(); repeatedPausesWithinOneDwellKeepBudget();
        idlePauseBeforeFirstRequestKeepsEmptyState(); idlePauseAfterWorkPreservesFloorAndDirection();
        mixedMovementDwellAndIdleShareOnePause(); oppositeHallCallsAndDestinationSurviveRepeatedPause();
        minimumDurationsAllowPauseAtPhaseBoundaries(); singleFloorDwellCanPauseWithoutCreatingMovement();
        manyIdleCabinsBlockAndKeepTheirThreadIdentities();
        System.out.println("T57: 18 escenarios de fases y tiempos satisfactorios.");
    }
}
```

## src/test/java/com/elevatormanager/simulation/PausePhaseTest.java

```java
package com.elevatormanager.simulation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** T57: matriz temporal sobre actores reales. */
@Timeout(30)
class PausePhaseTest {
    @Test void upwardMovementEarlyPause() throws Exception {
        PausePhaseScenarios.upwardMovementEarlyPause();
    }
    @Test void upwardMovementMiddlePause() throws Exception {
        PausePhaseScenarios.upwardMovementMiddlePause();
    }
    @Test void upwardMovementLatePause() throws Exception {
        PausePhaseScenarios.upwardMovementLatePause();
    }
    @Test void downwardMovementMiddlePause() throws Exception {
        PausePhaseScenarios.downwardMovementMiddlePause();
    }
    @Test void downwardMovementLatePause() throws Exception {
        PausePhaseScenarios.downwardMovementLatePause();
    }
    @Test void dwellEarlyPause() throws Exception {
        PausePhaseScenarios.dwellEarlyPause();
    }
    @Test void dwellMiddlePause() throws Exception {
        PausePhaseScenarios.dwellMiddlePause();
    }
    @Test void dwellLatePause() throws Exception {
        PausePhaseScenarios.dwellLatePause();
    }
    @Test void downwardDwellMiddlePause() throws Exception {
        PausePhaseScenarios.downwardDwellMiddlePause();
    }
    @Test void repeatedPausesWithinOneMovementKeepBudget() throws Exception {
        PausePhaseScenarios.repeatedPausesWithinOneMovementKeepBudget();
    }
    @Test void repeatedPausesWithinOneDwellKeepBudget() throws Exception {
        PausePhaseScenarios.repeatedPausesWithinOneDwellKeepBudget();
    }
    @Test void idlePauseBeforeFirstRequestKeepsEmptyState() throws Exception {
        PausePhaseScenarios.idlePauseBeforeFirstRequestKeepsEmptyState();
    }
    @Test void idlePauseAfterWorkPreservesFloorAndDirection() throws Exception {
        PausePhaseScenarios.idlePauseAfterWorkPreservesFloorAndDirection();
    }
    @Test void mixedMovementDwellAndIdleShareOnePause() throws Exception {
        PausePhaseScenarios.mixedMovementDwellAndIdleShareOnePause();
    }
    @Test void oppositeHallCallsAndDestinationSurviveRepeatedPause() throws Exception {
        PausePhaseScenarios.oppositeHallCallsAndDestinationSurviveRepeatedPause();
    }
    @Test void minimumDurationsAllowPauseAtPhaseBoundaries() throws Exception {
        PausePhaseScenarios.minimumDurationsAllowPauseAtPhaseBoundaries();
    }
    @Test void singleFloorDwellCanPauseWithoutCreatingMovement() throws Exception {
        PausePhaseScenarios.singleFloorDwellCanPauseWithoutCreatingMovement();
    }
    @Test void manyIdleCabinsBlockAndKeepTheirThreadIdentities() throws Exception {
        PausePhaseScenarios.manyIdleCabinsBlockAndKeepTheirThreadIdentities();
    }
}
```
