# T41 — Código completo

Los cinco fuentes nuevos de esta tarjeta. El ZIP incluye además todos los fuentes anteriores del proyecto, documentación y pom.xml.

## src/main/java/com/elevatormanager/logging/EventFormatter.java

```java
package com.elevatormanager.logging;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.logging.Formatter;
import java.util.logging.LogRecord;

/** Formato independiente del idioma local; la fecha ISO-8601 se expresa en UTC. */
public final class EventFormatter extends Formatter {
    private final int elevatorId;

    public EventFormatter(int elevatorId) {
        if (elevatorId < 1) { throw new IllegalArgumentException("ID de elevador no positivo."); }
        this.elevatorId = elevatorId;
    }

    @Override public String format(LogRecord record) {
        Objects.requireNonNull(record, "record");
        String message = String.valueOf(formatMessage(record)).replace("\r", "\\r").replace("\n", "\\n");
        String text = DateTimeFormatter.ISO_INSTANT.format(record.getInstant())
                + " [" + record.getLevel().getName() + "] [elevador=" + elevatorId + "] "
                + message + System.lineSeparator();
        if (record.getThrown() != null) {
            var trace = new StringWriter();
            record.getThrown().printStackTrace(new PrintWriter(trace));
            text += trace;
        }
        return text;
    }
}
```

## src/main/java/com/elevatormanager/logging/LoggerFactory.java

```java
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
```

## src/main/java/com/elevatormanager/diagnostics/LoggingDemo.java

```java
package com.elevatormanager.diagnostics;

import com.elevatormanager.logging.LoggerFactory;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** T41: comprobación manual del logging; sus mensajes no son movimientos del motor. */
public final class LoggingDemo {
    private LoggingDemo() { }

    public static void main(String[] args) throws Exception {
        if (args.length > 1) { throw new IllegalArgumentException("Uso: LoggingDemo [directorio]"); }
        Path directory = Path.of(args.length == 0 ? "logs" : args[0]);
        try (var logs = LoggerFactory.open(directory, 2)) {
            var producers = Executors.newFixedThreadPool(2);
            try {
                var tasks = new ArrayList<Future<?>>();
                for (int id = 1; id <= 2; id++) {
                    int cabin = id;
                    tasks.add(producers.submit(() -> {
                        for (int sample = 1; sample <= 5; sample++) {
                            logs.loggerFor(cabin).info("PRUEBA_LOGGING; muestra=" + sample);
                        }
                    }));
                }
                for (var task : tasks) { task.get(10, TimeUnit.SECONDS); }
            } finally {
                producers.shutdownNow();
                if (!producers.awaitTermination(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("No terminaron los productores del diagnóstico.");
                }
            }
        }
        System.out.println("T41: archivos en " + directory.toAbsolutePath().normalize());
    }
}
```

## src/test/java/com/elevatormanager/logging/LoggingScenarios.java

```java
package com.elevatormanager.logging;

import com.elevatormanager.model.SimulationConfig;
import com.elevatormanager.model.SimulationState;
import com.elevatormanager.simulation.SimulationController;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/** Escenarios ejecutables sin JUnit. Cada prueba limpia sus directorios y productores. */
public final class LoggingScenarios {
    private LoggingScenarios() { }

    public static void filesAreIsolatedAndConsoleContainsBoth() throws Exception {
        inDirectory(dir -> {
            var console = new ByteArrayOutputStream();
            try (var logs = LoggerFactory.open(dir, 2, console)) {
                logs.loggerFor(1).info("SOLO_UNO"); logs.loggerFor(2).info("SOLO_DOS");
            }
            check(read(dir, 1).contains("SOLO_UNO") && !read(dir, 1).contains("SOLO_DOS"), "Archivo 1 aislado");
            check(read(dir, 2).contains("SOLO_DOS") && !read(dir, 2).contains("SOLO_UNO"), "Archivo 2 aislado");
            check(console.toString(StandardCharsets.UTF_8).lines().count() == 2, "Dos registros en consola");
            check(Files.exists(dir.resolve("elevador1.log")), "Nombre exacto");
            check(Files.exists(dir.resolve("elevador2.log")), "Nombre exacto 2");
        });
    }

    public static void formatterHasDateIdLevelAndParameters() {
        var record = new LogRecord(Level.WARNING, "Piso {0}");
        record.setParameters(new Object[] { 7 });
        record.setInstant(Instant.parse("2026-10-28T18:00:00Z"));
        check(new EventFormatter(3).format(record).equals(
                "2026-10-28T18:00:00Z [WARNING] [elevador=3] Piso 7" + System.lineSeparator()), "Formato exacto");
        record.setMessage(null);
        check(new EventFormatter(3).format(record).contains("null"), "Mensaje nulo admitido por JUL");
    }

    public static void utf8AndLineBreaksArePreservedSafely() throws Exception {
        inDirectory(dir -> {
            var console = new ByteArrayOutputStream();
            try (var logs = LoggerFactory.open(dir, 1, console)) { logs.loggerFor(1).info("Dirección: ñ á\r\nfin"); }
            String text = read(dir, 1);
            check(text.contains("Dirección: ñ á\\r\\nfin"), "UTF-8 y saltos escapados");
            check(text.lines().count() == 1, "Un registro físico");
            check(text.equals(console.toString(StandardCharsets.UTF_8)), "Formato igual en destinos");
        });
    }

    public static void parentHandlersAreNotInvoked() throws Exception {
        Logger root = Logger.getLogger("");
        var seen = new AtomicInteger();
        Handler foreign = new Handler() {
            @Override public void publish(LogRecord record) { seen.incrementAndGet(); }
            @Override public void flush() { }
            @Override public void close() { }
        };
        root.addHandler(foreign);
        try {
            inDirectory(dir -> {
                try (var logs = LoggerFactory.open(dir, 1, new ByteArrayOutputStream())) {
                    check(!logs.loggerFor(1).getUseParentHandlers(), "Herencia desactivada");
                    logs.loggerFor(1).warning("NO_PROPAGAR");
                }
            });
            check(seen.get() == 0, "No duplica en el padre");
            check(List.of(root.getHandlers()).contains(foreign), "No elimina handlers ajenos");
        } finally { root.removeHandler(foreign); }
    }

    public static void repeatedLookupDoesNotAddHandlers() throws Exception {
        inDirectory(dir -> {
            try (var logs = LoggerFactory.open(dir, 1, new ByteArrayOutputStream())) {
                Logger first = logs.loggerFor(1);
                for (int i = 0; i < 1000; i++) { check(first == logs.loggerFor(1), "Identidad estable"); }
                check(first.getHandlers().length == 2, "Solo dos destinos"); first.info("UNA_VEZ");
            }
            check(read(dir, 1).lines().count() == 1, "Un registro en archivo");
        });
    }

    public static void recordsAreFlushedBeforeClose() throws Exception {
        inDirectory(dir -> {
            var console = new ByteArrayOutputStream();
            try (var logs = LoggerFactory.open(dir, 1, console)) {
                logs.loggerFor(1).info("VISIBLE");
                check(read(dir, 1).contains("VISIBLE"), "Archivo vaciado");
                check(console.toString(StandardCharsets.UTF_8).contains("VISIBLE"), "Consola vaciada");
            }
        });
    }

    public static void closeIsIdempotentAndDoesNotCloseConsole() throws Exception {
        inDirectory(dir -> {
            var closed = new AtomicInteger();
            var console = new ByteArrayOutputStream() {
                @Override public void close() { closed.incrementAndGet(); }
            };
            var logs = LoggerFactory.open(dir, 1, console);
            Logger logger = logs.loggerFor(1); logger.info("ANTES"); logs.close(); logs.close();
            check(logger.getHandlers().length == 0, "Handlers desconectados");
            logger.info("DESPUES");
            check(!read(dir, 1).contains("DESPUES"), "Referencia vieja no escribe");
            check(closed.get() == 0, "Consola del llamador permanece abierta");
            expect(IllegalStateException.class, () -> logs.loggerFor(1));
            console.write('X');
        });
    }

    public static void reopeningAppendsAndReleasesFileLocks() throws Exception {
        inDirectory(dir -> {
            for (int i = 0; i < 3; i++) {
                try (var logs = LoggerFactory.open(dir, 1, new ByteArrayOutputStream())) { logs.loggerFor(1).info("SESION_" + i); }
            }
            check(read(dir, 1).lines().count() == 3, "Append entre sesiones");
            try (var paths = Files.list(dir)) {
                check(paths.noneMatch(p -> p.getFileName().toString().endsWith(".lck")), "Sin bloqueos FileHandler");
            }
            Files.move(dir.resolve("elevador1.log"), dir.resolve("movido.log"));
        });
    }

    public static void simultaneousSessionInSameDirectoryIsRejected() throws Exception {
        inDirectory(dir -> {
            try (var first = LoggerFactory.open(dir, 1, new ByteArrayOutputStream())) {
                expect(IOException.class, () -> {
                    try (var unexpected = LoggerFactory.open(dir.resolve("."), 1, new ByteArrayOutputStream())) {
                        throw new AssertionError("Sesión duplicada aceptada");
                    }
                });
                first.loggerFor(1).info("ORIGINAL");
                check(read(dir, 1).lines().count() == 1, "Primera sesión intacta");
            }
        });
    }

    public static void differentDirectoriesHaveIndependentSessions() throws Exception {
        inDirectory(dir -> {
            var console = new ByteArrayOutputStream();
            try (var a = LoggerFactory.open(dir.resolve("a"), 1, console);
                 var b = LoggerFactory.open(dir.resolve("b"), 1, console)) {
                check(a.loggerFor(1) != b.loggerFor(1), "Sin logger global compartido");
                a.loggerFor(1).info("A"); a.close(); b.loggerFor(1).info("B");
                check(read(dir.resolve("b"), 1).contains("B"), "Cerrar A no cierra B");
            }
        });
    }

    public static void percentInDirectoryIsLiteral() throws Exception {
        inDirectory(dir -> {
            Path literal = dir.resolve("logs-%u-%g-ñ");
            try (var logs = LoggerFactory.open(literal, 1, new ByteArrayOutputStream())) { logs.loggerFor(1).info("LITERAL"); }
            check(read(literal, 1).contains("LITERAL"), "Porcentajes no se expanden");
        });
    }

    public static void failedSetupReleasesEarlierHandlersAndDirectoryLock() throws Exception {
        inDirectory(dir -> {
            Files.createDirectory(dir.resolve("elevador2.log"));
            expect(IOException.class, () -> {
                try (var unexpected = LoggerFactory.open(dir, 2, new ByteArrayOutputStream())) {
                    throw new AssertionError("Apertura debía fallar");
                }
            });
            Files.delete(dir.resolve("elevador2.log"));
            try (var logs = LoggerFactory.open(dir, 2, new ByteArrayOutputStream())) { logs.loggerFor(1).info("RECUPERADO"); }
            check(read(dir, 1).lines().count() == 1, "Sin duplicados tras rollback");
        });
    }

    public static void invalidArgumentsDoNotCreateFiles() throws Exception {
        inDirectory(dir -> {
            expect(NullPointerException.class, () -> LoggerFactory.open(null, 1));
            expect(NullPointerException.class, () -> LoggerFactory.open(dir, 1, null));
            expect(IllegalArgumentException.class, () -> LoggerFactory.open(dir, 0));
            expect(IllegalArgumentException.class, () -> new EventFormatter(0));
            try (var files = Files.list(dir)) { check(files.findAny().isEmpty(), "Validación sin efectos"); }
            try (var logs = LoggerFactory.open(dir, 2, new ByteArrayOutputStream())) {
                expect(IllegalArgumentException.class, () -> logs.loggerFor(0));
                expect(IllegalArgumentException.class, () -> logs.loggerFor(3));
            }
        });
    }

    public static void concurrentCabinsWriteExactlyOncePerDestination() throws Exception {
        inDirectory(dir -> {
            var console = new ByteArrayOutputStream();
            var pool = Executors.newFixedThreadPool(4);
            var start = new CountDownLatch(1);
            List<Future<?>> jobs = new ArrayList<>();
            try (var logs = LoggerFactory.open(dir, 4, console)) {
                try {
                    for (int id = 1; id <= 4; id++) {
                        int cabin = id;
                        jobs.add(pool.submit(() -> {
                            try { if (!start.await(10, TimeUnit.SECONDS)) { throw new AssertionError("Inicio agotado"); } }
                            catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new AssertionError(error); }
                            for (int i = 0; i < 100; i++) { logs.loggerFor(cabin).info("C" + cabin + "-" + i + ":" + "ñ".repeat(5000)); }
                        }));
                    }
                    start.countDown();
                    for (var job : jobs) { job.get(15, TimeUnit.SECONDS); }
                } finally {
                    start.countDown(); pool.shutdownNow();
                    check(pool.awaitTermination(10, TimeUnit.SECONDS), "Productores terminados");
                }
            }
            List<String> terminal = console.toString(StandardCharsets.UTF_8).lines().toList();
            check(terminal.size() == 400, "400 líneas completas en consola");
            for (int id = 1; id <= 4; id++) {
                List<String> lines = read(dir, id).lines().toList();
                check(lines.size() == 100, "100 por cabina");
                for (String line : lines) { check(terminal.stream().filter(line::equals).count() == 1, "Exactamente una copia en consola"); }
                for (int i = 0; i < 100; i++) {
                    String token = "C" + id + "-" + i + ":";
                    check(lines.stream().filter(line -> line.contains(token)).count() == 1, "ID de registro único");
                }
            }
        });
    }

    public static void controllerOwnsAndReleasesLogging() throws Exception {
        inDirectory(dir -> {
            var controller = new SimulationController(new SimulationConfig(2, 5, 10, 10), 0);
            var logs = LoggerFactory.open(dir, 2, new ByteArrayOutputStream());
            try {
                check(controller.registerResource(logs).get(10, TimeUnit.SECONDS).outcome()
                        == SimulationController.Outcome.APPLIED, "Propiedad transferida");
                controller.start().get(10, TimeUnit.SECONDS);
                logs.loggerFor(2).info("RECURSO_DE_SESION");
                check(controller.closeAsync().get(10, TimeUnit.SECONDS).state() == SimulationState.CLOSED, "CLOSED real");
                check(controller.awaitTermination(Duration.ofSeconds(10)), "Actores terminados");
                expect(IllegalStateException.class, () -> logs.loggerFor(2));
                try (var reopened = LoggerFactory.open(dir, 2, new ByteArrayOutputStream())) {
                    reopened.loggerFor(2).info("LIBERADO");
                }
            } finally {
                controller.closeAsync().get(10, TimeUnit.SECONDS);
                controller.awaitTermination(Duration.ofSeconds(10)); logs.close();
            }
            check(read(dir, 2).lines().count() == 2, "Cierre de sesión libera logs");
        });
    }

    public static void levelsAndExceptionsRemainVisible() throws Exception {
        inDirectory(dir -> {
            var console = new ByteArrayOutputStream();
            try (var logs = LoggerFactory.open(dir, 1, console)) {
                logs.loggerFor(1).fine("DETALLE");
                logs.loggerFor(1).log(Level.SEVERE, "ERROR", new IOException("CAUSA"));
            }
            String text = read(dir, 1);
            check(text.contains("[FINE]") && text.contains("[SEVERE]"), "Niveles conservados");
            check(text.contains("java.io.IOException: CAUSA"), "Causa preservada");
            check(text.equals(console.toString(StandardCharsets.UTF_8)), "Excepción en ambos destinos");
        });
    }

    private static String read(Path directory, int id) throws IOException {
        return Files.readString(directory.resolve("elevador" + id + ".log"), StandardCharsets.UTF_8);
    }
    private static void check(boolean condition, String message) { if (!condition) { throw new AssertionError(message); } }
    private interface Action { void run() throws Exception; }
    private interface DirectoryAction { void run(Path path) throws Exception; }
    private static void expect(Class<? extends Throwable> type, Action action) throws Exception {
        try { action.run(); }
        catch (Throwable error) { if (type.isInstance(error)) { return; } throw new AssertionError("Excepción inesperada", error); }
        throw new AssertionError("Falta excepción " + type.getSimpleName());
    }
    private static void inDirectory(DirectoryAction action) throws Exception {
        Path dir = Files.createTempDirectory("t41-");
        try { action.run(dir); }
        finally {
            try (var paths = Files.walk(dir)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) { Files.delete(path); }
            }
        }
    }
    public static void main(String[] args) throws Exception {
        filesAreIsolatedAndConsoleContainsBoth(); formatterHasDateIdLevelAndParameters();
        utf8AndLineBreaksArePreservedSafely(); parentHandlersAreNotInvoked(); repeatedLookupDoesNotAddHandlers();
        recordsAreFlushedBeforeClose(); closeIsIdempotentAndDoesNotCloseConsole(); reopeningAppendsAndReleasesFileLocks();
        simultaneousSessionInSameDirectoryIsRejected(); differentDirectoriesHaveIndependentSessions(); percentInDirectoryIsLiteral();
        failedSetupReleasesEarlierHandlersAndDirectoryLock(); invalidArgumentsDoNotCreateFiles();
        concurrentCabinsWriteExactlyOncePerDestination(); controllerOwnsAndReleasesLogging(); levelsAndExceptionsRemainVisible();
        System.out.println("T41: 16 escenarios satisfactorios.");
    }
}
```

## src/test/java/com/elevatormanager/logging/LoggingTest.java

```java
package com.elevatormanager.logging;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Adaptadores JUnit para los 16 escenarios de T41. */
@Timeout(60)
class LoggingTest {
    @Test void filesAreIsolatedAndConsoleContainsBoth() throws Exception {
        LoggingScenarios.filesAreIsolatedAndConsoleContainsBoth();
    }
    @Test void formatterHasDateIdLevelAndParameters() throws Exception {
        LoggingScenarios.formatterHasDateIdLevelAndParameters();
    }
    @Test void utf8AndLineBreaksArePreservedSafely() throws Exception {
        LoggingScenarios.utf8AndLineBreaksArePreservedSafely();
    }
    @Test void parentHandlersAreNotInvoked() throws Exception {
        LoggingScenarios.parentHandlersAreNotInvoked();
    }
    @Test void repeatedLookupDoesNotAddHandlers() throws Exception {
        LoggingScenarios.repeatedLookupDoesNotAddHandlers();
    }
    @Test void recordsAreFlushedBeforeClose() throws Exception {
        LoggingScenarios.recordsAreFlushedBeforeClose();
    }
    @Test void closeIsIdempotentAndDoesNotCloseConsole() throws Exception {
        LoggingScenarios.closeIsIdempotentAndDoesNotCloseConsole();
    }
    @Test void reopeningAppendsAndReleasesFileLocks() throws Exception {
        LoggingScenarios.reopeningAppendsAndReleasesFileLocks();
    }
    @Test void simultaneousSessionInSameDirectoryIsRejected() throws Exception {
        LoggingScenarios.simultaneousSessionInSameDirectoryIsRejected();
    }
    @Test void differentDirectoriesHaveIndependentSessions() throws Exception {
        LoggingScenarios.differentDirectoriesHaveIndependentSessions();
    }
    @Test void percentInDirectoryIsLiteral() throws Exception {
        LoggingScenarios.percentInDirectoryIsLiteral();
    }
    @Test void failedSetupReleasesEarlierHandlersAndDirectoryLock() throws Exception {
        LoggingScenarios.failedSetupReleasesEarlierHandlersAndDirectoryLock();
    }
    @Test void invalidArgumentsDoNotCreateFiles() throws Exception {
        LoggingScenarios.invalidArgumentsDoNotCreateFiles();
    }
    @Test void concurrentCabinsWriteExactlyOncePerDestination() throws Exception {
        LoggingScenarios.concurrentCabinsWriteExactlyOncePerDestination();
    }
    @Test void controllerOwnsAndReleasesLogging() throws Exception {
        LoggingScenarios.controllerOwnsAndReleasesLogging();
    }
    @Test void levelsAndExceptionsRemainVisible() throws Exception {
        LoggingScenarios.levelsAndExceptionsRemainVisible();
    }
}
```

