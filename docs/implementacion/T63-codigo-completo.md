# T63 — Código completo

Fecha: 2026-11-02. Cada bloque reproduce un archivo Java íntegro de esta entrega, sin omisiones. Las rutas son relativas a la raíz Maven. El ZIP incluye además todo el proyecto anterior.

## src/main/java/com/elevatormanager/protocol/ProtocolMessage.java

```java
package com.elevatormanager.protocol;

import com.elevatormanager.model.Direction;
import com.elevatormanager.model.ElevatorPhase;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;

/** DTOs inmutables V1: identidad y contenido operativo, sin fechas ni estado mutable. */
public sealed interface ProtocolMessage permits ProtocolMessage.Pickup,
        ProtocolMessage.Destination, ProtocolMessage.Control, ProtocolMessage.Ack,
        ProtocolMessage.Error, ProtocolMessage.Terminal, ProtocolMessage.Move,
        ProtocolMessage.Arrived, ProtocolMessage.DirectionChanged,
        ProtocolMessage.StoppedAt, ProtocolMessage.State {
    Header header();

    /** id es cmdId o seq según la familia; 0 solo en ERROR/STATE. */
    record Header(long id, long epoch, int elevatorId) {
        public static final Header UNKNOWN = new Header(0, 0, 0);
        public Header {
            if (id < 0 || epoch < 0 || elevatorId < 0) {
                throw new IllegalArgumentException("Cabecera negativa.");
            }
        }
    }

    enum ControlKind { RESET, STOP, START, PAUSE, RESUME, STATUS, SHUTDOWN }
    enum AckKind { ACCEPTED, COALESCED }
    enum TerminalKind { COMPLETED, CANCELLED, FAILED }
    enum CancelReason { RESET, SHUTDOWN, SUPERSEDED }
    enum HoldReason { STOP, PAUSE }
    enum Mode { NORMAL, RESET_RETURN }
    enum ErrorCode {
        INVALID_FORMAT, UNSUPPORTED_VERSION, UNKNOWN_COMMAND, INVALID_ARGUMENT,
        INVALID_FLOOR, INVALID_DIRECTION, UNKNOWN_ELEVATOR, STALE_EPOCH,
        FUTURE_EPOCH, INVALID_STATE, ID_CONFLICT, WORKER_UNAVAILABLE,
        INTERNAL_ERROR, MESSAGE_TOO_LONG, UNAUTHORIZED_CONTROL
    }

    record Pickup(Header header, UUID requestId, int floor, Direction direction)
            implements ProtocolMessage {
        public Pickup {
            correlated(header);
            Objects.requireNonNull(requestId, "requestId");
            positiveFloor(floor);
            Objects.requireNonNull(direction, "direction");
        }
    }

    record Destination(Header header, UUID requestId, int floor) implements ProtocolMessage {
        public Destination {
            correlated(header);
            Objects.requireNonNull(requestId, "requestId");
            positiveFloor(floor);
        }
    }

    record Control(Header header, ControlKind kind) implements ProtocolMessage {
        public Control { correlated(header); Objects.requireNonNull(kind, "kind"); }
    }

    record Ack(Header header, AckKind kind, Optional<UUID> canonicalRequestId)
            implements ProtocolMessage {
        public Ack {
            correlated(header);
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(canonicalRequestId, "canonicalRequestId");
            if ((kind == AckKind.COALESCED) != canonicalRequestId.isPresent()) {
                throw new IllegalArgumentException("Solo COALESCED tiene UUID canónico.");
            }
        }
    }

    record Error(Header header, ErrorCode code) implements ProtocolMessage {
        public Error {
            Objects.requireNonNull(header, "header");
            Objects.requireNonNull(code, "code");
            if (!header.equals(Header.UNKNOWN)) { correlated(header); }
        }
    }

    record Terminal(Header header, TerminalKind kind, Optional<CancelReason> reason,
            Optional<ErrorCode> error) implements ProtocolMessage {
        public Terminal {
            correlated(header);
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(reason, "reason");
            Objects.requireNonNull(error, "error");
            if ((kind == TerminalKind.CANCELLED) != reason.isPresent()
                    || (kind == TerminalKind.FAILED) != error.isPresent()) {
                throw new IllegalArgumentException("Resultado terminal incoherente.");
            }
        }
    }

    record Move(Header header, int from, int to, Direction direction) implements ProtocolMessage {
        public Move {
            correlated(header); positiveFloor(from); positiveFloor(to);
            Objects.requireNonNull(direction, "direction");
            if ((long) from + direction.floorDelta() != to) {
                throw new IllegalArgumentException("MOVE exige un tramo adyacente coherente.");
            }
        }
    }

    record Arrived(Header header, int floor) implements ProtocolMessage {
        public Arrived { correlated(header); positiveFloor(floor); }
    }

    record DirectionChanged(Header header, Direction from, Direction to)
            implements ProtocolMessage {
        public DirectionChanged {
            correlated(header);
            Objects.requireNonNull(from, "from"); Objects.requireNonNull(to, "to");
            if (from == to) { throw new IllegalArgumentException("No hubo cambio de dirección."); }
        }
    }

    /** SERVICE es sentido de la parada, también en destinos internos extremos. */
    record StoppedAt(Header header, int floor, Direction service) implements ProtocolMessage {
        public StoppedAt {
            correlated(header); positiveFloor(floor); Objects.requireNonNull(service, "service");
        }
    }

    record ExternalCall(int floor, Direction direction) {
        public ExternalCall { positiveFloor(floor); Objects.requireNonNull(direction, "direction"); }
    }

    /** Proyección pública: no reemplaza ElevatorSnapshot ni el reloj de animación. */
    record State(Header header, long revision, int floor, OptionalInt next,
            Direction direction, ElevatorPhase phase, Optional<ElevatorPhase> suspended,
            Optional<HoldReason> hold, Mode mode, long remainingMillis,
            List<Integer> internal, List<ExternalCall> external) implements ProtocolMessage {
        public State {
            Objects.requireNonNull(header, "header");
            if (header.elevatorId() < 1 || revision < 0 || remainingMillis < 0) {
                throw new IllegalArgumentException("Identidad/revisión/restante de STATE inválidos.");
            }
            positiveFloor(floor);
            Objects.requireNonNull(next, "next");
            Objects.requireNonNull(direction, "direction"); Objects.requireNonNull(phase, "phase");
            Objects.requireNonNull(suspended, "suspended"); Objects.requireNonNull(hold, "hold");
            Objects.requireNonNull(mode, "mode");
            internal = List.copyOf(internal); external = List.copyOf(external);
            ElevatorPhase effective = phase;
            if (phase == ElevatorPhase.HELD) {
                if (suspended.isEmpty() || hold.isEmpty()
                        || (suspended.get() != ElevatorPhase.MOVING
                        && suspended.get() != ElevatorPhase.DWELLING)) {
                    throw new IllegalArgumentException("HELD exige fase suspendida y motivo.");
                }
                effective = suspended.get();
                if (hold.get() == HoldReason.STOP && effective == ElevatorPhase.MOVING) {
                    throw new IllegalArgumentException("STOP no congela un tramo.");
                }
            } else if (suspended.isPresent() || hold.isPresent()) {
                throw new IllegalArgumentException("Solo HELD tiene suspensión/motivo.");
            }
            if (effective == ElevatorPhase.MOVING) {
                if (next.isEmpty() || (long) floor + direction.floorDelta() != next.getAsInt()
                        || next.getAsInt() < 1) {
                    throw new IllegalArgumentException("NEXT exige tramo adyacente coherente.");
                }
            } else if (next.isPresent()) {
                throw new IllegalArgumentException("NEXT solo pertenece a movimiento.");
            }
            if ((effective == ElevatorPhase.IDLE || effective == ElevatorPhase.TERMINATED)
                    && remainingMillis != 0) {
                throw new IllegalArgumentException("Fase sin reloj con restante.");
            }
            if ((mode == Mode.RESET_RETURN || phase == ElevatorPhase.TERMINATED)
                    && (!internal.isEmpty() || !external.isEmpty())) {
                throw new IllegalArgumentException("Reset/terminación no conservan servicio pendiente.");
            }
            int previous = 0;
            for (int item : internal) {
                if (item <= previous) {
                    throw new IllegalArgumentException("INTERNAL debe ser único y ascendente.");
                }
                previous = item;
            }
            ExternalCall prior = null;
            for (ExternalCall item : external) {
                if (prior != null && (item.floor() < prior.floor()
                        || (item.floor() == prior.floor()
                        && item.direction().ordinal() <= prior.direction().ordinal()))) {
                    throw new IllegalArgumentException("EXTERNAL debe ser único y ordenado UP/DOWN.");
                }
                prior = item;
            }
        }
    }

    private static void correlated(Header header) {
        Objects.requireNonNull(header, "header");
        if (header.id() < 1 || header.elevatorId() < 1) {
            throw new IllegalArgumentException("ID y destinatario deben ser positivos.");
        }
    }

    private static void positiveFloor(int floor) {
        if (floor < 1) { throw new IllegalArgumentException("Piso no positivo."); }
    }
}
```

## src/main/java/com/elevatormanager/protocol/ProtocolDecodeResult.java

```java
package com.elevatormanager.protocol;

import java.util.Objects;
import java.util.Optional;

/** El parser no lanza por entrada malformada ni genera respuestas automáticamente. */
public record ProtocolDecodeResult(Optional<ProtocolMessage> message, Optional<Failure> failure) {
    public ProtocolDecodeResult {
        Objects.requireNonNull(message, "message"); Objects.requireNonNull(failure, "failure");
        if (message.isPresent() == failure.isPresent()) {
            throw new IllegalArgumentException("Debe existir mensaje o fallo, exclusivamente.");
        }
    }

    public boolean isSuccess() { return message.isPresent(); }

    public static ProtocolDecodeResult success(ProtocolMessage message) {
        return new ProtocolDecodeResult(Optional.of(message), Optional.empty());
    }

    public static ProtocolDecodeResult rejected(Failure failure) {
        return new ProtocolDecodeResult(Optional.empty(), Optional.of(failure));
    }

    /** Vista limitada y escapada; no retiene la línea original ni Throwable arbitrario. */
    public record Failure(ProtocolMessage.ErrorCode code, ProtocolMessage.Header header,
            String detail, String preview) {
        public Failure {
            Objects.requireNonNull(code, "code"); Objects.requireNonNull(header, "header");
            Objects.requireNonNull(detail, "detail"); Objects.requireNonNull(preview, "preview");
            if (detail.length() > 256 || preview.length() > 256) {
                throw new IllegalArgumentException("Diagnóstico demasiado extenso.");
            }
            new ProtocolMessage.Error(header, code);
        }

        /** Solo el receptor de órdenes decide enviarlo; nunca contestar ERROR a ERROR. */
        public ProtocolMessage.Error asErrorMessage() { return new ProtocolMessage.Error(header, code); }
    }
}
```

## src/main/java/com/elevatormanager/protocol/ProtocolCodec.java

```java
package com.elevatormanager.protocol;

import com.elevatormanager.model.Direction;
import com.elevatormanager.model.ElevatorPhase;
import com.elevatormanager.model.SimulationConfig;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.regex.Pattern;
import static com.elevatormanager.protocol.ProtocolMessage.*;

/** Codec V1 sin estado de sesión, seguro para compartir entre hilos. No ejecuta órdenes. */
public final class ProtocolCodec {
    public static final int MAX_FRAME_BYTES = 1_048_576;
    private static final int MAX_TOKENS = 29;
    private static final Pattern UUID_TOKEN = Pattern.compile(
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private final SimulationConfig config;

    public ProtocolCodec(SimulationConfig config) { this.config = Objects.requireNonNull(config, "config"); }

    /** Decodifica cualquier familia V1; la validación de admisión corresponde al receptor. */
    public ProtocolDecodeResult decode(String input) { return decode(input, Channel.ANY); }

    /** Frontera de órdenes: rechaza respuestas en vez de ejecutarlas accidentalmente. */
    public ProtocolDecodeResult decodeCommand(String input) { return decode(input, Channel.COMMAND); }

    /** Frontera de respuestas: informa un fallo local, nunca envía otro ERROR. */
    public ProtocolDecodeResult decodeResponse(String input) { return decode(input, Channel.RESPONSE); }

    private enum Channel { ANY, COMMAND, RESPONSE }

    private ProtocolDecodeResult decode(String input, Channel channel) {
        Header correlation = Header.UNKNOWN;
        try {
            Frame frame = frame(input);
            List<String> t = frame.tokens();
            String version = t.get(0);
            if (!version.equals("V1")) {
                if (version.matches("V[0-9]+")) {
                    throw problem(ErrorCode.UNSUPPORTED_VERSION, "Solo se admite V1.");
                }
                throw problem(ErrorCode.INVALID_FORMAT, "Token de versión inválido.");
            }
            correlation = recover(t);
            if (t.size() < 2) { throw problem(ErrorCode.INVALID_FORMAT, "Falta familia."); }
            String family = t.get(1);
            boolean isCommand = family.equals("CMD");
            if ((channel == Channel.COMMAND && !isCommand)
                    || (channel == Channel.RESPONSE && isCommand)) {
                throw problem(ErrorCode.INVALID_FORMAT, "Familia incompatible con el canal.");
            }
            if (isCommand && correlation.equals(Header.UNKNOWN)) {
                throw problem(ErrorCode.INVALID_FORMAT, "Cabecera CMD incompleta o inválida.");
            }
            if (!isCommand && !List.of("ACK", "ERROR", "EVENT", "MOVE", "ARRIVED",
                    "DIRECTION", "STOPPED_AT", "STATE").contains(family)) {
                throw problem(ErrorCode.INVALID_FORMAT, "Familia desconocida.");
            }
            Header header = isCommand ? correlation : header(t, family);
            if (!isCommand && header.id() > 0 && header.elevatorId() > 0) { correlation = header; }
            if (t.size() < 8) { throw problem(ErrorCode.INVALID_FORMAT, "Falta cuerpo."); }
            ProtocolMessage message = parse(frame, family, header);
            validate(message);
            return ProtocolDecodeResult.success(message);
        } catch (Problem error) {
            return ProtocolDecodeResult.rejected(failure(error.code, correlation, error.getMessage(), input));
        } catch (IllegalArgumentException error) {
            return ProtocolDecodeResult.rejected(failure(ErrorCode.INVALID_ARGUMENT,
                    correlation, "Campos o combinaciones incoherentes.", input));
        }
    }

    /** Emite una línea canónica, sin terminador. Falla explícitamente sin truncar. */
    public String encode(ProtocolMessage message) {
        Header correlation = message == null ? Header.UNKNOWN : safeCorrelation(message.header());
        try {
            if (message == null) { throw problem(ErrorCode.INVALID_ARGUMENT, "Mensaje nulo."); }
            validate(message);
            Writer w = new Writer();
            Header h = message.header();
            w.add("V1", family(message), h.id(), "EPOCH", h.epoch(), "ELEVATOR", h.elevatorId());
            if (message instanceof Pickup m) { w.add("PICKUP", m.requestId(), m.floor(), m.direction()); }
            else if (message instanceof Destination m) { w.add("DESTINATION", m.requestId(), m.floor()); }
            else if (message instanceof Control m) { w.add(m.kind()); }
            else if (message instanceof Ack m) {
                w.add(m.kind()); m.canonicalRequestId().ifPresent(w::add);
            } else if (message instanceof ProtocolMessage.Error m) { w.add(m.code()); }
            else if (message instanceof Terminal m) {
                w.add(m.kind()); m.reason().ifPresent(w::add); m.error().ifPresent(w::add);
            } else if (message instanceof Move m) { w.add("FROM", m.from(), "TO", m.to(), "DIRECTION", m.direction()); }
            else if (message instanceof Arrived m) { w.add("FLOOR", m.floor()); }
            else if (message instanceof DirectionChanged m) { w.add("FROM", m.from(), "TO", m.to()); }
            else if (message instanceof StoppedAt m) { w.add("FLOOR", m.floor(), "SERVICE", m.service()); }
            else if (message instanceof State m) {
                w.add("REV", m.revision(), "FLOOR", m.floor(), "NEXT", floorToken(m.next()),
                        "DIRECTION", m.direction(), "PHASE", m.phase(),
                        "SUSPENDED", m.suspended().map(Enum::name).orElse("-"),
                        "HOLD", m.hold().map(Enum::name).orElse("-"), "MODE", m.mode(),
                        "REMAINING_MS", m.remainingMillis(), "INTERNAL");
                w.beginToken();
                if (m.internal().isEmpty()) { w.raw("-"); }
                for (int i = 0; i < m.internal().size(); i++) {
                    if (i > 0) { w.raw(","); } w.raw(Integer.toString(m.internal().get(i)));
                }
                w.add("EXTERNAL"); w.beginToken();
                if (m.external().isEmpty()) { w.raw("-"); }
                for (int i = 0; i < m.external().size(); i++) {
                    if (i > 0) { w.raw(","); }
                    ExternalCall call = m.external().get(i);
                    w.raw(call.floor() + ":" + call.direction());
                }
            }
            return w.text();
        } catch (Problem error) {
            throw new EncodingException(failure(error.code, correlation, error.getMessage(), null));
        }
    }

    /** Error controlado del emisor; la integración decide FAILED o diagnóstico local. */
    public static final class EncodingException extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;
        private final ProtocolDecodeResult.Failure failure;
        private EncodingException(ProtocolDecodeResult.Failure failure) {
            super(failure.code() + ": " + failure.detail()); this.failure = failure;
        }
        public ProtocolDecodeResult.Failure failure() { return failure; }
    }

    private ProtocolMessage parse(Frame frame, String family, Header h) {
        List<String> t = frame.tokens();
        switch (family) {
            case "CMD": {
                String verb = t.get(7);
                if (verb.equals("PICKUP") || verb.equals("DESTINATION")) {
                    arity(frame, verb.equals("PICKUP") ? 11 : 10);
                    UUID request = uuid(t.get(8)); int floor = integer(t.get(9));
                    Direction direction = verb.equals("PICKUP") ? direction(t.get(10)) : null;
                    target(h); floor(floor);
                    if (direction != null) { externalDirection(floor, direction); }
                    return direction == null ? new Destination(h, request, floor) : new Pickup(h, request, floor, direction);
                }
                ControlKind kind = value(ControlKind.class, verb, ErrorCode.UNKNOWN_COMMAND);
                arity(frame, 8); return new Control(h, kind);
            }
            case "ACK": {
                AckKind kind = value(AckKind.class, t.get(7), ErrorCode.INVALID_ARGUMENT);
                arity(frame, kind == AckKind.ACCEPTED ? 8 : 9);
                return new Ack(h, kind, kind == AckKind.ACCEPTED ? Optional.empty() : Optional.of(uuid(t.get(8))));
            }
            case "ERROR":
                arity(frame, 8); return new ProtocolMessage.Error(h, errorCode(t.get(7)));
            case "EVENT": {
                TerminalKind kind = value(TerminalKind.class, t.get(7), ErrorCode.INVALID_ARGUMENT);
                arity(frame, kind == TerminalKind.COMPLETED ? 8 : 9);
                return new Terminal(h, kind, kind == TerminalKind.CANCELLED
                        ? Optional.of(value(CancelReason.class, t.get(8), ErrorCode.INVALID_ARGUMENT)) : Optional.empty(),
                        kind == TerminalKind.FAILED ? Optional.of(errorCode(t.get(8))) : Optional.empty());
            }
            case "MOVE": {
                arity(frame, 13); labels(t, 7, "FROM", 9, "TO", 11, "DIRECTION");
                int from = integer(t.get(8)), to = integer(t.get(10)); Direction d = direction(t.get(12));
                target(h); floor(from); floor(to); return new Move(h, from, to, d);
            }
            case "ARRIVED": {
                arity(frame, 9); labels(t, 7, "FLOOR"); int f = integer(t.get(8));
                target(h); floor(f); return new Arrived(h, f);
            }
            case "DIRECTION":
                arity(frame, 11); labels(t, 7, "FROM", 9, "TO");
                return new DirectionChanged(h, direction(t.get(8)), direction(t.get(10)));
            case "STOPPED_AT": {
                arity(frame, 11); labels(t, 7, "FLOOR", 9, "SERVICE");
                int f = integer(t.get(8)); Direction d = direction(t.get(10));
                target(h); floor(f); return new StoppedAt(h, f, d);
            }
            case "STATE": {
                arity(frame, 29);
                labels(t, 7, "REV", 9, "FLOOR", 11, "NEXT", 13, "DIRECTION", 15, "PHASE",
                        17, "SUSPENDED", 19, "HOLD", 21, "MODE", 23, "REMAINING_MS",
                        25, "INTERNAL", 27, "EXTERNAL");
                long revision = number(t.get(8)); int f = integer(t.get(10));
                OptionalInt next = t.get(12).equals("-") ? OptionalInt.empty() : OptionalInt.of(integer(t.get(12)));
                Direction d = direction(t.get(14));
                ElevatorPhase phase = value(ElevatorPhase.class, t.get(16), ErrorCode.INVALID_ARGUMENT);
                Optional<ElevatorPhase> suspended = optional(ElevatorPhase.class, t.get(18));
                Optional<HoldReason> hold = optional(HoldReason.class, t.get(20));
                Mode mode = value(Mode.class, t.get(22), ErrorCode.INVALID_ARGUMENT);
                long remaining = number(t.get(24));
                List<Integer> internal = internal(t.get(26)); List<ExternalCall> external = external(t.get(28));
                target(h); floor(f); if (next.isPresent()) { floor(next.getAsInt()); }
                for (int item : internal) { floor(item); }
                for (ExternalCall call : external) { floor(call.floor()); }
                for (ExternalCall call : external) { externalDirection(call.floor(), call.direction()); }
                return new State(h, revision, f, next, d, phase, suspended, hold, mode, remaining, internal, external);
            }
            default: throw problem(ErrorCode.INVALID_FORMAT, "Familia desconocida.");
        }
    }

    private void validate(ProtocolMessage m) {
        if (!(m instanceof ProtocolMessage.Error)) { target(m.header()); }
        if (m instanceof Pickup v) { floor(v.floor()); externalDirection(v.floor(), v.direction()); }
        else if (m instanceof Destination v) { floor(v.floor()); }
        else if (m instanceof Move v) { floor(v.from()); floor(v.to()); }
        else if (m instanceof Arrived v) { floor(v.floor()); }
        else if (m instanceof StoppedAt v) { floor(v.floor()); }
        else if (m instanceof State v) {
            floor(v.floor()); if (v.next().isPresent()) { floor(v.next().getAsInt()); }
            for (int item : v.internal()) { floor(item); }
            for (ExternalCall call : v.external()) { floor(call.floor()); }
            for (ExternalCall call : v.external()) { externalDirection(call.floor(), call.direction()); }
            ElevatorPhase phase = v.suspended().orElse(v.phase());
            long duration = phase == ElevatorPhase.MOVING ? config.movementTimeMillis()
                    : phase == ElevatorPhase.DWELLING ? config.dwellTimeMillis() : 0;
            if (v.remainingMillis() > duration) {
                throw problem(ErrorCode.INVALID_ARGUMENT, "Restante supera duración configurada.");
            }
        }
    }

    private void target(Header h) {
        if (h.elevatorId() < 1 || h.elevatorId() > config.elevatorCount()) {
            throw problem(ErrorCode.UNKNOWN_ELEVATOR, "Cabina fuera de la configuración.");
        }
    }

    private void floor(int value) {
        if (value < 1 || value > config.floorCount()) {
            throw problem(ErrorCode.INVALID_FLOOR, "Piso fuera de la configuración.");
        }
    }

    private void externalDirection(int floor, Direction d) {
        if ((floor == 1 && d == Direction.DOWN) || (floor == config.floorCount() && d == Direction.UP)) {
            throw problem(ErrorCode.INVALID_DIRECTION, "Llamada externa imposible en el extremo.");
        }
    }

    private static Header header(List<String> t, String family) {
        if (t.size() < 7) { throw problem(ErrorCode.INVALID_FORMAT, "Cabecera incompleta."); }
        labels(t, 3, "EPOCH", 5, "ELEVATOR");
        Header h = new Header(number(t.get(2)), number(t.get(4)), integer(t.get(6)));
        if (family.equals("ERROR") && h.equals(Header.UNKNOWN)) { return h; }
        if (h.elevatorId() == 0 || (!family.equals("STATE") && h.id() == 0)) {
            throw problem(ErrorCode.INVALID_ARGUMENT, "Cabecera cero fuera de ERROR/STATE.");
        }
        return h;
    }

    private static Header recover(List<String> t) {
        if (t.size() < 7 || !t.get(0).equals("V1") || !t.get(1).equals("CMD")
                || !t.get(3).equals("EPOCH") || !t.get(5).equals("ELEVATOR")) { return Header.UNKNOWN; }
        try {
            Header h = new Header(number(t.get(2)), number(t.get(4)), integer(t.get(6)));
            return safeCorrelation(h);
        } catch (Problem error) { return Header.UNKNOWN; }
    }

    private static Header safeCorrelation(Header h) {
        return h.id() > 0 && h.elevatorId() > 0 ? h : Header.UNKNOWN;
    }

    private static long number(String token) {
        if (token.isEmpty() || (token.length() > 1 && token.charAt(0) == '0')) {
            throw problem(ErrorCode.INVALID_ARGUMENT, "Entero no canónico.");
        }
        long result = 0;
        for (int i = 0; i < token.length(); i++) {
            int digit = token.charAt(i) - '0';
            if (digit < 0 || digit > 9 || result > (Long.MAX_VALUE - digit) / 10) {
                throw problem(ErrorCode.INVALID_ARGUMENT, "Entero inválido o fuera de long.");
            }
            result = result * 10 + digit;
        }
        return result;
    }

    private static int integer(String token) {
        long n = number(token);
        if (n > Integer.MAX_VALUE) { throw problem(ErrorCode.INVALID_ARGUMENT, "Entero fuera de int."); }
        return (int) n;
    }

    private static UUID uuid(String token) {
        if (!UUID_TOKEN.matcher(token).matches()) { throw problem(ErrorCode.INVALID_ARGUMENT, "UUID no canónico."); }
        return UUID.fromString(token);
    }

    private static Direction direction(String token) {
        return value(Direction.class, token, ErrorCode.INVALID_DIRECTION);
    }

    private static ErrorCode errorCode(String token) { return value(ErrorCode.class, token, ErrorCode.INVALID_ARGUMENT); }

    private static <E extends Enum<E>> E value(Class<E> type, String token, ErrorCode code) {
        try { return Enum.valueOf(type, token); }
        catch (IllegalArgumentException error) { throw problem(code, "Valor fuera de enumeración."); }
    }

    private static <E extends Enum<E>> Optional<E> optional(Class<E> type, String token) {
        return token.equals("-") ? Optional.empty() : Optional.of(value(type, token, ErrorCode.INVALID_ARGUMENT));
    }

    private static List<Integer> internal(String token) {
        if (token.equals("-")) { return List.of(); }
        List<Integer> result = new ArrayList<>();
        for (String item : token.split(",", -1)) { result.add(integer(item)); }
        return result;
    }

    private static List<ExternalCall> external(String token) {
        if (token.equals("-")) { return List.of(); }
        List<ExternalCall> result = new ArrayList<>();
        for (String item : token.split(",", -1)) {
            int colon = item.indexOf(':');
            if (colon < 1 || colon != item.lastIndexOf(':')) {
                throw problem(ErrorCode.INVALID_ARGUMENT, "Par externo mal formado.");
            }
            int floor = integer(item.substring(0, colon));
            Direction d = direction(item.substring(colon + 1));
            if (floor == 0) { throw problem(ErrorCode.INVALID_FLOOR, "Piso externo cero."); }
            result.add(new ExternalCall(floor, d));
        }
        return result;
    }

    private static void arity(Frame frame, int expected) {
        if (frame.excess() || frame.tokens().size() != expected) {
            throw problem(ErrorCode.INVALID_FORMAT, "Cantidad de tokens incorrecta.");
        }
    }

    private static void labels(List<String> t, Object... pairs) {
        for (int i = 0; i < pairs.length; i += 2) {
            int index = (Integer) pairs[i];
            if (!t.get(index).equals(pairs[i + 1])) {
                throw problem(ErrorCode.INVALID_FORMAT, "Etiqueta o posición incorrecta.");
            }
        }
    }

    private record Frame(List<String> tokens, boolean excess) { }

    private static Frame frame(String input) {
        if (input == null) { throw problem(ErrorCode.INVALID_FORMAT, "Entrada nula."); }
        long bytes = 0;
        for (int i = 0; i < input.length(); i++) {
            char ch = input.charAt(i);
            if (ch <= 0x7f) { bytes++; }
            else if (ch <= 0x7ff) { bytes += 2; }
            else if (Character.isHighSurrogate(ch) && i + 1 < input.length()
                    && Character.isLowSurrogate(input.charAt(i + 1))) { bytes += 4; i++; }
            else { bytes += Character.isSurrogate(ch) ? 1 : 3; }
            if (bytes > MAX_FRAME_BYTES) { throw problem(ErrorCode.MESSAGE_TOO_LONG, "Mensaje supera 1 MiB."); }
        }
        int end = input.length();
        if (end > 0 && input.charAt(end - 1) == '\n') {
            end--; if (end > 0 && input.charAt(end - 1) == '\r') { end--; }
        }
        if (end == 0 || input.charAt(0) == ' ' || input.charAt(end - 1) == ' ') {
            throw problem(ErrorCode.INVALID_FORMAT, "Mensaje vacío o espacios exteriores.");
        }
        for (int i = 0; i < end; i++) {
            char ch = input.charAt(i);
            if (ch < 32 || ch > 126) { throw problem(ErrorCode.INVALID_FORMAT, "Solo ASCII imprimible y terminador final."); }
        }
        List<String> tokens = new ArrayList<>(MAX_TOKENS);
        boolean excess = false;
        for (int start = 0; start < end;) {
            int finish = start;
            while (finish < end && input.charAt(finish) != ' ') { finish++; }
            if (tokens.size() < MAX_TOKENS) { tokens.add(input.substring(start, finish)); }
            else { excess = true; break; }
            start = finish;
            while (start < end && input.charAt(start) == ' ') { start++; }
        }
        return new Frame(List.copyOf(tokens), excess);
    }

    private static String family(ProtocolMessage m) {
        if (m instanceof Pickup || m instanceof Destination || m instanceof Control) { return "CMD"; }
        if (m instanceof Ack) { return "ACK"; }
        if (m instanceof ProtocolMessage.Error) { return "ERROR"; }
        if (m instanceof Terminal) { return "EVENT"; }
        if (m instanceof Move) { return "MOVE"; }
        if (m instanceof Arrived) { return "ARRIVED"; }
        if (m instanceof DirectionChanged) { return "DIRECTION"; }
        if (m instanceof StoppedAt) { return "STOPPED_AT"; }
        return "STATE";
    }

    private static String floorToken(OptionalInt floor) { return floor.isPresent() ? Integer.toString(floor.getAsInt()) : "-"; }

    private static ProtocolDecodeResult.Failure failure(ErrorCode code, Header header, String detail, String input) {
        return new ProtocolDecodeResult.Failure(code, header, detail, preview(input));
    }

    private static String preview(String input) {
        if (input == null) { return ""; }
        StringBuilder out = new StringBuilder(256);
        for (int i = 0; i < input.length(); i++) {
            char ch = input.charAt(i);
            String escaped = switch (ch) {
                case '\n' -> "\\n"; case '\r' -> "\\r"; case '\t' -> "\\t"; case '\\' -> "\\\\";
                default -> ch < 32 || ch > 126 ? String.format("\\u%04x", (int) ch) : Character.toString(ch);
            };
            if (out.length() + escaped.length() > 256) { break; }
            out.append(escaped);
        }
        return out.toString();
    }

    private static Problem problem(ErrorCode code, String detail) { return new Problem(code, detail); }

    private static final class Problem extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final ErrorCode code;
        private Problem(ErrorCode code, String detail) { super(detail); this.code = code; }
    }

    /** Construcción acotada: nunca crear primero una línea gigante y después recortarla. */
    private static final class Writer {
        private final StringBuilder text = new StringBuilder();
        private void beginToken() { if (!text.isEmpty()) { raw(" "); } }
        private void add(Object... tokens) {
            for (Object token : tokens) { beginToken(); raw(token.toString()); }
        }
        private void raw(String part) {
            if (part.length() > MAX_FRAME_BYTES - text.length()) {
                throw problem(ErrorCode.MESSAGE_TOO_LONG, "Mensaje serializado supera 1 MiB.");
            }
            text.append(part);
        }
        private String text() { return text.toString(); }
    }
}
```

## src/main/java/com/elevatormanager/protocol/ProtocolCommands.java

```java
package com.elevatormanager.protocol;

import com.elevatormanager.model.request.CabinRequest;
import com.elevatormanager.model.request.HallRequest;
import java.time.Instant;
import java.util.Objects;
import static com.elevatormanager.protocol.ProtocolMessage.*;

/** Puente sin efectos al subconjunto Command existente; no despacha ni crea hilos. */
public final class ProtocolCommands {
    private ProtocolCommands() { }

    public static ProtocolMessage fromDomain(Command command) {
        Objects.requireNonNull(command, "command");
        Header h = new Header(command.commandId(), command.epoch(), command.elevatorId());
        if (command instanceof Command.Pickup pickup) {
            var r = pickup.request(); return new Pickup(h, r.getId(), r.getFloor(), r.getDirection());
        }
        if (command instanceof Command.Destination destination) {
            var r = destination.request(); return new Destination(h, r.getId(), r.getFloor());
        }
        return new Control(h, ControlKind.RESET);
    }

    /** createdAt es metadato explícito del llamador, ausente en V1. Nunca usa Instant.now(). */
    public static Command toDomain(ProtocolMessage message, Instant createdAt) {
        Objects.requireNonNull(message, "message"); Objects.requireNonNull(createdAt, "createdAt");
        Header h = message.header();
        if (message instanceof Pickup pickup) {
            return new Command.Pickup(h.id(), h.elevatorId(), new HallRequest(pickup.requestId(),
                    h.epoch(), pickup.floor(), createdAt, pickup.direction()));
        }
        if (message instanceof Destination destination) {
            return new Command.Destination(h.id(), h.elevatorId(), new CabinRequest(destination.requestId(),
                    h.epoch(), destination.floor(), createdAt, h.elevatorId()));
        }
        if (message instanceof Control control && control.kind() == ControlKind.RESET) {
            return new Command.Reset(h.id(), h.epoch(), h.elevatorId());
        }
        throw new IllegalArgumentException("El Command actual solo representa PICKUP, DESTINATION y RESET.");
    }
}
```

## src/main/java/com/elevatormanager/protocol/package-info.java

```java
/**
 * DTOs inmutables y codec textual V1. El parseo valida formato/configuración;
 * la admisión por estado, autoridad, época e idempotencia pertenece al receptor.
 * No depende de Swing ni ejecuta órdenes al decodificar.
 */
package com.elevatormanager.protocol;
```

## src/main/java/com/elevatormanager/diagnostics/ProtocolCodecDemo.java

```java
package com.elevatormanager.diagnostics;

import com.elevatormanager.model.Direction;
import com.elevatormanager.model.SimulationConfig;
import com.elevatormanager.model.request.HallRequest;
import com.elevatormanager.protocol.Command;
import com.elevatormanager.protocol.ProtocolCodec;
import com.elevatormanager.protocol.ProtocolCommands;
import java.time.Instant;
import java.util.UUID;

/** Demostración T63 de codec real, sin mover cabinas ni simular transporte T64. */
public final class ProtocolCodecDemo {
    private ProtocolCodecDemo() { }

    public static void main(String[] args) {
        var codec = new ProtocolCodec(new SimulationConfig(2, 8, 1000, 750));
        var createdAt = Instant.parse("2026-11-02T00:00:00Z");
        var request = new HallRequest(UUID.fromString("12345678-1234-1234-1234-123456789abc"),
                0, 3, createdAt, Direction.UP);
        Command original = new Command.Pickup(42, 1, request);
        String wire = codec.encode(ProtocolCommands.fromDomain(original));
        System.out.println("T63_TX " + wire);
        var decoded = codec.decodeCommand(wire);
        if (!decoded.isSuccess()) { throw new IllegalStateException("Falló el round-trip válido."); }
        Command restored = ProtocolCommands.toDomain(decoded.message().orElseThrow(), createdAt);
        if (!original.sameContent(restored)) { throw new IllegalStateException("Contenido operativo distinto."); }
        System.out.println("T63_ROUND_TRIP sameContent=true; uuid=" + request.getId());
        var rejected = codec.decodeCommand(wire.replace(" 3 UP", " 8 UP"));
        var failure = rejected.failure().orElseThrow();
        System.out.println("T63_REJECT " + codec.encode(failure.asErrorMessage()));
        if (!codec.decodeCommand(wire).isSuccess()) { throw new IllegalStateException("El codec no se recuperó."); }
        System.out.println("T63_CODEC_OK; transportIntegrated=false");
    }
}
```

## src/test/java/com/elevatormanager/protocol/ProtocolCodecScenarios.java

```java
package com.elevatormanager.protocol;

import com.elevatormanager.model.Direction;
import com.elevatormanager.model.ElevatorPhase;
import com.elevatormanager.model.SimulationConfig;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static com.elevatormanager.protocol.ProtocolMessage.*;

/** Escenarios independientes de JUnit; ejecutan el codec real, sin simular el motor. */
public final class ProtocolCodecScenarios {
    private static final ProtocolCodec CODEC = new ProtocolCodec(new SimulationConfig(2, 8, 1000, 750));
    private static final Header H = new Header(42, 0, 1);
    private static final UUID U = UUID.fromString("12345678-1234-1234-1234-123456789abc");
    private static final String PREFIX = "V1 CMD 42 EPOCH 0 ELEVATOR 1 ";
    private static final String DEST = PREFIX + "DESTINATION " + U + " 5";
    private static final String STATE = "V1 STATE 90 EPOCH 0 ELEVATOR 1 REV 8 FLOOR 2 NEXT 3"
            + " DIRECTION UP PHASE HELD SUSPENDED MOVING HOLD PAUSE MODE NORMAL REMAINING_MS 450 INTERNAL 5 EXTERNAL 3:UP";

    private ProtocolCodecScenarios() { }

    public static void pickupRoundTrip() { round(new Pickup(H, U, 3, Direction.UP)); }
    public static void destinationRoundTrip() { round(new Destination(H, U, 8)); }
    public static void resetRoundTrip() { control(ControlKind.RESET); }
    public static void stopRoundTrip() { control(ControlKind.STOP); }
    public static void startRoundTrip() { control(ControlKind.START); }
    public static void pauseRoundTrip() { control(ControlKind.PAUSE); }
    public static void resumeRoundTrip() { control(ControlKind.RESUME); }
    public static void statusRoundTrip() { control(ControlKind.STATUS); }
    public static void shutdownRoundTrip() { control(ControlKind.SHUTDOWN); }
    public static void acceptedRoundTrip() { round(new Ack(H, AckKind.ACCEPTED, Optional.empty())); }
    public static void coalescedRoundTrip() { round(new Ack(H, AckKind.COALESCED, Optional.of(U))); }
    public static void allErrorsRoundTrip() {
        for (ErrorCode code : ErrorCode.values()) {
            round(new ProtocolMessage.Error(H, code));
            round(new ProtocolMessage.Error(Header.UNKNOWN, code));
        }
        round(new ProtocolMessage.Error(new Header(42, 0, 99), ErrorCode.UNKNOWN_ELEVATOR));
    }
    public static void completedRoundTrip() {
        round(new Terminal(H, TerminalKind.COMPLETED, Optional.empty(), Optional.empty()));
    }
    public static void allCancellationsRoundTrip() {
        for (CancelReason reason : CancelReason.values()) {
            round(new Terminal(H, TerminalKind.CANCELLED, Optional.of(reason), Optional.empty()));
        }
    }
    public static void allFailuresRoundTrip() {
        for (ErrorCode code : ErrorCode.values()) {
            round(new Terminal(H, TerminalKind.FAILED, Optional.empty(), Optional.of(code)));
        }
    }
    public static void moveRoundTrip() {
        round(new Move(H, 2, 3, Direction.UP)); round(new Move(H, 8, 7, Direction.DOWN));
    }
    public static void arrivedRoundTrip() { round(new Arrived(H, 8)); }
    public static void directionRoundTrip() { round(new DirectionChanged(H, Direction.UP, Direction.DOWN)); }
    public static void stoppedAtInternalExtremeRoundTrip() {
        round(new StoppedAt(H, 8, Direction.UP)); round(new StoppedAt(H, 1, Direction.DOWN));
    }
    public static void idleStateRoundTrip() { round(state(ElevatorPhase.IDLE, OptionalInt.empty(), Optional.empty(), Optional.empty(), 0)); }
    public static void movingStateRoundTrip() {
        round(state(ElevatorPhase.MOVING, OptionalInt.of(3), Optional.empty(), Optional.empty(), 1000));
    }
    public static void dwellingStateRoundTrip() {
        round(state(ElevatorPhase.DWELLING, OptionalInt.empty(), Optional.empty(), Optional.empty(), 750));
    }
    public static void heldMovingRoundTrip() {
        ProtocolMessage message = good(CODEC.decode(STATE));
        equal(STATE, CODEC.encode(message)); round(message);
    }
    public static void heldDwellingStopRoundTrip() {
        round(state(ElevatorPhase.HELD, OptionalInt.empty(), Optional.of(ElevatorPhase.DWELLING), Optional.of(HoldReason.STOP), 700));
    }
    public static void resetStateRoundTrip() {
        round(new State(H, 10, 2, OptionalInt.of(1), Direction.DOWN, ElevatorPhase.MOVING,
                Optional.empty(), Optional.empty(), Mode.RESET_RETURN, 300, List.of(), List.of()));
    }
    public static void terminatedStateRoundTrip() {
        round(new State(H, 10, 1, OptionalInt.empty(), Direction.UP, ElevatorPhase.TERMINATED,
                Optional.empty(), Optional.empty(), Mode.NORMAL, 0, List.of(), List.of()));
    }
    public static void spontaneousStateRoundTrip() {
        State s = state(ElevatorPhase.IDLE, OptionalInt.empty(), Optional.empty(), Optional.empty(), 0);
        round(new State(new Header(0, 0, 1), 0, s.floor(), s.next(), s.direction(), s.phase(), s.suspended(),
                s.hold(), s.mode(), 0, s.internal(), s.external()));
    }
    public static void zeroRemainingWhileMovingIsValid() { good(CODEC.decode(STATE.replace("450", "0"))); }
    public static void canonicalSpacingAndTerminators() {
        for (String ending : List.of("", "\n", "\r\n")) {
            equal(DEST, CODEC.encode(good(CODEC.decode(DEST.replace(" ", "   ") + ending))));
        }
    }
    public static void nullAndEmptyRejected() {
        for (String input : new String[] {null, "", " ", "\n", "\r\n"}) { bad(input, ErrorCode.INVALID_FORMAT, Header.UNKNOWN); }
    }
    public static void exteriorSpacesRejected() {
        bad(" " + DEST, ErrorCode.INVALID_FORMAT, Header.UNKNOWN); bad(DEST + " ", ErrorCode.INVALID_FORMAT, Header.UNKNOWN);
        bad(DEST + " \r\n", ErrorCode.INVALID_FORMAT, Header.UNKNOWN);
    }
    public static void tabsBomUnicodeAndControlsRejected() {
        for (String input : List.of(DEST.replaceFirst(" ", "\t"), "\ufeff" + DEST,
                DEST + "é", DEST + "\u0000", DEST + "\ud800", DEST + "\u007f", DEST + "😀")) {
            bad(input, ErrorCode.INVALID_FORMAT, Header.UNKNOWN);
        }
    }
    public static void embeddedOrRepeatedTerminatorsRejected() {
        for (String input : List.of(DEST + "\r", DEST + "\n\n", DEST + "\r\n\r\n", DEST + "\n" + DEST)) {
            bad(input, ErrorCode.INVALID_FORMAT, Header.UNKNOWN);
        }
    }
    public static void commentsQuotesAndPlaceholdersRejected() {
        bad("#" + DEST, ErrorCode.INVALID_FORMAT, Header.UNKNOWN);
        bad(DEST + " # comentario", ErrorCode.INVALID_FORMAT, H);
        bad(PREFIX + "DESTINATION \"" + U + "\" 5", ErrorCode.INVALID_ARGUMENT, H);
        bad(PREFIX + "DESTINATION <requestId> 5", ErrorCode.INVALID_ARGUMENT, H);
    }
    public static void unsupportedAndMalformedVersions() {
        for (String v : List.of("V0", "V2", "V01", "V999999999999999999999999999")) {
            bad(DEST.replaceFirst("V1", v), ErrorCode.UNSUPPORTED_VERSION, Header.UNKNOWN);
        }
        for (String v : List.of("v1", "1", "V-1", "V", "V1X")) {
            bad(DEST.replaceFirst("V1", v), ErrorCode.INVALID_FORMAT, Header.UNKNOWN);
        }
    }
    public static void unknownFamilyAndCommand() {
        bad(DEST.replace("CMD", "BOGUS"), ErrorCode.INVALID_FORMAT, Header.UNKNOWN);
        bad(PREFIX + "FLY", ErrorCode.UNKNOWN_COMMAND, H);
        bad(PREFIX + "status", ErrorCode.UNKNOWN_COMMAND, H);
    }
    public static void commandHeaderMustBeCompleteAndCanonical() {
        for (String text : List.of("V1 CMD", "V1 CMD 42 EPOCH 0", "V1 CMD 42 EPOCH 0 ELEVATOR",
                "V1 CMD 0 EPOCH 0 ELEVATOR 1 STATUS", "V1 CMD 01 EPOCH 0 ELEVATOR 1 STATUS",
                "V1 CMD 42 EPOCH -1 ELEVATOR 1 STATUS", "V1 CMD 42 EPOCH 0 ELEVATOR 0 STATUS",
                "V1 CMD 42 EPOCH 0 ELEVATOR 2147483648 STATUS",
                "V1 CMD 9223372036854775808 EPOCH 0 ELEVATOR 1 STATUS")) {
            bad(text, ErrorCode.INVALID_FORMAT, Header.UNKNOWN);
        }
    }
    public static void headerOnlyRetainsCorrelation() { bad(PREFIX.stripTrailing(), ErrorCode.INVALID_FORMAT, H); }
    public static void arityAndLabelsAreExact() {
        for (String suffix : List.of("STATUS EXTRA", "RESET EXTRA", "DESTINATION " + U,
                "PICKUP " + U + " 3", "PICKUP " + U + " 3 UP EXTRA")) {
            bad(PREFIX + suffix, ErrorCode.INVALID_FORMAT, H);
        }
        bad(DEST.replace("EPOCH", "ERA"), ErrorCode.INVALID_FORMAT, Header.UNKNOWN);
        bad(STATE.replace("REMAINING_MS", "TIME"), ErrorCode.INVALID_FORMAT, new Header(90, 0, 1));
    }
    public static void positiveLongAndIntMaximaRoundTrip() {
        ProtocolCodec huge = new ProtocolCodec(new SimulationConfig(Integer.MAX_VALUE, Integer.MAX_VALUE, 1, 1));
        ProtocolMessage message = new Destination(new Header(Long.MAX_VALUE, Long.MAX_VALUE, Integer.MAX_VALUE), U, Integer.MAX_VALUE);
        equal(message, good(huge.decode(huge.encode(message))));
    }
    public static void numericBodiesAreCanonicalAndBounded() {
        for (String floor : List.of("02", "+2", "-2", "2.0", "2e0", "2147483648", "9223372036854775808")) {
            bad(PREFIX + "DESTINATION " + U + " " + floor, ErrorCode.INVALID_ARGUMENT, H);
        }
        for (String floor : List.of("0", "9", "2147483647")) {
            bad(PREFIX + "DESTINATION " + U + " " + floor, ErrorCode.INVALID_FLOOR, H);
        }
    }
    public static void uuidIsStrictAndPreserved() {
        for (String id : List.of("100", U.toString().toUpperCase(), "1-1-1-1-1", U.toString().replace("-", ""), "-")) {
            bad(PREFIX + "DESTINATION " + id + " 5", ErrorCode.INVALID_ARGUMENT, H);
        }
        equal(U, ((Destination) good(CODEC.decode(DEST))).requestId());
    }
    public static void directionsAndBuildingLimits() {
        for (String d : List.of("up", "ARRIBA", "-", "0")) {
            bad(PREFIX + "PICKUP " + U + " 3 " + d, ErrorCode.INVALID_DIRECTION, H);
        }
        bad(PREFIX + "PICKUP " + U + " 1 DOWN", ErrorCode.INVALID_DIRECTION, H);
        bad(PREFIX + "PICKUP " + U + " 8 UP", ErrorCode.INVALID_DIRECTION, H);
        good(CODEC.decode(PREFIX + "PICKUP " + U + " 1 UP"));
        good(CODEC.decode(PREFIX + "PICKUP " + U + " 8 DOWN"));
    }
    public static void singleFloorBuilding() {
        ProtocolCodec one = new ProtocolCodec(new SimulationConfig(1, 1, 1, 1));
        equal(1, ((Destination) good(one.decode(DEST.replace(" 5", " 1")))).floor());
        for (Direction d : Direction.values()) {
            equal(ErrorCode.INVALID_DIRECTION, one.decode(PREFIX + "PICKUP " + U + " 1 " + d).failure().orElseThrow().code());
        }
    }
    public static void lexicalTypeTargetAndFloorPrecedence() {
        Header unknown = new Header(42, 0, 99);
        bad(DEST.replace("ELEVATOR 1", "ELEVATOR 99").replace(" 5", " 9"), ErrorCode.UNKNOWN_ELEVATOR, unknown);
        bad(PREFIX.replace("ELEVATOR 1", "ELEVATOR 99") + "DESTINATION bad 9", ErrorCode.INVALID_ARGUMENT, unknown);
        bad(PREFIX + "PICKUP " + U + " 9 SIDEWAYS", ErrorCode.INVALID_DIRECTION, H);
        bad(PREFIX + "PICKUP " + U + " 9 UP", ErrorCode.INVALID_FLOOR, H);
    }
    public static void parserDoesNotApplySessionAdmission() {
        good(CODEC.decode(PREFIX.replace("EPOCH 0", "EPOCH 999") + "START"));
        good(CODEC.decode(PREFIX + "RESET"));
        good(CODEC.decode("V1 EVENT 42 EPOCH 999 ELEVATOR 1 COMPLETED"));
        good(CODEC.decode(DEST)); good(CODEC.decode(DEST.replace(" 5", " 6")));
    }
    public static void commandAndResponseChannelsAreDistinct() {
        String ack = "V1 ACK 42 EPOCH 0 ELEVATOR 1 ACCEPTED";
        check(CODEC.decodeCommand(DEST).isSuccess(), "Comando válido");
        check(CODEC.decodeResponse(ack).isSuccess(), "Respuesta válida");
        equal(ErrorCode.INVALID_FORMAT, CODEC.decodeCommand(ack).failure().orElseThrow().code());
        equal(ErrorCode.INVALID_FORMAT, CODEC.decodeResponse(DEST).failure().orElseThrow().code());
    }
    public static void unknownErrorReasonsAndStatusesRejected() {
        for (String line : List.of("V1 ERROR 42 EPOCH 0 ELEVATOR 1 BOGUS",
                "V1 EVENT 42 EPOCH 0 ELEVATOR 1 CANCELLED BOGUS",
                "V1 EVENT 42 EPOCH 0 ELEVATOR 1 FAILED BOGUS",
                "V1 ACK 42 EPOCH 0 ELEVATOR 1 BOGUS")) {
            bad(line, ErrorCode.INVALID_ARGUMENT, H);
        }
    }
    public static void responseAritiesAreExact() {
        for (String line : List.of("V1 ACK 42 EPOCH 0 ELEVATOR 1 ACCEPTED " + U,
                "V1 ACK 42 EPOCH 0 ELEVATOR 1 COALESCED",
                "V1 EVENT 42 EPOCH 0 ELEVATOR 1 COMPLETED EXTRA",
                "V1 EVENT 42 EPOCH 0 ELEVATOR 1 CANCELLED",
                "V1 ERROR 42 EPOCH 0 ELEVATOR 1 INVALID_ARGUMENT EXTRA",
                "V1 ARRIVED 42 EPOCH 0 ELEVATOR 1 FLOOR 3 EXTRA")) {
            bad(line, ErrorCode.INVALID_FORMAT, H);
        }
    }
    public static void unknownErrorHeaderIsAllOrNothing() {
        good(CODEC.decode("V1 ERROR 0 EPOCH 0 ELEVATOR 0 INVALID_FORMAT"));
        for (String line : List.of("V1 ERROR 0 EPOCH 1 ELEVATOR 0 INVALID_FORMAT",
                "V1 ERROR 0 EPOCH 0 ELEVATOR 1 INVALID_FORMAT", "V1 ERROR 42 EPOCH 0 ELEVATOR 0 INVALID_FORMAT")) {
            bad(line, ErrorCode.INVALID_ARGUMENT, Header.UNKNOWN);
        }
    }
    public static void physicalEventsMustBeCoherent() {
        bad("V1 MOVE 42 EPOCH 0 ELEVATOR 1 FROM 2 TO 4 DIRECTION UP", ErrorCode.INVALID_ARGUMENT, H);
        bad("V1 MOVE 42 EPOCH 0 ELEVATOR 1 FROM 2 TO 1 DIRECTION UP", ErrorCode.INVALID_ARGUMENT, H);
        bad("V1 DIRECTION 42 EPOCH 0 ELEVATOR 1 FROM UP TO UP", ErrorCode.INVALID_ARGUMENT, H);
        bad("V1 ARRIVED 42 EPOCH 0 ELEVATOR 1 FLOOR 9", ErrorCode.INVALID_FLOOR, H);
        bad("V1 ARRIVED 0 EPOCH 0 ELEVATOR 1 FLOOR 2", ErrorCode.INVALID_ARGUMENT, Header.UNKNOWN);
    }
    public static void stateCounterBounds() {
        good(CODEC.decode(STATE.replace("REV 8", "REV " + Long.MAX_VALUE)));
        good(CODEC.decode(STATE.replace("REV 8", "REV 0")));
        for (String value : List.of("-1", "01", "9223372036854775808")) {
            bad(STATE.replace("REV 8", "REV " + value), ErrorCode.INVALID_ARGUMENT, new Header(90, 0, 1));
        }
    }
    public static void stateHeldCombinations() {
        for (String line : List.of(STATE.replace("SUSPENDED MOVING", "SUSPENDED -"),
                STATE.replace("HOLD PAUSE", "HOLD -"), STATE.replace("HOLD PAUSE", "HOLD STOP"),
                STATE.replace("SUSPENDED MOVING", "SUSPENDED IDLE"), STATE.replace("PHASE HELD", "PHASE MOVING"))) {
            bad(line, ErrorCode.INVALID_ARGUMENT, new Header(90, 0, 1));
        }
    }
    public static void stateNextAndRemainingConstraints() {
        for (String line : List.of(STATE.replace("NEXT 3", "NEXT -"), STATE.replace("NEXT 3", "NEXT 4"),
                STATE.replace("450", "1001"), STATE.replace("450", "-1"))) {
            bad(line, ErrorCode.INVALID_ARGUMENT, new Header(90, 0, 1));
        }
        bad(STATE.replace("NEXT 3", "NEXT 9"), ErrorCode.INVALID_FLOOR, new Header(90, 0, 1));
        State idle = state(ElevatorPhase.IDLE, OptionalInt.empty(), Optional.empty(), Optional.empty(), 0);
        bad(CODEC.encode(idle).replace("REMAINING_MS 0", "REMAINING_MS 1"), ErrorCode.INVALID_ARGUMENT, H);
    }
    public static void stateListsAreSortedUniqueAndStrict() {
        for (String list : List.of("5,3", "5,5", "05", "5,", ",5", "5,,7")) {
            bad(STATE.replace("INTERNAL 5", "INTERNAL " + list), ErrorCode.INVALID_ARGUMENT, new Header(90, 0, 1));
        }
        bad(STATE.replace("INTERNAL 5", "INTERNAL"), ErrorCode.INVALID_FORMAT, new Header(90, 0, 1));
        bad(STATE.replace("INTERNAL 5", "INTERNAL 9"), ErrorCode.INVALID_FLOOR, new Header(90, 0, 1));
        good(CODEC.decode(STATE.replace("INTERNAL 5", "INTERNAL 1,3,5,8")));
        good(CODEC.decode(STATE.replace("EXTERNAL 3:UP", "EXTERNAL 3:UP,3:DOWN,5:DOWN")));
    }
    public static void stateExternalPairsAreStrict() {
        for (String list : List.of("3:DOWN,3:UP", "3:UP,3:UP", "5:UP,3:DOWN", "3", "3:UP:", "3:UP,", ":UP")) {
            bad(STATE.replace("EXTERNAL 3:UP", "EXTERNAL " + list), ErrorCode.INVALID_ARGUMENT, new Header(90, 0, 1));
        }
        bad(STATE.replace("3:UP", "1:DOWN"), ErrorCode.INVALID_DIRECTION, new Header(90, 0, 1));
        bad(STATE.replace("3:UP", "8:UP"), ErrorCode.INVALID_DIRECTION, new Header(90, 0, 1));
        bad(STATE.replace("3:UP", "3:up"), ErrorCode.INVALID_DIRECTION, new Header(90, 0, 1));
        bad(STATE.replace("3:UP", "0:UP"), ErrorCode.INVALID_FLOOR, new Header(90, 0, 1));
    }
    public static void stateResetAndTerminatedHaveNoPending() {
        bad(STATE.replace("MODE NORMAL", "MODE RESET_RETURN"), ErrorCode.INVALID_ARGUMENT, new Header(90, 0, 1));
        State terminated = new State(H, 0, 1, OptionalInt.empty(), Direction.UP, ElevatorPhase.TERMINATED,
                Optional.empty(), Optional.empty(), Mode.NORMAL, 0, List.of(), List.of());
        bad(CODEC.encode(terminated).replace("INTERNAL -", "INTERNAL 2"), ErrorCode.INVALID_ARGUMENT, H);
    }
    public static void stateProjectionDoesNotGenerateIdentityOrTime() {
        State parsed = (State) good(CODEC.decode(STATE));
        equal(List.of(5), parsed.internal()); equal(450L, parsed.remainingMillis());
        equal(new Header(90, 0, 1), parsed.header()); equal(8L, parsed.revision());
        equal(STATE, CODEC.encode(parsed));
    }
    public static void immutableStateListsAndDtoShapes() {
        List<Integer> internal = new ArrayList<>(List.of(5));
        List<ExternalCall> external = new ArrayList<>(List.of(new ExternalCall(3, Direction.UP)));
        State state = new State(H, 0, 2, OptionalInt.empty(), Direction.UP, ElevatorPhase.IDLE,
                Optional.empty(), Optional.empty(), Mode.NORMAL, 0, internal, external);
        internal.clear(); external.clear(); equal(1, state.internal().size()); equal(1, state.external().size());
        expectIllegal(() -> state.internal().add(8));
        expectIllegal(() -> new Ack(H, AckKind.ACCEPTED, Optional.of(U)));
        expectIllegal(() -> new Terminal(H, TerminalKind.COMPLETED, Optional.of(CancelReason.RESET), Optional.empty()));
        expectIllegal(() -> new Pickup(Header.UNKNOWN, U, 3, Direction.UP));
        expectIllegal(() -> new ProtocolMessage.Error(new Header(0, 0, 1), ErrorCode.INVALID_FORMAT));
    }
    public static void exactFrameLimitIncludesTerminator() {
        for (String ending : List.of("", "\n", "\r\n")) {
            int spaces = ProtocolCodec.MAX_FRAME_BYTES - DEST.length() - ending.length() + 1;
            String exact = DEST.replaceFirst(" ", " ".repeat(spaces)) + ending;
            equal(ProtocolCodec.MAX_FRAME_BYTES, exact.length());
            equal(DEST, CODEC.encode(good(CODEC.decode(exact))));
            bad(exact + " ", ErrorCode.MESSAGE_TOO_LONG, Header.UNKNOWN);
        }
    }
    public static void oversizedUnicodeIsCheckedInBytes() {
        bad("é".repeat(ProtocolCodec.MAX_FRAME_BYTES / 2 + 1), ErrorCode.MESSAGE_TOO_LONG, Header.UNKNOWN);
        bad("😀".repeat(ProtocolCodec.MAX_FRAME_BYTES / 4 + 1), ErrorCode.MESSAGE_TOO_LONG, Header.UNKNOWN);
    }
    public static void excessTokensDoNotAllocateUnboundedTokenArray() {
        bad(PREFIX + "STATUS" + " X".repeat(300_000), ErrorCode.INVALID_FORMAT, H);
        bad(PREFIX + "FLY" + " X".repeat(300_000), ErrorCode.UNKNOWN_COMMAND, H);
    }
    public static void hugeNumericAndUuidTokensAreControlled() {
        bad(PREFIX + "DESTINATION " + "a".repeat(100_000) + " 5", ErrorCode.INVALID_ARGUMENT, H);
        bad(PREFIX + "DESTINATION " + U + " " + "9".repeat(100_000), ErrorCode.INVALID_ARGUMENT, H);
    }
    public static void diagnosticsAreBoundedEscapedAndNotExecutable() {
        ProtocolDecodeResult.Failure f = CODEC.decode(DEST + "\n<script>\t").failure().orElseThrow();
        check(!f.preview().contains("\n") && !f.preview().contains("\t"), "Controles escapados");
        check(f.preview().contains("\\n") && f.preview().contains("\\t"), "Prefijo fiel");
        ProtocolDecodeResult.Failure longFailure = CODEC.decode(PREFIX + "X".repeat(10_000)).failure().orElseThrow();
        check(longFailure.preview().length() <= 256, "Prefijo limitado");
        equal(new ProtocolMessage.Error(H, ErrorCode.UNKNOWN_COMMAND), longFailure.asErrorMessage());
    }
    public static void invalidMessageThenValidMessageRecovers() {
        for (int i = 0; i < 500; i++) {
            bad(PREFIX + "BOGUS", ErrorCode.UNKNOWN_COMMAND, H); equal(U, ((Destination) good(CODEC.decode(DEST))).requestId());
        }
    }
    public static void encodingUsesConfigurationAndControlledFailure() {
        encodingError(() -> CODEC.encode(new Destination(H, U, 9)), ErrorCode.INVALID_FLOOR);
        encodingError(() -> CODEC.encode(new Control(new Header(1, 0, 3), ControlKind.STATUS)), ErrorCode.UNKNOWN_ELEVATOR);
        encodingError(() -> CODEC.encode(new Pickup(H, U, 8, Direction.UP)), ErrorCode.INVALID_DIRECTION);
        encodingError(() -> CODEC.encode(null), ErrorCode.INVALID_ARGUMENT);
    }
    public static void oversizedStateEncodingIsNotTruncated() {
        ProtocolCodec huge = new ProtocolCodec(new SimulationConfig(1, 300_000, 1, 1));
        List<Integer> internal = new ArrayList<>();
        for (int i = 1; i <= 200_000; i++) { internal.add(i); }
        State state = new State(H, 0, 1, OptionalInt.empty(), Direction.UP, ElevatorPhase.IDLE,
                Optional.empty(), Optional.empty(), Mode.NORMAL, 0, internal, List.of());
        encodingError(() -> huge.encode(state), ErrorCode.MESSAGE_TOO_LONG);
    }
    public static void sameRevisionCanCarryDifferentRemaining() {
        State first = (State) good(CODEC.decode(STATE));
        State second = (State) good(CODEC.decode(STATE.replace("450", "400")));
        equal(first.revision(), second.revision()); check(!first.equals(second), "No perder restante");
    }
    public static void randomRoundTripsRetainAllOperationalFields() {
        Random random = new Random(630062L);
        for (int i = 1; i <= 1000; i++) {
            Header h = new Header(i, random.nextInt(10), 1 + random.nextInt(2));
            UUID id = new UUID(random.nextLong(), random.nextLong());
            round(new Destination(h, id, 1 + random.nextInt(8)));
            round(new Pickup(h, id, 2 + random.nextInt(6), random.nextBoolean() ? Direction.UP : Direction.DOWN));
        }
    }
    public static void randomMalformedInputsNeverEscapeTheParser() {
        Random random = new Random(63);
        for (int i = 0; i < 5000; i++) {
            int length = random.nextInt(160); StringBuilder s = new StringBuilder();
            for (int j = 0; j < length; j++) { s.append((char) random.nextInt(256)); }
            ProtocolDecodeResult result = CODEC.decode(s.toString());
            check(result.isSuccess() != result.failure().isPresent(), "Resultado exclusivo");
        }
        for (int i = 0; i < DEST.length(); i++) {
            CODEC.decode(DEST.substring(0, i));
            CODEC.decode(DEST.substring(0, i) + " " + DEST.substring(i));
        }
    }
    public static void sharedCodecIsThreadSafe() throws Exception {
        var pool = Executors.newFixedThreadPool(4);
        try {
            List<Callable<Boolean>> jobs = new ArrayList<>();
            for (int thread = 0; thread < 4; thread++) {
                jobs.add(() -> {
                    for (int i = 0; i < 300; i++) {
                        equal(DEST, CODEC.encode(good(CODEC.decode(DEST))));
                        bad(PREFIX + "FLY", ErrorCode.UNKNOWN_COMMAND, H);
                    }
                    return true;
                });
            }
            for (var future : pool.invokeAll(jobs, 15, TimeUnit.SECONDS)) { check(future.get(), "Hilo terminado"); }
        } finally { pool.shutdownNow(); check(pool.awaitTermination(10, TimeUnit.SECONDS), "Cierre pool"); }
    }

    public static void typedPickupBridgePreservesContentAndExplicitMetadata() {
        var time = java.time.Instant.parse("2026-11-02T00:00:00Z");
        Command original = new Command.Pickup(42, 1, new com.elevatormanager.model.request.HallRequest(U, 0, 3, time, Direction.UP));
        Command restored = ProtocolCommands.toDomain(good(CODEC.decode(CODEC.encode(ProtocolCommands.fromDomain(original)))), time);
        check(original.sameContent(restored), "Contenido completo de PICKUP");
        equal(time, ((Command.Pickup) restored).request().getCreatedAt());
    }
    public static void typedDestinationBridgePreservesContentAndExplicitMetadata() {
        var time = java.time.Instant.parse("2026-11-02T00:00:00Z");
        Command original = new Command.Destination(42, 1, new com.elevatormanager.model.request.CabinRequest(U, 0, 5, time, 1));
        Command restored = ProtocolCommands.toDomain(good(CODEC.decode(CODEC.encode(ProtocolCommands.fromDomain(original)))), time);
        check(original.sameContent(restored), "Contenido completo de DESTINATION");
        equal(time, ((Command.Destination) restored).request().getCreatedAt());
    }
    public static void typedResetBridgeAndOtherControlsRemainExplicit() {
        var time = java.time.Instant.EPOCH;
        Command original = new Command.Reset(42, 1, 1);
        equal(original, ProtocolCommands.toDomain(good(CODEC.decode(CODEC.encode(ProtocolCommands.fromDomain(original)))), time));
        expectIllegal(() -> ProtocolCommands.toDomain(new Control(H, ControlKind.PAUSE), time));
        expectIllegal(() -> ProtocolCommands.toDomain(new Ack(H, AckKind.ACCEPTED, Optional.empty()), time));
    }
    public static void messageEqualityIncludesContentBeyondUuid() {
        check(!new Destination(H, U, 3).equals(new Destination(H, U, 5)), "Igualdad no reducida a UUID");
        check(!new Pickup(H, U, 3, Direction.UP).equals(new Pickup(H, U, 3, Direction.DOWN)), "Sentido incluido");
    }
    public static void malformedMutationsOfEveryFamilyAreControlled() {
        List<String> valid = List.of(DEST, STATE, PREFIX + "STATUS",
                "V1 ACK 42 EPOCH 0 ELEVATOR 1 COALESCED " + U,
                "V1 EVENT 42 EPOCH 0 ELEVATOR 1 CANCELLED RESET",
                "V1 MOVE 42 EPOCH 0 ELEVATOR 1 FROM 2 TO 3 DIRECTION UP",
                "V1 ARRIVED 42 EPOCH 0 ELEVATOR 1 FLOOR 3",
                "V1 DIRECTION 42 EPOCH 0 ELEVATOR 1 FROM UP TO DOWN",
                "V1 STOPPED_AT 42 EPOCH 0 ELEVATOR 1 FLOOR 8 SERVICE UP",
                "V1 ERROR 0 EPOCH 0 ELEVATOR 0 INVALID_FORMAT");
        for (String line : valid) {
            good(CODEC.decode(line));
            for (int i = 0; i < line.length(); i++) {
                CODEC.decode(line.substring(0, i));
                CODEC.decode(line.substring(0, i) + "#" + line.substring(i + 1));
            }
        }
    }

    private static State state(ElevatorPhase phase, OptionalInt next, Optional<ElevatorPhase> suspended,
            Optional<HoldReason> hold, long remaining) {
        return new State(H, 8, 2, next, Direction.UP, phase, suspended, hold, Mode.NORMAL, remaining,
                List.of(5, 8), List.of(new ExternalCall(3, Direction.UP), new ExternalCall(3, Direction.DOWN)));
    }
    private static void control(ControlKind kind) { round(new Control(H, kind)); }
    private static void round(ProtocolMessage message) {
        String encoded = CODEC.encode(message);
        equal(message, good(CODEC.decode(encoded)));
        equal(encoded, CODEC.encode(good(CODEC.decode(encoded))));
        check(!encoded.endsWith("\n") && !encoded.contains("  "), "Emisión canónica");
    }
    private static ProtocolMessage good(ProtocolDecodeResult result) {
        if (!result.isSuccess()) { throw new AssertionError("Rechazado: " + result.failure().orElseThrow()); }
        return result.message().orElseThrow();
    }
    private static void bad(String text, ErrorCode code, Header correlation) {
        ProtocolDecodeResult result = CODEC.decode(text);
        check(!result.isSuccess(), "Se esperaba rechazo");
        var f = result.failure().orElseThrow(); equal(code, f.code()); equal(correlation, f.header());
    }
    private static void encodingError(Runnable action, ErrorCode code) {
        try { action.run(); }
        catch (ProtocolCodec.EncodingException ex) { equal(code, ex.failure().code()); return; }
        throw new AssertionError("Se esperaba error de codificación " + code);
    }
    private static void expectIllegal(Runnable action) {
        try { action.run(); }
        catch (IllegalArgumentException | UnsupportedOperationException expected) { return; }
        throw new AssertionError("Se esperaba rechazo de DTO/colección");
    }
    private static void equal(Object expected, Object actual) {
        if (!java.util.Objects.equals(expected, actual)) { throw new AssertionError("Esperado " + expected + ", recibido " + actual); }
    }
    private static void check(boolean condition, String message) { if (!condition) { throw new AssertionError(message); } }

    public static void main(String[] args) throws Exception {
        int passed = 0;
        for (var method : ProtocolCodecScenarios.class.getDeclaredMethods()) {
            if (java.lang.reflect.Modifier.isPublic(method.getModifiers())
                    && java.lang.reflect.Modifier.isStatic(method.getModifiers())
                    && method.getParameterCount() == 0) {
                try { method.invoke(null); passed++; System.out.println("T63_OK " + method.getName()); }
                catch (java.lang.reflect.InvocationTargetException ex) {
                    throw new AssertionError("Fallo " + method.getName(), ex.getCause());
                }
            }
        }
        System.out.println("T63_RESULT scenarios=" + passed + "; failures=0");
    }
}
```

## src/test/java/com/elevatormanager/protocol/ProtocolCodecTest.java

```java
package com.elevatormanager.protocol;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Los mismos escenarios del main independiente bajo Maven/JUnit. */
@Timeout(60)
class ProtocolCodecTest {
    @Test void pickupRoundTrip() throws Exception {
        ProtocolCodecScenarios.pickupRoundTrip();
    }
    @Test void destinationRoundTrip() throws Exception {
        ProtocolCodecScenarios.destinationRoundTrip();
    }
    @Test void resetRoundTrip() throws Exception {
        ProtocolCodecScenarios.resetRoundTrip();
    }
    @Test void stopRoundTrip() throws Exception {
        ProtocolCodecScenarios.stopRoundTrip();
    }
    @Test void startRoundTrip() throws Exception {
        ProtocolCodecScenarios.startRoundTrip();
    }
    @Test void pauseRoundTrip() throws Exception {
        ProtocolCodecScenarios.pauseRoundTrip();
    }
    @Test void resumeRoundTrip() throws Exception {
        ProtocolCodecScenarios.resumeRoundTrip();
    }
    @Test void statusRoundTrip() throws Exception {
        ProtocolCodecScenarios.statusRoundTrip();
    }
    @Test void shutdownRoundTrip() throws Exception {
        ProtocolCodecScenarios.shutdownRoundTrip();
    }
    @Test void acceptedRoundTrip() throws Exception {
        ProtocolCodecScenarios.acceptedRoundTrip();
    }
    @Test void coalescedRoundTrip() throws Exception {
        ProtocolCodecScenarios.coalescedRoundTrip();
    }
    @Test void allErrorsRoundTrip() throws Exception {
        ProtocolCodecScenarios.allErrorsRoundTrip();
    }
    @Test void completedRoundTrip() throws Exception {
        ProtocolCodecScenarios.completedRoundTrip();
    }
    @Test void allCancellationsRoundTrip() throws Exception {
        ProtocolCodecScenarios.allCancellationsRoundTrip();
    }
    @Test void allFailuresRoundTrip() throws Exception {
        ProtocolCodecScenarios.allFailuresRoundTrip();
    }
    @Test void moveRoundTrip() throws Exception {
        ProtocolCodecScenarios.moveRoundTrip();
    }
    @Test void arrivedRoundTrip() throws Exception {
        ProtocolCodecScenarios.arrivedRoundTrip();
    }
    @Test void directionRoundTrip() throws Exception {
        ProtocolCodecScenarios.directionRoundTrip();
    }
    @Test void stoppedAtInternalExtremeRoundTrip() throws Exception {
        ProtocolCodecScenarios.stoppedAtInternalExtremeRoundTrip();
    }
    @Test void idleStateRoundTrip() throws Exception {
        ProtocolCodecScenarios.idleStateRoundTrip();
    }
    @Test void movingStateRoundTrip() throws Exception {
        ProtocolCodecScenarios.movingStateRoundTrip();
    }
    @Test void dwellingStateRoundTrip() throws Exception {
        ProtocolCodecScenarios.dwellingStateRoundTrip();
    }
    @Test void heldMovingRoundTrip() throws Exception {
        ProtocolCodecScenarios.heldMovingRoundTrip();
    }
    @Test void heldDwellingStopRoundTrip() throws Exception {
        ProtocolCodecScenarios.heldDwellingStopRoundTrip();
    }
    @Test void resetStateRoundTrip() throws Exception {
        ProtocolCodecScenarios.resetStateRoundTrip();
    }
    @Test void terminatedStateRoundTrip() throws Exception {
        ProtocolCodecScenarios.terminatedStateRoundTrip();
    }
    @Test void spontaneousStateRoundTrip() throws Exception {
        ProtocolCodecScenarios.spontaneousStateRoundTrip();
    }
    @Test void zeroRemainingWhileMovingIsValid() throws Exception {
        ProtocolCodecScenarios.zeroRemainingWhileMovingIsValid();
    }
    @Test void canonicalSpacingAndTerminators() throws Exception {
        ProtocolCodecScenarios.canonicalSpacingAndTerminators();
    }
    @Test void nullAndEmptyRejected() throws Exception {
        ProtocolCodecScenarios.nullAndEmptyRejected();
    }
    @Test void exteriorSpacesRejected() throws Exception {
        ProtocolCodecScenarios.exteriorSpacesRejected();
    }
    @Test void tabsBomUnicodeAndControlsRejected() throws Exception {
        ProtocolCodecScenarios.tabsBomUnicodeAndControlsRejected();
    }
    @Test void embeddedOrRepeatedTerminatorsRejected() throws Exception {
        ProtocolCodecScenarios.embeddedOrRepeatedTerminatorsRejected();
    }
    @Test void commentsQuotesAndPlaceholdersRejected() throws Exception {
        ProtocolCodecScenarios.commentsQuotesAndPlaceholdersRejected();
    }
    @Test void unsupportedAndMalformedVersions() throws Exception {
        ProtocolCodecScenarios.unsupportedAndMalformedVersions();
    }
    @Test void unknownFamilyAndCommand() throws Exception {
        ProtocolCodecScenarios.unknownFamilyAndCommand();
    }
    @Test void commandHeaderMustBeCompleteAndCanonical() throws Exception {
        ProtocolCodecScenarios.commandHeaderMustBeCompleteAndCanonical();
    }
    @Test void headerOnlyRetainsCorrelation() throws Exception {
        ProtocolCodecScenarios.headerOnlyRetainsCorrelation();
    }
    @Test void arityAndLabelsAreExact() throws Exception {
        ProtocolCodecScenarios.arityAndLabelsAreExact();
    }
    @Test void positiveLongAndIntMaximaRoundTrip() throws Exception {
        ProtocolCodecScenarios.positiveLongAndIntMaximaRoundTrip();
    }
    @Test void numericBodiesAreCanonicalAndBounded() throws Exception {
        ProtocolCodecScenarios.numericBodiesAreCanonicalAndBounded();
    }
    @Test void uuidIsStrictAndPreserved() throws Exception {
        ProtocolCodecScenarios.uuidIsStrictAndPreserved();
    }
    @Test void directionsAndBuildingLimits() throws Exception {
        ProtocolCodecScenarios.directionsAndBuildingLimits();
    }
    @Test void singleFloorBuilding() throws Exception {
        ProtocolCodecScenarios.singleFloorBuilding();
    }
    @Test void lexicalTypeTargetAndFloorPrecedence() throws Exception {
        ProtocolCodecScenarios.lexicalTypeTargetAndFloorPrecedence();
    }
    @Test void parserDoesNotApplySessionAdmission() throws Exception {
        ProtocolCodecScenarios.parserDoesNotApplySessionAdmission();
    }
    @Test void commandAndResponseChannelsAreDistinct() throws Exception {
        ProtocolCodecScenarios.commandAndResponseChannelsAreDistinct();
    }
    @Test void unknownErrorReasonsAndStatusesRejected() throws Exception {
        ProtocolCodecScenarios.unknownErrorReasonsAndStatusesRejected();
    }
    @Test void responseAritiesAreExact() throws Exception {
        ProtocolCodecScenarios.responseAritiesAreExact();
    }
    @Test void unknownErrorHeaderIsAllOrNothing() throws Exception {
        ProtocolCodecScenarios.unknownErrorHeaderIsAllOrNothing();
    }
    @Test void physicalEventsMustBeCoherent() throws Exception {
        ProtocolCodecScenarios.physicalEventsMustBeCoherent();
    }
    @Test void stateCounterBounds() throws Exception {
        ProtocolCodecScenarios.stateCounterBounds();
    }
    @Test void stateHeldCombinations() throws Exception {
        ProtocolCodecScenarios.stateHeldCombinations();
    }
    @Test void stateNextAndRemainingConstraints() throws Exception {
        ProtocolCodecScenarios.stateNextAndRemainingConstraints();
    }
    @Test void stateListsAreSortedUniqueAndStrict() throws Exception {
        ProtocolCodecScenarios.stateListsAreSortedUniqueAndStrict();
    }
    @Test void stateExternalPairsAreStrict() throws Exception {
        ProtocolCodecScenarios.stateExternalPairsAreStrict();
    }
    @Test void stateResetAndTerminatedHaveNoPending() throws Exception {
        ProtocolCodecScenarios.stateResetAndTerminatedHaveNoPending();
    }
    @Test void stateProjectionDoesNotGenerateIdentityOrTime() throws Exception {
        ProtocolCodecScenarios.stateProjectionDoesNotGenerateIdentityOrTime();
    }
    @Test void immutableStateListsAndDtoShapes() throws Exception {
        ProtocolCodecScenarios.immutableStateListsAndDtoShapes();
    }
    @Test void exactFrameLimitIncludesTerminator() throws Exception {
        ProtocolCodecScenarios.exactFrameLimitIncludesTerminator();
    }
    @Test void oversizedUnicodeIsCheckedInBytes() throws Exception {
        ProtocolCodecScenarios.oversizedUnicodeIsCheckedInBytes();
    }
    @Test void excessTokensDoNotAllocateUnboundedTokenArray() throws Exception {
        ProtocolCodecScenarios.excessTokensDoNotAllocateUnboundedTokenArray();
    }
    @Test void hugeNumericAndUuidTokensAreControlled() throws Exception {
        ProtocolCodecScenarios.hugeNumericAndUuidTokensAreControlled();
    }
    @Test void diagnosticsAreBoundedEscapedAndNotExecutable() throws Exception {
        ProtocolCodecScenarios.diagnosticsAreBoundedEscapedAndNotExecutable();
    }
    @Test void invalidMessageThenValidMessageRecovers() throws Exception {
        ProtocolCodecScenarios.invalidMessageThenValidMessageRecovers();
    }
    @Test void encodingUsesConfigurationAndControlledFailure() throws Exception {
        ProtocolCodecScenarios.encodingUsesConfigurationAndControlledFailure();
    }
    @Test void oversizedStateEncodingIsNotTruncated() throws Exception {
        ProtocolCodecScenarios.oversizedStateEncodingIsNotTruncated();
    }
    @Test void sameRevisionCanCarryDifferentRemaining() throws Exception {
        ProtocolCodecScenarios.sameRevisionCanCarryDifferentRemaining();
    }
    @Test void randomRoundTripsRetainAllOperationalFields() throws Exception {
        ProtocolCodecScenarios.randomRoundTripsRetainAllOperationalFields();
    }
    @Test void randomMalformedInputsNeverEscapeTheParser() throws Exception {
        ProtocolCodecScenarios.randomMalformedInputsNeverEscapeTheParser();
    }
    @Test void sharedCodecIsThreadSafe() throws Exception {
        ProtocolCodecScenarios.sharedCodecIsThreadSafe();
    }
    @Test void typedPickupBridgePreservesContentAndExplicitMetadata() throws Exception {
        ProtocolCodecScenarios.typedPickupBridgePreservesContentAndExplicitMetadata();
    }
    @Test void typedDestinationBridgePreservesContentAndExplicitMetadata() throws Exception {
        ProtocolCodecScenarios.typedDestinationBridgePreservesContentAndExplicitMetadata();
    }
    @Test void typedResetBridgeAndOtherControlsRemainExplicit() throws Exception {
        ProtocolCodecScenarios.typedResetBridgeAndOtherControlsRemainExplicit();
    }
    @Test void messageEqualityIncludesContentBeyondUuid() throws Exception {
        ProtocolCodecScenarios.messageEqualityIncludesContentBeyondUuid();
    }
    @Test void malformedMutationsOfEveryFamilyAreControlled() throws Exception {
        ProtocolCodecScenarios.malformedMutationsOfEveryFamilyAreControlled();
    }
}
```
