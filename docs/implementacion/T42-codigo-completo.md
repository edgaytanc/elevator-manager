# T42 — Código completo

Los once fuentes Java nuevos o modificados se reproducen completos, sin elipsis. El ZIP conserva además todos los fuentes previos, pom.xml y documentación.

## src/main/java/com/elevatormanager/logging/LogFailure.java

```java
package com.elevatormanager.logging;

import java.time.Instant;
import java.util.Objects;

/** Primera incidencia del logging; permanece consultable aunque falle la consola. */
public record LogFailure(Instant detectedAt, String actor, String destination,
        int code, String message, Exception cause) {
    public LogFailure {
        Objects.requireNonNull(detectedAt, "detectedAt");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(destination, "destination");
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(cause, "cause");
    }
}
```

## src/main/java/com/elevatormanager/logging/TraceMailbox.java

```java
package com.elevatormanager.logging;

import com.elevatormanager.dispatch.HallCallSnapshot;
import com.elevatormanager.dispatch.ManagerWorker;
import com.elevatormanager.simulation.ExecutionEvent;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;

/** Cola adicional fiable en memoria. Sus productores solo encolan: sin I/O ni callbacks. */
public final class TraceMailbox {
    sealed interface Entry permits Cabin, Decision, CallUpdate, ManagerState, Barrier, End { }
    record Cabin(Instant at, ExecutionEvent event, Throwable failure) implements Entry { }
    record Decision(Instant at, long sequence, ManagerWorker.Result result) implements Entry { }
    record CallUpdate(Instant at, long sequence, HallCallSnapshot call) implements Entry { }
    record ManagerState(Instant at, long sequence, long epoch, String state, Throwable failure) implements Entry { }
    record Barrier(CompletableFuture<Void> completion) implements Entry { }
    record End() implements Entry { }
    private static final TraceMailbox DISABLED = new TraceMailbox(false);
    private final boolean enabled;
    private final LinkedBlockingQueue<Entry> queue = new LinkedBlockingQueue<>();
    private boolean sealed;
    private long managerSequence;

    public TraceMailbox() { this(true); }
    private TraceMailbox(boolean enabled) { this.enabled = enabled; }
    public static TraceMailbox disabled() { return DISABLED; }

    public synchronized void cabin(ExecutionEvent event, Throwable failure) {
        if (enabled) { add(new Cabin(Instant.now(), Objects.requireNonNull(event, "event"), failure)); }
    }
    public synchronized void decision(ManagerWorker.Result result) {
        if (enabled) { add(new Decision(Instant.now(), ++managerSequence, Objects.requireNonNull(result, "result"))); }
    }
    public synchronized void call(HallCallSnapshot call) {
        if (enabled) { add(new CallUpdate(Instant.now(), ++managerSequence, Objects.requireNonNull(call, "call"))); }
    }
    public synchronized void managerState(long epoch, String state, Throwable failure) {
        if (enabled) { add(new ManagerState(Instant.now(), ++managerSequence, epoch, Objects.requireNonNull(state, "state"), failure)); }
    }
    synchronized CompletableFuture<Void> barrier() {
        var future = new CompletableFuture<Void>();
        add(new Barrier(future));
        return future.copy();
    }
    synchronized void seal() {
        if (!sealed) { sealed = true; queue.add(new End()); }
    }
    Entry take() throws InterruptedException { return queue.take(); }
    private void add(Entry entry) {
        if (sealed) { throw new IllegalStateException("El canal de logging ya está cerrado."); }
        queue.add(entry);
    }
}
```

## src/main/java/com/elevatormanager/logging/SessionLog.java

```java
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
```

## src/main/java/com/elevatormanager/logging/LoggerFactory.java

```java
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
```

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
    private final String actor;

    public EventFormatter(int elevatorId) {
        if (elevatorId < 1) { throw new IllegalArgumentException("ID de elevador no positivo."); }
        this.actor = "elevador=" + elevatorId;
    }

    private EventFormatter(String actor) { this.actor = actor; }

    public static EventFormatter forManager() { return new EventFormatter("manejador"); }

    @Override public String format(LogRecord record) {
        Objects.requireNonNull(record, "record");
        String message = String.valueOf(formatMessage(record)).replace("\r", "\\r").replace("\n", "\\n");
        String text = DateTimeFormatter.ISO_INSTANT.format(record.getInstant())
                + " [" + record.getLevel().getName() + "] [" + actor + "] "
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

## src/main/java/com/elevatormanager/simulation/ElevatorWorker.java

```java
package com.elevatormanager.simulation;

import com.elevatormanager.logging.TraceMailbox;
import com.elevatormanager.model.ElevatorSnapshot;
import com.elevatormanager.model.ElevatorPhase;
import com.elevatormanager.model.ElevatorState;
import com.elevatormanager.model.SimulationConfig;
import com.elevatormanager.model.Direction;
import com.elevatormanager.model.request.Request;
import com.elevatormanager.protocol.Command;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * T37: reset prioritario por cabina, retorno físico y controles serializados.
 * No expone el estado mutable ni ejecuta callbacks de consumidores.
 */
public final class ElevatorWorker implements AutoCloseable {
    public enum Lifecycle { NEW, RUNNING, CLOSING, TERMINATED, FAILED }
    public enum Outcome { ACCEPTED, REJECTED, DUPLICATE }

    /** Aceptación en agenda, nunca confirmación de llegada o servicio. */
    public record Result(Command command, Outcome outcome, String detail,
            Optional<UUID> canonicalRequestId, Optional<CommandLedger.RequestStatus> requestStatus,
            boolean replay) {
        public Result(Command command, Outcome outcome, String detail) {
            this(command, outcome, detail, Optional.empty(), Optional.empty(), false);
        }
    }

    /** Hecho de servicio: apertura de parada o incorporación a una parada abierta. */
    public record Service(int elevatorId, long epoch, long revision, int floor,
            Direction direction, long referenceNanos, boolean openedStop, List<Request> requests) {
        public Service { requests = List.copyOf(requests); }
    }

    private final TraceMailbox trace;
    private final ElevatorState state;
    private final CommandLedger ledger;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition available = lock.newCondition();
    private final ArrayDeque<Command> inbox = new ArrayDeque<>();
    private final BlockingQueue<Result> results = new LinkedBlockingQueue<>();
    private final BlockingQueue<Service> services = new LinkedBlockingQueue<>();
    private final BlockingQueue<ExecutionEvent> events = new LinkedBlockingQueue<>();
    private final CopyOnWriteArrayList<ExecutionEventMailbox> subscribers = new CopyOnWriteArrayList<>();
    private long eventSequence;
    // Solo el actor construye y entrega este lote antes de la siguiente transición.
    private final List<ExecutionEvent> eventBatch = new ArrayList<>();
    private final AtomicReference<PlanningSnapshot> published;
    private final Thread thread;
    private volatile Lifecycle lifecycle = Lifecycle.NEW;
    private volatile Throwable failure;
    private boolean stopRequested;
    private boolean stopped;
    private boolean resumeRequested;
    private CompletableFuture<Void> stopConfirmation;
    private record ResetTicket(Command.Reset command, CompletableFuture<ElevatorSnapshot> response) { }
    private record ResetOperation(Command.Reset command, CompletableFuture<ElevatorSnapshot> completion) { }
    private final ArrayDeque<ResetTicket> resetInbox = new ArrayDeque<>();
    private ResetOperation lastReset;
    private boolean resetHeld;
    private Optional<Direction> completedSweep = Optional.empty();

    public ElevatorWorker(int id, SimulationConfig config, long epoch) {
        this(id, config, epoch, TraceMailbox.disabled());
    }

    public ElevatorWorker(int id, SimulationConfig config, long epoch, TraceMailbox trace) {
        this.trace = Objects.requireNonNull(trace, "trace");
        state = new ElevatorState(id, config, epoch);
        ledger = new CommandLedger(state);
        published = new AtomicReference<>(capture(System.nanoTime()));
        thread = new Thread(this::runLoop, "elevator-worker-" + id);
    }

    /** Solo arranca una vez. Un cierre previo también impide arrancar. */
    public boolean start() {
        lock.lock();
        try {
            if (lifecycle != Lifecycle.NEW) { return false; }
            lifecycle = Lifecycle.RUNNING;
            try {
                thread.start();
            } catch (RuntimeException | Error exception) {
                failure = exception;
                lifecycle = Lifecycle.FAILED;
                throw exception;
            }
            return true;
        } finally {
            lock.unlock();
        }
    }

    /** true solo confirma encolado; el resultado de admisión llega por separado. */
    public boolean submit(Command command) {
        Objects.requireNonNull(command, "command");
        lock.lock();
        try {
            if (lifecycle != Lifecycle.RUNNING) { return false; }
            if (command instanceof Command.Reset reset) {
                resetInbox.addLast(new ResetTicket(reset, new CompletableFuture<>()));
                available.signalAll();
                return true;
            }
            if (resetHeld || !resetInbox.isEmpty()) { return false; }
            inbox.addLast(command);
            available.signal();
            return true;
        } finally {
            lock.unlock();
        }
    }

    /** El futuro confirma piso 1/UP; aceptar el control no significa terminar el retorno. */
    public CompletableFuture<ElevatorSnapshot> requestReset(Command.Reset command) {
        Objects.requireNonNull(command, "command");
        lock.lock();
        try {
            if (lifecycle != Lifecycle.RUNNING) {
                return CompletableFuture.failedFuture(new IllegalStateException("Cabina no disponible."));
            }
            var response = new CompletableFuture<ElevatorSnapshot>();
            resetInbox.addLast(new ResetTicket(command, response));
            available.signalAll();
            return response.copy();
        } finally { lock.unlock(); }
    }

    /** Suscripción adicional sin callbacks. No reproduce eventos anteriores al registro. */
    public void subscribeEvents(ExecutionEventMailbox mailbox) {
        subscribers.addIfAbsent(Objects.requireNonNull(mailbox, "mailbox"));
    }

    /** Puede quedar un último lote ya capturado por el publicador concurrente. */
    public void unsubscribeEvents(ExecutionEventMailbox mailbox) { subscribers.remove(mailbox); }

    public enum Submission { ENQUEUED, STALE, UNAVAILABLE }

    /**
     * Compara y encola bajo el lock local. STALE/UNAVAILABLE no encolan nada.
     * No reserva el estado ni confirma admisión; el buzón previo puede contener trabajo.
     */
    public Submission submitIfCurrent(Command command, PlanningSnapshot expected) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(expected, "expected");
        lock.lock();
        try {
            if (lifecycle != Lifecycle.RUNNING) { return Submission.UNAVAILABLE; }
            if (published.get() != expected) { return Submission.STALE; }
            if (command instanceof Command.Reset reset) {
                resetInbox.addLast(new ResetTicket(reset, new CompletableFuture<>()));
                available.signalAll();
                return Submission.ENQUEUED;
            }
            if (resetHeld || !resetInbox.isEmpty()) { return Submission.UNAVAILABLE; }
            inbox.addLast(command);
            available.signal();
            return Submission.ENQUEUED;
        } finally { lock.unlock(); }
    }

    /** Control prioritario; confirma tras llegada segura y publicación de eventos. */
    public CompletableFuture<Void> requestStop() {
        lock.lock();
        try {
            if (lifecycle != Lifecycle.RUNNING || resetHeld || !resetInbox.isEmpty()) {
                return CompletableFuture.failedFuture(new IllegalStateException("Cabina no disponible para Stop."));
            }
            if (!stopRequested) {
                stopRequested = true;
                stopConfirmation = new CompletableFuture<>();
            }
            available.signalAll();
            return stopConfirmation.copy();
        } finally { lock.unlock(); }
    }

    /** Continúa el mismo actor; no crea hilos ni vuelve a admitir comandos previos. */
    public boolean resume() {
        lock.lock();
        try {
            if (lifecycle != Lifecycle.RUNNING || !stopRequested || !stopped
                    || state.isResetting() || !resetInbox.isEmpty()) { return false; }
            resetHeld = false;
            stopRequested = false;
            resumeRequested = true;
            available.signalAll();
            return true;
        } finally { lock.unlock(); }
    }

    public ElevatorSnapshot snapshot() { return published.get().elevator(); }

    /** Estado, orden de admisión y barrido capturados juntos por el actor. */
    public PlanningSnapshot planningSnapshot() { return published.get(); }

    private PlanningSnapshot capture(long now) {
        return new PlanningSnapshot(state.snapshot(now),
                state.getPendingRequestsInAdmissionOrder(), completedSweep);
    }
    public Lifecycle lifecycle() { return lifecycle; }
    public Optional<Throwable> failure() { return Optional.ofNullable(failure); }
    public String threadName() { return thread.getName(); }
    public Thread.State threadState() { return thread.getState(); }

    /** Canal de un solo consumidor lógico. No invocar con espera desde el EDT. */
    public Optional<Result> awaitResult(Duration timeout) throws InterruptedException {
        return Optional.ofNullable(results.poll(timeoutNanos(timeout), TimeUnit.NANOSECONDS));
    }

    /** Canal fiable con un consumidor lógico; no esperar desde el EDT. */
    public Optional<Service> awaitService(Duration timeout) throws InterruptedException {
        return Optional.ofNullable(services.poll(timeoutNanos(timeout), TimeUnit.NANOSECONDS));
    }

    /** Flujo completo, fiable en memoria, con un único consumidor lógico por cabina.
     * No esperar desde el EDT. No existe orden global entre cabinas.
     */
    public Optional<ExecutionEvent> awaitEvent(Duration timeout) throws InterruptedException {
        return Optional.ofNullable(events.poll(timeoutNanos(timeout), TimeUnit.NANOSECONDS));
    }

    /** Cierre local prioritario, idempotente y sin esperar al trabajador. */
    @Override
    public void close() {
        lock.lock();
        try {
            if (lifecycle == Lifecycle.NEW) {
                lifecycle = Lifecycle.TERMINATED;
            } else if (lifecycle == Lifecycle.RUNNING) {
                lifecycle = Lifecycle.CLOSING;
                available.signalAll();
            }
        } finally {
            lock.unlock();
        }
    }

    /** Espera terminación real fuera del EDT y sin conservar el lock del buzón. */
    public boolean awaitTermination(Duration timeout) throws InterruptedException {
        if (Thread.currentThread() == thread) {
            throw new IllegalStateException("Un trabajador no espera su propia terminación.");
        }
        long nanos = timeoutNanos(timeout);
        if (lifecycle == Lifecycle.NEW) { return false; }
        if (nanos > 0) { TimeUnit.NANOSECONDS.timedJoin(thread, nanos); }
        return !thread.isAlive();
    }

    private void runLoop() {
        try {
            while (true) {
                Result result = null;
                CompletableFuture<Void> stoppedAck = null;
                List<Runnable> deferred = new ArrayList<>();
                List<Service> notifications = new ArrayList<>();
                lock.lockInterruptibly();
                try {
                    if (lifecycle != Lifecycle.RUNNING) { break; }
                    long now = System.nanoTime();
                    if (!resetInbox.isEmpty()) {
                        processReset(resetInbox.removeFirst(), now, deferred);
                    } else if (state.isResetting()) {
                        if (state.getPhase() == ElevatorPhase.MOVING) {
                            if (state.completeLegIfDue(now)) {
                                published.set(capture(now));
                                emit(ExecutionEvent.Type.ARRIVED, now);
                                continueReset(now, deferred);
                            } else { available.awaitNanos(state.phaseRemainingNanos(now)); }
                        } else { continueReset(now, deferred); }
                    } else if (stopRequested) {
                        // Nunca drenar inbox ni abrir servicio después de solicitar Stop.
                        if (state.getPhase() == ElevatorPhase.MOVING) {
                            if (state.completeLegIfDue(now)) {
                                if (state.getCommittedFloor().isEmpty()) {
                                    completedSweep = Optional.of(state.getDirection());
                                }
                                published.set(capture(now));
                                emit(ExecutionEvent.Type.ARRIVED, now);
                            } else {
                                available.awaitNanos(state.phaseRemainingNanos(now));
                            }
                        }
                        if (state.getPhase() != ElevatorPhase.MOVING) {
                            if (!stopped) {
                                state.holdDwellForStop(now);
                                published.set(capture(now));
                                stopped = true;
                                emit(ExecutionEvent.Type.CONTROL_STOPPED, now);
                            }
                            if (!stopConfirmation.isDone()) { stoppedAck = stopConfirmation; }
                            else { available.await(); }
                        }
                    } else if (resumeRequested) {
                        resumeRequested = false;
                        stopped = false;
                        state.resumeAfterStop(now);
                        published.set(capture(now));
                        emit(ExecutionEvent.Type.CONTROL_RESUMED, now);
                        continueWork(now, notifications);
                    } else if (state.getPhase() == ElevatorPhase.MOVING
                            && state.completeLegIfDue(now)) {
                        if (state.getCommittedFloor().isEmpty()) {
                            completedSweep = Optional.of(state.getDirection());
                        }
                        published.set(capture(now));
                        emit(ExecutionEvent.Type.ARRIVED, now);
                        continueWork(now, notifications);
                    } else if (state.getPhase() == ElevatorPhase.DWELLING
                            && state.completeDwellIfDue(now)) {
                        published.set(capture(now));
                        emit(ExecutionEvent.Type.STOP_ENDED, now);
                        continueWork(now, notifications);
                    } else if (!inbox.isEmpty()) {
                        result = admit(inbox.removeFirst());
                        continueWork(System.nanoTime(), notifications);
                    } else if (state.getPhase() == ElevatorPhase.MOVING
                            || state.getPhase() == ElevatorPhase.DWELLING) {
                        long remaining = state.phaseRemainingNanos(System.nanoTime());
                        if (remaining > 0) { available.awaitNanos(remaining); }
                    } else {
                        available.await();
                    }
                } finally {
                    lock.unlock();
                }
                flushEvents();
                for (Runnable action : deferred) { action.run(); }
                if (stoppedAck != null) { stoppedAck.complete(null); }
                if (result != null) { results.add(result); }
                for (Service notification : notifications) { services.add(notification); }
            }
        } catch (InterruptedException exception) {
            failure = exception;
            Thread.currentThread().interrupt();
        } catch (RuntimeException | Error exception) {
            failure = exception;
        } finally {
            List<Command> abandoned;
            List<ResetTicket> abandonedResets;
            lock.lock();
            try {
                lifecycle = failure == null ? Lifecycle.TERMINATED : Lifecycle.FAILED;
                abandoned = new ArrayList<>(inbox);
                inbox.clear();
                abandonedResets = new ArrayList<>(resetInbox);
                resetInbox.clear();
            } finally {
                lock.unlock();
            }
            for (Command command : abandoned) {
                Result rejected = new Result(command, Outcome.REJECTED, "Trabajador finalizado.");
                emitAdmission(rejected, System.nanoTime());
                results.add(rejected);
            }
            for (ResetTicket ticket : abandonedResets) {
                Result rejected = new Result(ticket.command(), Outcome.REJECTED, "Cabina finalizada antes de procesar reset.");
                emitAdmission(rejected, System.nanoTime());
                results.add(rejected);
            }
            emit(failure == null ? ExecutionEvent.Type.WORKER_TERMINATED
                    : ExecutionEvent.Type.WORKER_FAILED, System.nanoTime());
            flushEvents();
            var resetFailure = new IllegalStateException("Cabina terminada antes de completar reset.", failure);
            for (ResetTicket ticket : abandonedResets) { ticket.response().completeExceptionally(resetFailure); }
            if (lastReset != null && !lastReset.completion().isDone()) {
                lastReset.completion().completeExceptionally(resetFailure);
            }
            if (stopConfirmation != null && !stopConfirmation.isDone()) {
                stopConfirmation.completeExceptionally(new IllegalStateException("Cabina terminada antes de confirmar Stop.", failure));
            }
        }
    }

    /** Admisión del control: cierre > reset > Stop > servicio. Solo escribe el actor. */
    private void processReset(ResetTicket ticket, long now, List<Runnable> deferred) {
        Command.Reset command = ticket.command();
        if (command.elevatorId() != state.getId() || !command.validateAgainst(state.getConfig()).isValid()) {
            rejectReset(ticket, "Reset dirigido a otra cabina.", now, deferred);
            return;
        }
        if (lastReset != null && lastReset.command().sameContent(command)) {
            var replay = new Result(command, Outcome.DUPLICATE, "Repetición de reset sin reiniciar retorno.",
                    Optional.empty(), Optional.empty(), true);
            emitAdmission(replay, now);
            deferred.add(() -> results.add(replay));
            var operation = lastReset;
            deferred.add(() -> operation.completion().whenComplete((snapshot, error) -> {
                if (error == null) { ticket.response().complete(snapshot); }
                else { ticket.response().completeExceptionally(error); }
            }));
            return;
        }
        if (state.isResetting() || command.epoch() <= state.getEpoch()) {
            rejectReset(ticket, "Reset en curso o época no creciente.", now, deferred);
            return;
        }
        // Cancelaciones conservan el snapshot y la correlación de la época anterior.
        for (var cancelled : ledger.clearForReset()) {
            eventBatch.add(new ExecutionEvent(++eventSequence, ExecutionEvent.Type.COMMAND_CANCELLED,
                    state.snapshot(now), Optional.of(cancelled.command()),
                    Optional.of(cancelled.canonicalRequestId()), "Cancelado por reset prioritario."));
        }
        while (!inbox.isEmpty()) {
            var rejected = new Result(inbox.removeFirst(), Outcome.REJECTED, "Descartado del inbox por reset.");
            emitAdmission(rejected, now);
            deferred.add(() -> results.add(rejected));
        }
        if (stopConfirmation != null && !stopConfirmation.isDone()) {
            var replaced = stopConfirmation;
            deferred.add(() -> replaced.completeExceptionally(new IllegalStateException("Stop sustituido por reset.")));
        }
        stopRequested = false;
        stopped = false;
        resumeRequested = false;
        resetHeld = true;
        completedSweep = Optional.empty();
        state.beginReset(command.epoch());
        lastReset = new ResetOperation(command, new CompletableFuture<>());
        var operation = lastReset;
        deferred.add(() -> operation.completion().whenComplete((snapshot, error) -> {
            if (error == null) { ticket.response().complete(snapshot); }
            else { ticket.response().completeExceptionally(error); }
        }));
        published.set(capture(now));
        var accepted = new Result(command, Outcome.ACCEPTED, "Reset adoptado; retorno pendiente.");
        emitAdmission(accepted, now);
        emitReset(ExecutionEvent.Type.RESET_STARTED, now);
        deferred.add(() -> results.add(accepted));
        continueReset(now, deferred);
    }

    private void rejectReset(ResetTicket ticket, String detail, long now, List<Runnable> deferred) {
        var rejected = new Result(ticket.command(), Outcome.REJECTED, detail);
        emitAdmission(rejected, now);
        deferred.add(() -> results.add(rejected));
        deferred.add(() -> ticket.response().completeExceptionally(new IllegalArgumentException(detail)));
    }

    private void continueReset(long now, List<Runnable> deferred) {
        if (state.getPhase() == ElevatorPhase.MOVING) { return; }
        Direction previous = state.getDirection();
        if (state.getCurrentFloor() == 1) {
            state.finishReset();
            stopRequested = true;
            stopped = true;
            stopConfirmation = CompletableFuture.completedFuture(null);
            published.set(capture(now));
            emitDirectionChange(previous, now);
            emitReset(ExecutionEvent.Type.RESET_COMPLETED, now);
            var operation = lastReset;
            var confirmation = published.get().elevator();
            deferred.add(() -> operation.completion().complete(confirmation));
        } else {
            if (state.getCommittedFloor().isPresent()) { state.beginNextLeg(now); }
            else { state.beginJourney(1, now); }
            published.set(capture(now));
            emitDirectionChange(previous, now);
            emit(ExecutionEvent.Type.DEPARTED, now);
        }
    }

    private void emitReset(ExecutionEvent.Type type, long now) {
        eventBatch.add(new ExecutionEvent(++eventSequence, type, state.snapshot(now),
                Optional.of(lastReset.command()), Optional.empty(), ""));
    }

    /** Sección local breve: sin I/O, callbacks ni bloqueos de otras cabinas. */
    private Result admit(Command command) {
        if (lastReset != null && command.epoch() == lastReset.command().epoch()
                && command.commandId() == lastReset.command().commandId()) {
            var rejected = new Result(command, Outcome.REJECTED, "ID reservado por el reset de esta época.");
            emitAdmission(rejected, System.nanoTime());
            return rejected;
        }
        CommandLedger.Decision decision = ledger.admit(command);
        published.set(capture(System.nanoTime()));
        Outcome outcome = switch (decision.kind()) {
            case ADDED -> Outcome.ACCEPTED;
            case DUPLICATE -> Outcome.DUPLICATE;
            case REJECTED -> Outcome.REJECTED;
        };
        Result result = new Result(command, outcome, decision.detail(), decision.canonicalRequestId(),
                decision.requestStatus(), decision.replay());
        long now = System.nanoTime();
        emitAdmission(result, now);
        emitCompletions(now);
        return result;
    }

    /** Transiciones locales: sin callbacks ni entrega de eventos bajo el lock. */
    private void continueWork(long now, List<Service> notifications) {
        if (state.getPhase() == ElevatorPhase.MOVING) { return; }
        if (state.getPhase() == ElevatorPhase.DWELLING) {
            List<Request> served = state.serveDuringDwell(now);
            if (!served.isEmpty()) { recordService(served, false, now, notifications); }
            return;
        }
        Direction previousDirection = state.getDirection();
        List<Request> served = state.beginDwell(state.getDirection(), now);
        if (!served.isEmpty()) {
            recordService(served, true, now, notifications);
            return;
        }
        if (state.getCommittedFloor().isPresent()) {
            completedSweep = Optional.empty();
            state.beginNextLeg(now);
        } else {
            served = state.beginDwell(state.getDirection().opposite(), now);
            if (!served.isEmpty()) {
                emitDirectionChange(previousDirection, now);
                recordService(served, true, now, notifications);
                return;
            }
            var target = DirectionalAgenda.selectTarget(state.getCurrentFloor(),
                    state.getPendingRequestsInAdmissionOrder(), completedSweep);
            if (target.isPresent()) { state.beginJourney(target.getAsInt(), now); }
            completedSweep = Optional.empty();
        }
        published.set(capture(now));
        emitDirectionChange(previousDirection, now);
        if (state.getPhase() == ElevatorPhase.MOVING) {
            emit(ExecutionEvent.Type.DEPARTED, now);
        }
    }

    private void recordService(List<Request> served, boolean openedStop, long now,
            List<Service> notifications) {
        ledger.markServed(served);
        published.set(capture(now));
        if (openedStop) { emit(ExecutionEvent.Type.STOP_STARTED, now); }
        emitCompletions(now);
        notifications.add(new Service(state.getId(), state.getEpoch(), state.getRevision(),
                state.getCurrentFloor(), state.getDirection(), now, openedStop, served));
    }

    private void emitAdmission(Result result, long now) {
        ExecutionEvent.Type type = result.replay() ? ExecutionEvent.Type.COMMAND_REPLAYED
                : switch (result.outcome()) {
                    case ACCEPTED -> ExecutionEvent.Type.COMMAND_ACCEPTED;
                    case DUPLICATE -> ExecutionEvent.Type.COMMAND_DUPLICATE;
                    case REJECTED -> ExecutionEvent.Type.COMMAND_REJECTED;
                };
        eventBatch.add(new ExecutionEvent(++eventSequence, type, state.snapshot(now),
                Optional.of(result.command()), result.canonicalRequestId(), result.detail()));
    }

    private void emitCompletions(long now) {
        for (CommandLedger.Completion completion : ledger.drainCompletions()) {
            eventBatch.add(new ExecutionEvent(++eventSequence,
                    ExecutionEvent.Type.COMMAND_COMPLETED, state.snapshot(now),
                    Optional.of(completion.command()), Optional.of(completion.canonicalRequestId()),
                    "Solicitud canónica atendida."));
        }
    }

    private void emitDirectionChange(Direction previous, long now) {
        if (previous != state.getDirection()) { emit(ExecutionEvent.Type.DIRECTION_CHANGED, now); }
    }

    private void emit(ExecutionEvent.Type type, long now) {
        eventBatch.add(new ExecutionEvent(++eventSequence, type, state.snapshot(now),
                Optional.empty(), Optional.empty(), ""));
    }

    /** Sin lock del buzón, callbacks, I/O ni espera de consumidores. */
    private void flushEvents() {
        for (ExecutionEvent event : eventBatch) {
            events.add(event);
            trace.cabin(event, failure);
            for (var subscriber : subscribers) { subscriber.publish(event); }
        }
        eventBatch.clear();
    }

    private static long timeoutNanos(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative()) { throw new IllegalArgumentException("Tiempo negativo."); }
        return timeout.toNanos();
    }
}
```

## src/main/java/com/elevatormanager/simulation/SimulationController.java

```java
package com.elevatormanager.simulation;

import com.elevatormanager.logging.SessionLog;
import com.elevatormanager.logging.TraceMailbox;
import com.elevatormanager.dispatch.HallCallSnapshot;
import com.elevatormanager.dispatch.ManagerWorker;
import com.elevatormanager.model.ElevatorSnapshot;
import com.elevatormanager.model.SimulationConfig;
import com.elevatormanager.model.SimulationState;
import com.elevatormanager.model.request.CabinRequest;
import com.elevatormanager.model.request.HallRequest;
import com.elevatormanager.model.request.Request;
import com.elevatormanager.protocol.Command;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import javax.swing.SwingUtilities;

/** T39: propietario de una sesión; serializa controles y admisiones fuera del EDT. */
public final class SimulationController implements AutoCloseable {
    public enum Outcome { APPLIED, UNCHANGED, ENQUEUED, REJECTED }

    /** ENQUEUED confirma envío, nunca admisión local ni servicio físico. */
    public record Result(Outcome outcome, SimulationState state, String detail,
            Optional<Command.Destination> command, long epoch) { }

    private final Optional<SessionLog> sessionLog;
    private final SimulationConfig config;
    private volatile long epoch;
    private final List<ElevatorWorker> elevators;
    private final ManagerWorker manager;
    private final ExecutionEventMailbox events = new ExecutionEventMailbox();
    private final AtomicLong commandIds = new AtomicLong();
    private final AtomicReference<Thread> coordinatorThread = new AtomicReference<>();
    private final ExecutorService coordinator = Executors.newSingleThreadExecutor(task -> {
        var thread = new Thread(task, "simulation-controller");
        coordinatorThread.set(thread);
        return thread;
    });
    private final AtomicBoolean closeRequested = new AtomicBoolean();
    private final AtomicBoolean shutdownStarted = new AtomicBoolean();
    private final CompletableFuture<Result> closeCompletion = new CompletableFuture<>();
    private final List<AutoCloseable> ownedResources = new java.util.ArrayList<>();
    private final Thread shutdownThread = new Thread(this::finishClose, "simulation-shutdown");
    private volatile SimulationState state = SimulationState.RESET;
    private volatile Throwable failure;
    private CompletableFuture<Result> stopCompletion;
    private CompletableFuture<Result> resetCompletion;
    private boolean workersStarted;

    public SimulationController(SimulationConfig config, long epoch) {
        this(config, epoch, Optional.empty());
    }

    /** Transfiere el logging al controlador si la construcción termina correctamente. */
    public SimulationController(SimulationConfig config, long epoch, SessionLog sessionLog) {
        this(config, epoch, Optional.of(Objects.requireNonNull(sessionLog, "sessionLog")));
    }

    private SimulationController(SimulationConfig config, long epoch, Optional<SessionLog> sessionLog) {
        this.sessionLog = sessionLog;
        this.config = Objects.requireNonNull(config, "config");
        if (epoch < 0) { throw new IllegalArgumentException("Época negativa."); }
        this.epoch = epoch;
        TraceMailbox trace = sessionLog.map(SessionLog::mailbox).orElse(TraceMailbox.disabled());
        elevators = java.util.stream.IntStream.rangeClosed(1, config.elevatorCount())
                .mapToObj(id -> new ElevatorWorker(id, config, epoch, trace)).toList();
        elevators.forEach(e -> e.subscribeEvents(events));
        manager = new ManagerWorker(config, epoch, elevators, this::nextCommandId, trace);
        sessionLog.ifPresent(log -> {
            log.claim(config.elevatorCount());
            ownedResources.add(log);
        });
    }

    public SimulationConfig config() { return config; }
    public long epoch() { return epoch; }
    public SimulationState state() { return state; }
    public Optional<Throwable> failure() {
        return failure != null ? Optional.of(failure) : sessionLog.flatMap(SessionLog::failure);
    }

    /** Copias inmutables por cabina; no representan un instante físico global único. */
    public List<ElevatorSnapshot> snapshots() {
        return elevators.stream().map(ElevatorWorker::snapshot).toList();
    }

    /** Vista de la época vigente, incluso mientras el manejador adopta el reset. */
    public Map<UUID, HallCallSnapshot> calls() {
        long currentEpoch = epoch;
        var current = new java.util.HashMap<UUID, HallCallSnapshot>();
        manager.calls().forEach((id, call) -> {
            if (call.request().getEpoch() == currentEpoch) { current.put(id, call); }
        });
        return Map.copyOf(current);
    }

    /** Observación de salud sin exponer los trabajadores ni sus canales de control. */
    public List<ElevatorWorker.Lifecycle> elevatorLifecycles() {
        return elevators.stream().map(ElevatorWorker::lifecycle).toList();
    }

    public ManagerWorker.Lifecycle managerLifecycle() { return manager.lifecycle(); }

    /** RESET arranca una vez; STOPPED continúa. Start repetido no reenvía trabajo. */
    public CompletableFuture<Result> start() {
        return schedule(() -> {
            if (state == SimulationState.RUNNING) {
                return result(Outcome.UNCHANGED, "La sesión ya está en ejecución.");
            }
            if (state == SimulationState.STOPPED || (state == SimulationState.RESET && workersStarted)) {
                try {
                    for (var elevator : elevators) {
                        if (!elevator.resume()) { throw new IllegalStateException("Cabina no reanudable."); }
                    }
                    if (!manager.resume()) { throw new IllegalStateException("Manejador no reanudable."); }
                    state = SimulationState.RUNNING;
                    return result(Outcome.APPLIED, "Continuación de trabajadores y pendientes existentes.");
                } catch (RuntimeException | Error exception) {
                    failure = exception;
                    beginClose();
                    throw exception;
                }
            }
            if (state != SimulationState.RESET) {
                return result(Outcome.REJECTED, "Start no está permitido en " + state);
            }
            try {
                startWorkersOnce();
                state = SimulationState.RUNNING;
                return result(Outcome.APPLIED, "Trabajadores iniciados una sola vez.");
            } catch (RuntimeException | Error exception) {
                failure = exception;
                beginClose();
                throw exception;
            }
        });
    }

    /** RUNNING -> STOPPING -> STOPPED; el futuro confirma toda la barrera. */
    public CompletableFuture<Result> stop() {
        return scheduleAsync(() -> {
            if (state == SimulationState.STOPPING) { return stopCompletion; }
            if (state == SimulationState.RESET || state == SimulationState.STOPPED) {
                return CompletableFuture.completedFuture(result(Outcome.UNCHANGED, "No hay avance que detener."));
            }
            if (state != SimulationState.RUNNING) {
                return CompletableFuture.completedFuture(result(Outcome.REJECTED, "Stop no permitido en " + state));
            }
            state = SimulationState.STOPPING;
            var completion = new CompletableFuture<Result>();
            stopCompletion = completion;
            var acknowledgements = new java.util.ArrayList<CompletableFuture<Void>>();
            acknowledgements.add(manager.requestStop());
            for (var elevator : elevators) { acknowledgements.add(elevator.requestStop()); }
            CompletableFuture.allOf(acknowledgements.toArray(CompletableFuture[]::new))
                    .whenComplete((ignored, error) -> schedule(() -> {
                        if (state == SimulationState.STOPPING && stopCompletion == completion) {
                            if (error != null) {
                                failure = error;
                                completion.completeExceptionally(error);
                                beginClose();
                            } else {
                                state = SimulationState.STOPPED;
                                completion.complete(result(Outcome.APPLIED, "Despacho y cabinas detenidos; pendientes conservados."));
                            }
                        }
                        return result(Outcome.UNCHANGED, "Confirmación de control procesada.");
                    }));
            return completion;
        });
    }

    /** Una época por operación; RESET se confirma solo tras limpieza y N retornos. */
    public CompletableFuture<Result> reset() {
        return scheduleAsync(() -> {
            if (state == SimulationState.RESETTING) { return resetCompletion; }
            if (state != SimulationState.RESET && state != SimulationState.RUNNING
                    && state != SimulationState.STOPPING && state != SimulationState.STOPPED) {
                return CompletableFuture.completedFuture(result(Outcome.REJECTED, "Reset no permitido en " + state));
            }
            final long targetEpoch;
            final List<Command.Reset> commands;
            try {
                targetEpoch = Math.incrementExact(epoch);
                var controls = new java.util.ArrayList<Command.Reset>();
                for (int id = 1; id <= elevators.size(); id++) {
                    controls.add(new Command.Reset(nextCommandId(), targetEpoch, id));
                }
                commands = List.copyOf(controls);
            } catch (ArithmeticException error) {
                return CompletableFuture.completedFuture(result(Outcome.REJECTED, "Época o IDs agotados; cerrar la sesión."));
            }
            var completion = new CompletableFuture<Result>();
            resetCompletion = completion;
            try {
                startWorkersOnce();
                state = SimulationState.RESETTING;
                epoch = targetEpoch;
                if (stopCompletion != null && !stopCompletion.isDone()) {
                    stopCompletion.complete(result(Outcome.REJECTED, "Stop sustituido por reset global."));
                }
                var managerAck = manager.requestReset(targetEpoch);
                var cabinAcks = new java.util.ArrayList<CompletableFuture<ElevatorSnapshot>>();
                for (int i = 0; i < elevators.size(); i++) {
                    cabinAcks.add(elevators.get(i).requestReset(commands.get(i)));
                }
                var participants = new java.util.ArrayList<CompletableFuture<?>>();
                participants.add(managerAck);
                participants.addAll(cabinAcks);
                // Un participante fallido dispara cierre; no espera a un viaje largo de otro.
                for (var participant : participants) {
                    participant.whenComplete((value, error) -> {
                        if (error != null) {
                            schedule(() -> finishReset(completion, targetEpoch, error, managerAck, cabinAcks));
                        }
                    });
                }
                CompletableFuture.allOf(participants.toArray(CompletableFuture[]::new))
                        .whenComplete((ignored, error) -> schedule(() ->
                                finishReset(completion, targetEpoch, error, managerAck, cabinAcks)));
            } catch (RuntimeException | Error error) {
                failure = error;
                completion.completeExceptionally(error);
                beginClose();
            }
            return completion;
        });
    }

    /** Solo se llama en el coordinador. Las lecturas getNow nunca esperan. */
    private Result finishReset(CompletableFuture<Result> completion, long targetEpoch, Throwable error,
            CompletableFuture<Long> managerAck, List<CompletableFuture<ElevatorSnapshot>> cabinAcks) {
        if (state != SimulationState.RESETTING || epoch != targetEpoch
                || resetCompletion != completion || completion.isDone()) {
            return result(Outcome.UNCHANGED, "Confirmación de reset fuera de vigencia.");
        }
        try {
            if (error != null) { throw new IllegalStateException("Participante de reset fallido.", error); }
            if (!Long.valueOf(targetEpoch).equals(managerAck.getNow(null)) || manager.epoch() != targetEpoch
                    || manager.lifecycle() != ManagerWorker.Lifecycle.RUNNING || !manager.calls().isEmpty()) {
                throw new IllegalStateException("El manejador no confirmó limpieza de la época vigente.");
            }
            if (cabinAcks.size() != elevators.size()) { throw new IllegalStateException("Barrera incompleta."); }
            for (int i = 0; i < cabinAcks.size(); i++) {
                var snapshot = cabinAcks.get(i).getNow(null);
                if (snapshot == null || snapshot.id() != i + 1 || snapshot.epoch() != targetEpoch
                        || snapshot.currentFloor() != 1 || snapshot.direction() != com.elevatormanager.model.Direction.UP
                        || snapshot.phase() != com.elevatormanager.model.ElevatorPhase.IDLE
                        || snapshot.mode() != ElevatorSnapshot.Mode.NORMAL || snapshot.nextFloor().isPresent()
                        || snapshot.committedFloor().isPresent() || !snapshot.cabinRequests().isEmpty()
                        || !snapshot.hallRequests().isEmpty()
                        || elevators.get(i).lifecycle() != ElevatorWorker.Lifecycle.RUNNING) {
                    throw new IllegalStateException("Cabina sin retorno físico confirmado en la época vigente.");
                }
            }
            state = SimulationState.RESET;
            completion.complete(result(Outcome.APPLIED, "Manejador limpio y todas las cabinas en 1/UP; esperando Start."));
        } catch (RuntimeException | Error exception) {
            failure = exception;
            completion.completeExceptionally(exception);
            beginClose();
        }
        return result(Outcome.UNCHANGED, "Confirmación de reset procesada.");
    }

    private void startWorkersOnce() {
        if (workersStarted) { return; }
        for (var elevator : elevators) {
            if (!elevator.start()) { throw new IllegalStateException("Cabina ya iniciada."); }
        }
        if (!manager.start()) { throw new IllegalStateException("Manejador ya iniciado."); }
        workersStarted = true;
    }

    /** Valida y enruta usando Request y su validación polimórfica. */
    public CompletableFuture<Result> submit(Request request) {
        Objects.requireNonNull(request, "request");
        return schedule(() -> {
            if (!state.acceptsManualRequests()) {
                return result(Outcome.REJECTED, "Las solicitudes requieren RUNNING: " + state);
            }
            if (request.getEpoch() != epoch) {
                return result(Outcome.REJECTED, "La solicitud pertenece a otra época.");
            }
            var validation = request.validateAgainst(config);
            if (!validation.isValid()) {
                return result(Outcome.REJECTED, validation.errors().toString());
            }
            if (request instanceof HallRequest hall) {
                return result(manager.submit(hall) ? Outcome.ENQUEUED : Outcome.REJECTED,
                        "Envío al manejador; consultar seguimiento de llamadas.");
            }
            if (request instanceof CabinRequest cabin) {
                var command = new Command.Destination(nextCommandId(), cabin.getElevatorId(), cabin);
                boolean sent = elevators.get(cabin.getElevatorId() - 1).submit(command);
                return new Result(sent ? Outcome.ENQUEUED : Outcome.REJECTED, state,
                        "Envío a cabina; la aceptación y el servicio se notifican por eventos.",
                        sent ? Optional.of(command) : Optional.empty(), epoch);
            }
            return result(Outcome.REJECTED, "Tipo de solicitud no soportado.");
        });
    }

    /** Consumidor de la época vigente. ZERO permite sondeo en EDT sin esperar. */
    public Optional<ExecutionEvent> awaitEvent(Duration timeout) throws InterruptedException {
        long budget = checkedNanos(timeout);
        long began = System.nanoTime();
        int discarded = 0;
        while (true) {
            var event = events.await(Duration.ofNanos(remaining(began, budget)));
            if (event.isEmpty() || isCurrent(event.orElseThrow())) { return event; }
            // No prolongar el plazo ni drenar un historial enorme en un sondeo EDT.
            if ((budget > 0 && remaining(began, budget) == 0)
                    || (budget == 0 && ++discarded == 256)) { return Optional.empty(); }
        }
    }

    public boolean isCurrent(Result response) {
        Objects.requireNonNull(response, "response");
        long currentEpoch = epoch;
        return response.epoch() == currentEpoch
                && response.command().map(command -> command.epoch() == currentEpoch).orElse(true);
    }

    /** Revalidar también al aplicar un evento ya extraído antes de un reset concurrente. */
    public boolean isCurrent(ExecutionEvent event) {
        Objects.requireNonNull(event, "event");
        long currentEpoch = epoch;
        return event.epoch() == currentEpoch
                && event.command().map(command -> command.epoch() == currentEpoch).orElse(true);
    }

    /** APPLIED transfiere propiedad; un recurso rechazado sigue siendo del llamador. */
    public CompletableFuture<Result> registerResource(AutoCloseable resource) {
        Objects.requireNonNull(resource, "resource");
        return schedule(() -> {
            if (ownedResources.stream().anyMatch(known -> known == resource)) {
                return result(Outcome.UNCHANGED, "Recurso ya registrado por identidad.");
            }
            ownedResources.add(resource);
            return result(Outcome.APPLIED, "Recurso incorporado al cierre de la sesión.");
        });
    }

    /** Solicitud idempotente; no espera actores ni recursos en el hilo llamador. */
    @Override public void close() { closeAsync(); }

    /** El futuro confirma liberación real. La copia visible no cancela el cierre privado. */
    public CompletableFuture<Result> closeAsync() {
        if (closeRequested.compareAndSet(false, true)) {
            try { coordinator.execute(this::beginClose); }
            catch (RejectedExecutionException exception) {
                // Un fallo del motor pudo iniciar el mismo cierre entre el CAS y execute.
                if (!shutdownStarted.get()) {
                    failure = exception;
                    closeCompletion.completeExceptionally(exception);
                }
            }
        }
        return closeCompletion.copy();
    }

    private void beginClose() {
        closeRequested.set(true);
        if (!shutdownStarted.compareAndSet(false, true)) { return; }
        state = SimulationState.CLOSING;
        if (resetCompletion != null && !resetCompletion.isDone()) {
            resetCompletion.complete(result(Outcome.REJECTED, "Reset interrumpido por cierre."));
        }
        if (stopCompletion != null && !stopCompletion.isDone()) {
            stopCompletion.complete(result(Outcome.REJECTED, "Stop interrumpido por cierre."));
        }
        manager.close();
        elevators.forEach(ElevatorWorker::close);
        coordinator.shutdown();
        try { shutdownThread.start(); }
        catch (RuntimeException | Error exception) {
            failure = exception;
            closeCompletion.completeExceptionally(exception);
        }
    }

    /** Propiedad de recursos transferida al cerrador tras terminar el coordinador. */
    private void finishClose() {
        boolean interrupted = false;
        try {
            boolean coordinatorEnded = false;
            while (!coordinatorEnded) {
                try { coordinatorEnded = coordinator.awaitTermination(1, TimeUnit.DAYS); }
                catch (InterruptedException exception) { interrupted = true; }
            }
            boolean managerEnded = false;
            while (!managerEnded) {
                try { managerEnded = manager.awaitTermination(Duration.ofDays(1)); }
                catch (InterruptedException exception) { interrupted = true; }
            }
            for (var elevator : elevators) {
                boolean ended = false;
                while (!ended) {
                    try { ended = elevator.awaitTermination(Duration.ofDays(1)); }
                    catch (InterruptedException exception) { interrupted = true; }
                }
            }
            var errors = new java.util.ArrayList<Throwable>();
            // Orden inverso de adquisición; intentar todos aunque falle uno.
            for (int i = ownedResources.size() - 1; i >= 0; i--) {
                try { ownedResources.get(i).close(); }
                catch (Exception | LinkageError exception) { errors.add(exception); }
            }
            if (!errors.isEmpty()) {
                var error = new IllegalStateException("No se confirmó la liberación de todos los recursos.");
                errors.forEach(error::addSuppressed);
                throw error;
            }
            ownedResources.clear();
            state = SimulationState.CLOSED;
            closeCompletion.complete(result(Outcome.APPLIED, "Trabajadores terminados y recursos liberados."));
        } catch (RuntimeException | Error exception) {
            if (failure != null && failure != exception) { exception.addSuppressed(failure); }
            failure = exception;
            closeCompletion.completeExceptionally(exception);
        } finally {
            if (interrupted) { Thread.currentThread().interrupt(); }
        }
    }

    /** Espera acotada de cierre real y salida del cerrador; nunca publica CLOSED por timeout. */
    public boolean awaitTermination(Duration timeout) throws InterruptedException {
        long budget = checkedNanos(timeout);
        if (budget > 0 && (Thread.currentThread() == coordinatorThread.get()
                || Thread.currentThread() == shutdownThread)) {
            throw new IllegalStateException("No esperar el cierre desde un hilo que debe terminarlo.");
        }
        if (!closeRequested.get()) { return false; }
        long began = System.nanoTime();
        try { closeCompletion.get(budget, TimeUnit.NANOSECONDS); }
        catch (TimeoutException | ExecutionException exception) { return false; }
        long remaining = remaining(began, budget);
        if (remaining > 0) { TimeUnit.NANOSECONDS.timedJoin(shutdownThread, remaining); }
        return state == SimulationState.CLOSED && coordinator.isTerminated()
                && !shutdownThread.isAlive();
    }

    private CompletableFuture<Result> schedule(Supplier<Result> operation) {
        return scheduleAsync(() -> CompletableFuture.completedFuture(operation.get()));
    }

    private CompletableFuture<Result> scheduleAsync(Supplier<CompletionStage<Result>> operation) {
        var future = new CompletableFuture<Result>();
        try {
            coordinator.execute(() -> {
                if (closeRequested.get()) {
                    future.complete(result(Outcome.REJECTED, "Cierre solicitado; acción descartada."));
                    return;
                }
                try {
                    operation.get().whenComplete((value, error) -> {
                        if (error == null) { future.complete(value); }
                        else { future.completeExceptionally(error); }
                    });
                } catch (RuntimeException | Error exception) { future.completeExceptionally(exception); }
            });
        } catch (RejectedExecutionException exception) {
            future.complete(result(Outcome.REJECTED, "La sesión se está cerrando o está cerrada."));
        }
        return future;
    }

    private Result result(Outcome outcome, String detail) {
        return new Result(outcome, state, detail, Optional.empty(), epoch);
    }

    private long nextCommandId() {
        return commandIds.updateAndGet(previous -> Math.incrementExact(previous));
    }

    private static long remaining(long began, long budget) {
        return Math.max(0L, budget - (System.nanoTime() - began));
    }

    private static long checkedNanos(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative()) { throw new IllegalArgumentException("Tiempo negativo."); }
        long nanos = timeout.toNanos();
        if (nanos > 0 && SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("No esperar desde el EDT.");
        }
        return nanos;
    }
}
```

## src/main/java/com/elevatormanager/dispatch/ManagerWorker.java

```java
package com.elevatormanager.dispatch;

import com.elevatormanager.logging.TraceMailbox;
import com.elevatormanager.model.SimulationConfig;
import com.elevatormanager.model.request.HallRequest;
import com.elevatormanager.protocol.Command;
import com.elevatormanager.simulation.ElevatorWorker;
import com.elevatormanager.simulation.ExecutionEventMailbox;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;
import javax.swing.SwingUtilities;

/**
 * T38: actor de llamadas externas con invalidación de época y barreras de control.
 * Consume una suscripción propia; mantiene intactos los canales anteriores.
 */
public final class ManagerWorker implements AutoCloseable {
    public enum Lifecycle { NEW, RUNNING, CLOSING, TERMINATED, FAILED }
    public enum Outcome { DISPATCHED, PENDING, DUPLICATE, REJECTED, CANCELLED }

    /** DISPATCHED confirma envío al buzón, nunca admisión ni servicio. */
    public record Result(HallRequest request, Outcome outcome,
            Optional<Command.Pickup> command, String detail, String processedBy) { }

    private final TraceMailbox trace;
    private final SimulationConfig config;
    private volatile long epoch;
    private final List<ElevatorWorker> elevators;
    private final LongSupplier commandIds;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition controlChanged = lock.newCondition();
    private boolean stopRequested;
    private boolean stopped;
    private CompletableFuture<Void> stopConfirmation;
    private record ResetTicket(long epoch, CompletableFuture<Long> response) { }
    private record ResetOperation(long epoch, CompletableFuture<Long> completion) { }
    private final ArrayDeque<ResetTicket> resetInbox = new ArrayDeque<>();
    private ResetOperation lastReset;
    private boolean resetHeld;
    private final ExecutionEventMailbox mailbox = new ExecutionEventMailbox();
    private HallRequestLedger ledger;
    private final BlockingQueue<HallCallSnapshot> updates = new LinkedBlockingQueue<>();
    private final AtomicReference<Map<UUID, HallCallSnapshot>> published = new AtomicReference<>(Map.of());
    private static final long RETRY_NANOS = TimeUnit.MILLISECONDS.toNanos(100);
    private int retryCursor;
    private final ArrayDeque<HallRequest> inbox = new ArrayDeque<>();
    private final BlockingQueue<Result> results = new LinkedBlockingQueue<>();
    private final Thread thread;
    private volatile Lifecycle lifecycle = Lifecycle.NEW;
    private volatile Throwable failure;
    private static final int MAX_SELECTION_ATTEMPTS = 3;
    private final ElevatorSelector selector = new ElevatorSelector();

    /**
     * Cabinas prestadas: este actor no las arranca ni las cierra.
     * commandIds debe ser rápido, seguro entre hilos y producir IDs positivos
     * únicos, compartidos con TODO productor de comandos de estas cabinas.
     * No reutilizar un generador nuevo sobre cabinas con historial previo.
     */
    public ManagerWorker(SimulationConfig config, long epoch,
            List<ElevatorWorker> elevators, LongSupplier commandIds) {
        this(config, epoch, elevators, commandIds, TraceMailbox.disabled());
    }

    public ManagerWorker(SimulationConfig config, long epoch,
            List<ElevatorWorker> elevators, LongSupplier commandIds, TraceMailbox trace) {
        this.trace = Objects.requireNonNull(trace, "trace");
        this.config = Objects.requireNonNull(config, "config");
        if (epoch < 0) { throw new IllegalArgumentException("Época negativa."); }
        this.epoch = epoch;
        ledger = new HallRequestLedger(config, epoch);
        this.commandIds = Objects.requireNonNull(commandIds, "commandIds");
        this.elevators = List.copyOf(elevators).stream()
                .sorted(Comparator.comparingInt(e -> e.snapshot().id())).toList();
        if (this.elevators.size() != config.elevatorCount()) {
            throw new IllegalArgumentException("Se requiere una cabina por ID configurado.");
        }
        for (int i = 0; i < this.elevators.size(); i++) {
            var snapshot = this.elevators.get(i).snapshot();
            if (snapshot.id() != i + 1 || !snapshot.config().equals(config)
                    || snapshot.epoch() != epoch) {
                throw new IllegalArgumentException("IDs, configuración o época incompatibles.");
            }
        }
        thread = new Thread(this::runLoop, "manager-worker-" + epoch);
    }

    public boolean start() {
        lock.lock();
        try {
            if (lifecycle != Lifecycle.NEW) { return false; }
            lifecycle = Lifecycle.RUNNING;
            try {
                for (var elevator : elevators) { elevator.subscribeEvents(mailbox); }
                thread.start();
            }
            catch (RuntimeException | Error exception) {
                for (var elevator : elevators) { elevator.unsubscribeEvents(mailbox); }
                failure = exception;
                lifecycle = Lifecycle.FAILED;
                throw exception;
            }
            return true;
        } finally { lock.unlock(); }
    }

    /** Apto para EDT: solo encola; validación y despacho ocurren en el actor. */
    public boolean submit(HallRequest request) {
        Objects.requireNonNull(request, "request");
        lock.lock();
        try {
            if (lifecycle != Lifecycle.RUNNING || resetHeld || !resetInbox.isEmpty()) { return false; }
            inbox.addLast(request);
            mailbox.wake();
            return true;
        } finally { lock.unlock(); }
    }

    /** Confirma adopción de época y limpieza; mantiene detenido el despacho hasta resume. */
    public CompletableFuture<Long> requestReset(long newEpoch) {
        lock.lock();
        try {
            if (lifecycle != Lifecycle.RUNNING) {
                return CompletableFuture.failedFuture(new IllegalStateException("Manejador no disponible."));
            }
            var response = new CompletableFuture<Long>();
            resetInbox.addLast(new ResetTicket(newEpoch, response));
            controlChanged.signalAll();
            mailbox.wake();
            return response.copy();
        } finally { lock.unlock(); }
    }

    public long epoch() { return epoch; }

    /** Solo el actor reemplaza su registro. Completa futuros después de soltar el lock. */
    private void processReset(ResetTicket ticket) {
        if (lastReset != null && ticket.epoch() == lastReset.epoch()) {
            lastReset.completion().whenComplete((value, error) -> {
                if (error == null) { ticket.response().complete(value); }
                else { ticket.response().completeExceptionally(error); }
            });
            return;
        }
        if (ticket.epoch() <= epoch) {
            ticket.response().completeExceptionally(new IllegalArgumentException("Época de reset no creciente."));
            return;
        }
        List<HallRequest> abandoned;
        CompletableFuture<Void> replaced;
        lock.lock();
        try {
            if (lifecycle != Lifecycle.RUNNING) {
                // El resultado se completa fuera de esta sección.
                abandoned = null;
                replaced = null;
            } else {
                abandoned = List.copyOf(inbox);
                inbox.clear();
                replaced = stopConfirmation;
                resetHeld = true;
                stopRequested = true;
                stopped = true;
                stopConfirmation = CompletableFuture.completedFuture(null);
            }
        } finally { lock.unlock(); }
        if (abandoned == null) {
            ticket.response().completeExceptionally(new IllegalStateException("Cierre interrumpió reset del manejador."));
            return;
        }
        ledger.cancelForReset();
        publish();
        for (var request : abandoned) {
            results.add(result(request, Outcome.CANCELLED, Optional.empty(), "Entrada invalidada por reset global."));
        }
        ledger = new HallRequestLedger(config, ticket.epoch());
        epoch = ticket.epoch();
        retryCursor = 0;
        publish();
        lastReset = new ResetOperation(epoch, new CompletableFuture<>());
        if (replaced != null && !replaced.isDone()) {
            replaced.completeExceptionally(new IllegalStateException("Stop del manejador sustituido por reset."));
        }
        trace.managerState(epoch, "MANAGER_RESET", null);
        lastReset.completion().complete(epoch);
        ticket.response().complete(epoch);
    }

    /** Confirma al salir del despacho en vuelo; conserva inbox, ledger y eventos. */
    public CompletableFuture<Void> requestStop() {
        lock.lock();
        try {
            if (lifecycle != Lifecycle.RUNNING || resetHeld || !resetInbox.isEmpty()) {
                return CompletableFuture.failedFuture(new IllegalStateException("Manejador no disponible para Stop."));
            }
            if (!stopRequested) {
                stopRequested = true;
                stopConfirmation = new CompletableFuture<>();
            }
            mailbox.wake();
            return stopConfirmation.copy();
        } finally { lock.unlock(); }
    }

    public boolean resume() {
        lock.lock();
        try {
            if (lifecycle != Lifecycle.RUNNING || !stopRequested || !stopped
                    || !resetInbox.isEmpty()) { return false; }
            resetHeld = false;
            stopRequested = false;
            stopped = false;
            controlChanged.signalAll();
            return true;
        } finally { lock.unlock(); }
    }

    /** Sin callbacks ni esperas externas bajo el lock local. */
    private boolean awaitDispatchPermission() throws InterruptedException {
        while (true) {
            CompletableFuture<Void> ack = null;
            ResetTicket reset = null;
            lock.lockInterruptibly();
            try {
                if (lifecycle != Lifecycle.RUNNING) { return false; }
                if (!resetInbox.isEmpty()) { reset = resetInbox.removeFirst(); }
                else if (!stopRequested) { return true; }
                else { stopped = true; ack = stopConfirmation; }
            } finally { lock.unlock(); }
            if (reset != null) { processReset(reset); continue; }
            ack.complete(null);
            lock.lockInterruptibly();
            try {
                if (stopRequested && stopConfirmation == ack && resetInbox.isEmpty()
                        && lifecycle == Lifecycle.RUNNING) { controlChanged.await(); }
            } finally { lock.unlock(); }
        }
    }

    public Lifecycle lifecycle() { return lifecycle; }
    public Optional<Throwable> failure() { return Optional.ofNullable(failure); }
    public String threadName() { return thread.getName(); }
    public Thread.State threadState() { return thread.getState(); }

    /** Un consumidor lógico. ZERO permite sondeo desde Swing sin esperar. */
    public Optional<Result> awaitResult(Duration timeout) throws InterruptedException {
        long nanos = timeoutNanos(timeout);
        rejectBlockingOnEdt(nanos);
        return Optional.ofNullable(results.poll(nanos, TimeUnit.NANOSECONDS));
    }

    /**
     * Cierre local sin join. El despacho ya extraído puede terminar; los demás
     * se cancelan explícitamente. No equivale a Stop o Reset del simulador.
     */
    @Override public void close() {
        lock.lock();
        try {
            if (lifecycle == Lifecycle.NEW) { lifecycle = Lifecycle.TERMINATED; }
            else if (lifecycle == Lifecycle.RUNNING) {
                lifecycle = Lifecycle.CLOSING;
                controlChanged.signalAll();
                mailbox.wake();
            }
        } finally { lock.unlock(); }
    }

    public boolean awaitTermination(Duration timeout) throws InterruptedException {
        long nanos = timeoutNanos(timeout);
        rejectBlockingOnEdt(nanos);
        if (Thread.currentThread() == thread) {
            throw new IllegalStateException("El manejador no espera su propia terminación.");
        }
        if (lifecycle == Lifecycle.NEW) { return false; }
        if (nanos > 0) { TimeUnit.NANOSECONDS.timedJoin(thread, nanos); }
        return !thread.isAlive();
    }

    /** Historial global inmutable; también conserva identidades alias y atendidas. */
    public Map<UUID, HallCallSnapshot> calls() { return published.get(); }
    public Optional<HallCallSnapshot> call(UUID id) { return Optional.ofNullable(published.get().get(id)); }

    /** Canal de cambios, independiente del resultado único de cada submit. */
    public Optional<HallCallSnapshot> awaitUpdate(Duration timeout) throws InterruptedException {
        long nanos = timeoutNanos(timeout);
        rejectBlockingOnEdt(nanos);
        return Optional.ofNullable(updates.poll(nanos, TimeUnit.NANOSECONDS));
    }

    private void runLoop() {
        HallRequest inFlight = null;
        long lastRetry = System.nanoTime();
        try {
            trace.managerState(epoch, "MANAGER_STARTED", null);
            while (lifecycle == Lifecycle.RUNNING) {
                if (!awaitDispatchPermission()) { break; }
                boolean hadEvents = false;
                for (int i = 0; i < 256; i++) {
                    var event = mailbox.await(Duration.ZERO);
                    if (event.isEmpty()) { break; }
                    ledger.onEvent(event.orElseThrow()); publish(); hadEvents = true;
                }
                long now = System.nanoTime();
                if (hadEvents || now - lastRetry >= RETRY_NANOS) {
                    var pending = ledger.pending();
                    int count = Math.min(16, pending.size());
                    for (int i = 0; i < count && lifecycle == Lifecycle.RUNNING; i++) {
                        var request = pending.get((int) (((long) retryCursor + i) % pending.size()));
                        dispatch(request); publish();
                    }
                    if (!pending.isEmpty()) { retryCursor = (retryCursor + count) % pending.size(); }
                    lastRetry = System.nanoTime();
                }
                lock.lockInterruptibly();
                try {
                    if (lifecycle != Lifecycle.RUNNING) { break; }
                    inFlight = inbox.pollFirst();
                } finally { lock.unlock(); }
                if (inFlight != null) {
                    var admission = ledger.receive(inFlight);
                    publish();
                    Result result;
                    if (admission.kind() == HallRequestLedger.AdmissionKind.REJECTED) {
                        result = result(inFlight, Outcome.REJECTED, Optional.empty(), admission.detail());
                    } else if (admission.kind() == HallRequestLedger.AdmissionKind.DUPLICATE) {
                        var known = ledger.get(inFlight.getId()).orElseThrow();
                        result = result(inFlight, Outcome.DUPLICATE, known.command(),
                                admission.detail() + " Canónica=" + known.canonicalRequestId());
                    } else {
                        result = dispatch(inFlight);
                    }
                    publish(); results.add(result); inFlight = null;
                    // Una entrada por vuelta: los eventos tienen oportunidad de avanzar.
                    continue;
                }
                // Un wake de Stop/cierre pudo consumirse al drenar eventos.
                // Revalidar antes de volver a dormir evita perder ese control.
                // Si llega después de esta lectura, su wake permanece en la cola.
                lock.lockInterruptibly();
                try {
                    if (stopRequested || !resetInbox.isEmpty() || lifecycle != Lifecycle.RUNNING) { continue; }
                } finally { lock.unlock(); }
                // Los eventos despiertan por cola; el temporizador solo reintenta recibidas.
                var event = ledger.pending().isEmpty()
                        ? mailbox.await() : mailbox.await(Duration.ofNanos(RETRY_NANOS));
                if (event.isPresent()) { ledger.onEvent(event.orElseThrow()); publish(); }
            }
        } catch (InterruptedException exception) {
            failure = exception; Thread.currentThread().interrupt();
        } catch (RuntimeException | Error exception) {
            failure = exception;
        } finally {
            for (var elevator : elevators) { elevator.unsubscribeEvents(mailbox); }
            if (inFlight != null) {
                results.add(result(inFlight, Outcome.CANCELLED, Optional.empty(),
                        "Fallo durante el procesamiento; revisar el historial publicado."));
            }
            ledger.close(); publish();
            List<ResetTicket> abandonedResets;
            lock.lock();
            try {
                abandonedResets = List.copyOf(resetInbox);
                resetInbox.clear();
                while (!inbox.isEmpty()) {
                    results.add(result(inbox.removeFirst(), Outcome.CANCELLED, Optional.empty(),
                            "Manejador cerrado antes de procesar la entrada."));
                }
                lifecycle = failure == null ? Lifecycle.TERMINATED : Lifecycle.FAILED;
            } finally { lock.unlock(); }
            trace.managerState(epoch, failure == null ? "MANAGER_TERMINATED" : "MANAGER_FAILED", failure);
            var error = new IllegalStateException("Manejador terminó antes de adoptar reset.", failure);
            for (var reset : abandonedResets) { reset.response().completeExceptionally(error); }
            if (stopConfirmation != null && !stopConfirmation.isDone()) {
                stopConfirmation.completeExceptionally(new IllegalStateException("Manejador terminado antes de confirmar Stop.", failure));
            }
        }
    }

    private void publish() {
        var next = ledger.snapshot();
        var previous = published.getAndSet(next);
        for (var entry : next.entrySet()) {
            if (!entry.getValue().equals(previous.get(entry.getKey()))) {
                updates.add(entry.getValue()); trace.call(entry.getValue());
            }
        }
    }

    private List<ElevatorSelector.Candidate> eligibleCandidates(HallRequest request,
            List<ElevatorSelector.Candidate> candidates) {
        var rejected = ledger.rejectedCabins(request.getId());
        var awaiting = ledger.awaitingAdmissionCabins();
        return candidates.stream().filter(c -> !rejected.contains(c.planning().elevator().id())
                && !awaiting.contains(c.planning().elevator().id())).toList();
    }

    private Result pending(HallRequest request, String detail) {
        ledger.pendingReason(request.getId(), detail);
        return result(request, Outcome.PENDING, Optional.empty(), detail);
    }

    private Result dispatch(HallRequest request) {
        if (request.getEpoch() != epoch) {
            return result(request, Outcome.REJECTED, Optional.empty(), "Época ajena.");
        }
        var validation = request.validateAgainst(config);
        if (!validation.isValid()) {
            return result(request, Outcome.REJECTED, Optional.empty(), validation.errors().toString());
        }
        var initial = captureCandidates();
        if (selector.rank(config, request, eligibleCandidates(request, initial), System.nanoTime()).choices().isEmpty()) {
            return pending(request, "Conservada: no hay cabinas disponibles, confirmadas o estimables para despachar.");
        }
        long commandId = commandIds.getAsLong();
        if (commandId < 1) {
            throw new IllegalStateException("Generador de comandos agotado o inválido.");
        }
        // El generador ya terminó: todos los costos siguientes usan un instante nuevo.
        for (int attempt = 0; attempt < MAX_SELECTION_ATTEMPTS; attempt++) {
            var candidates = captureCandidates();
            var ranking = selector.rank(config, request, eligibleCandidates(request, candidates), System.nanoTime());
            if (ranking.choices().isEmpty()) {
                return pending(request, "Conservada: no hay cabinas disponibles, confirmadas o estimables para despachar.");
            }
            var selected = ranking.choices().get(0);
            // Revalidar TODAS: también puede mejorar una cabina antes descartada.
            if (!areCurrent(candidates)) { continue; }
            var elevator = elevators.get(selected.elevatorId() - 1);
            var command = new Command.Pickup(commandId, selected.elevatorId(), request);
            var submitted = elevator.submitIfCurrent(command, selected.candidate().planning());
            if (submitted == ElevatorWorker.Submission.ENQUEUED) {
                ledger.dispatched(request.getId(), command);
                return result(request, Outcome.DISPATCHED, Optional.of(command),
                        "Enviado por menor ETA=" + selected.estimate().pickupNanos().orElseThrow()
                        + " ns; desempate por ID ascendente. Admisión y servicio pendientes de confirmación.");
            }
            // No se envió: volver a estimar todas las opciones, no usar un ranking viejo.
        }
        return pending(request, "Conservada: tres intentos sin encolar por cambios de estado; se reintentará.");
    }

    private List<ElevatorSelector.Candidate> captureCandidates() {
        return elevators.stream().map(e -> new ElevatorSelector.Candidate(
                e.planningSnapshot(), e.lifecycle())).toList();
    }

    private boolean areCurrent(List<ElevatorSelector.Candidate> candidates) {
        for (int i = 0; i < elevators.size(); i++) {
            var elevator = elevators.get(i);
            var candidate = candidates.get(i);
            if (elevator.lifecycle() != candidate.lifecycle()
                    || elevator.planningSnapshot() != candidate.planning()) { return false; }
        }
        return true;
    }

    private Result result(HallRequest request, Outcome outcome,
            Optional<Command.Pickup> command, String detail) {
        var result = new Result(request, outcome, command, detail, Thread.currentThread().getName());
        trace.decision(result);
        return result;
    }

    private static long timeoutNanos(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative()) { throw new IllegalArgumentException("Tiempo negativo."); }
        return timeout.toNanos();
    }

    private static void rejectBlockingOnEdt(long nanos) {
        if (nanos > 0 && SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("No esperar desde el EDT.");
        }
    }
}
```

## src/main/java/com/elevatormanager/diagnostics/EventLoggingDemo.java

```java
package com.elevatormanager.diagnostics;

import com.elevatormanager.dispatch.HallCallSnapshot;
import com.elevatormanager.logging.SessionLog;
import com.elevatormanager.model.Direction;
import com.elevatormanager.model.SimulationConfig;
import com.elevatormanager.model.request.CabinRequest;
import com.elevatormanager.model.request.HallRequest;
import com.elevatormanager.simulation.ExecutionEvent;
import com.elevatormanager.simulation.SimulationController;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

/** T42: muestra con dos actores de cabina, manejador real y logging en su propio hilo. */
public final class EventLoggingDemo {
    private EventLoggingDemo() { }

    public static void main(String[] args) throws Exception {
        if (args.length > 1) { throw new IllegalArgumentException("Uso: EventLoggingDemo [directorio]"); }
        Path directory = Path.of(args.length == 0 ? "logs/t42" : args[0]);
        var config = new SimulationConfig(2, 6, 60, 30);
        try (var log = SessionLog.open(directory, config.elevatorCount())) {
            var controller = new SimulationController(config, 0, log);
            try {
                requireEnqueued(controller.start().get(10, TimeUnit.SECONDS), SimulationController.Outcome.APPLIED);
                requireEnqueued(controller.submit(cabin(4)).get(10, TimeUnit.SECONDS), SimulationController.Outcome.ENQUEUED);
                until(controller, ExecutionEvent.Type.STOP_ENDED, 1, 4);
                requireEnqueued(controller.submit(cabin(2)).get(10, TimeUnit.SECONDS), SimulationController.Outcome.ENQUEUED);
                until(controller, ExecutionEvent.Type.STOP_ENDED, 1, 2);
                var hall = new HallRequest(UUID.randomUUID(), 0, 3, Instant.now(), Direction.UP);
                requireEnqueued(controller.submit(hall).get(10, TimeUnit.SECONDS), SimulationController.Outcome.ENQUEUED);
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                while (true) {
                    var call = controller.calls().get(hall.getId());
                    if (call != null && call.status() == HallCallSnapshot.Status.SERVED) { break; }
                    if (System.nanoTime() >= deadline) { throw new IllegalStateException("Llamada no confirmada."); }
                    LockSupport.parkNanos(1_000_000);
                }
                requireEnqueued(controller.reset().get(10, TimeUnit.SECONDS), SimulationController.Outcome.APPLIED);
            } finally {
                controller.closeAsync().get(15, TimeUnit.SECONDS);
                if (!controller.awaitTermination(Duration.ofSeconds(10))) {
                    throw new IllegalStateException("Cierre no confirmado.");
                }
            }
        }
        System.out.println("T42: muestra terminada; archivos en " + directory.toAbsolutePath().normalize());
    }

    private static CabinRequest cabin(int floor) {
        return new CabinRequest(UUID.randomUUID(), 0, floor, Instant.now(), 1);
    }

    private static void until(SimulationController controller, ExecutionEvent.Type type, int id, int floor)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (true) {
            long left = deadline - System.nanoTime();
            if (left <= 0) { throw new IllegalStateException("Evento no observado: " + type); }
            var event = controller.awaitEvent(Duration.ofNanos(left)).orElseThrow();
            if (event.type() == type && event.elevatorId() == id && event.snapshot().currentFloor() == floor) { return; }
        }
    }

    private static void requireEnqueued(SimulationController.Result result, SimulationController.Outcome expected) {
        if (result.outcome() != expected) { throw new IllegalStateException(result.detail()); }
    }
}
```

## src/test/java/com/elevatormanager/logging/SessionLoggingScenarios.java

```java
package com.elevatormanager.logging;

import com.elevatormanager.dispatch.HallCallSnapshot;
import com.elevatormanager.dispatch.ManagerWorker;
import com.elevatormanager.model.Direction;
import com.elevatormanager.model.ElevatorState;
import com.elevatormanager.model.SimulationConfig;
import com.elevatormanager.model.SimulationState;
import com.elevatormanager.model.request.CabinRequest;
import com.elevatormanager.model.request.HallRequest;
import com.elevatormanager.simulation.ElevatorWorker;
import com.elevatormanager.simulation.ExecutionEvent;
import com.elevatormanager.simulation.SimulationController;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import java.util.logging.ErrorManager;
import java.util.logging.FileHandler;
import java.util.logging.Level;
import java.util.logging.SimpleFormatter;
import java.util.logging.StreamHandler;
import javax.swing.SwingUtilities;
import static com.elevatormanager.simulation.ExecutionEvent.Type.*;

/** T42: eventos reales, drenaje, errores de handlers y regresión de canales independientes. */
public final class SessionLoggingScenarios {
    private SessionLoggingScenarios() { }

    public static void obligatoryEventsComeFromPhysicalActors() throws Exception {
        inDirectory(dir -> {
            try (var rig = new Rig(dir, 1, 5)) {
                get(rig.controller.start());
                get(rig.controller.submit(cabin(0, 4, 1))); rig.until(STOP_ENDED, 4);
                get(rig.controller.submit(cabin(0, 2, 1))); rig.until(STOP_ENDED, 2);
                get(rig.controller.reset()); get(rig.log.flushAsync());
            }
            String text = read(dir, 1);
            for (String action : new String[] { "SUBIENDO", "BAJANDO", "LLEGADA", "PARADA_RECOGER_DEJAR",
                    "CAMBIO_DIRECCION", "RESET_INICIADO", "RESET_COMPLETADO" }) {
                check(text.contains("accion=" + action + ";"), "Obligatorio: " + action);
            }
            check(text.contains("piso=1; siguiente=2; direccion=UP"), "Partida conserva piso lógico");
            check(text.contains("modo=RESET_RETURN"), "Retorno físico identificado");
            check(readManager(dir).contains("evento=MANAGER_RESET"), "Reset de manejador");
        });
    }

    public static void realCabinsRemainIsolatedAndConsoleHasOneCopy() throws Exception {
        inDirectory(dir -> {
            ByteArrayOutputStream console;
            try (var rig = new Rig(dir, 3, 5)) {
                console = rig.console; get(rig.controller.start());
                for (int id = 1; id <= 3; id++) { get(rig.controller.submit(cabin(0, id + 1, id))); }
                var served = new HashSet<Integer>();
                long deadline = deadline();
                while (served.size() < 3) {
                    var event = rig.next(deadline);
                    if (event.type() == COMMAND_COMPLETED) { served.add(event.elevatorId()); }
                }
            }
            String terminal = console.toString(StandardCharsets.UTF_8);
            for (int id = 1; id <= 3; id++) {
                for (String line : read(dir, id).lines().toList()) {
                    check(line.contains("[elevador=" + id + "]"), "Solo su cabina");
                    check(terminal.lines().filter(line::equals).count() == 1, "Una copia en consola");
                }
            }
            for (String line : readManager(dir).lines().toList()) {
                check(line.contains("[manejador]"), "Archivo exclusivo del manejador");
                check(terminal.lines().filter(line::equals).count() == 1, "Una copia de manejador");
            }
        });
    }

    public static void dispatchAdmissionAndServiceAreDifferentFacts() throws Exception {
        inDirectory(dir -> {
            var request = hall(0, 3, Direction.DOWN);
            try (var rig = new Rig(dir, 1, 10)) {
                get(rig.controller.start()); get(rig.controller.submit(request));
                rig.until(COMMAND_COMPLETED, 3);
                await(() -> rig.controller.calls().get(request.getId()).status() == HallCallSnapshot.Status.SERVED);
            }
            String manager = readManager(dir);
            check(manager.contains("resultado=DISPATCHED") && manager.contains("menor ETA="), "Decisión y motivo");
            check(manager.contains("estado=DISPATCHED") && manager.contains("estado=ASSIGNED")
                    && manager.contains("estado=SERVED"), "Tres hechos diferentes");
            check(manager.contains("solicitud=" + request.getId()), "UUID externo");
            check(read(dir, 1).contains("canonica=" + request.getId()), "Correlación cabina/manejador");
        });
    }

    public static void duplicateHallRequestKeepsCanonicalIdentity() throws Exception {
        inDirectory(dir -> {
            var original = hall(0, 4, Direction.UP); var duplicate = hall(0, 4, Direction.UP);
            try (var rig = new Rig(dir, 1, 100)) {
                get(rig.controller.start()); get(rig.controller.submit(original)); rig.until(DEPARTED, 1);
                get(rig.controller.submit(duplicate)); rig.until(COMMAND_COMPLETED, 4);
                await(() -> rig.controller.calls().get(duplicate.getId()) != null
                        && rig.controller.calls().get(duplicate.getId()).status() == HallCallSnapshot.Status.SERVED);
            }
            String manager = readManager(dir);
            check(manager.contains("resultado=DUPLICATE"), "Decisión duplicada explícita");
            check(manager.contains("solicitud=" + duplicate.getId() + "; canonica=" + original.getId()), "Alias preservado");
            check(read(dir, 1).lines().filter(line -> line.contains("evento=COMMAND_COMPLETED;")).count() == 1, "Un servicio canónico");
        });
    }

    public static void resetKeepsOldHistoryWithoutCompletingCancelledWork() throws Exception {
        inDirectory(dir -> {
            var request = cabin(0, 5, 1);
            try (var rig = new Rig(dir, 1, 100)) {
                get(rig.controller.start()); get(rig.controller.submit(request)); rig.until(DEPARTED, 1);
                get(rig.controller.reset());
            }
            String text = read(dir, 1);
            check(text.contains("epoca=0;") && text.contains("epoca=1;"), "No filtra historia por época vigente");
            check(text.contains("evento=COMMAND_CANCELLED;"), "Cancelación registrada");
            check(text.lines().noneMatch(line -> line.contains("evento=COMMAND_COMPLETED;")
                    && line.contains("canonica=" + request.getId())), "Cancelado no es servido");
        });
    }

    public static void sequencesRemainUniqueAcrossResets() throws Exception {
        inDirectory(dir -> {
            try (var rig = new Rig(dir, 1, 5)) {
                get(rig.controller.start());
                for (int epoch = 0; epoch < 3; epoch++) {
                    get(rig.controller.submit(cabin(epoch, 2, 1))); rig.until(STOP_ENDED, 2);
                    get(rig.controller.reset()); get(rig.controller.start());
                }
            }
            long previous = 0;
            for (String line : read(dir, 1).lines().toList()) {
                long sequence = Long.parseLong(line.split("secuencia=")[1].split(";")[0]);
                check(sequence == previous + 1, "Secuencia continua, sin duplicados ni pérdidas"); previous = sequence;
            }
            check(previous > 20 && read(dir, 1).contains("epoca=3;"), "Tres resets registrados");
        });
    }

    public static void closingInFlightRecordsTerminationWithoutInventingArrival() throws Exception {
        inDirectory(dir -> {
            try (var rig = new Rig(dir, 1, 60_000)) {
                get(rig.controller.start()); get(rig.controller.submit(cabin(0, 5, 1))); rig.until(DEPARTED, 1);
            }
            String text = read(dir, 1);
            check(text.contains("evento=WORKER_TERMINATED;"), "Drena último evento del actor");
            check(!text.contains("evento=ARRIVED;"), "No inventa llegada al cancelar viaje largo");
            check(readManager(dir).contains("evento=MANAGER_TERMINATED;"), "Drena final del manejador");
        });
    }

    public static void closingDrainsEveryQueuedEvent() throws Exception {
        inDirectory(dir -> {
            var log = SessionLog.open(dir, 1, new ByteArrayOutputStream());
            for (int i = 1; i <= 400; i++) { log.mailbox().cabin(sample(i, 1, 1, ARRIVED), null); }
            log.close();
            check(read(dir, 1).lines().count() == 400, "Drenaje FIFO completo");
            check(!log.isAlive(), "Consumidor terminado");
        });
    }

    public static void appendDistinguishesSessions() throws Exception {
        inDirectory(dir -> {
            String first;
            try (var rig = new Rig(dir, 1, 5)) { first = rig.log.sessionId(); get(rig.controller.start()); }
            try (var rig = new Rig(dir, 1, 5)) { check(!first.equals(rig.log.sessionId()), "Sesión distinta"); get(rig.controller.start()); }
            check(read(dir, 1).lines().map(line -> line.split("sesion=")[1].split(";")[0]).distinct().count() == 2,
                    "Historial append distingue secuencias reiniciadas");
        });
    }

    public static void barrierMakesPriorWritesVisible() throws Exception {
        inDirectory(dir -> {
            try (var log = SessionLog.open(dir, 1, new ByteArrayOutputStream())) {
                log.mailbox().cabin(sample(1, 1, 1, ARRIVED), null); get(log.flushAsync());
                check(read(dir, 1).contains("evento=ARRIVED;"), "Barrera después de publicación");
                check(log.isAlive(), "Consumidor permanece disponible");
            }
        });
    }

    public static void consumerFailureIsVisibleAndCleanupStillRuns() throws Exception {
        inDirectory(dir -> {
            var log = SessionLog.open(dir, 1, new ByteArrayOutputStream());
            log.mailbox().cabin(sample(1, 2, 2, ARRIVED), null);
            expect(ExecutionException.class, () -> get(log.flushAsync()));
            check(log.failure().isPresent(), "Error de enrutamiento visible");
            expect(IllegalStateException.class, log::close);
            check(!log.isAlive(), "Consumidor terminó tras fallo");
            try (var reopened = SessionLog.open(dir, 1, new ByteArrayOutputStream())) { get(reopened.flushAsync()); }
        });
    }

    public static void consoleWriteFailureIsReportedOutOfBand() throws Exception {
        inDirectory(dir -> {
            var emergency = new ByteArrayOutputStream(); PrintStream previous = System.err;
            var log = SessionLog.open(dir, 1, brokenWrite());
            try (var capture = new PrintStream(emergency, true, StandardCharsets.UTF_8)) {
                System.setErr(capture);
                log.mailbox().cabin(sample(1, 1, 1, ARRIVED), null);
                expect(ExecutionException.class, () -> get(log.flushAsync()));
                check(log.ioFailure().orElseThrow().destination().equals("consola"), "Destino fallido identificado");
                check(emergency.toString(StandardCharsets.UTF_8).contains("ERROR_LOGGING"), "Aviso de emergencia independiente");
                check(read(dir, 1).contains("evento=ARRIVED;"), "Archivo sano sigue escribiendo");
                expect(IllegalStateException.class, log::close);
            } finally { System.setErr(previous); }
            try (var swallowed = new PrintStream(brokenWrite(), true, StandardCharsets.UTF_8)) {
                var second = SessionLog.open(dir, 1, swallowed);
                second.mailbox().cabin(sample(1, 1, 1, ARRIVED), null);
                expect(ExecutionException.class, () -> get(second.flushAsync()));
                check(second.ioFailure().orElseThrow().cause().getMessage().contains("PrintStream"),
                        "Detecta IOException ocultada por PrintStream");
                expect(IllegalStateException.class, second::close);
            }
        });
    }

    public static void fileWriteFailurePreservesCauseAndHealthyConsole() throws Exception {
        inDirectory(dir -> {
            var console = new ByteArrayOutputStream();
            var factory = LoggerFactory.open(dir, 1, console, path -> path.getFileName().toString().equals("elevador1.log")
                    ? flushing(brokenWrite()) : new FileHandler(path.toString(), true));
            factory.loggerFor(1).info("ARCHIVO_FALLIDO");
            var incident = factory.failure().orElseThrow();
            check(incident.actor().equals("elevador=1") && incident.destination().endsWith("elevador1.log"), "Actor y archivo");
            check(incident.cause().getMessage().contains("escritura inyectada"), "Causa original");
            check(console.toString(StandardCharsets.UTF_8).contains("ARCHIVO_FALLIDO"), "Consola sana");
            expect(IllegalStateException.class, factory::close);
            try (var reopened = LoggerFactory.open(dir, 1, console)) { reopened.loggerFor(1).info("LIBRE"); }
        });
    }

    public static void repeatedHandlerFailuresAreCountedAndFirstIsRetained() throws Exception {
        inDirectory(dir -> {
            var factory = LoggerFactory.open(dir, 1, brokenWrite());
            factory.loggerFor(1).info("PRIMERO"); var first = factory.failure().orElseThrow();
            factory.loggerFor(1).info("SEGUNDO");
            check(factory.errorCount() >= 2, "No silencia reportes posteriores");
            check(factory.failure().orElseThrow() == first, "Primera causa conservada por identidad");
            expect(IllegalStateException.class, factory::close);
            expect(IllegalStateException.class, factory::close);
        });
    }

    public static void flushFailureIsDetected() throws Exception {
        inDirectory(dir -> {
            var fail = new java.util.concurrent.atomic.AtomicBoolean(true);
            var output = new OutputStream() {
                @Override public void write(int value) { }
                @Override public void flush() throws IOException {
                    if (fail.get()) { throw new IOException("flush inyectado"); }
                }
            };
            expect(IOException.class, () -> LoggerFactory.open(dir, 1, output));
            fail.set(false);
            var factory = LoggerFactory.open(dir, 1, output);
            fail.set(true); factory.loggerFor(1).info("VACIA");
            check(factory.failure().orElseThrow().code() == ErrorManager.FLUSH_FAILURE, "Código de flush");
            expect(IllegalStateException.class, factory::close);
        });
    }

    public static void closeFailureDoesNotKeepFileLock() throws Exception {
        inDirectory(dir -> {
            var factory = LoggerFactory.open(dir, 1, new ByteArrayOutputStream(), path -> new StreamHandler(
                    new ByteArrayOutputStream() { @Override public void close() throws IOException { throw new IOException("cierre inyectado"); } },
                    new SimpleFormatter()));
            factory.loggerFor(1).info("ANTES_CIERRE"); expect(IllegalStateException.class, factory::close);
            check(factory.failure().orElseThrow().code() == ErrorManager.CLOSE_FAILURE, "Código de close");
            try (var reopened = LoggerFactory.open(dir, 1, new ByteArrayOutputStream())) { reopened.loggerFor(1).info("REABIERTO"); }
        });
    }

    public static void managerSetupFailureRollsBackCabinResources() throws Exception {
        inDirectory(dir -> {
            expect(IOException.class, () -> LoggerFactory.open(dir, 2, new ByteArrayOutputStream(), path -> {
                if (path.getFileName().toString().equals("manejador.log")) { throw new IOException("No abrir manejador"); }
                return new FileHandler(path.toString(), true);
            }));
            try (var logs = LoggerFactory.open(dir, 2, new ByteArrayOutputStream())) { logs.managerLogger().info("RECUPERADO"); }
            check(readManager(dir).contains("RECUPERADO"), "Rollback completo");
        });
    }

    public static void unusedControllerClosesConsumerAndFiles() throws Exception {
        inDirectory(dir -> {
            SessionLog log;
            try (var rig = new Rig(dir, 2, 5)) { log = rig.log; }
            check(!log.isAlive(), "Cierre sin Start también termina consumidor");
            try (var reopened = SessionLog.open(dir, 2, new ByteArrayOutputStream())) { get(reopened.flushAsync()); }
        });
    }

    public static void closedSessionRejectsLatePublications() throws Exception {
        inDirectory(dir -> {
            var log = SessionLog.open(dir, 1, new ByteArrayOutputStream()); log.close(); log.close();
            expect(IllegalStateException.class, () -> log.mailbox().cabin(sample(1, 1, 1, ARRIVED), null));
            expect(IllegalStateException.class, log::flushAsync);
        });
    }

    public static void sessionOwnershipCannotBeSharedOrMismatched() throws Exception {
        inDirectory(dir -> {
            var log = SessionLog.open(dir, 1, new ByteArrayOutputStream());
            expect(IllegalArgumentException.class, () -> new SimulationController(new SimulationConfig(2, 6, 5, 5), 0, log));
            var controller = new SimulationController(new SimulationConfig(1, 6, 5, 5), 0, log);
            try {
                expect(IllegalStateException.class, () -> new SimulationController(new SimulationConfig(1, 6, 5, 5), 0, log));
            } finally { get(controller.closeAsync()); check(controller.awaitTermination(Duration.ofSeconds(10)), "Cerrador terminó"); }
            check(!log.isAlive(), "Propietario único liberado");
        });
    }

    public static void openingAndClosingOnEdtAreRejected() throws Exception {
        inDirectory(dir -> {
            var seen = new AtomicReference<Throwable>();
            SwingUtilities.invokeAndWait(() -> {
                try { SessionLog.open(dir, 1); } catch (Throwable error) { seen.set(error); }
            });
            check(seen.get() instanceof IllegalStateException, "Apertura fuera del EDT");
            try (var log = SessionLog.open(dir, 1, new ByteArrayOutputStream())) {
                seen.set(null);
                SwingUtilities.invokeAndWait(() -> { try { log.close(); } catch (Throwable error) { seen.set(error); } });
                check(seen.get() instanceof IllegalStateException && log.isAlive(), "Cierre rechazado no sella canal");
            }
        });
    }

    public static void actorFailureKeepsExceptionTrace() throws Exception {
        inDirectory(dir -> {
            try (var log = SessionLog.open(dir, 1, new ByteArrayOutputStream())) {
                log.mailbox().cabin(sample(1, 1, 1, WORKER_FAILED), new IllegalStateException("causa del actor"));
            }
            String text = read(dir, 1);
            check(text.contains("[SEVERE]") && text.contains("evento=WORKER_FAILED;"), "Nivel de fallo");
            check(text.contains("java.lang.IllegalStateException: causa del actor"), "Traza original preservada");
        });
    }

    public static void realManagerFailureIsLogged() throws Exception {
        inDirectory(dir -> {
            var config = new SimulationConfig(1, 6, 5, 5);
            try (var log = SessionLog.open(dir, 1, new ByteArrayOutputStream())) {
                var elevator = new ElevatorWorker(1, config, 0, log.mailbox());
                var manager = new ManagerWorker(config, 0, java.util.List.of(elevator), () -> 0, log.mailbox());
                try {
                    check(elevator.start() && manager.start(), "Actores reales"); manager.submit(hall(0, 3, Direction.UP));
                    check(manager.awaitTermination(Duration.ofSeconds(10)), "Manejador falla sin bloquear");
                    check(manager.failure().isPresent(), "Fallo provocado por generador inválido");
                } finally {
                    manager.close(); elevator.close();
                    check(manager.awaitTermination(Duration.ofSeconds(10)), "Manejador terminado");
                    check(elevator.awaitTermination(Duration.ofSeconds(10)), "Cabina terminada");
                }
            }
            check(readManager(dir).contains("evento=MANAGER_FAILED;")
                    && readManager(dir).contains("Generador de comandos agotado o inválido"), "Fallo y causa en historial");
        });
    }

    public static void degradedLoggingPreventsSuccessfulControllerClose() throws Exception {
        inDirectory(dir -> {
            var log = SessionLog.open(dir, 1, brokenWrite());
            var controller = new SimulationController(new SimulationConfig(1, 6, 5, 5), 0, log);
            get(controller.start()); get(controller.submit(cabin(0, 2, 1)));
            long deadline = deadline();
            while (controller.failure().isEmpty() && System.nanoTime() < deadline) { LockSupport.parkNanos(1_000_000); }
            check(controller.failure().isPresent(), "Incidencia visible durante ejecución");
            expect(ExecutionException.class, () -> get(controller.closeAsync()));
            check(controller.state() != SimulationState.CLOSED && !log.isAlive(), "No confirma cierre exitoso; consumidor liberado");
            check(controller.failure().isPresent(), "Causa conservada después del cierre");
            try (var reopened = SessionLog.open(dir, 1, new ByteArrayOutputStream())) { get(reopened.flushAsync()); }
        });
    }

    private static final class Rig implements AutoCloseable {
        final ByteArrayOutputStream console = new ByteArrayOutputStream();
        final SessionLog log;
        final SimulationController controller;
        Rig(Path dir, int count, long movement) throws IOException {
            log = SessionLog.open(dir, count, console);
            controller = new SimulationController(new SimulationConfig(count, 6, movement, 5), 0, log);
        }
        ExecutionEvent next(long deadline) throws Exception {
            long left = deadline - System.nanoTime(); check(left > 0, "Plazo de eventos agotado");
            return controller.awaitEvent(Duration.ofNanos(left)).orElseThrow(() -> new AssertionError("Falta evento"));
        }
        void until(ExecutionEvent.Type type, int floor) throws Exception {
            long deadline = deadline();
            while (true) { var event = next(deadline); if (event.type() == type && event.snapshot().currentFloor() == floor) { return; } }
        }
        @Override public void close() throws Exception {
            get(controller.closeAsync()); check(controller.awaitTermination(Duration.ofSeconds(10)), "Cierre completo");
            check(!log.isAlive() && controller.failure().isEmpty(), "Sin hilos ni fallos de logging");
        }
    }
    private static ExecutionEvent sample(long seq, int id, int count, ExecutionEvent.Type type) {
        var snapshot = new ElevatorState(id, new SimulationConfig(count, 6, 5, 5), 0).snapshot(System.nanoTime());
        return new ExecutionEvent(seq, type, snapshot, Optional.empty(), Optional.empty(), "Prueba del consumidor");
    }
    private static CabinRequest cabin(long epoch, int floor, int id) { return new CabinRequest(UUID.randomUUID(), epoch, floor, Instant.now(), id); }
    private static HallRequest hall(long epoch, int floor, Direction direction) { return new HallRequest(UUID.randomUUID(), epoch, floor, Instant.now(), direction); }
    private static OutputStream brokenWrite() {
        return new OutputStream() { @Override public void write(int value) throws IOException { throw new IOException("escritura inyectada"); } };
    }
    private static StreamHandler flushing(OutputStream output) {
        return new StreamHandler(output, new SimpleFormatter()) {
            @Override public synchronized void publish(java.util.logging.LogRecord record) { super.publish(record); flush(); }
        };
    }
    private static long deadline() { return System.nanoTime() + TimeUnit.SECONDS.toNanos(10); }
    private static void await(BooleanSupplier condition) {
        long deadline = deadline();
        while (!condition.getAsBoolean()) { check(System.nanoTime() < deadline, "Condición no alcanzada"); LockSupport.parkNanos(1_000_000); }
    }
    private static <T> T get(CompletableFuture<T> future) throws Exception { return future.get(15, TimeUnit.SECONDS); }
    private static String read(Path dir, int id) throws IOException { return Files.readString(dir.resolve("elevador" + id + ".log"), StandardCharsets.UTF_8); }
    private static String readManager(Path dir) throws IOException { return Files.readString(dir.resolve("manejador.log"), StandardCharsets.UTF_8); }
    private static void check(boolean condition, String message) { if (!condition) { throw new AssertionError(message); } }
    private interface Action { void run() throws Exception; }
    private interface DirectoryAction { void run(Path path) throws Exception; }
    private static void expect(Class<? extends Throwable> type, Action action) throws Exception {
        try { action.run(); }
        catch (Throwable error) { if (type.isInstance(error)) { return; } throw new AssertionError("Excepción inesperada", error); }
        throw new AssertionError("Falta excepción " + type.getSimpleName());
    }
    private static void inDirectory(DirectoryAction action) throws Exception {
        Path dir = Files.createTempDirectory("t42-");
        try { action.run(dir); }
        finally { try (var files = Files.walk(dir)) { for (Path p : files.sorted(Comparator.reverseOrder()).toList()) { Files.delete(p); } } }
    }
    public static void main(String[] args) throws Exception {
        obligatoryEventsComeFromPhysicalActors(); realCabinsRemainIsolatedAndConsoleHasOneCopy();
        dispatchAdmissionAndServiceAreDifferentFacts(); duplicateHallRequestKeepsCanonicalIdentity();
        resetKeepsOldHistoryWithoutCompletingCancelledWork(); sequencesRemainUniqueAcrossResets();
        closingInFlightRecordsTerminationWithoutInventingArrival(); closingDrainsEveryQueuedEvent();
        appendDistinguishesSessions(); barrierMakesPriorWritesVisible(); consumerFailureIsVisibleAndCleanupStillRuns();
        consoleWriteFailureIsReportedOutOfBand(); fileWriteFailurePreservesCauseAndHealthyConsole();
        repeatedHandlerFailuresAreCountedAndFirstIsRetained(); flushFailureIsDetected(); closeFailureDoesNotKeepFileLock();
        managerSetupFailureRollsBackCabinResources(); unusedControllerClosesConsumerAndFiles(); closedSessionRejectsLatePublications();
        sessionOwnershipCannotBeSharedOrMismatched(); openingAndClosingOnEdtAreRejected(); actorFailureKeepsExceptionTrace();
        realManagerFailureIsLogged(); degradedLoggingPreventsSuccessfulControllerClose();
        System.out.println("T42: 24 escenarios satisfactorios.");
    }
}
```

## src/test/java/com/elevatormanager/logging/SessionLoggingTest.java

```java
package com.elevatormanager.logging;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Los mismos 24 escenarios de integración y errores, ejecutados por JUnit. */
@Timeout(60)
class SessionLoggingTest {
    @Test void obligatoryEventsComeFromPhysicalActors() throws Exception {
        SessionLoggingScenarios.obligatoryEventsComeFromPhysicalActors();
    }
    @Test void realCabinsRemainIsolatedAndConsoleHasOneCopy() throws Exception {
        SessionLoggingScenarios.realCabinsRemainIsolatedAndConsoleHasOneCopy();
    }
    @Test void dispatchAdmissionAndServiceAreDifferentFacts() throws Exception {
        SessionLoggingScenarios.dispatchAdmissionAndServiceAreDifferentFacts();
    }
    @Test void duplicateHallRequestKeepsCanonicalIdentity() throws Exception {
        SessionLoggingScenarios.duplicateHallRequestKeepsCanonicalIdentity();
    }
    @Test void resetKeepsOldHistoryWithoutCompletingCancelledWork() throws Exception {
        SessionLoggingScenarios.resetKeepsOldHistoryWithoutCompletingCancelledWork();
    }
    @Test void sequencesRemainUniqueAcrossResets() throws Exception {
        SessionLoggingScenarios.sequencesRemainUniqueAcrossResets();
    }
    @Test void closingInFlightRecordsTerminationWithoutInventingArrival() throws Exception {
        SessionLoggingScenarios.closingInFlightRecordsTerminationWithoutInventingArrival();
    }
    @Test void closingDrainsEveryQueuedEvent() throws Exception {
        SessionLoggingScenarios.closingDrainsEveryQueuedEvent();
    }
    @Test void appendDistinguishesSessions() throws Exception {
        SessionLoggingScenarios.appendDistinguishesSessions();
    }
    @Test void barrierMakesPriorWritesVisible() throws Exception {
        SessionLoggingScenarios.barrierMakesPriorWritesVisible();
    }
    @Test void consumerFailureIsVisibleAndCleanupStillRuns() throws Exception {
        SessionLoggingScenarios.consumerFailureIsVisibleAndCleanupStillRuns();
    }
    @Test void consoleWriteFailureIsReportedOutOfBand() throws Exception {
        SessionLoggingScenarios.consoleWriteFailureIsReportedOutOfBand();
    }
    @Test void fileWriteFailurePreservesCauseAndHealthyConsole() throws Exception {
        SessionLoggingScenarios.fileWriteFailurePreservesCauseAndHealthyConsole();
    }
    @Test void repeatedHandlerFailuresAreCountedAndFirstIsRetained() throws Exception {
        SessionLoggingScenarios.repeatedHandlerFailuresAreCountedAndFirstIsRetained();
    }
    @Test void flushFailureIsDetected() throws Exception {
        SessionLoggingScenarios.flushFailureIsDetected();
    }
    @Test void closeFailureDoesNotKeepFileLock() throws Exception {
        SessionLoggingScenarios.closeFailureDoesNotKeepFileLock();
    }
    @Test void managerSetupFailureRollsBackCabinResources() throws Exception {
        SessionLoggingScenarios.managerSetupFailureRollsBackCabinResources();
    }
    @Test void unusedControllerClosesConsumerAndFiles() throws Exception {
        SessionLoggingScenarios.unusedControllerClosesConsumerAndFiles();
    }
    @Test void closedSessionRejectsLatePublications() throws Exception {
        SessionLoggingScenarios.closedSessionRejectsLatePublications();
    }
    @Test void sessionOwnershipCannotBeSharedOrMismatched() throws Exception {
        SessionLoggingScenarios.sessionOwnershipCannotBeSharedOrMismatched();
    }
    @Test void openingAndClosingOnEdtAreRejected() throws Exception {
        SessionLoggingScenarios.openingAndClosingOnEdtAreRejected();
    }
    @Test void actorFailureKeepsExceptionTrace() throws Exception {
        SessionLoggingScenarios.actorFailureKeepsExceptionTrace();
    }
    @Test void realManagerFailureIsLogged() throws Exception {
        SessionLoggingScenarios.realManagerFailureIsLogged();
    }
    @Test void degradedLoggingPreventsSuccessfulControllerClose() throws Exception {
        SessionLoggingScenarios.degradedLoggingPreventsSuccessfulControllerClose();
    }
}
```

