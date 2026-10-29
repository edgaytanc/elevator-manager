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
