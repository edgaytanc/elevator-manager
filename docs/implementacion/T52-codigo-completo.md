# T52 — Código completo

Archivos Java nuevos o modificados, íntegros. El ZIP contiene además todos los fuentes previos.

## src/main/java/com/elevatormanager/ui/RefreshGate.java

```java
package com.elevatormanager.ui;

import java.util.Objects;
import java.util.function.LongSupplier;

/** T52: frecuencia monotónica, sin sleeps ni trabajo acumulado para recuperar ticks perdidos. */
final class RefreshGate {
    private final long interval;
    private final LongSupplier clock;
    private boolean started;
    private long previous;
    RefreshGate(long interval, LongSupplier clock) {
        if (interval < 1) throw new IllegalArgumentException("Intervalo no positivo.");
        this.interval = interval; this.clock = Objects.requireNonNull(clock, "clock");
    }
    boolean due() {
        long now = clock.getAsLong();
        if (started && now - previous < interval) return false;
        started = true; previous = now; return true;
    }
}
```

## src/main/java/com/elevatormanager/ui/UiSnapshotPump.java

```java
package com.elevatormanager.ui;

import com.elevatormanager.dispatch.HallCallSnapshot;
import com.elevatormanager.model.ElevatorSnapshot;
import com.elevatormanager.simulation.SimulationController;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import javax.swing.SwingUtilities;

/** T52: captura fuera del EDT, una tarea en curso y una sola publicación reemplazable. */
public final class UiSnapshotPump {
    public static final long INTERVAL_NANOS = 100_000_000L;
    public record Frame(UUID sessionId, long epoch, List<ElevatorSnapshot> snapshots,
            Map<Integer, List<HallCallSnapshot>> assigned) {
        public Frame {
            Objects.requireNonNull(sessionId, "sessionId"); snapshots = List.copyOf(snapshots);
            var copy = new HashMap<Integer, List<HallCallSnapshot>>();
            assigned.forEach((id, calls) -> copy.put(id, List.copyOf(calls)));
            assigned = Map.copyOf(copy);
        }
    }
    private final Supplier<Frame> capture;
    private final RefreshGate gate;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(task -> new Thread(task, "ui-snapshot-capture"));
    private final AtomicReference<Frame> latest = new AtomicReference<>();
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private boolean inFlight;
    private boolean closed;
    private CompletableFuture<Void> termination;

    public UiSnapshotPump(SimulationController controller) { this(() -> capture(controller), System::nanoTime); }
    UiSnapshotPump(Supplier<Frame> capture, LongSupplier clock) {
        this.capture = Objects.requireNonNull(capture, "capture");
        gate = new RefreshGate(INTERVAL_NANOS, clock);
    }

    public synchronized void request() {
        if (closed || inFlight || !gate.due()) return;
        inFlight = true;
        executor.execute(() -> {
            try {
                if (SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Captura en EDT.");
                Frame frame = capture.get();
                synchronized (this) {
                    if (!closed && frame != null) { latest.set(frame); failure.set(null); }
                }
            } catch (RuntimeException | Error error) { failure.set(error); }
            finally { synchronized (this) { inFlight = false; } }
        });
    }

    public Optional<Frame> latest() { return Optional.ofNullable(latest.get()); }
    public Optional<Throwable> failure() { return Optional.ofNullable(failure.get()); }

    static Frame capture(SimulationController controller) {
        if (SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Agrupar snapshots fuera del EDT.");
        long epoch = controller.epoch();
        var snapshots = controller.snapshots();
        var assigned = new HashMap<Integer, List<HallCallSnapshot>>();
        controller.calls().values().forEach(call -> {
            if (call.status() == HallCallSnapshot.Status.DISPATCHED || call.status() == HallCallSnapshot.Status.ASSIGNED
                    || call.status() == HallCallSnapshot.Status.UNCONFIRMED) {
                call.command().ifPresent(command -> assigned.computeIfAbsent(command.elevatorId(), id -> new ArrayList<>()).add(call));
            }
        });
        // Captura por cabina, no instante global atómico. Un reset concurrente descarta el conjunto.
        return epoch == controller.epoch() ? new Frame(controller.sessionId(), epoch, snapshots, assigned) : null;
    }

    /** No espera al trabajador en el llamador; una captura tardía no vuelve a publicarse. */
    public synchronized CompletableFuture<Void> closeAsync() {
        if (termination != null) return termination.copy();
        closed = true; latest.set(null); executor.shutdownNow();
        termination = CompletableFuture.runAsync(() -> {
            try { while (!executor.awaitTermination(1, TimeUnit.SECONDS)) { /* Fuera del EDT. */ } }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new CompletionException(error); }
        });
        return termination.copy();
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
    /** Lectura O(1) de una instantánea ya publicada, sin recorrer llamadas ni cabinas. */
    public ElevatorSnapshot snapshot(int elevatorId) {
        if (elevatorId < 1 || elevatorId > elevators.size()) throw new IllegalArgumentException("Cabina fuera de rango.");
        return elevators.get(elevatorId - 1).snapshot();
    }

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

## src/main/java/com/elevatormanager/ui/CabinPanel.java

```java
package com.elevatormanager.ui;

import com.elevatormanager.dispatch.HallCallSnapshot;
import com.elevatormanager.model.Direction;
import com.elevatormanager.model.ElevatorPhase;
import com.elevatormanager.model.ElevatorSnapshot;
import com.elevatormanager.model.SimulationState;
import com.elevatormanager.model.request.CabinRequest;
import com.elevatormanager.simulation.SimulationController;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;
import javax.swing.*;

/** T50: una cabina real, sin modificar agendas desde Swing ni encender por mero envío. */
public final class CabinPanel extends JPanel {
    private static final long serialVersionUID = 1L;
    static final int PAGE_SIZE = 20;
    private final SimulationController session;
    private final int cabinId;
    private final Consumer<Runnable> postToEdt;
    private final JLabel location = new JLabel();
    private final JLabel direction = new JLabel();
    private final JLabel phase = new JLabel();
    private final JTextArea internal = textArea(2);
    private final JTextArea feedback = textArea(2);
    private final DefaultListModel<String> external = new DefaultListModel<>();
    private final JPanel destinations = new JPanel(new GridLayout(0, 4, 6, 6));
    private final Map<Integer, JButton> buttons = new LinkedHashMap<>();
    private final Set<Integer> lit = new TreeSet<>();
    private final int pageCount;
    private final JSpinner page;
    private final JButton previous = new JButton("Anterior");
    private final JButton next = new JButton("Siguiente");
    private boolean available;
    private long epoch = -1;
    private long sequence;
    private ElevatorSnapshot latest;
    private ElevatorSnapshot rendered;
    private List<HallCallSnapshot> renderedAssigned = List.of();
    private SimulationState renderedState;
    private boolean renderedAvailable;

    public CabinPanel(SimulationController session, int cabinId) { this(session, cabinId, SwingUtilities::invokeLater); }

    CabinPanel(SimulationController session, int cabinId, Consumer<Runnable> postToEdt) {
        requireEdt();
        this.session = Objects.requireNonNull(session, "session");
        if (cabinId < 1 || cabinId > session.config().elevatorCount()) { throw new IllegalArgumentException("Cabina inexistente."); }
        this.cabinId = cabinId;
        this.postToEdt = Objects.requireNonNull(postToEdt, "postToEdt");
        pageCount = (session.config().floorCount() - 1) / PAGE_SIZE + 1;
        page = new JSpinner(new SpinnerNumberModel(1, 1, pageCount, 1));
        page.setEditor(new JSpinner.NumberEditor(page, "0"));
        page.getAccessibleContext().setAccessibleName("Página de destinos del elevador " + cabinId);
        setLayout(new BorderLayout(8, 8));
        setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        var header = new JPanel(new GridLayout(0, 1, 0, 4));
        header.add(new JLabel("Elevador " + cabinId));
        header.add(location); header.add(direction); header.add(phase);
        add(header, BorderLayout.NORTH);
        var body = new JPanel(); body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        var navigation = new JPanel(new FlowLayout(FlowLayout.LEFT));
        navigation.add(new JLabel("Página de pisos:")); navigation.add(page); navigation.add(previous); navigation.add(next);
        navigation.setAlignmentX(0f);
        navigation.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
        body.add(navigation);
        destinations.setAlignmentX(0f);
        destinations.setBorder(BorderFactory.createTitledBorder("Destinos internos — amarillo = pendiente"));
        body.add(destinations);
        var internalLabel = new JLabel("Destinos internos pendientes (todos los pisos):");
        internalLabel.setAlignmentX(0f); body.add(internalLabel);
        var internalScroll = new JScrollPane(internal);
        internalScroll.setAlignmentX(0f);
        internalScroll.setPreferredSize(new Dimension(380, 60));
        internalScroll.setMaximumSize(new Dimension(Integer.MAX_VALUE, 80));
        body.add(internalScroll);
        var externalLabel = new JLabel("Llamadas externas asignadas — piso y sentido:");
        externalLabel.setAlignmentX(0f); body.add(externalLabel);
        var list = new JList<>(external); list.setVisibleRowCount(4);
        list.getAccessibleContext().setAccessibleName("Llamadas externas del elevador " + cabinId);
        var externalScroll = new JScrollPane(list);
        externalScroll.setAlignmentX(0f);
        externalScroll.setPreferredSize(new Dimension(380, 90));
        body.add(externalScroll);
        add(body, BorderLayout.CENTER);
        var feedbackScroll = new JScrollPane(feedback);
        feedbackScroll.setPreferredSize(new Dimension(380, 54));
        add(feedbackScroll, BorderLayout.SOUTH);
        page.addChangeListener(event -> rebuildButtons());
        previous.addActionListener(event -> page.setValue(((Number) page.getValue()).intValue() - 1));
        next.addActionListener(event -> page.setValue(((Number) page.getValue()).intValue() + 1));
        rebuildButtons();
        message("Pulsa Start para habilitar los destinos internos.");
    }

    private static JTextArea textArea(int rows) {
        var area = new JTextArea(rows, 32);
        area.setEditable(false); area.setLineWrap(true); area.setWrapStyleWord(true);
        area.setFont(UIManager.getFont("Label.font"));
        return area;
    }

    private void rebuildButtons() {
        requireEdt();
        int selectedPage = ((Number) page.getValue()).intValue();
        if (selectedPage < 1 || selectedPage > pageCount) { return; }
        destinations.removeAll(); buttons.clear();
        long first = (long) (selectedPage - 1) * PAGE_SIZE + 1;
        long last = Math.min(session.config().floorCount(), first + PAGE_SIZE - 1);
        for (long value = first; value <= last; value++) {
            int floor = (int) value;
            var button = new JButton(Integer.toString(floor));
            button.getAccessibleContext().setAccessibleName("Elevador " + cabinId + ", destino piso " + floor);
            button.addActionListener(event -> submit(floor));
            buttons.put(floor, button); destinations.add(button);
        }
        previous.setEnabled(selectedPage > 1); next.setEnabled(selectedPage < pageCount);
        destinations.setMaximumSize(new Dimension(Integer.MAX_VALUE, destinations.getPreferredSize().height));
        paintButtons(); destinations.revalidate(); destinations.repaint();
    }

    /** Snapshot local y llamadas globales ya agrupadas para esta cabina. No espera trabajo. */
    public void render(ElevatorSnapshot snapshot, List<HallCallSnapshot> assigned, boolean enabled) {
        requireEdt();
        Objects.requireNonNull(snapshot, "snapshot"); Objects.requireNonNull(assigned, "assigned");
        if (snapshot.id() != cabinId || !snapshot.config().equals(session.config())) {
            throw new IllegalArgumentException("Snapshot de otra cabina o configuración.");
        }
        long currentEpoch = session.epoch();
        boolean canSubmit = enabled && session.state() == SimulationState.RUNNING;
        if (epoch != currentEpoch || available != canSubmit) {
            ++sequence;
            if (epoch != currentEpoch) { latest = null; lit.clear(); external.clear(); }
            epoch = currentEpoch;
            available = canSubmit;
            message(canSubmit ? "Selecciona un destino interno para esta cabina."
                    : "Destinos bloqueados mientras el sistema está en " + session.state() + ".");
        }
        if (snapshot.epoch() == epoch && (latest == null || snapshot.revision() >= latest.revision())) { latest = snapshot; }
        if (latest != null && latest == rendered && renderedAssigned.equals(assigned)
                && renderedState == session.state() && renderedAvailable == available) return;
        rendered = latest; renderedAssigned = List.copyOf(assigned);
        renderedState = session.state(); renderedAvailable = available;
        lit.clear();
        if (latest == null) {
            location.setText("Esperando posición de la época actual...");
            direction.setText("Dirección: pendiente de actualización");
            phase.setText("Estado del sistema: " + session.state());
            internal.setText("Ninguno"); external.clear(); paintButtons(); return;
        }
        location.setText("Piso actual: " + latest.currentFloor() + latest.nextFloor().stream()
                .mapToObj(value -> " | Siguiente: " + value).findFirst().orElse(""));
        direction.setText("Dirección: " + (latest.direction() == Direction.UP ? "Arriba" : "Abajo"));
        phase.setText("Estado: " + session.state() + " / " + switch (latest.phase()) {
            case IDLE -> "En espera";
            case MOVING -> "En movimiento";
            case DWELLING -> "Parada de servicio";
            case HELD -> "Detenida";
            case TERMINATED -> "Terminada";
        } + (latest.mode() == ElevatorSnapshot.Mode.RESET_RETURN ? " — Retorno por Reset" : ""));
        if (latest.phase() != ElevatorPhase.TERMINATED && session.state() != SimulationState.CLOSED) {
            latest.cabinRequests().forEach(request -> lit.add(request.getFloor()));
        }
        internal.setText(lit.isEmpty() ? "Ninguno" : lit.toString()); internal.setCaretPosition(0);
        record Key(int floor, Direction direction) { }
        var calls = new TreeSet<Key>(Comparator.comparingInt(Key::floor).thenComparing(Key::direction));
        if (latest.phase() != ElevatorPhase.TERMINATED && session.state() != SimulationState.CLOSED) {
            latest.hallRequests().forEach(request -> calls.add(new Key(request.getFloor(), request.getDirection())));
            for (var call : assigned) {
                if (call.request().getEpoch() == epoch && call.command().isPresent()
                        && call.command().orElseThrow().elevatorId() == cabinId
                        && (call.status() == HallCallSnapshot.Status.DISPATCHED || call.status() == HallCallSnapshot.Status.ASSIGNED
                        || call.status() == HallCallSnapshot.Status.UNCONFIRMED)) {
                    calls.add(new Key(call.request().getFloor(), call.request().getDirection()));
                }
            }
        }
        external.clear();
        calls.forEach(call -> external.addElement("Piso " + call.floor() + " — "
                + (call.direction() == Direction.UP ? "Subir" : "Bajar")));
        paintButtons();
    }

    /** Bloqueo inmediato: no necesita recapturar ni ordenar datos de la simulación. */
    public void setInteractive(boolean enabled) {
        requireEdt();
        boolean value = enabled && session.state() == SimulationState.RUNNING && epoch == session.epoch();
        if (available != value) {
            available = value; ++sequence;
            message(value ? "Selecciona un destino interno para esta cabina."
                    : "Destinos bloqueados mientras el sistema está en " + session.state() + ".");
            paintButtons();
        }
    }

    private void paintButtons() {
        buttons.forEach((floor, button) -> {
            boolean pending = lit.contains(floor);
            button.setSelected(pending);
            button.setOpaque(true);
            button.setBackground(pending ? new Color(255, 214, 92) : UIManager.getColor("Button.background"));
            button.setText(Integer.toString(floor) + (pending ? " ●" : ""));
            button.setToolTipText(pending ? "Destino pendiente de atención" : "Solicitar destino piso " + floor);
            button.setEnabled(available);
        });
    }

    private void submit(int floor) {
        requireEdt();
        if (!available || session.state() != SimulationState.RUNNING || epoch != session.epoch()) { return; }
        var request = new CabinRequest(UUID.randomUUID(), epoch, floor, Instant.now(), cabinId);
        long token = ++sequence;
        message("Piso " + floor + ": enviando destino interno...");
        session.submit(request).whenComplete((result, error) -> postToEdt.accept(() -> {
            requireEdt();
            if (token != sequence || !available || session.state() != SimulationState.RUNNING
                    || request.getEpoch() != session.epoch()) { return; }
            if (error != null) {
                Throwable cause = error;
                while (cause instanceof CompletionException && cause.getCause() != null) { cause = cause.getCause(); }
                message("No se pudo enviar el destino: " + cause.getMessage());
            } else if (session.isCurrent(result)) {
                message(result.outcome() == SimulationController.Outcome.ENQUEUED
                        ? "Piso " + floor + ": destino enviado; la luz refleja la agenda confirmada."
                        : "Destino rechazado: " + result.detail());
            }
        }));
    }

    private void message(String text) { feedback.setText(text.length() > 1000 ? text.substring(0, 1000) : text); feedback.setCaretPosition(0); }
    JButton destinationButton(int floor) { requireEdt(); return Objects.requireNonNull(buttons.get(floor), "Piso fuera de la página actual"); }
    void showPage(int number) { requireEdt(); if (number < 1 || number > pageCount) throw new IllegalArgumentException("Página inválida."); page.setValue(number); }
    Set<Integer> litFloors() { requireEdt(); return Set.copyOf(lit); }
    List<String> externalCalls() { requireEdt(); return Collections.list(external.elements()); }
    String locationText() { requireEdt(); return location.getText(); }
    String phaseText() { requireEdt(); return phase.getText(); }
    String directionText() { requireEdt(); return direction.getText(); }
    String feedbackText() { requireEdt(); return feedback.getText(); }
    int visibleButtonCount() { requireEdt(); return buttons.size(); }
    private static void requireEdt() {
        if (!SwingUtilities.isEventDispatchThread()) { throw new IllegalStateException("Los paneles de cabina requieren EDT."); }
    }
}
```

## src/main/java/com/elevatormanager/ui/CabinDashboardPanel.java

```java
package com.elevatormanager.ui;

import com.elevatormanager.simulation.SimulationController;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.util.*;
import javax.swing.*;

/** T52: pestañas para N cabinas, con hasta tres paneles completos en memoria. */
public final class CabinDashboardPanel extends JPanel {
    private static final long serialVersionUID = 1L;
    static final int MAX_CACHED_PANELS = 3;
    private final JTabbedPane tabs = new JTabbedPane(JTabbedPane.TOP, JTabbedPane.SCROLL_TAB_LAYOUT);
    private final LinkedHashMap<Integer, CabinPanel> cabins = new LinkedHashMap<>(4, 0.75f, true);
    private SimulationController controller;
    private JSpinner selector;
    private final JLabel navigationStatus = new JLabel();
    private UiSnapshotPump pump;
    private boolean interactive;
    private boolean binding;

    public CabinDashboardPanel() {
        requireEdt(); setLayout(new BorderLayout());
        setBorder(BorderFactory.createTitledBorder("Paneles de cabina"));
        setPreferredSize(new Dimension(470, 470)); setMinimumSize(new Dimension(330, 250));
        add(new JLabel("Valida la configuración para crear las cabinas."), BorderLayout.CENTER);
        tabs.addChangeListener(event -> {
            if (!binding && controller != null && tabs.getSelectedIndex() >= 0) {
                selector.setValue(tabs.getSelectedIndex() + 1); refresh(interactive);
            }
        });
    }

    public void bind(SimulationController session) {
        requireEdt(); binding = true;
        try {
            controller = Objects.requireNonNull(session, "session"); pump = null;
            removeAll(); tabs.removeAll(); cabins.clear();
            for (int id = 1; id <= session.config().elevatorCount(); id++) {
                tabs.addTab("Elevador " + id, new JPanel());
            }
            selector = new JSpinner(new SpinnerNumberModel(1, 1, session.config().elevatorCount(), 1));
            selector.setEditor(new JSpinner.NumberEditor(selector, "0"));
            selector.getAccessibleContext().setAccessibleName("Número de cabina a inspeccionar");
            var show = new JButton("Ver cabina"); show.addActionListener(event -> selectTypedCabin());
            var navigation = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT));
            navigation.add(new JLabel("Cabina:")); navigation.add(selector); navigation.add(show);
            navigationStatus.setText(session.config().elevatorCount() + " cabinas"); navigation.add(navigationStatus);
            add(navigation, BorderLayout.NORTH); add(tabs, BorderLayout.CENTER);
        } finally { binding = false; }
        refresh(false); revalidate(); repaint();
    }

    public void usePump(UiSnapshotPump source) { requireEdt(); pump = Objects.requireNonNull(source); }

    /** Solo la cabina visible se renderiza; la agregación global pertenece al trabajador. */
    public void refresh(boolean enabled) {
        requireEdt(); if (controller == null) return;
        setInteractive(enabled);
        int id = tabs.getSelectedIndex() + 1;
        var panel = ensurePanel(id);
        var frame = pump == null ? Optional.<UiSnapshotPump.Frame>empty() : pump.latest();
        if (frame.isPresent() && frame.orElseThrow().sessionId().equals(controller.sessionId())
                && frame.orElseThrow().epoch() == controller.epoch()) {
            var data = frame.orElseThrow();
            panel.render(data.snapshots().get(id - 1), data.assigned().getOrDefault(id, List.of()), enabled);
        } else {
            // Sin conjunto vigente, solo posición/agenda local O(1); nunca recorre el historial global en EDT.
            panel.render(controller.snapshot(id), List.of(), enabled);
        }
    }

    public void setInteractive(boolean enabled) {
        requireEdt(); interactive = enabled;
        for (var panel : cabins.values()) panel.setInteractive(enabled);
    }

    private CabinPanel ensurePanel(int id) {
        var existing = cabins.get(id); if (existing != null) return existing;
        var panel = new CabinPanel(controller, id); cabins.put(id, panel);
        panel.render(controller.snapshot(id), List.of(), interactive);
        tabs.setComponentAt(id - 1, new JScrollPane(panel));
        if (cabins.size() > MAX_CACHED_PANELS) {
            var iterator = cabins.entrySet().iterator();
            while (iterator.hasNext()) {
                var entry = iterator.next();
                if (entry.getKey() != tabs.getSelectedIndex() + 1) {
                    int removed = entry.getKey(); iterator.remove(); tabs.setComponentAt(removed - 1, new JPanel()); break;
                }
            }
        }
        return panel;
    }

    private void selectTypedCabin() {
        requireEdt();
        try {
            int id = Integer.parseInt(((JSpinner.NumberEditor) selector.getEditor()).getTextField().getText().strip());
            selectCabin(id); navigationStatus.setText(controller.config().elevatorCount() + " cabinas");
        } catch (IllegalArgumentException error) { navigationStatus.setText("Indica una cabina entre 1 y " + controller.config().elevatorCount() + "."); }
    }
    void selectCabin(int id) {
        requireEdt(); if (id < 1 || id > cabinCount()) throw new IllegalArgumentException("Cabina fuera de rango.");
        selector.setValue(id); tabs.setSelectedIndex(id - 1); refresh(interactive);
    }
    int selectedCabin() { requireEdt(); return tabs.getSelectedIndex() + 1; }
    CabinPanel cabin(int id) {
        requireEdt(); if (id < 1 || id > cabinCount()) throw new IllegalArgumentException("Cabina fuera de rango.");
        return ensurePanel(id);
    }
    int cachedPanelCount() { requireEdt(); return cabins.size(); }
    int cabinCount() { requireEdt(); return controller == null ? 0 : controller.config().elevatorCount(); }
    private static void requireEdt() {
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("El conjunto de cabinas requiere EDT.");
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

/** T52: captura asíncrona y refresco visual limitado; ningún get/join, lectura o apertura en EDT. */
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
    private final java.util.function.Function<SimulationController, UiSnapshotPump> pumpFactory;
    private final Supplier<Optional<Path>> selector;
    private final Timer refresh;
    private final RefreshGate visualGate = new RefreshGate(UiSnapshotPump.INTERVAL_NANOS, System::nanoTime);
    private UiSnapshotPump snapshotPump;
    private Throwable reportedRefreshFailure;
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
        this(opener, selector, UiSnapshotPump::new);
    }

    SystemControlPanel(SessionOpener opener, Supplier<Optional<Path>> selector,
            java.util.function.Function<SimulationController, UiSnapshotPump> pumpFactory) {
        requireEdt();
        this.pumpFactory = Objects.requireNonNull(pumpFactory, "pumpFactory");
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
                snapshotPump = Objects.requireNonNull(pumpFactory.apply(session), "snapshotPump");
                cabinDashboard.usePump(snapshotPump); snapshotPump.request();
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
        var samplerClosed = snapshotPump == null ? CompletableFuture.<Void>completedFuture(null) : snapshotPump.closeAsync();
        var engineClosed = opened.thenCompose(session -> session == null ? CompletableFuture.completedFuture(null)
                : session.closeAsync().thenApply(result -> (Void) null));
        return CompletableFuture.allOf(engineClosed, samplerClosed)
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
        cabinDashboard.setInteractive(usable && idle && !loading);
        if (visualGate.due()) {
            if (snapshotPump != null && !closing && !terminal) {
                snapshotPump.request();
                snapshotPump.failure().ifPresent(error -> {
                    if (reportedRefreshFailure != error) {
                        reportedRefreshFailure = error;
                        message("No se pudo actualizar la vista: " + cause(error));
                    }
                });
            }
            cabinDashboard.refresh(usable && idle && !loading);
            eventViewer.refresh();
        }
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
    UiSnapshotPump snapshotPump() { requireEdt(); return snapshotPump; }
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

## src/test/java/com/elevatormanager/simulation/EdtImportFixture.java

```java
package com.elevatormanager.simulation;

import com.elevatormanager.io.RequestFileReader;
import com.elevatormanager.model.SimulationConfig;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.SwingUtilities;

/** T52: retiene la apertura y después usa el lector real; no fabrica su resultado. */
public final class EdtImportFixture {
    private EdtImportFixture() { }
    public static SimulationController create(SimulationConfig config, CountDownLatch entered,
            CountDownLatch release, AtomicBoolean ranOnEdt) {
        return SimulationController.withBatchLoader(config, 0, (path, current) -> {
            ranOnEdt.set(SwingUtilities.isEventDispatchThread()); entered.countDown();
            if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("No se liberó la lectura de prueba.");
            return new RequestFileReader().read(path, current);
        });
    }
}
```

## src/test/java/com/elevatormanager/ui/EdtSafetyScenarios.java

```java
package com.elevatormanager.ui;

import com.elevatormanager.dispatch.HallCallSnapshot;
import com.elevatormanager.model.*;
import com.elevatormanager.model.SimulationConfig.Field;
import com.elevatormanager.model.request.*;
import com.elevatormanager.simulation.*;
import java.awt.Component;
import java.awt.Container;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import javax.swing.*;
import javax.swing.event.*;
import javax.swing.text.JTextComponent;

/** T52: EDT real, actores/lector reales, auditoría de Swing y retenciones deterministas. */
public final class EdtSafetyScenarios {
    private EdtSafetyScenarios() { }
    public static void gateAllowsFirstRefresh() throws Exception {
        var clock = new AtomicLong(0); check(new RefreshGate(100, clock::get).due(), "Primer refresco");
    }

    public static void gateLimitsRepeatedRefreshes() throws Exception {
        var clock = new AtomicLong(0); var gate = new RefreshGate(100, clock::get);
        check(gate.due() && !gate.due(), "Sin repetición inmediata"); clock.set(99); check(!gate.due(), "Antes del límite");
        clock.set(100); check(gate.due(), "Intervalo cumplido");
    }

    public static void gateDoesNotCatchUpMissedTicks() throws Exception {
        var clock = new AtomicLong(); var gate = new RefreshGate(100, clock::get); gate.due();
        clock.set(10000); check(gate.due() && !gate.due(), "No acumula cien trabajos atrasados");
    }

    public static void gateHandlesNanoTimeWrap() throws Exception {
        var clock = new AtomicLong(Long.MAX_VALUE - 50); var gate = new RefreshGate(100, clock::get); gate.due();
        clock.set(Long.MIN_VALUE + 50); check(gate.due(), "Diferencia monotónica con wrap");
    }

    public static void pumpCapturesOutsideEdt() throws Exception {
        try (var rig = new EngineRig()) {
            var onEdt = new AtomicBoolean(true); var pump = new UiSnapshotPump(() -> { onEdt.set(SwingUtilities.isEventDispatchThread()); return UiSnapshotPump.capture(rig.c); }, System::nanoTime);
            try { edt(pump::request); waitFor(() -> pump.latest().isPresent()); check(!onEdt.get(), "Captura fuera de EDT"); }
            finally { pump.closeAsync().get(5, TimeUnit.SECONDS); }
        }
    }

    public static void pumpCoalescesBusyRequests() throws Exception {
        try (var rig = new EngineRig()) {
            var entered = new CountDownLatch(1); var release = new CountDownLatch(1); var count = new AtomicInteger(); var clock = new AtomicLong();
            var pump = new UiSnapshotPump(() -> { count.incrementAndGet(); entered.countDown(); hold(release); return UiSnapshotPump.capture(rig.c); }, clock::get);
            try {
                pump.request(); check(entered.await(5, TimeUnit.SECONDS), "Captura retenida");
                for (int i = 0; i < 10000; i++) { clock.addAndGet(UiSnapshotPump.INTERVAL_NANOS); pump.request(); }
                check(count.get() == 1, "Una tarea en curso, sin cola de diez mil capturas");
            } finally { release.countDown(); pump.closeAsync().get(5, TimeUnit.SECONDS); }
        }
    }

    public static void pumpEnforcesMinimumCaptureInterval() throws Exception {
        try (var rig = new EngineRig()) {
            var clock = new AtomicLong(); var count = new AtomicInteger();
            var pump = new UiSnapshotPump(() -> { count.incrementAndGet(); return UiSnapshotPump.capture(rig.c); }, clock::get);
            try {
                pump.request(); waitFor(() -> pump.latest().isPresent());
                for (int i = 0; i < 100; i++) pump.request(); check(count.get() == 1, "Captura limitada por reloj");
                clock.addAndGet(UiSnapshotPump.INTERVAL_NANOS); waitFor(() -> { pump.request(); return count.get() == 2; });
            } finally { pump.closeAsync().get(5, TimeUnit.SECONDS); }
        }
    }

    public static void latestFrameIsReplacedAfterReset() throws Exception {
        try (var rig = new EngineRig()) {
            var clock = new AtomicLong(); var pump = new UiSnapshotPump(() -> UiSnapshotPump.capture(rig.c), clock::get);
            try {
                pump.request(); waitFor(() -> pump.latest().isPresent()); var first = pump.latest().orElseThrow();
                rig.c.reset().get(5, TimeUnit.SECONDS); clock.addAndGet(UiSnapshotPump.INTERVAL_NANOS); pump.request();
                waitFor(() -> pump.latest().orElseThrow().epoch() == 1);
                check(pump.latest().orElseThrow() != first && first.epoch() == 0, "Publicación reemplazada, copia anterior inmutable");
            } finally { pump.closeAsync().get(5, TimeUnit.SECONDS); }
        }
    }

    public static void frameCollectionsAreDeeplyImmutable() throws Exception {
        try (var rig = new EngineRig()) {
            rig.c.start().get(5, TimeUnit.SECONDS);
            rig.c.submit(new HallRequest(UUID.randomUUID(), 0, 7, Instant.now(), Direction.UP)).get(5, TimeUnit.SECONDS);
            waitFor(() -> rig.c.calls().values().stream().anyMatch(c -> c.status() == HallCallSnapshot.Status.ASSIGNED));
            var frame = UiSnapshotPump.capture(rig.c);
            try { frame.snapshots().clear(); throw new AssertionError("Snapshots mutables"); } catch (UnsupportedOperationException expected) { }
            try { frame.assigned().clear(); throw new AssertionError("Mapa mutable"); } catch (UnsupportedOperationException expected) { }
            try { frame.assigned().values().iterator().next().clear(); throw new AssertionError("Lista mutable"); } catch (UnsupportedOperationException expected) { }
        }
    }

    public static void captureExcludesServedHistory() throws Exception {
        try (var rig = new EngineRig()) {
            rig.c.start().get(5, TimeUnit.SECONDS);
            rig.c.submit(new HallRequest(UUID.randomUUID(), 0, 1, Instant.now(), Direction.UP)).get(5, TimeUnit.SECONDS);
            waitFor(() -> !rig.c.calls().isEmpty() && rig.c.calls().values().stream().allMatch(c -> c.status() == HallCallSnapshot.Status.SERVED));
            check(UiSnapshotPump.capture(rig.c).assigned().isEmpty() && !rig.c.calls().isEmpty(), "Historial servido no viaja a Swing");
        }
    }

    public static void dashboardRejectsFrameFromOldEpoch() throws Exception {
        try (var rig = new EngineRig()) {
            var saved = UiSnapshotPump.capture(rig.c); var pump = new UiSnapshotPump(() -> saved, System::nanoTime);
            try {
                pump.request(); waitFor(() -> pump.latest().isPresent()); rig.c.reset().get(5, TimeUnit.SECONDS);
                edt(() -> { var dashboard = new CabinDashboardPanel(); dashboard.bind(rig.c); dashboard.usePump(pump); dashboard.refresh(true);
                    check(dashboard.cabin(1).litFloors().isEmpty() && dashboard.cabin(1).locationText().contains("Piso actual: 1"), "No se aplica conjunto de época anterior"); });
            } finally { pump.closeAsync().get(5, TimeUnit.SECONDS); }
        }
    }

    public static void dashboardRejectsFrameFromAnotherSession() throws Exception {
        try (var first = new EngineRig(); var second = new EngineRig()) {
            first.c.start().get(5, TimeUnit.SECONDS);
            first.c.submit(new CabinRequest(UUID.randomUUID(), 0, 7, Instant.now(), 1)).get(5, TimeUnit.SECONDS);
            waitFor(() -> !first.c.snapshot(1).cabinRequests().isEmpty());
            var saved = UiSnapshotPump.capture(first.c); var pump = new UiSnapshotPump(() -> saved, System::nanoTime);
            try {
                pump.request(); waitFor(() -> pump.latest().isPresent());
                edt(() -> { var dashboard = new CabinDashboardPanel(); dashboard.bind(second.c); dashboard.usePump(pump); dashboard.refresh(true);
                    check(dashboard.cabinCount() == 2 && dashboard.cabin(1).litFloors().isEmpty(), "No mezcla sesiones"); });
            } finally { pump.closeAsync().get(5, TimeUnit.SECONDS); }
        }
    }

    public static void captureFailureCanRecover() throws Exception {
        try (var rig = new EngineRig()) {
            var clock = new AtomicLong(); var attempt = new AtomicInteger();
            var pump = new UiSnapshotPump(() -> { if (attempt.incrementAndGet() == 1) throw new IllegalStateException("captura inyectada"); return UiSnapshotPump.capture(rig.c); }, clock::get);
            try {
                pump.request(); waitFor(() -> pump.failure().isPresent());
                clock.addAndGet(UiSnapshotPump.INTERVAL_NANOS); waitFor(() -> { pump.request(); return pump.latest().isPresent(); });
                check(pump.failure().isEmpty(), "Recupera publicación sin tocar Swing desde capturador");
            } finally { pump.closeAsync().get(5, TimeUnit.SECONDS); }
        }
    }

    public static void closedPumpCannotPublishLateFrame() throws Exception {
        try (var rig = new EngineRig()) {
            var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
            var pump = new UiSnapshotPump(() -> { entered.countDown(); hold(release); return UiSnapshotPump.capture(rig.c); }, System::nanoTime);
            try {
                pump.request(); check(entered.await(5, TimeUnit.SECONDS), "Captura activa"); var done = pump.closeAsync(); release.countDown(); done.get(5, TimeUnit.SECONDS);
                pump.request(); check(pump.latest().isEmpty(), "Publicación tardía descartada y nuevas solicitudes ignoradas");
            } finally { release.countDown(); pump.closeAsync().get(5, TimeUnit.SECONDS); }
        }
    }

    public static void closeWaitsForRealCaptureTerminationWithoutBlockingEdt() throws Exception {
        try (var rig = new EngineRig()) {
            var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
            var pump = new UiSnapshotPump(() -> { entered.countDown(); hold(release); return UiSnapshotPump.capture(rig.c); }, System::nanoTime);
            try {
                pump.request(); check(entered.await(5, TimeUnit.SECONDS), "Captura activa"); var done = onEdt(pump::closeAsync);
                check(!done.isDone(), "No anuncia cierre antes de terminar"); pulses(4); release.countDown(); done.get(5, TimeUnit.SECONDS);
            } finally { release.countDown(); pump.closeAsync().get(5, TimeUnit.SECONDS); }
        }
    }

    public static void closeIsIdempotent() throws Exception {
        try (var rig = new EngineRig()) {
            var pump = new UiSnapshotPump(rig.c); pump.request();
            var first = pump.closeAsync(); var second = pump.closeAsync(); first.get(5, TimeUnit.SECONDS); second.get(5, TimeUnit.SECONDS);
            check(first.isDone() && second.isDone() && pump.latest().isEmpty(), "Un cierre real y recibos independientes");
        }
    }

    public static void constantTimeSnapshotValidatesCabinId() throws Exception {
        try (var rig = new EngineRig()) {
            check(rig.c.snapshot(2).id() == 2, "Snapshot directo");
            for (int id : new int[] {0, 3}) try { rig.c.snapshot(id); throw new AssertionError("Cabina inválida"); } catch (IllegalArgumentException expected) { }
        }
    }

    public static void dashboardCachesAtMostThreePanels() throws Exception {
        var c = new SimulationController(new SimulationConfig(30, 25, 20, 10), 0);
        try {
            edt(() -> { var dashboard = new CabinDashboardPanel(); dashboard.bind(c);
                for (int id = 1; id <= 30; id++) { dashboard.selectCabin(id); check(dashboard.cachedPanelCount() <= 3, "Caché limitada"); }
                check(dashboard.cabinCount() == 30 && dashboard.selectedCabin() == 30, "Las treinta siguen accesibles"); });
        } finally { close(c); }
    }

    public static void selectionRemainsConsistentAfterTabNavigation() throws Exception {
        try (var rig = new EngineRig()) {
            edt(() -> { var dashboard = new CabinDashboardPanel(); dashboard.bind(rig.c); dashboard.selectCabin(2);
                check(dashboard.selectedCabin() == 2 && dashboard.cabin(2).locationText().contains("Piso actual: 1"), "Selección coherente"); });
        }
    }

    public static void evictedCabinRebuildsFromPublishedState() throws Exception {
        var c = new SimulationController(new SimulationConfig(8, 8, 200, 20), 0);
        try {
            c.start().get(5, TimeUnit.SECONDS); c.submit(new CabinRequest(UUID.randomUUID(), 0, 8, Instant.now(), 1)).get(5, TimeUnit.SECONDS);
            waitFor(() -> !c.snapshot(1).cabinRequests().isEmpty());
            edt(() -> { var dashboard = new CabinDashboardPanel(); dashboard.bind(c); dashboard.refresh(true);
                for (int id = 2; id <= 8; id++) dashboard.selectCabin(id);
                dashboard.selectCabin(1); check(dashboard.cabin(1).litFloors().contains(8), "Panel recreado usa agenda vigente"); });
        } finally { close(c); }
    }

    public static void unchangedSnapshotDoesNotRewriteDocuments() throws Exception {
        try (var rig = new EngineRig()) {
            edt(() -> { var p = new CabinPanel(rig.c, 1); var snapshot = rig.c.snapshot(1); p.render(snapshot, List.of(), false);
                var mutations = new AtomicInteger(); watchDocuments(p, mutations);
                for (int i = 0; i < 100; i++) p.render(snapshot, List.of(), false);
                check(mutations.get() == 0, "No reescribe textos sin cambios"); });
        }
    }

    public static void repaintAuditAndPulsesDuringConcurrentMovement() throws Exception {
        try (var audit = new Audit(); var rig = new PanelRig(8, null, null, null)) {
            rig.prepare(); rig.start(); var c = rig.controller();
            for (int id = 1; id <= 8; id++) c.submit(new CabinRequest(UUID.randomUUID(), c.epoch(), 8, Instant.now(), id)).get(5, TimeUnit.SECONDS);
            waitFor(() -> c.snapshots().stream().filter(s -> s.phase() == ElevatorPhase.MOVING).count() == 8);
            pulses(8); edt(() -> { rig.panel.cabinDashboard().selectCabin(8); rig.panel.updateView(); });
            audit.assertClean();
        }
    }

    public static void largeRealImportLeavesEdtAvailable() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1); var readOnEdt = new AtomicBoolean(true);
        try (var audit = new Audit(); var rig = new PanelRig(4, config -> EdtImportFixture.create(config, entered, release, readOnEdt), null, null)) {
            rig.prepare(); rig.largeFile(); rig.click(SystemControlPanel.Action.LOAD);
            check(entered.await(5, TimeUnit.SECONDS), "Lectura retenida"); pulses(6); check(!readOnEdt.get(), "Lector fuera de EDT");
            release.countDown(); waitFor(() -> rig.controller().preparedBatch().map(b -> b.requests().size() == 10000).orElse(false));
            check(rig.controller().state() == SimulationState.RESET, "Importación real sin mover cabinas"); audit.assertClean();
        } finally { release.countDown(); }
    }

    public static void closeDuringImportDoesNotFreezeEdt() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1); var readOnEdt = new AtomicBoolean(true);
        try (var audit = new Audit(); var rig = new PanelRig(2, config -> EdtImportFixture.create(config, entered, release, readOnEdt), null, null)) {
            rig.prepare(); rig.largeFile(); rig.click(SystemControlPanel.Action.LOAD); check(entered.await(5, TimeUnit.SECONDS), "Lectura activa");
            var closed = onEdt(rig.panel::closeAsync); pulses(3); release.countDown(); closed.get(5, TimeUnit.SECONDS); audit.assertClean();
        } finally { release.countDown(); }
    }

    public static void slowResourceCloseLeavesEdtAvailable() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        try (var audit = new Audit(); var rig = new PanelRig(2, null, null, null)) {
            rig.prepare(); rig.start(); rig.controller().registerResource(() -> { entered.countDown(); hold(release); }).get(5, TimeUnit.SECONDS);
            var closed = onEdt(rig.panel::closeAsync); check(entered.await(5, TimeUnit.SECONDS), "Recurso cerrando");
            pulses(5); check(!closed.isDone(), "No dispone antes de liberar recurso"); release.countDown(); closed.get(5, TimeUnit.SECONDS); audit.assertClean();
        } finally { release.countDown(); }
    }

    public static void preparationLeavesEdtAvailable() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1); var openedOnEdt = new AtomicBoolean(true);
        try (var audit = new Audit(); var rig = new PanelRig(2, config -> { openedOnEdt.set(SwingUtilities.isEventDispatchThread()); entered.countDown(); hold(release); return new SimulationController(config, 0); }, null, null)) {
            rig.confirm(); check(entered.await(5, TimeUnit.SECONDS), "Preparador activo"); pulses(5); check(!openedOnEdt.get(), "Apertura fuera de EDT");
            release.countDown(); waitFor(() -> rig.controller() != null); audit.assertClean();
        } finally { release.countDown(); }
    }

    public static void windowCloseWaitsForCaptureAndDropsLatePublication() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        try (var audit = new Audit(); var rig = new PanelRig(2, null, null, c -> new UiSnapshotPump(() -> { entered.countDown(); hold(release); return UiSnapshotPump.capture(c); }, System::nanoTime))) {
            rig.prepare(); check(entered.await(5, TimeUnit.SECONDS), "Capturador activo"); var pump = onEdt(rig.panel::snapshotPump);
            var closed = onEdt(rig.panel::closeAsync); pulses(5); check(!closed.isDone(), "Cierre espera al capturador"); release.countDown(); closed.get(5, TimeUnit.SECONDS);
            check(pump.latest().isEmpty(), "No publica después del cierre"); audit.assertClean();
        } finally { release.countDown(); }
    }

    public static void resetAndLateReceiptsNeverMutateSwingOffEdt() throws Exception {
        try (var audit = new Audit(); var rig = new PanelRig(4, null, null, null)) {
            rig.prepare(); rig.start();
            edt(() -> { for (int id = 1; id <= 4; id++) rig.panel.cabinDashboard().cabin(id).destinationButton(8).doClick(0);
                rig.panel.button(SystemControlPanel.Action.STOP).doClick(0); rig.panel.button(SystemControlPanel.Action.RESET).doClick(0); });
            waitFor(() -> rig.controller().state() == SimulationState.RESET); pulses(4);
            edt(() -> check(!rig.panel.hallCallPanel().button(Direction.UP).isEnabled(), "No habilita llamadas con respuesta vieja")); audit.assertClean();
        }
    }

    public static void panelCloseTerminatesSnapshotWorker() throws Exception {
        try (var rig = new PanelRig(2, null, null, null)) {
            rig.prepare(); var pump = onEdt(rig.panel::snapshotPump); waitFor(() -> pump.latest().isPresent());
            onEdt(rig.panel::closeAsync).get(5, TimeUnit.SECONDS); pump.closeAsync().get(5, TimeUnit.SECONDS); pump.request();
            check(pump.latest().isEmpty(), "Sin trabajador/publicación activa tras cerrar");
        }
    }

    public static void captureGuardRejectsEdt() throws Exception {
        try (var audit = new Audit()) {
            var fixture = onEdt(() -> new JLabel("Control positivo"));
            fixture.setText("Mutación deliberada del fixture fuera del EDT");
            check(!audit.violations.isEmpty(), "La auditoría debe detectar una violación deliberada");
            audit.violations.clear(); // No corresponde al código de producción; no contamina la auditoría siguiente.
        }
        try (var rig = new EngineRig()) {
            edt(() -> { try { UiSnapshotPump.capture(rig.c); throw new AssertionError("Captura global en EDT"); } catch (IllegalStateException expected) { } });
        }
    }

    public static void closeHandlerCallbacksStayOnEdt() throws Exception {
        try (var audit = new Audit(); var rig = new PanelRig(2, null, null, null)) {
            rig.prepare(); var finished = new CountDownLatch(1); var outside = new AtomicBoolean();
            edt(() -> { var handler = new WindowCloseHandler(rig.panel::closeAsync,
                    () -> outside.set(!SwingUtilities.isEventDispatchThread()),
                    () -> { outside.set(outside.get() || !SwingUtilities.isEventDispatchThread()); rig.panel.closed(); finished.countDown(); },
                    error -> { throw new AssertionError(error); }); handler.requestClose(); });
            check(finished.await(5, TimeUnit.SECONDS) && !outside.get(), "Callbacks de cierre en EDT"); audit.assertClean();
        }
    }

    public static void resetWhileImportingIgnoresOldUiResult() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1); var readOnEdt = new AtomicBoolean();
        try (var audit = new Audit(); var rig = new PanelRig(2, config -> EdtImportFixture.create(config, entered, release, readOnEdt), null, null)) {
            rig.prepare(); rig.largeFile(); rig.click(SystemControlPanel.Action.LOAD); check(entered.await(5, TimeUnit.SECONDS), "Importación activa");
            rig.click(SystemControlPanel.Action.RESET); release.countDown(); waitFor(() -> rig.controller().state() == SimulationState.RESET && rig.controller().epoch() == 1);
            pulses(4); check(rig.controller().preparedBatch().isEmpty(), "Lote de época antigua no revive"); audit.assertClean();
        } finally { release.countDown(); }
    }

    private static final class EngineRig implements AutoCloseable {
        final SimulationController c = new SimulationController(new SimulationConfig(2, 8, 80, 20), 0);
        @Override public void close() throws Exception { EdtSafetyScenarios.close(c); }
    }
    private static final class PanelRig implements AutoCloseable {
        final Path directory = Files.createTempDirectory("t52-");
        final AtomicReference<Optional<Path>> selected = new AtomicReference<>(Optional.empty());
        final SystemControlPanel panel;
        final int count;
        PanelRig(int count, SystemControlPanel.SessionOpener opener, Supplier<Optional<Path>> selector,
                Function<SimulationController, UiSnapshotPump> pumpFactory) throws Exception {
            this.count = count;
            panel = onEdt(() -> new SystemControlPanel(opener == null ? config -> new SimulationController(config, 0) : opener,
                    selector == null ? selected::get : selector, pumpFactory == null ? UiSnapshotPump::new : pumpFactory));
        }
        void confirm() throws Exception { edt(() -> {
            var form = panel.configurationPanel(); String[] values = {Integer.toString(count), "8", "120", "30"};
            for (var field : Field.values()) form.input(field).setText(values[field.ordinal()]); form.validateConfiguration();
        }); }
        void prepare() throws Exception { confirm(); waitFor(() -> controller() != null); }
        void start() throws Exception { click(SystemControlPanel.Action.START); waitFor(() -> controller().state() == SimulationState.RUNNING);
            waitFor(() -> onEdt(() -> { panel.updateView(); return panel.cabinDashboard().cabin(1).destinationButton(8).isEnabled(); })); }
        SimulationController controller() { return onEdt(() -> panel.controller().orElse(null)); }
        void click(SystemControlPanel.Action action) throws Exception { edt(() -> { panel.updateView(); panel.button(action).doClick(0); }); }
        void largeFile() throws Exception {
            Path file = directory.resolve("large.txt"); Files.writeString(file, (" ".repeat(80) + "CABIN 1 5\n").repeat(10000)); selected.set(Optional.of(file));
        }
        @Override public void close() throws Exception {
            onEdt(panel::closeAsync).get(8, TimeUnit.SECONDS); var c = controller(); if (c != null) check(c.awaitTermination(Duration.ofSeconds(5)), "Sesión terminada");
            edt(panel::closed);
            try (var files = Files.walk(directory)) { for (var path : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path); }
        }
    }
    private static final class Audit extends RepaintManager implements AutoCloseable {
        private final RepaintManager previous;
        private final ConcurrentLinkedQueue<String> violations = new ConcurrentLinkedQueue<>();
        Audit() throws Exception {
            previous = onEdt(() -> RepaintManager.currentManager((JComponent) null)); edt(() -> RepaintManager.setCurrentManager(this));
        }
        @Override public synchronized void addInvalidComponent(JComponent component) { record(component); super.addInvalidComponent(component); }
        @Override public void addDirtyRegion(JComponent component, int x, int y, int width, int height) { record(component); super.addDirtyRegion(component, x, y, width, height); }
        private void record(JComponent component) {
            if (!SwingUtilities.isEventDispatchThread() && violations.size() < 100) violations.add(component.getClass().getSimpleName() + " en " + Thread.currentThread().getName());
        }
        void assertClean() throws Exception { edt(() -> { }); check(violations.isEmpty(), "Mutaciones fuera EDT: " + violations); }
        @Override public void close() throws Exception { try { assertClean(); } finally { edt(() -> RepaintManager.setCurrentManager(previous)); } }
    }
    private static void watchDocuments(Component component, AtomicInteger mutations) {
        if (component instanceof JTextComponent text) text.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { mutations.incrementAndGet(); }
            public void removeUpdate(DocumentEvent e) { mutations.incrementAndGet(); }
            public void changedUpdate(DocumentEvent e) { mutations.incrementAndGet(); }
        });
        if (component instanceof Container container) for (var child : container.getComponents()) watchDocuments(child, mutations);
    }
    private static void pulses(int count) throws Exception {
        var ticks = new CountDownLatch(count); var worst = new AtomicLong(); var previous = new AtomicLong(System.nanoTime());
        var timer = onEdt(() -> { var value = new javax.swing.Timer(20, event -> {
            long now = System.nanoTime(); worst.accumulateAndGet(now - previous.getAndSet(now), Math::max); ticks.countDown();
        }); value.start(); return value; });
        try { check(ticks.await(5, TimeUnit.SECONDS), "El EDT no despachó pulsos durante operación retenida"); }
        finally { edt(timer::stop); }
        System.out.printf(java.util.Locale.ROOT, "T52_PULSE ticks=%d max_gap_ms=%.3f%n", count, worst.get() / 1_000_000.0);
    }
    private static void hold(CountDownLatch latch) {
        boolean interrupted = false;
        try { while (true) { try { latch.await(); return; } catch (InterruptedException error) { interrupted = true; } } }
        finally { if (interrupted) Thread.currentThread().interrupt(); }
    }
    private static void close(SimulationController c) throws Exception {
        c.closeAsync().get(5, TimeUnit.SECONDS); check(c.awaitTermination(Duration.ofSeconds(5)), "Motor cerrado");
    }
    private static void check(boolean condition, String text) { if (!condition) throw new AssertionError(text); }
    private static void waitFor(BooleanSupplier condition) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(7);
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
        gateAllowsFirstRefresh();
        gateLimitsRepeatedRefreshes();
        gateDoesNotCatchUpMissedTicks();
        gateHandlesNanoTimeWrap();
        pumpCapturesOutsideEdt();
        pumpCoalescesBusyRequests();
        pumpEnforcesMinimumCaptureInterval();
        latestFrameIsReplacedAfterReset();
        frameCollectionsAreDeeplyImmutable();
        captureExcludesServedHistory();
        dashboardRejectsFrameFromOldEpoch();
        dashboardRejectsFrameFromAnotherSession();
        captureFailureCanRecover();
        closedPumpCannotPublishLateFrame();
        closeWaitsForRealCaptureTerminationWithoutBlockingEdt();
        closeIsIdempotent();
        constantTimeSnapshotValidatesCabinId();
        dashboardCachesAtMostThreePanels();
        selectionRemainsConsistentAfterTabNavigation();
        evictedCabinRebuildsFromPublishedState();
        unchangedSnapshotDoesNotRewriteDocuments();
        repaintAuditAndPulsesDuringConcurrentMovement();
        largeRealImportLeavesEdtAvailable();
        closeDuringImportDoesNotFreezeEdt();
        slowResourceCloseLeavesEdtAvailable();
        preparationLeavesEdtAvailable();
        windowCloseWaitsForCaptureAndDropsLatePublication();
        resetAndLateReceiptsNeverMutateSwingOffEdt();
        panelCloseTerminatesSnapshotWorker();
        captureGuardRejectsEdt();
        closeHandlerCallbacksStayOnEdt();
        resetWhileImportingIgnoresOldUiResult();
        System.out.println("T52: 32 escenarios satisfactorios.");
    }
}
```

## src/test/java/com/elevatormanager/ui/EdtSafetyTest.java

```java
package com.elevatormanager.ui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Adaptadores JUnit T52. */
@Timeout(40)
class EdtSafetyTest {
    @Test void gateAllowsFirstRefresh() throws Exception {
        EdtSafetyScenarios.gateAllowsFirstRefresh();
    }
    @Test void gateLimitsRepeatedRefreshes() throws Exception {
        EdtSafetyScenarios.gateLimitsRepeatedRefreshes();
    }
    @Test void gateDoesNotCatchUpMissedTicks() throws Exception {
        EdtSafetyScenarios.gateDoesNotCatchUpMissedTicks();
    }
    @Test void gateHandlesNanoTimeWrap() throws Exception {
        EdtSafetyScenarios.gateHandlesNanoTimeWrap();
    }
    @Test void pumpCapturesOutsideEdt() throws Exception {
        EdtSafetyScenarios.pumpCapturesOutsideEdt();
    }
    @Test void pumpCoalescesBusyRequests() throws Exception {
        EdtSafetyScenarios.pumpCoalescesBusyRequests();
    }
    @Test void pumpEnforcesMinimumCaptureInterval() throws Exception {
        EdtSafetyScenarios.pumpEnforcesMinimumCaptureInterval();
    }
    @Test void latestFrameIsReplacedAfterReset() throws Exception {
        EdtSafetyScenarios.latestFrameIsReplacedAfterReset();
    }
    @Test void frameCollectionsAreDeeplyImmutable() throws Exception {
        EdtSafetyScenarios.frameCollectionsAreDeeplyImmutable();
    }
    @Test void captureExcludesServedHistory() throws Exception {
        EdtSafetyScenarios.captureExcludesServedHistory();
    }
    @Test void dashboardRejectsFrameFromOldEpoch() throws Exception {
        EdtSafetyScenarios.dashboardRejectsFrameFromOldEpoch();
    }
    @Test void dashboardRejectsFrameFromAnotherSession() throws Exception {
        EdtSafetyScenarios.dashboardRejectsFrameFromAnotherSession();
    }
    @Test void captureFailureCanRecover() throws Exception {
        EdtSafetyScenarios.captureFailureCanRecover();
    }
    @Test void closedPumpCannotPublishLateFrame() throws Exception {
        EdtSafetyScenarios.closedPumpCannotPublishLateFrame();
    }
    @Test void closeWaitsForRealCaptureTerminationWithoutBlockingEdt() throws Exception {
        EdtSafetyScenarios.closeWaitsForRealCaptureTerminationWithoutBlockingEdt();
    }
    @Test void closeIsIdempotent() throws Exception {
        EdtSafetyScenarios.closeIsIdempotent();
    }
    @Test void constantTimeSnapshotValidatesCabinId() throws Exception {
        EdtSafetyScenarios.constantTimeSnapshotValidatesCabinId();
    }
    @Test void dashboardCachesAtMostThreePanels() throws Exception {
        EdtSafetyScenarios.dashboardCachesAtMostThreePanels();
    }
    @Test void selectionRemainsConsistentAfterTabNavigation() throws Exception {
        EdtSafetyScenarios.selectionRemainsConsistentAfterTabNavigation();
    }
    @Test void evictedCabinRebuildsFromPublishedState() throws Exception {
        EdtSafetyScenarios.evictedCabinRebuildsFromPublishedState();
    }
    @Test void unchangedSnapshotDoesNotRewriteDocuments() throws Exception {
        EdtSafetyScenarios.unchangedSnapshotDoesNotRewriteDocuments();
    }
    @Test void repaintAuditAndPulsesDuringConcurrentMovement() throws Exception {
        EdtSafetyScenarios.repaintAuditAndPulsesDuringConcurrentMovement();
    }
    @Test void largeRealImportLeavesEdtAvailable() throws Exception {
        EdtSafetyScenarios.largeRealImportLeavesEdtAvailable();
    }
    @Test void closeDuringImportDoesNotFreezeEdt() throws Exception {
        EdtSafetyScenarios.closeDuringImportDoesNotFreezeEdt();
    }
    @Test void slowResourceCloseLeavesEdtAvailable() throws Exception {
        EdtSafetyScenarios.slowResourceCloseLeavesEdtAvailable();
    }
    @Test void preparationLeavesEdtAvailable() throws Exception {
        EdtSafetyScenarios.preparationLeavesEdtAvailable();
    }
    @Test void windowCloseWaitsForCaptureAndDropsLatePublication() throws Exception {
        EdtSafetyScenarios.windowCloseWaitsForCaptureAndDropsLatePublication();
    }
    @Test void resetAndLateReceiptsNeverMutateSwingOffEdt() throws Exception {
        EdtSafetyScenarios.resetAndLateReceiptsNeverMutateSwingOffEdt();
    }
    @Test void panelCloseTerminatesSnapshotWorker() throws Exception {
        EdtSafetyScenarios.panelCloseTerminatesSnapshotWorker();
    }
    @Test void captureGuardRejectsEdt() throws Exception {
        EdtSafetyScenarios.captureGuardRejectsEdt();
    }
    @Test void closeHandlerCallbacksStayOnEdt() throws Exception {
        EdtSafetyScenarios.closeHandlerCallbacksStayOnEdt();
    }
    @Test void resetWhileImportingIgnoresOldUiResult() throws Exception {
        EdtSafetyScenarios.resetWhileImportingIgnoresOldUiResult();
    }
}
```
