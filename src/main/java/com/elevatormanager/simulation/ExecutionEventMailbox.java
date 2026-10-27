package com.elevatormanager.simulation;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** Canal adicional fiable en memoria; no sustituye awaitEvent del trabajador. */
public final class ExecutionEventMailbox {
    private final LinkedBlockingQueue<Optional<ExecutionEvent>> queue = new LinkedBlockingQueue<>();

    // Solo los trabajadores de este paquete publican hechos; no hay callbacks.
    void publish(ExecutionEvent event) { queue.add(Optional.of(event)); }

    /** Despierta al actor por una entrada o cierre, sin fabricar un evento físico. */
    public void wake() { queue.add(Optional.empty()); }

    public Optional<ExecutionEvent> await() throws InterruptedException { return queue.take(); }

    public Optional<ExecutionEvent> await(Duration timeout) throws InterruptedException {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative()) { throw new IllegalArgumentException("Tiempo negativo."); }
        var result = queue.poll(timeout.toNanos(), TimeUnit.NANOSECONDS);
        return result == null ? Optional.empty() : result;
    }
}
