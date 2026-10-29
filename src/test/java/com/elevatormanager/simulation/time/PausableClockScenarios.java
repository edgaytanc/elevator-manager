package com.elevatormanager.simulation.time;

import com.elevatormanager.model.Direction;
import com.elevatormanager.model.ElevatorState;
import com.elevatormanager.model.SimulationConfig;
import com.elevatormanager.model.request.CabinRequest;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** T54: escenarios ejecutables sin dependencias de JUnit ni escritorio. */
public final class PausableClockScenarios {
    private static final Duration BUDGET = Duration.ofSeconds(30);
    private static final long SECOND = 1_000_000_000L;
    private PausableClockScenarios() { }

    public static void monotonicOriginAndProgress() {
        var source = new AtomicLong(-9 * SECOND);
        try (var clock = new PausableClock(source::get)) {
            equal(0, clock.nowNanos());
            source.addAndGet(SECOND);
            equal(SECOND, clock.nowNanos());
        }
    }

    public static void pauseExcludesWallTime() {
        var source = new AtomicLong();
        try (var clock = new PausableClock(source::get)) {
            source.set(5 * SECOND);
            check(clock.pause(), "Primera pausa");
            source.addAndGet(100 * SECOND);
            equal(5 * SECOND, clock.nowNanos());
            check(clock.resume(), "Primera reanudación");
            source.addAndGet(2 * SECOND);
            equal(7 * SECOND, clock.nowNanos());
        }
    }

    public static void repeatedControlsAreIdempotent() {
        var source = new AtomicLong();
        try (var clock = new PausableClock(source::get)) {
            check(!clock.resume(), "Reanudar activo");
            clock.pause();
            source.set(20 * SECOND);
            check(!clock.pause(), "Pausa repetida");
            check(clock.isPaused(), "Estado pausado");
            clock.resume();
            source.addAndGet(SECOND);
            check(!clock.resume(), "Reanudación repetida");
            equal(SECOND, clock.nowNanos());
        }
    }

    public static void manyCyclesPreserveActiveTime() {
        var source = new AtomicLong();
        try (var clock = new PausableClock(source::get)) {
            for (int i = 0; i < 100; i++) {
                source.addAndGet(SECOND);
                clock.pause();
                source.addAndGet(50 * SECOND);
                clock.resume();
            }
            equal(100 * SECOND, clock.nowNanos());
        }
    }

    public static void nanoTimeWrapIsHandled() {
        var source = new AtomicLong(Long.MAX_VALUE - 50);
        try (var clock = new PausableClock(source::get)) {
            source.addAndGet(100);
            equal(100, clock.nowNanos());
            clock.pause();
            source.addAndGet(1000);
            clock.resume();
            source.addAndGet(10);
            equal(110, clock.nowNanos());
        }
    }

    public static void invalidDurationsAndMarksAreRejected() throws Exception {
        try (var clock = new PausableClock(() -> 0L)) {
            expect(NullPointerException.class, () -> clock.await(null));
            expect(IllegalArgumentException.class, () -> clock.await(Duration.ofNanos(-1)));
            expect(ArithmeticException.class, () -> clock.await(Duration.ofSeconds(Long.MAX_VALUE)));
            expect(IllegalArgumentException.class, () -> clock.awaitElapsed(1, Duration.ZERO));
        }
    }

    public static void zeroAndExpiredBudgetsReturnWhilePaused() throws Exception {
        var source = new AtomicLong();
        try (var clock = new PausableClock(source::get)) {
            source.set(30 * SECOND);
            clock.pause();
            clock.await(Duration.ZERO);
            clock.awaitElapsed(0, BUDGET);
        }
    }

    public static void pausedWaitDoesNotExpireInWallTime() throws Exception {
        var source = new AtomicLong();
        try (var clock = new PausableClock(source::get)) {
            clock.pause();
            try (var waiter = new Waiter(() -> clock.await(BUDGET))) {
                waiter.blocked(Thread.State.WAITING);
                source.set(200 * SECOND);
                equal(0, clock.nowNanos());
                check(waiter.thread.isAlive(), "La pausa no completa el plazo");
                clock.resume();
                waiter.blocked(Thread.State.TIMED_WAITING);
                source.addAndGet(30 * SECOND);
                clock.pause(); // Señala el cambio y permite reconocer el plazo vencido.
                waiter.finished(null);
            }
        }
    }

    public static void partialWaitKeepsRemainingBudget() throws Exception {
        var source = new AtomicLong();
        try (var clock = new PausableClock(source::get);
                var waiter = new Waiter(() -> clock.awaitElapsed(0, BUDGET))) {
            waiter.blocked(Thread.State.TIMED_WAITING);
            source.set(10 * SECOND);
            clock.pause();
            waiter.blocked(Thread.State.WAITING);
            source.addAndGet(300 * SECOND);
            equal(10 * SECOND, clock.nowNanos());
            clock.resume();
            waiter.blocked(Thread.State.TIMED_WAITING);
            source.addAndGet(19 * SECOND);
            clock.pause();
            waiter.blocked(Thread.State.WAITING);
            equal(29 * SECOND, clock.nowNanos());
            clock.resume();
            source.addAndGet(SECOND);
            clock.pause();
            waiter.finished(null);
        }
    }

    public static void resumeWakesEveryWaiter() throws Exception {
        var source = new AtomicLong();
        try (var clock = new PausableClock(source::get)) {
            clock.pause();
            try (var first = new Waiter(() -> clock.await(BUDGET));
                    var second = new Waiter(() -> clock.await(BUDGET))) {
                first.blocked(Thread.State.WAITING);
                second.blocked(Thread.State.WAITING);
                clock.resume();
                first.blocked(Thread.State.TIMED_WAITING);
                second.blocked(Thread.State.TIMED_WAITING);
                source.set(30 * SECOND);
                clock.pause();
                first.finished(null);
                second.finished(null);
            }
        }
    }

    public static void interruptWorksDuringPause() throws Exception {
        try (var clock = new PausableClock()) {
            clock.pause();
            try (var waiter = new Waiter(() -> clock.await(BUDGET))) {
                waiter.blocked(Thread.State.WAITING);
                waiter.thread.interrupt();
                waiter.finished(InterruptedException.class);
                check(clock.isPaused(), "Interrumpir no reanuda el reloj");
            }
        }
    }

    public static void interruptWorksDuringTimedWait() throws Exception {
        try (var clock = new PausableClock();
                var waiter = new Waiter(() -> clock.await(BUDGET))) {
            waiter.blocked(Thread.State.TIMED_WAITING);
            waiter.thread.interrupt();
            waiter.finished(InterruptedException.class);
        }
    }

    public static void interruptedBudgetCanBeRetried() throws Exception {
        var source = new AtomicLong();
        try (var clock = new PausableClock(source::get)) {
            long start = clock.nowNanos();
            try (var first = new Waiter(() -> clock.awaitElapsed(start, BUDGET))) {
                first.blocked(Thread.State.TIMED_WAITING);
                source.set(10 * SECOND);
                first.thread.interrupt();
                first.finished(InterruptedException.class);
            }
            clock.pause();
            source.addAndGet(100 * SECOND);
            try (var retry = new Waiter(() -> clock.awaitElapsed(start, BUDGET))) {
                retry.blocked(Thread.State.WAITING);
                clock.resume();
                retry.blocked(Thread.State.TIMED_WAITING);
                source.addAndGet(20 * SECOND);
                clock.pause();
                retry.finished(null);
            }
        }
    }

    public static void closeWakesPausedAndTimedWaiters() throws Exception {
        for (boolean pause : new boolean[] {false, true}) {
            try (var clock = new PausableClock()) {
                if (pause) { clock.pause(); }
                try (var first = new Waiter(() -> clock.await(BUDGET));
                        var second = new Waiter(() -> clock.await(BUDGET))) {
                    var expected = pause ? Thread.State.WAITING : Thread.State.TIMED_WAITING;
                    first.blocked(expected);
                    second.blocked(expected);
                    clock.close();
                    first.finished(IllegalStateException.class);
                    second.finished(IllegalStateException.class);
                }
            }
        }
    }

    public static void closedClockRejectsNewOperations() throws Exception {
        var clock = new PausableClock();
        clock.close();
        clock.close();
        expect(IllegalStateException.class, clock::nowNanos);
        expect(IllegalStateException.class, clock::pause);
        expect(IllegalStateException.class, clock::resume);
        expect(IllegalStateException.class, () -> clock.await(Duration.ZERO));
        expect(IllegalStateException.class, () -> clock.awaitElapsed(0, BUDGET));
    }

    public static void movementAndDwellUseOnlyActiveTime() {
        var source = new AtomicLong();
        var state = new ElevatorState(1, new SimulationConfig(1, 3, 1000, 1000), 0);
        try (var clock = new PausableClock(source::get)) {
            state.beginJourney(2, clock.nowNanos());
            source.set(400_000_000L);
            clock.pause();
            long revision = state.getRevision();
            equal(600_000_000L, state.movementRemainingNanos(clock.nowNanos()));
            source.addAndGet(100 * SECOND);
            check(!state.completeLegIfDue(clock.nowNanos()), "No llegar durante pausa");
            equal(revision, state.getRevision());
            equal(1, state.getCurrentFloor());
            equal(600_000_000L, state.movementRemainingNanos(clock.nowNanos()));
            clock.resume();
            source.addAndGet(600_000_000L);
            check(state.completeLegIfDue(clock.nowNanos()), "Llegada tras el resto");
            state.addPendingRequest(new CabinRequest(UUID.randomUUID(), 0, 2, Instant.EPOCH, 1));
            state.beginDwell(Direction.UP, clock.nowNanos());
            source.addAndGet(250_000_000L);
            clock.pause();
            source.addAndGet(200 * SECOND);
            equal(750_000_000L, state.dwellRemainingNanos(clock.nowNanos()));
            check(!state.completeDwellIfDue(clock.nowNanos()), "No terminar parada pausada");
            clock.resume();
            source.addAndGet(750_000_000L);
            check(state.completeDwellIfDue(clock.nowNanos()), "Fin de parada tras el resto");
        }
    }

    public static void productionClockWaitsForRealElapsedTime() throws Exception {
        try (var clock = new PausableClock()) {
            long start = clock.nowNanos();
            clock.awaitElapsed(start, Duration.ofMillis(5));
            check(clock.nowNanos() - start >= 5_000_000L, "Sin vencimiento anticipado");
        }
    }

    @FunctionalInterface
    private interface Action { void run() throws Exception; }

    private static final class Waiter implements AutoCloseable {
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private final Thread thread;
        Waiter(Action action) {
            thread = new Thread(() -> {
                try { action.run(); }
                catch (Throwable error) { failure.set(error); }
            }, "t54-waiter");
            thread.start();
        }
        void blocked(Thread.State expected) throws Exception {
            long began = System.nanoTime();
            while (thread.isAlive() && thread.getState() != expected
                    && System.nanoTime() - began < 3_000_000_000L) {
                Thread.sleep(1); // Solo observación acotada de pruebas; no motor.
            }
            check(thread.isAlive() && thread.getState() == expected,
                    "Estado esperado " + expected + "; actual " + thread.getState());
        }
        void finished(Class<? extends Throwable> expected) throws Exception {
            thread.join(3000);
            check(!thread.isAlive(), "El hilo debe terminar");
            Throwable error = failure.get();
            if (expected == null) {
                if (error != null) { throw new AssertionError("Fallo del hilo", error); }
            } else {
                check(expected.isInstance(error), "Excepción esperada: " + expected + "; recibida: " + error);
            }
        }
        @Override public void close() throws InterruptedException {
            thread.interrupt();
            thread.join(3000);
            check(!thread.isAlive(), "Sin hilos abandonados");
        }
    }

    private static void expect(Class<? extends Throwable> type, Action action) throws Exception {
        try { action.run(); }
        catch (Throwable error) {
            if (type.isInstance(error)) { return; }
            throw new AssertionError("Excepción incorrecta", error);
        }
        throw new AssertionError("Falta excepción " + type);
    }
    private static void equal(long expected, long actual) {
        check(expected == actual, "Esperado " + expected + "; recibido " + actual);
    }
    private static void check(boolean condition, String detail) {
        if (!condition) { throw new AssertionError(detail); }
    }

    public static void main(String[] args) throws Exception {
        monotonicOriginAndProgress();
        pauseExcludesWallTime();
        repeatedControlsAreIdempotent();
        manyCyclesPreserveActiveTime();
        nanoTimeWrapIsHandled();
        invalidDurationsAndMarksAreRejected();
        zeroAndExpiredBudgetsReturnWhilePaused();
        pausedWaitDoesNotExpireInWallTime();
        partialWaitKeepsRemainingBudget();
        resumeWakesEveryWaiter();
        interruptWorksDuringPause();
        interruptWorksDuringTimedWait();
        interruptedBudgetCanBeRetried();
        closeWakesPausedAndTimedWaiters();
        closedClockRejectsNewOperations();
        movementAndDwellUseOnlyActiveTime();
        productionClockWaitsForRealElapsedTime();
        System.out.println("T54: 17 escenarios satisfactorios.");
    }
}
