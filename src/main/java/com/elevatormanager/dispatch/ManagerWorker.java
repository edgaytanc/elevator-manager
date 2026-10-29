package com.elevatormanager.dispatch;

import com.elevatormanager.logging.TraceMailbox;
import com.elevatormanager.simulation.time.PauseCoordinator;
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
    private final PauseCoordinator pause; // null: actores independientes de la sesión.
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
        this(config, epoch, elevators, commandIds, trace, null);
    }

    /** Dominio temporal compartido con las cabinas de esta sesión. */
    public ManagerWorker(SimulationConfig config, long epoch,
            List<ElevatorWorker> elevators, LongSupplier commandIds, TraceMailbox trace,
            PauseCoordinator pause) {
        this.pause = pause;
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

    /** El wake queda en cola si el actor aún no está esperando. */
    public void wakeForPause() { mailbox.wake(); }

    private long nowNanos() { return pause == null ? System.nanoTime() : pause.nowNanos(); }

    private void runLoop() {
        HallRequest inFlight = null;
        long lastRetry = nowNanos();
        try {
            trace.managerState(epoch, "MANAGER_STARTED", null);
            while (lifecycle == Lifecycle.RUNNING) {
                if (pause != null && !pause.checkpoint(0, () -> { }, () -> { })) { break; }
                if (!awaitDispatchPermission()) { break; }
                boolean hadEvents = false;
                for (int i = 0; i < 256; i++) {
                    var event = mailbox.await(Duration.ZERO);
                    if (event.isEmpty()) { break; }
                    ledger.onEvent(event.orElseThrow()); publish(); hadEvents = true;
                }
                long now = nowNanos();
                if (hadEvents || now - lastRetry >= RETRY_NANOS) {
                    var pending = ledger.pending();
                    int count = Math.min(16, pending.size());
                    for (int i = 0; i < count && lifecycle == Lifecycle.RUNNING; i++) {
                        var request = pending.get((int) (((long) retryCursor + i) % pending.size()));
                        dispatch(request); publish();
                    }
                    if (!pending.isEmpty()) { retryCursor = (retryCursor + count) % pending.size(); }
                    lastRetry = nowNanos();
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
                    if (stopRequested || !resetInbox.isEmpty() || lifecycle != Lifecycle.RUNNING
                            || (pause != null && pause.isRequested())) { continue; }
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
            if (pause != null) { pause.participantTerminated(0, failure); }
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
        if (selector.rank(config, request, eligibleCandidates(request, initial), nowNanos()).choices().isEmpty()) {
            return pending(request, "Conservada: no hay cabinas disponibles, confirmadas o estimables para despachar.");
        }
        long commandId = commandIds.getAsLong();
        if (commandId < 1) {
            throw new IllegalStateException("Generador de comandos agotado o inválido.");
        }
        // El generador ya terminó: todos los costos siguientes usan un instante nuevo.
        for (int attempt = 0; attempt < MAX_SELECTION_ATTEMPTS; attempt++) {
            var candidates = captureCandidates();
            var ranking = selector.rank(config, request, eligibleCandidates(request, candidates), nowNanos());
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
