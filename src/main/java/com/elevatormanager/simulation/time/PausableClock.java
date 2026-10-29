package com.elevatormanager.simulation.time;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;

/**
 * T54: reloj de tiempo activo y esperas interrumpibles sin sondeo.
 * El origen lógico es cero y la fuente de producción es System.nanoTime.
 * No coordina actores ni autoriza transiciones: esa barrera corresponde a T55.
 * No mezclar sus marcas lógicas con marcas directas de System.nanoTime.
 * Cada intervalo medido debe ser menor que Long.MAX_VALUE nanosegundos.
 */
public final class PausableClock implements AutoCloseable {
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition changed = lock.newCondition();
    private final LongSupplier source;
    private long activeNanos;
    private long sampledAt;
    private boolean paused;
    private boolean closed;

    public PausableClock() { this(System::nanoTime); }

    /** Fuente monotónica inyectable para pruebas del mismo paquete. */
    PausableClock(LongSupplier source) {
        this.source = Objects.requireNonNull(source, "source");
        sampledAt = source.getAsLong();
    }

    public long nowNanos() {
        lock.lock();
        try {
            requireOpen();
            return currentTime();
        } finally { lock.unlock(); }
    }

    /** Idempotente. El instante de adquisición del lock delimita la pausa. */
    public boolean pause() {
        lock.lock();
        try {
            requireOpen();
            if (paused) { return false; }
            activeNanos = currentTime();
            paused = true;
            changed.signalAll();
            return true;
        } finally { lock.unlock(); }
    }

    /** Conserva el tiempo activo acumulado y descarta el intervalo pausado. */
    public boolean resume() {
        lock.lock();
        try {
            requireOpen();
            if (!paused) { return false; }
            sampledAt = source.getAsLong();
            paused = false;
            changed.signalAll();
            return true;
        } finally { lock.unlock(); }
    }

    public boolean isPaused() {
        lock.lock();
        try { return paused; }
        finally { lock.unlock(); }
    }

    /** Espera una duración de tiempo activo desde esta llamada. */
    public void await(Duration duration) throws InterruptedException {
        long budget = budgetNanos(duration);
        lock.lockInterruptibly();
        try {
            requireOpen();
            awaitBudget(currentTime(), budget);
        } finally { lock.unlock(); }
    }

    /**
     * Espera el resto de un plazo iniciado con nowNanos de ESTE reloj.
     * Admite retomar un plazo tras una interrupción sin reiniciarlo.
     * Un plazo ya cumplido (incluido cero) retorna incluso durante pausa.
     * Retornar no garantiza que siga reanudado: T55 debe coordinar al actor.
     */
    public void awaitElapsed(long startedNanos, Duration duration) throws InterruptedException {
        long budget = budgetNanos(duration);
        lock.lockInterruptibly();
        try {
            requireOpen();
            awaitBudget(startedNanos, budget);
        } finally { lock.unlock(); }
    }

    private void awaitBudget(long startedNanos, long budget) throws InterruptedException {
        while (true) {
            requireOpen();
            long elapsed = currentTime() - startedNanos;
            if (elapsed < 0) {
                throw new IllegalArgumentException("Marca futura o intervalo fuera de rango.");
            }
            if (elapsed >= budget) { return; }
            if (paused) { changed.await(); }
            else { changed.awaitNanos(budget - elapsed); }
        }
    }

    private long currentTime() {
        return paused ? activeNanos : activeNanos + (source.getAsLong() - sampledAt);
    }

    private static long budgetNanos(Duration duration) {
        Objects.requireNonNull(duration, "duration");
        if (duration.isNegative()) { throw new IllegalArgumentException("Duración negativa."); }
        return duration.toNanos(); // ArithmeticException si no cabe; nunca trunca ni satura.
    }

    private void requireOpen() {
        if (closed) { throw new IllegalStateException("Reloj cerrado."); }
    }

    /** Despierta todas las esperas con IllegalStateException; no requiere resume. */
    @Override
    public void close() {
        lock.lock();
        try {
            closed = true;
            changed.signalAll();
        } finally { lock.unlock(); }
    }
}
