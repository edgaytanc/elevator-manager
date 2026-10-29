# T55 — Código completo

Se reproducen íntegras las diez fuentes Java añadidas o modificadas. El ZIP incluye también todas las fuentes anteriores, recursos y documentación.

## src/main/java/com/elevatormanager/dispatch/ManagerWorker.java

```java
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
```

## src/main/java/com/elevatormanager/dispatch/PickupTimeEstimator.java

```java
package com.elevatormanager.dispatch;

import com.elevatormanager.model.Direction;
import com.elevatormanager.model.ElevatorPhase;
import com.elevatormanager.model.ElevatorSnapshot;
import com.elevatormanager.model.request.CabinRequest;
import com.elevatormanager.model.request.HallRequest;
import com.elevatormanager.model.request.Request;
import com.elevatormanager.simulation.DirectionalAgenda;
import com.elevatormanager.simulation.PlanningSnapshot;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * T30: proyección pura del tiempo hasta servicio de una llamada externa.
 * Simula fronteras y paradas; no recorre piso por piso ni modifica el motor.
 * Supone admisión inmediata, agenda estable y ausencia de nuevas solicitudes.
 */
public final class PickupTimeEstimator {
    public enum Status { ESTIMATED, UNAVAILABLE, INVALID_REQUEST }
    public enum StepType { REMAINING_LEG, MOVEMENT, WAIT, PICKUP }

    /** WAIT incluye solo paradas anteriores a la recogida, nunca su duración posterior. */
    public record Step(StepType type, int fromFloor, int toFloor,
            Direction direction, BigInteger durationNanos) { }

    /** Tiempo relativo a observedAtNanos; ausencia de tiempo no significa costo cero. */
    public record Estimate(Status status, Optional<BigInteger> pickupNanos,
            long observedAtNanos, List<Step> steps, String detail) {
        public Estimate { steps = List.copyOf(steps); }
    }

    /** Estima desde el instante de captura, sin consultar un reloj real. */
    public Estimate estimate(PlanningSnapshot planning, HallRequest request) {
        Objects.requireNonNull(planning, "planning");
        return estimate(planning, request, planning.elevator().referenceNanos());
    }

    /**
     * observedAtNanos debe usar el mismo reloj activo que el snapshot. Solo descuenta edad de la
     * fase capturada; no declara como reales llegadas todavía no publicadas.
     */
    public Estimate estimate(PlanningSnapshot planning, HallRequest request, long observedAtNanos) {
        Objects.requireNonNull(planning, "planning");
        Objects.requireNonNull(request, "request");
        var snapshot = planning.elevator();
        long age = observedAtNanos - snapshot.referenceNanos();
        if (age < 0) {
            throw new IllegalArgumentException("Observación anterior a la captura o intervalo no representable.");
        }
        if (request.getEpoch() != snapshot.epoch()
                || !request.validateAgainst(snapshot.config()).isValid()) {
            return rejected(Status.INVALID_REQUEST, observedAtNanos,
                    "Solicitud inválida para el edificio o la época.");
        }
        if (snapshot.mode() != ElevatorSnapshot.Mode.NORMAL
                || snapshot.phase() == ElevatorPhase.HELD
                || snapshot.phase() == ElevatorPhase.TERMINATED) {
            return rejected(Status.UNAVAILABLE, observedAtNanos,
                    "La cabina no está disponible para servicio normal.");
        }
        var pending = new ArrayList<>(planning.admissionOrder());
        HallRequest target = request;
        for (var existing : pending) {
            if (existing.getId().equals(request.getId())
                    && !PlanningSnapshot.sameRequest(existing, request)) {
                return rejected(Status.INVALID_REQUEST, observedAtNanos,
                        "UUID pendiente reutilizado con contenido diferente.");
            }
        }
        for (var existing : pending) {
            if (existing instanceof HallRequest hall && hall.getFloor() == request.getFloor()
                    && hall.getDirection() == request.getDirection()) {
                target = hall;
                break;
            }
        }
        if (target == request && !pending.contains(request)) { pending.add(request); }
        long remaining = age >= snapshot.remainingNanos() ? 0 : snapshot.remainingNanos() - age;
        return new Projection(planning, pending, target, remaining, observedAtNanos).run();
    }

    private static Estimate rejected(Status status, long now, String detail) {
        return new Estimate(status, Optional.empty(), now, List.of(), detail);
    }

    /** Estado exclusivo de una invocación; no contiene referencias mutables del actor. */
    private static final class Projection {
        private final ElevatorSnapshot source;
        private final ArrayList<Request> pending;
        private final HallRequest target;
        private final List<Step> steps = new ArrayList<>();
        private final long remaining;
        private final long observedAt;
        private final BigInteger movement;
        private final BigInteger dwell;
        private BigInteger elapsed = BigInteger.ZERO;
        private int floor;
        private int frontier;
        private Direction direction;
        private Optional<Direction> completedSweep;

        private Projection(PlanningSnapshot planning, ArrayList<Request> pending,
                HallRequest target, long remaining, long observedAt) {
            source = planning.elevator();
            this.pending = pending;
            this.target = target;
            this.remaining = remaining;
            this.observedAt = observedAt;
            movement = BigInteger.valueOf(source.config().movementTimeMillis()).multiply(BigInteger.valueOf(1_000_000));
            dwell = BigInteger.valueOf(source.config().dwellTimeMillis()).multiply(BigInteger.valueOf(1_000_000));
            floor = source.currentFloor();
            direction = source.direction();
            frontier = source.committedFloor().orElse(0);
            completedSweep = planning.completedSweep();
        }

        private Estimate run() {
            if (source.phase() == ElevatorPhase.MOVING) {
                if (frontier == 0 || (long) (frontier - floor) * direction.floorDelta() <= 0) {
                    throw new IllegalArgumentException("Movimiento sin frontera compatible.");
                }
                advance(StepType.REMAINING_LEG, source.nextFloor().orElseThrow(),
                        BigInteger.valueOf(remaining));
                reachedFrontier();
            } else if (source.phase() == ElevatorPhase.DWELLING) {
                if (remaining > 0 && matches(target, direction)) { return pickup(); }
                add(StepType.WAIT, floor, BigInteger.valueOf(remaining));
            }

            // Cada iteración atiende trabajo o avanza a una parada/frontera.
            // La cota detecta capturas incoherentes sin depender del número de pisos.
            long budget = 8L * (pending.size() + 1L) + 16;
            while (budget-- > 0) {
                var served = compatible(direction);
                if (!served.isEmpty()) {
                    if (served.contains(target)) { return pickup(); }
                    pending.removeAll(served);
                    add(StepType.WAIT, floor, dwell);
                    continue;
                }

                if (frontier != 0) {
                    if ((long) (frontier - floor) * direction.floorDelta() <= 0) {
                        throw new IllegalArgumentException("Frontera incompatible con piso y dirección.");
                    }
                    completedSweep = Optional.empty();
                    int next = frontier;
                    long distance = Math.abs((long) frontier - floor);
                    for (var request : pending) {
                        long delta = (long) request.getFloor() - floor;
                        if (delta * direction.floorDelta() > 0 && Math.abs(delta) <= distance
                                && compatibleDirection(request, direction)) {
                            next = request.getFloor();
                            distance = Math.abs(delta);
                        }
                    }
                    advance(StepType.MOVEMENT, next, movement.multiply(BigInteger.valueOf(distance)));
                    reachedFrontier();
                    continue;
                }

                if (!compatible(direction.opposite()).isEmpty()) {
                    direction = direction.opposite();
                    continue;
                }

                int next = DirectionalAgenda.selectTarget(floor, pending, completedSweep).orElseThrow();
                if (next == floor) { throw new IllegalStateException("Solicitud local sin servicio compatible."); }
                direction = next > floor ? Direction.UP : Direction.DOWN;
                frontier = next;
                // Igual que ElevatorState.beginJourney: destinos internos amplían la frontera.
                for (var request : pending) {
                    if (request instanceof CabinRequest) {
                        frontier = direction == Direction.UP
                                ? Math.max(frontier, request.getFloor()) : Math.min(frontier, request.getFloor());
                    }
                }
                completedSweep = Optional.empty();
            }
            throw new IllegalStateException("No se pudo proyectar una agenda finita coherente.");
        }

        private List<Request> compatible(Direction serviceDirection) {
            return pending.stream().filter(r -> matches(r, serviceDirection)).toList();
        }

        private boolean matches(Request request, Direction serviceDirection) {
            return request.getFloor() == floor && compatibleDirection(request, serviceDirection);
        }

        private static boolean compatibleDirection(Request request, Direction serviceDirection) {
            return request instanceof CabinRequest
                    || ((HallRequest) request).getDirection() == serviceDirection;
        }

        private void reachedFrontier() {
            if (floor == frontier) {
                frontier = 0;
                completedSweep = Optional.of(direction);
            }
        }

        private void advance(StepType type, int to, BigInteger duration) {
            add(type, to, duration);
            floor = to;
        }

        private void add(StepType type, int to, BigInteger duration) {
            steps.add(new Step(type, floor, to, direction, duration));
            elapsed = elapsed.add(duration);
        }

        private Estimate pickup() {
            add(StepType.PICKUP, floor, BigInteger.ZERO);
            return new Estimate(Status.ESTIMATED, Optional.of(elapsed), observedAt, steps,
                    "Proyección de servicio compatible; excluye la parada posterior a recoger.");
        }
    }
}
```

## src/main/java/com/elevatormanager/simulation/ElevatorWorker.java

```java
package com.elevatormanager.simulation;

import com.elevatormanager.logging.TraceMailbox;
import com.elevatormanager.simulation.time.PauseCoordinator;
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
    private final PauseCoordinator pause; // null: actor independiente, nanoTime directo.
    private boolean pauseView;
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
        this(id, config, epoch, trace, null);
    }

    /** Sesión coordinada: todos los actores deben compartir exactamente esta barrera. */
    public ElevatorWorker(int id, SimulationConfig config, long epoch, TraceMailbox trace,
            PauseCoordinator pause) {
        this.pause = pause;
        this.trace = Objects.requireNonNull(trace, "trace");
        state = new ElevatorState(id, config, epoch);
        ledger = new CommandLedger(state);
        published = new AtomicReference<>(capture(nowNanos()));
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
        ElevatorSnapshot snapshot = state.snapshot(now);
        if (pauseView && (snapshot.phase() == ElevatorPhase.MOVING || snapshot.phase() == ElevatorPhase.DWELLING)) {
            snapshot = new ElevatorSnapshot(snapshot.id(), snapshot.config(), snapshot.epoch(), snapshot.revision(),
                    snapshot.currentFloor(), snapshot.nextFloor(), snapshot.direction(), ElevatorPhase.HELD,
                    Optional.of(snapshot.phase()), Optional.of(ElevatorSnapshot.HoldReason.PAUSE), snapshot.mode(),
                    snapshot.phaseDurationNanos(), snapshot.remainingNanos(), snapshot.referenceNanos(),
                    snapshot.committedFloor(), snapshot.cabinRequests(), snapshot.hallRequests());
        }
        return new PlanningSnapshot(snapshot,
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

    /** Señal breve: despierta espera de tramo, parada o reposo para llegar al checkpoint. */
    public void wakeForPause() {
        lock.lock();
        try { available.signalAll(); }
        finally { lock.unlock(); }
    }

    private long nowNanos() { return pause == null ? System.nanoTime() : pause.nowNanos(); }

    private void publishPauseView(boolean value) {
        lock.lock();
        try {
            pauseView = value;
            published.set(capture(nowNanos()));
        } finally { lock.unlock(); }
    }

    private void runLoop() {
        try {
            while (true) {
                if (pause != null && !pause.checkpoint(state.getId(),
                        () -> publishPauseView(true), () -> publishPauseView(false))) { break; }
                Result result = null;
                CompletableFuture<Void> stoppedAck = null;
                List<Runnable> deferred = new ArrayList<>();
                List<Service> notifications = new ArrayList<>();
                lock.lockInterruptibly();
                try {
                    if (lifecycle != Lifecycle.RUNNING) { break; }
                    // Cierra la carrera entre checkpoint y adquisición del lock del actor.
                    if (pause != null && pause.isRequested()) { continue; }
                    long now = nowNanos();
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
                        continueWork(nowNanos(), notifications);
                    } else if (state.getPhase() == ElevatorPhase.MOVING
                            || state.getPhase() == ElevatorPhase.DWELLING) {
                        long remaining = state.phaseRemainingNanos(nowNanos());
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
            if (pause != null) { pause.participantTerminated(state.getId(), failure); }
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
                emitAdmission(rejected, nowNanos());
                results.add(rejected);
            }
            for (ResetTicket ticket : abandonedResets) {
                Result rejected = new Result(ticket.command(), Outcome.REJECTED, "Cabina finalizada antes de procesar reset.");
                emitAdmission(rejected, nowNanos());
                results.add(rejected);
            }
            emit(failure == null ? ExecutionEvent.Type.WORKER_TERMINATED
                    : ExecutionEvent.Type.WORKER_FAILED, nowNanos());
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
            emitAdmission(rejected, nowNanos());
            return rejected;
        }
        CommandLedger.Decision decision = ledger.admit(command);
        published.set(capture(nowNanos()));
        Outcome outcome = switch (decision.kind()) {
            case ADDED -> Outcome.ACCEPTED;
            case DUPLICATE -> Outcome.DUPLICATE;
            case REJECTED -> Outcome.REJECTED;
        };
        Result result = new Result(command, outcome, decision.detail(), decision.canonicalRequestId(),
                decision.requestStatus(), decision.replay());
        long now = nowNanos();
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

## src/main/java/com/elevatormanager/simulation/ExecutionEvent.java

```java
package com.elevatormanager.simulation;

import com.elevatormanager.model.ElevatorSnapshot;
import com.elevatormanager.protocol.Command;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Hecho inmutable capturado por el actor, antes de ejecutar la siguiente transición.
 * sequence ordena eventos de una cabina; revision ordena cambios de su modelo.
 * referenceNanos pertenece al reloj activo de la sesión (nanoTime directo en actores
 * independientes), no a una fecha civil. Comparar solo dentro del mismo dominio.
 */
public record ExecutionEvent(long sequence, Type type, ElevatorSnapshot snapshot,
        Optional<Command> command, Optional<UUID> canonicalRequestId, String detail) {
    public enum Type {
        COMMAND_ACCEPTED, COMMAND_DUPLICATE, COMMAND_REJECTED, COMMAND_REPLAYED,
        DEPARTED, ARRIVED, STOP_STARTED, STOP_ENDED, DIRECTION_CHANGED,
        COMMAND_COMPLETED, WORKER_TERMINATED, WORKER_FAILED, CONTROL_STOPPED, CONTROL_RESUMED,
        COMMAND_CANCELLED, RESET_STARTED, RESET_COMPLETED
    }

    public ExecutionEvent {
        if (sequence < 1) { throw new IllegalArgumentException("Secuencia no positiva."); }
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(canonicalRequestId, "canonicalRequestId");
        Objects.requireNonNull(detail, "detail");
    }

    public int elevatorId() { return snapshot.id(); }
    public long epoch() { return snapshot.epoch(); }
    public long referenceNanos() { return snapshot.referenceNanos(); }
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
        TraceMailbox trace = sessionLog.map(SessionLog::mailbox).orElse(TraceMailbox.disabled());
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
            if (!pauseCoordinator.resume()) {
                var error = new IllegalStateException("Barrera de reanudación incompleta.");
                failure = error;
                beginClose();
                throw error;
            }
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

    public static void pausedStopAndResetAwaitT56Policy() throws Exception {
        try (var rig = new Session(1, 4, 50, 30)) {
            var c = rig.controller;
            get(c.start()); get(c.pause());
            check(get(c.stop()).outcome() == SimulationController.Outcome.REJECTED, "Stop pausado pendiente de T56");
            check(get(c.reset()).outcome() == SimulationController.Outcome.REJECTED, "Reset pausado pendiente de T56");
            check(c.state() == SimulationState.PAUSED && c.epoch() == 0, "Conserva contexto");
            get(c.resume()); get(c.stop()); get(c.reset());
            check(c.state() == SimulationState.RESET && c.epoch() == 1, "Flujo base conservado después de Resume");
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
        pausedStopAndResetAwaitT56Policy();
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
    @Test void pausedStopAndResetAwaitT56Policy() throws Exception {
        GlobalPauseScenarios.pausedStopAndResetAwaitT56Policy();
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

## src/test/java/com/elevatormanager/simulation/time/PauseCoordinatorScenarios.java

```java
package com.elevatormanager.simulation.time;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Pruebas de la barrera sin depender de la velocidad de las cabinas. */
public final class PauseCoordinatorScenarios {
    private PauseCoordinatorScenarios() { }

    public static void validatesParticipants() {
        for (int n : new int[] {-1, 0, 1}) {
            try { new PauseCoordinator(n); throw new AssertionError("Debe rechazar " + n); }
            catch (IllegalArgumentException expected) { }
        }
        try (var gate = new PauseCoordinator(2)) {
            check(gate.status().participants() == 2, "Manejador y una cabina");
        }
    }

    public static void barrierRequiresEveryActor() throws Exception {
        try (var gate = new PauseCoordinator(3)) {
            var future = gate.requestPause();
            try (var first = new Actor(gate, 0); var second = new Actor(gate, 1)) {
                first.waiting(); second.waiting();
                check(gate.status().acknowledged() == 2, "Dos reconocimientos");
                check(!future.isDone() && !gate.resume(), "Sin PAUSED ni reanudación prematura");
                try (var third = new Actor(gate, 2)) {
                    future.get(3, TimeUnit.SECONDS);
                    check(gate.status().acknowledged() == 3, "Barrera completa");
                    check(gate.resume(), "Reanudación permitida");
                    first.finished(); second.finished(); third.finished();
                }
            }
        }
    }

    public static void clockFreezesBeforeAcknowledgements() throws Exception {
        try (var gate = new PauseCoordinator(2)) {
            gate.requestPause();
            long frozen = gate.nowNanos();
            Thread.sleep(25);
            check(gate.nowNanos() == frozen, "Congelado desde PAUSING, antes de los ACK");
            check(gate.status().acknowledged() == 0, "Sin ACK inventados");
        }
    }

    public static void callbackFinishesBeforeAcknowledgement() throws Exception {
        try (var gate = new PauseCoordinator(2)) {
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var failure = new AtomicReference<Throwable>();
            var future = gate.requestPause();
            Thread slow = new Thread(() -> {
                try {
                    gate.checkpoint(1, () -> {
                        entered.countDown();
                        try { check(release.await(3, TimeUnit.SECONDS), "Liberación de captura"); }
                        catch (InterruptedException e) { throw new AssertionError(e); }
                    }, () -> { });
                } catch (Throwable e) { failure.set(e); }
            }, "t55-delayed-snapshot");
            slow.start();
            try (var manager = new Actor(gate, 0)) {
                check(entered.await(3, TimeUnit.SECONDS), "Captura iniciada");
                manager.waiting();
                check(gate.status().acknowledged() == 1 && !future.isDone(), "Captura pendiente no confirma");
                release.countDown();
                future.get(3, TimeUnit.SECONDS);
                gate.resume();
                manager.finished();
            } finally {
                release.countDown(); gate.close(); slow.join(3000);
                check(!slow.isAlive() && failure.get() == null, "Captura terminó sin fallo");
            }
        }
    }

    public static void publicCancellationDoesNotCancelBarrier() throws Exception {
        try (var gate = new PauseCoordinator(2)) {
            var cancelled = gate.requestPause();
            var observer = gate.requestPause();
            cancelled.cancel(true);
            try (var manager = new Actor(gate, 0); var cabin = new Actor(gate, 1)) {
                observer.get(3, TimeUnit.SECONDS);
                check(observer.isDone() && !observer.isCancelled(), "Barrera privada conservada");
                gate.resume(); manager.finished(); cabin.finished();
            }
        }
    }

    public static void repeatedPausePreservesGeneration() throws Exception {
        try (var gate = new PauseCoordinator(2)) {
            var first = gate.requestPause();
            long generation = gate.status().generation();
            var second = gate.requestPause();
            check(gate.status().generation() == generation, "Misma operación");
            try (var manager = new Actor(gate, 0); var cabin = new Actor(gate, 1)) {
                first.get(3, TimeUnit.SECONDS); second.get(3, TimeUnit.SECONDS);
                check(gate.resume() && !gate.resume(), "Una única liberación");
                manager.finished(); cabin.finished();
            }
        }
    }

    public static void repeatedGenerationsCannotReuseOldAcks() throws Exception {
        try (var gate = new PauseCoordinator(2)) {
            for (int cycle = 1; cycle <= 12; cycle++) {
                var future = gate.requestPause();
                check(gate.status().generation() == cycle && gate.status().acknowledged() == 0,
                        "Generación nueva sin ACK previos");
                try (var manager = new Actor(gate, 0); var cabin = new Actor(gate, 1)) {
                    future.get(3, TimeUnit.SECONDS);
                    gate.resume(); manager.finished(); cabin.finished();
                }
            }
        }
    }

    public static void closeCancelsPartialBarrierAndWakesActors() throws Exception {
        try (var gate = new PauseCoordinator(3)) {
            var future = gate.requestPause();
            try (var manager = new Actor(gate, 0)) {
                manager.waiting(); gate.close(); gate.close();
                manager.finished();
                check(future.isCompletedExceptionally(), "Barrera cancelada sin esperar al ausente");
                check(gate.requestPause().isCompletedExceptionally() && !gate.resume(), "Cierre definitivo");
            }
        }
    }

    public static void terminatedParticipantRejectsPause() {
        try (var gate = new PauseCoordinator(2)) {
            gate.participantTerminated(1, new IllegalStateException("fallo de prueba"));
            check(gate.requestPause().isCompletedExceptionally(), "Participante fallido no es pausado");
            check(!gate.isRequested(), "No deja reloj congelado");
        }
    }

    public static void failureCompletesBarrierWithoutWaitingForOthers() {
        try (var gate = new PauseCoordinator(3)) {
            var future = gate.requestPause();
            gate.participantTerminated(1, new IllegalStateException("fallo de prueba"));
            check(future.isCompletedExceptionally(), "Fallo inmediato, no allOf infinito");
            check(!gate.resume(), "Sin reanudar barrera fallida");
        }
    }

    public static void invalidParticipantIsRejected() throws Exception {
        try (var gate = new PauseCoordinator(2)) {
            try { gate.checkpoint(2, () -> { }, () -> { }); throw new AssertionError("ID inválido"); }
            catch (IllegalArgumentException expected) { }
        }
    }

    public static void activeCheckpointDoesNotPublishPause() throws Exception {
        try (var gate = new PauseCoordinator(2)) {
            var callbacks = new AtomicInteger();
            check(gate.checkpoint(0, callbacks::incrementAndGet, callbacks::incrementAndGet), "Activo");
            check(callbacks.get() == 0, "Sin publicación artificial");
        }
    }

    private static final class Actor implements AutoCloseable {
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final Thread thread;
        Actor(PauseCoordinator gate, int id) {
            thread = new Thread(() -> {
                try { gate.checkpoint(id, () -> { }, () -> { }); }
                catch (Throwable e) { failure.set(e); gate.participantTerminated(id, e); }
            }, "t55-barrier-" + id);
            thread.start();
        }
        void waiting() throws Exception {
            long start = System.nanoTime();
            while (thread.isAlive() && thread.getState() != Thread.State.WAITING
                    && System.nanoTime() - start < TimeUnit.SECONDS.toNanos(3)) { Thread.sleep(1); }
            check(thread.isAlive() && thread.getState() == Thread.State.WAITING, "Actor esperando en condición");
        }
        void finished() throws Exception {
            thread.join(3000);
            check(!thread.isAlive() && failure.get() == null, "Actor finalizado sin error: " + failure.get());
        }
        @Override public void close() throws InterruptedException {
            thread.interrupt(); thread.join(3000);
            check(!thread.isAlive(), "Sin hilo abandonado");
        }
    }

    private static void check(boolean condition, String detail) {
        if (!condition) { throw new AssertionError(detail); }
    }

    public static void main(String[] args) throws Exception {
        validatesParticipants();
        barrierRequiresEveryActor();
        clockFreezesBeforeAcknowledgements();
        callbackFinishesBeforeAcknowledgement();
        publicCancellationDoesNotCancelBarrier();
        repeatedPausePreservesGeneration();
        repeatedGenerationsCannotReuseOldAcks();
        closeCancelsPartialBarrierAndWakesActors();
        terminatedParticipantRejectsPause();
        failureCompletesBarrierWithoutWaitingForOthers();
        invalidParticipantIsRejected();
        activeCheckpointDoesNotPublishPause();
        System.out.println("T55: 12 escenarios de barrera satisfactorios.");
    }
}
```

## src/test/java/com/elevatormanager/simulation/time/PauseCoordinatorTest.java

```java
package com.elevatormanager.simulation.time;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** T55: escenarios completos bajo JUnit. */
@Timeout(30)
class PauseCoordinatorTest {
    @Test void validatesParticipants() throws Exception {
        PauseCoordinatorScenarios.validatesParticipants();
    }
    @Test void barrierRequiresEveryActor() throws Exception {
        PauseCoordinatorScenarios.barrierRequiresEveryActor();
    }
    @Test void clockFreezesBeforeAcknowledgements() throws Exception {
        PauseCoordinatorScenarios.clockFreezesBeforeAcknowledgements();
    }
    @Test void callbackFinishesBeforeAcknowledgement() throws Exception {
        PauseCoordinatorScenarios.callbackFinishesBeforeAcknowledgement();
    }
    @Test void publicCancellationDoesNotCancelBarrier() throws Exception {
        PauseCoordinatorScenarios.publicCancellationDoesNotCancelBarrier();
    }
    @Test void repeatedPausePreservesGeneration() throws Exception {
        PauseCoordinatorScenarios.repeatedPausePreservesGeneration();
    }
    @Test void repeatedGenerationsCannotReuseOldAcks() throws Exception {
        PauseCoordinatorScenarios.repeatedGenerationsCannotReuseOldAcks();
    }
    @Test void closeCancelsPartialBarrierAndWakesActors() throws Exception {
        PauseCoordinatorScenarios.closeCancelsPartialBarrierAndWakesActors();
    }
    @Test void terminatedParticipantRejectsPause() throws Exception {
        PauseCoordinatorScenarios.terminatedParticipantRejectsPause();
    }
    @Test void failureCompletesBarrierWithoutWaitingForOthers() throws Exception {
        PauseCoordinatorScenarios.failureCompletesBarrierWithoutWaitingForOthers();
    }
    @Test void invalidParticipantIsRejected() throws Exception {
        PauseCoordinatorScenarios.invalidParticipantIsRejected();
    }
    @Test void activeCheckpointDoesNotPublishPause() throws Exception {
        PauseCoordinatorScenarios.activeCheckpointDoesNotPublishPause();
    }
}
```
