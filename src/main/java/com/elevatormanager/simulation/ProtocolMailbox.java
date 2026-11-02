package com.elevatormanager.simulation;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** Canal de cadenas con un consumidor lógico; publicar no ejecuta callbacks. */
public final class ProtocolMailbox {
    private final LinkedBlockingQueue<Optional<String>> queue = new LinkedBlockingQueue<>();

    // Solo los actores de este paquete producen respuestas del receptor.
    void publish(String frame) { queue.add(Optional.of(Objects.requireNonNull(frame, "frame"))); }

    /** Señal sin mensaje para entradas/cierre de un actor consumidor. */
    public void wake() { queue.add(Optional.empty()); }

    public Optional<String> await() throws InterruptedException {
        if (javax.swing.SwingUtilities.isEventDispatchThread()) { throw new IllegalStateException("No bloquear el EDT."); }
        return queue.take();
    }

    /** ZERO permite sondeo desde EDT. No esperar desde el hilo gráfico. */
    public Optional<String> await(Duration timeout) throws InterruptedException {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative()) { throw new IllegalArgumentException("Tiempo negativo."); }
        long nanos = timeout.toNanos();
        if (nanos > 0 && javax.swing.SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("No bloquear el EDT esperando protocolo.");
        }
        var frame = queue.poll(nanos, TimeUnit.NANOSECONDS);
        return frame == null ? Optional.empty() : frame;
    }
}
