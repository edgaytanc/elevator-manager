# T59 — Código Java completo

Los siete archivos de producción y pruebas añadidos/modificados por T59, íntegros, sin fragmentos ni elipsis. Las fuentes restantes están completas en el ZIP.

## src/main/java/com/elevatormanager/simulation/SimulationController.java

```java
package com.elevatormanager.simulation;

import com.elevatormanager.io.BatchDiagnostic;
import com.elevatormanager.simulation.time.PauseCoordinator;
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
    private final TraceMailbox trace;
    private final SimulationConfig config;
    private final PauseCoordinator pauseCoordinator;
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
    private CompletableFuture<Result> pauseCompletion;
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
        pauseCoordinator = new PauseCoordinator(Math.addExact(config.elevatorCount(), 1));
        trace = sessionLog.map(SessionLog::mailbox).orElse(TraceMailbox.disabled());
        elevators = java.util.stream.IntStream.rangeClosed(1, config.elevatorCount())
                .mapToObj(id -> new ElevatorWorker(id, config, epoch, trace, pauseCoordinator)).toList();
        elevators.forEach(e -> e.subscribeEvents(events));
        manager = new ManagerWorker(config, epoch, elevators, this::nextCommandId, trace, pauseCoordinator);
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

    /** T59: reloj activo compartido con referenceNanos de los snapshots.
     * Solo observación; consultar fuera del EDT al capturar el conjunto visual.
     */
    public long activeTimeNanos() { return pauseCoordinator.nowNanos(); }
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
                    trace.managerState(epoch, "SIMULATION_RUNNING", null);
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
                trace.managerState(epoch, "SIMULATION_RUNNING", null);
                releasePreparedBatch();
                return result(Outcome.APPLIED, "Trabajadores iniciados una sola vez.");
            } catch (RuntimeException | Error exception) {
                failure = exception;
                beginClose();
                throw exception;
            }
        });
    }

    /** Progreso de la barrera; leerlo no espera ni consume eventos. */
    public PauseCoordinator.Status pauseStatus() { return pauseCoordinator.status(); }

    /** RUNNING -> PAUSING -> PAUSED. El resultado público no cancela la operación privada. */
    public CompletableFuture<Result> pause() {
        return scheduleAsync(() -> {
            if (state == SimulationState.PAUSING) { return pauseCompletion; }
            if (state == SimulationState.PAUSED) {
                return CompletableFuture.completedFuture(result(Outcome.UNCHANGED, "La sesión ya está pausada."));
            }
            if (state != SimulationState.RUNNING) {
                return CompletableFuture.completedFuture(result(Outcome.REJECTED, "Pausa no permitida en " + state));
            }
            state = SimulationState.PAUSING;
            trace.managerState(epoch, "SIMULATION_PAUSING", null);
            var completion = new CompletableFuture<Result>();
            pauseCompletion = completion;
            var barrier = pauseCoordinator.requestPause();
            manager.wakeForPause();
            elevators.forEach(ElevatorWorker::wakeForPause);
            barrier.whenComplete((ignored, error) -> schedule(() -> {
                if (state != SimulationState.PAUSING || pauseCompletion != completion || completion.isDone()) {
                    return result(Outcome.UNCHANGED, "Confirmación de pausa fuera de vigencia.");
                }
                if (error != null || manager.lifecycle() != ManagerWorker.Lifecycle.RUNNING
                        || elevators.stream().anyMatch(e -> e.lifecycle() != ElevatorWorker.Lifecycle.RUNNING)) {
                    Throwable cause = error != null ? error : new IllegalStateException("Participante de pausa no disponible.");
                    failure = cause;
                    completion.completeExceptionally(cause);
                    beginClose();
                } else {
                    state = SimulationState.PAUSED;
                    trace.managerState(epoch, "SIMULATION_PAUSED", null);
                    completion.complete(result(Outcome.APPLIED, "Manejador y todas las cabinas reconocieron la pausa."));
                }
                return result(Outcome.UNCHANGED, "Barrera de pausa procesada.");
            }));
            return completion;
        });
    }

    /** PAUSED -> RUNNING; libera a todos con el mismo reloj y sin reenviar comandos. */
    public CompletableFuture<Result> resume() {
        return schedule(() -> {
            if (state == SimulationState.RUNNING) { return result(Outcome.UNCHANGED, "La sesión ya está activa."); }
            if (state != SimulationState.PAUSED) { return result(Outcome.REJECTED, "Reanudar requiere PAUSED: " + state); }
            state = SimulationState.RUNNING;
            trace.managerState(epoch, "SIMULATION_RUNNING", null);
            if (!pauseCoordinator.resume()) {
                var error = new IllegalStateException("Barrera de reanudación incompleta.");
                failure = error;
                beginClose();
                throw error;
            }
            trace.managerState(epoch, "SIMULATION_RESUMED", null);
            return result(Outcome.APPLIED, "Mismos actores, comandos y tiempo restante; ejecución reanudada.");
        });
    }

    /** RUNNING -> STOPPING -> STOPPED; el futuro confirma toda la barrera. */
    public CompletableFuture<Result> stop() {
        return scheduleAsync(() -> {
            if (state == SimulationState.STOPPING) { return stopCompletion; }
            if (state == SimulationState.RESET || state == SimulationState.STOPPED) {
                return CompletableFuture.completedFuture(result(Outcome.UNCHANGED, "No hay avance que detener."));
            }
            if (state != SimulationState.RUNNING && state != SimulationState.PAUSING && state != SimulationState.PAUSED) {
                return CompletableFuture.completedFuture(result(Outcome.REJECTED, "Stop no permitido en " + state));
            }
            boolean fromPause = state == SimulationState.PAUSING || state == SimulationState.PAUSED;
            state = SimulationState.STOPPING;
            trace.managerState(epoch, "SIMULATION_STOPPING", null);
            var completion = new CompletableFuture<Result>();
            stopCompletion = completion;
            var acknowledgements = new java.util.ArrayList<CompletableFuture<Void>>();
            acknowledgements.add(manager.requestStop());
            for (var elevator : elevators) { acknowledgements.add(elevator.requestStop()); }
            if (fromPause) { supersedePause("STOP"); }
            CompletableFuture.allOf(acknowledgements.toArray(CompletableFuture[]::new))
                    .whenComplete((ignored, error) -> schedule(() -> {
                        if (state == SimulationState.STOPPING && stopCompletion == completion) {
                            if (error != null) {
                                failure = error;
                                completion.completeExceptionally(error);
                                beginClose();
                            } else {
                                state = SimulationState.STOPPED;
                                trace.managerState(epoch, "SIMULATION_STOPPED", null);
                                completion.complete(result(Outcome.APPLIED, "Despacho y cabinas detenidos; pendientes conservados."));
                            }
                        }
                        return result(Outcome.UNCHANGED, "Confirmación de control procesada.");
                    }));
            return completion;
        });
    }

    /** Solo coordinador: se llama DESPUÉS de encolar el control en los N+1 actores. */
    private void supersedePause(String control) {
        if (pauseCompletion != null && !pauseCompletion.isDone()) {
            pauseCompletion.complete(result(Outcome.REJECTED, "Pausa sustituida por " + control + "."));
        }
        trace.managerState(epoch, "PAUSE_SUPERSEDED_BY_" + control, null);
        pauseCoordinator.releaseForControl("Pausa sustituida por " + control);
    }

    /** Una época por operación; RESET se confirma solo tras limpieza y N retornos. */
    public CompletableFuture<Result> reset() {
        return scheduleAsync(() -> {
            if (state == SimulationState.RESETTING) { return resetCompletion; }
            if (state != SimulationState.RESET && state != SimulationState.RUNNING
                    && state != SimulationState.STOPPING && state != SimulationState.STOPPED
                    && state != SimulationState.PAUSING && state != SimulationState.PAUSED) {
                return CompletableFuture.completedFuture(result(Outcome.REJECTED, "Reset no permitido en " + state));
            }
            boolean fromPause = state == SimulationState.PAUSING || state == SimulationState.PAUSED;
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
                trace.managerState(epoch, "SIMULATION_RESETTING", null);
                if (stopCompletion != null && !stopCompletion.isDone()) {
                    stopCompletion.complete(result(Outcome.REJECTED, "Stop sustituido por reset global."));
                }
                var managerAck = manager.requestReset(targetEpoch);
                var cabinAcks = new java.util.ArrayList<CompletableFuture<ElevatorSnapshot>>();
                for (int i = 0; i < elevators.size(); i++) {
                    cabinAcks.add(elevators.get(i).requestReset(commands.get(i)));
                }
                if (fromPause) { supersedePause("RESET"); }
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
            trace.managerState(epoch, "SIMULATION_RESET", null);
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
        trace.managerState(epoch, "SIMULATION_CLOSING", null);
        invalidateImport(BatchImportResult.Outcome.REJECTED, "Cierre invalidó la lectura pendiente.");
        preparedBatch = Optional.empty();
        importExecutor.shutdownNow();
        if (resetCompletion != null && !resetCompletion.isDone()) {
            resetCompletion.complete(result(Outcome.REJECTED, "Reset interrumpido por cierre."));
        }
        if (stopCompletion != null && !stopCompletion.isDone()) {
            stopCompletion.complete(result(Outcome.REJECTED, "Stop interrumpido por cierre."));
        }
        if (pauseCoordinator.isRequested()) { trace.managerState(epoch, "PAUSE_SUPERSEDED_BY_CLOSE", null); }
        if (pauseCompletion != null && !pauseCompletion.isDone()) {
            pauseCompletion.complete(result(Outcome.REJECTED, "Pausa interrumpida por cierre."));
        }
        // Cierre primero la compuerta: ningún checkpoint liberado admite más servicio.
        pauseCoordinator.close();
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

## src/main/java/com/elevatormanager/ui/UiSnapshotPump.java

```java
package com.elevatormanager.ui;

import com.elevatormanager.dispatch.HallCallSnapshot;
import com.elevatormanager.model.ElevatorSnapshot;
import com.elevatormanager.model.SimulationState;
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
            Map<Integer, List<HallCallSnapshot>> assigned, Optional<Timing> timing) {
        /** Compatibilidad de capturas sin muestra temporal: solo progreso ya publicado. */
        public Frame(UUID sessionId, long epoch, List<ElevatorSnapshot> snapshots,
                Map<Integer, List<HallCallSnapshot>> assigned) {
            this(sessionId, epoch, snapshots, assigned, Optional.empty());
        }
        public Frame {
            Objects.requireNonNull(timing, "timing");
            Objects.requireNonNull(sessionId, "sessionId"); snapshots = List.copyOf(snapshots);
            var copy = new HashMap<Integer, List<HallCallSnapshot>>();
            assigned.forEach((id, calls) -> copy.put(id, List.copyOf(calls)));
            assigned = Map.copyOf(copy);
        }
    }
    /** Ancla entre relojes distintos; wallNanos nunca se resta de referenceNanos. */
    public record Timing(long activeNanos, long wallNanos, boolean advancing) {
        public long at(long currentWallNanos) {
            return activeNanos + (advancing ? Math.max(0L, currentWallNanos - wallNanos) : 0L);
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
        // La lectura del reloj y cualquier lock del motor permanecen fuera del EDT.
        SimulationState state = controller.state();
        long active = controller.activeTimeNanos();
        long wall = System.nanoTime();
        boolean advancing = state == SimulationState.RUNNING || state == SimulationState.STOPPING
                || state == SimulationState.RESETTING;
        var timing = new Timing(active, wall, advancing);
        // No es una captura atómica global. Descarta también transiciones durante el muestreo.
        return epoch == controller.epoch() && state == controller.state()
                ? new Frame(controller.sessionId(), epoch, snapshots, assigned, Optional.of(timing)) : null;
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

## src/main/java/com/elevatormanager/ui/CabinMotion.java

```java
package com.elevatormanager.ui;

import com.elevatormanager.model.ElevatorPhase;
import com.elevatormanager.model.ElevatorSnapshot;
import java.util.Objects;

/** T59: proyección visual pura de un único tramo publicado; no confirma llegadas. */
final class CabinMotion {
    private CabinMotion() { }

    /** activeNanos pertenece al mismo reloj lógico que snapshot.referenceNanos(). */
    static double position(ElevatorSnapshot snapshot, long activeNanos) {
        Objects.requireNonNull(snapshot, "snapshot");
        boolean moving = snapshot.phase() == ElevatorPhase.MOVING;
        boolean heldMoving = snapshot.phase() == ElevatorPhase.HELD
                && snapshot.suspendedPhase().orElseThrow() == ElevatorPhase.MOVING;
        if (!moving && !heldMoving) return snapshot.currentFloor();
        long duration = snapshot.phaseDurationNanos();
        long spent = duration - snapshot.remainingNanos();
        // Restar las marcas admite wrap monotónico dentro de un intervalo < Long.MAX_VALUE.
        long elapsed = moving ? Math.max(0L, activeNanos - snapshot.referenceNanos()) : 0L;
        long extra = Math.min(snapshot.remainingNanos(), elapsed);
        // Sumar solo hasta remaining impide overflow y no extrapola otro tramo.
        double progress = Math.min(1.0, (double) (spent + extra) / duration);
        return snapshot.currentFloor()
                + (snapshot.nextFloor().orElseThrow() - snapshot.currentFloor()) * progress;
    }
}
```

## src/main/java/com/elevatormanager/ui/BuildingPanel.java

```java
package com.elevatormanager.ui;

import com.elevatormanager.model.ElevatorSnapshot;
import com.elevatormanager.model.SimulationConfig;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.LongSupplier;
import javax.swing.JPanel;
import javax.swing.JViewport;
import javax.swing.Scrollable;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;

/** T59: edificio virtual interpolado desde snapshots y reloj activo muestreado.
 * La representación nunca modifica el piso lógico ni confirma llegadas.
 */
public final class BuildingPanel extends JPanel implements Scrollable {
    private static final long serialVersionUID = 1L;
    static final Color CABIN_COLOR = new Color(34, 100, 176);
    private UUID sessionId;
    private SimulationConfig config;
    private List<ElevatorSnapshot> snapshots = List.of();
    private UiSnapshotPump.Frame rendered;
    private final LongSupplier wallClock;

    public BuildingPanel() { this(System::nanoTime); }

    /** Reloj de presentación inyectable; no sustituye el reloj del motor. */
    BuildingPanel(LongSupplier wallClock) {
        requireEdt();
        this.wallClock = Objects.requireNonNull(wallClock, "wallClock");
        setBackground(Color.WHITE);
        getAccessibleContext().setAccessibleName("Edificio: pisos y cabinas identificados por número");
    }

    public void bind(UUID session, SimulationConfig configuration) {
        requireEdt();
        sessionId = Objects.requireNonNull(session, "session");
        config = Objects.requireNonNull(configuration, "configuration");
        snapshots = List.of(); rendered = null;
        revalidate(); repaint();
    }

    /** Solo acepta la sesión/época vigente. Reutiliza el conjunto inmutable de T52. */
    public void render(UiSnapshotPump.Frame frame, long currentEpoch) {
        requireEdt();
        if (config == null || !frame.sessionId().equals(sessionId) || frame.epoch() != currentEpoch
                || frame.snapshots().size() != config.elevatorCount() || frame == rendered) return;
        snapshots = frame.snapshots(); rendered = frame;
        repaint();
    }

    @Override public Dimension getPreferredSize() {
        if (config == null) return new Dimension(600, 320);
        return new Dimension(extent(config.elevatorCount(), 112, Geometry.LEFT + Geometry.RIGHT),
                extent(config.floorCount(), 36, Geometry.TOP + Geometry.BOTTOM));
    }

    /** Satura con long antes de convertir: las cantidades válidas no desbordan Swing. */
    private static int extent(int count, int unit, int margin) {
        return (int) Math.min(Integer.MAX_VALUE - 1024L, (long) count * unit + margin);
    }

    Geometry geometry() {
        requireEdt();
        if (config == null) throw new IllegalStateException("Edificio sin configurar.");
        var minimum = getPreferredSize();
        return new Geometry(config.elevatorCount(), config.floorCount(),
                Math.max(getWidth(), minimum.width), Math.max(getHeight(), minimum.height));
    }

    @Override protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        var g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(new Color(32, 46, 64));
            if (config == null) {
                g.drawString("Valida la configuración para mostrar el edificio.", 16, 30); return;
            }
            var layout = geometry();
            Rectangle clip = g.getClipBounds();
            if (clip == null) clip = new Rectangle(0, 0, getWidth(), getHeight());
            int firstFloor = layout.floorAt((double) clip.y + clip.height);
            int lastFloor = layout.floorAt(clip.y);
            for (int floor = firstFloor; ; floor++) {
                int y = layout.floorY(floor);
                g.setColor(new Color(222, 228, 235));
                g.drawLine(Geometry.LEFT, y, layout.width() - Geometry.RIGHT, y);
                g.setColor(new Color(32, 46, 64));
                g.drawString("Piso " + floor, 8, y + 5);
                if (floor == lastFloor) break;
            }
            long wallNow = wallClock.getAsLong();
            int firstCabin = layout.cabinAt(clip.x);
            int lastCabin = layout.cabinAt((double) clip.x + clip.width);
            for (int id = firstCabin; ; id++) {
                int x = layout.cabinX(id);
                int shaftWidth = Math.max(1, (int) Math.min(96, layout.laneWidth() - 12));
                g.setColor(new Color(231, 237, 244));
                g.fillRect(x - shaftWidth / 2, Geometry.TOP, shaftWidth,
                        layout.height() - Geometry.TOP - Geometry.BOTTOM);
                g.setColor(new Color(32, 46, 64));
                String title = "Elevador " + id;
                g.drawString(title, x - g.getFontMetrics().stringWidth(title) / 2, 25);
                Rectangle cabin = layout.cabinBounds(id, positionAt(id, wallNow));
                if (clip.intersects(cabin)) {
                    g.setColor(CABIN_COLOR);
                    g.fillRoundRect(cabin.x, cabin.y, cabin.width, cabin.height, 8, 8);
                    g.setColor(Color.WHITE);
                    String label = "E" + id;
                    g.drawString(label, x - g.getFontMetrics().stringWidth(label) / 2,
                            cabin.y + (cabin.height + g.getFontMetrics().getAscent()) / 2 - 2);
                }
                if (id == lastCabin) break;
            }
        } finally { g.dispose(); }
    }

    int displayedFloor(int id) {
        requireEdt();
        if (config == null || id < 1 || id > config.elevatorCount())
            throw new IllegalArgumentException("Cabina fuera de rango.");
        return snapshots.isEmpty() ? 1 : snapshots.get(id - 1).currentFloor();
    }

    /** Piso fraccional de presentación, distinto de displayedFloor (piso lógico). */
    double displayedPosition(int id) {
        requireEdt();
        return positionAt(id, wallClock.getAsLong());
    }

    private double positionAt(int id, long wallNow) {
        int logicalFloor = displayedFloor(id);
        if (snapshots.isEmpty()) return logicalFloor;
        var snapshot = snapshots.get(id - 1);
        long active = rendered.timing().map(sample -> sample.at(wallNow)).orElse(snapshot.referenceNanos());
        return CabinMotion.position(snapshot, active);
    }

    /** Un tick solo invalida la región visible; no captura ni recorre todo N/P. */
    void refreshAnimation() {
        requireEdt();
        if (config != null && isShowing()) repaint(getVisibleRect());
    }

    @Override public Dimension getPreferredScrollableViewportSize() { return new Dimension(600, 320); }
    @Override public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) { return 36; }
    @Override public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) {
        return Math.max(36, (orientation == SwingConstants.VERTICAL ? visible.height : visible.width) - 36);
    }
    @Override public boolean getScrollableTracksViewportWidth() {
        return getParent() instanceof JViewport viewport && viewport.getWidth() >= getPreferredSize().width;
    }
    @Override public boolean getScrollableTracksViewportHeight() {
        return getParent() instanceof JViewport viewport && viewport.getHeight() >= getPreferredSize().height;
    }

    /** Coordenadas puras: piso 1 abajo; no divide entre P-1, admite P=1. */
    record Geometry(int cabins, int floors, int width, int height) {
        static final int LEFT = 112, RIGHT = 20, TOP = 48, BOTTOM = 20;
        double laneWidth() { return (width - LEFT - RIGHT) / (double) cabins; }
        double floorHeight() { return (height - TOP - BOTTOM) / (double) floors; }
        int floorY(double floor) { return (int) Math.round(TOP + (floors - floor + 0.5) * floorHeight()); }
        int cabinX(int id) { return (int) Math.round(LEFT + (id - 0.5) * laneWidth()); }
        int floorAt(double y) {
            return (int) Math.max(1, Math.min(floors, floors - Math.floor((y - TOP) / floorHeight())));
        }
        int cabinAt(double x) {
            return (int) Math.max(1, Math.min(cabins, 1 + Math.floor((x - LEFT) / laneWidth())));
        }
        Rectangle cabinBounds(int id, double floor) {
            int w = Math.max(1, (int) Math.min(76, laneWidth() - 24));
            int h = Math.max(1, (int) Math.min(28, floorHeight() * 0.7));
            return new Rectangle(cabinX(id) - w / 2, floorY(floor) - h / 2, w, h);
        }
    }

    private static void requireEdt() {
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("El edificio requiere EDT.");
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
    public enum Action { START, PAUSE, RESUME, STOP, RESET, LOAD, CLOSE }
    @FunctionalInterface interface SessionOpener {
        SimulationController open(SimulationConfig config) throws Exception;
    }
    private final ConfigurationPanel configuration;
    private final HallCallPanel hallCalls;
    private final CabinDashboardPanel cabinDashboard;
    private final BuildingPanel buildingPanel;
    private final JScrollPane buildingScroll;
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
        buildingPanel = new BuildingPanel();
        buildingScroll = new JScrollPane(buildingPanel);
        buildingScroll.getAccessibleContext().setAccessibleName("Recorrido del edificio");
        var buildingView = new JPanel(new BorderLayout());
        buildingView.add(new JLabel("Movimiento de cabinas · usa las barras para recorrer el edificio"), BorderLayout.NORTH);
        buildingView.add(buildingScroll, BorderLayout.CENTER);
        var views = new javax.swing.JTabbedPane();
        views.addTab("Cabinas", cabinDashboard);
        views.addTab("Edificio", buildingView);
        views.setSelectedIndex(1);
        views.setMinimumSize(new java.awt.Dimension(330, 250));
        var split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, workspace, views);
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
        String[] titles = {"Start", "Pausar", "Reanudar", "Stop", "Reset", "Cargar archivo", "Cerrar"};
        for (var action : Action.values()) {
            var button = new JButton(titles[action.ordinal()]);
            buttons.put(action, button);
            actions.add(button);
        }
        controls.add(stateLabel, BorderLayout.NORTH);
        var actionScroll = new JScrollPane(actions, JScrollPane.VERTICAL_SCROLLBAR_NEVER,
                JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        actionScroll.setBorder(BorderFactory.createEmptyBorder());
        actionScroll.setPreferredSize(new java.awt.Dimension(500, actions.getPreferredSize().height
                + actionScroll.getHorizontalScrollBar().getPreferredSize().height + 4));
        controls.add(actionScroll, BorderLayout.CENTER);
        detail.setEditable(false);
        detail.setLineWrap(true);
        detail.setWrapStyleWord(true);
        var detailScroll = new JScrollPane(detail);
        detailScroll.setPreferredSize(new java.awt.Dimension(400, 48));
        controls.add(detailScroll, BorderLayout.SOUTH);
        add(controls, BorderLayout.NORTH);
        buttons.get(Action.START).addActionListener(event -> command(Action.START));
        buttons.get(Action.PAUSE).addActionListener(event -> command(Action.PAUSE));
        buttons.get(Action.RESUME).addActionListener(event -> command(Action.RESUME));
        buttons.get(Action.STOP).addActionListener(event -> command(Action.STOP));
        buttons.get(Action.RESET).addActionListener(event -> command(Action.RESET));
        buttons.get(Action.LOAD).addActionListener(event -> load());
        buttons.get(Action.CLOSE).addActionListener(event -> onCloseRequest.run());
        refresh = new Timer(16, event -> updateView());
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
                buildingPanel.bind(session.sessionId(), session.config());
                SwingUtilities.invokeLater(() -> {
                    if (!closing && !terminal && controller == session) {
                        // Al abrir un edificio alto se presenta el piso inicial.
                        buildingScroll.getViewport().setViewPosition(new java.awt.Point(0,
                                Math.max(0, buildingPanel.getPreferredSize().height
                                - buildingScroll.getViewport().getExtentSize().height)));
                    }
                });
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
            case PAUSE -> "Pausando: esperando confirmación del manejador y las cabinas...";
            case RESUME -> "Reanudando el tiempo restante...";
            case STOP -> "Deteniendo: esperando confirmación de las cabinas...";
            case RESET -> "Reseteando: limpiando solicitudes y retornando al piso 1...";
            default -> throw new IllegalArgumentException("Control desconocido.");
        });
        updateView();
        var result = switch (action) {
            case START -> controller.start();
            case PAUSE -> controller.pause();
            case RESUME -> controller.resume();
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
            if (snapshotPump != null && controller != null) {
                snapshotPump.latest().ifPresent(data -> buildingPanel.render(data, controller.epoch()));
            }
            eventViewer.refresh();
        }
        buildingPanel.refreshAnimation();
        buttons.get(Action.START).setEnabled(usable && idle && !loading
                && (state == SimulationState.RESET || state == SimulationState.STOPPED));
        buttons.get(Action.PAUSE).setEnabled(usable && idle && !loading && state == SimulationState.RUNNING);
        buttons.get(Action.RESUME).setEnabled(usable && idle && !loading && state == SimulationState.PAUSED);
        boolean canOverridePause = idle || pending == Action.PAUSE || pending == Action.RESUME;
        buttons.get(Action.STOP).setEnabled(usable && canOverridePause
                && (state == SimulationState.RUNNING || state == SimulationState.PAUSING || state == SimulationState.PAUSED));
        buttons.get(Action.RESET).setEnabled(usable && pending != Action.RESET && pending != Action.START
                && (state == SimulationState.RESET || state == SimulationState.RUNNING
                || state == SimulationState.STOPPING || state == SimulationState.STOPPED
                || state == SimulationState.PAUSING || state == SimulationState.PAUSED));
        buttons.get(Action.LOAD).setEnabled(usable && idle && !loading && state == SimulationState.RESET);
        buttons.get(Action.CLOSE).setEnabled(!closing && !disposed);
        String current = closing ? "CLOSING — Cerrando..." : preparing ? "Preparando sesión..."
                : state == null ? "SIN CONFIGURAR" : switch (state) {
                    case PAUSING -> "PAUSING — Pausando... " + controller.pauseStatus().acknowledged()
                            + "/" + controller.pauseStatus().participants();
                    case STOPPING -> "STOPPING — Deteniendo...";
                    case RESETTING -> "RESETTING — Reseteando...";
                    case CLOSING -> "CLOSING — Cerrando...";
                    default -> state.name();
                };
        if (!closing && pending == Action.PAUSE && state != SimulationState.PAUSED && state != SimulationState.PAUSING) {
            current = "PAUSING — Pausando...";
        }
        if (!closing && pending == Action.RESUME) { current = "Reanudando..."; }
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
    BuildingPanel buildingPanel() { requireEdt(); return buildingPanel; }
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

## src/test/java/com/elevatormanager/ui/CabinAnimationScenarios.java

```java
package com.elevatormanager.ui;

import com.elevatormanager.model.*;
import com.elevatormanager.model.request.CabinRequest;
import com.elevatormanager.simulation.SimulationController;
import java.awt.image.BufferedImage;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import javax.swing.SwingUtilities;

/** T59: reloj virtual para oráculos exactos y actores reales para verificar la frontera temporal. */
public final class CabinAnimationScenarios {
    private static final long MS = 1_000_000L;
    private static final long ACTIVE = 4_000 * MS;
    private static final long WALL = -9_000_000 * MS;
    private static final UUID SESSION = UUID.randomUUID();
    private static final SimulationConfig CONFIG = new SimulationConfig(2, 6, 1000, 100);
    private CabinAnimationScenarios() { }

    public static void upwardStartMiddleAndEnd() {
        var s = moving(1, 2, Direction.UP, ACTIVE, 1000 * MS);
        near(CabinMotion.position(s, ACTIVE), 2);
        near(CabinMotion.position(s, ACTIVE + 500 * MS), 2.5);
        near(CabinMotion.position(s, ACTIVE + 1000 * MS), 3);
    }
    public static void downwardInterpolation() {
        var s = moving(1, 5, Direction.DOWN, ACTIVE, 1000 * MS);
        near(CabinMotion.position(s, ACTIVE + 250 * MS), 4.75);
        near(CabinMotion.position(s, ACTIVE + 750 * MS), 4.25);
    }
    public static void clampsToPublishedLeg() {
        var s = moving(1, 2, Direction.UP, ACTIVE, 1000 * MS);
        near(CabinMotion.position(s, ACTIVE - 50 * MS), 2);
        near(CabinMotion.position(s, ACTIVE + 9000 * MS), 3);
    }
    public static void addsElapsedToCapturedProgress() {
        var s = moving(1, 2, Direction.UP, ACTIVE, 600 * MS);
        near(CabinMotion.position(s, ACTIVE), 2.4);
        near(CabinMotion.position(s, ACTIVE + 100 * MS), 2.5);
        near(CabinMotion.position(s, ACTIVE + 700 * MS), 3);
    }
    public static void heldMovementUsesOnlyCapturedProgress() {
        var s = moving(1, 2, Direction.UP, ACTIVE, 600 * MS);
        var held = new ElevatorSnapshot(s.id(), s.config(), s.epoch(), s.revision(), s.currentFloor(), s.nextFloor(),
                s.direction(), ElevatorPhase.HELD, Optional.of(ElevatorPhase.MOVING),
                Optional.of(ElevatorSnapshot.HoldReason.PAUSE), s.mode(), s.phaseDurationNanos(),
                s.remainingNanos(), s.referenceNanos(), s.committedFloor(), List.of(), List.of());
        near(CabinMotion.position(held, ACTIVE + 60_000 * MS), 2.4);
    }
    public static void idleAndDwellRemainAtLogicalFloor() {
        var idle = idle(1, 4);
        near(CabinMotion.position(idle, ACTIVE + 1000 * MS), 4);
        var dwell = new ElevatorSnapshot(1, CONFIG, 0, 1, 4, OptionalInt.empty(), Direction.UP,
                ElevatorPhase.DWELLING, Optional.empty(), Optional.empty(), ElevatorSnapshot.Mode.NORMAL,
                100 * MS, 80 * MS, ACTIVE, OptionalInt.empty(), List.of(), List.of());
        near(CabinMotion.position(dwell, ACTIVE + 5000 * MS), 4);
    }
    public static void unrelatedClockOriginsAreAnchored() throws Exception { edt(() -> {
        var clock = new AtomicLong(WALL + 125 * MS); var p = panel(clock);
        p.render(frame(moving(1, 2, Direction.UP, ACTIVE, 1000 * MS),
                new UiSnapshotPump.Timing(ACTIVE + 250 * MS, WALL, true)), 0);
        near(p.displayedPosition(1), 2.375);
    }); }
    public static void repeatedFrameDoesNotRestartMotion() throws Exception { edt(() -> {
        var clock = new AtomicLong(WALL); var p = panel(clock);
        var f = frame(moving(1, 2, Direction.UP, ACTIVE, 1000 * MS), timing()); p.render(f, 0);
        clock.addAndGet(250 * MS); p.render(f, 0); near(p.displayedPosition(1), 2.25);
        clock.addAndGet(250 * MS); p.render(f, 0); near(p.displayedPosition(1), 2.5);
    }); }
    public static void freshFrameOfSameLegDoesNotRestartMotion() throws Exception { edt(() -> {
        var clock = new AtomicLong(WALL + 250 * MS); var p = panel(clock);
        var snapshot = moving(1, 2, Direction.UP, ACTIVE, 1000 * MS);
        p.render(frame(snapshot, timing()), 0); near(p.displayedPosition(1), 2.25);
        p.render(frame(snapshot, new UiSnapshotPump.Timing(ACTIVE + 250 * MS, WALL + 250 * MS, true)), 0);
        clock.addAndGet(250 * MS); near(p.displayedPosition(1), 2.5);
    }); }
    public static void delayedDeliveryUsesCaptureAnchor() throws Exception { edt(() -> {
        var clock = new AtomicLong(WALL + 650 * MS); var p = panel(clock);
        p.render(frame(moving(1, 2, Direction.UP, ACTIVE, 1000 * MS), timing()), 0);
        near(p.displayedPosition(1), 2.65);
    }); }
    public static void waitsAtEndpointUntilModelPublishesArrival() throws Exception { edt(() -> {
        var clock = new AtomicLong(WALL + 8000 * MS); var p = panel(clock);
        var s = moving(1, 2, Direction.UP, ACTIVE, 1000 * MS);
        p.render(frame(s, timing()), 0);
        near(p.displayedPosition(1), 3); check(p.displayedFloor(1) == 2, "Llegada visual no confirma piso lógico");
        p.render(frame(idle(1, 3), timing()), 0);
        near(p.displayedPosition(1), 3); check(p.displayedFloor(1) == 3, "Solo el modelo confirma llegada");
    }); }
    public static void untimedFramesUseCapturedProgress() throws Exception { edt(() -> {
        var clock = new AtomicLong(WALL); var p = panel(clock);
        var s = moving(1, 2, Direction.UP, ACTIVE, 600 * MS);
        p.render(new UiSnapshotPump.Frame(SESSION, 0, List.of(s, idle(2, 1)), Map.of()), 0);
        clock.addAndGet(9000 * MS); near(p.displayedPosition(1), 2.4);
    }); }
    public static void nonAdvancingSampleDoesNotCountWallTime() throws Exception { edt(() -> {
        var clock = new AtomicLong(WALL + 9000 * MS); var p = panel(clock);
        p.render(frame(moving(1, 2, Direction.UP, ACTIVE, 1000 * MS),
                new UiSnapshotPump.Timing(ACTIVE + 350 * MS, WALL, false)), 0);
        near(p.displayedPosition(1), 2.35);
    }); }
    public static void clockWrapAndLargeDurationRemainBounded() {
        var t = new UiSnapshotPump.Timing(100, Long.MAX_VALUE - 10, true);
        check(t.at(Long.MIN_VALUE + 9) == 120, "Delta monotónico con wrap");
        check(new UiSnapshotPump.Timing(100, 1000, true).at(900) == 100, "Marca de pared futura no retrocede");
        var cfg = new SimulationConfig(1, 2, SimulationConfig.MAX_TIME_MILLIS, 1);
        long duration = cfg.movementTimeMillis() * MS;
        var s = new ElevatorSnapshot(1, cfg, 0, 0, 1, OptionalInt.of(2), Direction.UP, ElevatorPhase.MOVING,
                Optional.empty(), Optional.empty(), ElevatorSnapshot.Mode.NORMAL,
                duration, duration / 2, 0, OptionalInt.empty(), List.of(), List.of());
        near(CabinMotion.position(s, Long.MAX_VALUE), 2);
        var wrapped = moving(1, 2, Direction.UP, Long.MAX_VALUE - 100 * MS, 1000 * MS);
        near(CabinMotion.position(wrapped, Long.MIN_VALUE + 100 * MS - 1), 2.2);
    }
    public static void rasterMovesThroughIntermediateCoordinates() throws Exception { edt(() -> {
        var clock = new AtomicLong(WALL); var p = panel(clock);
        p.render(frame(moving(1, 2, Direction.UP, ACTIVE, 1000 * MS), timing()), 0);
        var start = p.geometry().cabinBounds(1, 2);
        clock.addAndGet(500 * MS); var middle = p.geometry().cabinBounds(1, p.displayedPosition(1));
        check(middle.y < start.y && middle.y > p.geometry().cabinBounds(1, 3).y, "Coordenada intermedia");
        var image = new BufferedImage(800, 500, BufferedImage.TYPE_INT_RGB); var g = image.createGraphics();
        try { p.paint(g); } finally { g.dispose(); }
        check(image.getRGB(middle.x + 5, middle.y + 5) == BuildingPanel.CABIN_COLOR.getRGB(), "Raster en posición interpolada");
        check(p.displayedFloor(1) == 2, "Raster no modifica el piso lógico");
    }); }
    public static void cabinsInterpolateIndependently() throws Exception { edt(() -> {
        var clock = new AtomicLong(WALL + 250 * MS); var p = panel(clock);
        var a = moving(1, 2, Direction.UP, ACTIVE, 1000 * MS);
        var b = moving(2, 5, Direction.DOWN, ACTIVE, 500 * MS);
        p.render(new UiSnapshotPump.Frame(SESSION, 0, List.of(a, b), Map.of(), Optional.of(timing())), 0);
        near(p.displayedPosition(1), 2.25); near(p.displayedPosition(2), 4.25);
    }); }
    public static void resizingDoesNotRestartTemporalProgress() throws Exception { edt(() -> {
        var clock = new AtomicLong(WALL + 500 * MS); var p = panel(clock);
        p.render(frame(moving(1, 2, Direction.UP, ACTIVE, 1000 * MS), timing()), 0);
        near(p.displayedPosition(1), 2.5); int oldY = p.geometry().floorY(p.displayedPosition(1));
        p.setSize(1000, 700); near(p.displayedPosition(1), 2.5);
        check(p.geometry().floorY(p.displayedPosition(1)) != oldY, "Se adapta geometría, no reloj");
    }); }
    public static void productionCaptureUsesTheModelClock() throws Exception {
        var c = new SimulationController(new SimulationConfig(2, 4, 10000, 100), 0);
        try {
            c.start().get(5, TimeUnit.SECONDS);
            c.submit(new CabinRequest(UUID.randomUUID(), 0, 4, Instant.now(), 2)).get(5, TimeUnit.SECONDS);
            await(() -> c.snapshot(2).phase() == ElevatorPhase.MOVING);
            var f = UiSnapshotPump.capture(c); check(f != null && f.timing().isPresent(), "Captura temporal publicada");
            var s = f.snapshots().get(1); var t = f.timing().orElseThrow();
            check(t.advancing() && t.activeNanos() - s.referenceNanos() >= 0, "Mismo dominio activo");
            var before = c.snapshot(2);
            edt(() -> {
                var clock = new AtomicLong(t.wallNanos() + 250 * MS);
                var p = new BuildingPanel(clock::get); p.bind(c.sessionId(), c.config()); p.setSize(800, 500);
                p.render(f, c.epoch());
                check(p.displayedPosition(2) > s.currentFloor()
                        && p.displayedPosition(2) <= s.nextFloor().orElseThrow(), "Modelo alimenta interpolación");
                p.refreshAnimation();
            });
            check(before.equals(c.snapshot(2)), "Representar no escribe en el actor");
        } finally { c.closeAsync().get(5, TimeUnit.SECONDS); }
    }

    private static BuildingPanel panel(AtomicLong clock) {
        var p = new BuildingPanel(clock::get); p.bind(SESSION, CONFIG); p.setSize(800, 500); return p;
    }
    private static ElevatorSnapshot moving(int id, int floor, Direction direction, long reference, long remaining) {
        return new ElevatorSnapshot(id, CONFIG, 0, 1, floor, OptionalInt.of(floor + direction.floorDelta()), direction,
                ElevatorPhase.MOVING, Optional.empty(), Optional.empty(), ElevatorSnapshot.Mode.NORMAL,
                1000 * MS, remaining, reference, OptionalInt.empty(), List.of(), List.of());
    }
    private static ElevatorSnapshot idle(int id, int floor) {
        return new ElevatorSnapshot(id, CONFIG, 0, 2, floor, OptionalInt.empty(), Direction.UP, ElevatorPhase.IDLE,
                Optional.empty(), Optional.empty(), ElevatorSnapshot.Mode.NORMAL,
                0, 0, ACTIVE, OptionalInt.empty(), List.of(), List.of());
    }
    private static UiSnapshotPump.Timing timing() { return new UiSnapshotPump.Timing(ACTIVE, WALL, true); }
    private static UiSnapshotPump.Frame frame(ElevatorSnapshot snapshot, UiSnapshotPump.Timing timing) {
        return new UiSnapshotPump.Frame(SESSION, 0, List.of(snapshot, idle(2, 1)), Map.of(), Optional.of(timing));
    }
    private static void near(double actual, double expected) {
        if (Math.abs(actual - expected) > 1e-9) throw new AssertionError("Esperado " + expected + "; obtenido " + actual);
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static void edt(Runnable action) throws Exception { SwingUtilities.invokeAndWait(action); }
    private static void await(BooleanSupplier condition) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > end) throw new AssertionError("Plazo agotado");
            Thread.sleep(5);
        }
    }
    public static void main(String[] args) throws Exception {
        upwardStartMiddleAndEnd(); downwardInterpolation(); clampsToPublishedLeg(); addsElapsedToCapturedProgress();
        heldMovementUsesOnlyCapturedProgress(); idleAndDwellRemainAtLogicalFloor(); unrelatedClockOriginsAreAnchored();
        repeatedFrameDoesNotRestartMotion(); freshFrameOfSameLegDoesNotRestartMotion(); delayedDeliveryUsesCaptureAnchor();
        waitsAtEndpointUntilModelPublishesArrival(); untimedFramesUseCapturedProgress(); nonAdvancingSampleDoesNotCountWallTime();
        clockWrapAndLargeDurationRemainBounded(); rasterMovesThroughIntermediateCoordinates(); cabinsInterpolateIndependently();
        resizingDoesNotRestartTemporalProgress(); productionCaptureUsesTheModelClock();
        System.out.println("T59: 18 escenarios satisfactorios.");
    }
}
```

## src/test/java/com/elevatormanager/ui/CabinAnimationTest.java

```java
package com.elevatormanager.ui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Adaptadores JUnit T59. */
@Timeout(30)
class CabinAnimationTest {
    @Test void upwardStartMiddleAndEnd() throws Exception {
        CabinAnimationScenarios.upwardStartMiddleAndEnd();
    }
    @Test void downwardInterpolation() throws Exception {
        CabinAnimationScenarios.downwardInterpolation();
    }
    @Test void clampsToPublishedLeg() throws Exception {
        CabinAnimationScenarios.clampsToPublishedLeg();
    }
    @Test void addsElapsedToCapturedProgress() throws Exception {
        CabinAnimationScenarios.addsElapsedToCapturedProgress();
    }
    @Test void heldMovementUsesOnlyCapturedProgress() throws Exception {
        CabinAnimationScenarios.heldMovementUsesOnlyCapturedProgress();
    }
    @Test void idleAndDwellRemainAtLogicalFloor() throws Exception {
        CabinAnimationScenarios.idleAndDwellRemainAtLogicalFloor();
    }
    @Test void unrelatedClockOriginsAreAnchored() throws Exception {
        CabinAnimationScenarios.unrelatedClockOriginsAreAnchored();
    }
    @Test void repeatedFrameDoesNotRestartMotion() throws Exception {
        CabinAnimationScenarios.repeatedFrameDoesNotRestartMotion();
    }
    @Test void freshFrameOfSameLegDoesNotRestartMotion() throws Exception {
        CabinAnimationScenarios.freshFrameOfSameLegDoesNotRestartMotion();
    }
    @Test void delayedDeliveryUsesCaptureAnchor() throws Exception {
        CabinAnimationScenarios.delayedDeliveryUsesCaptureAnchor();
    }
    @Test void waitsAtEndpointUntilModelPublishesArrival() throws Exception {
        CabinAnimationScenarios.waitsAtEndpointUntilModelPublishesArrival();
    }
    @Test void untimedFramesUseCapturedProgress() throws Exception {
        CabinAnimationScenarios.untimedFramesUseCapturedProgress();
    }
    @Test void nonAdvancingSampleDoesNotCountWallTime() throws Exception {
        CabinAnimationScenarios.nonAdvancingSampleDoesNotCountWallTime();
    }
    @Test void clockWrapAndLargeDurationRemainBounded() throws Exception {
        CabinAnimationScenarios.clockWrapAndLargeDurationRemainBounded();
    }
    @Test void rasterMovesThroughIntermediateCoordinates() throws Exception {
        CabinAnimationScenarios.rasterMovesThroughIntermediateCoordinates();
    }
    @Test void cabinsInterpolateIndependently() throws Exception {
        CabinAnimationScenarios.cabinsInterpolateIndependently();
    }
    @Test void resizingDoesNotRestartTemporalProgress() throws Exception {
        CabinAnimationScenarios.resizingDoesNotRestartTemporalProgress();
    }
    @Test void productionCaptureUsesTheModelClock() throws Exception {
        CabinAnimationScenarios.productionCaptureUsesTheModelClock();
    }
}
```
