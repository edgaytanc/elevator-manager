package com.elevatormanager.simulation.time;

import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * T55: barrera de N+1 actores sobre un único reloj de tiempo activo.
 * ID 0: manejador; IDs 1..N: cabinas. Los callbacks y la finalización de
 * futuros ocurren fuera del lock; ningún actor espera con su lock retenido.
 */
public final class PauseCoordinator implements AutoCloseable {
    public record Status(long generation, boolean requested, int acknowledged, int participants) { }

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition changed = lock.newCondition();
    private final PausableClock clock = new PausableClock();
    private final boolean[] acknowledged;
    private final boolean[] terminated;
    private long generation;
    private int count;
    private boolean requested;
    private boolean closed;
    private CompletableFuture<Void> completion;

    public PauseCoordinator(int participants) {
        if (participants < 2) { throw new IllegalArgumentException("Se requiere manejador y alguna cabina."); }
        acknowledged = new boolean[participants];
        terminated = new boolean[participants];
    }

    /** Marcas lógicas: compartir esta instancia entre estado, snapshots y estimador. */
    public long nowNanos() { return clock.nowNanos(); }

    public Status status() {
        lock.lock();
        try { return new Status(generation, requested, count, acknowledged.length); }
        finally { lock.unlock(); }
    }

    /** Congela el tiempo antes de solicitar reconocimiento a los actores. */
    public CompletableFuture<Void> requestPause() {
        lock.lock();
        try {
            if (closed) { return CompletableFuture.failedFuture(new IllegalStateException("Barrera cerrada.")); }
            if (requested) { return completion.copy(); }
            for (boolean ended : terminated) {
                if (ended) {
                    return CompletableFuture.failedFuture(new IllegalStateException("Participante terminado."));
                }
            }
            generation = Math.incrementExact(generation);
            Arrays.fill(acknowledged, false);
            count = 0;
            completion = new CompletableFuture<>();
            clock.pause();
            requested = true;
            return completion.copy();
        } finally { lock.unlock(); }
    }

    public boolean isRequested() {
        lock.lock();
        try { return requested; }
        finally { lock.unlock(); }
    }

    /**
     * Punto seguro del actor. Publica su captura congelada antes de reconocer,
     * espera sin sondeo y restaura la captura al salir por reanudación.
     * false indica cierre. Los callbacks pertenecen al propio hilo del actor.
     */
    public boolean checkpoint(int participant, Runnable onPaused, Runnable onResumed)
            throws InterruptedException {
        checkId(participant);
        Objects.requireNonNull(onPaused, "onPaused");
        Objects.requireNonNull(onResumed, "onResumed");
        long ticket;
        lock.lockInterruptibly();
        try {
            if (closed) { return false; }
            if (!requested) { return true; }
            ticket = generation;
        } finally { lock.unlock(); }

        onPaused.run();
        CompletableFuture<Void> ready = null;
        lock.lockInterruptibly();
        try {
            if (!closed && requested && generation == ticket && !acknowledged[participant]) {
                acknowledged[participant] = true;
                if (++count == acknowledged.length) { ready = completion; }
            }
        } finally { lock.unlock(); }
        if (ready != null) { ready.complete(null); }

        lock.lockInterruptibly();
        try {
            while (!closed && requested && generation == ticket) { changed.await(); }
            if (closed) { return false; }
        } finally { lock.unlock(); }
        onResumed.run();
        return true;
    }

    /** Liberación común, solo después de reconocer TODOS los participantes. */
    public boolean resume() {
        lock.lock();
        try {
            if (closed || !requested || count != acknowledged.length) { return false; }
            for (boolean ended : terminated) { if (ended) { return false; } }
            clock.resume();
            requested = false;
            changed.signalAll();
            return true;
        } finally { lock.unlock(); }
    }

    /** Un actor finalizado no puede dejar una barrera pendiente para siempre. */
    public void participantTerminated(int participant, Throwable failure) {
        checkId(participant);
        CompletableFuture<Void> pending;
        lock.lock();
        try {
            terminated[participant] = true;
            pending = requested ? completion : null;
        } finally { lock.unlock(); }
        if (pending != null) {
            pending.completeExceptionally(new IllegalStateException("Participante " + participant + " terminado.", failure));
        }
    }

    /** Despierta actores y cancela la barrera; el reloj sigue legible para eventos de cierre. */
    @Override public void close() {
        CompletableFuture<Void> pending;
        lock.lock();
        try {
            if (closed) { return; }
            closed = true;
            requested = false;
            clock.resume();
            changed.signalAll();
            pending = completion;
        } finally { lock.unlock(); }
        if (pending != null) {
            pending.completeExceptionally(new IllegalStateException("Barrera interrumpida por cierre."));
        }
    }

    private void checkId(int id) {
        if (id < 0 || id >= acknowledged.length) { throw new IllegalArgumentException("Participante fuera de rango."); }
    }
}
