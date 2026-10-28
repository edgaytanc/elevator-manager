# T39 — Código Java completo

Las cuatro clases de producción nuevas o modificadas y las dos clases de pruebas se presentan íntegramente. Las rutas son relativas a la raíz Maven; los originales .java también están incluidos en el ZIP.

## src/main/java/com/elevatormanager/simulation/SimulationController.java

```java
package com.elevatormanager.simulation;

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
        this.config = Objects.requireNonNull(config, "config");
        if (epoch < 0) { throw new IllegalArgumentException("Época negativa."); }
        this.epoch = epoch;
        elevators = java.util.stream.IntStream.rangeClosed(1, config.elevatorCount())
                .mapToObj(id -> new ElevatorWorker(id, config, epoch)).toList();
        elevators.forEach(e -> e.subscribeEvents(events));
        manager = new ManagerWorker(config, epoch, elevators, this::nextCommandId);
    }

    public SimulationConfig config() { return config; }
    public long epoch() { return epoch; }
    public SimulationState state() { return state; }
    public Optional<Throwable> failure() { return Optional.ofNullable(failure); }

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

## src/main/java/com/elevatormanager/logging/OwnedLogHandlers.java

```java
package com.elevatormanager.logging;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.logging.Handler;
import java.util.logging.Logger;

/** Recursos de logging propiedad de una sesión; no toca handlers ajenos o del padre. */
public final class OwnedLogHandlers implements AutoCloseable {
    private final Logger logger;
    private final List<Handler> handlers;
    private boolean closed;
    private RuntimeException failure;

    public OwnedLogHandlers(Logger logger, List<? extends Handler> handlers) {
        this.logger = Objects.requireNonNull(logger, "logger");
        this.handlers = List.copyOf(handlers);
        Set<Handler> identities = Collections.newSetFromMap(new IdentityHashMap<>());
        for (var handler : this.handlers) {
            if (!identities.add(handler)) { throw new IllegalArgumentException("Handler repetido por identidad."); }
        }
    }

    /** Desconecta, vacía y cierra cada handler propio; completa los intentos ante fallos. */
    @Override public synchronized void close() {
        if (closed) {
            if (failure != null) { throw failure; }
            return;
        }
        closed = true;
        var errors = new java.util.ArrayList<RuntimeException>();
        for (int i = handlers.size() - 1; i >= 0; i--) {
            Handler handler = handlers.get(i);
            try { logger.removeHandler(handler); } catch (RuntimeException error) { errors.add(error); }
            try { handler.flush(); } catch (RuntimeException error) { errors.add(error); }
            try { handler.close(); } catch (RuntimeException error) { errors.add(error); }
        }
        if (!errors.isEmpty()) {
            failure = new IllegalStateException("No se confirmó el cierre de todos los handlers propios.");
            errors.forEach(failure::addSuppressed);
            throw failure;
        }
    }
}
```

## src/main/java/com/elevatormanager/ui/WindowCloseHandler.java

```java
package com.elevatormanager.ui;

import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.swing.SwingUtilities;

/** Une botón y X a una única solicitud; callbacks visuales exclusivamente en EDT. */
public final class WindowCloseHandler {
    private final Supplier<? extends CompletionStage<?>> closeAction;
    private final Runnable onClosing;
    private final Runnable onClosed;
    private final Consumer<Throwable> onFailure;
    private boolean closing;
    private boolean closed;

    public WindowCloseHandler(Supplier<? extends CompletionStage<?>> closeAction,
            Runnable onClosing, Runnable onClosed, Consumer<Throwable> onFailure) {
        this.closeAction = Objects.requireNonNull(closeAction, "closeAction");
        this.onClosing = Objects.requireNonNull(onClosing, "onClosing");
        this.onClosed = Objects.requireNonNull(onClosed, "onClosed");
        this.onFailure = Objects.requireNonNull(onFailure, "onFailure");
    }

    public void requestClose() {
        requireEdt();
        if (closing || closed) { return; }
        closing = true;
        try {
            onClosing.run();
            Objects.requireNonNull(closeAction.get(), "closeStage").whenComplete((ignored, error) ->
                    SwingUtilities.invokeLater(() -> finish(error)));
        } catch (RuntimeException exception) { finish(exception); }
    }

    private void finish(Throwable error) {
        requireEdt();
        if (error == null) {
            closing = false;
            closed = true;
            onClosed.run();
        } else {
            closing = false;
            Throwable cause = error instanceof CompletionException && error.getCause() != null
                    ? error.getCause() : error;
            onFailure.accept(cause);
        }
    }

    public boolean isClosing() { requireEdt(); return closing; }
    public boolean isClosed() { requireEdt(); return closed; }

    private static void requireEdt() {
        if (!SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("El control visual de cierre requiere EDT.");
        }
    }
}
```

## src/main/java/com/elevatormanager/ui/MainWindow.java

```java
package com.elevatormanager.ui;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;

/** Ventana mínima; su disposición espera la confirmación de cierre asíncrono. */
public final class MainWindow {
    private static final Logger LOGGER = Logger.getLogger(MainWindow.class.getName());
    private final JFrame frame;

    /** Todavía no hay sesión configurada en la entrada mínima; no hay actores que liberar. */
    public MainWindow() { this(() -> CompletableFuture.completedFuture(null)); }

    /** Una sesión existente se conecta con controller::closeAsync. */
    public MainWindow(Supplier<? extends CompletionStage<?>> closeAction) {
        requireEventDispatchThread();
        frame = new JFrame("Elevator Manager");
        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        JPanel content = new JPanel(new BorderLayout(12, 12));
        content.setBorder(BorderFactory.createEmptyBorder(24, 24, 24, 24));
        JPanel messages = new JPanel(new GridLayout(0, 1, 0, 12));
        messages.add(new JLabel("Elevator Manager", SwingConstants.CENTER));
        messages.add(new JLabel("Simulador de elevadores", SwingConstants.CENTER));
        JLabel status = new JLabel("Listo.", SwingConstants.CENTER);
        messages.add(status);
        content.add(messages, BorderLayout.CENTER);
        JButton closeButton = new JButton("Cerrar");
        var close = new WindowCloseHandler(closeAction,
                () -> { closeButton.setEnabled(false); status.setText("Cerrando y liberando recursos..."); },
                frame::dispose,
                error -> {
                    status.setText("No se pudo completar el cierre. Consulta el registro.");
                    closeButton.setEnabled(true);
                    LOGGER.log(Level.SEVERE, "CIERRE_NO_COMPLETADO", error);
                });
        closeButton.addActionListener(event -> close.requestClose());
        frame.addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent event) { close.requestClose(); }
        });
        JPanel actions = new JPanel();
        actions.add(closeButton);
        content.add(actions, BorderLayout.SOUTH);
        frame.setContentPane(content);
        frame.setMinimumSize(new Dimension(560, 240));
        frame.pack();
        frame.setLocationRelativeTo(null);
    }

    public void showWindow() {
        requireEventDispatchThread();
        frame.setVisible(true);
    }

    private static void requireEventDispatchThread() {
        if (!SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("La ventana debe utilizarse desde el EDT.");
        }
    }
}
```

## src/test/java/com/elevatormanager/simulation/OrderlyShutdownScenarios.java

```java
package com.elevatormanager.simulation;

import com.elevatormanager.dispatch.ManagerWorker;
import com.elevatormanager.logging.OwnedLogHandlers;
import com.elevatormanager.model.*;
import com.elevatormanager.model.request.CabinRequest;
import com.elevatormanager.ui.WindowCloseHandler;
import java.nio.file.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.logging.*;
import javax.swing.SwingUtilities;
import static com.elevatormanager.simulation.ExecutionEvent.Type.*;
import static com.elevatormanager.simulation.SimulationController.Outcome.*;

/** T39: cierre real, recursos y puente EDT sin depender de una pantalla gráfica. */
public final class OrderlyShutdownScenarios {
    private static final Duration LIMIT = Duration.ofSeconds(10);
    private OrderlyShutdownScenarios() { }

    public static void newSessionClosesAutomatically() throws Exception {
        var c = create(1, 5, 20, 10);
        check(get(c.closeAsync()).state() == SimulationState.CLOSED, "CLOSED sin usar awaitTermination para impulsarlo");
        ended(c);
    }

    public static void runningIdleSessionClosesAllWorkers() throws Exception {
        var c = create(4, 5, 20, 10); get(c.start()); get(c.closeAsync()); ended(c);
    }

    public static void movementDoesNotDelayCloseByItsDuration() throws Exception {
        var c = create(1, 5, 60_000, 10);
        try {
            get(c.start()); get(c.submit(cabin(5))); until(c, DEPARTED);
            get(c.closeAsync()); ended(c);
            check(c.snapshots().get(0).currentFloor() == 1, "No espera un minuto ni inventa una llegada");
        } finally { c.close(); c.awaitTermination(LIMIT); }
    }

    public static void dwellDoesNotDelayCloseByItsDuration() throws Exception {
        var c = create(1, 5, 10, 60_000);
        try { get(c.start()); get(c.submit(cabin(1))); until(c, COMMAND_COMPLETED); get(c.closeAsync()); ended(c); }
        finally { c.close(); c.awaitTermination(LIMIT); }
    }

    public static void stoppedSessionWakesAndCloses() throws Exception {
        var c = create(3, 5, 20, 10); get(c.start()); get(c.stop()); get(c.closeAsync()); ended(c);
    }

    public static void pendingStopIsResolvedByClose() throws Exception {
        var c = create(1, 5, 60_000, 10);
        try {
            get(c.start()); get(c.submit(cabin(5))); until(c, DEPARTED);
            var stop = c.stop(); var closed = c.closeAsync();
            check(get(stop).outcome() == REJECTED, "Stop pendiente no queda colgado"); get(closed); ended(c);
        } finally { c.close(); c.awaitTermination(LIMIT); }
    }

    public static void pendingResetIsResolvedByClose() throws Exception {
        var c = create(1, 5, 100, 10);
        try {
            get(c.start()); get(c.submit(cabin(4))); until(c, STOP_ENDED);
            var reset = c.reset(); until(c, RESET_STARTED); var closed = c.closeAsync();
            check(get(reset).outcome() == REJECTED, "Reset pendiente invalidado por cierre"); get(closed); ended(c);
        } finally { c.close(); c.awaitTermination(LIMIT); }
    }

    public static void resetReadySessionCloses() throws Exception {
        var c = create(3, 5, 20, 10); get(c.reset()); get(c.closeAsync()); ended(c);
    }

    public static void concurrentCloseRunsResourcesOnce() throws Exception {
        var c = create(2, 5, 20, 10); var calls = new AtomicInteger(); get(c.registerResource(calls::incrementAndGet)); get(c.start());
        var pool = Executors.newFixedThreadPool(8); var gate = new CountDownLatch(1);
        try {
            var futures = new ArrayList<Future<SimulationController.Result>>();
            for (int i = 0; i < 64; i++) { futures.add(pool.submit(() -> { gate.await(); return get(c.closeAsync()); })); }
            gate.countDown();
            for (var f : futures) { check(f.get(10, TimeUnit.SECONDS).state() == SimulationState.CLOSED, "Confirmación compartida"); }
            ended(c); check(calls.get() == 1, "Un único cierre del recurso");
        } finally { gate.countDown(); pool.shutdownNow(); c.close(); c.awaitTermination(LIMIT); }
    }

    public static void cancelledVisibleFutureDoesNotCancelShutdown() throws Exception {
        var c = create(1, 5, 20, 10); var resource = new BlockingResource(); get(c.registerResource(resource));
        var visible = c.closeAsync();
        try {
            resource.entered(); visible.cancel(false); var observer = c.closeAsync(); resource.release();
            check(get(observer).state() == SimulationState.CLOSED, "Cierre privado continúa"); ended(c);
        } finally { resource.release(); c.close(); c.awaitTermination(LIMIT); }
    }

    public static void resourcesCloseInReverseRegistrationOrder() throws Exception {
        var c = create(1, 1, 1, 1); var order = new CopyOnWriteArrayList<Integer>();
        get(c.registerResource(() -> order.add(1))); get(c.registerResource(() -> order.add(2))); get(c.registerResource(() -> order.add(3)));
        get(c.closeAsync()); ended(c); check(order.equals(List.of(3, 2, 1)), "Orden inverso de adquisición");
    }

    public static void duplicateResourceIsNotClosedTwice() throws Exception {
        var c = create(1, 1, 1, 1); var count = new AtomicInteger(); AutoCloseable resource = count::incrementAndGet;
        check(get(c.registerResource(resource)).outcome() == APPLIED, "Propiedad transferida");
        check(get(c.registerResource(resource)).outcome() == UNCHANGED, "Identidad duplicada");
        get(c.closeAsync()); ended(c); check(count.get() == 1, "Solo un cierre");
    }

    public static void rejectedResourceRemainsOwnedByCaller() throws Exception {
        var c = create(1, 1, 1, 1); get(c.closeAsync()); ended(c);
        var count = new AtomicInteger(); AutoCloseable resource = count::incrementAndGet;
        check(get(c.registerResource(resource)).outcome() == REJECTED && count.get() == 0, "No adopta después de cerrar");
        resource.close(); check(count.get() == 1, "Llamador conserva propiedad");
    }

    public static void resourcesAreReleasedAfterActorsTerminate() throws Exception {
        var c = create(2, 5, 60_000, 10); var checked = new AtomicBoolean();
        get(c.registerResource(() -> {
            check(c.managerLifecycle() == ManagerWorker.Lifecycle.TERMINATED, "Manejador terminó antes del recurso");
            check(c.elevatorLifecycles().stream().allMatch(l -> l == ElevatorWorker.Lifecycle.TERMINATED), "Cabinas terminaron antes del recurso");
            check(c.state() == SimulationState.CLOSING, "No publica CLOSED prematuramente"); checked.set(true);
        }));
        get(c.start()); get(c.submit(cabin(5))); until(c, DEPARTED); get(c.closeAsync()); ended(c); check(checked.get(), "Recurso verificado");
    }

    public static void blockedResourceDoesNotPublishClosed() throws Exception {
        var c = create(1, 1, 1, 1); var resource = new BlockingResource(); get(c.registerResource(resource)); var future = c.closeAsync();
        try {
            resource.entered(); check(c.state() == SimulationState.CLOSING && !future.isDone(), "Recursos pendientes no equivalen a CLOSED");
            check(!c.awaitTermination(Duration.ZERO), "Sondeo no afirma terminación");
        } finally { resource.release(); get(future); ended(c); }
    }

    public static void failingResourceDoesNotSkipRemainingResources() throws Exception {
        var c = create(1, 1, 1, 1); var order = new CopyOnWriteArrayList<Integer>();
        get(c.registerResource(() -> order.add(1)));
        get(c.registerResource(() -> { order.add(2); throw new IllegalStateException("fallo previsto"); }));
        get(c.registerResource(() -> order.add(3)));
        expectFailure(c.closeAsync());
        check(order.equals(List.of(3, 2, 1)), "Intenta recursos restantes");
        check(c.state() == SimulationState.CLOSING && c.failure().isPresent(), "Fallo visible sin falso CLOSED");
        check(!c.awaitTermination(Duration.ofMillis(10)), "No afirma éxito ante recurso fallido");
    }

    public static void severalResourceFailuresArePreserved() throws Exception {
        var c = create(1, 1, 1, 1);
        get(c.registerResource(() -> { throw new IllegalArgumentException("primero"); }));
        get(c.registerResource(() -> { throw new IllegalStateException("segundo"); }));
        Throwable error = expectFailure(c.closeAsync()); check(error.getSuppressed().length == 2, "Ambas causas conservadas");
    }

    public static void timeoutDoesNotCancelCleanup() throws Exception {
        var c = create(1, 1, 1, 1); var resource = new BlockingResource(); get(c.registerResource(resource)); var future = c.closeAsync();
        try {
            resource.entered(); check(!c.awaitTermination(Duration.ofMillis(30)), "Plazo corto agotado");
            check(!future.isDone() && c.state() == SimulationState.CLOSING, "Timeout no cancela ni publica CLOSED");
        } finally { resource.release(); get(future); ended(c); }
    }

    public static void positiveWaitIsRejectedOnEdt() throws Exception {
        var c = create(1, 1, 1, 1);
        try {
            SwingUtilities.invokeAndWait(() -> {
                try { c.awaitTermination(Duration.ofMillis(1)); throw new AssertionError("Espera en EDT"); }
                catch (IllegalStateException expected) { }
                catch (InterruptedException error) { throw new AssertionError(error); }
                try { check(!c.awaitTermination(Duration.ZERO), "Sondeo permitido"); }
                catch (InterruptedException error) { throw new AssertionError(error); }
            });
        } finally { get(c.closeAsync()); ended(c); }
    }

    public static void resourceCloseRunsOutsideEdt() throws Exception {
        var c = create(1, 1, 1, 1); var thread = new AtomicReference<Thread>(); get(c.registerResource(() -> thread.set(Thread.currentThread())));
        var future = new AtomicReference<CompletableFuture<SimulationController.Result>>();
        SwingUtilities.invokeAndWait(() -> future.set(c.closeAsync())); get(future.get()); ended(c);
        check(thread.get() != null && thread.get().getName().equals("simulation-shutdown"), "Liberación en cerrador independiente");
    }

    public static void cleanupSelfWaitIsRejected() throws Exception {
        var c = create(1, 1, 1, 1); var detected = new AtomicBoolean();
        get(c.registerResource(() -> {
            try { c.awaitTermination(Duration.ofMillis(1)); throw new AssertionError("Autoespera"); }
            catch (IllegalStateException expected) { detected.set(true); }
        }));
        get(c.closeAsync()); ended(c); check(detected.get(), "No puede esperar su propio cierre");
    }

    public static void normalActionsAreRejectedAsSoonAsCloseIsRequested() throws Exception {
        var c = create(1, 5, 20, 10); var resource = new BlockingResource(); get(c.registerResource(resource)); get(c.start());
        var closing = c.closeAsync();
        try {
            check(get(c.start()).outcome() == REJECTED && get(c.stop()).outcome() == REJECTED, "Controles rechazados");
            check(get(c.reset()).outcome() == REJECTED && get(c.submit(cabin(2))).outcome() == REJECTED, "Sin trabajo nuevo");
            check(get(c.registerResource(() -> { throw new AssertionError("No debía adoptar"); })).outcome() == REJECTED, "Sin recurso nuevo");
        } finally { resource.release(); get(closing); ended(c); }
    }

    public static void invalidResourceAndTimeoutArgumentsAreRejected() throws Exception {
        var c = create(1, 1, 1, 1);
        try {
            try { c.registerResource(null); throw new AssertionError("null"); } catch (NullPointerException expected) { }
            try { c.awaitTermination(Duration.ofNanos(-1)); throw new AssertionError("negativo"); } catch (IllegalArgumentException expected) { }
            try { c.awaitTermination(null); throw new AssertionError("timeout null"); } catch (NullPointerException expected) { }
        } finally { get(c.closeAsync()); ended(c); }
    }

    public static void visualCallbacksRunOnlyOnEdt() throws Exception {
        var stage = new CompletableFuture<Void>(); var requests = new AtomicInteger(); var disposed = new AtomicInteger(); var closed = new CountDownLatch(1);
        var handler = new WindowCloseHandler(() -> { requests.incrementAndGet(); return stage; },
                () -> check(SwingUtilities.isEventDispatchThread(), "Inicio en EDT"),
                () -> { check(SwingUtilities.isEventDispatchThread(), "Fin en EDT"); disposed.incrementAndGet(); closed.countDown(); },
                error -> { throw new AssertionError(error); });
        SwingUtilities.invokeAndWait(() -> { handler.requestClose(); handler.requestClose(); check(handler.isClosing() && !handler.isClosed(), "Cierre en curso"); });
        check(disposed.get() == 0 && requests.get() == 1, "Botón/X repetidos comparten solicitud");
        stage.complete(null); check(closed.await(10, TimeUnit.SECONDS), "Confirmación visual");
        SwingUtilities.invokeAndWait(() -> { check(handler.isClosed() && !handler.isClosing(), "Estado visual final"); handler.requestClose(); });
        check(disposed.get() == 1 && requests.get() == 1, "Una disposición");
    }

    public static void visualCloseWaitsForActualResourceRelease() throws Exception {
        var c = create(2, 5, 20, 10); var resource = new BlockingResource(); get(c.registerResource(resource)); get(c.start());
        var disposed = new CountDownLatch(1); var handler = new WindowCloseHandler(c::closeAsync, () -> { }, disposed::countDown,
                error -> { throw new AssertionError(error); });
        try {
            SwingUtilities.invokeAndWait(handler::requestClose); resource.entered();
            var pulse = new CountDownLatch(1); SwingUtilities.invokeLater(pulse::countDown);
            check(pulse.await(2, TimeUnit.SECONDS) && disposed.getCount() == 1, "EDT responde sin disponer antes de tiempo");
            resource.release(); check(disposed.await(10, TimeUnit.SECONDS), "Dispone tras liberación"); ended(c);
        } finally { resource.release(); c.close(); c.awaitTermination(LIMIT); }
    }

    public static void visualFailureDoesNotDisposeAndCanBeReportedAgain() throws Exception {
        var requests = new AtomicInteger(); var disposed = new CountDownLatch(1); var failed = new CountDownLatch(1);
        var handler = new WindowCloseHandler(() -> requests.incrementAndGet() == 1
                ? CompletableFuture.failedFuture(new IllegalStateException("fallo previsto")) : CompletableFuture.completedFuture(null),
                () -> { }, disposed::countDown, error -> { check(SwingUtilities.isEventDispatchThread(), "Error en EDT"); failed.countDown(); });
        SwingUtilities.invokeAndWait(handler::requestClose); check(failed.await(10, TimeUnit.SECONDS) && disposed.getCount() == 1, "No dispone ante fallo");
        SwingUtilities.invokeAndWait(() -> { check(!handler.isClosed() && !handler.isClosing(), "Error reportado"); handler.requestClose(); });
        check(disposed.await(10, TimeUnit.SECONDS), "Otro proveedor puede completar un nuevo intento");
    }

    public static void visualHandlerRequiresEdtAndValidCallbacks() {
        var handler = new WindowCloseHandler(() -> CompletableFuture.completedFuture(null), () -> { }, () -> { }, error -> { });
        try { handler.requestClose(); throw new AssertionError("Fuera de EDT"); } catch (IllegalStateException expected) { }
        try { handler.isClosed(); throw new AssertionError("Lectura fuera de EDT"); } catch (IllegalStateException expected) { }
        try { new WindowCloseHandler(null, () -> { }, () -> { }, error -> { }); throw new AssertionError("null"); }
        catch (NullPointerException expected) { }
    }

    public static void ownedHandlersLeaveForeignHandlersUntouched() {
        Logger logger = Logger.getAnonymousLogger(); logger.setUseParentHandlers(false);
        var ownA = new CountingHandler(false); var ownB = new CountingHandler(false); var foreign = new CountingHandler(false);
        logger.addHandler(ownA); logger.addHandler(foreign); logger.addHandler(ownB);
        var owner = new OwnedLogHandlers(logger, List.of(ownA, ownB)); owner.close(); owner.close();
        check(ownA.closed.get() == 1 && ownB.closed.get() == 1 && ownA.flushed.get() == 1, "Handlers propios cerrados una vez");
        check(foreign.closed.get() == 0 && Arrays.asList(logger.getHandlers()).equals(List.of(foreign)), "Handler ajeno intacto");
        logger.removeHandler(foreign); foreign.close();
    }

    public static void failingHandlerStillReleasesOtherHandlers() {
        Logger logger = Logger.getAnonymousLogger(); var good = new CountingHandler(false); var bad = new CountingHandler(true);
        logger.addHandler(good); logger.addHandler(bad); var owner = new OwnedLogHandlers(logger, List.of(good, bad));
        try { owner.close(); throw new AssertionError("Debía informar fallos"); }
        catch (IllegalStateException error) { check(error.getSuppressed().length == 2, "Conserva fallo de flush y close"); }
        check(good.closed.get() == 1 && bad.closed.get() == 1 && logger.getHandlers().length == 0, "Intenta todos y desconecta");
        try { owner.close(); throw new AssertionError("Fallo conservado"); } catch (IllegalStateException expected) { }
        check(good.closed.get() == 1 && bad.closed.get() == 1, "No repite cierre fallido");
    }

    public static void realFileHandlerIsReleasedAndReopened() throws Exception {
        Path directory = Files.createTempDirectory("elevator-t39-"); Path log = directory.resolve("session.log");
        Logger logger = Logger.getAnonymousLogger(); logger.setUseParentHandlers(false);
        FileHandler file = new FileHandler(log.toString(), false); logger.addHandler(file);
        var c = create(1, 1, 1, 1);
        try {
            get(c.registerResource(new OwnedLogHandlers(logger, List.of(file)))); logger.info("T39_PRUEBA_ARCHIVO");
            get(c.closeAsync()); ended(c);
            check(logger.getHandlers().length == 0 && Files.exists(log), "Archivo creado y handler retirado");
            check(!Files.exists(directory.resolve("session.log.lck")), "Bloqueo FileHandler liberado");
            Path moved = directory.resolve("renamed.log"); Files.move(log, moved); Files.move(moved, log);
            FileHandler reopened = new FileHandler(log.toString(), true);
            reopened.close();
            check(!Files.exists(directory.resolve("session.log.1")), "Reabre mismo archivo sin generar otro por bloqueo");
        } finally {
            file.close(); logger.removeHandler(file); c.close(); c.awaitTermination(LIMIT);
            try (var children = Files.list(directory)) { for (var child : children.toList()) { Files.deleteIfExists(child); } }
            Files.deleteIfExists(directory);
        }
    }

    public static void handlerOwnershipArgumentsAreValidated() {
        Logger logger = Logger.getAnonymousLogger(); var handler = new CountingHandler(false);
        try { new OwnedLogHandlers(null, List.of()); throw new AssertionError("logger null"); } catch (NullPointerException expected) { }
        try { new OwnedLogHandlers(logger, null); throw new AssertionError("lista null"); } catch (NullPointerException expected) { }
        try { new OwnedLogHandlers(logger, List.of(handler, handler)); throw new AssertionError("identidad repetida"); } catch (IllegalArgumentException expected) { }
        check(handler.closed.get() == 0, "Validación sin adoptar ni cerrar");
    }

    public static void successfulTerminationLeavesNoOwnedThreadAlive() throws Exception {
        var c = create(2, 5, 20, 10); var closer = new AtomicReference<Thread>();
        get(c.registerResource(() -> closer.set(Thread.currentThread()))); get(c.start());
        var actors = Thread.getAllStackTraces().keySet().stream().filter(t -> t.getName().equals("simulation-controller")
                || t.getName().startsWith("elevator-worker-") || t.getName().startsWith("manager-worker-")).toList();
        get(c.closeAsync()); ended(c);
        check(actors.stream().noneMatch(Thread::isAlive) && !closer.get().isAlive(), "Coordinador, actores y cerrador terminaron realmente");
    }

    private static final class CountingHandler extends Handler {
        final AtomicInteger closed = new AtomicInteger(); final AtomicInteger flushed = new AtomicInteger(); final boolean failing;
        CountingHandler(boolean failing) { this.failing = failing; }
        @Override public void publish(LogRecord record) { }
        @Override public void flush() { flushed.incrementAndGet(); if (failing) { throw new IllegalStateException("flush previsto"); } }
        @Override public void close() { closed.incrementAndGet(); if (failing) { throw new IllegalStateException("close previsto"); } }
    }
    private static final class BlockingResource implements AutoCloseable {
        private final CountDownLatch entered = new CountDownLatch(1); private final CountDownLatch release = new CountDownLatch(1);
        @Override public void close() throws InterruptedException { entered.countDown(); check(release.await(10, TimeUnit.SECONDS), "Recurso de prueba liberado"); }
        void entered() throws InterruptedException { check(entered.await(10, TimeUnit.SECONDS), "Cerrador entró al recurso"); }
        void release() { release.countDown(); }
    }
    private static SimulationController create(int n, int floors, long move, long dwell) { return new SimulationController(new SimulationConfig(n, floors, move, dwell), 0); }
    private static CabinRequest cabin(int floor) { return new CabinRequest(UUID.randomUUID(), 0, floor, Instant.now(), 1); }
    private static SimulationController.Result get(CompletableFuture<SimulationController.Result> future) throws Exception { return future.get(10, TimeUnit.SECONDS); }
    private static Throwable expectFailure(CompletableFuture<?> future) throws Exception {
        try { future.get(10, TimeUnit.SECONDS); throw new AssertionError("Debía fallar"); }
        catch (ExecutionException error) { return error.getCause(); }
    }
    private static void until(SimulationController c, ExecutionEvent.Type type) throws Exception {
        long began = System.nanoTime();
        while (true) {
            long left = LIMIT.toNanos() - (System.nanoTime() - began); check(left > 0, "Plazo de evento");
            var event = c.awaitEvent(Duration.ofNanos(left)).orElseThrow(); check(event.type() != WORKER_FAILED, "Cabina sana");
            if (event.type() == type) { return; }
        }
    }
    private static void ended(SimulationController c) throws Exception {
        check(c.awaitTermination(LIMIT), "Terminación real confirmada");
        check(c.state() == SimulationState.CLOSED && c.managerLifecycle() == ManagerWorker.Lifecycle.TERMINATED, "CLOSED con manejador terminado");
        check(c.elevatorLifecycles().stream().allMatch(l -> l == ElevatorWorker.Lifecycle.TERMINATED), "Cabinas terminadas");
        check(c.failure().isEmpty(), "Sin incidencia de cierre: " + c.failure());
    }
    private static void check(boolean value, String detail) { if (!value) { throw new AssertionError(detail); } }

    public static void main(String[] args) throws Exception {
        newSessionClosesAutomatically(); runningIdleSessionClosesAllWorkers(); movementDoesNotDelayCloseByItsDuration();
        dwellDoesNotDelayCloseByItsDuration(); stoppedSessionWakesAndCloses(); pendingStopIsResolvedByClose();
        pendingResetIsResolvedByClose(); resetReadySessionCloses(); concurrentCloseRunsResourcesOnce();
        cancelledVisibleFutureDoesNotCancelShutdown(); resourcesCloseInReverseRegistrationOrder(); duplicateResourceIsNotClosedTwice();
        rejectedResourceRemainsOwnedByCaller(); resourcesAreReleasedAfterActorsTerminate(); blockedResourceDoesNotPublishClosed();
        failingResourceDoesNotSkipRemainingResources(); severalResourceFailuresArePreserved(); timeoutDoesNotCancelCleanup();
        positiveWaitIsRejectedOnEdt(); resourceCloseRunsOutsideEdt(); cleanupSelfWaitIsRejected();
        normalActionsAreRejectedAsSoonAsCloseIsRequested(); invalidResourceAndTimeoutArgumentsAreRejected(); visualCallbacksRunOnlyOnEdt();
        visualCloseWaitsForActualResourceRelease(); visualFailureDoesNotDisposeAndCanBeReportedAgain(); visualHandlerRequiresEdtAndValidCallbacks();
        ownedHandlersLeaveForeignHandlersUntouched(); failingHandlerStillReleasesOtherHandlers(); realFileHandlerIsReleasedAndReopened();
        handlerOwnershipArgumentsAreValidated(); successfulTerminationLeavesNoOwnedThreadAlive();
        System.out.println("T39: 32 escenarios satisfactorios.");
    }
}
```

## src/test/java/com/elevatormanager/simulation/OrderlyShutdownTest.java

```java
package com.elevatormanager.simulation;

import org.junit.jupiter.api.Test;

class OrderlyShutdownTest {
    @Test void newSessionClosesAutomatically() throws Exception {
        OrderlyShutdownScenarios.newSessionClosesAutomatically();
    }
    @Test void runningIdleSessionClosesAllWorkers() throws Exception {
        OrderlyShutdownScenarios.runningIdleSessionClosesAllWorkers();
    }
    @Test void movementDoesNotDelayCloseByItsDuration() throws Exception {
        OrderlyShutdownScenarios.movementDoesNotDelayCloseByItsDuration();
    }
    @Test void dwellDoesNotDelayCloseByItsDuration() throws Exception {
        OrderlyShutdownScenarios.dwellDoesNotDelayCloseByItsDuration();
    }
    @Test void stoppedSessionWakesAndCloses() throws Exception {
        OrderlyShutdownScenarios.stoppedSessionWakesAndCloses();
    }
    @Test void pendingStopIsResolvedByClose() throws Exception {
        OrderlyShutdownScenarios.pendingStopIsResolvedByClose();
    }
    @Test void pendingResetIsResolvedByClose() throws Exception {
        OrderlyShutdownScenarios.pendingResetIsResolvedByClose();
    }
    @Test void resetReadySessionCloses() throws Exception {
        OrderlyShutdownScenarios.resetReadySessionCloses();
    }
    @Test void concurrentCloseRunsResourcesOnce() throws Exception {
        OrderlyShutdownScenarios.concurrentCloseRunsResourcesOnce();
    }
    @Test void cancelledVisibleFutureDoesNotCancelShutdown() throws Exception {
        OrderlyShutdownScenarios.cancelledVisibleFutureDoesNotCancelShutdown();
    }
    @Test void resourcesCloseInReverseRegistrationOrder() throws Exception {
        OrderlyShutdownScenarios.resourcesCloseInReverseRegistrationOrder();
    }
    @Test void duplicateResourceIsNotClosedTwice() throws Exception {
        OrderlyShutdownScenarios.duplicateResourceIsNotClosedTwice();
    }
    @Test void rejectedResourceRemainsOwnedByCaller() throws Exception {
        OrderlyShutdownScenarios.rejectedResourceRemainsOwnedByCaller();
    }
    @Test void resourcesAreReleasedAfterActorsTerminate() throws Exception {
        OrderlyShutdownScenarios.resourcesAreReleasedAfterActorsTerminate();
    }
    @Test void blockedResourceDoesNotPublishClosed() throws Exception {
        OrderlyShutdownScenarios.blockedResourceDoesNotPublishClosed();
    }
    @Test void failingResourceDoesNotSkipRemainingResources() throws Exception {
        OrderlyShutdownScenarios.failingResourceDoesNotSkipRemainingResources();
    }
    @Test void severalResourceFailuresArePreserved() throws Exception {
        OrderlyShutdownScenarios.severalResourceFailuresArePreserved();
    }
    @Test void timeoutDoesNotCancelCleanup() throws Exception {
        OrderlyShutdownScenarios.timeoutDoesNotCancelCleanup();
    }
    @Test void positiveWaitIsRejectedOnEdt() throws Exception {
        OrderlyShutdownScenarios.positiveWaitIsRejectedOnEdt();
    }
    @Test void resourceCloseRunsOutsideEdt() throws Exception {
        OrderlyShutdownScenarios.resourceCloseRunsOutsideEdt();
    }
    @Test void cleanupSelfWaitIsRejected() throws Exception {
        OrderlyShutdownScenarios.cleanupSelfWaitIsRejected();
    }
    @Test void normalActionsAreRejectedAsSoonAsCloseIsRequested() throws Exception {
        OrderlyShutdownScenarios.normalActionsAreRejectedAsSoonAsCloseIsRequested();
    }
    @Test void invalidResourceAndTimeoutArgumentsAreRejected() throws Exception {
        OrderlyShutdownScenarios.invalidResourceAndTimeoutArgumentsAreRejected();
    }
    @Test void visualCallbacksRunOnlyOnEdt() throws Exception {
        OrderlyShutdownScenarios.visualCallbacksRunOnlyOnEdt();
    }
    @Test void visualCloseWaitsForActualResourceRelease() throws Exception {
        OrderlyShutdownScenarios.visualCloseWaitsForActualResourceRelease();
    }
    @Test void visualFailureDoesNotDisposeAndCanBeReportedAgain() throws Exception {
        OrderlyShutdownScenarios.visualFailureDoesNotDisposeAndCanBeReportedAgain();
    }
    @Test void visualHandlerRequiresEdtAndValidCallbacks() throws Exception {
        OrderlyShutdownScenarios.visualHandlerRequiresEdtAndValidCallbacks();
    }
    @Test void ownedHandlersLeaveForeignHandlersUntouched() throws Exception {
        OrderlyShutdownScenarios.ownedHandlersLeaveForeignHandlersUntouched();
    }
    @Test void failingHandlerStillReleasesOtherHandlers() throws Exception {
        OrderlyShutdownScenarios.failingHandlerStillReleasesOtherHandlers();
    }
    @Test void realFileHandlerIsReleasedAndReopened() throws Exception {
        OrderlyShutdownScenarios.realFileHandlerIsReleasedAndReopened();
    }
    @Test void handlerOwnershipArgumentsAreValidated() throws Exception {
        OrderlyShutdownScenarios.handlerOwnershipArgumentsAreValidated();
    }
    @Test void successfulTerminationLeavesNoOwnedThreadAlive() throws Exception {
        OrderlyShutdownScenarios.successfulTerminationLeavesNoOwnedThreadAlive();
    }
}
```
