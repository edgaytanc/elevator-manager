package com.elevatormanager.simulation.time;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** T60: anclaje exacto y lectura sin adquirir el lock del reloj. */
public final class ClockViewScenarios {
    private ClockViewScenarios() { }
    public static void publishedViewMatchesActiveTime() {
        var source = new AtomicLong(1000);
        try (var c = new PausableClock(source::get)) {
            source.set(1350);
            check(c.view().at(source.get()) == c.nowNanos() && c.nowNanos() == 350, "Mismo dominio activo");
            check(c.view() == c.view(), "Lectura de referencia publicada sin crear una captura");
        }
    }
    public static void repeatedPauseResumeExcludesWallIntervals() {
        var source = new AtomicLong(1000);
        try (var c = new PausableClock(source::get)) {
            source.addAndGet(200); c.pause(); var frozen = c.view();
            source.addAndGet(5000); check(c.view().at(source.get()) == 200, "Tiempo pausado excluido");
            c.resume(); source.addAndGet(100); check(c.view().at(source.get()) == 300, "Solo resto activo");
            c.pause(); source.addAndGet(9000); c.resume(); source.addAndGet(50);
            check(c.view().at(source.get()) == 350 && frozen.at(source.get()) == 200, "Ciclos y vista antigua inmutable");
        }
    }
    public static void closingFreezesReadableView() {
        var source = new AtomicLong(1000); var c = new PausableClock(source::get);
        source.set(1200); c.close(); var finalView = c.view(); source.set(9000);
        check(finalView.paused() && finalView.at(source.get()) == 200, "Vista final congelada");
        c.close(); check(c.view() == finalView, "Cierre idempotente de la vista");
    }
    public static void readingViewDoesNotWaitForClockLock() throws Exception {
        var source = new AtomicLong(1000); var block = new AtomicBoolean();
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var c = new PausableClock(() -> {
            if (block.get()) {
                entered.countDown();
                try { if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("Liberación de fuente"); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
            }
            return source.get();
        });
        var threads = Executors.newFixedThreadPool(2);
        try {
            block.set(true); var locked = threads.submit(c::nowNanos);
            check(entered.await(3, TimeUnit.SECONDS), "Fuente mantiene el lock del reloj");
            var read = threads.submit(c::view);
            check(read.get(1, TimeUnit.SECONDS).at(1100) == 100, "Lectura independiente del lock");
            release.countDown(); locked.get(3, TimeUnit.SECONDS);
        } finally {
            release.countDown(); block.set(false); threads.shutdownNow();
            check(threads.awaitTermination(3, TimeUnit.SECONDS), "Cierre de comprobadores"); c.close();
        }
    }
    private static void check(boolean condition, String detail) { if (!condition) throw new AssertionError(detail); }
    public static void main(String[] args) throws Exception {
        publishedViewMatchesActiveTime(); repeatedPauseResumeExcludesWallIntervals(); closingFreezesReadableView();
        readingViewDoesNotWaitForClockLock(); System.out.println("T60: 4 escenarios de vista temporal satisfactorios.");
    }
}
