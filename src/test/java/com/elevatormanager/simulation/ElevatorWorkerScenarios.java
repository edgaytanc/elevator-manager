package com.elevatormanager.simulation;

import com.elevatormanager.model.Direction;
import com.elevatormanager.model.ElevatorPhase;
import com.elevatormanager.model.SimulationConfig;
import com.elevatormanager.model.request.CabinRequest;
import com.elevatormanager.protocol.Command;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

/** Escenarios reutilizables desde JUnit y desde javac/java sin dependencias. */
public final class ElevatorWorkerScenarios {
    private static final Duration LIMIT = Duration.ofSeconds(5);
    private ElevatorWorkerScenarios() { }

    public static void initialState() throws Exception {
        try (var worker = worker(1)) {
            var snapshot = worker.snapshot();
            check(snapshot.currentFloor() == 1 && snapshot.direction() == Direction.UP,
                    "Inicio en piso 1 y UP.");
            check(snapshot.phase() == ElevatorPhase.IDLE && snapshot.revision() == 0,
                    "Estado inicial intacto.");
            check(worker.threadState() == Thread.State.NEW, "Constructor no arranca hilo.");
            check(!worker.submit(destination(1, 2)), "No encola antes de start.");
        }
    }

    public static void blockingAndWakeup() throws Exception {
        var worker = worker(1);
        try {
            worker.start();
            long beginning = System.nanoTime();
            while (worker.threadState() != Thread.State.WAITING) {
                check(System.nanoTime() - beginning < LIMIT.toNanos(), "Espera bloqueada.");
                LockSupport.parkNanos(1_000_000L);
            }
            check(worker.submit(destination(1, 2)), "Envío admitido al buzón.");
            accepted(worker);
            check(worker.snapshot().cabinRequests().size() == 1, "Señal despierta al consumidor.");
            check(worker.snapshot().currentFloor() == 1, "No cambia de piso antes del plazo.");
        } finally { finish(worker); }
    }

    public static void independentWorkers() throws Exception {
        var first = worker(1);
        var second = worker(2);
        var go = new CountDownLatch(1);
        var done = new CountDownLatch(2);
        var error = new AtomicReference<Throwable>();
        Thread a = producer(first, 1, go, done, error);
        Thread b = producer(second, 2, go, done, error);
        try {
            first.start(); second.start();
            check(!first.threadName().equals(second.threadName()), "Hilos identificables distintos.");
            a.start(); b.start(); go.countDown();
            check(done.await(5, TimeUnit.SECONDS), "Ambos productores terminan.");
            if (error.get() != null) { throw new AssertionError("Productor falló.", error.get()); }
            for (int i = 0; i < 100; i++) { accepted(first); accepted(second); }
            check(first.snapshot().cabinRequests().size() == 100, "Agenda propia 1.");
            check(second.snapshot().cabinRequests().size() == 100, "Agenda propia 2.");
            check(first.snapshot().cabinRequests().stream().allMatch(r -> r.getElevatorId() == 1),
                    "Sin mezcla de cabinas.");
        } finally {
            go.countDown(); a.join(5_000); b.join(5_000);
            finish(first); finish(second);
        }
    }

    public static void rejectionDoesNotKillWorker() throws Exception {
        var worker = worker(1);
        try {
            worker.start();
            worker.submit(destination(2, 2));
            check(result(worker).outcome() == ElevatorWorker.Outcome.REJECTED, "Rechaza destinatario.");
            worker.submit(new Command.Reset(9, 1, 1));
            check(result(worker).outcome() == ElevatorWorker.Outcome.REJECTED, "Reset sin falso éxito.");
            worker.submit(new Command.Destination(10, 1,
                    new CabinRequest(UUID.randomUUID(), 1, 2, Instant.now(), 1)));
            check(result(worker).outcome() == ElevatorWorker.Outcome.REJECTED, "Rechaza otra época.");
            worker.submit(destination(1, 2)); accepted(worker);
            check(worker.failure().isEmpty(), "Rechazos recuperables no matan al trabajador.");
        } finally { finish(worker); }
    }

    public static void immutablePublication() throws Exception {
        var worker = worker(1);
        try {
            var previous = worker.snapshot();
            worker.start(); worker.submit(destination(1, 2)); accepted(worker);
            check(previous.cabinRequests().isEmpty(), "Snapshot anterior permanece intacto.");
            check(worker.snapshot().revision() > previous.revision(), "Revisión creciente.");
            try {
                worker.snapshot().cabinRequests().clear();
                throw new AssertionError("Colección modificable.");
            } catch (UnsupportedOperationException expected) { /* Copia inmutable. */ }
        } finally { finish(worker); }
    }

    public static void closeAccountsForQueuedCommands() throws Exception {
        var worker = worker(1);
        worker.start();
        try {
            for (int floor = 2; floor <= 101; floor++) {
                check(worker.submit(destination(1, floor)), "Encolado previo al cierre.");
            }
            worker.close();
            check(worker.awaitTermination(LIMIT), "Cierre despierta y termina.");
            for (int i = 0; i < 100; i++) { result(worker); }
            check(worker.awaitResult(Duration.ZERO).isEmpty(), "Un resultado por entrada.");
            check(!worker.submit(destination(1, 200)), "Cierre rechaza nuevas entradas.");
            check(worker.lifecycle() == ElevatorWorker.Lifecycle.TERMINATED, "Terminación normal.");
        } finally { finish(worker); }
    }

    public static void singleStartAndCloseBeforeStart() throws Exception {
        var worker = worker(1);
        try {
            check(worker.start() && !worker.start(), "Start idempotente.");
        } finally { finish(worker); }
        check(!worker.start(), "No reinicia hilo finalizado.");
        var neverStarted = worker(1);
        neverStarted.close(); neverStarted.close();
        check(!neverStarted.start() && neverStarted.awaitTermination(Duration.ZERO),
                "Cierre antes de start no crea un hilo.");
    }

    public static void main(String[] args) throws Exception {
        initialState(); blockingAndWakeup(); independentWorkers(); rejectionDoesNotKillWorker();
        immutablePublication(); closeAccountsForQueuedCommands(); singleStartAndCloseBeforeStart();
        System.out.println("T22: 7 escenarios satisfactorios.");
    }

    private static ElevatorWorker worker(int id) {
        return new ElevatorWorker(id, new SimulationConfig(2, 500, 10_000, 100), 0);
    }

    private static Command destination(int id, int floor) {
        return new Command.Destination(floor, id,
                new CabinRequest(UUID.randomUUID(), 0, floor, Instant.now(), id));
    }

    private static Thread producer(ElevatorWorker worker, int id, CountDownLatch go,
            CountDownLatch done, AtomicReference<Throwable> error) {
        return new Thread(() -> {
            try {
                check(go.await(5, TimeUnit.SECONDS), "Inicio coordinado.");
                for (int floor = 2; floor <= 101; floor++) {
                    check(worker.submit(destination(id, floor)), "Envío concurrente.");
                }
            } catch (Throwable exception) { error.compareAndSet(null, exception); }
            finally { done.countDown(); }
        }, "test-producer-" + id);
    }

    private static ElevatorWorker.Result result(ElevatorWorker worker) throws Exception {
        return worker.awaitResult(LIMIT).orElseThrow(() -> new AssertionError("Falta resultado."));
    }

    private static void accepted(ElevatorWorker worker) throws Exception {
        check(result(worker).outcome() == ElevatorWorker.Outcome.ACCEPTED, "Aceptación en agenda.");
    }

    private static void finish(ElevatorWorker worker) throws Exception {
        worker.close();
        check(worker.awaitTermination(LIMIT), "No quedan trabajadores vivos.");
    }

    private static void check(boolean condition, String message) {
        if (!condition) { throw new AssertionError(message); }
    }
}
