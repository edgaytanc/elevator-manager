package com.elevatormanager.simulation.time;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Pruebas de la barrera sin depender de la velocidad de las cabinas. */
public final class PauseCoordinatorScenarios {
    private PauseCoordinatorScenarios() { }

    public static void validatesParticipants() {
        for (int n : new int[] {-1, 0, 1}) {
            try { new PauseCoordinator(n); throw new AssertionError("Debe rechazar " + n); }
            catch (IllegalArgumentException expected) { }
        }
        try (var gate = new PauseCoordinator(2)) {
            check(gate.status().participants() == 2, "Manejador y una cabina");
        }
    }

    public static void barrierRequiresEveryActor() throws Exception {
        try (var gate = new PauseCoordinator(3)) {
            var future = gate.requestPause();
            try (var first = new Actor(gate, 0); var second = new Actor(gate, 1)) {
                first.waiting(); second.waiting();
                check(gate.status().acknowledged() == 2, "Dos reconocimientos");
                check(!future.isDone() && !gate.resume(), "Sin PAUSED ni reanudación prematura");
                try (var third = new Actor(gate, 2)) {
                    future.get(3, TimeUnit.SECONDS);
                    check(gate.status().acknowledged() == 3, "Barrera completa");
                    check(gate.resume(), "Reanudación permitida");
                    first.finished(); second.finished(); third.finished();
                }
            }
        }
    }

    public static void clockFreezesBeforeAcknowledgements() throws Exception {
        try (var gate = new PauseCoordinator(2)) {
            gate.requestPause();
            long frozen = gate.nowNanos();
            Thread.sleep(25);
            check(gate.nowNanos() == frozen, "Congelado desde PAUSING, antes de los ACK");
            check(gate.status().acknowledged() == 0, "Sin ACK inventados");
        }
    }

    public static void callbackFinishesBeforeAcknowledgement() throws Exception {
        try (var gate = new PauseCoordinator(2)) {
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var failure = new AtomicReference<Throwable>();
            var future = gate.requestPause();
            Thread slow = new Thread(() -> {
                try {
                    gate.checkpoint(1, () -> {
                        entered.countDown();
                        try { check(release.await(3, TimeUnit.SECONDS), "Liberación de captura"); }
                        catch (InterruptedException e) { throw new AssertionError(e); }
                    }, () -> { });
                } catch (Throwable e) { failure.set(e); }
            }, "t55-delayed-snapshot");
            slow.start();
            try (var manager = new Actor(gate, 0)) {
                check(entered.await(3, TimeUnit.SECONDS), "Captura iniciada");
                manager.waiting();
                check(gate.status().acknowledged() == 1 && !future.isDone(), "Captura pendiente no confirma");
                release.countDown();
                future.get(3, TimeUnit.SECONDS);
                gate.resume();
                manager.finished();
            } finally {
                release.countDown(); gate.close(); slow.join(3000);
                check(!slow.isAlive() && failure.get() == null, "Captura terminó sin fallo");
            }
        }
    }

    public static void publicCancellationDoesNotCancelBarrier() throws Exception {
        try (var gate = new PauseCoordinator(2)) {
            var cancelled = gate.requestPause();
            var observer = gate.requestPause();
            cancelled.cancel(true);
            try (var manager = new Actor(gate, 0); var cabin = new Actor(gate, 1)) {
                observer.get(3, TimeUnit.SECONDS);
                check(observer.isDone() && !observer.isCancelled(), "Barrera privada conservada");
                gate.resume(); manager.finished(); cabin.finished();
            }
        }
    }

    public static void repeatedPausePreservesGeneration() throws Exception {
        try (var gate = new PauseCoordinator(2)) {
            var first = gate.requestPause();
            long generation = gate.status().generation();
            var second = gate.requestPause();
            check(gate.status().generation() == generation, "Misma operación");
            try (var manager = new Actor(gate, 0); var cabin = new Actor(gate, 1)) {
                first.get(3, TimeUnit.SECONDS); second.get(3, TimeUnit.SECONDS);
                check(gate.resume() && !gate.resume(), "Una única liberación");
                manager.finished(); cabin.finished();
            }
        }
    }

    public static void repeatedGenerationsCannotReuseOldAcks() throws Exception {
        try (var gate = new PauseCoordinator(2)) {
            for (int cycle = 1; cycle <= 12; cycle++) {
                var future = gate.requestPause();
                check(gate.status().generation() == cycle && gate.status().acknowledged() == 0,
                        "Generación nueva sin ACK previos");
                try (var manager = new Actor(gate, 0); var cabin = new Actor(gate, 1)) {
                    future.get(3, TimeUnit.SECONDS);
                    gate.resume(); manager.finished(); cabin.finished();
                }
            }
        }
    }

    public static void closeCancelsPartialBarrierAndWakesActors() throws Exception {
        try (var gate = new PauseCoordinator(3)) {
            var future = gate.requestPause();
            try (var manager = new Actor(gate, 0)) {
                manager.waiting(); gate.close(); gate.close();
                manager.finished();
                check(future.isCompletedExceptionally(), "Barrera cancelada sin esperar al ausente");
                check(gate.requestPause().isCompletedExceptionally() && !gate.resume(), "Cierre definitivo");
            }
        }
    }

    public static void terminatedParticipantRejectsPause() {
        try (var gate = new PauseCoordinator(2)) {
            gate.participantTerminated(1, new IllegalStateException("fallo de prueba"));
            check(gate.requestPause().isCompletedExceptionally(), "Participante fallido no es pausado");
            check(!gate.isRequested(), "No deja reloj congelado");
        }
    }

    public static void failureCompletesBarrierWithoutWaitingForOthers() {
        try (var gate = new PauseCoordinator(3)) {
            var future = gate.requestPause();
            gate.participantTerminated(1, new IllegalStateException("fallo de prueba"));
            check(future.isCompletedExceptionally(), "Fallo inmediato, no allOf infinito");
            check(!gate.resume(), "Sin reanudar barrera fallida");
        }
    }

    public static void invalidParticipantIsRejected() throws Exception {
        try (var gate = new PauseCoordinator(2)) {
            try { gate.checkpoint(2, () -> { }, () -> { }); throw new AssertionError("ID inválido"); }
            catch (IllegalArgumentException expected) { }
        }
    }

    public static void activeCheckpointDoesNotPublishPause() throws Exception {
        try (var gate = new PauseCoordinator(2)) {
            var callbacks = new AtomicInteger();
            check(gate.checkpoint(0, callbacks::incrementAndGet, callbacks::incrementAndGet), "Activo");
            check(callbacks.get() == 0, "Sin publicación artificial");
        }
    }

    private static final class Actor implements AutoCloseable {
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final Thread thread;
        Actor(PauseCoordinator gate, int id) {
            thread = new Thread(() -> {
                try { gate.checkpoint(id, () -> { }, () -> { }); }
                catch (Throwable e) { failure.set(e); gate.participantTerminated(id, e); }
            }, "t55-barrier-" + id);
            thread.start();
        }
        void waiting() throws Exception {
            long start = System.nanoTime();
            while (thread.isAlive() && thread.getState() != Thread.State.WAITING
                    && System.nanoTime() - start < TimeUnit.SECONDS.toNanos(3)) { Thread.sleep(1); }
            check(thread.isAlive() && thread.getState() == Thread.State.WAITING, "Actor esperando en condición");
        }
        void finished() throws Exception {
            thread.join(3000);
            check(!thread.isAlive() && failure.get() == null, "Actor finalizado sin error: " + failure.get());
        }
        @Override public void close() throws InterruptedException {
            thread.interrupt(); thread.join(3000);
            check(!thread.isAlive(), "Sin hilo abandonado");
        }
    }

    private static void check(boolean condition, String detail) {
        if (!condition) { throw new AssertionError(detail); }
    }

    public static void main(String[] args) throws Exception {
        validatesParticipants();
        barrierRequiresEveryActor();
        clockFreezesBeforeAcknowledgements();
        callbackFinishesBeforeAcknowledgement();
        publicCancellationDoesNotCancelBarrier();
        repeatedPausePreservesGeneration();
        repeatedGenerationsCannotReuseOldAcks();
        closeCancelsPartialBarrierAndWakesActors();
        terminatedParticipantRejectsPause();
        failureCompletesBarrierWithoutWaitingForOthers();
        invalidParticipantIsRejected();
        activeCheckpointDoesNotPublishPause();
        System.out.println("T55: 12 escenarios de barrera satisfactorios.");
    }
}
