package com.elevatormanager.logging;

import java.io.IOException;
import java.io.OutputStream;
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
                factory.configure(realDirectory, id, console);
            }
            return factory;
        } catch (IOException | RuntimeException | Error error) {
            try { factory.close(); } catch (RuntimeException cleanup) { error.addSuppressed(cleanup); }
            throw error;
        }
    }

    private void configure(Path directory, int id, OutputStream console) throws IOException {
        Logger logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        logger.setLevel(Level.ALL);
        // FileHandler interpreta % como patrón: los porcentajes del directorio son literales.
        String pattern = directory.resolve("elevador" + id + ".log").toString().replace("%", "%%");
        FileHandler file = new FileHandler(pattern, true);
        var owned = new OwnedLogHandlers(logger, List.of(file));
        resources.add(owned); // Desde este punto cualquier fallo cierra el archivo.
        file.setEncoding(StandardCharsets.UTF_8.name());
        file.setFormatter(new EventFormatter(id));
        file.setLevel(Level.ALL);
        var terminal = new FlushingConsoleHandler(console, new EventFormatter(id));
        resources.set(resources.size() - 1, new OwnedLogHandlers(logger, List.of(file, terminal)));
        logger.addHandler(file);
        logger.addHandler(terminal);
        loggers.put(id, logger);
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
        if (!errors.isEmpty()) {
            closeFailure = new IllegalStateException("No se confirmó el cierre del logging.");
            errors.forEach(closeFailure::addSuppressed);
            throw closeFailure;
        }
    }

    /** Herencia funcional: publica y vacía por registro sin cerrar la consola compartida. */
    private static final class FlushingConsoleHandler extends StreamHandler {
        private final OutputStream output;
        FlushingConsoleHandler(OutputStream output, EventFormatter formatter) {
            super(output, formatter);
            this.output = output;
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
        @Override public synchronized void close() { flush(); }
    }
}
