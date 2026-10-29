# T51 — Código completo

Archivos Java nuevos o modificados, íntegros. El ZIP contiene además todos los fuentes previos.

## src/main/java/com/elevatormanager/logging/RecentEventBuffer.java

```java
package com.elevatormanager.logging;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** T51: copia reciente acotada para inspección; no reemplaza la cola fiable ni los archivos. */
public final class RecentEventBuffer {
    public static final int MAX_EVENTS = 500;
    public static final int MAX_TEXT_CHARS = 1200;
    public static final int MAX_TOTAL_CHARS = 300_000;
    public static final int MAX_BATCH = 200;
    public record Event(long sequence, Instant at, String actor, String level, String text) { }
    public record Batch(List<Event> events, long nextCursor, long skipped) {
        public Batch { events = List.copyOf(events); }
    }
    private final ArrayDeque<Event> events = new ArrayDeque<>();
    private long sequence;
    private int textChars;

    /** Solo texto limitado; no retiene Throwable, snapshots ni referencias a trabajadores. */
    public synchronized void append(Instant at, String actor, String level, String text) {
        Objects.requireNonNull(at, "at");
        String bounded = singleLine(text, MAX_TEXT_CHARS);
        String boundedActor = singleLine(actor, 64);
        String boundedLevel = singleLine(level, 32);
        var event = new Event(++sequence, at, boundedActor, boundedLevel, bounded);
        events.addLast(event); textChars += bounded.length();
        while (events.size() > MAX_EVENTS || textChars > MAX_TOTAL_CHARS) {
            textChars -= events.removeFirst().text().length();
        }
    }

    /** Lectura no destructiva por cursor; varios lectores no se roban registros. */
    public synchronized Batch after(long cursor, int limit) {
        if (cursor < 0 || limit < 1 || limit > MAX_BATCH) throw new IllegalArgumentException("Cursor o límite inválido.");
        long skipped = events.isEmpty() ? 0 : Math.max(0, events.getFirst().sequence() - 1 - cursor);
        var result = new ArrayList<Event>();
        for (var event : events) {
            if (event.sequence() > cursor) {
                result.add(event);
                if (result.size() == limit) break;
            }
        }
        return new Batch(result, result.isEmpty() ? cursor : result.get(result.size() - 1).sequence(), skipped);
    }

    public synchronized long lastSequence() { return sequence; }
    public synchronized int retainedCount() { return events.size(); }
    public synchronized int retainedTextChars() { return textChars; }

    private static String singleLine(String text, int maximum) {
        Objects.requireNonNull(text, "text");
        return text.substring(0, Math.min(text.length(), maximum)).replace('\n', ' ').replace('\r', ' ');
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
    private final RecentEventBuffer recent = new RecentEventBuffer();
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

    public RecentEventBuffer recentEvents() { return recent; }

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
            publish(factory.loggerFor(event.elevatorId()), "Elevador " + event.elevatorId(), item.at(), level, text,
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
            publish(factory.managerLogger(), "Manejador", item.at(), result.outcome()
                    == com.elevatormanager.dispatch.ManagerWorker.Outcome.REJECTED ? Level.WARNING : Level.INFO, text, null);
        } else if (entry instanceof TraceMailbox.CallUpdate item) {
            var call = item.call();
            String text = "evento=ESTADO_LLAMADA; secuencia=" + item.sequence()
                    + "; epoca=" + call.request().getEpoch() + "; solicitud=" + call.request().getId()
                    + "; canonica=" + call.canonicalRequestId() + "; estado=" + call.status()
                    + "; cabina=" + call.command().map(Command::elevatorId).map(String::valueOf).orElse("-")
                    + "; comando=" + call.command().map(Command::commandId).map(String::valueOf).orElse("-")
                    + "; intentos=" + call.attempts() + "; detalle=" + call.detail();
            publish(factory.managerLogger(), "Manejador", item.at(), Level.INFO, text, null);
        } else if (entry instanceof TraceMailbox.ManagerState item) {
            publish(factory.managerLogger(), "Manejador", item.at(), item.failure() == null ? Level.INFO : Level.SEVERE,
                    "evento=" + item.state() + "; secuencia=" + item.sequence() + "; epoca=" + item.epoch(), item.failure());
        }
    }

    private void publish(Logger logger, String actor, Instant at, Level level, String message, Throwable error) {
        var record = new LogRecord(level, "sesion=" + sessionId + "; " + message);
        record.setInstant(at);
        record.setThrown(error);
        logger.log(record);
        // La copia visual se limita después de enviar el registro íntegro a sus handlers.
        recent.append(at, actor, level.getName(), record.getMessage()
                + (error == null ? "" : " | " + error.getClass().getSimpleName() + ": " + error.getMessage()));
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

## src/main/java/com/elevatormanager/simulation/SimulationController.java

```java
package com.elevatormanager.simulation;

import com.elevatormanager.io.BatchDiagnostic;
import com.elevatormanager.io.BatchReadResult;
import com.elevatormanager.io.RequestFileReader;
import java.nio.file.Path;
import java.io.IOException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
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

/** T45: sesión con controles serializados e importación validada fuera del EDT. */
public final class SimulationController implements AutoCloseable {
    public enum Outcome { APPLIED, UNCHANGED, ENQUEUED, REJECTED }

    /** ENQUEUED confirma envío, nunca admisión local ni servicio físico. */
    public record Result(Outcome outcome, SimulationState state, String detail,
            Optional<Command.Destination> command, long epoch) { }

    @FunctionalInterface interface BatchLoader {
        BatchReadResult read(Path path, SimulationConfig config) throws Exception;
    }
    private final UUID sessionId = UUID.randomUUID();
    private final BatchLoader batchLoader;
    private final AtomicReference<Thread> importThread = new AtomicReference<>();
    private final ThreadPoolExecutor importExecutor = new ThreadPoolExecutor(1, 1, 0L,
            TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1), task -> {
                var thread = new Thread(task, "simulation-import-" + sessionId);
                importThread.set(thread);
                return thread;
            });
    private static final class PendingImport {
        final long generation;
        final long epoch;
        final CompletableFuture<BatchImportResult> completion;
        Future<?> task;
        PendingImport(long generation, long epoch, CompletableFuture<BatchImportResult> completion) {
            this.generation = generation; this.epoch = epoch; this.completion = completion;
        }
    }
    private volatile long importGeneration;
    private PendingImport pendingImport; // Solo lo modifica el coordinador.
    private volatile Optional<ImportedRequestBatch> preparedBatch = Optional.empty();
    private volatile Optional<BatchDispatchReport> lastBatchDispatch = Optional.empty();
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
        this(config, epoch, Optional.empty(), new RequestFileReader()::read);
    }

    /** Transfiere el logging al controlador si la construcción termina correctamente. */
    public SimulationController(SimulationConfig config, long epoch, SessionLog sessionLog) {
        this(config, epoch, Optional.of(Objects.requireNonNull(sessionLog, "sessionLog")), new RequestFileReader()::read);
    }

    /** Punto de prueba del paquete; el lector de producción sigue siendo RequestFileReader. */
    static SimulationController withBatchLoader(SimulationConfig config, long epoch, BatchLoader loader) {
        return new SimulationController(config, epoch, Optional.empty(), loader);
    }

    private SimulationController(SimulationConfig config, long epoch, Optional<SessionLog> sessionLog,
            BatchLoader batchLoader) {
        this.batchLoader = Objects.requireNonNull(batchLoader, "batchLoader");
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

    /** Vista reciente no destructiva; no consume awaitEvent ni afecta al log completo. */
    public Optional<com.elevatormanager.logging.RecentEventBuffer> recentLogEvents() {
        return sessionLog.map(SessionLog::recentEvents);
    }

    public SimulationConfig config() { return config; }
    public long epoch() { return epoch; }
    public UUID sessionId() { return sessionId; }
    public Optional<ImportedRequestBatch> preparedBatch() { return preparedBatch; }
    public Optional<BatchDispatchReport> lastBatchDispatch() { return lastBatchDispatch; }
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
                    boolean fromReset = state == SimulationState.RESET;
                    if (fromReset) { invalidateImport(BatchImportResult.Outcome.REJECTED, "Start invalidó la lectura pendiente."); }
                    for (var elevator : elevators) {
                        if (!elevator.resume()) { throw new IllegalStateException("Cabina no reanudable."); }
                    }
                    if (!manager.resume()) { throw new IllegalStateException("Manejador no reanudable."); }
                    state = SimulationState.RUNNING;
                    if (fromReset) { releasePreparedBatch(); }
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
                invalidateImport(BatchImportResult.Outcome.REJECTED, "Start invalidó la lectura pendiente.");
                startWorkersOnce();
                state = SimulationState.RUNNING;
                releasePreparedBatch();
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
                invalidateImport(BatchImportResult.Outcome.REJECTED, "Reset invalidó la lectura pendiente.");
                preparedBatch = Optional.empty();
                lastBatchDispatch = Optional.empty();
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
        return schedule(() -> submitNow(request));
    }

    private Result submitNow(Request request) {
        if (closeRequested.get()) { return result(Outcome.REJECTED, "Cierre solicitado; solicitud no enviada."); }
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
    }


    /** Apto para EDT: solo agenda captura de contexto; toda lectura se realiza en el ejecutor I/O. */
    public CompletableFuture<BatchImportResult> importRequests(Path path) {
        Objects.requireNonNull(path, "path");
        var completion = new CompletableFuture<BatchImportResult>();
        executeImportAction(() -> beginImport(path, completion), completion);
        return completion.copy();
    }

    /** Cancelación explícita de la lectura vigente; conserva el lote previamente confirmado. */
    public CompletableFuture<BatchImportResult> cancelImport() {
        var completion = new CompletableFuture<BatchImportResult>();
        executeImportAction(() -> {
            invalidateImport(BatchImportResult.Outcome.CANCELLED, "Lectura cancelada explícitamente.");
            completion.complete(importResult(BatchImportResult.Outcome.CANCELLED, importGeneration,
                    "Cancelación procesada; lote confirmado conservado.", Optional.empty(), List.of()));
        }, completion);
        return completion.copy();
    }

    /** Revalidar también al aplicar una respuesta diferida en la interfaz. */
    public boolean isCurrent(BatchImportResult response) {
        Objects.requireNonNull(response, "response");
        return sessionId.equals(response.sessionId()) && response.generation() == importGeneration
                && response.epoch() == epoch && response.state() == state && !closeRequested.get();
    }

    private void executeImportAction(Runnable action, CompletableFuture<BatchImportResult> completion) {
        try {
            coordinator.execute(() -> {
                if (closeRequested.get()) {
                    completion.complete(importRejected("Cierre solicitado; importación descartada."));
                    return;
                }
                try { action.run(); }
                catch (RuntimeException | Error error) { completion.completeExceptionally(error); }
            });
        } catch (RejectedExecutionException error) {
            completion.complete(importRejected("La sesión está cerrándose o cerrada."));
        }
    }

    private void beginImport(Path path, CompletableFuture<BatchImportResult> completion) {
        if (!state.allowsRequestImport()) {
            completion.complete(importRejected("La importación requiere RESET: " + state));
            return;
        }
        invalidateImport(BatchImportResult.Outcome.SUPERSEDED, "Sustituida por una lectura más reciente.");
        var pending = new PendingImport(importGeneration, epoch, completion);
        pendingImport = pending;
        try {
            pending.task = importExecutor.submit(() -> {
                BatchReadResult parsed;
                try { parsed = Objects.requireNonNull(batchLoader.read(path, config), "Resultado nulo del lector."); }
                catch (Throwable error) {
                    if (error instanceof InterruptedException) { Thread.currentThread().interrupt(); }
                    Exception cause = error instanceof Exception exception ? exception : new IOException("Fallo del lector.", error);
                    parsed = BatchReadResult.invalid(List.of(new BatchDiagnostic(BatchDiagnostic.Code.READ_ERROR,
                            java.util.OptionalInt.empty(), "No se pudo completar la lectura del lote.",
                            "Archivo legible y lector disponible.", Optional.of(cause))));
                }
                BatchReadResult result = parsed;
                try { coordinator.execute(() -> completeImport(pending, result)); }
                catch (RejectedExecutionException error) {
                    // beginClose ya resolvió la operación; no se confirma nada desde el hilo I/O.
                    pending.completion.complete(importRejected("Cierre antes de confirmar la lectura."));
                }
            });
        } catch (RejectedExecutionException error) {
            pendingImport = null;
            completion.complete(importRejected("Ejecutor de lectura no disponible; lote anterior conservado."));
        }
    }

    /** Único punto de confirmación; no se ejecuta I/O ni se admiten solicitudes aquí. */
    private void completeImport(PendingImport pending, BatchReadResult parsed) {
        if (pendingImport != pending || pending.generation != importGeneration) { return; }
        pendingImport = null;
        if (closeRequested.get() || state != SimulationState.RESET || epoch != pending.epoch) {
            pending.completion.complete(importRejected("El contexto cambió durante la lectura; lote no aplicado."));
            return;
        }
        if (!parsed.isValid()) {
            pending.completion.complete(importResult(BatchImportResult.Outcome.INVALID, pending.generation,
                    "Archivo rechazado; lote confirmado anterior conservado.", Optional.empty(), parsed.diagnostics()));
            return;
        }
        if (!parsed.batch().orElseThrow().config().equals(config)) {
            pending.completion.complete(importRejected("Configuración del lote incompatible con la sesión."));
            return;
        }
        try {
            var confirmed = ImportedRequestBatch.confirm(parsed.batch().orElseThrow(), epoch);
            if (closeRequested.get()) {
                pending.completion.complete(importRejected("Cierre solicitado antes de publicar el lote confirmado."));
                return;
            }
            preparedBatch = Optional.of(confirmed); // Sustitución completa tras construir y validar.
            pending.completion.complete(importResult(BatchImportResult.Outcome.IMPORTED, pending.generation,
                    "Lote confirmado en RESET; no se ejecuta hasta Start.", Optional.of(confirmed), List.of()));
        } catch (RuntimeException | Error error) { pending.completion.completeExceptionally(error); }
    }

    /** Invalida antes de interrumpir; un lector tardío jamás puede recuperar su contexto. */
    private void invalidateImport(BatchImportResult.Outcome outcome, String detail) {
        importGeneration = Math.incrementExact(importGeneration);
        PendingImport old = pendingImport;
        pendingImport = null;
        if (old != null) {
            old.completion.complete(importResult(outcome, old.generation, detail, Optional.empty(), List.of()));
            if (old.task != null) { old.task.cancel(true); }
        }
        importExecutor.purge();
    }

    /** Solo Start efectivo desde RESET entra aquí, antes de otras admisiones del coordinador. */
    private void releasePreparedBatch() {
        Optional<ImportedRequestBatch> pending = preparedBatch;
        preparedBatch = Optional.empty(); // Consumido incluso si posteriormente falla algún envío.
        if (pending.isEmpty()) { return; }
        ImportedRequestBatch batch = pending.orElseThrow();
        var entries = new java.util.ArrayList<BatchDispatchReport.Entry>();
        try {
            for (int i = 0; i < batch.requests().size(); i++) {
                Request request = batch.requests().get(i);
                entries.add(new BatchDispatchReport.Entry(batch.source().entries().get(i).lineNumber(),
                        request.getId(), submitNow(request)));
            }
        } catch (RuntimeException | Error error) {
            for (int i = entries.size(); i < batch.requests().size(); i++) {
                entries.add(new BatchDispatchReport.Entry(batch.source().entries().get(i).lineNumber(),
                        batch.requests().get(i).getId(), result(Outcome.REJECTED, "Envío interrumpido por fallo; no se reintentará el lote.")));
            }
            lastBatchDispatch = Optional.of(new BatchDispatchReport(batch, entries));
            throw error;
        }
        lastBatchDispatch = Optional.of(new BatchDispatchReport(batch, entries));
    }

    private BatchImportResult importRejected(String detail) {
        return importResult(BatchImportResult.Outcome.REJECTED, importGeneration, detail, Optional.empty(), List.of());
    }
    private BatchImportResult importResult(BatchImportResult.Outcome outcome, long generation,
            String detail, Optional<ImportedRequestBatch> batch, List<BatchDiagnostic> diagnostics) {
        return new BatchImportResult(outcome, state, epoch, sessionId, generation, detail, batch, diagnostics);
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
        invalidateImport(BatchImportResult.Outcome.REJECTED, "Cierre invalidó la lectura pendiente.");
        preparedBatch = Optional.empty();
        importExecutor.shutdownNow();
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
            boolean importsEnded = false;
            while (!importsEnded) {
                try { importsEnded = importExecutor.awaitTermination(1, TimeUnit.DAYS); }
                catch (InterruptedException exception) { interrupted = true; }
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
                || Thread.currentThread() == shutdownThread || Thread.currentThread() == importThread.get())) {
            throw new IllegalStateException("No esperar el cierre desde un hilo que debe terminarlo.");
        }
        if (!closeRequested.get()) { return false; }
        long began = System.nanoTime();
        try { closeCompletion.get(budget, TimeUnit.NANOSECONDS); }
        catch (TimeoutException | ExecutionException exception) { return false; }
        long remaining = remaining(began, budget);
        if (remaining > 0) { TimeUnit.NANOSECONDS.timedJoin(shutdownThread, remaining); }
        return state == SimulationState.CLOSED && coordinator.isTerminated() && importExecutor.isTerminated()
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

## src/main/java/com/elevatormanager/ui/EventViewerPanel.java

```java
package com.elevatormanager.ui;

import com.elevatormanager.logging.RecentEventBuffer;
import com.elevatormanager.simulation.SimulationController;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Point;
import java.util.ArrayDeque;
import javax.swing.*;
import javax.swing.text.DefaultCaret;

/** T51: historial visual acotado, actualizado por lotes desde el Timer existente. */
public final class EventViewerPanel extends JPanel {
    private static final long serialVersionUID = 1L;
    public static final int MAX_LINES = 300;
    public static final int MAX_DOCUMENT_CHARS = 120_000;
    private final JTextArea text = new JTextArea(7, 80);
    private final JScrollPane scroll = new JScrollPane(text);
    private final JCheckBox follow = new JCheckBox("Seguir últimos", true);
    private final JButton clear = new JButton("Limpiar visor");
    private final JLabel summary = new JLabel("Valida la configuración para ver eventos.");
    private final ArrayDeque<String> lines = new ArrayDeque<>();
    private SimulationController controller;
    private RecentEventBuffer source;
    private long cursor;
    private long skipped;
    private long evicted;
    private int chars;

    public EventViewerPanel() {
        requireEdt(); setLayout(new BorderLayout(4, 4));
        setBorder(BorderFactory.createTitledBorder("Eventos recientes — registros completos en archivos de sesión"));
        setPreferredSize(new Dimension(600, 190)); setMinimumSize(new Dimension(200, 100));
        text.setEditable(false); text.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        ((DefaultCaret) text.getCaret()).setUpdatePolicy(DefaultCaret.NEVER_UPDATE);
        text.getAccessibleContext().setAccessibleName("Eventos recientes de la simulación");
        var actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        actions.add(follow); actions.add(clear);
        add(actions, BorderLayout.NORTH); add(scroll, BorderLayout.CENTER); add(summary, BorderLayout.SOUTH);
        clear.addActionListener(event -> clearView());
        follow.addActionListener(event -> { if (follow.isSelected()) scrollToEnd(); });
    }

    public void bind(SimulationController session) {
        requireEdt(); controller = java.util.Objects.requireNonNull(session, "session");
        source = session.recentLogEvents().orElse(null);
        cursor = 0; skipped = 0; evicted = 0; chars = 0; lines.clear(); text.setText("");
        refresh();
    }

    /** Hasta 200 registros por refresco; nunca lee archivos ni espera eventos. */
    public void refresh() {
        requireEdt();
        if (source == null) {
            summary.setText(controller == null ? "Valida la configuración para ver eventos."
                    : "Esta sesión no tiene un canal de registros visuales.");
            return;
        }
        var batch = source.after(cursor, RecentEventBuffer.MAX_BATCH);
        cursor = batch.nextCursor(); skipped += batch.skipped();
        if (!batch.events().isEmpty()) {
            Point oldPosition = scroll.getViewport().getViewPosition();
            int start = text.getSelectionStart(); int end = text.getSelectionEnd();
            int removedChars = 0; int removedLines = 0;
            for (var event : batch.events()) {
                String line = event.sequence() + " " + event.at() + " [" + event.level() + "] ["
                        + event.actor() + "] " + event.text() + "\n";
                lines.addLast(line); chars += line.length();
                while (lines.size() > MAX_LINES || chars > MAX_DOCUMENT_CHARS) {
                    int removed = lines.removeFirst().length(); chars -= removed;
                    removedChars += removed; ++removedLines; ++evicted;
                }
            }
            text.setText(String.join("", lines));
            if (follow.isSelected()) { scrollToEnd(); }
            else {
                int length = text.getDocument().getLength();
                text.select(Math.max(0, Math.min(length, start - removedChars)), Math.max(0, Math.min(length, end - removedChars)));
                int lineHeight = text.getFontMetrics(text.getFont()).getHeight();
                scroll.getViewport().setViewPosition(new Point(oldPosition.x, Math.max(0, oldPosition.y - removedLines * lineHeight)));
            }
        }
        String failure = controller.failure().map(error -> " | Fallo de sesión: " + error.getClass().getSimpleName()).orElse("");
        summary.setText("Mostrados: " + lines.size() + " | Retirados del visor: " + evicted + " | No recuperables del búfer: " + skipped + failure);
    }

    /** Solo limpia esta vista y avanza su cursor: nunca borra archivos ni vacía el búfer. */
    public void clearView() {
        requireEdt(); cursor = source == null ? 0 : source.lastSequence();
        lines.clear(); chars = 0; skipped = 0; evicted = 0; text.setText(""); refresh();
    }

    private void scrollToEnd() {
        text.setCaretPosition(text.getDocument().getLength());
        text.scrollRectToVisible(new java.awt.Rectangle(0, Math.max(0, text.getPreferredSize().height - 1), 1, 1));
    }
    String displayedText() { requireEdt(); return text.getText(); }
    int displayedCount() { requireEdt(); return lines.size(); }
    String summaryText() { requireEdt(); return summary.getText(); }
    JCheckBox followControl() { requireEdt(); return follow; }
    private static void requireEdt() {
        if (!SwingUtilities.isEventDispatchThread()) { throw new IllegalStateException("El visor requiere EDT."); }
    }
}
```

## src/main/java/com/elevatormanager/ui/SystemControlPanel.java

```java
package com.elevatormanager.ui;

import com.elevatormanager.logging.SessionLog;
import com.elevatormanager.model.SimulationConfig;
import com.elevatormanager.model.SimulationState;
import com.elevatormanager.simulation.SimulationController;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.JSplitPane;

/** T51: controles, cabinas y visor reciente acotado; ningún get/join, lectura o apertura en EDT. */
public final class SystemControlPanel extends JPanel {
    private static final long serialVersionUID = 1L;
    public enum Action { START, STOP, RESET, LOAD, CLOSE }
    @FunctionalInterface interface SessionOpener {
        SimulationController open(SimulationConfig config) throws Exception;
    }
    private final ConfigurationPanel configuration;
    private final HallCallPanel hallCalls;
    private final CabinDashboardPanel cabinDashboard;
    private final EventViewerPanel eventViewer;
    private final EnumMap<Action, JButton> buttons = new EnumMap<>(Action.class);
    private final JLabel stateLabel = new JLabel();
    private final JTextArea detail = new JTextArea(2, 54);
    private final SessionOpener opener;
    private final Supplier<Optional<Path>> selector;
    private final Timer refresh;
    private SimulationController controller;
    private CompletableFuture<SimulationController> preparation;
    private ExecutorService preparer;
    private Runnable onCloseRequest = () -> { };
    private boolean preparing;
    private boolean loading;
    private boolean closing;
    private boolean terminal;
    private boolean disposed;
    private long operationId;
    private long importId;
    private Action pending;

    public SystemControlPanel() { this(SystemControlPanel::openSession, null); }

    /** Inyección de apertura/selección; conserva controlador y actores reales en las pruebas. */
    SystemControlPanel(SessionOpener opener, Supplier<Optional<Path>> selector) {
        requireEdt();
        this.opener = Objects.requireNonNull(opener, "opener");
        this.selector = selector;
        setLayout(new BorderLayout(8, 8));
        setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        configuration = new ConfigurationPanel(this::prepareSession);
        hallCalls = new HallCallPanel();
        var workspace = new JPanel(new BorderLayout(8, 8));
        workspace.add(new JScrollPane(configuration), BorderLayout.CENTER);
        workspace.add(hallCalls, BorderLayout.SOUTH);
        cabinDashboard = new CabinDashboardPanel();
        workspace.setMinimumSize(new java.awt.Dimension(350, 300));
        var split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, workspace, cabinDashboard);
        split.setResizeWeight(0.45);
        var workspaceScroll = new JScrollPane(split);
        workspaceScroll.setMinimumSize(new java.awt.Dimension(200, 120));
        eventViewer = new EventViewerPanel();
        var vertical = new JSplitPane(JSplitPane.VERTICAL_SPLIT, workspaceScroll, eventViewer);
        vertical.setResizeWeight(0.8);
        vertical.setContinuousLayout(true);
        add(vertical, BorderLayout.CENTER);
        var controls = new JPanel(new BorderLayout(6, 6));
        var actions = new JPanel(new FlowLayout(FlowLayout.LEFT));
        String[] titles = {"Start", "Stop", "Reset", "Cargar archivo", "Cerrar"};
        for (var action : Action.values()) {
            var button = new JButton(titles[action.ordinal()]);
            buttons.put(action, button);
            actions.add(button);
        }
        controls.add(stateLabel, BorderLayout.NORTH);
        controls.add(actions, BorderLayout.CENTER);
        detail.setEditable(false);
        detail.setLineWrap(true);
        detail.setWrapStyleWord(true);
        var detailScroll = new JScrollPane(detail);
        detailScroll.setPreferredSize(new java.awt.Dimension(400, 48));
        controls.add(detailScroll, BorderLayout.SOUTH);
        add(controls, BorderLayout.NORTH);
        buttons.get(Action.START).addActionListener(event -> command(Action.START));
        buttons.get(Action.STOP).addActionListener(event -> command(Action.STOP));
        buttons.get(Action.RESET).addActionListener(event -> command(Action.RESET));
        buttons.get(Action.LOAD).addActionListener(event -> load());
        buttons.get(Action.CLOSE).addActionListener(event -> onCloseRequest.run());
        refresh = new Timer(100, event -> updateView());
        refresh.setCoalesce(true);
        refresh.start();
        message("Valida la configuración para preparar la sesión.");
        updateView();
    }

    /** Crea archivos y estructuras fuera del EDT; el controlador recibe la propiedad del log. */
    static SimulationController openSession(SimulationConfig config) throws Exception {
        return openSession(config, Path.of("logs", "sesion-" + UUID.randomUUID()));
    }

    static SimulationController openSession(SimulationConfig config, Path directory) throws Exception {
        var log = SessionLog.open(directory, config.elevatorCount());
        try { return new SimulationController(config, 0, log); }
        catch (RuntimeException | Error error) {
            try { log.close(); } catch (RuntimeException cleanup) { error.addSuppressed(cleanup); }
            throw error;
        }
    }

    private void prepareSession(SimulationConfig config) {
        requireEdt();
        if (preparing || controller != null || closing || terminal) { return; }
        preparing = true;
        configuration.setEditable(false);
        message("Preparando sesión y archivos de registro...");
        updateView();
        preparer = Executors.newSingleThreadExecutor(task -> new Thread(task, "ui-session-prepare"));
        preparation = CompletableFuture.supplyAsync(() -> {
            try { return Objects.requireNonNull(opener.open(config), "session"); }
            catch (Exception error) { throw new CompletionException(error); }
        }, preparer);
        preparer.shutdown(); // Una apertura por intento, sin ejecutor ocioso permanente.
        preparation.whenComplete((session, error) -> SwingUtilities.invokeLater(() -> {
            preparing = false;
            controller = session;
            if (closing || terminal) { return; } // closeAsync espera y libera incluso una apertura tardía.
            if (error != null) {
                configuration.setEditable(true);
                message("No se pudo preparar la sesión: " + cause(error));
            } else {
                hallCalls.bind(session);
                cabinDashboard.bind(session);
                eventViewer.bind(session);
                message("Sesión preparada en RESET. Puedes cargar un archivo o pulsar Start.");
            }
            updateView();
        }));
    }

    private void command(Action action) {
        requireEdt();
        updateView();
        if (!buttons.get(action).isEnabled()) { return; }
        long token = ++operationId;
        pending = action;
        if (action == Action.RESET) { ++importId; loading = false; }
        message(switch (action) {
            case START -> "Iniciando o continuando...";
            case STOP -> "Deteniendo: esperando confirmación de las cabinas...";
            case RESET -> "Reseteando: limpiando solicitudes y retornando al piso 1...";
            default -> throw new IllegalArgumentException("Control desconocido.");
        });
        updateView();
        var result = switch (action) {
            case START -> controller.start();
            case STOP -> controller.stop();
            case RESET -> controller.reset();
            default -> throw new IllegalArgumentException("Control desconocido.");
        };
        result.whenComplete((receipt, error) -> SwingUtilities.invokeLater(() -> {
            if (closing || terminal || token != operationId) { return; }
            pending = null;
            if (error != null) { message("No se completó el control: " + cause(error)); }
            else if (controller.isCurrent(receipt)) { message(receipt.detail()); }
            updateView(); // Se consulta el estado actual; no se restaura el estado del recibo.
        }));
    }

    private void load() {
        requireEdt();
        updateView();
        if (!buttons.get(Action.LOAD).isEnabled()) { return; }
        loading = true;
        long token = ++importId;
        message("Seleccionando o leyendo archivo de solicitudes...");
        updateView();
        java.util.function.Consumer<RequestImportHandler.Result> completed = result -> {
            if (closing || terminal || token != importId) { return; }
            loading = false;
            var text = new StringBuilder(result.detail());
            result.imported().ifPresent(receipt -> {
                receipt.diagnostics().stream().limit(8).forEach(diagnostic -> text.append("\n")
                        .append(diagnostic.lineNumber().isPresent()
                                ? "Línea " + diagnostic.lineNumber().getAsInt() + ": " : "")
                        .append(diagnostic.message()));
                if (receipt.diagnostics().size() > 8) { text.append("\nHay más errores en el archivo."); }
            });
            message(text.toString());
            updateView();
        };
        java.util.function.Consumer<Throwable> failed = error -> {
            if (closing || terminal || token != importId) { return; }
            loading = false;
            message("No se pudo cargar el archivo: " + cause(error));
            updateView();
        };
        var handler = selector == null
                ? RequestImportHandler.forDialog(controller, this, completed, failed)
                : new RequestImportHandler(controller, selector, completed, failed);
        handler.requestImport();
    }

    /** Botón y X deben apuntar al mismo WindowCloseHandler. */
    public void onCloseRequest(Runnable action) { requireEdt(); onCloseRequest = Objects.requireNonNull(action); }

    /** Espera apertura tardía, cierre del controlador y fin del preparador, siempre fuera del EDT. */
    public CompletableFuture<Void> closeAsync() {
        requireEdt();
        closing = true;
        ++operationId;
        ++importId;
        pending = null;
        loading = false;
        configuration.setEditable(false);
        message("Cerrando y liberando recursos...");
        updateView();
        var opened = preparation == null ? CompletableFuture.<SimulationController>completedFuture(controller)
                : preparation.handle((session, error) -> session);
        ExecutorService executor = preparer;
        return opened.thenCompose(session -> session == null ? CompletableFuture.completedFuture(null)
                        : session.closeAsync().thenApply(result -> (Void) null))
                .handleAsync((ignored, error) -> {
                    try {
                        if (executor != null) {
                            while (!executor.awaitTermination(1, TimeUnit.SECONDS)) { /* Espera fuera del EDT. */ }
                        }
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new CompletionException(interrupted);
                    }
                    if (error != null) { throw new CompletionException(error); }
                    return (Void) null;
                });
    }

    public void closed() {
        requireEdt();
        terminal = true;
        disposed = true;
        refresh.stop();
        updateView();
    }

    public void closeFailed(Throwable error) {
        requireEdt();
        // Cierre no confirmado: conserva el panel y permite volver a solicitarlo.
        closing = false;
        terminal = true;
        refresh.stop();
        message("No se confirmó el cierre: " + cause(error));
        updateView();
    }

    /** Lee estado y snapshots publicados; no consume eventos ni espera futuros. */
    void updateView() {
        requireEdt();
        SimulationState state = controller == null ? null : controller.state();
        boolean usable = controller != null && !preparing && !closing && !terminal;
        boolean idle = pending == null;
        hallCalls.refresh(usable && idle && !loading);
        cabinDashboard.refresh(usable && idle && !loading);
        eventViewer.refresh();
        buttons.get(Action.START).setEnabled(usable && idle && !loading
                && (state == SimulationState.RESET || state == SimulationState.STOPPED));
        buttons.get(Action.STOP).setEnabled(usable && idle && state == SimulationState.RUNNING);
        buttons.get(Action.RESET).setEnabled(usable && pending != Action.RESET && pending != Action.START
                && (state == SimulationState.RESET || state == SimulationState.RUNNING
                || state == SimulationState.STOPPING || state == SimulationState.STOPPED));
        buttons.get(Action.LOAD).setEnabled(usable && idle && !loading && state == SimulationState.RESET);
        buttons.get(Action.CLOSE).setEnabled(!closing && !disposed);
        String current = closing ? "CLOSING — Cerrando..." : preparing ? "Preparando sesión..."
                : state == null ? "SIN CONFIGURAR" : switch (state) {
                    case STOPPING -> "STOPPING — Deteniendo...";
                    case RESETTING -> "RESETTING — Reseteando...";
                    case CLOSING -> "CLOSING — Cerrando...";
                    default -> state.name();
                };
        if (!closing && pending == Action.STOP) { current = "STOPPING — Deteniendo..."; }
        if (!closing && pending == Action.RESET) { current = "RESETTING — Reseteando..."; }
        int count = controller == null ? 0 : controller.preparedBatch().map(batch -> batch.requests().size()).orElse(0);
        stateLabel.setText("Estado: " + current + " | Lote preparado: " + count);
    }

    private void message(String text) {
        // Es el resultado de la última acción, no el visor de eventos de T51.
        detail.setText(text.length() > 4000 ? text.substring(0, 4000) : text);
        detail.setCaretPosition(0);
    }

    private static String cause(Throwable error) {
        while (error instanceof CompletionException && error.getCause() != null) { error = error.getCause(); }
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    public Optional<SimulationController> controller() { requireEdt(); return Optional.ofNullable(controller); }
    EventViewerPanel eventViewer() { requireEdt(); return eventViewer; }
    CabinDashboardPanel cabinDashboard() { requireEdt(); return cabinDashboard; }
    HallCallPanel hallCallPanel() { requireEdt(); return hallCalls; }
    ConfigurationPanel configurationPanel() { requireEdt(); return configuration; }
    JButton button(Action action) { requireEdt(); return buttons.get(action); }
    String stateText() { requireEdt(); return stateLabel.getText(); }
    String detailText() { requireEdt(); return detail.getText(); }

    private static void requireEdt() {
        if (!SwingUtilities.isEventDispatchThread()) { throw new IllegalStateException("Los controles requieren EDT."); }
    }
}
```

## src/main/java/com/elevatormanager/ui/CabinDashboardPanel.java

```java
package com.elevatormanager.ui;

import com.elevatormanager.dispatch.HallCallSnapshot;
import com.elevatormanager.simulation.SimulationController;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.util.*;
import javax.swing.*;

/** T50: N cabinas con botones 1..P paginados; comparte el refresco de T48. */
public final class CabinDashboardPanel extends JPanel {
    private static final long serialVersionUID = 1L;
    private final JTabbedPane tabs = new JTabbedPane(JTabbedPane.TOP, JTabbedPane.SCROLL_TAB_LAYOUT);
    private final List<CabinPanel> cabins = new ArrayList<>();
    private SimulationController controller;
    private JSpinner selector;
    private final JLabel navigationStatus = new JLabel();

    public CabinDashboardPanel() {
        requireEdt(); setLayout(new BorderLayout());
        setBorder(BorderFactory.createTitledBorder("Paneles de cabina"));
        setPreferredSize(new Dimension(470, 470));
        setMinimumSize(new Dimension(330, 250));
        add(new JLabel("Valida la configuración para crear las cabinas."), BorderLayout.CENTER);
    }

    public void bind(SimulationController session) {
        requireEdt(); controller = Objects.requireNonNull(session, "session");
        removeAll(); tabs.removeAll(); cabins.clear();
        for (int id = 1; id <= session.config().elevatorCount(); id++) {
            var panel = new CabinPanel(session, id);
            cabins.add(panel); tabs.addTab("Elevador " + id, new JScrollPane(panel));
        }
        selector = new JSpinner(new SpinnerNumberModel(1, 1, cabins.size(), 1));
        selector.setEditor(new JSpinner.NumberEditor(selector, "0"));
        selector.getAccessibleContext().setAccessibleName("Número de cabina a inspeccionar");
        var show = new JButton("Ver cabina");
        show.addActionListener(event -> selectTypedCabin());
        var navigation = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT));
        navigation.add(new JLabel("Cabina:")); navigation.add(selector); navigation.add(show);
        navigationStatus.setText(cabins.size() + " cabinas"); navigation.add(navigationStatus);
        add(navigation, BorderLayout.NORTH);
        add(tabs, BorderLayout.CENTER); refresh(false); revalidate(); repaint();
    }

    /** Una captura por cabina; no representa un instante físico global único. */
    public void refresh(boolean interactive) {
        requireEdt(); if (controller == null) return;
        var snapshots = controller.snapshots();
        var assigned = new HashMap<Integer, List<HallCallSnapshot>>();
        controller.calls().values().forEach(call -> call.command().ifPresent(command ->
                assigned.computeIfAbsent(command.elevatorId(), id -> new ArrayList<>()).add(call)));
        for (var snapshot : snapshots) {
            cabins.get(snapshot.id() - 1).render(snapshot, assigned.getOrDefault(snapshot.id(), List.of()), interactive);
        }
    }

    private void selectTypedCabin() {
        requireEdt();
        try {
            int id = Integer.parseInt(((JSpinner.NumberEditor) selector.getEditor()).getTextField().getText().strip());
            selectCabin(id);
            navigationStatus.setText(cabins.size() + " cabinas");
        } catch (IllegalArgumentException error) {
            navigationStatus.setText("Indica una cabina entre 1 y " + cabins.size() + ".");
        }
    }

    void selectCabin(int id) {
        requireEdt();
        if (id < 1 || id > cabins.size()) throw new IllegalArgumentException("Cabina fuera de rango.");
        selector.setValue(id); tabs.setSelectedIndex(id - 1);
    }
    int selectedCabin() { requireEdt(); return tabs.getSelectedIndex() + 1; }
    CabinPanel cabin(int id) { requireEdt(); return cabins.get(id - 1); }
    int cabinCount() { requireEdt(); return cabins.size(); }
    private static void requireEdt() {
        if (!SwingUtilities.isEventDispatchThread()) { throw new IllegalStateException("El conjunto de cabinas requiere EDT."); }
    }
}
```

## src/main/java/com/elevatormanager/ui/MainWindow.java

```java
package com.elevatormanager.ui;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Rectangle;
import java.awt.GraphicsEnvironment;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;

/** T48: entrada real de configuración y controles; botón y X comparten cierre confirmado. */
public final class MainWindow {
    private static final Logger LOGGER = Logger.getLogger(MainWindow.class.getName());
    private final JFrame frame;

    public MainWindow() { this(() -> CompletableFuture.completedFuture(null)); }

    /** Conserva la extensión de cierre de T39, después de liberar la sesión de esta ventana. */
    public MainWindow(Supplier<? extends CompletionStage<?>> closeAction) {
        requireEventDispatchThread();
        frame = new JFrame("Elevator Manager");
        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        var controls = new SystemControlPanel();
        var close = new WindowCloseHandler(
                () -> controls.closeAsync().thenCompose(ignored -> {
                    // El proveedor externo puede tener contrato EDT: obtenerlo en ese hilo.
                    var completion = new CompletableFuture<Void>();
                    SwingUtilities.invokeLater(() -> {
                        try {
                            closeAction.get().whenComplete((value, error) -> {
                                if (error == null) { completion.complete(null); }
                                else { completion.completeExceptionally(error); }
                            });
                        } catch (RuntimeException error) { completion.completeExceptionally(error); }
                    });
                    return completion;
                }),
                () -> { },
                () -> { controls.closed(); frame.dispose(); },
                error -> {
                    controls.closeFailed(error);
                    LOGGER.log(Level.SEVERE, "CIERRE_NO_COMPLETADO", error);
                });
        controls.onCloseRequest(close::requestClose);
        frame.addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent event) { close.requestClose(); }
        });
        frame.add(new JLabel("Elevator Manager — Simulador de elevadores", SwingConstants.CENTER), BorderLayout.NORTH);
        frame.add(controls, BorderLayout.CENTER);
        Rectangle usable = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        frame.setMinimumSize(minimumSize(usable));
        frame.pack();
        frame.setBounds(initialBounds(usable));
    }

    public void showWindow() { requireEventDispatchThread(); frame.setVisible(true); }

    /** Geometría pura para probar pantallas pequeñas y coordenadas virtuales sin JFrame. */
    static Rectangle initialBounds(Rectangle usable) {
        if (usable.width < 1 || usable.height < 1) throw new IllegalArgumentException("Área de pantalla inválida.");
        int width = Math.min(1180, usable.width); int height = Math.min(860, usable.height);
        return new Rectangle(usable.x + (usable.width - width) / 2, usable.y + (usable.height - height) / 2, width, height);
    }
    static Dimension minimumSize(Rectangle usable) {
        if (usable.width < 1 || usable.height < 1) throw new IllegalArgumentException("Área de pantalla inválida.");
        return new Dimension(Math.min(720, usable.width), Math.min(480, usable.height));
    }

    private static void requireEventDispatchThread() {
        if (!SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("La ventana debe utilizarse desde el EDT.");
        }
    }
}
```

## src/test/java/com/elevatormanager/ui/EventViewerScenarios.java

```java
package com.elevatormanager.ui;

import com.elevatormanager.logging.*;
import com.elevatormanager.model.*;
import com.elevatormanager.model.SimulationConfig.Field;
import com.elevatormanager.model.request.CabinRequest;
import com.elevatormanager.simulation.*;
import java.awt.Rectangle;
import java.io.OutputStream;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.*;
import javax.swing.SwingUtilities;

/** T51: retención acotada, archivos íntegros, Swing real y geometría sin JFrame. */
public final class EventViewerScenarios {
    private EventViewerScenarios() { }
    public static void emptyBufferIsReadable() throws Exception {
        var buffer = new RecentEventBuffer(); var batch = buffer.after(0, 10);
        check(batch.events().isEmpty() && batch.nextCursor() == 0 && batch.skipped() == 0, "Búfer vacío");
    }

    public static void sequencesAreOrderedAndCursorDoesNotRepeat() throws Exception {
        var buffer = new RecentEventBuffer(); append(buffer, 3, "event");
        var first = buffer.after(0, 2); var second = buffer.after(first.nextCursor(), 2);
        check(first.events().get(0).sequence() == 1 && first.nextCursor() == 2
                && second.events().size() == 1 && second.nextCursor() == 3, "Cursor monótono");
    }

    public static void recordLimitEvictsOldest() throws Exception {
        var buffer = new RecentEventBuffer(); append(buffer, 700, "event");
        check(buffer.retainedCount() == 500 && buffer.after(0, 1).events().get(0).sequence() == 201, "Anillo por cantidad");
    }

    public static void characterBudgetEvictsEvenBeforeCountLimit() throws Exception {
        var buffer = new RecentEventBuffer(); append(buffer, 400, "x".repeat(1200));
        check(buffer.retainedTextChars() <= 300_000 && buffer.retainedCount() == 250, "Presupuesto de caracteres");
    }

    public static void messageAndMetadataAreBounded() throws Exception {
        var buffer = new RecentEventBuffer(); buffer.append(Instant.now(), "a".repeat(200), "l".repeat(100), "m".repeat(5000));
        var event = buffer.after(0, 1).events().get(0);
        check(event.actor().length() == 64 && event.level().length() == 32 && event.text().length() == 1200, "Límites individuales");
    }

    public static void embeddedNewlinesCannotCreateUnlimitedVisualLines() throws Exception {
        var buffer = new RecentEventBuffer(); buffer.append(Instant.now(), "a\nb", "INFO", "one\ntwo\rthree");
        var event = buffer.after(0, 1).events().get(0);
        check(!event.text().contains("\n") && !event.text().contains("\r") && !event.actor().contains("\n"), "Una entrada visual por registro");
    }

    public static void batchResultsAreImmutable() throws Exception {
        var buffer = new RecentEventBuffer(); append(buffer, 1, "event");
        try { buffer.after(0, 1).events().clear(); throw new AssertionError("Lista mutable"); }
        catch (UnsupportedOperationException expected) { }
    }

    public static void invalidCursorAndBatchAreRejected() throws Exception {
        var buffer = new RecentEventBuffer();
        for (int limit : new int[] {0, -1, 201}) {
            try { buffer.after(0, limit); throw new AssertionError("Límite aceptado"); } catch (IllegalArgumentException expected) { }
        }
        try { buffer.after(-1, 1); throw new AssertionError("Cursor negativo"); } catch (IllegalArgumentException expected) { }
    }

    public static void skippedRecordsAreCountedOnce() throws Exception {
        var buffer = new RecentEventBuffer(); append(buffer, 800, "event");
        var first = buffer.after(0, 200); var next = buffer.after(first.nextCursor(), 200);
        check(first.skipped() == 300 && next.skipped() == 0, "Pérdidas del búfer contadas al avanzar");
    }

    public static void independentReadersDoNotConsumeEachOther() throws Exception {
        var buffer = new RecentEventBuffer(); append(buffer, 10, "event");
        check(buffer.after(0, 10).equals(buffer.after(0, 10)) && buffer.retainedCount() == 10, "Lectura no destructiva");
    }

    public static void concurrentProducersPreserveSequenceAndBounds() throws Exception {
        var buffer = new RecentEventBuffer(); var pool = Executors.newFixedThreadPool(4);
        try {
            var jobs = new ArrayList<Future<?>>(); for (int i = 0; i < 4; i++) jobs.add(pool.submit(() -> append(buffer, 400, "event")));
            for (var job : jobs) job.get(5, TimeUnit.SECONDS);
            check(buffer.lastSequence() == 1600 && buffer.retainedCount() == 500, "Productores concurrentes");
            var batch = buffer.after(0, 200); long last = batch.events().get(0).sequence() - 1;
            for (var event : batch.events()) check(event.sequence() == ++last, "Orden sin duplicación");
        } finally { pool.shutdownNow(); check(pool.awaitTermination(5, TimeUnit.SECONDS), "Pool terminado"); }
    }

    public static void nullInputDoesNotAdvanceSequence() throws Exception {
        var buffer = new RecentEventBuffer();
        try { buffer.append(Instant.now(), null, "INFO", "event"); throw new AssertionError("Actor nulo"); } catch (NullPointerException expected) { }
        check(buffer.lastSequence() == 0 && buffer.retainedCount() == 0, "Sin mutación parcial");
    }

    public static void fullFilesSurviveVisualBufferOverflow() throws Exception {
        try (var rig = new LogRig()) {
            rig.emit(1200, "T51_FLOOD_");
            String file = Files.readString(rig.directory.resolve("manejador.log"));
            check(file.split("evento=T51_FLOOD_", -1).length - 1 == 1200, "Todos los eventos en disco");
            check(rig.log.recentEvents().retainedCount() <= 500 && rig.log.failure().isEmpty(), "Solo se recorta copia visual");
        }
    }

    public static void longRecordIsCompleteInFileButTruncatedInViewerSource() throws Exception {
        try (var rig = new LogRig()) {
            rig.log.mailbox().managerState(0, "T51_LONG_" + "x".repeat(4000) + "TAIL_END", null); rig.flush();
            check(Files.readString(rig.directory.resolve("manejador.log")).contains("TAIL_END"), "Archivo no truncado");
            var event = rig.log.recentEvents().after(0, 1).events().get(0);
            check(event.text().length() <= 1200 && !event.text().contains("TAIL_END"), "Copia limitada");
        }
    }

    public static void exceptionRemainsInFileAndTextSummary() throws Exception {
        try (var rig = new LogRig()) {
            rig.log.mailbox().managerState(0, "T51_FAILURE", new IllegalStateException("t51-exception")); rig.flush();
            var event = rig.log.recentEvents().after(0, 1).events().get(0);
            check(event.level().equals("SEVERE") && event.text().contains("IllegalStateException"), "Resumen de excepción");
            check(Files.readString(rig.directory.resolve("manejador.log")).contains("t51-exception"), "Excepción completa en archivo");
        }
    }

    public static void viewerReadsManagerAndCabinEvents() throws Exception {
        try (var rig = new LogRig()) {
            rig.c.start().get(5, TimeUnit.SECONDS);
            rig.c.submit(new CabinRequest(UUID.randomUUID(), 0, 3, Instant.now(), 1)).get(5, TimeUnit.SECONDS);
            waitFor(() -> rig.c.snapshots().get(0).currentFloor() == 3); rig.flush(); rig.refreshAll();
            edt(() -> check(rig.viewer.displayedText().contains("[Manejador]") && rig.viewer.displayedText().contains("[Elevador 1]")
                    && rig.viewer.displayedText().contains("evento=ARRIVED"), "Eventos reales de ambos productores"));
        }
    }

    public static void viewerDoesNotStealControllerExecutionEvents() throws Exception {
        try (var rig = new LogRig()) {
            rig.c.start().get(5, TimeUnit.SECONDS);
            rig.c.submit(new CabinRequest(UUID.randomUUID(), 0, 2, Instant.now(), 1)).get(5, TimeUnit.SECONDS);
            waitFor(() -> rig.c.snapshots().get(0).currentFloor() == 2); rig.flush(); rig.refreshAll();
            boolean completed = false;
            for (int i = 0; i < 30; i++) {
                var event = rig.c.awaitEvent(Duration.ofMillis(20));
                if (event.isPresent() && event.orElseThrow().type() == ExecutionEvent.Type.COMMAND_COMPLETED) { completed = true; break; }
            }
            check(completed, "awaitEvent conserva su copia independiente");
        }
    }

    public static void viewerPreservesHistoryAcrossReset() throws Exception {
        try (var rig = new LogRig()) {
            rig.c.start().get(5, TimeUnit.SECONDS); rig.log.mailbox().managerState(0, "T51_BEFORE_RESET", null);
            rig.c.reset().get(5, TimeUnit.SECONDS); rig.log.mailbox().managerState(rig.c.epoch(), "T51_AFTER_RESET", null);
            rig.flush(); rig.refreshAll();
            edt(() -> check(rig.viewer.displayedText().contains("T51_BEFORE_RESET") && rig.viewer.displayedText().contains("T51_AFTER_RESET")
                    && rig.viewer.displayedText().contains("epoca=1"), "Historial conserva épocas"));
        }
    }

    public static void clearDoesNotDeleteFilesOrSharedBuffer() throws Exception {
        try (var rig = new LogRig()) {
            rig.emit(10, "T51_CLEAR_"); rig.refreshAll(); String before = Files.readString(rig.directory.resolve("manejador.log"));
            int retained = rig.log.recentEvents().retainedCount();
            edt(() -> { rig.viewer.clearView(); check(rig.viewer.displayedCount() == 0, "Visor limpio"); });
            check(before.equals(Files.readString(rig.directory.resolve("manejador.log"))) && retained == rig.log.recentEvents().retainedCount(), "Archivos y búfer intactos");
        }
    }

    public static void clearAdvancesCursorAndAllowsNewEvents() throws Exception {
        try (var rig = new LogRig()) {
            rig.emit(5, "T51_OLD_"); rig.refreshAll(); edt(rig.viewer::clearView); rig.emit(1, "T51_NEW_"); rig.refreshAll();
            edt(() -> check(!rig.viewer.displayedText().contains("T51_OLD_") && rig.viewer.displayedText().contains("T51_NEW_"), "Sin repetición tras limpiar"));
        }
    }

    public static void oneRefreshReadsAtMostTwoHundredRecords() throws Exception {
        try (var rig = new LogRig()) {
            rig.emit(400, "T51_BATCH_"); edt(rig.viewer::refresh);
            edt(() -> check(rig.viewer.displayedCount() == 200, "Lote visual acotado"));
        }
    }

    public static void visibleLineCountIsBounded() throws Exception {
        try (var rig = new LogRig()) {
            rig.emit(500, "T51_LINES_"); rig.refreshAll();
            edt(() -> check(rig.viewer.displayedCount() == 300 && rig.viewer.summaryText().contains("Retirados del visor: 200"), "Límite de líneas"));
        }
    }

    public static void visibleDocumentCharacterCountIsBounded() throws Exception {
        try (var rig = new LogRig()) {
            rig.emit(400, "T51_CHARS_" + "x".repeat(1200)); rig.refreshAll();
            edt(() -> check(rig.viewer.displayedText().length() <= 120_000 && rig.viewer.displayedCount() < 300, "Presupuesto del documento"));
        }
    }

    public static void bufferGapsAreReportedInViewer() throws Exception {
        try (var rig = new LogRig()) {
            rig.emit(900, "T51_GAP_"); rig.refreshAll();
            edt(() -> check(rig.viewer.summaryText().contains("No recuperables del búfer: 400"), "Recorte comunicado"));
        }
    }

    public static void viewerRefreshDoesNotDuplicateRecords() throws Exception {
        try (var rig = new LogRig()) {
            rig.emit(20, "T51_UNIQUE_"); rig.refreshAll();
            String before = onEdt(rig.viewer::displayedText); rig.refreshAll();
            edt(() -> check(before.equals(rig.viewer.displayedText()), "Cursor evita duplicación"));
        }
    }

    public static void viewerWithoutSessionHasUsefulPlaceholder() throws Exception {
        var viewer = onEdt(EventViewerPanel::new);
        edt(() -> { viewer.refresh(); check(viewer.displayedCount() == 0 && viewer.summaryText().contains("configuración"), "Sin sesión"); });
    }

    public static void controllerWithoutLoggingIsSupported() throws Exception {
        var c = new SimulationController(new SimulationConfig(1, 2, 10, 10), 0);
        try {
            var viewer = onEdt(EventViewerPanel::new);
            edt(() -> { viewer.bind(c); check(viewer.summaryText().contains("no tiene un canal"), "Sesión sin logging explícito"); });
        } finally { c.closeAsync().get(5, TimeUnit.SECONDS); check(c.awaitTermination(Duration.ofSeconds(5)), "Sin hilos vivos"); }
    }

    public static void rebindingDoesNotMixSessions() throws Exception {
        try (var first = new LogRig(); var second = new LogRig()) {
            first.emit(1, "T51_FIRST_"); first.refreshAll(); second.emit(1, "T51_SECOND_");
            edt(() -> { first.viewer.bind(second.c); check(!first.viewer.displayedText().contains("T51_FIRST_")
                    && first.viewer.displayedText().contains("T51_SECOND_"), "Historial de sesión nueva"); });
        }
    }

    public static void followCanBeDisabledWithoutPausingLogging() throws Exception {
        try (var rig = new LogRig()) {
            edt(() -> rig.viewer.followControl().setSelected(false)); rig.emit(10, "T51_FOLLOW_"); rig.refreshAll();
            edt(() -> check(!rig.viewer.followControl().isSelected() && rig.viewer.displayedCount() == 10, "Seguir no detiene ingesta"));
            check(Files.readString(rig.directory.resolve("manejador.log")).contains("T51_FOLLOW_9"), "Archivo continúa");
        }
    }

    public static void viewerRejectsOffEdtAccess() throws Exception {
        try { new EventViewerPanel(); throw new AssertionError("Construcción fuera EDT"); } catch (IllegalStateException expected) { }
        var viewer = onEdt(EventViewerPanel::new);
        try { viewer.refresh(); throw new AssertionError("Refresco fuera EDT"); } catch (IllegalStateException expected) { }
        try { viewer.clearView(); throw new AssertionError("Limpieza fuera EDT"); } catch (IllegalStateException expected) { }
    }

    public static void manyCabinsCanBeSelectedDirectly() throws Exception {
        var c = new SimulationController(new SimulationConfig(25, 3, 10, 10), 0);
        try {
            edt(() -> { var dashboard = new CabinDashboardPanel(); dashboard.bind(c); dashboard.selectCabin(25);
                check(dashboard.cabinCount() == 25 && dashboard.selectedCabin() == 25
                        && dashboard.cabin(25).locationText().contains("Piso actual: 1"), "Cabina fuera de pestañas iniciales accesible"); });
        } finally { c.closeAsync().get(5, TimeUnit.SECONDS); check(c.awaitTermination(Duration.ofSeconds(5)), "25 cabinas liberadas"); }
    }

    public static void invalidCabinSelectionIsRejected() throws Exception {
        var c = new SimulationController(new SimulationConfig(2, 3, 10, 10), 0);
        try {
            edt(() -> { var dashboard = new CabinDashboardPanel(); dashboard.bind(c);
                for (int id : new int[] {0, 3}) {
                    try { dashboard.selectCabin(id); throw new AssertionError("Cabina inexistente"); } catch (IllegalArgumentException expected) { }
                }
                check(dashboard.selectedCabin() == 1, "Selección conservada"); });
        } finally { c.closeAsync().get(5, TimeUnit.SECONDS); check(c.awaitTermination(Duration.ofSeconds(5)), "Cerrada"); }
    }

    public static void initialWindowFitsSmallDesktop() throws Exception {
        var usable = new Rectangle(0, 0, 800, 600); var bounds = MainWindow.initialBounds(usable);
        check(usable.contains(bounds) && bounds.width == 800 && bounds.height == 600, "Sin exceder escritorio pequeño");
    }

    public static void initialWindowFitsLaptopDesktop() throws Exception {
        var usable = new Rectangle(0, 0, 1366, 728); var bounds = MainWindow.initialBounds(usable);
        check(usable.contains(bounds) && bounds.width == 1180 && bounds.height == 728, "Área útil sin barra de tareas");
    }

    public static void initialWindowSupportsNegativeDesktopCoordinates() throws Exception {
        var usable = new Rectangle(-1920, -100, 1920, 1080); var bounds = MainWindow.initialBounds(usable);
        check(usable.contains(bounds) && bounds.x < 0, "Escritorio virtual");
    }

    public static void minimumWindowFitsTinyDesktop() throws Exception {
        var usable = new Rectangle(0, 0, 640, 360); var minimum = MainWindow.minimumSize(usable);
        check(minimum.width == 640 && minimum.height == 360 && usable.contains(MainWindow.initialBounds(usable)), "Mínimo limitado por pantalla");
    }

    public static void invalidDesktopAreaIsRejected() throws Exception {
        for (var area : List.of(new Rectangle(0, 0, 0, 600), new Rectangle(0, 0, 800, -1))) {
            try { MainWindow.initialBounds(area); throw new AssertionError("Área inválida"); } catch (IllegalArgumentException expected) { }
            try { MainWindow.minimumSize(area); throw new AssertionError("Mínimo inválido"); } catch (IllegalArgumentException expected) { }
        }
    }

    public static void composedWindowReceivesRealLoggingEvents() throws Exception {
        Path directory = Files.createTempDirectory("t51-composition-");
        var panel = onEdt(() -> new SystemControlPanel(config -> {
            var log = SessionLog.open(directory, config.elevatorCount(), OutputStream.nullOutputStream());
            try { return new SimulationController(config, 0, log); } catch (RuntimeException | Error error) { log.close(); throw error; }
        }, Optional::empty));
        try {
            edt(() -> { String[] values = {"2", "4", "40", "20"}; var form = panel.configurationPanel();
                for (var field : Field.values()) form.input(field).setText(values[field.ordinal()]); form.validateConfiguration(); });
            waitFor(() -> onEdt(() -> panel.controller().isPresent()));
            edt(() -> panel.button(SystemControlPanel.Action.START).doClick(0));
            waitFor(() -> onEdt(() -> { panel.updateView(); return panel.eventViewer().displayedText().contains("MANAGER_STARTED"); }));
            edt(() -> panel.cabinDashboard().cabin(1).destinationButton(3).doClick(0));
            waitFor(() -> onEdt(() -> { panel.updateView(); return panel.eventViewer().displayedText().contains("COMMAND_COMPLETED"); }));
        } finally {
            onEdt(panel::closeAsync).get(5, TimeUnit.SECONDS); edt(panel::closed); deleteTree(directory);
        }
    }

    public static void viewerStillContainsTerminationEventsAfterClose() throws Exception {
        try (var rig = new LogRig()) {
            rig.c.start().get(5, TimeUnit.SECONDS); rig.c.closeAsync().get(5, TimeUnit.SECONDS); rig.refreshAll();
            edt(() -> check(rig.viewer.displayedText().contains("WORKER_TERMINATED")
                    && rig.viewer.displayedText().contains("MANAGER_TERMINATED"), "Últimos registros tras cierre"));
        }
    }

    public static void closedLoggingCanStillBeInspectedWithoutIO() throws Exception {
        try (var rig = new LogRig()) {
            rig.emit(3, "T51_CLOSED_"); rig.c.closeAsync().get(5, TimeUnit.SECONDS);
            check(!rig.log.isAlive(), "Consumidor cerrado"); rig.refreshAll();
            edt(() -> check(rig.viewer.displayedText().contains("T51_CLOSED_2"), "Búfer retenido consultable"));
        }
    }

    private static void append(RecentEventBuffer buffer, int count, String text) {
        for (int i = 0; i < count; i++) buffer.append(Instant.now(), "Manejador", "INFO", text);
    }
    private static final class LogRig implements AutoCloseable {
        final Path directory = Files.createTempDirectory("t51-log-");
        final SessionLog log = SessionLog.open(directory, 2, OutputStream.nullOutputStream());
        final SimulationController c = new SimulationController(new SimulationConfig(2, 5, 40, 20), 0, log);
        final EventViewerPanel viewer = onEdt(EventViewerPanel::new);
        LogRig() throws Exception { edt(() -> viewer.bind(c)); }
        void emit(int count, String prefix) throws Exception {
            for (int i = 0; i < count; i++) log.mailbox().managerState(c.epoch(), prefix + i, null); flush();
        }
        void flush() throws Exception { log.flushAsync().get(5, TimeUnit.SECONDS); }
        void refreshAll() throws Exception { for (int i = 0; i < 4; i++) edt(viewer::refresh); }
        @Override public void close() throws Exception {
            c.closeAsync().get(5, TimeUnit.SECONDS); check(c.awaitTermination(Duration.ofSeconds(5)), "Recursos terminados"); deleteTree(directory);
        }
    }
    private static void deleteTree(Path directory) throws Exception {
        try (var paths = Files.walk(directory)) { for (var path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path); }
    }
    private static void check(boolean condition, String text) { if (!condition) throw new AssertionError(text); }
    private static void waitFor(BooleanSupplier condition) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(6);
        while (!condition.getAsBoolean()) { if (System.nanoTime() > until) throw new AssertionError("Tiempo agotado esperando condición"); Thread.sleep(5); }
    }
    private static void edt(Runnable action) throws Exception { onEdt(() -> { action.run(); return null; }); }
    private static <T> T onEdt(Supplier<T> action) {
        if (SwingUtilities.isEventDispatchThread()) return action.get();
        var value = new AtomicReference<T>(); var failure = new AtomicReference<Throwable>();
        try { SwingUtilities.invokeAndWait(() -> { try { value.set(action.get()); } catch (Throwable error) { failure.set(error); } }); }
        catch (Exception error) { throw new AssertionError(error); }
        if (failure.get() instanceof Error error) throw error;
        if (failure.get() != null) throw new IllegalStateException(failure.get()); return value.get();
    }
    public static void main(String[] args) throws Exception {
        emptyBufferIsReadable();
        sequencesAreOrderedAndCursorDoesNotRepeat();
        recordLimitEvictsOldest();
        characterBudgetEvictsEvenBeforeCountLimit();
        messageAndMetadataAreBounded();
        embeddedNewlinesCannotCreateUnlimitedVisualLines();
        batchResultsAreImmutable();
        invalidCursorAndBatchAreRejected();
        skippedRecordsAreCountedOnce();
        independentReadersDoNotConsumeEachOther();
        concurrentProducersPreserveSequenceAndBounds();
        nullInputDoesNotAdvanceSequence();
        fullFilesSurviveVisualBufferOverflow();
        longRecordIsCompleteInFileButTruncatedInViewerSource();
        exceptionRemainsInFileAndTextSummary();
        viewerReadsManagerAndCabinEvents();
        viewerDoesNotStealControllerExecutionEvents();
        viewerPreservesHistoryAcrossReset();
        clearDoesNotDeleteFilesOrSharedBuffer();
        clearAdvancesCursorAndAllowsNewEvents();
        oneRefreshReadsAtMostTwoHundredRecords();
        visibleLineCountIsBounded();
        visibleDocumentCharacterCountIsBounded();
        bufferGapsAreReportedInViewer();
        viewerRefreshDoesNotDuplicateRecords();
        viewerWithoutSessionHasUsefulPlaceholder();
        controllerWithoutLoggingIsSupported();
        rebindingDoesNotMixSessions();
        followCanBeDisabledWithoutPausingLogging();
        viewerRejectsOffEdtAccess();
        manyCabinsCanBeSelectedDirectly();
        invalidCabinSelectionIsRejected();
        initialWindowFitsSmallDesktop();
        initialWindowFitsLaptopDesktop();
        initialWindowSupportsNegativeDesktopCoordinates();
        minimumWindowFitsTinyDesktop();
        invalidDesktopAreaIsRejected();
        composedWindowReceivesRealLoggingEvents();
        viewerStillContainsTerminationEventsAfterClose();
        closedLoggingCanStillBeInspectedWithoutIO();
        System.out.println("T51: 40 escenarios satisfactorios.");
    }
}
```

## src/test/java/com/elevatormanager/ui/EventViewerTest.java

```java
package com.elevatormanager.ui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Adaptadores JUnit T51. */
@Timeout(30)
class EventViewerTest {
    @Test void emptyBufferIsReadable() throws Exception {
        EventViewerScenarios.emptyBufferIsReadable();
    }
    @Test void sequencesAreOrderedAndCursorDoesNotRepeat() throws Exception {
        EventViewerScenarios.sequencesAreOrderedAndCursorDoesNotRepeat();
    }
    @Test void recordLimitEvictsOldest() throws Exception {
        EventViewerScenarios.recordLimitEvictsOldest();
    }
    @Test void characterBudgetEvictsEvenBeforeCountLimit() throws Exception {
        EventViewerScenarios.characterBudgetEvictsEvenBeforeCountLimit();
    }
    @Test void messageAndMetadataAreBounded() throws Exception {
        EventViewerScenarios.messageAndMetadataAreBounded();
    }
    @Test void embeddedNewlinesCannotCreateUnlimitedVisualLines() throws Exception {
        EventViewerScenarios.embeddedNewlinesCannotCreateUnlimitedVisualLines();
    }
    @Test void batchResultsAreImmutable() throws Exception {
        EventViewerScenarios.batchResultsAreImmutable();
    }
    @Test void invalidCursorAndBatchAreRejected() throws Exception {
        EventViewerScenarios.invalidCursorAndBatchAreRejected();
    }
    @Test void skippedRecordsAreCountedOnce() throws Exception {
        EventViewerScenarios.skippedRecordsAreCountedOnce();
    }
    @Test void independentReadersDoNotConsumeEachOther() throws Exception {
        EventViewerScenarios.independentReadersDoNotConsumeEachOther();
    }
    @Test void concurrentProducersPreserveSequenceAndBounds() throws Exception {
        EventViewerScenarios.concurrentProducersPreserveSequenceAndBounds();
    }
    @Test void nullInputDoesNotAdvanceSequence() throws Exception {
        EventViewerScenarios.nullInputDoesNotAdvanceSequence();
    }
    @Test void fullFilesSurviveVisualBufferOverflow() throws Exception {
        EventViewerScenarios.fullFilesSurviveVisualBufferOverflow();
    }
    @Test void longRecordIsCompleteInFileButTruncatedInViewerSource() throws Exception {
        EventViewerScenarios.longRecordIsCompleteInFileButTruncatedInViewerSource();
    }
    @Test void exceptionRemainsInFileAndTextSummary() throws Exception {
        EventViewerScenarios.exceptionRemainsInFileAndTextSummary();
    }
    @Test void viewerReadsManagerAndCabinEvents() throws Exception {
        EventViewerScenarios.viewerReadsManagerAndCabinEvents();
    }
    @Test void viewerDoesNotStealControllerExecutionEvents() throws Exception {
        EventViewerScenarios.viewerDoesNotStealControllerExecutionEvents();
    }
    @Test void viewerPreservesHistoryAcrossReset() throws Exception {
        EventViewerScenarios.viewerPreservesHistoryAcrossReset();
    }
    @Test void clearDoesNotDeleteFilesOrSharedBuffer() throws Exception {
        EventViewerScenarios.clearDoesNotDeleteFilesOrSharedBuffer();
    }
    @Test void clearAdvancesCursorAndAllowsNewEvents() throws Exception {
        EventViewerScenarios.clearAdvancesCursorAndAllowsNewEvents();
    }
    @Test void oneRefreshReadsAtMostTwoHundredRecords() throws Exception {
        EventViewerScenarios.oneRefreshReadsAtMostTwoHundredRecords();
    }
    @Test void visibleLineCountIsBounded() throws Exception {
        EventViewerScenarios.visibleLineCountIsBounded();
    }
    @Test void visibleDocumentCharacterCountIsBounded() throws Exception {
        EventViewerScenarios.visibleDocumentCharacterCountIsBounded();
    }
    @Test void bufferGapsAreReportedInViewer() throws Exception {
        EventViewerScenarios.bufferGapsAreReportedInViewer();
    }
    @Test void viewerRefreshDoesNotDuplicateRecords() throws Exception {
        EventViewerScenarios.viewerRefreshDoesNotDuplicateRecords();
    }
    @Test void viewerWithoutSessionHasUsefulPlaceholder() throws Exception {
        EventViewerScenarios.viewerWithoutSessionHasUsefulPlaceholder();
    }
    @Test void controllerWithoutLoggingIsSupported() throws Exception {
        EventViewerScenarios.controllerWithoutLoggingIsSupported();
    }
    @Test void rebindingDoesNotMixSessions() throws Exception {
        EventViewerScenarios.rebindingDoesNotMixSessions();
    }
    @Test void followCanBeDisabledWithoutPausingLogging() throws Exception {
        EventViewerScenarios.followCanBeDisabledWithoutPausingLogging();
    }
    @Test void viewerRejectsOffEdtAccess() throws Exception {
        EventViewerScenarios.viewerRejectsOffEdtAccess();
    }
    @Test void manyCabinsCanBeSelectedDirectly() throws Exception {
        EventViewerScenarios.manyCabinsCanBeSelectedDirectly();
    }
    @Test void invalidCabinSelectionIsRejected() throws Exception {
        EventViewerScenarios.invalidCabinSelectionIsRejected();
    }
    @Test void initialWindowFitsSmallDesktop() throws Exception {
        EventViewerScenarios.initialWindowFitsSmallDesktop();
    }
    @Test void initialWindowFitsLaptopDesktop() throws Exception {
        EventViewerScenarios.initialWindowFitsLaptopDesktop();
    }
    @Test void initialWindowSupportsNegativeDesktopCoordinates() throws Exception {
        EventViewerScenarios.initialWindowSupportsNegativeDesktopCoordinates();
    }
    @Test void minimumWindowFitsTinyDesktop() throws Exception {
        EventViewerScenarios.minimumWindowFitsTinyDesktop();
    }
    @Test void invalidDesktopAreaIsRejected() throws Exception {
        EventViewerScenarios.invalidDesktopAreaIsRejected();
    }
    @Test void composedWindowReceivesRealLoggingEvents() throws Exception {
        EventViewerScenarios.composedWindowReceivesRealLoggingEvents();
    }
    @Test void viewerStillContainsTerminationEventsAfterClose() throws Exception {
        EventViewerScenarios.viewerStillContainsTerminationEventsAfterClose();
    }
    @Test void closedLoggingCanStillBeInspectedWithoutIO() throws Exception {
        EventViewerScenarios.closedLoggingCanStillBeInspectedWithoutIO();
    }
}
```
