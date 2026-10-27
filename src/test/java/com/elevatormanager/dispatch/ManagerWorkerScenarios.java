package com.elevatormanager.dispatch;

import com.elevatormanager.model.Direction;
import com.elevatormanager.model.SimulationConfig;
import com.elevatormanager.model.request.CabinRequest;
import com.elevatormanager.model.request.HallRequest;
import com.elevatormanager.protocol.Command;
import com.elevatormanager.simulation.ElevatorWorker;
import com.elevatormanager.simulation.ExecutionEvent;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;

/** Escenarios ejecutables con hilos reales, sin dependencias de JUnit ni sleeps. */
public final class ManagerWorkerScenarios {
    private static final Duration LIMIT = Duration.ofSeconds(5);
    private ManagerWorkerScenarios() { }

    public static void singleCabinDispatchesAndServes() throws Exception {
        try (var rig = new Rig(1)) {
            var request = hall(2);
            check(rig.manager.submit(request), "Entrada encolada.");
            var result = result(rig.manager);
            check(result.outcome() == ManagerWorker.Outcome.DISPATCHED, "Despachada con N=1.");
            check(result.processedBy().equals(rig.manager.threadName()), "Procesada por el manejador.");
            var command = result.command().orElseThrow();
            check(command.request() == request && command.elevatorId() == 1, "Identidad y destino.");
            check(rig.elevators.get(0).awaitResult(LIMIT).orElseThrow().outcome()
                    == ElevatorWorker.Outcome.ACCEPTED, "Admisión separada por cabina.");
            boolean completed = false;
            long started = System.nanoTime();
            while (!completed) {
                long remaining = LIMIT.toNanos() - (System.nanoTime() - started);
                check(remaining > 0, "Plazo de servicio.");
                var event = rig.elevators.get(0).awaitEvent(Duration.ofNanos(remaining)).orElseThrow();
                check(event.type() != ExecutionEvent.Type.WORKER_FAILED, "Cabina sana.");
                completed = event.type() == ExecutionEvent.Type.COMMAND_COMPLETED
                        && event.command().orElseThrow().commandId() == command.commandId();
                if (completed) {
                    check(event.snapshot().currentFloor() == 2, "Servicio en piso solicitado.");
                    check(event.snapshot().direction() == Direction.UP, "Sentido compatible.");
                }
            }
        }
    }

    public static void dispatchSupportsNWithCostSelection() throws Exception {
        try (var rig = new Rig(3)) {
            for (int i = 0; i < 9; i++) { check(rig.manager.submit(hall(1)), "Encolado."); }
            for (int i = 0; i < 9; i++) {
                var result = result(rig.manager);
                check(result.outcome() == ManagerWorker.Outcome.DISPATCHED
                        || result.outcome() == ManagerWorker.Outcome.DUPLICATE
                        || result.outcome() == ManagerWorker.Outcome.PENDING, "Entrada registrada o despachada.");
                if (result.command().isPresent()) {
                    check(result.command().orElseThrow().elevatorId() == 1, "Empate por ID o alias de esa asignación.");
                }
                check(rig.manager.call(result.request().getId()).isPresent(), "Identidad retenida en seguimiento.");
            }
        }
    }

    public static void lifecycleIsOneShot() throws Exception {
        try (var rig = new Rig(1)) {
            check(!rig.manager.start(), "No duplica hilo.");
            rig.manager.close(); rig.manager.close();
            check(rig.manager.awaitTermination(LIMIT), "Termina estando vacío.");
            check(!rig.manager.start() && !rig.manager.submit(hall(2)), "No reinicia ni recibe.");
            check(rig.elevators.get(0).lifecycle() == ElevatorWorker.Lifecycle.RUNNING,
                    "Cerrar manejador no cierra cabinas prestadas.");
        }
    }

    public static void validatesOnManagerThread() throws Exception {
        try (var rig = new Rig(1)) {
            var invalid = List.of(hall(6),
                    new HallRequest(UUID.randomUUID(), 0, 1, Instant.now(), Direction.DOWN),
                    new HallRequest(UUID.randomUUID(), 0, 5, Instant.now(), Direction.UP),
                    new HallRequest(UUID.randomUUID(), 1, 2, Instant.now(), Direction.UP));
            for (var request : invalid) { check(rig.manager.submit(request), "Recibe para validar."); }
            for (int i = 0; i < invalid.size(); i++) {
                var result = result(rig.manager);
                check(result.outcome() == ManagerWorker.Outcome.REJECTED
                        && result.command().isEmpty(), "Rechazo explícito sin comando.");
                check(result.processedBy().equals(rig.manager.threadName()), "Valida fuera del productor.");
            }
            check(rig.ids.get() == 0, "Entradas inválidas no consumen IDs.");
            rig.manager.submit(hall(2));
            check(result(rig.manager).outcome() == ManagerWorker.Outcome.DISPATCHED,
                    "Sigue funcionando después de rechazos.");
        }
    }

    public static void noAvailableCabinIsExplicit() throws Exception {
        try (var rig = new Rig(1)) {
            rig.elevators.get(0).close();
            check(rig.elevators.get(0).awaitTermination(LIMIT), "Cabina terminada.");
            rig.manager.submit(hall(2));
            check(result(rig.manager).outcome() == ManagerWorker.Outcome.PENDING,
                    "Sin cabinas se conserva la llamada pendiente para reintentar.");
            check(rig.ids.get() == 0, "No genera un comando sin destinatario.");
        }
    }

    public static void closedCabinIsSkipped() throws Exception {
        try (var rig = new Rig(2)) {
            rig.elevators.get(0).close();
            check(rig.elevators.get(0).awaitTermination(LIMIT), "Cabina 1 cerrada.");
            rig.manager.submit(hall(2));
            check(result(rig.manager).command().orElseThrow().elevatorId() == 2, "Usa cabina 2.");
        }
    }

    public static void edtRemainsFreeDuringDispatch() throws Exception {
        try (var rig = new Rig(1)) {
            rig.manager.close(); check(rig.manager.awaitTermination(LIMIT), "Reemplaza manejador vacío.");
            var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
            var onEdt = new AtomicBoolean(true); var pulse = new AtomicBoolean();
            try (var manager = new ManagerWorker(rig.config, 0, rig.elevators, () -> {
                onEdt.set(SwingUtilities.isEventDispatchThread());
                entered.countDown(); awaitGate(release); return rig.ids.incrementAndGet();
            })) {
                manager.start();
                try {
                    SwingUtilities.invokeAndWait(() -> check(manager.submit(hall(2)), "Submit desde EDT."));
                    check(entered.await(5, TimeUnit.SECONDS), "Despacho dentro del hilo propio.");
                    SwingUtilities.invokeAndWait(() -> {
                        pulse.set(true);
                        check(manager.submit(hall(3)), "EDT admite otra entrada durante despacho.");
                        try {
                            manager.awaitResult(Duration.ZERO);
                            expect(IllegalStateException.class, () -> manager.awaitResult(LIMIT));
                            expect(IllegalStateException.class, () -> manager.awaitTermination(LIMIT));
                            expect(IllegalStateException.class, () -> manager.awaitUpdate(LIMIT));
                        } catch (Exception e) { throw new AssertionError(e); }
                    });
                    check(pulse.get() && !onEdt.get(), "EDT avanza mientras el despacho espera.");
                } finally { release.countDown(); }
                result(manager); result(manager);
                manager.close(); check(manager.awaitTermination(LIMIT), "Cierre del manejador alterno.");
            }
        }
    }

    public static void concurrentProducersLoseNoRequests() throws Exception {
        try (var rig = new Rig(3)) {
            var ready = new CountDownLatch(4); var gate = new CountDownLatch(1);
            var error = new AtomicReference<Throwable>(); var producers = new ArrayList<Thread>();
            for (int i = 0; i < 4; i++) {
                var producer = new Thread(() -> {
                    ready.countDown();
                    try {
                        awaitGate(gate);
                        for (int j = 0; j < 12; j++) { check(rig.manager.submit(hall(2)), "Encolado concurrente."); }
                    } catch (Throwable e) { error.compareAndSet(null, e); }
                }, "t29-producer-" + i);
                producers.add(producer); producer.start();
            }
            try { check(ready.await(5, TimeUnit.SECONDS), "Productores listos."); }
            finally { gate.countDown(); }
            for (var producer : producers) { producer.join(5000); check(!producer.isAlive(), "Productor terminó."); }
            check(error.get() == null, "Sin error de productores: " + error.get());
            var requests = new HashSet<UUID>(); var commands = new HashSet<Long>();
            int dispatched = 0;
            for (int i = 0; i < 48; i++) {
                var result = result(rig.manager);
                check(requests.add(result.request().getId()), "Sin entrada repetida ni perdida.");
                if (result.outcome() == ManagerWorker.Outcome.DISPATCHED) {
                    dispatched++;
                    check(commands.add(result.command().orElseThrow().commandId()), "Sin ID de comando repetido.");
                } else {
                    check(result.outcome() == ManagerWorker.Outcome.DUPLICATE
                            || result.outcome() == ManagerWorker.Outcome.PENDING,
                            "Duplicado o pendiente conserva la entrada sin otro despacho.");
                }
            }
            check(dispatched > 0, "Existe progreso de despacho.");
            check(rig.manager.awaitResult(Duration.ZERO).isEmpty(), "Exactamente 48 resultados.");
        }
    }

    public static void closeAccountsForQueuedRequests() throws Exception {
        try (var rig = new Rig(1)) {
            rig.manager.close(); check(rig.manager.awaitTermination(LIMIT), "Manejador original cerrado.");
            var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
            try (var manager = new ManagerWorker(rig.config, 0, rig.elevators, () -> {
                entered.countDown(); awaitGate(release); return rig.ids.incrementAndGet();
            })) {
                manager.start(); manager.submit(hall(2));
                try {
                    check(entered.await(5, TimeUnit.SECONDS), "Primera entrada en despacho.");
                    for (int i = 0; i < 10; i++) { check(manager.submit(hall(3)), "Pendiente en cola."); }
                    manager.close();
                    check(!manager.submit(hall(4)), "Cierre impide nuevas entradas.");
                } finally { release.countDown(); }
                check(manager.awaitTermination(LIMIT), "Cierre termina realmente.");
                check(result(manager).outcome() == ManagerWorker.Outcome.DISPATCHED, "El envío en curso finaliza.");
                for (int i = 0; i < 10; i++) {
                    check(result(manager).outcome() == ManagerWorker.Outcome.CANCELLED, "Pendiente cancelado explícitamente.");
                }
                check(manager.awaitResult(Duration.ZERO).isEmpty(), "Una respuesta por entrada.");
            }
        }
    }

    public static void sharedCommandSequenceAvoidsCollisions() throws Exception {
        try (var rig = new Rig(1)) {
            var elevator = rig.elevators.get(0);
            var destination = new Command.Destination(rig.ids.incrementAndGet(), 1,
                    new CabinRequest(UUID.randomUUID(), 0, 3, Instant.now(), 1));
            check(elevator.submit(destination), "Destino interno encolado.");
            check(elevator.awaitResult(LIMIT).orElseThrow().outcome() == ElevatorWorker.Outcome.ACCEPTED,
                    "Destino interno admitido.");
            rig.manager.submit(hall(2));
            var command = result(rig.manager).command().orElseThrow();
            check(command.commandId() == 2, "Secuencia compartida con destinos internos.");
            check(elevator.awaitResult(LIMIT).orElseThrow().outcome() == ElevatorWorker.Outcome.ACCEPTED,
                    "Sin conflicto con el comando interno.");
        }
    }

    public static void failureIsVisibleAndAccountsForInput() throws Exception {
        try (var rig = new Rig(1)) {
            rig.manager.close(); check(rig.manager.awaitTermination(LIMIT), "Manejador original cerrado.");
            try (var manager = new ManagerWorker(rig.config, 0, rig.elevators, () -> 0)) {
                manager.start(); check(manager.submit(hall(2)), "Entrada aceptada por buzón.");
                check(manager.awaitTermination(LIMIT), "Fallo termina el hilo.");
                check(manager.lifecycle() == ManagerWorker.Lifecycle.FAILED && manager.failure().isPresent(),
                        "Fallo visible.");
                check(result(manager).outcome() == ManagerWorker.Outcome.CANCELLED, "Entrada contabilizada.");
                check(!manager.submit(hall(3)), "No recibe después de fallar.");
            }
        }
    }

    public static void constructorAndNewLifecycleAreValidated() throws Exception {
        try (var rig = new Rig(2)) {
            expect(IllegalArgumentException.class, () -> new ManagerWorker(rig.config, -1, rig.elevators, rig.ids::incrementAndGet));
            expect(IllegalArgumentException.class, () -> new ManagerWorker(rig.config, 0, List.of(), rig.ids::incrementAndGet));
            expect(IllegalArgumentException.class, () -> new ManagerWorker(rig.config, 0,
                    List.of(rig.elevators.get(0), rig.elevators.get(0)), rig.ids::incrementAndGet));
            expect(IllegalArgumentException.class, () -> new ManagerWorker(rig.config, 1, rig.elevators, rig.ids::incrementAndGet));
            expect(NullPointerException.class, () -> rig.manager.submit(null));
            expect(IllegalArgumentException.class, () -> rig.manager.awaitResult(Duration.ofNanos(-1)));
            try (var manager = new ManagerWorker(rig.config, 0, rig.elevators, rig.ids::incrementAndGet)) {
                check(!manager.submit(hall(2)), "NEW no recibe.");
                check(!manager.awaitTermination(Duration.ZERO), "NEW aún no ha terminado.");
                manager.close();
                check(manager.awaitTermination(LIMIT) && !manager.start(), "Cierre antes de Start es definitivo.");
            }
        }
    }

    public static void main(String[] args) throws Exception {
        singleCabinDispatchesAndServes(); dispatchSupportsNWithCostSelection(); lifecycleIsOneShot();
        validatesOnManagerThread(); noAvailableCabinIsExplicit(); closedCabinIsSkipped();
        edtRemainsFreeDuringDispatch(); concurrentProducersLoseNoRequests();
        closeAccountsForQueuedRequests(); sharedCommandSequenceAvoidsCollisions();
        failureIsVisibleAndAccountsForInput(); constructorAndNewLifecycleAreValidated();
        System.out.println("T29: 12 escenarios satisfactorios (hilos reales, N=1/N=3 y EDT).");
    }

    private static HallRequest hall(int floor) {
        return new HallRequest(UUID.randomUUID(), 0, floor, Instant.now(), Direction.UP);
    }
    private static ManagerWorker.Result result(ManagerWorker manager) throws Exception {
        return manager.awaitResult(LIMIT).orElseThrow(() -> new AssertionError("Falta resultado del manejador."));
    }
    private static void awaitGate(CountDownLatch gate) {
        try { check(gate.await(5, TimeUnit.SECONDS), "Barrera liberada dentro del plazo."); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
    }
    private static void check(boolean value, String message) {
        if (!value) { throw new AssertionError(message); }
    }
    @FunctionalInterface private interface CheckedAction { void run() throws Exception; }
    private static void expect(Class<? extends Throwable> type, CheckedAction action) throws Exception {
        try { action.run(); }
        catch (Throwable e) { if (type.isInstance(e)) { return; } throw new AssertionError("Excepción inesperada.", e); }
        throw new AssertionError("Falta excepción " + type.getSimpleName());
    }

    private static final class Rig implements AutoCloseable {
        private final SimulationConfig config;
        private final AtomicLong ids = new AtomicLong();
        private final List<ElevatorWorker> elevators = new ArrayList<>();
        private final ManagerWorker manager;
        private Rig(int count) {
            config = new SimulationConfig(count, 5, 50, 40);
            for (int i = 1; i <= count; i++) {
                var elevator = new ElevatorWorker(i, config, 0);
                elevators.add(elevator); elevator.start();
            }
            var reversed = new ArrayList<ElevatorWorker>();
            for (int i = elevators.size() - 1; i >= 0; i--) { reversed.add(elevators.get(i)); }
            manager = new ManagerWorker(config, 0, reversed, ids::incrementAndGet);
            manager.start();
        }
        @Override public void close() throws Exception {
            manager.close();
            for (var elevator : elevators) { elevator.close(); }
            check(manager.awaitTermination(LIMIT), "Manejador terminado.");
            for (var elevator : elevators) {
                check(elevator.awaitTermination(LIMIT) && elevator.failure().isEmpty(), "Cabina terminada sin fallo.");
            }
            check(manager.failure().isEmpty(), "Manejador sin fallo.");
        }
    }
}
