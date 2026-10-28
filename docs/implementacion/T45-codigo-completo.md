# T45 — Código completo

Los siete fuentes nuevos o modificados se reproducen íntegros. El ZIP conserva además el resto del proyecto, pom.xml y toda la documentación.

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

## src/main/java/com/elevatormanager/simulation/ImportedRequestBatch.java

```java
package com.elevatormanager.simulation;

import com.elevatormanager.io.PreparedRequestBatch;
import com.elevatormanager.io.RequestEntry;
import com.elevatormanager.model.request.CabinRequest;
import com.elevatormanager.model.request.HallRequest;
import com.elevatormanager.model.request.Request;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Lote confirmado en una sesión/época; conserva identidades hasta su consumo único. */
public record ImportedRequestBatch(UUID id, long epoch, Instant confirmedAt,
        PreparedRequestBatch source, List<Request> requests) {
    public ImportedRequestBatch {
        Objects.requireNonNull(id, "id"); Objects.requireNonNull(confirmedAt, "confirmedAt");
        Objects.requireNonNull(source, "source"); requests = List.copyOf(requests);
        if (epoch < 0 || requests.size() != source.entries().size()) {
            throw new IllegalArgumentException("Época o cantidad de solicitudes incompatible.");
        }
        var identities = new HashSet<UUID>();
        for (int i = 0; i < requests.size(); i++) {
            Request request = requests.get(i);
            RequestEntry entry = source.entries().get(i);
            boolean sameKind = entry instanceof RequestEntry.Hall hall
                    ? request instanceof HallRequest call && call.getDirection() == hall.direction()
                    : request instanceof CabinRequest cabin && cabin.getElevatorId() == ((RequestEntry.Cabin) entry).elevatorId();
            if (!sameKind || request.getFloor() != entry.floor() || request.getEpoch() != epoch
                    || !request.getCreatedAt().equals(confirmedAt) || !identities.add(request.getId())
                    || !request.validateAgainst(source.config()).isValid()) {
                throw new IllegalArgumentException("Solicitud incompatible con el descriptor confirmado.");
            }
        }
    }

    static ImportedRequestBatch confirm(PreparedRequestBatch source, long epoch) {
        Instant at = Instant.now();
        var requests = new ArrayList<Request>();
        for (var entry : source.entries()) {
            UUID id = UUID.randomUUID();
            requests.add(entry instanceof RequestEntry.Hall hall
                    ? new HallRequest(id, epoch, hall.floor(), at, hall.direction())
                    : new CabinRequest(id, epoch, entry.floor(), at, ((RequestEntry.Cabin) entry).elevatorId()));
        }
        return new ImportedRequestBatch(UUID.randomUUID(), epoch, at, source, requests);
    }
}
```

## src/main/java/com/elevatormanager/simulation/BatchImportResult.java

```java
package com.elevatormanager.simulation;

import com.elevatormanager.io.BatchDiagnostic;
import com.elevatormanager.model.SimulationState;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** IMPORTED confirma preparación en RESET, nunca envío ni servicio físico. */
public record BatchImportResult(Outcome outcome, SimulationState state, long epoch,
        UUID sessionId, long generation, String detail,
        Optional<ImportedRequestBatch> batch, List<BatchDiagnostic> diagnostics) {
    public enum Outcome { IMPORTED, INVALID, REJECTED, SUPERSEDED, CANCELLED }
    public BatchImportResult {
        Objects.requireNonNull(outcome, "outcome"); Objects.requireNonNull(state, "state");
        Objects.requireNonNull(sessionId, "sessionId"); Objects.requireNonNull(detail, "detail");
        Objects.requireNonNull(batch, "batch"); diagnostics = List.copyOf(diagnostics);
        if (epoch < 0 || generation < 0 || detail.isBlank()
                || batch.isPresent() != (outcome == Outcome.IMPORTED)
                || !diagnostics.isEmpty() != (outcome == Outcome.INVALID)
                || (batch.isPresent() && (state != SimulationState.RESET || batch.orElseThrow().epoch() != epoch))) {
            throw new IllegalArgumentException("Resultado de importación incoherente.");
        }
    }
}
```

## src/main/java/com/elevatormanager/simulation/BatchDispatchReport.java

```java
package com.elevatormanager.simulation;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Intento único de envío en orden; ENQUEUED no equivale a aceptación ni atención. */
public record BatchDispatchReport(ImportedRequestBatch batch, List<Entry> entries) {
    public record Entry(int lineNumber, UUID requestId, SimulationController.Result result) {
        public Entry {
            Objects.requireNonNull(requestId, "requestId"); Objects.requireNonNull(result, "result");
            if (lineNumber < 1) { throw new IllegalArgumentException("Línea no positiva."); }
        }
    }
    public BatchDispatchReport {
        Objects.requireNonNull(batch, "batch"); entries = List.copyOf(entries);
        if (entries.size() != batch.requests().size()) { throw new IllegalArgumentException("Informe incompleto."); }
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).lineNumber() != batch.source().entries().get(i).lineNumber()
                    || !entries.get(i).requestId().equals(batch.requests().get(i).getId())
                    || entries.get(i).result().epoch() != batch.epoch()
                    || (entries.get(i).result().outcome() != SimulationController.Outcome.ENQUEUED
                    && entries.get(i).result().outcome() != SimulationController.Outcome.REJECTED)) {
                throw new IllegalArgumentException("Informe no correlacionado con el lote.");
            }
            var request = batch.requests().get(i);
            var result = entries.get(i).result();
            if (result.command().isPresent()) {
                var command = result.command().orElseThrow();
                if (!(request instanceof com.elevatormanager.model.request.CabinRequest)
                        || !command.request().getId().equals(request.getId()) || command.epoch() != batch.epoch()) {
                    throw new IllegalArgumentException("Comando ajeno a la solicitud informada.");
                }
            } else if (request instanceof com.elevatormanager.model.request.CabinRequest
                    && result.outcome() == SimulationController.Outcome.ENQUEUED) {
                throw new IllegalArgumentException("Envío interno sin comando correlacionado.");
            }
        }
    }
}
```

## src/main/java/com/elevatormanager/diagnostics/RequestImportDemo.java

```java
package com.elevatormanager.diagnostics;

import com.elevatormanager.dispatch.HallCallSnapshot;
import com.elevatormanager.logging.SessionLog;
import com.elevatormanager.model.SimulationConfig;
import com.elevatormanager.model.SimulationState;
import com.elevatormanager.model.request.HallRequest;
import com.elevatormanager.protocol.Command;
import com.elevatormanager.simulation.BatchImportResult;
import com.elevatormanager.simulation.ExecutionEvent;
import com.elevatormanager.simulation.ImportedRequestBatch;
import com.elevatormanager.simulation.SimulationController;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** T45: importación real, Start único y seguimiento de servicios con identidades canónicas. */
public final class RequestImportDemo {
    private RequestImportDemo() { }

    public static void main(String[] args) throws Exception {
        int status = run(args);
        if (status != 0) { System.exit(status); }
    }

    public static int run(String[] args) throws Exception {
        if (args.length < 3 || args.length > 4) {
            System.err.println("Uso: RequestImportDemo <archivo> <elevadores> <pisos> [directorioLogs]");
            return 2;
        }
        final SimulationConfig config;
        final Path file;
        final Path logDirectory;
        try {
            config = SimulationConfig.fromText(args[1], args[2], "30", "10");
            file = Path.of(args[0]); logDirectory = Path.of(args.length == 4 ? args[3] : "logs/t45");
        } catch (IllegalArgumentException error) { System.err.println(error.getMessage()); return 2; }
        try (var log = SessionLog.open(logDirectory, config.elevatorCount())) {
            var controller = new SimulationController(config, 0, log);
            try {
                var result = controller.importRequests(file).get(15, TimeUnit.SECONDS);
                if (result.outcome() != BatchImportResult.Outcome.IMPORTED) {
                    System.err.println("T45: " + result.outcome() + "; " + result.detail());
                    for (var diagnostic : result.diagnostics()) {
                        System.err.println(diagnostic.code() + "; linea="
                                + (diagnostic.lineNumber().isPresent() ? diagnostic.lineNumber().getAsInt() : "archivo")
                                + "; " + diagnostic.message());
                        diagnostic.cause().ifPresent(cause -> System.err.println("Causa: " + cause));
                    }
                    return 1;
                }
                ImportedRequestBatch batch = result.batch().orElseThrow();
                if (controller.state() != SimulationState.RESET
                        || controller.snapshots().stream().anyMatch(s -> s.currentFloor() != 1 || s.nextFloor().isPresent())) {
                    throw new IllegalStateException("Importar no debe mover cabinas.");
                }
                System.out.println("T45: lote confirmado en RESET; solicitudes=" + batch.requests().size() + "; sin movimiento.");
                if (controller.start().get(10, TimeUnit.SECONDS).outcome() != SimulationController.Outcome.APPLIED) {
                    throw new IllegalStateException("Start no fue aplicado.");
                }
                var report = controller.lastBatchDispatch().orElseThrow();
                if (report.entries().stream().anyMatch(e -> e.result().outcome() != SimulationController.Outcome.ENQUEUED)) {
                    throw new IllegalStateException("Alguna entrada no pudo enviarse; consultar informe.");
                }
                awaitServices(controller, batch);
                System.out.println("T45: lote enviado una vez; todos sus servicios o alias activos confirmados.");
            } finally {
                controller.closeAsync().get(15, TimeUnit.SECONDS);
                if (!controller.awaitTermination(Duration.ofSeconds(10))) { throw new IllegalStateException("Cierre no confirmado."); }
            }
        }
        System.out.println("T45: sesión cerrada; registros en " + logDirectory.toAbsolutePath().normalize());
        return 0;
    }

    private static void awaitServices(SimulationController controller, ImportedRequestBatch batch) throws Exception {
        var canonical = new HashMap<UUID, UUID>();
        var served = new HashSet<UUID>();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (true) {
            var calls = controller.calls();
            boolean complete = batch.requests().stream().allMatch(request -> request instanceof HallRequest
                    ? calls.containsKey(request.getId()) && calls.get(request.getId()).status() == HallCallSnapshot.Status.SERVED
                    : canonical.containsKey(request.getId()) && served.contains(canonical.get(request.getId())));
            if (complete) { return; }
            long left = deadline - System.nanoTime();
            if (left <= 0) { throw new IllegalStateException("La muestra agotó su plazo de servicio de 30 s."); }
            if (controller.failure().isPresent()) { throw new IllegalStateException("Sesión con incidencia.", controller.failure().orElseThrow()); }
            var optional = controller.awaitEvent(Duration.ofNanos(Math.min(left, TimeUnit.MILLISECONDS.toNanos(100))));
            if (optional.isEmpty()) { continue; }
            ExecutionEvent event = optional.orElseThrow();
            if (event.type() == ExecutionEvent.Type.WORKER_FAILED || event.type() == ExecutionEvent.Type.COMMAND_REJECTED) {
                throw new IllegalStateException("Incidencia del actor: " + event.type() + "; " + event.detail());
            }
            if (event.command().isPresent() && event.canonicalRequestId().isPresent()) {
                Command command = event.command().orElseThrow();
                UUID request = command instanceof Command.Destination destination ? destination.request().getId()
                        : command instanceof Command.Pickup pickup ? pickup.request().getId() : null;
                if (request != null) { canonical.put(request, event.canonicalRequestId().orElseThrow()); }
            }
            if (event.type() == ExecutionEvent.Type.COMMAND_COMPLETED) { event.canonicalRequestId().ifPresent(served::add); }
        }
    }
}
```

## src/test/java/com/elevatormanager/simulation/RequestImportScenarios.java

```java
package com.elevatormanager.simulation;

import com.elevatormanager.io.BatchDiagnostic;
import com.elevatormanager.io.BatchReadResult;
import com.elevatormanager.io.RequestFileReader;
import com.elevatormanager.model.SimulationConfig;
import com.elevatormanager.model.SimulationState;
import com.elevatormanager.model.request.CabinRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import javax.swing.SwingUtilities;
import static com.elevatormanager.simulation.ExecutionEvent.Type.*;
import static com.elevatormanager.simulation.BatchImportResult.Outcome.*;

/** T45: confirmación serializada, carreras con lectores retenidos y actores reales. */
public final class RequestImportScenarios {
    private static final SimulationConfig CONFIG = new SimulationConfig(2,8,5,5);
    private static final RequestFileReader READER = new RequestFileReader();
    private RequestImportScenarios() { }

    public static void initialImportDoesNotStartActors() throws Exception {
        inDirectory(dir -> {
            try (var rig = new Rig(CONFIG)) {
                var before = rig.c.snapshots();
                var result = get(rig.c.importRequests(file(dir,"a","CABIN 1 4\nHALL 3 UP")));
                check(result.outcome()==IMPORTED && rig.c.state()==SimulationState.RESET,"Confirmado en RESET");
                check(rig.c.snapshots().equals(before) && rig.c.elevatorLifecycles().stream().allMatch(x -> x==ElevatorWorker.Lifecycle.NEW),"Sin movimiento ni arranque");
                check(rig.c.managerLifecycle()==com.elevatormanager.dispatch.ManagerWorker.Lifecycle.NEW,"Manejador sin iniciar");
            }
        });
    }
    public static void importAfterResetKeepsCabinsIdle() throws Exception {
        inDirectory(dir -> {
            try (var rig = new Rig(CONFIG)) {
                get(rig.c.start()); get(rig.c.submit(cabin(0,3,1))); rig.until(COMMAND_COMPLETED,3);
                get(rig.c.reset()); var before=rig.c.snapshots();
                check(get(rig.c.importRequests(file(dir,"a","CABIN 1 7"))).outcome()==IMPORTED,"Importación tras reset");
                check(rig.c.snapshots().equals(before) && rig.c.state()==SimulationState.RESET,"Confirmar no reanuda actores retenidos");
            }
        });
    }
    public static void startingServicesMixedImportedBatch() throws Exception {
        inDirectory(dir -> {
            try (var rig=new Rig(CONFIG)) {
                var batch=get(rig.c.importRequests(file(dir,"a","CABIN 1 4\nHALL 3 UP\nCABIN 2 6"))).batch().orElseThrow();
                get(rig.c.start()); rig.served(batch);
                check(rig.c.preparedBatch().isEmpty() && rig.c.lastBatchDispatch().orElseThrow().entries().size()==3,"Lote consumido e informado");
            }
        });
    }
    public static void repeatedStartNeverReleasesBatchAgain() throws Exception {
        inDirectory(dir -> {
            try(var rig=new Rig(CONFIG)) {
                var batch=get(rig.c.importRequests(file(dir,"a","CABIN 1 4"))).batch().orElseThrow(); get(rig.c.start());
                var report=rig.c.lastBatchDispatch().orElseThrow();
                for(int i=0;i<20;i++) { check(get(rig.c.start()).outcome()==SimulationController.Outcome.UNCHANGED,"Start repetido"); }
                check(rig.c.lastBatchDispatch().orElseThrow()==report,"No regenera informe ni solicitudes"); rig.served(batch);
            }
        });
    }
    public static void stopStartContinuesOriginalBatchRequests() throws Exception {
        inDirectory(dir -> {
            try(var rig=new Rig(new SimulationConfig(2,8,100,5))) {
                var batch=get(rig.c.importRequests(file(dir,"a","CABIN 1 5"))).batch().orElseThrow();get(rig.c.start());rig.until(DEPARTED,1);
                get(rig.c.stop());var report=rig.c.lastBatchDispatch().orElseThrow();get(rig.c.start());rig.served(batch);
                check(rig.c.lastBatchDispatch().orElseThrow()==report,"Continúa pendientes sin reimportar");
            }
        });
    }
    public static void validReplacementIsAtomicAndUsesFreshIdentities() throws Exception {
        inDirectory(dir -> {
            try(var rig=new Rig(CONFIG)) {
                var a=get(rig.c.importRequests(file(dir,"a","CABIN 1 2"))).batch().orElseThrow();
                var b=get(rig.c.importRequests(file(dir,"b","CABIN 2 6\nHALL 3 UP"))).batch().orElseThrow();
                check(rig.c.preparedBatch().orElseThrow()==b && !a.id().equals(b.id()),"Reemplazo completo");
                check(b.requests().stream().noneMatch(r -> r.getId().equals(a.requests().get(0).getId())),"Identidades nuevas");
            }
        });
    }
    public static void invalidFinalLinePreservesPreviousBatch() throws Exception {
        inDirectory(dir -> {
            try(var rig=new Rig(CONFIG)) {
                var old=get(rig.c.importRequests(file(dir,"a","CABIN 1 2"))).batch().orElseThrow();
                var result=get(rig.c.importRequests(file(dir,"b","# final\nHALL 3 UP\nCABIN 1 8\nHALL 8 UP")));
                check(result.outcome()==INVALID && result.diagnostics().get(0).lineNumber().orElseThrow()==4,"Error final");
                check(rig.c.preparedBatch().orElseThrow()==old,"No sustituye parcial");
            }
        });
    }
    public static void missingFilePreservesPreviousBatch() throws Exception {
        inDirectory(dir -> {
            try(var rig=new Rig(CONFIG)) {
                var old=get(rig.c.importRequests(file(dir,"a","CABIN 1 2"))).batch().orElseThrow();
                var bad=get(rig.c.importRequests(dir.resolve("ausente")));
                check(bad.outcome()==INVALID && bad.diagnostics().get(0).code()==BatchDiagnostic.Code.READ_ERROR,"Error de lectura");
                check(rig.c.preparedBatch().orElseThrow()==old,"Conserva anterior");
            }
        });
    }
    public static void emptyFilePreservesPreviousBatch() throws Exception {
        inDirectory(dir -> {
            try(var rig=new Rig(CONFIG)) {
                var old=get(rig.c.importRequests(file(dir,"a","CABIN 1 2"))).batch().orElseThrow();
                var bad=get(rig.c.importRequests(file(dir,"b","# vacío\n\n")));
                check(bad.outcome()==INVALID && bad.diagnostics().get(0).code()==BatchDiagnostic.Code.EMPTY_BATCH,"Vacío no es vaciado");
                check(rig.c.preparedBatch().orElseThrow()==old,"Mantiene preparado");
            }
        });
    }
    public static void duplicateEntriesRemainDistinctAtConfirmation() throws Exception {
        inDirectory(dir -> {
            try(var rig=new Rig(CONFIG)) {
                var batch=get(rig.c.importRequests(file(dir,"a","CABIN 1 6\nCABIN 1 006\nHALL 3 UP\nHALL 003 UP"))).batch().orElseThrow();
                check(batch.requests().size()==4 && batch.requests().stream().map(r->r.getId()).distinct().count()==4,"Cuatro identidades sin deduplicar archivo");
                get(rig.c.start());check(rig.c.lastBatchDispatch().orElseThrow().entries().size()==4,"Cada entrada se presenta al dominio");
            }
        });
    }
    public static void runningImportIsRejectedBeforeReading() throws Exception {
        var reads=new AtomicInteger();
        try(var rig=new Rig(CONFIG,(p,c)->{reads.incrementAndGet();return parsed("CABIN 1 2",c);})) {
            get(rig.c.start());check(get(rig.c.importRequests(Path.of("a"))).outcome()==REJECTED && reads.get()==0,"No lee desde RUNNING");
        }
    }
    public static void stoppedImportIsRejectedBeforeReading() throws Exception {
        var reads=new AtomicInteger();
        try(var rig=new Rig(CONFIG,(p,c)->{reads.incrementAndGet();return parsed("CABIN 1 2",c);})) {
            get(rig.c.start());get(rig.c.stop());check(get(rig.c.importRequests(Path.of("a"))).state()==SimulationState.STOPPED && reads.get()==0,"No lee desde STOPPED");
        }
    }
    public static void stoppingImportIsRejectedBeforeReading() throws Exception {
        var reads=new AtomicInteger();
        try(var rig=new Rig(new SimulationConfig(2,8,60_000,5),(p,c)->{reads.incrementAndGet();return parsed("CABIN 1 2",c);})) {
            get(rig.c.start());get(rig.c.submit(cabin(0,8,1)));rig.until(DEPARTED,1);rig.c.stop();
            var response=get(rig.c.importRequests(Path.of("a")));
            check(response.outcome()==REJECTED && response.state()==SimulationState.STOPPING && reads.get()==0,"No lee desde STOPPING");
        }
    }
    public static void resettingImportIsRejectedBeforeReading() throws Exception {
        var reads=new AtomicInteger();
        try(var rig=new Rig(new SimulationConfig(2,8,60_000,5),(p,c)->{reads.incrementAndGet();return parsed("CABIN 1 2",c);})) {
            get(rig.c.start());get(rig.c.submit(cabin(0,8,1)));rig.until(DEPARTED,1);rig.c.reset();
            var response=get(rig.c.importRequests(Path.of("a")));
            check(response.outcome()==REJECTED && response.state()==SimulationState.RESETTING && reads.get()==0,"No lee durante retorno físico");
        }
    }
    public static void closingAndClosedImportsAreRejected() throws Exception {
        var reads=new AtomicInteger();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        var c=SimulationController.withBatchLoader(CONFIG,0,(p,config)->{reads.incrementAndGet();return parsed("CABIN 1 2",config);});
        try {
            get(c.registerResource(()->{entered.countDown();check(release.await(10,TimeUnit.SECONDS),"Liberar recurso");}));
            var closed=c.closeAsync();await(entered);
            check(get(c.importRequests(Path.of("a"))).outcome()==REJECTED,"Rechaza CLOSING");release.countDown();get(closed);
            check(get(c.importRequests(Path.of("a"))).state()==SimulationState.CLOSED && reads.get()==0,"Rechaza CLOSED sin lectura");
        } finally {release.countDown();get(c.closeAsync());check(c.awaitTermination(Duration.ofSeconds(10)),"Cerrador termina");}
    }
    public static void startDuringReadInvalidatesLateSuccess() throws Exception {
        var gate=new GateLoader(1);
        try(var rig=new Rig(CONFIG,gate)) {
            try {
                var pending=rig.c.importRequests(Path.of("a"));await(gate.entered);get(rig.c.start());
                check(get(pending).outcome()==REJECTED && rig.c.preparedBatch().isEmpty(),"Start invalida de inmediato");
                gate.release.countDown();check(rig.c.lastBatchDispatch().isEmpty(),"No había lote confirmado que ejecutar");
            } finally {gate.release.countDown();}
        }
    }
    public static void resetDuringReadRejectsOldEpochEvenBackInReset() throws Exception {
        var gate=new GateLoader(1);
        try(var rig=new Rig(CONFIG,gate)) {
            try {
                var pending=rig.c.importRequests(Path.of("a"));await(gate.entered);get(rig.c.reset());
                check(get(pending).outcome()==REJECTED && rig.c.state()==SimulationState.RESET && rig.c.epoch()==1,"Nueva época RESET");
                gate.release.countDown();var fresh=get(rig.c.importRequests(Path.of("b"))).batch().orElseThrow();
                check(fresh.epoch()==1 && fresh.requests().get(0).getFloor()==6,"Solo confirma lectura nueva");
            } finally {gate.release.countDown();}
        }
    }
    public static void resetClearsConfirmedBatchBeforeNextStart() throws Exception {
        inDirectory(dir -> {
            try(var rig=new Rig(CONFIG)) {
                get(rig.c.importRequests(file(dir,"a","CABIN 1 4")));get(rig.c.reset());
                check(rig.c.preparedBatch().isEmpty() && rig.c.lastBatchDispatch().isEmpty(),"Reset elimina lote");get(rig.c.start());
                check(rig.c.lastBatchDispatch().isEmpty(),"No revive después de Start");
            }
        });
    }
    public static void closeWaitsForActualReaderTermination() throws Exception {
        var gate=new GateLoader(1);var c=SimulationController.withBatchLoader(CONFIG,0,gate);
        try {
            var pending=c.importRequests(Path.of("a"));await(gate.entered);var closing=c.closeAsync();
            check(get(pending).outcome()==REJECTED,"Cierre resuelve importación pendiente");
            check(!c.awaitTermination(Duration.ZERO) && !closing.isDone(),"No afirma CLOSED mientras lector sigue vivo");
            gate.release.countDown();get(closing);check(c.awaitTermination(Duration.ofSeconds(10)),"Cierre real confirmado");
            check(!gate.thread.get().isAlive(),"Hilo I/O terminado");
        } finally {gate.release.countDown();get(c.closeAsync());c.awaitTermination(Duration.ofSeconds(10));}
    }
    public static void newestReadSupersedesAndPreservesOldBatchWhilePending() throws Exception {
        var gate=new GateLoader(2);
        try(var rig=new Rig(CONFIG,gate)) {
            try {
                var old=get(rig.c.importRequests(Path.of("a"))).batch().orElseThrow();
                var obsolete=rig.c.importRequests(Path.of("b"));await(gate.entered);var latest=rig.c.importRequests(Path.of("c"));
                check(get(obsolete).outcome()==SUPERSEDED && rig.c.preparedBatch().orElseThrow()==old,"Mantiene confirmado mientras lee");
                gate.release.countDown();var fresh=get(latest).batch().orElseThrow();
                check(rig.c.preparedBatch().orElseThrow()==fresh && fresh.requests().get(0).getFloor()==7,"Gana petición nueva");
            } finally {gate.release.countDown();}
        }
    }
    public static void repeatedSupersedingKeepsOneQueuedRead() throws Exception {
        var gate=new GateLoader(1);
        try(var rig=new Rig(CONFIG,gate)) {
            try {
                var first=rig.c.importRequests(Path.of("a"));await(gate.entered);
                var futures=new ArrayList<CompletableFuture<BatchImportResult>>();
                for(int i=0;i<20;i++) {futures.add(rig.c.importRequests(Path.of("c")));}
                check(get(first).outcome()==SUPERSEDED,"Primera invalidada");
                for(int i=0;i<19;i++) {check(get(futures.get(i)).outcome()==SUPERSEDED,"Pendiente sustituida");}
                gate.release.countDown();check(get(futures.get(19)).outcome()==IMPORTED,"Última confirmada");
                check(gate.calls.get()==2 && gate.threads.size()==1,"Solo lector en curso y último en cola; mismo hilo");
            } finally {gate.release.countDown();}
        }
    }
    public static void explicitCancellationKeepsPreviousBatch() throws Exception {
        var gate=new GateLoader(2);
        try(var rig=new Rig(CONFIG,gate)) {
            try {
                var old=get(rig.c.importRequests(Path.of("a"))).batch().orElseThrow();var pending=rig.c.importRequests(Path.of("b"));await(gate.entered);
                check(get(rig.c.cancelImport()).outcome()==CANCELLED && get(pending).outcome()==CANCELLED,"Cancelación explícita");
                check(rig.c.preparedBatch().orElseThrow()==old,"No borra lote previo");gate.release.countDown();
                check(get(rig.c.importRequests(Path.of("c"))).outcome()==IMPORTED,"Puede leer después");
            } finally {gate.release.countDown();}
        }
    }
    public static void cancellingVisibleFutureDoesNotCancelOperation() throws Exception {
        var gate=new GateLoader(1);
        try(var rig=new Rig(CONFIG,gate)) {
            try {
                var observer=rig.c.importRequests(Path.of("a"));await(gate.entered);check(observer.cancel(true),"Observador cancelado");
                gate.release.countDown();until(()->rig.c.preparedBatch().isPresent());
                check(observer.isCancelled() && rig.c.preparedBatch().orElseThrow().requests().get(0).getFloor()==2,"Operación privada sigue vigente");
            } finally {gate.release.countDown();}
        }
    }
    public static void incompatibleConfigurationCannotReplaceBatch() throws Exception {
        try(var rig=new Rig(CONFIG,(p,c)->parsed("CABIN 1 2",p.toString().equals("bad")?new SimulationConfig(1,4,1,1):c))) {
            var old=get(rig.c.importRequests(Path.of("a"))).batch().orElseThrow();var result=get(rig.c.importRequests(Path.of("bad")));
            check(result.outcome()==REJECTED && rig.c.preparedBatch().orElseThrow()==old,"Revalida configuración");
        }
    }
    public static void loaderExceptionIsInvalidAndKeepsOriginalCause() throws Exception {
        var cause=new IOException("fallo inyectado");
        try(var rig=new Rig(CONFIG,(p,c)->{if(p.toString().equals("bad")){throw cause;}return parsed("CABIN 1 2",c);})) {
            var result=get(rig.c.importRequests(Path.of("bad")));
            check(result.outcome()==INVALID && result.diagnostics().get(0).cause().orElseThrow()==cause,"Causa por identidad");
            check(get(rig.c.importRequests(Path.of("a"))).outcome()==IMPORTED && rig.c.failure().isEmpty(),"Error de archivo no mata sesión");
        }
    }
    public static void nullLoaderResultCannotLeaveUnresolvedFuture() throws Exception {
        try(var rig=new Rig(CONFIG,(p,c)->null)) {
            var result=get(rig.c.importRequests(Path.of("a")));
            check(result.outcome()==INVALID && result.diagnostics().get(0).cause().orElseThrow() instanceof NullPointerException,"Lector nulo se informa");
        }
    }
    public static void edtAndCoordinatorStayResponsiveDuringRead() throws Exception {
        var gate=new GateLoader(1);
        try(var rig=new Rig(CONFIG,gate)) {
            try {
                var future=new AtomicReference<CompletableFuture<BatchImportResult>>();
                SwingUtilities.invokeAndWait(()->future.set(rig.c.importRequests(Path.of("a"))));await(gate.entered);
                var ping=new CompletableFuture<Boolean>();SwingUtilities.invokeLater(()->ping.complete(SwingUtilities.isEventDispatchThread()));
                check(get(ping) && gate.thread.get().getName().startsWith("simulation-import-"),"EDT libre e I/O en hilo propio");
                check(get(rig.c.stop()).outcome()==SimulationController.Outcome.UNCHANGED,"Coordinador no bloqueado por I/O");
                get(rig.c.cancelImport());gate.release.countDown();check(get(future.get()).outcome()==CANCELLED,"Cancelación responde");
            } finally {gate.release.countDown();}
        }
    }
    public static void confirmationDoesNotConsumeCommandIds() throws Exception {
        inDirectory(dir -> {
            try(var rig=new Rig(CONFIG)) {
                get(rig.c.importRequests(file(dir,"a","CABIN 1 2")));get(rig.c.importRequests(file(dir,"b","CABIN 2 3")));
                get(rig.c.importRequests(file(dir,"bad","HALL 8 UP")));get(rig.c.start());
                check(rig.c.lastBatchDispatch().orElseThrow().entries().get(0).result().command().orElseThrow().commandId()==1,"Primer comando solo al enviar");
            }
        });
    }
    public static void receiptsAreBoundToTheirSession() throws Exception {
        try(var a=new Rig(CONFIG,(p,c)->parsed("CABIN 1 2",c));var b=new Rig(CONFIG,(p,c)->parsed("CABIN 1 2",c))) {
            var result=get(a.c.importRequests(Path.of("a")));
            check(a.c.isCurrent(result) && !b.c.isCurrent(result),"Misma época no autoriza otra sesión");get(a.c.start());
            check(!a.c.isCurrent(result),"Respuesta anterior a Start ya no representa lote preparado");
        }
    }
    public static void delayedReceiptIsRejectedAfterReplacement() throws Exception {
        try(var rig=new Rig(CONFIG,(p,c)->parsed("CABIN 1 2",c))) {
            var old=get(rig.c.importRequests(Path.of("a")));var fresh=get(rig.c.importRequests(Path.of("b")));
            check(!rig.c.isCurrent(old) && rig.c.isCurrent(fresh),"Generación invalida respuesta tardía");
        }
    }
    public static void confirmedRequestsShareTimeAndHaveUniqueIds() throws Exception {
        try(var rig=new Rig(CONFIG,(p,c)->parsed("CABIN 1 2\nCABIN 1 2\nHALL 3 UP",c))) {
            var batch=get(rig.c.importRequests(Path.of("a"))).batch().orElseThrow();
            check(batch.requests().stream().allMatch(r->r.getEpoch()==0 && r.getCreatedAt().equals(batch.confirmedAt())),"Un Instant de confirmación y época");
            check(batch.requests().stream().map(r->r.getId()).distinct().count()==3,"UUID por instrucción, incluidos repetidos");
        }
    }
    public static void dispatchReportPreservesOrderWithoutClaimingService() throws Exception {
        try(var rig=new Rig(CONFIG,(p,c)->parsed("# origen\nCABIN 1 7\nHALL 2 UP\n\nCABIN 2 6\nHALL 5 DOWN",c))) {
            var batch=get(rig.c.importRequests(Path.of("a"))).batch().orElseThrow();get(rig.c.start());var report=rig.c.lastBatchDispatch().orElseThrow();
            check(report.entries().stream().map(BatchDispatchReport.Entry::lineNumber).toList().equals(List.of(2,3,5,6)),"Orden de presentación");
            check(report.entries().stream().allMatch(e->e.result().outcome()==SimulationController.Outcome.ENQUEUED),"Solo confirma envío");
            for(int i=0;i<4;i++) {check(report.entries().get(i).requestId().equals(batch.requests().get(i).getId()),"UUID correlacionado");}
            check(report.entries().get(1).result().command().isEmpty(),"HALL aún no es orden asignada al confirmar envío");
        }
    }
    public static void importingAfterResetKeepsActorThreadIdentities() throws Exception {
        try(var rig=new Rig(CONFIG,(p,c)->parsed("CABIN 1 2",c))) {
            get(rig.c.start());var before=actorThreads();get(rig.c.reset());get(rig.c.importRequests(Path.of("a")));get(rig.c.start());
            check(actorThreads().equals(before) && before.size()==3,"No crea nuevas cabinas ni manejador");
        }
    }
    public static void importingAfterCompletedResetUsesNewEpoch() throws Exception {
        try(var rig=new Rig(CONFIG,(p,c)->parsed("CABIN 1 3",c))) {
            get(rig.c.reset());var batch=get(rig.c.importRequests(Path.of("a"))).batch().orElseThrow();
            check(batch.epoch()==1 && batch.requests().get(0).getEpoch()==1,"Época actual confirmada");get(rig.c.start());rig.served(batch);
        }
    }
    public static void closeClearsConfirmedBatch() throws Exception {
        try(var rig=new Rig(CONFIG,(p,c)->parsed("CABIN 1 2",c))) {
            get(rig.c.importRequests(Path.of("a")));get(rig.c.closeAsync());check(rig.c.preparedBatch().isEmpty(),"Cierre no conserva lote ejecutable");
        }
    }
    public static void importedAndManualRequestsUseSameAdmissionPath() throws Exception {
        try(var rig=new Rig(CONFIG,(p,c)->parsed("CABIN 1 2",c))) {
            var batch=get(rig.c.importRequests(Path.of("a"))).batch().orElseThrow();get(rig.c.start());rig.served(batch);
            var manual=cabin(0,4,1);var submitted=get(rig.c.submit(manual));rig.until(COMMAND_COMPLETED,4);
            check(submitted.command().orElseThrow().commandId()>rig.c.lastBatchDispatch().orElseThrow().entries().get(0).result().command().orElseThrow().commandId(),"Generador compartido sin reinicio");
        }
    }
    public static void cancellationWithoutReadDoesNotEraseBatch() throws Exception {
        try(var rig=new Rig(CONFIG,(p,c)->parsed("CABIN 1 2",c))) {
            var batch=get(rig.c.importRequests(Path.of("a"))).batch().orElseThrow();get(rig.c.cancelImport());
            check(rig.c.preparedBatch().orElseThrow()==batch,"Cancelar selector sin lectura no borra preparado");
        }
    }
    public static void staleInvalidResultCannotEraseNewerBatch() throws Exception {
        var gate=new GateLoader(2,true);
        try(var rig=new Rig(CONFIG,gate)) {
            try {
                get(rig.c.importRequests(Path.of("a")));var obsolete=rig.c.importRequests(Path.of("b"));await(gate.entered);
                var latest=rig.c.importRequests(Path.of("c"));check(get(obsolete).outcome()==SUPERSEDED,"Error viejo invalidado");
                gate.release.countDown();var fresh=get(latest).batch().orElseThrow();check(rig.c.preparedBatch().orElseThrow()==fresh,"Error tardío no borra nuevo");
            } finally {gate.release.countDown();}
        }
    }
    public static void publicRecordsAreImmutableAndValidateCorrelation() throws Exception {
        try(var rig=new Rig(CONFIG,(p,c)->parsed("CABIN 1 2",c))) {
            var result=get(rig.c.importRequests(Path.of("a")));var batch=result.batch().orElseThrow();
            expect(UnsupportedOperationException.class,()->batch.requests().clear());
            expect(IllegalArgumentException.class,()->new ImportedRequestBatch(batch.id(),0,batch.confirmedAt(),batch.source(),List.of(cabin(0,3,1))));
            expect(IllegalArgumentException.class,()->new BatchImportResult(IMPORTED,SimulationState.RUNNING,0,rig.c.sessionId(),1,"mal",Optional.of(batch),List.of()));
            get(rig.c.start());var report=rig.c.lastBatchDispatch().orElseThrow();expect(UnsupportedOperationException.class,()->report.entries().clear());
            expect(IllegalArgumentException.class,()->new BatchDispatchReport(batch,List.of()));
            expect(IllegalArgumentException.class,()->new BatchDispatchReport(batch,List.of(new BatchDispatchReport.Entry(1,batch.requests().get(0).getId(),
                    new SimulationController.Result(SimulationController.Outcome.APPLIED,SimulationState.RUNNING,"No es un envío",Optional.empty(),0)))));
            expect(NullPointerException.class,()->rig.c.importRequests(null));
        }
    }
    public static void repeatedSessionsReleaseEveryOwnedThread() throws Exception {
        for(int i=0;i<10;i++) {
            var rig=new Rig(CONFIG,(p,c)->parsed("CABIN 1 2",c));
            List<Thread> owned;
            try {
                for(int j=0;j<3;j++) {get(rig.c.importRequests(Path.of("a")));}get(rig.c.start());
                owned=Thread.getAllStackTraces().keySet().stream().filter(t->t.getName().equals("simulation-import-"+rig.c.sessionId())
                        || t.getName().equals("simulation-controller") || t.getName().startsWith("elevator-worker-") || t.getName().startsWith("manager-worker-")).toList();
            } finally {rig.close();}
            check(owned.stream().noneMatch(Thread::isAlive),"Sin hilos de sesión tras cerrar");
        }
    }

    private static final class GateLoader implements SimulationController.BatchLoader {
        final int blockedCall;final boolean invalidBlocked;final AtomicInteger calls=new AtomicInteger();
        final CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        final AtomicReference<Thread> thread=new AtomicReference<>();
        final java.util.Set<Thread> threads=java.util.concurrent.ConcurrentHashMap.newKeySet();
        GateLoader(int blockedCall){this(blockedCall,false);}
        GateLoader(int blockedCall,boolean invalidBlocked){this.blockedCall=blockedCall;this.invalidBlocked=invalidBlocked;}
        @Override public BatchReadResult read(Path path,SimulationConfig config) {
            int call=calls.incrementAndGet();threads.add(Thread.currentThread());
            String text=path.toString().equals("a")?"CABIN 1 2":path.toString().equals("b")?"CABIN 1 6":"CABIN 1 7";
            var parsed=parsed(call==blockedCall && invalidBlocked?"HALL 8 UP":text,config);
            if(call==blockedCall) {
                thread.set(Thread.currentThread());entered.countDown();boolean interrupted=false;
                try {
                    long deadline=deadline();
                    while(true) {
                        long left=deadline-System.nanoTime();check(left>0,"Lector retenido no liberado");
                        try {check(release.await(left,TimeUnit.NANOSECONDS),"Liberar lector");break;}
                        catch(InterruptedException ignored){interrupted=true;}
                    }
                } finally {if(interrupted){Thread.currentThread().interrupt();}}
            }
            return parsed;
        }
    }
    private static final class Rig implements AutoCloseable {
        final SimulationController c;
        Rig(SimulationConfig config){c=new SimulationController(config,0);}
        Rig(SimulationConfig config,SimulationController.BatchLoader loader){c=SimulationController.withBatchLoader(config,0,loader);}
        void until(ExecutionEvent.Type type,int floor)throws Exception {
            long deadline=deadline();while(true){var event=next(deadline);if(event.type()==type && event.snapshot().currentFloor()==floor){return;}}
        }
        ExecutionEvent next(long deadline)throws Exception {
            long left=deadline-System.nanoTime();check(left>0,"Plazo de evento agotado");
            return c.awaitEvent(Duration.ofNanos(left)).orElseThrow(()->new AssertionError("Falta evento"));
        }
        void served(ImportedRequestBatch batch)throws Exception {
            var pending=new HashSet<UUID>();batch.requests().forEach(r->pending.add(r.getId()));long deadline=deadline();
            while(!pending.isEmpty()) {var event=next(deadline);if(event.type()==COMMAND_COMPLETED){event.canonicalRequestId().ifPresent(pending::remove);}}
        }
        @Override public void close()throws Exception {get(c.closeAsync());check(c.awaitTermination(Duration.ofSeconds(10)),"Cierre real");check(c.failure().isEmpty(),"Sin fallos de sesión");}
    }
    private static java.util.Set<Thread> actorThreads(){var set=new HashSet<Thread>();Thread.getAllStackTraces().keySet().stream().filter(t->t.getName().startsWith("elevator-worker-") || t.getName().startsWith("manager-worker-")).forEach(set::add);return set;}
    private static BatchReadResult parsed(String text,SimulationConfig config){return READER.parse(text.getBytes(StandardCharsets.UTF_8),config);}
    private static CabinRequest cabin(long epoch,int floor,int id){return new CabinRequest(UUID.randomUUID(),epoch,floor,Instant.now(),id);}
    private static Path file(Path directory,String name,String text)throws IOException{return Files.writeString(directory.resolve(name+".txt"),text,StandardCharsets.UTF_8);}
    private static long deadline(){return System.nanoTime()+TimeUnit.SECONDS.toNanos(10);}
    private static void await(CountDownLatch latch)throws InterruptedException{check(latch.await(10,TimeUnit.SECONDS),"Barrera no alcanzada");}
    private static void until(BooleanSupplier condition){long deadline=deadline();while(!condition.getAsBoolean()){check(System.nanoTime()<deadline,"Condición no alcanzada");LockSupport.parkNanos(1_000_000);}}
    private static <T>T get(CompletableFuture<T> future)throws Exception{return future.get(15,TimeUnit.SECONDS);}
    private static void check(boolean condition,String message){if(!condition){throw new AssertionError(message);}}
    private interface Action{void run()throws Exception;}
    private interface DirectoryAction{void run(Path path)throws Exception;}
    private static void expect(Class<? extends Throwable> type,Action action)throws Exception{try{action.run();}catch(Throwable error){if(type.isInstance(error)){return;}throw new AssertionError("Excepción inesperada",error);}throw new AssertionError("Falta excepción "+type.getSimpleName());}
    private static void inDirectory(DirectoryAction action)throws Exception{Path directory=Files.createTempDirectory("t45-");try{action.run(directory);}finally{try(var paths=Files.walk(directory)){for(Path path:paths.sorted(Comparator.reverseOrder()).toList()){Files.delete(path);}}}}
    public static void main(String[]args)throws Exception {
        initialImportDoesNotStartActors();importAfterResetKeepsCabinsIdle();startingServicesMixedImportedBatch();repeatedStartNeverReleasesBatchAgain();
        stopStartContinuesOriginalBatchRequests();validReplacementIsAtomicAndUsesFreshIdentities();invalidFinalLinePreservesPreviousBatch();
        missingFilePreservesPreviousBatch();emptyFilePreservesPreviousBatch();duplicateEntriesRemainDistinctAtConfirmation();
        runningImportIsRejectedBeforeReading();stoppedImportIsRejectedBeforeReading();stoppingImportIsRejectedBeforeReading();resettingImportIsRejectedBeforeReading();
        closingAndClosedImportsAreRejected();startDuringReadInvalidatesLateSuccess();resetDuringReadRejectsOldEpochEvenBackInReset();
        resetClearsConfirmedBatchBeforeNextStart();closeWaitsForActualReaderTermination();newestReadSupersedesAndPreservesOldBatchWhilePending();
        repeatedSupersedingKeepsOneQueuedRead();explicitCancellationKeepsPreviousBatch();cancellingVisibleFutureDoesNotCancelOperation();
        incompatibleConfigurationCannotReplaceBatch();loaderExceptionIsInvalidAndKeepsOriginalCause();nullLoaderResultCannotLeaveUnresolvedFuture();
        edtAndCoordinatorStayResponsiveDuringRead();confirmationDoesNotConsumeCommandIds();receiptsAreBoundToTheirSession();delayedReceiptIsRejectedAfterReplacement();
        confirmedRequestsShareTimeAndHaveUniqueIds();dispatchReportPreservesOrderWithoutClaimingService();importingAfterResetKeepsActorThreadIdentities();
        importingAfterCompletedResetUsesNewEpoch();closeClearsConfirmedBatch();importedAndManualRequestsUseSameAdmissionPath();
        cancellationWithoutReadDoesNotEraseBatch();staleInvalidResultCannotEraseNewerBatch();publicRecordsAreImmutableAndValidateCorrelation();repeatedSessionsReleaseEveryOwnedThread();
        System.out.println("T45: 40 escenarios satisfactorios.");
    }
}
```

## src/test/java/com/elevatormanager/simulation/RequestImportTest.java

```java
package com.elevatormanager.simulation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Los mismos 40 escenarios de importación y ciclo de vida, con actores reales. */
@Timeout(60)
class RequestImportTest {
    @Test void initialImportDoesNotStartActors() throws Exception {
        RequestImportScenarios.initialImportDoesNotStartActors();
    }
    @Test void importAfterResetKeepsCabinsIdle() throws Exception {
        RequestImportScenarios.importAfterResetKeepsCabinsIdle();
    }
    @Test void startingServicesMixedImportedBatch() throws Exception {
        RequestImportScenarios.startingServicesMixedImportedBatch();
    }
    @Test void repeatedStartNeverReleasesBatchAgain() throws Exception {
        RequestImportScenarios.repeatedStartNeverReleasesBatchAgain();
    }
    @Test void stopStartContinuesOriginalBatchRequests() throws Exception {
        RequestImportScenarios.stopStartContinuesOriginalBatchRequests();
    }
    @Test void validReplacementIsAtomicAndUsesFreshIdentities() throws Exception {
        RequestImportScenarios.validReplacementIsAtomicAndUsesFreshIdentities();
    }
    @Test void invalidFinalLinePreservesPreviousBatch() throws Exception {
        RequestImportScenarios.invalidFinalLinePreservesPreviousBatch();
    }
    @Test void missingFilePreservesPreviousBatch() throws Exception {
        RequestImportScenarios.missingFilePreservesPreviousBatch();
    }
    @Test void emptyFilePreservesPreviousBatch() throws Exception {
        RequestImportScenarios.emptyFilePreservesPreviousBatch();
    }
    @Test void duplicateEntriesRemainDistinctAtConfirmation() throws Exception {
        RequestImportScenarios.duplicateEntriesRemainDistinctAtConfirmation();
    }
    @Test void runningImportIsRejectedBeforeReading() throws Exception {
        RequestImportScenarios.runningImportIsRejectedBeforeReading();
    }
    @Test void stoppedImportIsRejectedBeforeReading() throws Exception {
        RequestImportScenarios.stoppedImportIsRejectedBeforeReading();
    }
    @Test void stoppingImportIsRejectedBeforeReading() throws Exception {
        RequestImportScenarios.stoppingImportIsRejectedBeforeReading();
    }
    @Test void resettingImportIsRejectedBeforeReading() throws Exception {
        RequestImportScenarios.resettingImportIsRejectedBeforeReading();
    }
    @Test void closingAndClosedImportsAreRejected() throws Exception {
        RequestImportScenarios.closingAndClosedImportsAreRejected();
    }
    @Test void startDuringReadInvalidatesLateSuccess() throws Exception {
        RequestImportScenarios.startDuringReadInvalidatesLateSuccess();
    }
    @Test void resetDuringReadRejectsOldEpochEvenBackInReset() throws Exception {
        RequestImportScenarios.resetDuringReadRejectsOldEpochEvenBackInReset();
    }
    @Test void resetClearsConfirmedBatchBeforeNextStart() throws Exception {
        RequestImportScenarios.resetClearsConfirmedBatchBeforeNextStart();
    }
    @Test void closeWaitsForActualReaderTermination() throws Exception {
        RequestImportScenarios.closeWaitsForActualReaderTermination();
    }
    @Test void newestReadSupersedesAndPreservesOldBatchWhilePending() throws Exception {
        RequestImportScenarios.newestReadSupersedesAndPreservesOldBatchWhilePending();
    }
    @Test void repeatedSupersedingKeepsOneQueuedRead() throws Exception {
        RequestImportScenarios.repeatedSupersedingKeepsOneQueuedRead();
    }
    @Test void explicitCancellationKeepsPreviousBatch() throws Exception {
        RequestImportScenarios.explicitCancellationKeepsPreviousBatch();
    }
    @Test void cancellingVisibleFutureDoesNotCancelOperation() throws Exception {
        RequestImportScenarios.cancellingVisibleFutureDoesNotCancelOperation();
    }
    @Test void incompatibleConfigurationCannotReplaceBatch() throws Exception {
        RequestImportScenarios.incompatibleConfigurationCannotReplaceBatch();
    }
    @Test void loaderExceptionIsInvalidAndKeepsOriginalCause() throws Exception {
        RequestImportScenarios.loaderExceptionIsInvalidAndKeepsOriginalCause();
    }
    @Test void nullLoaderResultCannotLeaveUnresolvedFuture() throws Exception {
        RequestImportScenarios.nullLoaderResultCannotLeaveUnresolvedFuture();
    }
    @Test void edtAndCoordinatorStayResponsiveDuringRead() throws Exception {
        RequestImportScenarios.edtAndCoordinatorStayResponsiveDuringRead();
    }
    @Test void confirmationDoesNotConsumeCommandIds() throws Exception {
        RequestImportScenarios.confirmationDoesNotConsumeCommandIds();
    }
    @Test void receiptsAreBoundToTheirSession() throws Exception {
        RequestImportScenarios.receiptsAreBoundToTheirSession();
    }
    @Test void delayedReceiptIsRejectedAfterReplacement() throws Exception {
        RequestImportScenarios.delayedReceiptIsRejectedAfterReplacement();
    }
    @Test void confirmedRequestsShareTimeAndHaveUniqueIds() throws Exception {
        RequestImportScenarios.confirmedRequestsShareTimeAndHaveUniqueIds();
    }
    @Test void dispatchReportPreservesOrderWithoutClaimingService() throws Exception {
        RequestImportScenarios.dispatchReportPreservesOrderWithoutClaimingService();
    }
    @Test void importingAfterResetKeepsActorThreadIdentities() throws Exception {
        RequestImportScenarios.importingAfterResetKeepsActorThreadIdentities();
    }
    @Test void importingAfterCompletedResetUsesNewEpoch() throws Exception {
        RequestImportScenarios.importingAfterCompletedResetUsesNewEpoch();
    }
    @Test void closeClearsConfirmedBatch() throws Exception {
        RequestImportScenarios.closeClearsConfirmedBatch();
    }
    @Test void importedAndManualRequestsUseSameAdmissionPath() throws Exception {
        RequestImportScenarios.importedAndManualRequestsUseSameAdmissionPath();
    }
    @Test void cancellationWithoutReadDoesNotEraseBatch() throws Exception {
        RequestImportScenarios.cancellationWithoutReadDoesNotEraseBatch();
    }
    @Test void staleInvalidResultCannotEraseNewerBatch() throws Exception {
        RequestImportScenarios.staleInvalidResultCannotEraseNewerBatch();
    }
    @Test void publicRecordsAreImmutableAndValidateCorrelation() throws Exception {
        RequestImportScenarios.publicRecordsAreImmutableAndValidateCorrelation();
    }
    @Test void repeatedSessionsReleaseEveryOwnedThread() throws Exception {
        RequestImportScenarios.repeatedSessionsReleaseEveryOwnedThread();
    }
}
```

