package com.elevatormanager.logging;

import com.elevatormanager.model.Direction;
import com.elevatormanager.protocol.Command;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import javax.swing.SwingUtilities;

/** T42: consumidor de trazas independiente. Cerrar únicamente después de los productores. */
public final class SessionLog implements AutoCloseable {
    private final LoggerFactory factory;
    private final int elevatorCount;
    private final String sessionId = UUID.randomUUID().toString();
    private final TraceMailbox mailbox = new TraceMailbox();
    private final AtomicReference<Throwable> consumerFailure = new AtomicReference<>();
    private final Thread consumer = new Thread(this::consume, "simulation-log-" + sessionId);
    private boolean claimed;
    private boolean closed;
    private RuntimeException closeFailure;

    public static SessionLog open(Path directory, int elevatorCount) throws IOException {
        return open(directory, elevatorCount, System.err);
    }

    public static SessionLog open(Path directory, int elevatorCount, OutputStream console) throws IOException {
        requireOutsideEdt();
        LoggerFactory factory = LoggerFactory.open(directory, elevatorCount, console);
        try { return new SessionLog(factory, elevatorCount); }
        catch (RuntimeException | Error error) {
            try { factory.close(); } catch (RuntimeException cleanup) { error.addSuppressed(cleanup); }
            throw error;
        }
    }

    /** El paquete puede inyectar handlers de prueba; no expone mutable logging al controlador. */
    SessionLog(LoggerFactory factory, int elevatorCount) {
        requireOutsideEdt();
        this.factory = Objects.requireNonNull(factory, "factory");
        if (elevatorCount < 1) { throw new IllegalArgumentException("Cantidad no positiva."); }
        this.elevatorCount = elevatorCount;
        for (int id = 1; id <= elevatorCount; id++) { factory.loggerFor(id); }
        factory.managerLogger();
        consumer.start();
    }

    /** Transferencia única a una sesión compatible, antes de arrancar actores. */
    public synchronized void claim(int count) {
        if (closed || claimed) { throw new IllegalStateException("Logging cerrado o ya asociado."); }
        if (count != elevatorCount) { throw new IllegalArgumentException("Cantidad de cabinas incompatible."); }
        claimed = true;
    }

    public TraceMailbox mailbox() { return mailbox; }
    public String sessionId() { return sessionId; }
    public Optional<LogFailure> ioFailure() { return factory.failure(); }
    public long errorCount() { return factory.errorCount(); }
    public boolean isAlive() { return consumer.isAlive(); }

    /** Visible durante la ejecución y después del cierre, incluso si la consola falló. */
    public Optional<Throwable> failure() {
        Throwable error = consumerFailure.get();
        if (error != null) { return Optional.of(error); }
        return factory.failure().map(incident -> new IOException(
                "Error de logging en " + incident.actor() + ", destino=" + incident.destination(), incident.cause()));
    }

    /** Barrera de observación: solo cubre trazas encoladas antes de esta llamada. No hace I/O en EDT. */
    public CompletableFuture<Void> flushAsync() { return mailbox.barrier(); }

    private void consume() {
        boolean interrupted = false;
        try {
            while (true) {
                TraceMailbox.Entry entry;
                try { entry = mailbox.take(); }
                catch (InterruptedException error) {
                    interrupted = true; reportConsumerFailure(error); continue;
                }
                if (entry instanceof TraceMailbox.End) { return; }
                if (entry instanceof TraceMailbox.Barrier barrier) {
                    Optional<Throwable> error = failure();
                    if (error.isPresent()) { barrier.completion().completeExceptionally(error.orElseThrow()); }
                    else { barrier.completion().complete(null); }
                    continue;
                }
                try { write(entry); }
                catch (RuntimeException | Error error) { reportConsumerFailure(error); }
            }
        } finally { if (interrupted) { Thread.currentThread().interrupt(); } }
    }

    private void reportConsumerFailure(Throwable error) {
        if (consumerFailure.compareAndSet(null, error)) {
            System.err.println("ERROR_CONSUMIDOR_LOGGING; sesion=" + sessionId + "; causa=" + error);
        }
    }

    private void write(TraceMailbox.Entry entry) {
        if (entry instanceof TraceMailbox.Cabin item) {
            var event = item.event();
            var snapshot = event.snapshot();
            String action = switch (event.type()) {
                case DEPARTED -> snapshot.direction() == Direction.UP ? "SUBIENDO" : "BAJANDO";
                case ARRIVED -> "LLEGADA";
                case STOP_STARTED -> "PARADA_RECOGER_DEJAR";
                case STOP_ENDED -> "FIN_PARADA";
                case DIRECTION_CHANGED -> "CAMBIO_DIRECCION";
                case RESET_STARTED -> "RESET_INICIADO";
                case RESET_COMPLETED -> "RESET_COMPLETADO";
                default -> event.type().name();
            };
            Level level = switch (event.type()) {
                case WORKER_FAILED -> Level.SEVERE;
                case COMMAND_REJECTED -> Level.WARNING;
                default -> Level.INFO;
            };
            String text = "evento=" + event.type() + "; accion=" + action
                    + "; secuencia=" + event.sequence() + "; epoca=" + event.epoch()
                    + "; revision=" + snapshot.revision() + "; nanos=" + event.referenceNanos()
                    + "; piso=" + snapshot.currentFloor()
                    + "; siguiente=" + (snapshot.nextFloor().isPresent() ? snapshot.nextFloor().getAsInt() : "-")
                    + "; direccion=" + snapshot.direction() + "; fase=" + snapshot.phase()
                    + "; modo=" + snapshot.mode()
                    + "; comando=" + event.command().map(Command::commandId).map(String::valueOf).orElse("-")
                    + "; canonica=" + event.canonicalRequestId().map(UUID::toString).orElse("-")
                    + "; detalle=" + event.detail();
            publish(factory.loggerFor(event.elevatorId()), item.at(), level, text,
                    event.type() == com.elevatormanager.simulation.ExecutionEvent.Type.WORKER_FAILED ? item.failure() : null);
        } else if (entry instanceof TraceMailbox.Decision item) {
            var result = item.result();
            String text = "evento=DECISION; secuencia=" + item.sequence()
                    + "; epoca=" + result.request().getEpoch() + "; solicitud=" + result.request().getId()
                    + "; piso=" + result.request().getFloor() + "; direccion=" + result.request().getDirection()
                    + "; resultado=" + result.outcome()
                    + "; cabina=" + result.command().map(Command::elevatorId).map(String::valueOf).orElse("-")
                    + "; comando=" + result.command().map(Command::commandId).map(String::valueOf).orElse("-")
                    + "; productor=" + result.processedBy() + "; detalle=" + result.detail();
            publish(factory.managerLogger(), item.at(), result.outcome()
                    == com.elevatormanager.dispatch.ManagerWorker.Outcome.REJECTED ? Level.WARNING : Level.INFO, text, null);
        } else if (entry instanceof TraceMailbox.CallUpdate item) {
            var call = item.call();
            String text = "evento=ESTADO_LLAMADA; secuencia=" + item.sequence()
                    + "; epoca=" + call.request().getEpoch() + "; solicitud=" + call.request().getId()
                    + "; canonica=" + call.canonicalRequestId() + "; estado=" + call.status()
                    + "; cabina=" + call.command().map(Command::elevatorId).map(String::valueOf).orElse("-")
                    + "; comando=" + call.command().map(Command::commandId).map(String::valueOf).orElse("-")
                    + "; intentos=" + call.attempts() + "; detalle=" + call.detail();
            publish(factory.managerLogger(), item.at(), Level.INFO, text, null);
        } else if (entry instanceof TraceMailbox.ManagerState item) {
            publish(factory.managerLogger(), item.at(), item.failure() == null ? Level.INFO : Level.SEVERE,
                    "evento=" + item.state() + "; secuencia=" + item.sequence() + "; epoca=" + item.epoch(), item.failure());
        }
    }

    private void publish(Logger logger, Instant at, Level level, String message, Throwable error) {
        var record = new LogRecord(level, "sesion=" + sessionId + "; " + message);
        record.setInstant(at);
        record.setThrown(error);
        logger.log(record);
    }

    /** Sella después de los actores; el marcador FIFO permite drenar también épocas anteriores. */
    @Override public synchronized void close() {
        requireOutsideEdt();
        if (Thread.currentThread() == consumer) { throw new IllegalStateException("El consumidor no puede esperarse."); }
        if (closed) {
            if (closeFailure != null) { throw closeFailure; }
            return;
        }
        closed = true;
        mailbox.seal();
        boolean interrupted = false;
        while (consumer.isAlive()) {
            try { consumer.join(); } catch (InterruptedException error) { interrupted = true; }
        }
        try {
            try { factory.close(); } catch (RuntimeException error) { closeFailure = error; }
            Throwable error = consumerFailure.get();
            if (error != null) {
                if (closeFailure == null) { closeFailure = new IllegalStateException("Falló el consumidor de logging.", error); }
                else { closeFailure.addSuppressed(error); }
            }
            if (closeFailure != null) { throw closeFailure; }
        } finally { if (interrupted) { Thread.currentThread().interrupt(); } }
    }

    private static void requireOutsideEdt() {
        if (SwingUtilities.isEventDispatchThread()) { throw new IllegalStateException("Abrir/cerrar logging fuera del EDT."); }
    }
}
