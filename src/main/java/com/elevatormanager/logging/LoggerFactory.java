package com.elevatormanager.logging;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.logging.FileHandler;
import java.util.logging.Handler;
import java.util.logging.ErrorManager;
import java.util.Optional;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.StreamHandler;

/**
 * Recursos de logging de una sesión. No modifica loggers globales ni handlers ajenos.
 * Registrar esta instancia en SimulationController antes de iniciar los productores.
 */
public final class LoggerFactory implements AutoCloseable {
    private final Map<Integer, Logger> loggers = new LinkedHashMap<>();
    private final List<OwnedLogHandlers> resources = new ArrayList<>();
    private final FileChannel lockChannel;
    private final FileLock directoryLock;
    private Logger managerLogger;
    private final AtomicReference<LogFailure> firstFailure = new AtomicReference<>();
    private final AtomicLong errorCount = new AtomicLong();
    private boolean closed;
    private RuntimeException closeFailure;

    private LoggerFactory(FileChannel channel, FileLock lock) {
        lockChannel = channel;
        directoryLock = lock;
    }

    /** Salida de consola actual de la JVM; se vacía pero nunca se cierra. */
    public static LoggerFactory open(Path directory, int elevatorCount) throws IOException {
        return open(directory, elevatorCount, System.err);
    }

    /**
     * La salida pertenece al llamador. El directorio es exclusivo entre fábricas cooperantes,
     * incluso en JVM distintas; los archivos UTF-8 se abren en modo append.
     */
    public static LoggerFactory open(Path directory, int elevatorCount, OutputStream console)
            throws IOException {
        return open(directory, elevatorCount, console,
                path -> new FileHandler(path.toString().replace("%", "%%"), true));
    }

    /** Inyección local para probar errores reales de StreamHandler sin depender del SO. */
    @FunctionalInterface interface HandlerOpener { Handler open(Path path) throws IOException; }

    static LoggerFactory open(Path directory, int elevatorCount, OutputStream console,
            HandlerOpener opener) throws IOException {
        Objects.requireNonNull(opener, "opener");
        Objects.requireNonNull(directory, "directory");
        Objects.requireNonNull(console, "console");
        if (elevatorCount < 1) { throw new IllegalArgumentException("Cantidad no positiva."); }
        Files.createDirectories(directory);
        Path realDirectory = directory.toRealPath();
        FileChannel channel = FileChannel.open(realDirectory.resolve(".logging.lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock lock;
        try {
            lock = channel.tryLock();
            if (lock == null) { throw new IOException("El directorio de logs ya está en uso."); }
        } catch (IOException | RuntimeException error) {
            try { channel.close(); } catch (IOException cleanup) { error.addSuppressed(cleanup); }
            if (error instanceof OverlappingFileLockException) {
                throw new IOException("El directorio de logs ya está en uso.", error);
            }
            throw error;
        }
        var factory = new LoggerFactory(channel, lock);
        try {
            for (int id = 1; id <= elevatorCount; id++) {
                factory.loggers.put(id, factory.configure(realDirectory.resolve("elevador" + id + ".log"),
                        "elevador=" + id, new EventFormatter(id), console, opener));
            }
            factory.managerLogger = factory.configure(realDirectory.resolve("manejador.log"),
                    "manejador", EventFormatter.forManager(), console, opener);
            if (factory.failure().isPresent()) {
                throw new IOException("Falló la inicialización de los destinos de logging.",
                        factory.failure().orElseThrow().cause());
            }
            return factory;
        } catch (IOException | RuntimeException | Error error) {
            try { factory.close(); } catch (RuntimeException cleanup) { error.addSuppressed(cleanup); }
            throw error;
        }
    }

    private Logger configure(Path filePath, String actor, EventFormatter formatter,
            OutputStream console, HandlerOpener opener) throws IOException {
        Logger logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        logger.setLevel(Level.ALL);
        Handler file = opener.open(filePath);
        var owned = new OwnedLogHandlers(logger, List.of(file));
        resources.add(owned);
        file.setErrorManager(new TrackingErrorManager(actor, filePath.toString()));
        file.setEncoding(StandardCharsets.UTF_8.name());
        file.setFormatter(formatter);
        file.setLevel(Level.ALL);
        var terminal = new FlushingConsoleHandler(console, formatter, new TrackingErrorManager(actor, "consola"));
        resources.set(resources.size() - 1, new OwnedLogHandlers(logger, List.of(file, terminal)));
        logger.addHandler(file);
        logger.addHandler(terminal);
        return logger;
    }

    public synchronized Logger managerLogger() {
        if (closed) { throw new IllegalStateException("Logging cerrado."); }
        return managerLogger;
    }

    public Optional<LogFailure> failure() { return Optional.ofNullable(firstFailure.get()); }
    public long errorCount() { return errorCount.get(); }

    /** No vuelve a registrar el fallo usando el destino averiado ni olvida fallos repetidos. */
    private final class TrackingErrorManager extends ErrorManager {
        private final String actor;
        private final String destination;
        TrackingErrorManager(String actor, String destination) {
            this.actor = actor; this.destination = destination;
        }
        @Override public void error(String message, Exception cause, int code) {
            errorCount.incrementAndGet();
            var incident = new LogFailure(Instant.now(), actor, destination, code,
                    message == null ? "Fallo de handler" : message,
                    cause == null ? new IOException("Error de handler, código " + code) : cause);
            if (firstFailure.compareAndSet(null, incident)) {
                System.err.println("ERROR_LOGGING; actor=" + actor + "; destino=" + destination
                        + "; codigo=" + code + "; causa=" + incident.cause());
            }
        }
    }

    /** La misma identidad durante toda la sesión; no se agregan handlers en cada consulta. */
    public synchronized Logger loggerFor(int elevatorId) {
        if (closed) { throw new IllegalStateException("Logging cerrado."); }
        Logger logger = loggers.get(elevatorId);
        if (logger == null) { throw new IllegalArgumentException("Elevador desconocido: " + elevatorId); }
        return logger;
    }

    /** Invocar después de terminar los productores. Cierre repetido no duplica liberaciones. */
    @Override public synchronized void close() {
        if (closed) {
            if (closeFailure != null) { throw closeFailure; }
            return;
        }
        closed = true;
        var errors = new ArrayList<Exception>();
        for (int i = resources.size() - 1; i >= 0; i--) {
            try { resources.get(i).close(); } catch (RuntimeException error) { errors.add(error); }
        }
        try { directoryLock.release(); } catch (IOException error) { errors.add(error); }
        try { lockChannel.close(); } catch (IOException error) { errors.add(error); }
        failure().ifPresent(incident -> errors.add(new IOException(
                "Logging degradado: " + incident.actor() + ", destino=" + incident.destination(), incident.cause())));
        if (!errors.isEmpty()) {
            closeFailure = new IllegalStateException("No se confirmó el cierre del logging.");
            errors.forEach(closeFailure::addSuppressed);
            throw closeFailure;
        }
    }

    /** Herencia funcional: publica y vacía por registro sin cerrar la consola compartida. */
    private static final class FlushingConsoleHandler extends StreamHandler {
        private final OutputStream output;
        FlushingConsoleHandler(OutputStream output, EventFormatter formatter, ErrorManager errors) {
            super(output, formatter);
            this.output = output;
            setErrorManager(errors);
            try { setEncoding(StandardCharsets.UTF_8.name()); }
            catch (java.io.UnsupportedEncodingException impossible) { throw new AssertionError(impossible); }
            setLevel(Level.ALL);
        }
        @Override public synchronized void publish(LogRecord record) {
            synchronized (output) {
                super.publish(record);
                flush();
            }
        }
        @Override public synchronized void flush() {
            super.flush();
            // PrintStream (incluido System.err) oculta IOException: consultar su indicador.
            if (output instanceof PrintStream terminal && terminal.checkError()) {
                reportError("PrintStream informó un fallo de escritura o flush.",
                        new IOException("El PrintStream de consola tiene errores."), ErrorManager.WRITE_FAILURE);
            }
        }
        @Override public synchronized void close() { flush(); }
    }
}
