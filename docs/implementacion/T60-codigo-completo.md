# T60 — Código Java completo

Diez archivos íntegros de producción y pruebas modificados/añadidos por T60, sin fragmentos, parches ni elipsis. El ZIP contiene también todas las fuentes anteriores.

## src/main/java/com/elevatormanager/simulation/time/PausableClock.java

```java
package com.elevatormanager.simulation.time;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;

/**
 * T54: reloj de tiempo activo y esperas interrumpibles sin sondeo.
 * El origen lógico es cero y la fuente de producción es System.nanoTime.
 * No coordina actores ni autoriza transiciones: esa barrera corresponde a T55.
 * No mezclar sus marcas lógicas con marcas directas de System.nanoTime.
 * Cada intervalo medido debe ser menor que Long.MAX_VALUE nanosegundos.
 */
public final class PausableClock implements AutoCloseable {
    /** T60: referencia inmutable publicada; at usa el mismo origen que sourceNanos.
     * Leer view no adquiere locks. Una vista antigua no se modifica al reanudar.
     */
    public record View(long activeNanos, long sourceNanos, boolean paused) {
        public long at(long currentSourceNanos) {
            return activeNanos + (paused ? 0L : Math.max(0L, currentSourceNanos - sourceNanos));
        }
    }
    private volatile View publishedView;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition changed = lock.newCondition();
    private final LongSupplier source;
    private long activeNanos;
    private long sampledAt;
    private boolean paused;
    private boolean closed;

    public PausableClock() { this(System::nanoTime); }

    /** Fuente monotónica inyectable para pruebas del mismo paquete. */
    PausableClock(LongSupplier source) {
        this.source = Objects.requireNonNull(source, "source");
        sampledAt = source.getAsLong();
        publishedView = new View(0, sampledAt, false);
    }

    public View view() { return publishedView; }

    public long nowNanos() {
        lock.lock();
        try {
            requireOpen();
            return currentTime();
        } finally { lock.unlock(); }
    }

    /** Idempotente. El instante de adquisición del lock delimita la pausa. */
    public boolean pause() {
        lock.lock();
        try {
            requireOpen();
            if (paused) { return false; }
            activeNanos = currentTime();
            paused = true;
            publishedView = new View(activeNanos, sampledAt, true);
            changed.signalAll();
            return true;
        } finally { lock.unlock(); }
    }

    /** Conserva el tiempo activo acumulado y descarta el intervalo pausado. */
    public boolean resume() {
        lock.lock();
        try {
            requireOpen();
            if (!paused) { return false; }
            sampledAt = source.getAsLong();
            paused = false;
            publishedView = new View(activeNanos, sampledAt, false);
            changed.signalAll();
            return true;
        } finally { lock.unlock(); }
    }

    public boolean isPaused() {
        lock.lock();
        try { return paused; }
        finally { lock.unlock(); }
    }

    /** Espera una duración de tiempo activo desde esta llamada. */
    public void await(Duration duration) throws InterruptedException {
        long budget = budgetNanos(duration);
        lock.lockInterruptibly();
        try {
            requireOpen();
            awaitBudget(currentTime(), budget);
        } finally { lock.unlock(); }
    }

    /**
     * Espera el resto de un plazo iniciado con nowNanos de ESTE reloj.
     * Admite retomar un plazo tras una interrupción sin reiniciarlo.
     * Un plazo ya cumplido (incluido cero) retorna incluso durante pausa.
     * Retornar no garantiza que siga reanudado: T55 debe coordinar al actor.
     */
    public void awaitElapsed(long startedNanos, Duration duration) throws InterruptedException {
        long budget = budgetNanos(duration);
        lock.lockInterruptibly();
        try {
            requireOpen();
            awaitBudget(startedNanos, budget);
        } finally { lock.unlock(); }
    }

    private void awaitBudget(long startedNanos, long budget) throws InterruptedException {
        while (true) {
            requireOpen();
            long elapsed = currentTime() - startedNanos;
            if (elapsed < 0) {
                throw new IllegalArgumentException("Marca futura o intervalo fuera de rango.");
            }
            if (elapsed >= budget) { return; }
            if (paused) { changed.await(); }
            else { changed.awaitNanos(budget - elapsed); }
        }
    }

    private long currentTime() {
        return paused ? activeNanos : activeNanos + (source.getAsLong() - sampledAt);
    }

    private static long budgetNanos(Duration duration) {
        Objects.requireNonNull(duration, "duration");
        if (duration.isNegative()) { throw new IllegalArgumentException("Duración negativa."); }
        return duration.toNanos(); // ArithmeticException si no cabe; nunca trunca ni satura.
    }

    private void requireOpen() {
        if (closed) { throw new IllegalStateException("Reloj cerrado."); }
    }

    /** Despierta todas las esperas con IllegalStateException; no requiere resume. */
    @Override
    public void close() {
        lock.lock();
        try {
            if (!closed) publishedView = new View(currentTime(), sampledAt, true);
            closed = true;
            changed.signalAll();
        } finally { lock.unlock(); }
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

    /** T60: publicación temporal O(1), sin adquirir los locks de barrera/reloj. */
    public PausableClock.View timeView() { return clock.view(); }

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

    /** T60: referencia de tiempo activo para pintura; lectura volatile sin locks. */
    public com.elevatormanager.simulation.time.PausableClock.View timeView() {
        return pauseCoordinator.timeView();
    }
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
        return position(snapshot, activeNanos, false);
    }

    /** T60: una vista vigente reanudada permite continuar un HELD de PAUSE atrasado. */
    static double position(ElevatorSnapshot snapshot, long activeNanos, boolean resumedClock) {
        Objects.requireNonNull(snapshot, "snapshot");
        boolean moving = snapshot.phase() == ElevatorPhase.MOVING;
        boolean heldMoving = snapshot.phase() == ElevatorPhase.HELD
                && snapshot.suspendedPhase().orElseThrow() == ElevatorPhase.MOVING;
        if (!moving && !heldMoving) return snapshot.currentFloor();
        long duration = snapshot.phaseDurationNanos();
        long spent = duration - snapshot.remainingNanos();
        // Restar las marcas admite wrap monotónico dentro de un intervalo < Long.MAX_VALUE.
        boolean advance = moving || (heldMoving && resumedClock
                && snapshot.holdReason().orElseThrow() == ElevatorSnapshot.HoldReason.PAUSE);
        long elapsed = advance ? Math.max(0L, activeNanos - snapshot.referenceNanos()) : 0L;
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
import com.elevatormanager.simulation.time.PausableClock;
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
import java.util.function.Supplier;
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
    private Supplier<PausableClock.View> timeView;
    private Long frozenActive;
    private boolean frozenResumed;
    private boolean animationClosed;

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
        snapshots = List.of(); rendered = null; timeView = null; frozenActive = null; frozenResumed = false; animationClosed = false;
        revalidate(); repaint();
    }

    /** El proveedor de producción solo lee una referencia volatile, sin locks. */
    public void useTimeView(Supplier<PausableClock.View> source) {
        requireEdt(); timeView = Objects.requireNonNull(source, "source");
    }

    /** Cierre: conserva la posición mientras se liberan actores y capturas. */
    public void freezeAnimation() {
        requireEdt();
        if (animationClosed) return;
        animationClosed = true;
        long now = wallClock.getAsLong();
        if (timeView != null) {
            var view = timeView.get(); frozenActive = view.at(now); frozenResumed = !view.paused();
        } else if (rendered != null) frozenActive = rendered.timing().map(t -> t.at(now)).orElse(null);
    }

    /** Solo acepta la sesión/época vigente. Reutiliza el conjunto inmutable de T52. */
    public void render(UiSnapshotPump.Frame frame, long currentEpoch) {
        requireEdt();
        if (animationClosed || config == null || !frame.sessionId().equals(sessionId) || frame.epoch() != currentEpoch
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
            var currentTime = timeView == null ? null : timeView.get();
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
                Rectangle cabin = layout.cabinBounds(id, positionAt(id, wallNow, currentTime));
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
        return positionAt(id, wallClock.getAsLong(), timeView == null ? null : timeView.get());
    }

    private double positionAt(int id, long wallNow, PausableClock.View currentTime) {
        int logicalFloor = displayedFloor(id);
        if (snapshots.isEmpty()) return logicalFloor;
        var snapshot = snapshots.get(id - 1);
        long active = frozenActive != null ? frozenActive : currentTime != null ? currentTime.at(wallNow)
                : rendered.timing().map(sample -> sample.at(wallNow)).orElse(snapshot.referenceNanos());
        return CabinMotion.position(snapshot, active,
                frozenActive != null ? frozenResumed : currentTime != null && !currentTime.paused());
    }

    /** Un tick solo invalida la región visible; no captura ni recorre todo N/P. */
    void refreshAnimation() {
        requireEdt();
        if (!animationClosed && config != null && isShowing()) repaint(getVisibleRect());
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
    private Object generation = new Object();
    private boolean freshPending;
    private boolean closed;
    private CompletableFuture<Void> termination;

    public UiSnapshotPump(SimulationController controller) { this(() -> capture(controller), System::nanoTime); }
    UiSnapshotPump(Supplier<Frame> capture, LongSupplier clock) {
        this.capture = Objects.requireNonNull(capture, "capture");
        gate = new RefreshGate(INTERVAL_NANOS, clock);
    }

    public synchronized void request() {
        if (closed || inFlight || !gate.due()) return;
        launch();
    }

    /** T60: invalida incluso una captura en vuelo, sin esperar ni interrumpir el motor. */
    public synchronized void invalidate() {
        generation = new Object(); latest.set(null); failure.set(null); freshPending = false;
    }

    /** Solo controles: pide una captura fresca sin esperar al gate de 100 ms. */
    public synchronized void requestFresh() {
        if (closed) return;
        invalidate();
        if (inFlight) freshPending = true;
        else launch();
    }

    private void launch() {
        inFlight = true;
        Object ticket = generation;
        executor.execute(() -> {
            try {
                if (SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Captura en EDT.");
                Frame frame = capture.get();
                synchronized (this) {
                    if (!closed && ticket == generation && frame != null) { latest.set(frame); failure.set(null); }
                }
            } catch (RuntimeException | Error error) {
                synchronized (this) { if (!closed && ticket == generation) failure.set(error); }
            } finally {
                synchronized (this) {
                    inFlight = false;
                    if (!closed && freshPending) { freshPending = false; launch(); }
                }
            }
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
                && snapshots.stream().allMatch(snapshot -> snapshot.epoch() == epoch)
                ? new Frame(controller.sessionId(), epoch, snapshots, assigned, Optional.of(timing)) : null;
    }

    /** No espera al trabajador en el llamador; una captura tardía no vuelve a publicarse. */
    public synchronized CompletableFuture<Void> closeAsync() {
        if (termination != null) return termination.copy();
        closed = true; freshPending = false; latest.set(null); executor.shutdownNow();
        termination = CompletableFuture.runAsync(() -> {
            try { while (!executor.awaitTermination(1, TimeUnit.SECONDS)) { /* Fuera del EDT. */ } }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new CompletionException(error); }
        });
        return termination.copy();
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
                buildingPanel.useTimeView(session::timeView);
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
        if (snapshotPump != null) snapshotPump.invalidate();
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
            if (snapshotPump != null) snapshotPump.requestFresh();
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
        buildingPanel.freezeAnimation();
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
        // Una captura fresca de control se presenta en el próximo tick, sin esperar al gate del visor.
        if (snapshotPump != null && controller != null && !closing && !terminal) {
            snapshotPump.latest().ifPresent(data -> buildingPanel.render(data, controller.epoch()));
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

## src/test/java/com/elevatormanager/simulation/time/ClockViewScenarios.java

```java
package com.elevatormanager.simulation.time;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** T60: anclaje exacto y lectura sin adquirir el lock del reloj. */
public final class ClockViewScenarios {
    private ClockViewScenarios() { }
    public static void publishedViewMatchesActiveTime() {
        var source = new AtomicLong(1000);
        try (var c = new PausableClock(source::get)) {
            source.set(1350);
            check(c.view().at(source.get()) == c.nowNanos() && c.nowNanos() == 350, "Mismo dominio activo");
            check(c.view() == c.view(), "Lectura de referencia publicada sin crear una captura");
        }
    }
    public static void repeatedPauseResumeExcludesWallIntervals() {
        var source = new AtomicLong(1000);
        try (var c = new PausableClock(source::get)) {
            source.addAndGet(200); c.pause(); var frozen = c.view();
            source.addAndGet(5000); check(c.view().at(source.get()) == 200, "Tiempo pausado excluido");
            c.resume(); source.addAndGet(100); check(c.view().at(source.get()) == 300, "Solo resto activo");
            c.pause(); source.addAndGet(9000); c.resume(); source.addAndGet(50);
            check(c.view().at(source.get()) == 350 && frozen.at(source.get()) == 200, "Ciclos y vista antigua inmutable");
        }
    }
    public static void closingFreezesReadableView() {
        var source = new AtomicLong(1000); var c = new PausableClock(source::get);
        source.set(1200); c.close(); var finalView = c.view(); source.set(9000);
        check(finalView.paused() && finalView.at(source.get()) == 200, "Vista final congelada");
        c.close(); check(c.view() == finalView, "Cierre idempotente de la vista");
    }
    public static void readingViewDoesNotWaitForClockLock() throws Exception {
        var source = new AtomicLong(1000); var block = new AtomicBoolean();
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var c = new PausableClock(() -> {
            if (block.get()) {
                entered.countDown();
                try { if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("Liberación de fuente"); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
            }
            return source.get();
        });
        var threads = Executors.newFixedThreadPool(2);
        try {
            block.set(true); var locked = threads.submit(c::nowNanos);
            check(entered.await(3, TimeUnit.SECONDS), "Fuente mantiene el lock del reloj");
            var read = threads.submit(c::view);
            check(read.get(1, TimeUnit.SECONDS).at(1100) == 100, "Lectura independiente del lock");
            release.countDown(); locked.get(3, TimeUnit.SECONDS);
        } finally {
            release.countDown(); block.set(false); threads.shutdownNow();
            check(threads.awaitTermination(3, TimeUnit.SECONDS), "Cierre de comprobadores"); c.close();
        }
    }
    private static void check(boolean condition, String detail) { if (!condition) throw new AssertionError(detail); }
    public static void main(String[] args) throws Exception {
        publishedViewMatchesActiveTime(); repeatedPauseResumeExcludesWallIntervals(); closingFreezesReadableView();
        readingViewDoesNotWaitForClockLock(); System.out.println("T60: 4 escenarios de vista temporal satisfactorios.");
    }
}
```

## src/test/java/com/elevatormanager/ui/AnimationSynchronizationScenarios.java

```java
package com.elevatormanager.ui;

import com.elevatormanager.model.*;
import com.elevatormanager.model.request.CabinRequest;
import com.elevatormanager.simulation.SimulationController;
import com.elevatormanager.simulation.time.PausableClock;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import javax.swing.SwingUtilities;

/** T60: controles temporales con reloj virtual, capturas bloqueadas y actores reales. */
public final class AnimationSynchronizationScenarios {
    private static final long MS = 1_000_000L;
    private static final UUID SESSION = UUID.randomUUID();
    private static final SimulationConfig CONFIG = new SimulationConfig(2, 6, 1000, 100);
    private AnimationSynchronizationScenarios() { }

    public static void oldMovingFrameFreezesAtActualPauseInstant() throws Exception { edt(() -> {
        var r = new VirtualRig(); r.show(moving(1, Direction.UP, 0, 1000 * MS, 0, false));
        r.wall.set(300 * MS); r.view.set(new PausableClock.View(300 * MS, 300 * MS, true));
        near(r.panel.displayedPosition(1), 1.3);
        r.wall.addAndGet(60_000 * MS); near(r.panel.displayedPosition(1), 1.3);
    }); }
    public static void lateHeldFrameDoesNotMovePausedPosition() throws Exception { edt(() -> {
        var r = new VirtualRig(); r.show(moving(1, Direction.UP, 0, 1000 * MS, 0, false));
        r.view.set(new PausableClock.View(300 * MS, 300 * MS, true)); r.wall.set(9000 * MS);
        double before = r.panel.displayedPosition(1);
        r.show(moving(1, Direction.UP, 300 * MS, 700 * MS, 0, true));
        near(r.panel.displayedPosition(1), before);
    }); }
    public static void oldHeldFrameContinuesWithoutPausedWallTime() throws Exception { edt(() -> {
        var r = new VirtualRig(); r.show(moving(1, Direction.UP, 300 * MS, 700 * MS, 0, true));
        r.wall.set(9000 * MS); r.view.set(new PausableClock.View(300 * MS, 9000 * MS, false));
        near(r.panel.displayedPosition(1), 1.3);
        r.wall.addAndGet(200 * MS); near(r.panel.displayedPosition(1), 1.5);
    }); }
    public static void repeatedPauseCyclesPreservePositionAndBudget() throws Exception { edt(() -> {
        var r = new VirtualRig(); r.show(moving(1, Direction.UP, 0, 1000 * MS, 0, false));
        r.wall.set(200 * MS); r.view.set(new PausableClock.View(200 * MS, 200 * MS, true));
        r.wall.set(5200 * MS); near(r.panel.displayedPosition(1), 1.2);
        r.view.set(new PausableClock.View(200 * MS, 5200 * MS, false));
        r.wall.set(5400 * MS); near(r.panel.displayedPosition(1), 1.4);
        r.view.set(new PausableClock.View(400 * MS, 5400 * MS, true));
        r.wall.set(15400 * MS); near(r.panel.displayedPosition(1), 1.4);
        r.view.set(new PausableClock.View(400 * MS, 15400 * MS, false));
        r.wall.set(16000 * MS); near(r.panel.displayedPosition(1), 2);
    }); }
    public static void stopFinishesOnlyPublishedAdjacentLeg() throws Exception { edt(() -> {
        var r = new VirtualRig(); r.show(moving(2, Direction.UP, 0, 1000 * MS, 0, false));
        r.wall.set(600 * MS); near(r.panel.displayedPosition(1), 2.6);
        r.wall.set(1000 * MS); near(r.panel.displayedPosition(1), 3);
        r.show(idle(3, 0)); r.wall.set(9000 * MS); near(r.panel.displayedPosition(1), 3);
    }); }
    public static void stopFromPausedMovementReleasesHeldProjection() throws Exception { edt(() -> {
        var r = new VirtualRig(); r.show(moving(2, Direction.UP, 300 * MS, 700 * MS, 0, true));
        r.wall.set(9000 * MS); r.view.set(new PausableClock.View(300 * MS, 9000 * MS, false));
        r.wall.set(9400 * MS); near(r.panel.displayedPosition(1), 2.7);
        r.wall.set(9700 * MS); near(r.panel.displayedPosition(1), 3);
    }); }
    public static void startFromStoppedDoesNotResetPosition() throws Exception { edt(() -> {
        var r = new VirtualRig(); r.show(idle(3, 0)); r.wall.set(8000 * MS);
        near(r.panel.displayedPosition(1), 3);
        r.show(moving(3, Direction.UP, 8000 * MS, 1000 * MS, 0, false));
        r.wall.set(8250 * MS); near(r.panel.displayedPosition(1), 3.25);
    }); }
    public static void resetRetainsPhysicalLegAcrossEpochChange() throws Exception { edt(() -> {
        var r = new VirtualRig(); r.show(moving(3, Direction.UP, 0, 1000 * MS, 0, false));
        r.wall.set(400 * MS); double before = r.panel.displayedPosition(1);
        var stale = r.frame(moving(1, Direction.UP, 0, 1000 * MS, 0, false));
        r.panel.render(stale, 1); near(r.panel.displayedPosition(1), before);
        r.show(moving(3, Direction.UP, 0, 1000 * MS, 1, false));
        near(r.panel.displayedPosition(1), before); check(r.panel.displayedFloor(1) == 3, "Reset no coloca cabina en piso 1");
    }); }
    public static void resetReturnMovesDownThroughIntermediatePositions() throws Exception { edt(() -> {
        var r = new VirtualRig(); r.show(moving(4, Direction.DOWN, 1000 * MS, 1000 * MS, 1, false));
        r.wall.set(1250 * MS); near(r.panel.displayedPosition(1), 3.75);
        r.wall.set(2000 * MS); near(r.panel.displayedPosition(1), 3);
        r.show(moving(3, Direction.DOWN, 2000 * MS, 1000 * MS, 1, false));
        r.wall.set(2500 * MS); near(r.panel.displayedPosition(1), 2.5);
    }); }
    public static void resetFromPausedHeldFramePreservesRemainder() throws Exception { edt(() -> {
        var r = new VirtualRig(); r.show(moving(3, Direction.UP, 400 * MS, 600 * MS, 0, true));
        r.wall.set(9000 * MS); r.view.set(new PausableClock.View(400 * MS, 9000 * MS, false));
        r.wall.set(9300 * MS); near(r.panel.displayedPosition(1), 3.7);
        r.show(moving(3, Direction.UP, 700 * MS, 300 * MS, 1, false));
        near(r.panel.displayedPosition(1), 3.7);
    }); }
    public static void closeFreezesEvenWhenLastFrameWasHeld() throws Exception { edt(() -> {
        var r = new VirtualRig(); r.show(moving(2, Direction.UP, 300 * MS, 700 * MS, 0, true));
        r.view.set(new PausableClock.View(300 * MS, 9000 * MS, false)); r.wall.set(9200 * MS);
        near(r.panel.displayedPosition(1), 2.5); r.panel.freezeAnimation();
        r.wall.set(60000 * MS); near(r.panel.displayedPosition(1), 2.5);
        r.show(idle(6, 0)); near(r.panel.displayedPosition(1), 2.5);
        r.panel.freezeAnimation(); near(r.panel.displayedPosition(1), 2.5);
    }); }
    public static void invalidationDiscardsLateCapture() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var second = new CountDownLatch(1); var releaseSecond = new CountDownLatch(1);
        var calls = new AtomicInteger(); var clock = new AtomicLong();
        var pump = new UiSnapshotPump(() -> {
            if (calls.incrementAndGet() == 1) { entered.countDown(); hold(release); }
            else { second.countDown(); hold(releaseSecond); }
            return emptyFrame();
        }, clock::get);
        try {
            pump.request(); check(entered.await(3, TimeUnit.SECONDS), "Captura iniciada");
            pump.invalidate(); release.countDown(); clock.set(UiSnapshotPump.INTERVAL_NANOS);
            await(() -> { pump.request(); return second.getCount() == 0; });
            check(pump.latest().isEmpty(), "Captura invalidada no reaparece antes de la siguiente");
        } finally { release.countDown(); releaseSecond.countDown(); pump.closeAsync().get(3, TimeUnit.SECONDS); }
    }
    public static void freshCaptureWaitsForOneWorkerAndBypassesGate() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var calls = new AtomicInteger(); var active = new AtomicInteger(); var maximum = new AtomicInteger();
        var pump = new UiSnapshotPump(() -> {
            int count = calls.incrementAndGet(); maximum.accumulateAndGet(active.incrementAndGet(), Math::max);
            try { if (count == 1) { entered.countDown(); hold(release); } return emptyFrame(); }
            finally { active.decrementAndGet(); }
        }, () -> 0L);
        try {
            pump.request(); check(entered.await(3, TimeUnit.SECONDS), "Captura retenida");
            pump.requestFresh(); release.countDown(); await(() -> calls.get() == 2 && pump.latest().isPresent());
            check(maximum.get() == 1, "Un solo capturador incluso con solicitud urgente");
        } finally { release.countDown(); pump.closeAsync().get(3, TimeUnit.SECONDS); }
    }
    public static void invalidatedFailureCannotReplaceFreshStatus() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1); var calls = new AtomicInteger();
        var second = new CountDownLatch(1); var releaseSecond = new CountDownLatch(1);
        var pump = new UiSnapshotPump(() -> {
            if (calls.incrementAndGet() == 1) { entered.countDown(); hold(release); throw new IllegalStateException("captura antigua"); }
            second.countDown(); hold(releaseSecond); return emptyFrame();
        }, () -> 0L);
        try {
            pump.request(); check(entered.await(3, TimeUnit.SECONDS), "Primera captura"); pump.requestFresh();
            release.countDown(); check(second.await(3, TimeUnit.SECONDS), "Segunda captura retenida");
            check(pump.failure().isEmpty(), "Error antiguo descartado antes del éxito siguiente");
            releaseSecond.countDown(); await(() -> pump.latest().isPresent());
        } finally { release.countDown(); releaseSecond.countDown(); pump.closeAsync().get(3, TimeUnit.SECONDS); }
    }
    public static void closeCancelsPendingFreshCapture() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1); var calls = new AtomicInteger();
        var pump = new UiSnapshotPump(() -> { calls.incrementAndGet(); entered.countDown(); hold(release); return emptyFrame(); }, () -> 0L);
        try {
            pump.request(); check(entered.await(3, TimeUnit.SECONDS), "Captura retenida"); pump.requestFresh();
            var close = pump.closeAsync(); release.countDown(); close.get(3, TimeUnit.SECONDS);
            check(calls.get() == 1 && pump.latest().isEmpty(), "Cierre no ejecuta la captura pendiente");
        } finally { release.countDown(); pump.closeAsync().get(3, TimeUnit.SECONDS); }
    }
    public static void realPauseResumeKeepsExactPosition() throws Exception {
        try (var r = new RealRig(3000, 200)) {
            r.startAndGo(1, 6); await(() -> r.c.snapshot(1).phase() == ElevatorPhase.MOVING);
            Thread.sleep(100); var movingFrame = UiSnapshotPump.capture(r.c); r.c.pause().get(5, TimeUnit.SECONDS);
            r.render(movingFrame); double frozen = r.position(1); Thread.sleep(80); near(r.position(1), frozen);
            r.render(UiSnapshotPump.capture(r.c)); near(r.position(1), frozen);
            r.c.resume().get(5, TimeUnit.SECONDS); Thread.sleep(60);
            check(r.position(1) > frozen && r.position(1) < 2, "Reanuda un HELD antiguo usando solo tiempo activo");
        }
    }
    public static void realDwellAndIdleRemainFixedWhilePaused() throws Exception {
        try (var r = new RealRig(300, 1000)) {
            r.startAndGo(1, 1); await(() -> r.c.snapshot(1).phase() == ElevatorPhase.DWELLING);
            r.c.pause().get(5, TimeUnit.SECONDS); r.render(UiSnapshotPump.capture(r.c));
            Thread.sleep(80); near(r.position(1), 1); near(r.position(2), 1);
            check(r.c.snapshot(1).suspendedPhase().orElseThrow() == ElevatorPhase.DWELLING, "Parada real pausada");
        }
    }
    public static void realStopFromPauseCompletesAdjacentLeg() throws Exception {
        try (var r = new RealRig(350, 100)) {
            r.startAndGo(1, 6); await(() -> r.c.snapshot(1).phase() == ElevatorPhase.MOVING);
            r.c.pause().get(5, TimeUnit.SECONDS); r.render(UiSnapshotPump.capture(r.c));
            double frozen = r.position(1); var stopped = r.c.stop();
            await(() -> !r.c.timeView().paused()); Thread.sleep(50);
            check(r.position(1) > frozen && r.position(1) <= 2, "Stop libera el resto del tramo pausado");
            stopped.get(5, TimeUnit.SECONDS); r.render(UiSnapshotPump.capture(r.c)); near(r.position(1), 2);
            check(r.c.snapshot(1).cabinRequests().size() == 1, "Stop no sirve ni elimina el destino");
        }
    }
    public static void realResetFromPauseShowsPhysicalReturn() throws Exception {
        try (var r = new RealRig(250, 100)) {
            r.startAndGo(1, 6); await(() -> r.c.snapshot(1).currentFloor() >= 3
                    && r.c.snapshot(1).phase() == ElevatorPhase.MOVING);
            r.c.pause().get(5, TimeUnit.SECONDS); r.render(UiSnapshotPump.capture(r.c));
            double before = r.position(1); var reset = r.c.reset(); var sawDown = new AtomicBoolean();
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(6);
            while (!reset.isDone()) {
                var f = UiSnapshotPump.capture(r.c);
                if (f != null) {
                    r.render(f); var s = f.snapshots().get(0); double position = r.position(1);
                    if (s.mode() == ElevatorSnapshot.Mode.RESET_RETURN && s.direction() == Direction.DOWN
                            && s.phase() == ElevatorPhase.MOVING && position > s.nextFloor().orElseThrow()
                            && position < s.currentFloor()) sawDown.set(true);
                }
                check(System.nanoTime() < end, "Plazo de retorno físico"); Thread.sleep(10);
            }
            reset.get(3, TimeUnit.SECONDS); r.render(UiSnapshotPump.capture(r.c));
            check(before > 2 && sawDown.get(), "Retorno observado entre pisos, sin teleportar a 1");
            near(r.position(1), 1); check(r.c.epoch() == 1 && r.c.snapshot(1).direction() == Direction.UP, "Reset final 1/UP");
        }
    }
    public static void systemButtonsSynchronizeAndCloseAnimation() throws Exception {
        var panel = onEdt(() -> new SystemControlPanel(cfg -> new SimulationController(cfg, 0), Optional::empty));
        try {
            edt(() -> {
                var f = panel.configurationPanel();
                f.input(SimulationConfig.Field.ELEVATORS).setText("2"); f.input(SimulationConfig.Field.FLOORS).setText("4");
                f.input(SimulationConfig.Field.MOVEMENT).setText("300"); f.input(SimulationConfig.Field.DWELL).setText("300");
                f.validateButton().doClick(0);
            });
            await(() -> value(() -> panel.controller().isPresent())); var c = onEdt(() -> panel.controller().orElseThrow());
            click(panel, SystemControlPanel.Action.START); await(() -> c.state() == SimulationState.RUNNING);
            edt(() -> panel.cabinDashboard().cabin(1).destinationButton(4).doClick(0));
            await(() -> c.snapshot(1).phase() == ElevatorPhase.MOVING);
            click(panel, SystemControlPanel.Action.PAUSE); await(() -> c.state() == SimulationState.PAUSED);
            await(() -> value(() -> panel.snapshotPump().latest().isPresent()));
            double frozen = onEdt(() -> { panel.updateView(); return panel.buildingPanel().displayedPosition(1); });
            Thread.sleep(80); near(onEdt(() -> panel.buildingPanel().displayedPosition(1)), frozen);
            click(panel, SystemControlPanel.Action.RESUME); await(() -> c.state() == SimulationState.RUNNING);
            click(panel, SystemControlPanel.Action.STOP); await(() -> c.state() == SimulationState.STOPPED);
            click(panel, SystemControlPanel.Action.START); await(() -> c.state() == SimulationState.RUNNING);
            click(panel, SystemControlPanel.Action.RESET); await(() -> c.state() == SimulationState.RESET);
            await(() -> value(() -> { panel.updateView(); return panel.buildingPanel().displayedPosition(1) == 1; }));
            check(c.epoch() == 1, "Botón Reset renueva época sin reemplazar sesión");
        } finally {
            var closing = onEdt(panel::closeAsync); double frozen = onEdt(() -> panel.buildingPanel().displayedPosition(1));
            closing.get(5, TimeUnit.SECONDS); near(onEdt(() -> panel.buildingPanel().displayedPosition(1)), frozen);
            edt(panel::closed);
        }
    }

    private static final class VirtualRig {
        final AtomicLong wall = new AtomicLong();
        final AtomicReference<PausableClock.View> view = new AtomicReference<>(new PausableClock.View(0, 0, false));
        final BuildingPanel panel = new BuildingPanel(wall::get);
        VirtualRig() { panel.bind(SESSION, CONFIG); panel.useTimeView(view::get); panel.setSize(800, 500); }
        UiSnapshotPump.Frame frame(ElevatorSnapshot s) {
            return new UiSnapshotPump.Frame(SESSION, s.epoch(), List.of(s, idle(2, 1, s.epoch())), Map.of(),
                    Optional.of(new UiSnapshotPump.Timing(0, 0, true)));
        }
        void show(ElevatorSnapshot s) { panel.render(frame(s), s.epoch()); }
    }
    private static final class RealRig implements AutoCloseable {
        final SimulationController c;
        final BuildingPanel panel;
        RealRig(long movement, long dwell) throws Exception {
            c = new SimulationController(new SimulationConfig(2, 6, movement, dwell), 0);
            panel = onEdt(() -> { var p = new BuildingPanel(); p.bind(c.sessionId(), c.config()); p.useTimeView(c::timeView); return p; });
        }
        void startAndGo(int id, int floor) throws Exception {
            c.start().get(5, TimeUnit.SECONDS);
            c.submit(new CabinRequest(UUID.randomUUID(), c.epoch(), floor, Instant.now(), id)).get(5, TimeUnit.SECONDS);
        }
        void render(UiSnapshotPump.Frame f) throws Exception { if (f != null) edt(() -> panel.render(f, c.epoch())); }
        double position(int id) throws Exception { return onEdt(() -> panel.displayedPosition(id)); }
        @Override public void close() throws Exception { c.closeAsync().get(5, TimeUnit.SECONDS); }
    }
    private static ElevatorSnapshot moving(int floor, Direction direction, long reference, long remaining, long epoch, boolean held) {
        return new ElevatorSnapshot(1, CONFIG, epoch, 1, floor, OptionalInt.of(floor + direction.floorDelta()), direction,
                held ? ElevatorPhase.HELD : ElevatorPhase.MOVING, held ? Optional.of(ElevatorPhase.MOVING) : Optional.empty(),
                held ? Optional.of(ElevatorSnapshot.HoldReason.PAUSE) : Optional.empty(),
                epoch > 0 ? ElevatorSnapshot.Mode.RESET_RETURN : ElevatorSnapshot.Mode.NORMAL,
                1000 * MS, remaining, reference, OptionalInt.empty(), List.of(), List.of());
    }
    private static ElevatorSnapshot idle(int floor, long epoch) { return idle(1, floor, epoch); }
    private static ElevatorSnapshot idle(int id, int floor, long epoch) {
        return new ElevatorSnapshot(id, CONFIG, epoch, 2, floor, OptionalInt.empty(), Direction.UP, ElevatorPhase.IDLE,
                Optional.empty(), Optional.empty(), ElevatorSnapshot.Mode.NORMAL, 0, 0, 0, OptionalInt.empty(), List.of(), List.of());
    }
    private static UiSnapshotPump.Frame emptyFrame() { return new UiSnapshotPump.Frame(SESSION, 0, List.of(), Map.of()); }
    private static void click(SystemControlPanel p, SystemControlPanel.Action a) throws Exception {
        await(() -> value(() -> { p.updateView(); return p.button(a).isEnabled(); }));
        edt(() -> p.button(a).doClick(0));
    }
    private static void hold(CountDownLatch latch) {
        boolean interrupted = false;
        while (true) {
            try { latch.await(); break; } catch (InterruptedException e) { interrupted = true; }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }
    private static void near(double actual, double expected) {
        if (Math.abs(actual - expected) > 1e-9) throw new AssertionError("Esperado " + expected + ", obtenido " + actual);
    }
    private static void check(boolean condition, String detail) { if (!condition) throw new AssertionError(detail); }
    private static <T> T onEdt(Callable<T> action) throws Exception {
        var task = new FutureTask<>(action); SwingUtilities.invokeAndWait(task); return task.get(8, TimeUnit.SECONDS);
    }
    private static void edt(Runnable action) throws Exception { onEdt(() -> { action.run(); return null; }); }
    private static boolean value(Callable<Boolean> action) {
        try { return onEdt(action); } catch (Exception e) { throw new RuntimeException(e); }
    }
    private static void await(BooleanSupplier condition) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (!condition.getAsBoolean()) { check(System.nanoTime() < end, "Plazo agotado"); Thread.sleep(5); }
    }
    public static void main(String[] args) throws Exception {
        oldMovingFrameFreezesAtActualPauseInstant(); lateHeldFrameDoesNotMovePausedPosition();
        oldHeldFrameContinuesWithoutPausedWallTime(); repeatedPauseCyclesPreservePositionAndBudget();
        stopFinishesOnlyPublishedAdjacentLeg(); stopFromPausedMovementReleasesHeldProjection();
        startFromStoppedDoesNotResetPosition(); resetRetainsPhysicalLegAcrossEpochChange();
        resetReturnMovesDownThroughIntermediatePositions(); resetFromPausedHeldFramePreservesRemainder();
        closeFreezesEvenWhenLastFrameWasHeld(); invalidationDiscardsLateCapture();
        freshCaptureWaitsForOneWorkerAndBypassesGate(); invalidatedFailureCannotReplaceFreshStatus();
        closeCancelsPendingFreshCapture(); realPauseResumeKeepsExactPosition(); realDwellAndIdleRemainFixedWhilePaused();
        realStopFromPauseCompletesAdjacentLeg(); realResetFromPauseShowsPhysicalReturn();
        systemButtonsSynchronizeAndCloseAnimation(); System.out.println("T60: 20 escenarios de sincronización satisfactorios.");
    }
}
```

## src/test/java/com/elevatormanager/ui/AnimationSynchronizationTest.java

```java
package com.elevatormanager.ui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import com.elevatormanager.simulation.time.ClockViewScenarios;

/** Adaptadores JUnit T60: 20 de sincronización y 4 de publicación temporal. */
@Timeout(30)
class AnimationSynchronizationTest {
    @Test void oldMovingFrameFreezesAtActualPauseInstant() throws Exception {
        AnimationSynchronizationScenarios.oldMovingFrameFreezesAtActualPauseInstant();
    }
    @Test void lateHeldFrameDoesNotMovePausedPosition() throws Exception {
        AnimationSynchronizationScenarios.lateHeldFrameDoesNotMovePausedPosition();
    }
    @Test void oldHeldFrameContinuesWithoutPausedWallTime() throws Exception {
        AnimationSynchronizationScenarios.oldHeldFrameContinuesWithoutPausedWallTime();
    }
    @Test void repeatedPauseCyclesPreservePositionAndBudget() throws Exception {
        AnimationSynchronizationScenarios.repeatedPauseCyclesPreservePositionAndBudget();
    }
    @Test void stopFinishesOnlyPublishedAdjacentLeg() throws Exception {
        AnimationSynchronizationScenarios.stopFinishesOnlyPublishedAdjacentLeg();
    }
    @Test void stopFromPausedMovementReleasesHeldProjection() throws Exception {
        AnimationSynchronizationScenarios.stopFromPausedMovementReleasesHeldProjection();
    }
    @Test void startFromStoppedDoesNotResetPosition() throws Exception {
        AnimationSynchronizationScenarios.startFromStoppedDoesNotResetPosition();
    }
    @Test void resetRetainsPhysicalLegAcrossEpochChange() throws Exception {
        AnimationSynchronizationScenarios.resetRetainsPhysicalLegAcrossEpochChange();
    }
    @Test void resetReturnMovesDownThroughIntermediatePositions() throws Exception {
        AnimationSynchronizationScenarios.resetReturnMovesDownThroughIntermediatePositions();
    }
    @Test void resetFromPausedHeldFramePreservesRemainder() throws Exception {
        AnimationSynchronizationScenarios.resetFromPausedHeldFramePreservesRemainder();
    }
    @Test void closeFreezesEvenWhenLastFrameWasHeld() throws Exception {
        AnimationSynchronizationScenarios.closeFreezesEvenWhenLastFrameWasHeld();
    }
    @Test void invalidationDiscardsLateCapture() throws Exception {
        AnimationSynchronizationScenarios.invalidationDiscardsLateCapture();
    }
    @Test void freshCaptureWaitsForOneWorkerAndBypassesGate() throws Exception {
        AnimationSynchronizationScenarios.freshCaptureWaitsForOneWorkerAndBypassesGate();
    }
    @Test void invalidatedFailureCannotReplaceFreshStatus() throws Exception {
        AnimationSynchronizationScenarios.invalidatedFailureCannotReplaceFreshStatus();
    }
    @Test void closeCancelsPendingFreshCapture() throws Exception {
        AnimationSynchronizationScenarios.closeCancelsPendingFreshCapture();
    }
    @Test void realPauseResumeKeepsExactPosition() throws Exception {
        AnimationSynchronizationScenarios.realPauseResumeKeepsExactPosition();
    }
    @Test void realDwellAndIdleRemainFixedWhilePaused() throws Exception {
        AnimationSynchronizationScenarios.realDwellAndIdleRemainFixedWhilePaused();
    }
    @Test void realStopFromPauseCompletesAdjacentLeg() throws Exception {
        AnimationSynchronizationScenarios.realStopFromPauseCompletesAdjacentLeg();
    }
    @Test void realResetFromPauseShowsPhysicalReturn() throws Exception {
        AnimationSynchronizationScenarios.realResetFromPauseShowsPhysicalReturn();
    }
    @Test void systemButtonsSynchronizeAndCloseAnimation() throws Exception {
        AnimationSynchronizationScenarios.systemButtonsSynchronizeAndCloseAnimation();
    }
    @Test void publishedViewMatchesActiveTime() throws Exception {
        ClockViewScenarios.publishedViewMatchesActiveTime();
    }
    @Test void repeatedPauseResumeExcludesWallIntervals() throws Exception {
        ClockViewScenarios.repeatedPauseResumeExcludesWallIntervals();
    }
    @Test void closingFreezesReadableView() throws Exception {
        ClockViewScenarios.closingFreezesReadableView();
    }
    @Test void readingViewDoesNotWaitForClockLock() throws Exception {
        ClockViewScenarios.readingViewDoesNotWaitForClockLock();
    }
}
```
