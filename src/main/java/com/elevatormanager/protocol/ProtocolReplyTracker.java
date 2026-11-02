package com.elevatormanager.protocol;

import com.elevatormanager.model.SimulationConfig;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import static com.elevatormanager.protocol.ProtocolMessage.*;

/** Correlación por sesión/ID/cabina/época; completar un futuro nunca se hace bajo el monitor. */
public final class ProtocolReplyTracker {
    public record Reply(Header header, Ack ack, Optional<State> state, Terminal terminal) { }
    private static final class Pending {
        final ProtocolMessage command;
        final CompletableFuture<Reply> future = new CompletableFuture<>();
        Ack ack;
        State state;
        Pending(ProtocolMessage command) { this.command = command; }
    }
    private final ProtocolCodec codec;
    private final Map<Long, Pending> pending = new HashMap<>();
    private final Map<Integer, State> current = new HashMap<>();
    private long epoch;
    private boolean closed;
    private long malformed;
    private long ignored;

    public ProtocolReplyTracker(SimulationConfig config, long epoch) {
        codec = new ProtocolCodec(config);
        if (epoch < 0) { throw new IllegalArgumentException("Época negativa."); }
        this.epoch = epoch;
    }

    public CompletableFuture<Reply> register(ProtocolMessage command) {
        Objects.requireNonNull(command, "command");
        if (!(command instanceof Control || command instanceof Pickup || command instanceof Destination)) {
            throw new IllegalArgumentException("Se requiere una orden.");
        }
        codec.encode(command);
        synchronized (this) {
            if (closed) { throw new IllegalStateException("Canal cerrado."); }
            Pending old = pending.get(command.header().id());
            if (old != null) {
                if (!old.command.equals(command)) { throw new IllegalArgumentException("ID de comando en conflicto."); }
                return old.future.copy();
            }
            Pending entry = new Pending(command); pending.put(command.header().id(), entry);
            return entry.future.copy();
        }
    }

    public synchronized void adoptEpoch(long value) {
        if (value < epoch) { throw new IllegalArgumentException("La época no retrocede."); }
        if (value != epoch) { epoch = value; current.clear(); }
    }

    public synchronized Optional<State> state(int elevatorId) { return Optional.ofNullable(current.get(elevatorId)); }
    public synchronized long malformed() { return malformed; }
    public synchronized long ignored() { return ignored; }

    /** Respuestas viejas pueden cerrar su futuro histórico; nunca instalan estado vigente. */
    public void receive(String text) {
        var parsed = codec.decodeResponse(text);
        if (!parsed.isSuccess()) { synchronized (this) { malformed++; } return; }
        ProtocolMessage m = parsed.message().orElseThrow();
        Pending complete = null; Reply reply = null; Throwable failure = null;
        synchronized (this) {
            if (closed) { ignored++; return; }
            Pending entry = pending.get(m.header().id());
            boolean correlated = entry != null && entry.command.header().equals(m.header());
            if (m instanceof State state) {
                // Un STATE correlacionado solo vale si fue solicitado; el espontáneo usa ID 0.
                if (state.header().id() == 0 || correlated && entry.ack != null && !entry.future.isDone()) {
                    State old = current.get(state.header().elevatorId());
                    if (state.header().epoch() == epoch && (old == null || state.revision() >= old.revision())) {
                        current.put(state.header().elevatorId(), state);
                    }
                    if (correlated && entry.ack != null) { entry.state = state; }
                } else { ignored++; }
                return;
            }
            if (!correlated || entry.future.isDone()) { ignored++; return; }
            if (m instanceof Ack ack) {
                if (entry.command instanceof Control && ack.kind() != AckKind.ACCEPTED) { ignored++; return; }
                if (entry.ack == null) { entry.ack = ack; } return;
            }
            if (m instanceof ProtocolMessage.Error error) {
                complete = entry; failure = new Rejected(error);
            } else if (m instanceof Terminal terminal) {
                if (entry.ack == null) { ignored++; return; }
                if (terminal.kind() != TerminalKind.COMPLETED) {
                    complete = entry; failure = new IllegalStateException("Orden " + terminal.header().id()
                            + ": " + terminal.kind() + " " + terminal.reason() + " " + terminal.error());
                } else {
                    boolean needsState = entry.command instanceof Control c
                            && (c.kind() == ControlKind.STATUS || c.kind() == ControlKind.RESET);
                    if (needsState && entry.state == null) { ignored++; return; }
                    if (entry.command instanceof Control c && c.kind() == ControlKind.RESET
                            && (entry.state.floor() != 1 || entry.state.direction() != com.elevatormanager.model.Direction.UP
                            || entry.state.phase() != com.elevatormanager.model.ElevatorPhase.IDLE
                            || entry.state.mode() != Mode.NORMAL || !entry.state.internal().isEmpty()
                            || !entry.state.external().isEmpty())) { ignored++; return; }
                    complete = entry; reply = new Reply(m.header(), entry.ack, Optional.ofNullable(entry.state), terminal);
                }
            }
        }
        if (complete != null) {
            if (failure == null) { complete.future.complete(reply); }
            else { complete.future.completeExceptionally(failure); }
        }
    }

    public void fail(long id, Throwable error) {
        Pending entry; synchronized (this) { entry = pending.get(id); }
        if (entry != null) { entry.future.completeExceptionally(error); }
    }

    public void close() {
        List<Pending> unfinished;
        synchronized (this) { closed = true; unfinished = pending.values().stream().filter(p -> !p.future.isDone()).toList(); }
        for (Pending entry : unfinished) { entry.future.completeExceptionally(new IllegalStateException("Canal de respuestas cerrado.")); }
    }

    public static final class Rejected extends IllegalStateException {
        private static final long serialVersionUID = 1L;
        private final ProtocolMessage.Error response;
        public Rejected(ProtocolMessage.Error response) { super(response.code().name()); this.response = response; }
        public ProtocolMessage.Error response() { return response; }
    }
}
