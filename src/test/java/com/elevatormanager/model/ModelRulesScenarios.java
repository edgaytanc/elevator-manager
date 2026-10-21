package com.elevatormanager.model;

import com.elevatormanager.model.request.CabinRequest;
import com.elevatormanager.model.request.HallRequest;
import com.elevatormanager.model.request.Request;
import com.elevatormanager.protocol.Command;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Escenarios de aceptación del modelo: ejecutables por JUnit o mediante main. */
public final class ModelRulesScenarios {
    private static final Instant NOW = Instant.parse("2026-10-21T06:00:00Z");
    private static final SimulationConfig BASE = new SimulationConfig(2, 5, 100, 200);

    private ModelRulesScenarios() { }

    private static UUID id(long value) { return new UUID(0, value); }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void rejects(Class<? extends Throwable> expected, Runnable action) {
        try {
            action.run();
        } catch (Throwable failure) {
            if (expected.isInstance(failure)) return;
            throw new AssertionError("Se esperaba " + expected.getSimpleName(), failure);
        }
        throw new AssertionError("No se rechazó una operación inválida.");
    }

    public static void buildingBoundaryMatrix() {
        for (int cabins = 1; cabins <= 3; cabins++) {
            for (int floors = 1; floors <= 6; floors++) {
                SimulationConfig config = new SimulationConfig(cabins, floors, 1, 1);
                for (int floor = 1; floor <= floors + 1; floor++) {
                    for (Direction direction : Direction.values()) {
                        Request request = new HallRequest(id(1), 0, floor, NOW, direction);
                        boolean possible = direction == Direction.UP
                                ? floor < floors : floor > 1 && floor <= floors;
                        check(request.validateAgainst(config).isValid() == possible,
                                "Límite externo: P=" + floors + ", piso=" + floor + ", " + direction);
                    }
                    for (int elevator = 1; elevator <= cabins + 1; elevator++) {
                        Request request = new CabinRequest(id(2), 0, floor, NOW, elevator);
                        check(request.validateAgainst(config).isValid()
                                == (floor <= floors && elevator <= cabins), "Límite interno incorrecto.");
                    }
                }
            }
        }
    }

    public static void requestsCommandsAgendasAndSnapshots() {
        ElevatorState owner = new ElevatorState(2, BASE, 7);
        ElevatorState other = new ElevatorState(1, BASE, 7);
        HallRequest hall = new HallRequest(id(3), 7, 3, NOW, Direction.DOWN);
        CabinRequest cabin = new CabinRequest(id(4), 7, 5, NOW, 2);
        Command.Pickup pickup = new Command.Pickup(101, 2, hall);
        Command.Destination destination = new Command.Destination(102, 2, cabin);
        for (Command command : List.of(pickup, destination)) {
            check(command.validateAgainst(BASE).isValid(), "Comando contextual inválido.");
            check(command.epoch() == 7 && command.elevatorId() == 2, "Correlación incorrecta.");
        }
        // Adaptación de prueba: no es un receptor ni ejecuta el protocolo o movimiento.
        for (Request request : List.of(pickup.request(), destination.request())) {
            check(request.validateAgainst(BASE).isValid(), "Falló el despacho polimórfico.");
            check(!request.describe().isBlank(), "Descripción vacía.");
            check(owner.addPendingRequest(request), "No se incorporó trabajo nuevo.");
        }
        ElevatorSnapshot snapshot = owner.snapshot(10);
        check(snapshot.revision() == 2 && snapshot.cabinRequests().get(0) == cabin
                && snapshot.hallRequests().get(0) == hall, "Snapshot distinto de las agendas.");
        check(snapshot.currentFloor() == 1 && snapshot.phase() == ElevatorPhase.IDLE,
                "Incorporar trabajo no debe simular movimiento.");
        check(other.snapshot(10).revision() == 0, "Se modificó otra cabina.");
    }

    public static void populatedStateSurvivesRejectedInputs() {
        ElevatorState state = new ElevatorState(1, BASE, 7);
        CabinRequest original = new CabinRequest(id(5), 7, 4, NOW, 1);
        state.addPendingRequest(original);
        ElevatorSnapshot before = state.snapshot(20);
        List<Request> invalid = List.of(
                new CabinRequest(id(5), 7, 3, NOW, 1),
                new CabinRequest(id(6), 7, 4, NOW, 1),
                new CabinRequest(id(7), 7, 3, NOW, 2),
                new CabinRequest(id(8), 6, 3, NOW, 1),
                new HallRequest(id(9), 8, 3, NOW, Direction.UP),
                new HallRequest(id(10), 7, 5, NOW, Direction.UP));
        for (Request request : invalid) {
            rejects(IllegalArgumentException.class, () -> state.addPendingRequest(request));
            check(state.snapshot(20).equals(before), "Un rechazo cambió el estado publicado.");
            check(state.getCabinRequests().get(0) == original, "Se sustituyó la solicitud canónica.");
        }
        check(!state.addPendingRequest(new CabinRequest(id(5), 7, 4, NOW, 1)),
                "El reenvío exacto creó trabajo nuevo.");
        check(state.getRevision() == 1, "El reenvío aumentó la revisión.");
    }

    public static void commandConflictMatrix() {
        Command original = new Command.Pickup(20, 1,
                new HallRequest(id(11), 7, 3, NOW, Direction.UP));
        Command retry = new Command.Pickup(20, 1,
                new HallRequest(id(11), 7, 3, NOW, Direction.UP));
        check(original.sameContent(retry) && retry.sameContent(original), "Reenvío no reconocido.");
        List<Command> conflicts = List.of(
                new Command.Pickup(21, 1, new HallRequest(id(11), 7, 3, NOW, Direction.UP)),
                new Command.Pickup(20, 2, new HallRequest(id(11), 7, 3, NOW, Direction.UP)),
                new Command.Pickup(20, 1, new HallRequest(id(11), 8, 3, NOW, Direction.UP)),
                new Command.Pickup(20, 1, new HallRequest(id(12), 7, 3, NOW, Direction.UP)),
                new Command.Pickup(20, 1, new HallRequest(id(11), 7, 4, NOW, Direction.UP)),
                new Command.Pickup(20, 1, new HallRequest(id(11), 7, 3, NOW, Direction.DOWN)),
                new Command.Reset(20, 7, 1));
        for (Command conflict : conflicts) {
            check(!original.sameContent(conflict) && !conflict.sameContent(original),
                    "No se detectó un cambio de cabecera o contenido.");
        }
    }

    public static void snapshotsCrossThreadsWithoutSharingMutableState() throws InterruptedException {
        AtomicReference<ElevatorSnapshot> published = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch initialReady = new CountDownLatch(1);
        CountDownLatch readerReady = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        Thread writer = new Thread(() -> {
            try {
                ElevatorState owner = new ElevatorState(1, BASE, 0);
                published.set(owner.snapshot(1));
                initialReady.countDown();
                check(readerReady.await(5, TimeUnit.SECONDS), "El lector no confirmó recepción.");
                owner.addPendingRequest(new CabinRequest(id(13), 0, 4, NOW, 1));
                owner.addPendingRequest(new HallRequest(id(14), 0, 3, NOW, Direction.DOWN));
                published.set(owner.snapshot(2));
            } catch (Throwable error) {
                failure.set(error);
            } finally {
                initialReady.countDown();
                finished.countDown();
            }
        }, "t21-model-writer");
        writer.start();
        try {
            check(initialReady.await(5, TimeUnit.SECONDS), "No se publicó el estado inicial.");
            if (failure.get() != null) throw new AssertionError("Falló el escritor", failure.get());
            ElevatorSnapshot previous = published.get();
            readerReady.countDown();
            check(finished.await(5, TimeUnit.SECONDS), "El escritor no finalizó.");
            if (failure.get() != null) throw new AssertionError("Falló el escritor", failure.get());
            ElevatorSnapshot current = published.get();
            check(previous.revision() == 0 && previous.cabinRequests().isEmpty()
                    && previous.hallRequests().isEmpty(), "Se modificó un snapshot anterior.");
            check(current.revision() == 2 && current.cabinRequests().size() == 1
                    && current.hallRequests().size() == 1, "Lectura incompleta del snapshot nuevo.");
            rejects(UnsupportedOperationException.class, () -> current.cabinRequests().clear());
            rejects(UnsupportedOperationException.class, () -> current.hallRequests().clear());
        } finally {
            readerReady.countDown();
            writer.join(5000);
            check(!writer.isAlive(), "Quedó un hilo de prueba sin terminar.");
        }
    }

    public static void numericBoundariesRemainRepresentable() {
        long max = SimulationConfig.MAX_TIME_MILLIS;
        SimulationConfig config = new SimulationConfig(Integer.MAX_VALUE, Integer.MAX_VALUE, max, max);
        ElevatorState state = new ElevatorState(Integer.MAX_VALUE, config, Long.MAX_VALUE);
        CabinRequest cabin = new CabinRequest(id(15), Long.MAX_VALUE, Integer.MAX_VALUE,
                NOW, Integer.MAX_VALUE);
        check(state.addPendingRequest(cabin), "No se aceptó el límite representable.");
        check(state.snapshot(Long.MIN_VALUE).cabinRequests().get(0) == cabin, "Se truncó un límite.");
        long nanos = Math.multiplyExact(max, 1_000_000L);
        ElevatorSnapshot moving = new ElevatorSnapshot(Integer.MAX_VALUE, config, Long.MAX_VALUE,
                Long.MAX_VALUE, Integer.MAX_VALUE, OptionalInt.of(Integer.MAX_VALUE - 1),
                Direction.DOWN, ElevatorPhase.MOVING, Optional.empty(), Optional.empty(),
                ElevatorSnapshot.Mode.NORMAL, nanos, nanos, Long.MIN_VALUE,
                OptionalInt.of(1), List.of(), List.of());
        check(moving.progress() == 0.0 && moving.remainingNanos() == nanos,
                "La duración máxima perdió precisión entera.");
        rejects(IllegalArgumentException.class, () -> new SimulationConfig(1, 1, max + 1, 1));
    }

    private static ElevatorSnapshot snapshot(ElevatorPhase phase, ElevatorSnapshot.Mode mode,
            List<CabinRequest> cabin, List<HallRequest> hall) {
        return new ElevatorSnapshot(1, BASE, 0, 0, 3, OptionalInt.empty(), Direction.DOWN,
                phase, Optional.empty(), Optional.empty(), mode, 0, 0, 0,
                OptionalInt.empty(), cabin, hall);
    }

    public static void snapshotPendingRulesAndStationaryDirection() {
        CabinRequest cabin = new CabinRequest(id(16), 0, 3, NOW, 1);
        CabinRequest duplicate = new CabinRequest(id(17), 0, 3, NOW, 1);
        HallRequest up = new HallRequest(id(18), 0, 3, NOW, Direction.UP);
        HallRequest down = new HallRequest(id(19), 0, 3, NOW, Direction.DOWN);
        ElevatorSnapshot idle = snapshot(ElevatorPhase.IDLE, ElevatorSnapshot.Mode.NORMAL,
                List.of(cabin), List.of(up, down));
        check(idle.direction() == Direction.DOWN && idle.hallRequests().size() == 2,
                "Reposo alteró dirección o fusionó sentidos externos.");
        rejects(IllegalArgumentException.class, () -> snapshot(ElevatorPhase.IDLE,
                ElevatorSnapshot.Mode.NORMAL, List.of(cabin, duplicate), List.of()));
        rejects(IllegalArgumentException.class, () -> snapshot(ElevatorPhase.IDLE,
                ElevatorSnapshot.Mode.NORMAL, List.of(),
                List.of(up, new HallRequest(id(20), 0, 3, NOW, Direction.UP))));
        rejects(IllegalArgumentException.class, () -> snapshot(ElevatorPhase.IDLE,
                ElevatorSnapshot.Mode.NORMAL, List.of(cabin),
                List.of(new HallRequest(id(16), 0, 3, NOW, Direction.UP))));
        rejects(IllegalArgumentException.class, () -> snapshot(ElevatorPhase.IDLE,
                ElevatorSnapshot.Mode.RESET_RETURN, List.of(cabin), List.of()));
        rejects(IllegalArgumentException.class, () -> snapshot(ElevatorPhase.TERMINATED,
                ElevatorSnapshot.Mode.NORMAL, List.of(), List.of(up)));
    }

    public static void main(String[] args) throws InterruptedException {
        buildingBoundaryMatrix();
        requestsCommandsAgendasAndSnapshots();
        populatedStateSurvivesRejectedInputs();
        commandConflictMatrix();
        snapshotsCrossThreadsWithoutSharingMutableState();
        numericBoundariesRemainRepresentable();
        snapshotPendingRulesAndStationaryDirection();
        System.out.println("T21: 7 escenarios de aceptación correctos; sin Swing ni esperas de simulación.");
    }
}
