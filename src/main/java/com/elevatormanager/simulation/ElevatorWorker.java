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
import com.elevatormanager.protocol.ProtocolCodec;
import com.elevatormanager.protocol.ProtocolCommands;
import com.elevatormanager.protocol.ProtocolDecodeResult;
import com.elevatormanager.protocol.ProtocolMessage;
import com.elevatormanager.protocol.ProtocolIdentities;
import com.elevatormanager.protocol.ProtocolMessage.ErrorCode;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
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
 * T64: buzones de texto V1; parseo/admisión en el actor y reset prioritario.
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
    /** El sobre solo lleva texto y fecha contextual; nunca el Command original. */
    private enum Origin { LEGACY, MANAGER, CONTROLLER }
    private record TextFrame(String text, Instant createdAt, Origin origin, boolean control) {
        TextFrame(String text, Instant date) { this(text, date, Origin.LEGACY, false); }
        TextFrame { Objects.requireNonNull(text, "text"); Objects.requireNonNull(createdAt, "createdAt"); }
    }
    private final ArrayDeque<TextFrame> controlInbox = new ArrayDeque<>();
    private final Map<ProtocolMessage.ControlKind, ProtocolMessage.Control> controls = new HashMap<>();
    private final Map<Long, ProtocolMessage> admittedProtocol = new HashMap<>();
    private final Map<WireKey, ProtocolMessage.State> wireStates = new HashMap<>();
    private ProtocolIdentities identities = new ProtocolIdentities();
    private Origin processingOrigin = Origin.LEGACY;
    private boolean protocolStarted;

    /** Capacidad del emisor por objeto interno, nunca un token autodeclarado. */
    public final class CommandChannel {
        private final Origin origin;
        private CommandChannel(Origin origin) { this.origin = origin; }
        public boolean send(String text, Instant date) {
            return enqueue(new TextFrame(text, date, origin, false), null, false) == Submission.ENQUEUED;
        }
        public Submission sendIfCurrent(String text, Instant date, PlanningSnapshot expected) {
            Objects.requireNonNull(expected, "expected");
            return enqueue(new TextFrame(text, date, origin, false), expected, false);
        }
        public boolean control(String text) {
            lock.lock();
            try {
                if (lifecycle != Lifecycle.RUNNING) { return false; }
                controlInbox.addLast(new TextFrame(text, Instant.EPOCH, origin, true));
                available.signalAll();
            } finally { lock.unlock(); }
            if (pause != null) { pause.wakeControls(); }
            return true;
        }
    }
    private final CommandChannel managerChannel = new CommandChannel(Origin.MANAGER);
    private final CommandChannel controllerChannel = new CommandChannel(Origin.CONTROLLER);
    public CommandChannel managerChannel() { return managerChannel; }
    public CommandChannel controllerChannel() { return controllerChannel; }

    /** Solo composición antes de start; el registro no llama a otros actores. */
    public void useProtocolIdentities(ProtocolIdentities registry) {
        lock.lock();
        try {
            if (lifecycle != Lifecycle.NEW) { throw new IllegalStateException("La sesión ya comenzó."); }
            identities = Objects.requireNonNull(registry, "registry");
        } finally { lock.unlock(); }
    }
    private record ResetFrame(TextFrame frame, CompletableFuture<ElevatorSnapshot> response) { }
    private record Decoded(Command command, Result rejection) { }
    public record ProtocolReception(long frames, long malformed, String processedBy) { }
    private final AtomicReference<ProtocolReception> protocolReception =
            new AtomicReference<>(new ProtocolReception(0, 0, ""));
    private final ProtocolCodec protocol;
    private final ProtocolMailbox protocolOutput = new ProtocolMailbox();
    private final CopyOnWriteArrayList<ProtocolMailbox> protocolSubscribers = new CopyOnWriteArrayList<>();
    private final List<String> protocolBatch = new ArrayList<>();
    private record WireKey(long epoch, long id) { }
    private final Map<WireKey, ProtocolMessage> wireAdmissions = new HashMap<>();
    private final Map<WireKey, ProtocolMessage.Terminal> wireTerminals = new HashMap<>();
    private final ArrayDeque<TextFrame> inbox = new ArrayDeque<>();
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
    private final ArrayDeque<ResetFrame> resetInbox = new ArrayDeque<>();
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
        protocol = new ProtocolCodec(config);
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
        TextFrame frame = encode(command);
        return enqueue(frame, null, command instanceof Command.Reset) == Submission.ENQUEUED;
    }

    /** Entrada de texto real. true confirma encolado, nunca parseo/admisión. */
    public boolean submitText(String text) { return submitText(text, Instant.EPOCH); }

    /** createdAt es contexto de sesión, no parte del contenido operativo V1. */
    public boolean submitText(String text, Instant createdAt) {
        return enqueue(new TextFrame(text, createdAt), null, false) == Submission.ENQUEUED;
    }

    public Submission submitTextIfCurrent(String text, Instant createdAt, PlanningSnapshot expected) {
        Objects.requireNonNull(expected, "expected");
        return enqueue(new TextFrame(text, createdAt), expected, false);
    }

    private Submission enqueue(TextFrame frame, PlanningSnapshot expected, boolean reset) {
        lock.lock();
        try {
            if (lifecycle != Lifecycle.RUNNING) { return Submission.UNAVAILABLE; }
            if (expected != null && published.get() != expected) { return Submission.STALE; }
            if (reset) {
                resetInbox.addLast(new ResetFrame(frame, new CompletableFuture<>()));
                available.signalAll();
                return Submission.ENQUEUED;
            }
            if (resetHeld || !resetInbox.isEmpty()) { return Submission.UNAVAILABLE; }
            inbox.addLast(frame);
            available.signal();
            return Submission.ENQUEUED;
        } finally { lock.unlock(); }
    }

    /** Serializa antes de cruzar el buzón prioritario; futuro de retorno físico. */
    public CompletableFuture<ElevatorSnapshot> requestReset(Command.Reset command) {
        return requestResetText(encode(command).text());
    }

    /** El actor valida que el texto sea RESET; un cuerpo inválido no cambia época. */
    public CompletableFuture<ElevatorSnapshot> requestResetText(String text) {
        TextFrame frame = new TextFrame(text, Instant.EPOCH);
        lock.lock();
        try {
            if (lifecycle != Lifecycle.RUNNING) {
                return CompletableFuture.failedFuture(new IllegalStateException("Cabina no disponible."));
            }
            var response = new CompletableFuture<ElevatorSnapshot>();
            resetInbox.addLast(new ResetFrame(frame, response));
            available.signalAll();
            return response.copy();
        } finally { lock.unlock(); }
    }

    /** Canal textual propio, independiente de Result/Service/ExecutionEvent. */
    public Optional<String> awaitProtocolMessage(Duration timeout) throws InterruptedException {
        return protocolOutput.await(timeout);
    }

    public void subscribeProtocol(ProtocolMailbox mailbox) {
        protocolSubscribers.addIfAbsent(Objects.requireNonNull(mailbox, "mailbox"));
    }

    public void unsubscribeProtocol(ProtocolMailbox mailbox) { protocolSubscribers.remove(mailbox); }
    public ProtocolReception protocolReception() { return protocolReception.get(); }

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
        Objects.requireNonNull(expected, "expected");
        return enqueue(encode(command), expected, command instanceof Command.Reset);
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
                        () -> publishPauseView(true), () -> publishPauseView(false), this::pausedControls)) { break; }
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
                    if (!controlInbox.isEmpty()) {
                        processControlFrame(controlInbox.removeFirst(), now, deferred);
                    } else if (!resetInbox.isEmpty()) {
                        processResetFrame(resetInbox.removeFirst(), now, deferred);
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
                        Decoded decoded = decode(inbox.removeFirst());
                        if (decoded.command() instanceof Command.Reset) {
                            result = rejectServiceControl(decoded.command(), now);
                        } else if (decoded.command() != null) {
                            result = admit(decoded.command());
                        } else { result = decoded.rejection(); }
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
                completeControls();
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
            List<TextFrame> abandoned;
            List<ResetFrame> abandonedResets;
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
            while (!controlInbox.isEmpty()) {
                TextFrame frame = controlInbox.removeFirst();
                var parsed = protocol.decodeCommand(frame.text());
                if (parsed.message().orElse(null) instanceof ProtocolMessage.Control control
                        && control.kind() == ProtocolMessage.ControlKind.SHUTDOWN && frame.origin() == Origin.CONTROLLER) {
                    processingOrigin = frame.origin(); acceptControl(control);
                } else if (parsed.isSuccess()) {
                    wire(new ProtocolMessage.Error(parsed.message().orElseThrow().header(), ErrorCode.WORKER_UNAVAILABLE));
                } else { wire(parsed.failure().orElseThrow().asErrorMessage()); }
            }
            cancelControls(ProtocolMessage.CancelReason.SHUTDOWN);
            for (TextFrame frame : abandoned) {
                rejectAbandoned(frame);
            }
            for (ResetFrame ticket : abandonedResets) { rejectAbandoned(ticket.frame()); }
            for (CommandLedger.Completion pending : ledger.clearForReset()) {
                wire(new ProtocolMessage.Terminal(header(pending.command()), failure == null
                        ? ProtocolMessage.TerminalKind.CANCELLED : ProtocolMessage.TerminalKind.FAILED,
                        failure == null ? Optional.of(ProtocolMessage.CancelReason.SHUTDOWN) : Optional.empty(),
                        failure == null ? Optional.empty() : Optional.of(ErrorCode.INTERNAL_ERROR)));
            }
            if (lastReset != null && !lastReset.completion().isDone()) {
                wire(new ProtocolMessage.Terminal(header(lastReset.command()), failure == null
                        ? ProtocolMessage.TerminalKind.CANCELLED : ProtocolMessage.TerminalKind.FAILED,
                        failure == null ? Optional.of(ProtocolMessage.CancelReason.SHUTDOWN) : Optional.empty(),
                        failure == null ? Optional.empty() : Optional.of(ErrorCode.INTERNAL_ERROR)));
            }
            ProtocolMessage.Control shutdown = controls.remove(ProtocolMessage.ControlKind.SHUTDOWN);
            if (shutdown != null) { controlCompleted(shutdown); }
            emit(failure == null ? ExecutionEvent.Type.WORKER_TERMINATED
                    : ExecutionEvent.Type.WORKER_FAILED, nowNanos());
            flushEvents();
            var resetFailure = new IllegalStateException("Cabina terminada antes de completar reset.", failure);
            for (ResetFrame ticket : abandonedResets) { ticket.response().completeExceptionally(resetFailure); }
            if (lastReset != null && !lastReset.completion().isDone()) {
                lastReset.completion().completeExceptionally(resetFailure);
            }
            if (stopConfirmation != null && !stopConfirmation.isDone()) {
                stopConfirmation.completeExceptionally(new IllegalStateException("Cabina terminada antes de confirmar Stop.", failure));
            }
        }
    }

    private boolean pausedControls() {
        List<Runnable> deferred = new ArrayList<>();
        lock.lock();
        try {
            while (!controlInbox.isEmpty()) { processControlFrame(controlInbox.removeFirst(), nowNanos(), deferred); }
            completeControls();
        } finally { lock.unlock(); }
        flushEvents();
        deferred.forEach(Runnable::run);
        return lifecycle == Lifecycle.RUNNING;
    }

    private void processControlFrame(TextFrame frame, long now, List<Runnable> deferred) {
        Decoded decoded = decode(frame);
        if (decoded.command() instanceof Command.Reset reset) {
            processReset(new ResetTicket(reset, new CompletableFuture<>()), now, deferred);
        }
    }

    private void replayProtocol(ProtocolMessage command) {
        WireKey key = new WireKey(command.header().epoch(), command.header().id());
        ProtocolMessage ack = wireAdmissions.get(key);
        if (ack != null) { wire(ack); }
        if (wireStates.containsKey(key)) { wire(wireStates.get(key)); }
        if (wireTerminals.containsKey(key)) { wire(wireTerminals.get(key)); }
    }

    private boolean acceptControl(ProtocolMessage.Control command) {
        if (!identities.reserve(command)) { wire(new ProtocolMessage.Error(command.header(), ErrorCode.ID_CONFLICT)); return false; }
        admittedProtocol.put(command.header().id(), command);
        controls.put(command.kind(), command);
        var ack = new ProtocolMessage.Ack(command.header(), ProtocolMessage.AckKind.ACCEPTED, Optional.empty());
        wireAdmissions.put(new WireKey(command.header().epoch(), command.header().id()), ack); wire(ack);
        return true;
    }

    private void handleControl(ProtocolMessage.Control command) {
        var kind = command.kind();
        boolean valid = switch (kind) {
            case STATUS, SHUTDOWN -> true;
            case START -> !state.isResetting() && ((!protocolStarted && !stopRequested) || stopped && stopRequested);
            case STOP -> !stopRequested && !state.isResetting();
            case PAUSE -> pause != null && pause.isRequested();
            case RESUME -> pause != null && !pause.isRequested() && !stopRequested && !state.isResetting();
            case RESET -> false;
        };
        if (!valid || controls.containsKey(kind)) { wire(new ProtocolMessage.Error(command.header(), ErrorCode.INVALID_STATE)); return; }
        if (!acceptControl(command)) { return; }
        switch (kind) {
            case STATUS -> {
                controls.remove(kind);
                if (wireState(command.header())) { controlTerminal(command, ProtocolMessage.TerminalKind.COMPLETED, Optional.empty(), Optional.empty()); }
                else { controlTerminal(command, ProtocolMessage.TerminalKind.FAILED, Optional.empty(), Optional.of(ErrorCode.MESSAGE_TOO_LONG)); }
            }
            case START -> { protocolStarted = true; if (stopRequested) { resume(); } }
            case STOP -> requestStop();
            case SHUTDOWN -> { lifecycle = Lifecycle.CLOSING; available.signalAll(); }
            default -> { }
        }
    }

    private void completeControls() {
        var ready = new ArrayList<ProtocolMessage.Control>();
        for (var command : controls.values()) {
            boolean complete = switch (command.kind()) {
                case START -> !stopRequested && !resumeRequested;
                case STOP -> stopped;
                case PAUSE -> pauseView;
                case RESUME -> !pauseView && pause != null && !pause.isRequested();
                default -> false;
            };
            if (complete) { ready.add(command); }
        }
        for (var command : ready) { controls.remove(command.kind()); controlCompleted(command); }
    }

    private void cancelControls(ProtocolMessage.CancelReason reason) {
        for (var command : List.copyOf(controls.values())) {
            if (command.kind() != ProtocolMessage.ControlKind.SHUTDOWN) {
                controls.remove(command.kind());
                controlTerminal(command, ProtocolMessage.TerminalKind.CANCELLED, Optional.of(reason), Optional.empty());
            }
        }
    }

    private void controlCompleted(ProtocolMessage.Control command) {
        if (command.kind() != ProtocolMessage.ControlKind.SHUTDOWN) { wireState(command.header()); }
        controlTerminal(command, ProtocolMessage.TerminalKind.COMPLETED, Optional.empty(), Optional.empty());
    }
    private void controlTerminal(ProtocolMessage.Control command, ProtocolMessage.TerminalKind kind,
            Optional<ProtocolMessage.CancelReason> reason, Optional<ErrorCode> error) {
        wire(new ProtocolMessage.Terminal(command.header(), kind, reason, error));
    }

    private boolean wireState(ProtocolMessage.Header header) {
        var snapshot = capture(nowNanos()).elevator();
        var internal = snapshot.cabinRequests().stream().map(Request::getFloor).distinct().sorted().toList();
        var external = snapshot.hallRequests().stream()
                .map(r -> new ProtocolMessage.ExternalCall(r.getFloor(), r.getDirection())).distinct()
                .sorted(java.util.Comparator.comparingInt(ProtocolMessage.ExternalCall::floor)
                        .thenComparing(ProtocolMessage.ExternalCall::direction)).toList();
        long nanos = snapshot.remainingNanos();
        var dto = new ProtocolMessage.State(header, snapshot.revision(), snapshot.currentFloor(), snapshot.nextFloor(),
                snapshot.direction(), snapshot.phase(), snapshot.suspendedPhase(),
                snapshot.holdReason().map(r -> ProtocolMessage.HoldReason.valueOf(r.name())),
                ProtocolMessage.Mode.valueOf(snapshot.mode().name()), nanos == 0 ? 0 : (nanos - 1) / 1_000_000 + 1,
                internal, external);
        try { wire(dto); return true; }
        catch (ProtocolCodec.EncodingException error) { return false; }
    }

    private TextFrame encode(Command command) {
        Objects.requireNonNull(command, "command");
        Instant createdAt = command instanceof Command.Pickup pickup ? pickup.request().getCreatedAt()
                : command instanceof Command.Destination destination ? destination.request().getCreatedAt() : Instant.EPOCH;
        return new TextFrame(protocol.encodeSyntax(ProtocolCommands.fromDomain(command)), createdAt);
    }

    /** Solo se invoca en el hilo del actor, también al rechazar entradas abandonadas. */
    private Decoded decode(TextFrame frame) {
        ProtocolDecodeResult parsed = protocol.decodeCommand(frame.text());
        ProtocolReception previous = protocolReception.get();
        protocolReception.set(new ProtocolReception(previous.frames() + 1,
                previous.malformed() + (parsed.isSuccess() ? 0 : 1), Thread.currentThread().getName()));
        processingOrigin = frame.origin();
        if (parsed.isSuccess()) {
            ProtocolMessage message = parsed.message().orElseThrow();
            if (frame.origin() != Origin.LEGACY) {
                boolean authorized = frame.origin() == Origin.MANAGER ? message instanceof ProtocolMessage.Pickup
                        : !(message instanceof ProtocolMessage.Pickup);
                if (!authorized) { wire(new ProtocolMessage.Error(message.header(), ErrorCode.UNAUTHORIZED_CONTROL)); return new Decoded(null, null); }
                if (message.header().elevatorId() != state.getId()) { wire(new ProtocolMessage.Error(message.header(), ErrorCode.UNKNOWN_ELEVATOR)); return new Decoded(null, null); }
                if (identities.conflicts(message)) { wire(new ProtocolMessage.Error(message.header(), ErrorCode.ID_CONFLICT)); return new Decoded(null, null); }
                ProtocolMessage previousCommand = admittedProtocol.get(message.header().id());
                if (previousCommand != null) {
                    if (!previousCommand.equals(message)) { wire(new ProtocolMessage.Error(message.header(), ErrorCode.ID_CONFLICT)); }
                    else { replayProtocol(message); }
                    return new Decoded(null, null);
                }
                boolean reset = message instanceof ProtocolMessage.Control c && c.kind() == ProtocolMessage.ControlKind.RESET;
                boolean shutdown = message instanceof ProtocolMessage.Control c && c.kind() == ProtocolMessage.ControlKind.SHUTDOWN;
                long expected = state.getEpoch();
                if (reset && message.header().epoch() == expected) {
                    wire(new ProtocolMessage.Error(message.header(), ErrorCode.INVALID_STATE)); return new Decoded(null, null);
                }
                if (reset && expected < Long.MAX_VALUE) { expected++; }
                if (!shutdown && message.header().epoch() != expected) {
                    wire(new ProtocolMessage.Error(message.header(), message.header().epoch() < expected
                            ? ErrorCode.STALE_EPOCH : ErrorCode.FUTURE_EPOCH)); return new Decoded(null, null);
                }
            }
            if (message instanceof ProtocolMessage.Control control && control.kind() != ProtocolMessage.ControlKind.RESET) {
                if (!frame.control() || frame.origin() != Origin.CONTROLLER) {
                    wire(new ProtocolMessage.Error(message.header(), frame.origin() == Origin.MANAGER
                            ? ErrorCode.UNAUTHORIZED_CONTROL : ErrorCode.INVALID_STATE));
                } else { handleControl(control); }
                return new Decoded(null, null);
            }
            if (frame.control() && !(message instanceof ProtocolMessage.Control)) {
                wire(new ProtocolMessage.Error(message.header(), ErrorCode.INVALID_STATE)); return new Decoded(null, null);
            }
            if (message instanceof ProtocolMessage.Pickup || message instanceof ProtocolMessage.Destination
                    || message instanceof ProtocolMessage.Control control
                    && control.kind() == ProtocolMessage.ControlKind.RESET) {
                return new Decoded(ProtocolCommands.toDomain(message, frame.createdAt()), null);
            }
            wire(new ProtocolMessage.Error(message.header(), ErrorCode.INVALID_STATE));
            return new Decoded(null, null); // Controles adicionales se integran en T65.
        }
        ProtocolDecodeResult.Failure error = parsed.failure().orElseThrow();
        // Conserva Result/ExecutionEvent para clientes tipados antiguos, reconstruidos
        // del texto; el DTO de esta segunda lectura NUNCA se admite ni mueve la cabina.
        ProtocolDecodeResult syntax = protocol.decodeCommandSyntax(frame.text());
        if (syntax.isSuccess()) {
            ProtocolMessage message = syntax.message().orElseThrow();
            if (message instanceof ProtocolMessage.Pickup || message instanceof ProtocolMessage.Destination
                    || message instanceof ProtocolMessage.Control control
                    && control.kind() == ProtocolMessage.ControlKind.RESET) {
                Command command = ProtocolCommands.toDomain(message, frame.createdAt());
                Result rejected = new Result(command, Outcome.REJECTED, error.detail());
                emitAdmission(rejected, nowNanos(), error.code());
                return new Decoded(null, rejected);
            }
        }
        wire(error.asErrorMessage());
        return new Decoded(null, null);
    }

    private Result rejectServiceControl(Command command, long now) {
        Result rejected = new Result(command, Outcome.REJECTED, "RESET requiere el buzón prioritario.");
        emitAdmission(rejected, now, ErrorCode.INVALID_STATE);
        return rejected;
    }

    private void processResetFrame(ResetFrame frame, long now, List<Runnable> deferred) {
        Decoded decoded = decode(frame.frame());
        if (decoded.command() instanceof Command.Reset reset) {
            processReset(new ResetTicket(reset, frame.response()), now, deferred);
        } else {
            Result rejected = decoded.rejection();
            if (decoded.command() != null) {
                rejected = new Result(decoded.command(), Outcome.REJECTED, "El buzón prioritario solo admite RESET.");
                emitAdmission(rejected, now, ErrorCode.INVALID_STATE);
            }
            Result result = rejected;
            if (result != null) { deferred.add(() -> results.add(result)); }
            deferred.add(() -> frame.response().completeExceptionally(
                    new IllegalArgumentException("Mensaje RESET inválido; consultar respuesta de protocolo.")));
        }
    }

    private void rejectAbandoned(TextFrame frame) {
        Decoded decoded = decode(frame);
        if (decoded.command() != null) {
            Result rejected = new Result(decoded.command(), Outcome.REJECTED, "Trabajador finalizado.");
            emitAdmission(rejected, nowNanos(), ErrorCode.WORKER_UNAVAILABLE);
            results.add(rejected);
        } else if (decoded.rejection() != null) { results.add(decoded.rejection()); }
    }

    private ErrorCode rejectionCode(Command command) {
        if (command.elevatorId() != state.getId()) { return ErrorCode.UNKNOWN_ELEVATOR; }
        if (command.epoch() < state.getEpoch()) { return ErrorCode.STALE_EPOCH; }
        if (command instanceof Command.Reset) { return ErrorCode.INVALID_STATE; }
        if (command.epoch() > state.getEpoch()) { return ErrorCode.FUTURE_EPOCH; }
        return ErrorCode.ID_CONFLICT;
    }

    private static ProtocolMessage.Header header(Command command) {
        return new ProtocolMessage.Header(command.commandId(), command.epoch(), command.elevatorId());
    }

    private void wire(ProtocolMessage message) {
        if (message instanceof ProtocolMessage.State state && state.header().id() > 0) {
            wireStates.put(new WireKey(state.header().epoch(), state.header().id()), state);
        }
        if (message instanceof ProtocolMessage.Terminal terminal) {
            wireTerminals.putIfAbsent(new WireKey(terminal.header().epoch(), terminal.header().id()), terminal);
        }
        protocolBatch.add(protocol.encode(message));
    }

    private void completed(Command command) {
        if (command instanceof Command.Reset) { wireState(header(command)); }
        wire(new ProtocolMessage.Terminal(header(command), ProtocolMessage.TerminalKind.COMPLETED,
                Optional.empty(), Optional.empty()));
    }

    /** Admisión del control: cierre > reset > Stop > servicio. Solo escribe el actor. */
    private void processReset(ResetTicket ticket, long now, List<Runnable> deferred) {
        Command.Reset command = ticket.command();
        Origin resetOrigin = processingOrigin;
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
        if ((state.isResetting() && processingOrigin == Origin.LEGACY) || command.epoch() <= state.getEpoch()) {
            rejectReset(ticket, "Reset en curso o época no creciente.", now, deferred);
            return;
        }
        if (processingOrigin != Origin.LEGACY && !identities.reserve(ProtocolCommands.fromDomain(command))) {
            var rejected = new Result(command, Outcome.REJECTED, "ID de sesión en conflicto.");
            emitAdmission(rejected, now, ErrorCode.ID_CONFLICT); deferred.add(() -> results.add(rejected));
            deferred.add(() -> ticket.response().completeExceptionally(new IllegalArgumentException("ID_CONFLICT"))); return;
        }
        if (state.isResetting()) {
            var oldReset = lastReset;
            wire(new ProtocolMessage.Terminal(header(oldReset.command()), ProtocolMessage.TerminalKind.CANCELLED,
                    Optional.of(ProtocolMessage.CancelReason.SUPERSEDED), Optional.empty()));
            deferred.add(() -> oldReset.completion().completeExceptionally(new IllegalStateException("Reset sustituido.")));
        }
        cancelControls(ProtocolMessage.CancelReason.SUPERSEDED);
        // Cancelaciones conservan el snapshot y la correlación de la época anterior.
        for (var cancelled : ledger.clearForReset()) {
            eventBatch.add(new ExecutionEvent(++eventSequence, ExecutionEvent.Type.COMMAND_CANCELLED,
                    state.snapshot(now), Optional.of(cancelled.command()),
                    Optional.of(cancelled.canonicalRequestId()), "Cancelado por reset prioritario."));
            wire(new ProtocolMessage.Terminal(header(cancelled.command()), ProtocolMessage.TerminalKind.CANCELLED,
                    Optional.of(ProtocolMessage.CancelReason.RESET), Optional.empty()));
        }
        while (!inbox.isEmpty()) {
            Decoded decoded = decode(inbox.removeFirst());
            if (decoded.command() != null) {
                var rejected = new Result(decoded.command(), Outcome.REJECTED, "Descartado del inbox por reset.");
                emitAdmission(rejected, now, ErrorCode.STALE_EPOCH);
                deferred.add(() -> results.add(rejected));
            } else if (decoded.rejection() != null) {
                deferred.add(() -> results.add(decoded.rejection()));
            }
        }
        if (stopConfirmation != null && !stopConfirmation.isDone()) {
            var replaced = stopConfirmation;
            deferred.add(() -> replaced.completeExceptionally(new IllegalStateException("Stop sustituido por reset.")));
        }
        processingOrigin = resetOrigin;
        stopRequested = false;
        stopped = false;
        resumeRequested = false;
        resetHeld = true;
        completedSweep = Optional.empty();
        if (state.isResetting()) { state.supersedeReset(command.epoch()); }
        else { state.beginReset(command.epoch()); }
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
        if (type == ExecutionEvent.Type.RESET_COMPLETED) { completed(lastReset.command()); }
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
        ProtocolMessage dto = ProtocolCommands.fromDomain(command);
        if (processingOrigin != Origin.LEGACY && !identities.reserve(dto)) {
            var rejected = new Result(command, Outcome.REJECTED, "ID de sesión en conflicto.");
            emitAdmission(rejected, nowNanos(), ErrorCode.ID_CONFLICT); return rejected;
        }
        CommandLedger.Decision decision = ledger.admit(command);
        if (processingOrigin != Origin.LEGACY && decision.kind() == CommandLedger.Kind.REJECTED) { identities.release(dto); }
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
        emitAdmission(result, now, rejectionCode(result.command()));
    }

    private void emitAdmission(Result result, long now, ErrorCode rejection) {
        WireKey key = new WireKey(result.command().epoch(), result.command().commandId());
        if (result.outcome() == Outcome.REJECTED) {
            wire(new ProtocolMessage.Error(header(result.command()), rejection));
        } else {
            if (processingOrigin != Origin.LEGACY) {
                ProtocolMessage dto = ProtocolCommands.fromDomain(result.command());
                identities.reserve(dto); admittedProtocol.put(dto.header().id(), dto);
            }
            ProtocolMessage ack = wireAdmissions.get(key);
            if (!result.replay() || ack == null) {
                boolean coalesced = result.outcome() == Outcome.DUPLICATE && !result.replay()
                        && result.canonicalRequestId().isPresent();
                ack = new ProtocolMessage.Ack(header(result.command()), coalesced
                        ? ProtocolMessage.AckKind.COALESCED : ProtocolMessage.AckKind.ACCEPTED,
                        coalesced ? result.canonicalRequestId() : Optional.empty());
                wireAdmissions.put(key, ack);
            }
            wire(ack);
            if (result.replay() && wireTerminals.containsKey(key)) { wire(wireTerminals.get(key)); }
        }
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
            completed(completion.command());
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
        ExecutionEvent event = new ExecutionEvent(++eventSequence, type, state.snapshot(now),
                Optional.empty(), Optional.empty(), "");
        eventBatch.add(event);
        var snapshot = event.snapshot();
        var h = new ProtocolMessage.Header(event.sequence(), event.epoch(), event.elevatorId());
        switch (type) {
            case DEPARTED -> wire(new ProtocolMessage.Move(h, snapshot.currentFloor(),
                    snapshot.nextFloor().orElseThrow(), snapshot.direction()));
            case ARRIVED -> wire(new ProtocolMessage.Arrived(h, snapshot.currentFloor()));
            case DIRECTION_CHANGED -> wire(new ProtocolMessage.DirectionChanged(h,
                    snapshot.direction().opposite(), snapshot.direction()));
            case STOP_STARTED -> wire(new ProtocolMessage.StoppedAt(h,
                    snapshot.currentFloor(), snapshot.direction()));
            default -> { }
        }
    }

    /** Sin lock del buzón, callbacks, I/O ni espera de consumidores. */
    private void flushEvents() {
        for (ExecutionEvent event : eventBatch) {
            events.add(event);
            trace.cabin(event, failure);
            for (var subscriber : subscribers) { subscriber.publish(event); }
        }
        eventBatch.clear();
        if (!admittedProtocol.isEmpty() && lifecycle == Lifecycle.RUNNING) {
            wireState(new ProtocolMessage.Header(0, state.getEpoch(), state.getId()));
        }
        for (String frame : protocolBatch) {
            protocolOutput.publish(frame);
            for (ProtocolMailbox subscriber : protocolSubscribers) { subscriber.publish(frame); }
        }
        protocolBatch.clear();
    }

    private static long timeoutNanos(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative()) { throw new IllegalArgumentException("Tiempo negativo."); }
        return timeout.toNanos();
    }
}
