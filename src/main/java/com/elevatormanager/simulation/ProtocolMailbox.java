package com.elevatormanager.simulation;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** Canal de cadenas con un consumidor lógico; publicar no ejecuta callbacks. */
public final class ProtocolMailbox {
    private final LinkedBlockingQueue<String> queue = new LinkedBlockingQueue<>();

    // Solo los actores de este paquete producen respuestas del receptor.
    void publish(String frame) { queue.add(Objects.requireNonNull(frame, "frame")); }

    /** ZERO permite sondeo desde EDT. No esperar desde el hilo gráfico. */
    public Optional<String> await(Duration timeout) throws InterruptedException {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative()) { throw new IllegalArgumentException("Tiempo negativo."); }
        long nanos = timeout.toNanos();
        if (nanos > 0 && javax.swing.SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("No bloquear el EDT esperando protocolo.");
        }
        return Optional.ofNullable(queue.poll(nanos, TimeUnit.NANOSECONDS));
    }
}
