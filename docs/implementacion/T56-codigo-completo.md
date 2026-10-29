# T56 — Código completo

Fuentes Java añadidas o modificadas, reproducidas íntegras. El proyecto completo con las fuentes previas está incluido en el ZIP.

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

## src/main/java/com/elevatormanager/simulation/time/PauseCoordinator.java

```java
package com.elevatormanager.simulation.time;

import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * T55: barrera de N+1 actores sobre un único reloj de tiempo activo.
 * ID 0: manejador; IDs 1..N: cabinas. Los callbacks y la finalización de
 * futuros ocurren fuera del lock; ningún actor espera con su lock retenido.
 */
public final class PauseCoordinator implements AutoCloseable {
    public record Status(long generation, boolean requested, int acknowledged, int participants) { }

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition changed = lock.newCondition();
    private final PausableClock clock = new PausableClock();
    private final boolean[] acknowledged;
    private final boolean[] terminated;
    private long generation;
    private int count;
    private boolean requested;
    private boolean closed;
    private CompletableFuture<Void> completion;

    public PauseCoordinator(int participants) {
        if (participants < 2) { throw new IllegalArgumentException("Se requiere manejador y alguna cabina."); }
        acknowledged = new boolean[participants];
        terminated = new boolean[participants];
    }

    /** Marcas lógicas: compartir esta instancia entre estado, snapshots y estimador. */
    public long nowNanos() { return clock.nowNanos(); }

    public Status status() {
        lock.lock();
        try { return new Status(generation, requested, count, acknowledged.length); }
        finally { lock.unlock(); }
    }

    /** Congela el tiempo antes de solicitar reconocimiento a los actores. */
    public CompletableFuture<Void> requestPause() {
        lock.lock();
        try {
            if (closed) { return CompletableFuture.failedFuture(new IllegalStateException("Barrera cerrada.")); }
            if (requested) { return completion.copy(); }
            for (boolean ended : terminated) {
                if (ended) {
                    return CompletableFuture.failedFuture(new IllegalStateException("Participante terminado."));
                }
            }
            generation = Math.incrementExact(generation);
            Arrays.fill(acknowledged, false);
            count = 0;
            completion = new CompletableFuture<>();
            clock.pause();
            requested = true;
            return completion.copy();
        } finally { lock.unlock(); }
    }

    public boolean isRequested() {
        lock.lock();
        try { return requested; }
        finally { lock.unlock(); }
    }

    /**
     * Punto seguro del actor. Publica su captura congelada antes de reconocer,
     * espera sin sondeo y restaura la captura al salir por reanudación.
     * false indica cierre. Los callbacks pertenecen al propio hilo del actor.
     */
    public boolean checkpoint(int participant, Runnable onPaused, Runnable onResumed)
            throws InterruptedException {
        checkId(participant);
        Objects.requireNonNull(onPaused, "onPaused");
        Objects.requireNonNull(onResumed, "onResumed");
        long ticket;
        lock.lockInterruptibly();
        try {
            if (closed) { return false; }
            if (!requested) { return true; }
            ticket = generation;
        } finally { lock.unlock(); }

        onPaused.run();
        CompletableFuture<Void> ready = null;
        lock.lockInterruptibly();
        try {
            if (!closed && requested && generation == ticket && !acknowledged[participant]) {
                acknowledged[participant] = true;
                if (++count == acknowledged.length) { ready = completion; }
            }
        } finally { lock.unlock(); }
        if (ready != null) { ready.complete(null); }

        lock.lockInterruptibly();
        try {
            while (!closed && requested && generation == ticket) { changed.await(); }
            if (closed) { return false; }
        } finally { lock.unlock(); }
        onResumed.run();
        return true;
    }

    /** Liberación común, solo después de reconocer TODOS los participantes. */
    public boolean resume() {
        lock.lock();
        try {
            if (closed || !requested || count != acknowledged.length) { return false; }
            for (boolean ended : terminated) { if (ended) { return false; } }
            clock.resume();
            requested = false;
            changed.signalAll();
            return true;
        } finally { lock.unlock(); }
    }

    /**
     * T56: libera una pausa incluso parcial por un control de mayor prioridad.
     * El llamador debe ENCOLAR Stop/Reset en todos los actores antes de invocar.
     * No equivale a Resume: el control prioritario gobierna la siguiente iteración.
     */
    public boolean releaseForControl(String reason) {
        Objects.requireNonNull(reason, "reason");
        CompletableFuture<Void> pending;
        lock.lock();
        try {
            if (closed || !requested) { return false; }
            clock.resume();
            requested = false;
            changed.signalAll();
            pending = completion;
        } finally { lock.unlock(); }
        pending.completeExceptionally(new java.util.concurrent.CancellationException(reason));
        return true;
    }

    /** Un actor finalizado no puede dejar una barrera pendiente para siempre. */
    public void participantTerminated(int participant, Throwable failure) {
        checkId(participant);
        CompletableFuture<Void> pending;
        lock.lock();
        try {
            terminated[participant] = true;
            pending = requested ? completion : null;
        } finally { lock.unlock(); }
        if (pending != null) {
            pending.completeExceptionally(new IllegalStateException("Participante " + participant + " terminado.", failure));
        }
    }

    /** Despierta actores y cancela la barrera; el reloj sigue legible para eventos de cierre. */
    @Override public void close() {
        CompletableFuture<Void> pending;
        lock.lock();
        try {
            if (closed) { return; }
            closed = true;
            requested = false;
            clock.resume();
            changed.signalAll();
            pending = completion;
        } finally { lock.unlock(); }
        if (pending != null) {
            pending.completeExceptionally(new IllegalStateException("Barrera interrumpida por cierre."));
        }
    }

    private void checkId(int id) {
        if (id < 0 || id >= acknowledged.length) { throw new IllegalArgumentException("Participante fuera de rango."); }
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
            case HELD -> latest.holdReason().orElseThrow() == ElevatorSnapshot.HoldReason.PAUSE ? "En pausa" : "Detenida";
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
            eventViewer.refresh();
        }
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

## src/test/java/com/elevatormanager/simulation/GlobalPauseScenarios.java

```java
package com.elevatormanager.simulation;

import com.elevatormanager.dispatch.HallCallSnapshot;
import com.elevatormanager.model.Direction;
import com.elevatormanager.model.ElevatorPhase;
import com.elevatormanager.model.ElevatorSnapshot;
import com.elevatormanager.model.SimulationConfig;
import com.elevatormanager.model.SimulationState;
import com.elevatormanager.model.request.CabinRequest;
import com.elevatormanager.model.request.HallRequest;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import javax.swing.SwingUtilities;

/** T55: controlador y actores reales; sin abrir ventanas ni sustituir el motor. */
public final class GlobalPauseScenarios {
    private static final Duration LIMIT = Duration.ofSeconds(8);
    private GlobalPauseScenarios() { }

    public static void pauseAndResumeRequireValidState() throws Exception {
        try (var rig = new Session(1, 5, 50, 40)) {
            var c = rig.controller;
            check(get(c.pause()).outcome() == SimulationController.Outcome.REJECTED, "Pausa rechazada en RESET");
            check(get(c.resume()).outcome() == SimulationController.Outcome.REJECTED, "Resume rechazado en RESET");
            get(c.start()); get(c.stop());
            check(get(c.pause()).outcome() == SimulationController.Outcome.REJECTED, "STOPPED usa Start");
            check(get(c.resume()).outcome() == SimulationController.Outcome.REJECTED, "Resume no sustituye Start");
        }
    }

    public static void singleCabinIdleBarrier() throws Exception { idleBarrier(1); }
    public static void manyCabinsIdleBarrier() throws Exception { idleBarrier(6); }

    private static void idleBarrier(int count) throws Exception {
        try (var rig = new Session(count, 5, 50, 40)) {
            var c = rig.controller;
            get(c.start());
            check(get(c.pause()).outcome() == SimulationController.Outcome.APPLIED, "Pausa aplicada");
            check(c.state() == SimulationState.PAUSED, "Estado confirmado");
            check(c.pauseStatus().acknowledged() == count + 1, "Todos los actores reconocieron");
            var snapshots = c.snapshots();
            check(snapshots.stream().allMatch(s -> s.currentFloor() == 1 && s.phase() == ElevatorPhase.IDLE), "Reposo conservado");
            long reference = snapshots.get(0).referenceNanos();
            check(snapshots.stream().allMatch(s -> s.referenceNanos() == reference), "Una base temporal congelada");
            Thread.sleep(30);
            check(c.snapshots().equals(snapshots), "Reposo completamente estable");
            get(c.resume());
            check(c.state() == SimulationState.RUNNING, "Reanudado");
        }
    }

    public static void partialMovementPreservesRemainingTimeAndCommand() throws Exception {
        try (var rig = new Session(1, 5, 800, 30)) {
            var c = rig.controller;
            get(c.start());
            var request = cabin(1, 3);
            get(c.submit(request));
            var departure = event(c, e -> e.type() == ExecutionEvent.Type.DEPARTED);
            Thread.sleep(200);
            get(c.pause());
            var frozen = c.snapshot(1);
            held(frozen, ElevatorPhase.MOVING);
            check(frozen.currentFloor() == 1 && frozen.nextFloor().orElseThrow() == 2, "Mismo tramo parcial");
            check(frozen.remainingNanos() > 0 && frozen.remainingNanos() < 700_000_000L, "Progreso parcial capturado");
            check(frozen.cabinRequests().stream().anyMatch(r -> r.getId().equals(request.getId())), "Destino sigue encendido");
            drain(c);
            Thread.sleep(850); // Más que el tramo completo, sin consumir tiempo simulado.
            check(c.snapshot(1).equals(frozen), "Sin avance durante la pausa");
            check(c.awaitEvent(Duration.ZERO).isEmpty(), "Sin llegadas ni servicios nuevos");
            get(c.resume());
            await(() -> c.snapshot(1).phase() == ElevatorPhase.MOVING, "Restaurar fase de movimiento");
            check(c.snapshot(1).remainingNanos() <= frozen.remainingNanos(), "No reiniciar presupuesto");
            var arrival = event(c, e -> e.type() == ExecutionEvent.Type.ARRIVED && e.snapshot().currentFloor() == 2);
            check(arrival.referenceNanos() - frozen.referenceNanos() >= frozen.remainingNanos(), "Consume el resto completo");
            check(arrival.referenceNanos() - departure.referenceNanos() >= 800_000_000L, "Tramo activo completo");
            var completion = event(c, e -> e.type() == ExecutionEvent.Type.COMMAND_COMPLETED
                    && e.canonicalRequestId().filter(request.getId()::equals).isPresent());
            check(completion.snapshot().currentFloor() == 3, "Mismo comando llega al destino final");
            check(completion.snapshot().cabinRequests().isEmpty(), "Luz apagada al atender");
        }
    }

    public static void partialDwellPreservesRemainingTime() throws Exception {
        try (var rig = new Session(1, 3, 50, 800)) {
            var c = rig.controller;
            get(c.start()); get(c.submit(cabin(1, 1)));
            event(c, e -> e.type() == ExecutionEvent.Type.STOP_STARTED);
            Thread.sleep(150);
            get(c.pause());
            var frozen = c.snapshot(1);
            held(frozen, ElevatorPhase.DWELLING);
            check(frozen.remainingNanos() > 0 && frozen.remainingNanos() < 750_000_000L, "Parada parcial");
            drain(c); Thread.sleep(850);
            check(c.snapshot(1).equals(frozen) && c.awaitEvent(Duration.ZERO).isEmpty(), "Parada congelada");
            get(c.resume());
            await(() -> c.snapshot(1).phase() == ElevatorPhase.DWELLING, "Restaurar parada");
            check(c.snapshot(1).remainingNanos() <= frozen.remainingNanos(), "No reiniciar parada");
            var end = event(c, e -> e.type() == ExecutionEvent.Type.STOP_ENDED);
            check(end.referenceNanos() - frozen.referenceNanos() >= frozen.remainingNanos(), "Resto de parada completo");
        }
    }

    public static void movingCabinsFreezeOnOneTimeDomain() throws Exception {
        try (var rig = new Session(2, 6, 1200, 30)) {
            var c = rig.controller;
            get(c.start()); get(c.submit(cabin(1, 5))); get(c.submit(cabin(2, 4)));
            await(() -> c.snapshots().stream().allMatch(s -> s.phase() == ElevatorPhase.MOVING), "Dos cabinas en paralelo");
            Thread.sleep(30); get(c.pause());
            var frozen = c.snapshots();
            frozen.forEach(s -> held(s, ElevatorPhase.MOVING));
            check(frozen.get(0).referenceNanos() == frozen.get(1).referenceNanos(), "Misma marca congelada");
            Thread.sleep(80);
            check(c.snapshots().equals(frozen) && c.pauseStatus().acknowledged() == 3, "Ambas inmóviles y reconocidas");
        }
    }

    public static void repeatedControlsAreIdempotent() throws Exception {
        try (var rig = new Session(2, 4, 50, 30)) {
            var c = rig.controller;
            get(c.start()); get(c.pause());
            long generation = c.pauseStatus().generation();
            check(get(c.pause()).outcome() == SimulationController.Outcome.UNCHANGED, "Pausa repetida");
            check(c.pauseStatus().generation() == generation, "Misma generación");
            check(get(c.start()).outcome() == SimulationController.Outcome.REJECTED, "Start no sustituye Resume");
            check(get(c.resume()).outcome() == SimulationController.Outcome.APPLIED, "Primer Resume");
            check(get(c.resume()).outcome() == SimulationController.Outcome.UNCHANGED, "Resume repetido");
        }
    }

    public static void rapidCyclesKeepSameSessionAndActors() throws Exception {
        try (var rig = new Session(3, 5, 100, 30)) {
            var c = rig.controller;
            get(c.start());
            UUID session = c.sessionId();
            Set<Thread> actors = actorThreads();
            for (int i = 1; i <= 20; i++) {
                get(c.pause());
                check(c.pauseStatus().generation() == i && c.pauseStatus().acknowledged() == 4, "ACK vigentes por ciclo");
                get(c.resume());
            }
            check(c.sessionId().equals(session) && c.epoch() == 0, "Misma sesión y época");
            check(actorThreads().equals(actors), "Mismos objetos Thread, sin reemplazarlos");
        }
    }

    public static void hallLedgerAndQueuedCommandsSurvivePause() throws Exception {
        try (var rig = new Session(2, 6, 150, 30)) {
            var c = rig.controller;
            get(c.start());
            var first = cabin(1, 5);
            var second = cabin(2, 4);
            var hall = new HallRequest(UUID.randomUUID(), 0, 3, Instant.now(), Direction.UP);
            get(c.submit(first)); get(c.submit(second)); get(c.submit(hall));
            get(c.pause());
            var calls = c.calls(); var snapshots = c.snapshots();
            Thread.sleep(200);
            check(c.calls().equals(calls) && c.snapshots().equals(snapshots), "Ledger y agendas estables");
            get(c.resume());
            var pending = new HashSet<>(Set.of(first.getId(), second.getId(), hall.getId()));
            while (!pending.isEmpty()) {
                var done = event(c, e -> e.type() == ExecutionEvent.Type.COMMAND_COMPLETED);
                check(pending.remove(done.canonicalRequestId().orElseThrow()), "Sin duplicar ni perder comandos");
            }
            await(() -> c.calls().containsKey(hall.getId())
                    && c.calls().get(hall.getId()).status() == HallCallSnapshot.Status.SERVED, "Llamada atendida tras Resume");
        }
    }

    public static void manualRequestsAndImportsAreRejectedWhilePaused() throws Exception {
        try (var rig = new Session(1, 4, 50, 30)) {
            var c = rig.controller;
            get(c.start()); get(c.pause());
            check(get(c.submit(cabin(1, 3))).outcome() == SimulationController.Outcome.REJECTED, "Sin admisión manual pausada");
            var imported = c.importRequests(Path.of("t55-no-debe-leerse.txt")).get(8, TimeUnit.SECONDS);
            check(imported.outcome() == BatchImportResult.Outcome.REJECTED, "Importación requiere RESET");
            check(c.state() == SimulationState.PAUSED && c.snapshot(1).cabinRequests().isEmpty(), "Sin efectos secundarios");
        }
    }

    public static void pausedStopAndResetUsePriorityControl() throws Exception {
        try (var rig = new Session(1, 4, 50, 30)) {
            var c = rig.controller;
            get(c.start()); get(c.pause());
            check(get(c.stop()).outcome() == SimulationController.Outcome.APPLIED, "Stop sustituye pausa");
            check(c.state() == SimulationState.STOPPED && c.epoch() == 0, "Stop conserva época");
            get(c.start()); get(c.pause());
            check(get(c.reset()).outcome() == SimulationController.Outcome.APPLIED, "Reset sustituye pausa");
            check(c.state() == SimulationState.RESET && c.epoch() == 1, "Reset sin Resume manual");
        }
    }

    public static void edtCanRequestPauseAndResumeWithoutWaiting() throws Exception {
        try (var rig = new Session(4, 4, 50, 30)) {
            var c = rig.controller;
            get(c.start());
            var requested = new AtomicReference<CompletableFuture<SimulationController.Result>>();
            SwingUtilities.invokeAndWait(() -> requested.set(c.pause()));
            check(get(requested.get()).state() == SimulationState.PAUSED, "Petición desde EDT");
            SwingUtilities.invokeAndWait(() -> requested.set(c.resume()));
            check(get(requested.get()).state() == SimulationState.RUNNING, "Reanudación desde EDT");
        }
    }

    public static void cancelledPublicFutureDoesNotStrandPause() throws Exception {
        try (var rig = new Session(8, 4, 50, 30)) {
            var c = rig.controller;
            get(c.start()); c.pause().cancel(true);
            await(() -> c.state() == SimulationState.PAUSED, "Pausa privada no cancelada");
            check(c.pauseStatus().acknowledged() == 9, "Todos reconocidos");
            get(c.resume());
        }
    }

    public static void simultaneousPauseRequestsShareOneGeneration() throws Exception {
        try (var rig = new Session(4, 4, 50, 30)) {
            var c = rig.controller;
            get(c.start());
            var pool = java.util.concurrent.Executors.newFixedThreadPool(6);
            try {
                var futures = new java.util.ArrayList<java.util.concurrent.Future<SimulationController.Result>>();
                for (int i = 0; i < 24; i++) { futures.add(pool.submit(() -> get(c.pause()))); }
                for (var future : futures) {
                    var result = future.get(8, TimeUnit.SECONDS);
                    check(result.state() == SimulationState.PAUSED
                            && result.outcome() != SimulationController.Outcome.REJECTED, "Misma pausa concurrente");
                }
                check(c.pauseStatus().generation() == 1, "Una generación para 24 peticiones");
            } finally { pool.shutdownNow(); }
            get(c.resume());
        }
    }

    public static void closingPausedSessionReleasesEveryActor() throws Exception {
        try (var rig = new Session(3, 5, 300, 30)) {
            var c = rig.controller;
            get(c.start()); get(c.submit(cabin(1, 5))); get(c.pause());
            get(c.closeAsync());
            check(c.state() == SimulationState.CLOSED, "Cierre sin Resume manual");
            check(c.elevatorLifecycles().stream().allMatch(s -> s == ElevatorWorker.Lifecycle.TERMINATED), "Cabinas terminadas");
            check(c.managerLifecycle() == com.elevatormanager.dispatch.ManagerWorker.Lifecycle.TERMINATED, "Manejador terminado");
            check(c.failure().isEmpty(), "Cierre pausado sin fallo");
        }
    }

    public static void closeRacingPauseAlwaysCompletesBothFutures() throws Exception {
        try (var rig = new Session(12, 4, 50, 30)) {
            var c = rig.controller;
            get(c.start());
            var pause = c.pause(); var close = c.closeAsync();
            get(close);
            check(pause.get(8, TimeUnit.SECONDS) != null && c.state() == SimulationState.CLOSED, "Ambas operaciones resueltas");
            check(c.failure().isEmpty(), "La sustitución por cierre no es fallo de motor");
        }
    }

    public static void sessionsHaveIndependentPauseDomains() throws Exception {
        try (var first = new Session(1, 4, 60, 20); var second = new Session(1, 4, 60, 20)) {
            var a = first.controller; var b = second.controller;
            get(a.start()); get(b.start()); get(a.pause());
            get(b.submit(cabin(1, 3)));
            event(b, e -> e.type() == ExecutionEvent.Type.COMMAND_COMPLETED);
            check(a.state() == SimulationState.PAUSED && a.snapshot(1).currentFloor() == 1,
                    "La pausa de una sesión no detiene otra");
        }
    }

    private static void held(ElevatorSnapshot snapshot, ElevatorPhase phase) {
        check(snapshot.phase() == ElevatorPhase.HELD && snapshot.suspendedPhase().orElseThrow() == phase
                && snapshot.holdReason().orElseThrow() == ElevatorSnapshot.HoldReason.PAUSE, "Captura HELD/PAUSE con fase original");
    }
    private static Set<Thread> actorThreads() {
        var threads = new HashSet<Thread>();
        Thread.getAllStackTraces().keySet().stream().filter(t -> t.isAlive()
                && (t.getName().startsWith("elevator-worker-") || t.getName().startsWith("manager-worker-")))
                .forEach(threads::add);
        return Set.copyOf(threads);
    }
    private static CabinRequest cabin(int id, int floor) {
        return new CabinRequest(UUID.randomUUID(), 0, floor, Instant.now(), id);
    }
    private static SimulationController.Result get(CompletableFuture<SimulationController.Result> future) throws Exception {
        return future.get(8, TimeUnit.SECONDS);
    }
    private static ExecutionEvent event(SimulationController c, Predicate<ExecutionEvent> predicate) throws Exception {
        long began = System.nanoTime();
        while (true) {
            long left = LIMIT.toNanos() - (System.nanoTime() - began);
            check(left > 0, "Plazo de evento agotado");
            var event = c.awaitEvent(Duration.ofNanos(left));
            if (event.isPresent() && predicate.test(event.orElseThrow())) { return event.orElseThrow(); }
        }
    }
    private static void drain(SimulationController c) throws Exception {
        while (c.awaitEvent(Duration.ZERO).isPresent()) { }
    }
    private static void await(BooleanSupplier condition, String detail) throws Exception {
        long began = System.nanoTime();
        while (!condition.getAsBoolean()) {
            check(System.nanoTime() - began < LIMIT.toNanos(), detail);
            Thread.sleep(1);
        }
    }
    private static final class Session implements AutoCloseable {
        final SimulationController controller;
        Session(int cabins, int floors, long movement, long dwell) {
            controller = new SimulationController(new SimulationConfig(cabins, floors, movement, dwell), 0);
        }
        @Override public void close() throws Exception {
            controller.close();
            check(controller.awaitTermination(LIMIT), "Cierre real y acotado del fixture");
        }
    }
    private static void check(boolean condition, String detail) {
        if (!condition) { throw new AssertionError(detail); }
    }

    public static void main(String[] args) throws Exception {
        pauseAndResumeRequireValidState();
        singleCabinIdleBarrier();
        manyCabinsIdleBarrier();
        partialMovementPreservesRemainingTimeAndCommand();
        partialDwellPreservesRemainingTime();
        movingCabinsFreezeOnOneTimeDomain();
        repeatedControlsAreIdempotent();
        rapidCyclesKeepSameSessionAndActors();
        hallLedgerAndQueuedCommandsSurvivePause();
        manualRequestsAndImportsAreRejectedWhilePaused();
        pausedStopAndResetUsePriorityControl();
        edtCanRequestPauseAndResumeWithoutWaiting();
        cancelledPublicFutureDoesNotStrandPause();
        simultaneousPauseRequestsShareOneGeneration();
        closingPausedSessionReleasesEveryActor();
        closeRacingPauseAlwaysCompletesBothFutures();
        sessionsHaveIndependentPauseDomains();
        System.out.println("T55: 17 escenarios de sesión satisfactorios.");
    }
}
```

## src/test/java/com/elevatormanager/simulation/GlobalPauseTest.java

```java
package com.elevatormanager.simulation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** T55: escenarios completos bajo JUnit. */
@Timeout(30)
class GlobalPauseTest {
    @Test void pauseAndResumeRequireValidState() throws Exception {
        GlobalPauseScenarios.pauseAndResumeRequireValidState();
    }
    @Test void singleCabinIdleBarrier() throws Exception {
        GlobalPauseScenarios.singleCabinIdleBarrier();
    }
    @Test void manyCabinsIdleBarrier() throws Exception {
        GlobalPauseScenarios.manyCabinsIdleBarrier();
    }
    @Test void partialMovementPreservesRemainingTimeAndCommand() throws Exception {
        GlobalPauseScenarios.partialMovementPreservesRemainingTimeAndCommand();
    }
    @Test void partialDwellPreservesRemainingTime() throws Exception {
        GlobalPauseScenarios.partialDwellPreservesRemainingTime();
    }
    @Test void movingCabinsFreezeOnOneTimeDomain() throws Exception {
        GlobalPauseScenarios.movingCabinsFreezeOnOneTimeDomain();
    }
    @Test void repeatedControlsAreIdempotent() throws Exception {
        GlobalPauseScenarios.repeatedControlsAreIdempotent();
    }
    @Test void rapidCyclesKeepSameSessionAndActors() throws Exception {
        GlobalPauseScenarios.rapidCyclesKeepSameSessionAndActors();
    }
    @Test void hallLedgerAndQueuedCommandsSurvivePause() throws Exception {
        GlobalPauseScenarios.hallLedgerAndQueuedCommandsSurvivePause();
    }
    @Test void manualRequestsAndImportsAreRejectedWhilePaused() throws Exception {
        GlobalPauseScenarios.manualRequestsAndImportsAreRejectedWhilePaused();
    }
    @Test void pausedStopAndResetUsePriorityControl() throws Exception {
        GlobalPauseScenarios.pausedStopAndResetUsePriorityControl();
    }
    @Test void edtCanRequestPauseAndResumeWithoutWaiting() throws Exception {
        GlobalPauseScenarios.edtCanRequestPauseAndResumeWithoutWaiting();
    }
    @Test void cancelledPublicFutureDoesNotStrandPause() throws Exception {
        GlobalPauseScenarios.cancelledPublicFutureDoesNotStrandPause();
    }
    @Test void simultaneousPauseRequestsShareOneGeneration() throws Exception {
        GlobalPauseScenarios.simultaneousPauseRequestsShareOneGeneration();
    }
    @Test void closingPausedSessionReleasesEveryActor() throws Exception {
        GlobalPauseScenarios.closingPausedSessionReleasesEveryActor();
    }
    @Test void closeRacingPauseAlwaysCompletesBothFutures() throws Exception {
        GlobalPauseScenarios.closeRacingPauseAlwaysCompletesBothFutures();
    }
    @Test void sessionsHaveIndependentPauseDomains() throws Exception {
        GlobalPauseScenarios.sessionsHaveIndependentPauseDomains();
    }
}
```

## src/test/java/com/elevatormanager/simulation/PausePriorityScenarios.java

```java
package com.elevatormanager.simulation;

import com.elevatormanager.logging.SessionLog;
import com.elevatormanager.model.*;
import com.elevatormanager.model.request.CabinRequest;
import com.elevatormanager.simulation.time.PauseCoordinator;
import java.io.OutputStream;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.*;

/** T56: prioridad real sobre actores pausados, sin invocar Resume para Stop/Reset/cierre. */
public final class PausePriorityScenarios {
    private static final Duration LIMIT = Duration.ofSeconds(8);
    private PausePriorityScenarios() { }

    public static void higherControlReleasesPartialBarrier() throws Exception {
        try (var gate = new PauseCoordinator(3)) {
            var future = gate.requestPause();
            check(gate.releaseForControl("STOP"), "Permite sustituir con cero ACK");
            check(future.isCompletedExceptionally() && !gate.isRequested(), "Barrera parcial resuelta");
            check(!gate.releaseForControl("STOP") && !gate.resume(), "No reanuda ni libera dos veces");
            check(gate.checkpoint(0, () -> { throw new AssertionError("No debe pausar"); }, () -> { }), "Checkpoint liberado");
            gate.requestPause();
            check(gate.status().generation() == 2 && gate.status().acknowledged() == 0, "Nueva generación limpia");
        }
    }

    public static void higherControlWakesAlreadyBlockedActor() throws Exception {
        try (var gate = new PauseCoordinator(3)) {
            var future = gate.requestPause();
            var failure = new AtomicReference<Throwable>();
            Thread actor = new Thread(() -> {
                try { gate.checkpoint(0, () -> { }, () -> { }); }
                catch (Throwable error) { failure.set(error); }
            }, "t56-priority-gate");
            actor.start();
            try {
                await(() -> gate.status().acknowledged() == 1, "ACK parcial");
                check(!future.isDone(), "Los ausentes no reconocen");
                gate.releaseForControl("RESET"); actor.join(3000);
                check(!actor.isAlive() && failure.get() == null, "Actor liberado sin interrumpirlo");
            } finally { gate.close(); actor.interrupt(); actor.join(3000); }
        }
    }

    public static void stopPausedMovementFinishesLegWithoutServing() throws Exception {
        try (var r = new Rig(1, 5, 450, 80)) {
            var c = r.c;
            get(c.start()); get(c.submit(cabin(c, 2)));
            event(c, e -> e.type() == ExecutionEvent.Type.DEPARTED);
            Thread.sleep(70); get(c.pause());
            var frozen = c.snapshot(1); drain(c);
            get(c.stop());
            var stopped = c.snapshot(1);
            check(c.state() == SimulationState.STOPPED && stopped.currentFloor() == 2, "Stop termina el tramo adyacente");
            check(stopped.cabinRequests().size() == 1, "No atiende al llegar por Stop");
            var events = drain(c);
            check(events.stream().noneMatch(e -> e.type() == ExecutionEvent.Type.COMMAND_COMPLETED
                    || e.type() == ExecutionEvent.Type.STOP_STARTED), "Sin servicio entre liberar pausa y adoptar Stop");
            var arrival = events.stream().filter(e -> e.type() == ExecutionEvent.Type.ARRIVED).findFirst().orElseThrow();
            check(arrival.referenceNanos() - frozen.referenceNanos() >= frozen.remainingNanos(), "Resto físico simulado");
            get(c.start()); event(c, e -> e.type() == ExecutionEvent.Type.COMMAND_COMPLETED);
            check(c.snapshot(1).cabinRequests().isEmpty(), "Start atiende el destino conservado");
        }
    }

    public static void stopPausedDwellPreservesBudgetForStart() throws Exception {
        try (var r = new Rig(1, 4, 40, 600)) {
            var c = r.c;
            get(c.start()); get(c.submit(cabin(c, 1)));
            event(c, e -> e.type() == ExecutionEvent.Type.STOP_STARTED);
            Thread.sleep(80); get(c.pause()); var frozen = c.snapshot(1);
            get(c.stop());
            var stopped = c.snapshot(1);
            check(stopped.phase() == ElevatorPhase.HELD && stopped.holdReason().orElseThrow() == ElevatorSnapshot.HoldReason.STOP,
                    "HELD/STOP sustituye HELD/PAUSE");
            check(stopped.remainingNanos() > 0 && stopped.remainingNanos() <= frozen.remainingNanos(), "Presupuesto conservado");
            Thread.sleep(80); check(c.snapshot(1).equals(stopped), "Stop no consume parada");
            get(c.start());
            var resumed = event(c, e -> e.type() == ExecutionEvent.Type.CONTROL_RESUMED);
            var end = event(c, e -> e.type() == ExecutionEvent.Type.STOP_ENDED);
            check(end.referenceNanos() - resumed.referenceNanos() >= stopped.remainingNanos(), "Start consume el restante completo");
        }
    }

    public static void resetPausedMovementReturnsPhysicallyAndCancels() throws Exception {
        try (var r = new Rig(1, 5, 300, 30)) {
            var c = r.c;
            get(c.start()); var request = cabin(c, 5); get(c.submit(request));
            event(c, e -> e.type() == ExecutionEvent.Type.DEPARTED);
            Thread.sleep(50); get(c.pause()); var frozen = c.snapshot(1);
            drain(c); get(c.reset());
            initial(c); check(c.epoch() == 1, "Una época nueva");
            var events = drain(c);
            var arrival = events.stream().filter(e -> e.type() == ExecutionEvent.Type.ARRIVED).findFirst().orElseThrow();
            check(arrival.snapshot().currentFloor() == frozen.nextFloor().orElseThrow(), "Completa el tramo congelado antes de retornar");
            check(arrival.referenceNanos() - frozen.referenceNanos() >= frozen.remainingNanos(), "Sin teleportar el tramo pendiente");
            check(events.stream().noneMatch(e -> e.type() == ExecutionEvent.Type.COMMAND_COMPLETED
                    && e.canonicalRequestId().filter(request.getId()::equals).isPresent()), "No completa trabajo cancelado");
        }
    }

    public static void resetPausedDwellDoesNotRequireResume() throws Exception {
        try (var r = new Rig(1, 4, 40, 700)) {
            var c = r.c;
            get(c.start()); get(c.submit(cabin(c, 1)));
            event(c, e -> e.type() == ExecutionEvent.Type.STOP_STARTED);
            get(c.pause()); get(c.reset()); initial(c);
            check(!c.pauseStatus().requested(), "Compuerta liberada por Reset");
        }
    }

    public static void pauseStopRaceResolvesEveryOperation() throws Exception {
        try (var r = new Rig(8, 4, 40, 20)) {
            var c = r.c; get(c.start());
            var pause = c.pause(); var stop = c.stop();
            get(stop); check(get(pause).outcome() != null, "Pausa reconocida o sustituida");
            check(c.state() == SimulationState.STOPPED && !c.pauseStatus().requested(), "Stop vence la carrera");
        }
    }

    public static void pauseResetRaceResolvesEveryOperation() throws Exception {
        try (var r = new Rig(8, 4, 40, 20)) {
            var c = r.c; get(c.start());
            var pause = c.pause(); var reset = c.reset();
            get(reset); get(pause); initial(c);
            Thread.sleep(20); initial(c); // Confirmaciones de pausa tardías no restauran PAUSED.
        }
    }

    public static void resetSupersedesStopOriginatingInPause() throws Exception {
        try (var r = new Rig(1, 5, 350, 30)) {
            var c = r.c; get(c.start()); get(c.submit(cabin(c, 5)));
            event(c, e -> e.type() == ExecutionEvent.Type.DEPARTED); get(c.pause());
            var stop = c.stop(); var reset = c.reset();
            get(reset); get(stop); initial(c);
            check(c.epoch() == 1 && c.failure().isEmpty(), "Reset vence Stop sin fallos ni dos épocas");
        }
    }

    public static void closeSupersedesResetOriginatingInPause() throws Exception {
        try (var r = new Rig(2, 5, 350, 30)) {
            var c = r.c; get(c.start()); get(c.submit(cabin(c, 5))); get(c.pause());
            var reset = c.reset(); var close = c.closeAsync();
            get(close); get(reset);
            check(c.state() == SimulationState.CLOSED && c.failure().isEmpty(), "Cierre prioritario, sin Resume");
            check(c.elevatorLifecycles().stream().allMatch(l -> l == ElevatorWorker.Lifecycle.TERMINATED), "Actores finalizados");
        }
    }

    public static void pauseIsRejectedWhileStopping() throws Exception {
        try (var r = new Rig(1, 5, 450, 30)) {
            var c = r.c; get(c.start()); get(c.submit(cabin(c, 5)));
            event(c, e -> e.type() == ExecutionEvent.Type.DEPARTED);
            var stop = c.stop(); var pause = get(c.pause());
            check(pause.outcome() == SimulationController.Outcome.REJECTED, "No congela un Stop en vuelo");
            get(stop); check(!c.pauseStatus().requested(), "Sin reloj retenido");
        }
    }

    public static void repeatedResetDuringPausedReturnUsesOneEpoch() throws Exception {
        try (var r = new Rig(1, 5, 300, 30)) {
            var c = r.c; get(c.start()); get(c.submit(cabin(c, 5)));
            event(c, e -> e.type() == ExecutionEvent.Type.DEPARTED); get(c.pause());
            var first = c.reset(); var second = c.reset();
            get(first); get(second); initial(c); check(c.epoch() == 1, "Reset idempotente durante retorno");
        }
    }

    public static void transitionsAndPriorityReasonsAreLogged() throws Exception {
        try (var r = new Rig(1, 4, 40, 30)) {
            var c = r.c;
            get(c.start()); get(c.pause()); get(c.pause()); get(c.stop());
            get(c.start()); get(c.pause()); get(c.reset());
            get(c.start()); get(c.pause()); get(c.resume()); get(c.pause());
            get(c.closeAsync());
            String log = Files.readString(r.directory.resolve("manejador.log"));
            for (String name : List.of("SIMULATION_PAUSING", "SIMULATION_PAUSED", "SIMULATION_RESUMED",
                    "PAUSE_SUPERSEDED_BY_STOP", "PAUSE_SUPERSEDED_BY_RESET", "PAUSE_SUPERSEDED_BY_CLOSE",
                    "SIMULATION_STOPPING", "SIMULATION_STOPPED", "SIMULATION_RESETTING", "SIMULATION_RESET", "SIMULATION_CLOSING")) {
                check(log.contains("evento=" + name + ";"), "Registro completo: " + name);
            }
            check(count(log, "SIMULATION_PAUSED") == 4, "Una transición por pausa efectiva, no por clic repetido");
            check(log.indexOf("evento=SIMULATION_PAUSING;") < log.indexOf("evento=SIMULATION_PAUSED;"), "Orden de reconocimiento");
        }
    }

    public static void supersededPausesNeverPublishLatePausedTransitions() throws Exception {
        try (var r = new Rig(8, 4, 40, 20)) {
            var c = r.c;
            int confirmed = 0;
            for (int i = 0; i < 12; i++) {
                get(c.start()); var pause = c.pause(); var stop = c.stop();
                get(stop);
                if (get(pause).outcome() == SimulationController.Outcome.APPLIED) { confirmed++; }
                check(c.state() == SimulationState.STOPPED, "No revive la pausa sustituida");
            }
            get(c.closeAsync());
            check(count(Files.readString(r.directory.resolve("manejador.log")), "SIMULATION_PAUSED") == confirmed,
                    "Solo pausas confirmadas, sin ACK tardíos publicados");
        }
    }

    private static long count(String text, String event) {
        return text.lines().filter(line -> line.contains("evento=" + event + ";")).count();
    }
    private static CabinRequest cabin(SimulationController c, int floor) {
        return new CabinRequest(UUID.randomUUID(), c.epoch(), floor, Instant.now(), 1);
    }
    private static SimulationController.Result get(CompletableFuture<SimulationController.Result> f) throws Exception {
        return f.get(8, TimeUnit.SECONDS);
    }
    private static void initial(SimulationController c) {
        check(c.state() == SimulationState.RESET && c.snapshots().stream().allMatch(s -> s.currentFloor() == 1
                && s.direction() == Direction.UP && s.phase() == ElevatorPhase.IDLE && s.cabinRequests().isEmpty()
                && s.hallRequests().isEmpty() && s.nextFloor().isEmpty()), "Retorno 1/UP completo y limpio");
    }
    private static ExecutionEvent event(SimulationController c, Predicate<ExecutionEvent> wanted) throws Exception {
        long started = System.nanoTime();
        while (true) {
            long left = LIMIT.toNanos() - (System.nanoTime() - started); check(left > 0, "Plazo de evento");
            var event = c.awaitEvent(Duration.ofNanos(left));
            if (event.isPresent() && wanted.test(event.orElseThrow())) { return event.orElseThrow(); }
        }
    }
    private static List<ExecutionEvent> drain(SimulationController c) throws Exception {
        var list = new ArrayList<ExecutionEvent>();
        Optional<ExecutionEvent> event;
        while ((event = c.awaitEvent(Duration.ZERO)).isPresent()) { list.add(event.orElseThrow()); }
        return list;
    }
    private static void await(BooleanSupplier condition, String detail) throws Exception {
        long started = System.nanoTime();
        while (!condition.getAsBoolean()) { check(System.nanoTime() - started < LIMIT.toNanos(), detail); Thread.sleep(1); }
    }
    private static final class Rig implements AutoCloseable {
        final Path directory = Files.createTempDirectory("t56-priority-");
        final SimulationController c;
        Rig(int n, int floors, int movement, int dwell) throws Exception {
            var log = SessionLog.open(directory, n, OutputStream.nullOutputStream());
            try { c = new SimulationController(new SimulationConfig(n, floors, movement, dwell), 0, log); }
            catch (RuntimeException | Error e) { log.close(); throw e; }
        }
        @Override public void close() throws Exception {
            c.close(); check(c.awaitTermination(LIMIT), "Cierre real del fixture");
            try (var paths = Files.walk(directory)) {
                for (var path : paths.sorted(Comparator.reverseOrder()).toList()) { Files.deleteIfExists(path); }
            }
        }
    }
    private static void check(boolean value, String detail) { if (!value) { throw new AssertionError(detail); } }

    public static void main(String[] args) throws Exception {
        higherControlReleasesPartialBarrier();
        higherControlWakesAlreadyBlockedActor();
        stopPausedMovementFinishesLegWithoutServing();
        stopPausedDwellPreservesBudgetForStart();
        resetPausedMovementReturnsPhysicallyAndCancels();
        resetPausedDwellDoesNotRequireResume();
        pauseStopRaceResolvesEveryOperation();
        pauseResetRaceResolvesEveryOperation();
        resetSupersedesStopOriginatingInPause();
        closeSupersedesResetOriginatingInPause();
        pauseIsRejectedWhileStopping();
        repeatedResetDuringPausedReturnUsesOneEpoch();
        transitionsAndPriorityReasonsAreLogged();
        supersededPausesNeverPublishLatePausedTransitions();
        System.out.println("T56: 14 escenarios de prioridades y trazas satisfactorios.");
    }
}
```

## src/test/java/com/elevatormanager/simulation/PausePriorityTest.java

```java
package com.elevatormanager.simulation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** T56: escenarios completos bajo JUnit. */
@Timeout(30)
class PausePriorityTest {
    @Test void higherControlReleasesPartialBarrier() throws Exception {
        PausePriorityScenarios.higherControlReleasesPartialBarrier();
    }
    @Test void higherControlWakesAlreadyBlockedActor() throws Exception {
        PausePriorityScenarios.higherControlWakesAlreadyBlockedActor();
    }
    @Test void stopPausedMovementFinishesLegWithoutServing() throws Exception {
        PausePriorityScenarios.stopPausedMovementFinishesLegWithoutServing();
    }
    @Test void stopPausedDwellPreservesBudgetForStart() throws Exception {
        PausePriorityScenarios.stopPausedDwellPreservesBudgetForStart();
    }
    @Test void resetPausedMovementReturnsPhysicallyAndCancels() throws Exception {
        PausePriorityScenarios.resetPausedMovementReturnsPhysicallyAndCancels();
    }
    @Test void resetPausedDwellDoesNotRequireResume() throws Exception {
        PausePriorityScenarios.resetPausedDwellDoesNotRequireResume();
    }
    @Test void pauseStopRaceResolvesEveryOperation() throws Exception {
        PausePriorityScenarios.pauseStopRaceResolvesEveryOperation();
    }
    @Test void pauseResetRaceResolvesEveryOperation() throws Exception {
        PausePriorityScenarios.pauseResetRaceResolvesEveryOperation();
    }
    @Test void resetSupersedesStopOriginatingInPause() throws Exception {
        PausePriorityScenarios.resetSupersedesStopOriginatingInPause();
    }
    @Test void closeSupersedesResetOriginatingInPause() throws Exception {
        PausePriorityScenarios.closeSupersedesResetOriginatingInPause();
    }
    @Test void pauseIsRejectedWhileStopping() throws Exception {
        PausePriorityScenarios.pauseIsRejectedWhileStopping();
    }
    @Test void repeatedResetDuringPausedReturnUsesOneEpoch() throws Exception {
        PausePriorityScenarios.repeatedResetDuringPausedReturnUsesOneEpoch();
    }
    @Test void transitionsAndPriorityReasonsAreLogged() throws Exception {
        PausePriorityScenarios.transitionsAndPriorityReasonsAreLogged();
    }
    @Test void supersededPausesNeverPublishLatePausedTransitions() throws Exception {
        PausePriorityScenarios.supersededPausesNeverPublishLatePausedTransitions();
    }
}
```

## src/test/java/com/elevatormanager/ui/PauseControlsScenarios.java

```java
package com.elevatormanager.ui;

import com.elevatormanager.logging.SessionLog;
import com.elevatormanager.model.*;
import com.elevatormanager.model.SimulationConfig.Field;
import com.elevatormanager.simulation.SimulationController;
import com.elevatormanager.ui.SystemControlPanel.Action;
import java.io.OutputStream;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import javax.swing.*;

/** T56: botones Swing en EDT con motor/log reales y refresco normal; sin JFrame/Robot. */
public final class PauseControlsScenarios {
    private PauseControlsScenarios() { }

    public static void pauseControlsRequireConfiguredRunningSession() throws Exception {
        try (var r = new Rig()) {
            edt(() -> check(!r.enabled(Action.PAUSE) && !r.enabled(Action.RESUME), "Sin sesión no hay pausa"));
            r.prepare(1, 4, 200);
            edt(() -> check(!r.enabled(Action.PAUSE) && !r.enabled(Action.RESUME), "RESET no pausa ni reanuda"));
            r.start();
            edt(() -> check(r.enabled(Action.PAUSE) && !r.enabled(Action.RESUME), "RUNNING ofrece Pausar"));
        }
    }

    public static void pauseRequestImmediatelyShowsPausingAndKeepsPriorityControls() throws Exception {
        try (var r = new Rig()) {
            r.prepare(8, 4, 200); r.start();
            edt(() -> {
                r.panel.button(Action.PAUSE).doClick(0);
                check(r.panel.stateText().contains("PAUSING"), "No anuncia PAUSED al pulsar");
                check(!r.enabled(Action.RESUME) && !r.enabled(Action.PAUSE), "Sin doble pausa ni Resume anticipado");
                check(r.enabled(Action.STOP) && r.enabled(Action.RESET) && r.enabled(Action.CLOSE), "Prioridades disponibles");
            });
            r.state(SimulationState.PAUSED);
            check(r.c().pauseStatus().acknowledged() == 9, "PAUSED solo con nueve ACK");
        }
    }

    public static void pausedButtonMatrixBlocksRequestsAndImport() throws Exception {
        try (var r = new Rig()) {
            r.prepare(1, 4, 200); r.start(); r.pause();
            edt(() -> {
                check(r.enabled(Action.RESUME) && r.enabled(Action.STOP) && r.enabled(Action.RESET) && r.enabled(Action.CLOSE), "Controles legales");
                check(!r.enabled(Action.START) && !r.enabled(Action.PAUSE) && !r.enabled(Action.LOAD), "Controles bloqueados");
                check(!r.cabin().destinationButton(4).isEnabled(), "Destino bloqueado");
                check(!r.panel.hallCallPanel().button(Direction.UP).isEnabled(), "Llamada bloqueada");
            });
        }
    }

    public static void resumeButtonKeepsSessionAndPendingLight() throws Exception {
        try (var r = new Rig()) {
            r.prepare(1, 5, 350); r.start(); r.destination(5);
            await("cabina moviéndose", () -> r.c().snapshot(1).phase() == ElevatorPhase.MOVING);
            UUID identity = r.c().sessionId(); r.pause();
            var frozen = r.c().snapshot(1);
            await("fase visible en pausa", () -> onEdt(() -> r.cabin().phaseText().contains("En pausa")));
            edt(() -> check(r.cabin().litFloors().contains(5), "Luz conservada"));
            Thread.sleep(400); check(r.c().snapshot(1).equals(frozen), "Sin avance mientras se inspecciona");
            r.click(Action.RESUME); r.state(SimulationState.RUNNING);
            check(r.c().sessionId().equals(identity) && r.c().epoch() == 0, "Misma sesión y época");
            await("atención y apagado", () -> r.c().snapshot(1).currentFloor() == 5
                    && onEdt(() -> !r.cabin().litFloors().contains(5)));
        }
    }

    public static void stopButtonWorksFromPausedMovement() throws Exception {
        try (var r = new Rig()) {
            r.prepare(1, 4, 400); r.start(); r.destination(2);
            await("tramo iniciado", () -> r.c().snapshot(1).phase() == ElevatorPhase.MOVING);
            r.pause(); r.click(Action.STOP); r.state(SimulationState.STOPPED);
            check(r.c().snapshot(1).currentFloor() == 2 && r.c().snapshot(1).cabinRequests().size() == 1, "Stop sin servicio intermedio");
            edt(() -> check(r.enabled(Action.START) && !r.enabled(Action.RESUME), "Continúa con Start"));
            r.start(); await("destino atendido", () -> r.c().snapshot(1).cabinRequests().isEmpty());
        }
    }

    public static void resetButtonWorksFromPausedMovement() throws Exception {
        try (var r = new Rig()) {
            r.prepare(1, 5, 250); r.start(); r.destination(5);
            await("movimiento", () -> r.c().snapshot(1).phase() == ElevatorPhase.MOVING);
            r.pause(); r.click(Action.RESET); r.state(SimulationState.RESET);
            check(r.c().epoch() == 1 && r.c().snapshot(1).currentFloor() == 1
                    && r.c().snapshot(1).cabinRequests().isEmpty(), "Retorno real con limpieza");
            edt(() -> check(r.enabled(Action.LOAD) && r.enabled(Action.START) && !r.enabled(Action.RESUME), "RESET habilita carga"));
        }
    }

    public static void immediateStopSupersedesPendingPauseInSameEdtTurn() throws Exception {
        try (var r = new Rig()) {
            r.prepare(8, 4, 200); r.start();
            edt(() -> {
                r.panel.button(Action.PAUSE).doClick(0);
                check(r.enabled(Action.STOP), "Stop habilitado durante petición");
                r.panel.button(Action.STOP).doClick(0);
            });
            r.state(SimulationState.STOPPED); Thread.sleep(120);
            edt(() -> check(r.panel.stateText().contains("STOPPED") && !r.enabled(Action.RESUME), "Respuesta vieja no restaura PAUSED"));
        }
    }

    public static void immediateResetSupersedesPendingPauseInSameEdtTurn() throws Exception {
        try (var r = new Rig()) {
            r.prepare(8, 4, 200); r.start();
            edt(() -> { r.panel.button(Action.PAUSE).doClick(0); r.panel.button(Action.RESET).doClick(0); });
            r.state(SimulationState.RESET); Thread.sleep(120);
            check(r.c().epoch() == 1, "Una época");
            edt(() -> check(r.panel.stateText().contains("RESET") && r.enabled(Action.LOAD), "Respuesta vigente"));
        }
    }

    public static void closeButtonReleasesPausedActorsAndSampler() throws Exception {
        try (var r = new Rig()) {
            r.prepare(3, 4, 300); r.start(); r.destination(4); r.pause();
            r.closeViaButton();
            check(r.c().state() == SimulationState.CLOSED && r.c().failure().isEmpty(), "Cierre sin reanudar");
            check(onEdt(() -> r.panel.snapshotPump().closeAsync()).isDone(), "Capturador cerrado");
        }
    }

    public static void closeButtonCanSupersedePendingPause() throws Exception {
        try (var r = new Rig()) {
            r.prepare(8, 4, 200); r.start();
            edt(() -> { r.panel.button(Action.PAUSE).doClick(0); r.panel.button(Action.CLOSE).doClick(0); });
            r.closed();
            check(r.c().state() == SimulationState.CLOSED && r.c().failure().isEmpty(), "Cierre prioritario desde Pausando");
        }
    }

    public static void viewerShowsPauseAndResumeTransitions() throws Exception {
        try (var r = new Rig()) {
            r.prepare(1, 4, 200); r.start(); r.pause();
            await("PAUSED en visor", () -> onEdt(() -> r.panel.eventViewer().displayedText().contains("SIMULATION_PAUSED")));
            r.click(Action.RESUME); r.state(SimulationState.RUNNING);
            await("RESUMED en visor", () -> onEdt(() -> r.panel.eventViewer().displayedText().contains("SIMULATION_RESUMED")));
        }
    }

    public static void repeatedButtonCyclesDoNotCreateSessions() throws Exception {
        try (var r = new Rig()) {
            r.prepare(2, 4, 100); r.start(); var original = r.c();
            for (int i = 0; i < 5; i++) { r.pause(); r.click(Action.RESUME); r.state(SimulationState.RUNNING); }
            check(r.c() == original && r.c().pauseStatus().generation() == 5, "Cinco ciclos sobre la misma sesión");
        }
    }

    public static void stoppedAndResettingDisablePauseAndResume() throws Exception {
        try (var r = new Rig()) {
            r.prepare(1, 4, 300); r.start(); r.destination(4);
            await("tramo", () -> r.c().snapshot(1).phase() == ElevatorPhase.MOVING);
            edt(() -> {
                r.panel.button(Action.STOP).doClick(0);
                check(!r.enabled(Action.PAUSE) && !r.enabled(Action.RESUME), "STOPPING no pausa ni reanuda");
            });
            r.state(SimulationState.STOPPED);
            edt(() -> check(!r.enabled(Action.PAUSE) && !r.enabled(Action.RESUME), "STOPPED no pausa ni reanuda"));
            edt(() -> {
                r.panel.button(Action.RESET).doClick(0);
                check(!r.enabled(Action.PAUSE) && !r.enabled(Action.RESUME), "RESETTING no pausa ni reanuda");
            });
            r.state(SimulationState.RESET);
        }
    }

    public static void pausedResetRestoresImportThroughButtons() throws Exception {
        try (var r = new Rig()) {
            r.prepare(1, 4, 100); r.start(); r.pause(); r.click(Action.RESET); r.state(SimulationState.RESET);
            Path file = r.directory.resolve("requests.txt"); Files.writeString(file, "CABIN 1 3\n"); r.selected.set(Optional.of(file));
            r.click(Action.LOAD);
            await("lote confirmado", () -> r.c().preparedBatch().isPresent() && onEdt(() -> r.enabled(Action.LOAD)));
            check(r.c().snapshot(1).currentFloor() == 1 && r.c().epoch() == 1, "Importa en nueva época sin mover");
        }
    }

    private static final class Rig implements AutoCloseable {
        final Path directory = Files.createTempDirectory("t56-gui-");
        final AtomicReference<Optional<Path>> selected = new AtomicReference<>(Optional.empty());
        final AtomicBoolean closed = new AtomicBoolean();
        final AtomicReference<Throwable> closeFailure = new AtomicReference<>();
        final SystemControlPanel panel;
        Rig() throws Exception {
            panel = onEdt(() -> new SystemControlPanel(config -> {
                var log = SessionLog.open(directory.resolve("logs"), config.elevatorCount(), OutputStream.nullOutputStream());
                try { return new SimulationController(config, 0, log); }
                catch (RuntimeException | Error e) { log.close(); throw e; }
            }, selected::get));
            edt(() -> {
                var handler = new WindowCloseHandler(panel::closeAsync, () -> { },
                        () -> { panel.closed(); closed.set(true); }, e -> { closeFailure.set(e); panel.closeFailed(e); });
                panel.onCloseRequest(handler::requestClose);
            });
        }
        void prepare(int cabins, int floors, int movement) throws Exception {
            edt(() -> {
                String[] values = {"" + cabins, "" + floors, "" + movement, "60"};
                for (Field field : Field.values()) { panel.configurationPanel().input(field).setText(values[field.ordinal()]); }
                panel.configurationPanel().validateConfiguration();
            });
            await("sesión preparada", () -> c() != null && onEdt(() -> enabled(Action.START)));
        }
        SimulationController c() { return onEdt(() -> panel.controller().orElse(null)); }
        CabinPanel cabin() { return panel.cabinDashboard().cabin(1); }
        boolean enabled(Action action) { return panel.button(action).isEnabled(); }
        void click(Action action) throws Exception {
            await("control " + action, () -> onEdt(() -> enabled(action)));
            edt(() -> panel.button(action).doClick(0));
        }
        void start() throws Exception { click(Action.START); state(SimulationState.RUNNING); }
        void pause() throws Exception { click(Action.PAUSE); state(SimulationState.PAUSED); }
        void state(SimulationState expected) throws Exception {
            await("estado " + expected, () -> c().state() == expected && onEdt(() -> {
                Action ready = expected == SimulationState.RUNNING ? Action.PAUSE
                        : expected == SimulationState.PAUSED ? Action.RESUME : Action.START;
                return panel.stateText().contains("Estado: " + expected) && enabled(ready);
            }));
        }
        void destination(int floor) throws Exception {
            await("destino habilitado", () -> onEdt(() -> cabin().destinationButton(floor).isEnabled()));
            edt(() -> cabin().destinationButton(floor).doClick(0));
        }
        void closeViaButton() throws Exception { click(Action.CLOSE); closed(); }
        void closed() throws Exception {
            await("cierre confirmado", () -> closed.get() || closeFailure.get() != null);
            check(closeFailure.get() == null && c().awaitTermination(Duration.ofSeconds(5)), "Cierre real de sesión");
        }
        @Override public void close() throws Exception {
            try { if (!closed.get()) { onEdt(panel::closeAsync).get(10, TimeUnit.SECONDS); edt(panel::closed); } }
            finally {
                try (var paths = Files.walk(directory)) {
                    for (var path : paths.sorted(Comparator.reverseOrder()).toList()) { Files.deleteIfExists(path); }
                }
            }
        }
    }
    private static void await(String detail, BooleanSupplier condition) throws Exception {
        long start = System.nanoTime();
        while (!condition.getAsBoolean()) { check(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(10), detail); Thread.sleep(3); }
    }
    private static void edt(Runnable action) { onEdt(() -> { action.run(); return null; }); }
    private static <T> T onEdt(Supplier<T> action) {
        if (SwingUtilities.isEventDispatchThread()) { return action.get(); }
        var value = new AtomicReference<T>(); var failure = new AtomicReference<Throwable>();
        try { SwingUtilities.invokeAndWait(() -> { try { value.set(action.get()); } catch (Throwable e) { failure.set(e); } }); }
        catch (Exception e) { throw new AssertionError(e); }
        if (failure.get() instanceof Error e) { throw e; }
        if (failure.get() != null) { throw new IllegalStateException(failure.get()); }
        return value.get();
    }
    private static void check(boolean condition, String detail) { if (!condition) { throw new AssertionError(detail); } }

    public static void main(String[] args) throws Exception {
        pauseControlsRequireConfiguredRunningSession();
        pauseRequestImmediatelyShowsPausingAndKeepsPriorityControls();
        pausedButtonMatrixBlocksRequestsAndImport();
        resumeButtonKeepsSessionAndPendingLight();
        stopButtonWorksFromPausedMovement();
        resetButtonWorksFromPausedMovement();
        immediateStopSupersedesPendingPauseInSameEdtTurn();
        immediateResetSupersedesPendingPauseInSameEdtTurn();
        closeButtonReleasesPausedActorsAndSampler();
        closeButtonCanSupersedePendingPause();
        viewerShowsPauseAndResumeTransitions();
        repeatedButtonCyclesDoNotCreateSessions();
        stoppedAndResettingDisablePauseAndResume();
        pausedResetRestoresImportThroughButtons();
        System.out.println("T56: 14 escenarios de controles Swing satisfactorios.");
    }
}
```

## src/test/java/com/elevatormanager/ui/PauseControlsTest.java

```java
package com.elevatormanager.ui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** T56: escenarios completos bajo JUnit. */
@Timeout(30)
class PauseControlsTest {
    @Test void pauseControlsRequireConfiguredRunningSession() throws Exception {
        PauseControlsScenarios.pauseControlsRequireConfiguredRunningSession();
    }
    @Test void pauseRequestImmediatelyShowsPausingAndKeepsPriorityControls() throws Exception {
        PauseControlsScenarios.pauseRequestImmediatelyShowsPausingAndKeepsPriorityControls();
    }
    @Test void pausedButtonMatrixBlocksRequestsAndImport() throws Exception {
        PauseControlsScenarios.pausedButtonMatrixBlocksRequestsAndImport();
    }
    @Test void resumeButtonKeepsSessionAndPendingLight() throws Exception {
        PauseControlsScenarios.resumeButtonKeepsSessionAndPendingLight();
    }
    @Test void stopButtonWorksFromPausedMovement() throws Exception {
        PauseControlsScenarios.stopButtonWorksFromPausedMovement();
    }
    @Test void resetButtonWorksFromPausedMovement() throws Exception {
        PauseControlsScenarios.resetButtonWorksFromPausedMovement();
    }
    @Test void immediateStopSupersedesPendingPauseInSameEdtTurn() throws Exception {
        PauseControlsScenarios.immediateStopSupersedesPendingPauseInSameEdtTurn();
    }
    @Test void immediateResetSupersedesPendingPauseInSameEdtTurn() throws Exception {
        PauseControlsScenarios.immediateResetSupersedesPendingPauseInSameEdtTurn();
    }
    @Test void closeButtonReleasesPausedActorsAndSampler() throws Exception {
        PauseControlsScenarios.closeButtonReleasesPausedActorsAndSampler();
    }
    @Test void closeButtonCanSupersedePendingPause() throws Exception {
        PauseControlsScenarios.closeButtonCanSupersedePendingPause();
    }
    @Test void viewerShowsPauseAndResumeTransitions() throws Exception {
        PauseControlsScenarios.viewerShowsPauseAndResumeTransitions();
    }
    @Test void repeatedButtonCyclesDoNotCreateSessions() throws Exception {
        PauseControlsScenarios.repeatedButtonCyclesDoNotCreateSessions();
    }
    @Test void stoppedAndResettingDisablePauseAndResume() throws Exception {
        PauseControlsScenarios.stoppedAndResettingDisablePauseAndResume();
    }
    @Test void pausedResetRestoresImportThroughButtons() throws Exception {
        PauseControlsScenarios.pausedResetRestoresImportThroughButtons();
    }
}
```
