# T64 — Código completo

Fecha: 2026-11-02, Guatemala. Cada bloque reproduce el archivo Java íntegro correspondiente, sin omitir métodos ni sustituir contenido por puntos suspensivos. Los .java completos están también en el ZIP del proyecto.

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

    /** Solo sintaxis/forma; para reconstruir un rechazo tipado, nunca para admitirlo. */
    public ProtocolDecodeResult decodeCommandSyntax(String input) {
        return decode(input, Channel.COMMAND, false);
    }

    private enum Channel { ANY, COMMAND, RESPONSE }

    private ProtocolDecodeResult decode(String input, Channel channel) {
        return decode(input, channel, true);
    }

    private ProtocolDecodeResult decode(String input, Channel channel, boolean checkConfig) {
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
            ProtocolMessage message = parse(frame, family, header, checkConfig);
            if (checkConfig) { validate(message); }
            return ProtocolDecodeResult.success(message);
        } catch (Problem error) {
            return ProtocolDecodeResult.rejected(failure(error.code, correlation, error.getMessage(), input));
        } catch (IllegalArgumentException error) {
            return ProtocolDecodeResult.rejected(failure(ErrorCode.INVALID_ARGUMENT,
                    correlation, "Campos o combinaciones incoherentes.", input));
        }
    }

    /** Emite una línea canónica, sin terminador. Falla explícitamente sin truncar. */
    public String encode(ProtocolMessage message) { return encode(message, true); }

    /** Forma V1 acotada, sin validar el edificio del receptor. No autoriza ejecución. */
    public String encodeSyntax(ProtocolMessage message) { return encode(message, false); }

    private String encode(ProtocolMessage message, boolean checkConfig) {
        Header correlation = message == null ? Header.UNKNOWN : safeCorrelation(message.header());
        try {
            if (message == null) { throw problem(ErrorCode.INVALID_ARGUMENT, "Mensaje nulo."); }
            if (checkConfig) { validate(message); }
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

    private ProtocolMessage parse(Frame frame, String family, Header h, boolean checkConfig) {
        List<String> t = frame.tokens();
        switch (family) {
            case "CMD": {
                String verb = t.get(7);
                if (verb.equals("PICKUP") || verb.equals("DESTINATION")) {
                    arity(frame, verb.equals("PICKUP") ? 11 : 10);
                    UUID request = uuid(t.get(8)); int floor = integer(t.get(9));
                    Direction direction = verb.equals("PICKUP") ? direction(t.get(10)) : null;
                    if (checkConfig) {
                        target(h); floor(floor);
                        if (direction != null) { externalDirection(floor, direction); }
                    }
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
                if (checkConfig) { target(h); floor(from); floor(to); } return new Move(h, from, to, d);
            }
            case "ARRIVED": {
                arity(frame, 9); labels(t, 7, "FLOOR"); int f = integer(t.get(8));
                if (checkConfig) { target(h); floor(f); } return new Arrived(h, f);
            }
            case "DIRECTION":
                arity(frame, 11); labels(t, 7, "FROM", 9, "TO");
                return new DirectionChanged(h, direction(t.get(8)), direction(t.get(10)));
            case "STOPPED_AT": {
                arity(frame, 11); labels(t, 7, "FLOOR", 9, "SERVICE");
                int f = integer(t.get(8)); Direction d = direction(t.get(10));
                if (checkConfig) { target(h); floor(f); } return new StoppedAt(h, f, d);
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
                if (checkConfig) {
                    target(h); floor(f); if (next.isPresent()) { floor(next.getAsInt()); }
                    for (int item : internal) { floor(item); }
                    for (ExternalCall call : external) { floor(call.floor()); }
                    for (ExternalCall call : external) { externalDirection(call.floor(), call.direction()); }
                }
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

## src/main/java/com/elevatormanager/simulation/ProtocolMailbox.java

```java
package com.elevatormanager.simulation;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** Canal de cadenas con un consumidor lógico; publicar no ejecuta callbacks. */
public final class ProtocolMailbox {
    private final LinkedBlockingQueue<String> queue = new LinkedBlockingQueue<>();

    // Solo los actores de este paquete producen respuestas del receptor.
    void publish(String frame) { queue.add(Objects.requireNonNull(frame, "frame")); }

    /** ZERO permite sondeo desde EDT. No esperar desde el hilo gráfico. */
    public Optional<String> await(Duration timeout) throws InterruptedException {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative()) { throw new IllegalArgumentException("Tiempo negativo."); }
        long nanos = timeout.toNanos();
        if (nanos > 0 && javax.swing.SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("No bloquear el EDT esperando protocolo.");
        }
        return Optional.ofNullable(queue.poll(nanos, TimeUnit.NANOSECONDS));
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
import com.elevatormanager.protocol.ProtocolCodec;
import com.elevatormanager.protocol.ProtocolCommands;
import com.elevatormanager.protocol.ProtocolDecodeResult;
import com.elevatormanager.protocol.ProtocolMessage;
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
    private record TextFrame(String text, Instant createdAt) {
        TextFrame { Objects.requireNonNull(text, "text"); Objects.requireNonNull(createdAt, "createdAt"); }
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
        if (parsed.isSuccess()) {
            ProtocolMessage message = parsed.message().orElseThrow();
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
        if (message instanceof ProtocolMessage.Terminal terminal) {
            wireTerminals.putIfAbsent(new WireKey(terminal.header().epoch(), terminal.header().id()), terminal);
        }
        protocolBatch.add(protocol.encode(message));
    }

    private void completed(Command command) {
        wire(new ProtocolMessage.Terminal(header(command), ProtocolMessage.TerminalKind.COMPLETED,
                Optional.empty(), Optional.empty()));
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
        emitAdmission(result, now, rejectionCode(result.command()));
    }

    private void emitAdmission(Result result, long now, ErrorCode rejection) {
        WireKey key = new WireKey(result.command().epoch(), result.command().commandId());
        if (result.outcome() == Outcome.REJECTED) {
            wire(new ProtocolMessage.Error(header(result.command()), rejection));
        } else {
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
```

## src/main/java/com/elevatormanager/dispatch/ManagerWorker.java

```java
package com.elevatormanager.dispatch;

import com.elevatormanager.logging.TraceMailbox;
import com.elevatormanager.simulation.time.PauseCoordinator;
import com.elevatormanager.model.SimulationConfig;
import com.elevatormanager.model.request.HallRequest;
import com.elevatormanager.protocol.Command;
import com.elevatormanager.protocol.ProtocolCodec;
import com.elevatormanager.protocol.ProtocolCommands;
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
    private final ProtocolCodec protocol;
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
        protocol = new ProtocolCodec(config);
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
            var submitted = elevator.submitTextIfCurrent(protocol.encode(ProtocolCommands.fromDomain(command)),
                    request.getCreatedAt(), selected.candidate().planning());
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
import com.elevatormanager.protocol.ProtocolCodec;
import com.elevatormanager.protocol.ProtocolCommands;
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
    private final ProtocolCodec protocol;
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
        protocol = new ProtocolCodec(config);
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
    /** Observación textual adicional; no consume el seguimiento tipado existente. */
    public void subscribeProtocol(ProtocolMailbox mailbox) {
        elevators.forEach(e -> e.subscribeProtocol(mailbox));
    }

    public void unsubscribeProtocol(ProtocolMailbox mailbox) {
        elevators.forEach(e -> e.unsubscribeProtocol(mailbox));
    }

    public List<ElevatorWorker.ProtocolReception> protocolReceptions() {
        return elevators.stream().map(ElevatorWorker::protocolReception).toList();
    }

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
            boolean sent = elevators.get(cabin.getElevatorId() - 1).submitText(
                    protocol.encode(ProtocolCommands.fromDomain(command)), cabin.getCreatedAt());
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

## src/main/java/com/elevatormanager/diagnostics/TextTransportDemo.java

```java
package com.elevatormanager.diagnostics;

import com.elevatormanager.dispatch.HallCallSnapshot;
import com.elevatormanager.dispatch.ManagerWorker;
import com.elevatormanager.model.Direction;
import com.elevatormanager.model.SimulationConfig;
import com.elevatormanager.model.request.HallRequest;
import com.elevatormanager.protocol.ProtocolCodec;
import com.elevatormanager.protocol.ProtocolCommands;
import com.elevatormanager.protocol.ProtocolMessage;
import com.elevatormanager.simulation.ElevatorWorker;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** T64: manejador/cabinas reales, mensajes de salida producidos por el receptor. */
public final class TextTransportDemo {
    private static final Duration LIMIT = Duration.ofSeconds(6);
    private TextTransportDemo() { }

    public static void main(String[] args) throws Exception {
        var config = new SimulationConfig(2, 5, 50, 100);
        var codec = new ProtocolCodec(config);
        var first = new ElevatorWorker(1, config, 0);
        var second = new ElevatorWorker(2, config, 0);
        var sequence = new AtomicLong(40);
        var manager = new ManagerWorker(config, 0, List.of(first, second), sequence::incrementAndGet);
        try {
            first.start(); second.start(); manager.start();
            var request = new HallRequest(UUID.fromString("12345678-1234-1234-1234-123456789abc"),
                    0, 3, Instant.EPOCH, Direction.UP);
            manager.submit(request);
            var sent = manager.awaitResult(LIMIT).orElseThrow().command().orElseThrow();
            var receiver = sent.elevatorId() == 1 ? first : second;
            System.out.println("T64_ORDER " + codec.encode(ProtocolCommands.fromDomain(sent)));
            var admitted = receiver.awaitResult(LIMIT).orElseThrow();
            if (admitted.command() == sent || !sent.sameContent(admitted.command())) {
                throw new IllegalStateException("No se reconstruyó el contenido desde texto.");
            }
            System.out.println("T64_RX thread=" + receiver.protocolReception().processedBy()
                    + "; reconstructed=true; sameContent=true");
            awaitTerminal(receiver, codec, sent.commandId());
            long end = System.nanoTime() + LIMIT.toNanos();
            while (manager.call(request.getId()).orElseThrow().status() != HallCallSnapshot.Status.SERVED) {
                if (System.nanoTime() > end) { throw new IllegalStateException("Servicio sin confirmar."); }
                TimeUnit.MILLISECONDS.sleep(2);
            }
            receiver.submitText("V1 CMD 42 EPOCH 0 ELEVATOR " + sent.elevatorId() + " FLY");
            String rejected = receiver.awaitProtocolMessage(LIMIT).orElseThrow();
            System.out.println("T64_WIRE " + rejected);
            var message = codec.decodeResponse(rejected).message().orElseThrow();
            if (!(message instanceof ProtocolMessage.Error error)
                    || error.code() != ProtocolMessage.ErrorCode.UNKNOWN_COMMAND || receiver.failure().isPresent()) {
                throw new IllegalStateException("El receptor no recuperó el mensaje inválido.");
            }
            System.out.println("T64_TRANSPORT_OK; managerStatus=SERVED; workerAlive=true");
        } finally {
            manager.close(); first.close(); second.close();
            if (!manager.awaitTermination(LIMIT) || !first.awaitTermination(LIMIT) || !second.awaitTermination(LIMIT)) {
                throw new IllegalStateException("Hilos sin terminar.");
            }
        }
    }

    private static void awaitTerminal(ElevatorWorker receiver, ProtocolCodec codec, long commandId) throws Exception {
        long end = System.nanoTime() + LIMIT.toNanos();
        while (System.nanoTime() < end) {
            String text = receiver.awaitProtocolMessage(LIMIT).orElseThrow();
            System.out.println("T64_WIRE " + text);
            var result = codec.decodeResponse(text).message().orElseThrow();
            if (result instanceof ProtocolMessage.Terminal terminal && terminal.header().id() == commandId
                    && terminal.kind() == ProtocolMessage.TerminalKind.COMPLETED) { return; }
        }
        throw new IllegalStateException("No se recibió finalización textual.");
    }
}
```

## src/test/java/com/elevatormanager/simulation/TextTransportScenarios.java

```java
package com.elevatormanager.simulation;

import com.elevatormanager.dispatch.HallCallSnapshot;
import com.elevatormanager.dispatch.ManagerWorker;
import com.elevatormanager.model.Direction;
import com.elevatormanager.model.ElevatorPhase;
import com.elevatormanager.model.SimulationConfig;
import com.elevatormanager.model.SimulationState;
import com.elevatormanager.model.request.CabinRequest;
import com.elevatormanager.model.request.HallRequest;
import com.elevatormanager.protocol.Command;
import com.elevatormanager.protocol.ProtocolCodec;
import com.elevatormanager.protocol.ProtocolCommands;
import com.elevatormanager.protocol.ProtocolMessage;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import static com.elevatormanager.protocol.ProtocolMessage.*;

/** Actor real: el cuerpo textual determina la agenda y las respuestas vienen de su hilo. */
public final class TextTransportScenarios {
    private static final Duration LIMIT = Duration.ofSeconds(6);
    private static final Instant DATE = Instant.parse("2026-11-02T00:00:00Z");
    private TextTransportScenarios() { }

    public static void rawDestinationIsParsedOnActor() throws Exception {
        try (Rig r = new Rig()) {
            check(r.w.submitText(destination(1, 3)), "Encolado");
            r.terminal(1, TerminalKind.COMPLETED);
            equal(3, r.w.snapshot().currentFloor());
            equal(r.w.threadName(), r.w.protocolReception().processedBy());
            equal(1L, r.w.protocolReception().frames());
        }
    }
    public static void rawPickupUsesTransmittedDirectionAndUuid() throws Exception {
        try (Rig r = new Rig()) {
            String line = pickup(1, 3, Direction.DOWN);
            check(r.w.submitText(line), "Encolado");
            var result = r.w.awaitResult(LIMIT).orElseThrow();
            var command = (Command.Pickup) result.command();
            equal(id(1), command.request().getId()); equal(Direction.DOWN, command.request().getDirection());
            r.terminal(1, TerminalKind.COMPLETED); equal(Direction.DOWN, r.w.snapshot().direction());
        }
    }
    public static void changedTextChangesPhysicalDestination() throws Exception {
        try (Rig r = new Rig()) {
            String changed = destination(1, 3).replace(" 3", " 4");
            r.w.submitText(changed); r.terminal(1, TerminalKind.COMPLETED);
            equal(4, r.w.snapshot().currentFloor());
            var result = (Command.Destination) r.w.awaitResult(LIMIT).orElseThrow().command();
            equal(4, result.request().getFloor());
        }
    }
    public static void compatibilitySubmitReconstructsDistinctObjects() throws Exception {
        try (Rig r = new Rig()) {
            var original = new Command.Destination(1, 1, new CabinRequest(id(1), 0, 2, DATE, 1));
            check(r.w.submit(original), "Encolado compatible");
            Command reconstructed = r.w.awaitResult(LIMIT).orElseThrow().command();
            check(reconstructed != original, "No se pasa el Command original");
            check(((Command.Destination) reconstructed).request() != original.request(), "Nueva solicitud desde texto");
            check(original.sameContent(reconstructed), "Contenido conservado");
        }
    }
    public static void explicitTimestampContextSurvives() throws Exception {
        try (Rig r = new Rig()) {
            r.w.submitText(destination(1, 2), DATE);
            var c = (Command.Destination) r.w.awaitResult(LIMIT).orElseThrow().command();
            equal(DATE, c.request().getCreatedAt());
        }
    }
    public static void defaultTimestampIsStableNotWallClock() throws Exception {
        try (Rig r = new Rig(10_000)) {
            r.w.submitText(destination(1, 3)); r.w.submitText(destination(1, 3));
            var first = r.w.awaitResult(LIMIT).orElseThrow(); var second = r.w.awaitResult(LIMIT).orElseThrow();
            equal(Instant.EPOCH, ((Command.Destination) first.command()).request().getCreatedAt());
            check(second.replay(), "Reintento no inventa otra fecha");
        }
    }
    public static void ackPrecedesPhysicalCompletion() throws Exception {
        try (Rig r = new Rig(300)) {
            r.w.submitText(destination(1, 3));
            r.read(m -> m instanceof Ack); check(r.w.snapshot().currentFloor() < 3, "ACK no es llegada");
            r.terminal(1, TerminalKind.COMPLETED); equal(3, r.w.snapshot().currentFloor());
        }
    }
    public static void moveArrivalAndStopAreTextualEvents() throws Exception {
        try (Rig r = new Rig()) {
            r.w.submitText(destination(1, 2)); r.terminal(1, TerminalKind.COMPLETED);
            check(r.seen.stream().anyMatch(m -> m instanceof Move v && v.from() == 1 && v.to() == 2), "MOVE real");
            check(r.seen.stream().anyMatch(m -> m instanceof Arrived v && v.floor() == 2), "ARRIVED real");
            check(r.seen.stream().anyMatch(m -> m instanceof StoppedAt v && v.floor() == 2), "Parada real");
        }
    }
    public static void directionChangesAreReportedInText() throws Exception {
        try (Rig r = new Rig()) {
            r.w.submitText(pickup(1, 3, Direction.DOWN)); r.terminal(1, TerminalKind.COMPLETED);
            check(r.seen.stream().anyMatch(m -> m instanceof DirectionChanged v
                    && v.from() == Direction.UP && v.to() == Direction.DOWN), "Inversión publicada");
        }
    }
    public static void malformedCommandDoesNotMutateAndWorkerRecovers() throws Exception {
        try (Rig r = new Rig()) {
            long revision = r.w.snapshot().revision();
            r.w.submitText(prefix(1) + "FLY"); r.error(ErrorCode.UNKNOWN_COMMAND);
            equal(revision, r.w.snapshot().revision());
            r.w.submitText(destination(2, 2)); r.terminal(2, TerminalKind.COMPLETED);
            check(r.w.failure().isEmpty(), "Trabajador vivo");
        }
    }
    public static void invalidFloorIsRejectedBeforeMotion() throws Exception {
        try (Rig r = new Rig()) {
            r.w.submitText(destination(1, 9)); r.error(ErrorCode.INVALID_FLOOR);
            equal(1, r.w.snapshot().currentFloor()); check(r.w.snapshot().cabinRequests().isEmpty(), "Sin agenda parcial");
        }
    }
    public static void impossibleDirectionIsRejected() throws Exception {
        try (Rig r = new Rig()) { r.w.submitText(pickup(1, 8, Direction.UP)); r.error(ErrorCode.INVALID_DIRECTION); }
    }
    public static void unknownConfiguredElevatorIsRejected() throws Exception {
        try (Rig r = new Rig()) {
            r.w.submitText(destination(1, 3).replace("ELEVATOR 1", "ELEVATOR 9"));
            var error = r.error(ErrorCode.UNKNOWN_ELEVATOR); equal(9, error.header().elevatorId());
        }
    }
    public static void wrongExistingTargetCannotMoveThisCabin() throws Exception {
        try (Rig r = new Rig()) {
            r.w.submitText(destination(1, 3).replace("ELEVATOR 1", "ELEVATOR 2"));
            r.error(ErrorCode.UNKNOWN_ELEVATOR); equal(1, r.w.snapshot().currentFloor());
        }
    }
    public static void staleAndFutureServiceEpochsAreRejected() throws Exception {
        try (Rig r = new Rig()) {
            r.w.submitText(destination(1, 3).replace("EPOCH 0", "EPOCH 1")); r.error(ErrorCode.FUTURE_EPOCH);
            r.w.requestResetText("V1 CMD 10 EPOCH 1 ELEVATOR 1 RESET").get(6, TimeUnit.SECONDS);
            check(r.w.resume(), "Liberar después de reset");
            r.w.submitText(destination(2, 3)); r.error(ErrorCode.STALE_EPOCH);
        }
    }
    public static void badUuidDoesNotCreateRequest() throws Exception {
        try (Rig r = new Rig()) {
            r.w.submitText(prefix(1) + "DESTINATION 123 3"); r.error(ErrorCode.INVALID_ARGUMENT);
            check(r.w.snapshot().cabinRequests().isEmpty(), "Sin UUID inventado");
        }
    }
    public static void ackInjectedIntoCommandQueueIsNotExecuted() throws Exception {
        try (Rig r = new Rig()) {
            r.w.submitText("V1 ACK 1 EPOCH 0 ELEVATOR 1 ACCEPTED"); r.error(ErrorCode.INVALID_FORMAT);
            equal(0L, r.w.snapshot().revision());
        }
    }
    public static void extraTokensDoNotPartiallyExecute() throws Exception {
        try (Rig r = new Rig()) {
            r.w.submitText(destination(1, 3) + " EXTRA"); r.error(ErrorCode.INVALID_FORMAT);
            check(r.w.snapshot().cabinRequests().isEmpty(), "Rechazo atómico");
        }
    }
    public static void oversizedFrameIsRejectedOnActor() throws Exception {
        try (Rig r = new Rig()) {
            r.w.submitText("X".repeat(ProtocolCodec.MAX_FRAME_BYTES + 1)); r.error(ErrorCode.MESSAGE_TOO_LONG);
            equal(r.w.threadName(), r.w.protocolReception().processedBy());
            r.w.submitText(destination(2, 2)); r.terminal(2, TerminalKind.COMPLETED);
        }
    }
    public static void framingNormalizationIsAppliedAtReceiver() throws Exception {
        try (Rig r = new Rig()) {
            r.w.submitText(destination(1, 2).replace(" ", "   ") + "\r\n"); r.terminal(1, TerminalKind.COMPLETED);
            equal(2, r.w.snapshot().currentFloor());
        }
    }
    public static void malformedBurstCannotKillActor() throws Exception {
        try (Rig r = new Rig()) {
            for (int i = 1; i <= 100; i++) { r.w.submitText(prefix(i) + "BOGUS"); }
            int errors = 0;
            while (errors < 100) { r.read(m -> m instanceof ProtocolMessage.Error); errors++; }
            r.w.submitText(destination(101, 2)); r.terminal(101, TerminalKind.COMPLETED);
            equal(100L, r.w.protocolReception().malformed());
        }
    }
    public static void conditionalSendChecksSnapshotBeforeEnqueue() throws Exception {
        try (Rig r = new Rig(10_000)) {
            var initial = r.w.planningSnapshot();
            equal(ElevatorWorker.Submission.ENQUEUED,
                    r.w.submitTextIfCurrent(destination(1, 3), DATE, initial));
            r.w.awaitResult(LIMIT).orElseThrow();
            equal(ElevatorWorker.Submission.STALE,
                    r.w.submitTextIfCurrent(destination(2, 4), DATE, initial));
            equal(1L, r.w.protocolReception().frames());
        }
    }
    public static void compatibleConditionalSendAlsoSerializes() throws Exception {
        try (Rig r = new Rig(10_000)) {
            var original = new Command.Destination(1, 1, new CabinRequest(id(1), 0, 3, DATE, 1));
            equal(ElevatorWorker.Submission.ENQUEUED, r.w.submitIfCurrent(original, r.w.planningSnapshot()));
            var parsed = r.w.awaitResult(LIMIT).orElseThrow().command();
            check(parsed != original && original.sameContent(parsed), "Ruta compatible también cruza como texto");
        }
    }
    public static void enqueueIsNotAdmissionWhileStopped() throws Exception {
        try (Rig r = new Rig()) {
            r.w.requestStop().get(6, TimeUnit.SECONDS);
            check(r.w.submitText(destination(1, 3)), "Solo encolado");
            equal(0L, r.w.protocolReception().frames());
            check(r.w.awaitProtocolMessage(Duration.ZERO).isEmpty(), "Sin ACK antes de leer");
            check(r.w.resume(), "Reanudar"); r.terminal(1, TerminalKind.COMPLETED);
        }
    }
    public static void serviceFifoIsPreserved() throws Exception {
        try (Rig r = new Rig(10_000)) {
            for (int i = 1; i <= 5; i++) { r.w.submitText(destination(i, i + 1)); }
            List<Long> accepted = new ArrayList<>();
            for (int i = 0; i < 5; i++) { accepted.add(r.read(m -> m instanceof Ack).header().id()); }
            equal(List.of(1L, 2L, 3L, 4L, 5L), accepted);
        }
    }
    public static void aliasesHaveCanonicalAckAndEachOwnTerminal() throws Exception {
        try (Rig r = new Rig(100)) {
            r.w.submitText(destination(1, 2)); r.w.submitText(destination(2, 2));
            Ack first = (Ack) r.read(m -> m instanceof Ack); Ack second = (Ack) r.read(m -> m instanceof Ack);
            equal(AckKind.ACCEPTED, first.kind()); equal(AckKind.COALESCED, second.kind());
            equal(Optional.of(id(1)), second.canonicalRequestId());
            r.terminal(1, TerminalKind.COMPLETED); r.terminal(2, TerminalKind.COMPLETED);
        }
    }
    public static void sameCommandReplayDoesNotDuplicatePhysicalService() throws Exception {
        try (Rig r = new Rig(100)) {
            String frame = destination(1, 2); r.w.submitText(frame); r.w.submitText(frame);
            r.read(m -> m instanceof Ack); r.read(m -> m instanceof Ack);
            r.terminal(1, TerminalKind.COMPLETED);
            var service = r.w.awaitService(LIMIT).orElseThrow(); equal(1, service.requests().size());
            check(r.w.awaitService(Duration.ZERO).isEmpty(), "Un servicio físico");
        }
    }
    public static void servedReplayReturnsKnownTerminalWithoutAnotherStop() throws Exception {
        try (Rig r = new Rig()) {
            String frame = destination(1, 1); r.w.submitText(frame); r.terminal(1, TerminalKind.COMPLETED);
            r.w.awaitService(LIMIT).orElseThrow();
            r.w.submitText(frame); r.read(m -> m instanceof Ack); r.terminal(1, TerminalKind.COMPLETED);
            check(r.w.awaitService(Duration.ZERO).isEmpty(), "Replay textual sin servicio nuevo");
        }
    }
    public static void conflictingIdCannotReplaceDestination() throws Exception {
        try (Rig r = new Rig(100)) {
            r.w.submitText(destination(1, 2)); r.w.submitText(destination(1, 3)); r.error(ErrorCode.ID_CONFLICT);
            r.terminal(1, TerminalKind.COMPLETED); equal(2, r.w.snapshot().currentFloor());
        }
    }
    public static void resetQueueAlsoCarriesAndParsesText() throws Exception {
        try (Rig r = new Rig()) {
            r.w.requestResetText("V1 CMD 10 EPOCH 1 ELEVATOR 1 RESET").get(6, TimeUnit.SECONDS);
            r.terminal(10, TerminalKind.COMPLETED); equal(1L, r.w.snapshot().epoch());
            equal(r.w.threadName(), r.w.protocolReception().processedBy());
        }
    }
    public static void resetCancelsActiveCommandBeforeReturnCompletes() throws Exception {
        try (Rig r = new Rig(100)) {
            r.w.submitText(destination(1, 5)); r.read(m -> m instanceof Move);
            r.w.requestResetText("V1 CMD 10 EPOCH 1 ELEVATOR 1 RESET").get(6, TimeUnit.SECONDS);
            Terminal cancelled = r.terminal(1, TerminalKind.CANCELLED);
            equal(Optional.of(CancelReason.RESET), cancelled.reason()); equal(0L, cancelled.header().epoch());
            r.terminal(10, TerminalKind.COMPLETED); equal(1, r.w.snapshot().currentFloor());
        }
    }
    public static void resetDiscardsQueuedTextBeforeAdmission() throws Exception {
        try (Rig r = new Rig()) {
            r.w.requestStop().get(6, TimeUnit.SECONDS); r.w.submitText(destination(1, 5));
            r.w.requestResetText("V1 CMD 10 EPOCH 1 ELEVATOR 1 RESET").get(6, TimeUnit.SECONDS);
            r.error(ErrorCode.STALE_EPOCH); r.terminal(10, TerminalKind.COMPLETED);
            equal(1, r.w.snapshot().currentFloor()); check(r.w.snapshot().cabinRequests().isEmpty(), "Inbox viejo no revive");
        }
    }
    public static void malformedResetFailsFutureWithoutAdoptingEpoch() throws Exception {
        try (Rig r = new Rig()) {
            expectFutureFailure(r.w.requestResetText("V1 CMD 10 EPOCH 1 ELEVATOR 1 RESET EXTRA"));
            r.error(ErrorCode.INVALID_FORMAT); equal(0L, r.w.snapshot().epoch());
        }
    }
    public static void serviceCannotEnterThroughResetPriority() throws Exception {
        try (Rig r = new Rig()) {
            expectFutureFailure(r.w.requestResetText(destination(1, 3)));
            r.error(ErrorCode.INVALID_STATE); check(r.w.snapshot().cabinRequests().isEmpty(), "No admitir servicio por control");
        }
    }
    public static void resetCannotEnterThroughOrdinaryServiceQueue() throws Exception {
        try (Rig r = new Rig()) {
            r.w.submitText("V1 CMD 10 EPOCH 1 ELEVATOR 1 RESET"); r.error(ErrorCode.INVALID_STATE);
            equal(0L, r.w.snapshot().epoch());
        }
    }
    public static void controlsPendingT65AreRejectedWithoutFakeAck() throws Exception {
        try (Rig r = new Rig()) {
            for (String verb : List.of("STOP", "START", "PAUSE", "RESUME", "STATUS", "SHUTDOWN")) {
                r.w.submitText(prefix(1) + verb); r.error(ErrorCode.INVALID_STATE);
            }
            check(r.w.failure().isEmpty(), "Actor sigue vivo");
        }
    }
    public static void textualObserversDoNotStealNativeEvents() throws Exception {
        try (Rig r = new Rig()) {
            ProtocolMailbox first = new ProtocolMailbox(), second = new ProtocolMailbox();
            r.w.subscribeProtocol(first); r.w.subscribeProtocol(second);
            r.w.submitText(destination(1, 1)); r.terminal(1, TerminalKind.COMPLETED);
            equal(first.await(LIMIT), second.await(LIMIT));
            equal(ExecutionEvent.Type.COMMAND_ACCEPTED, r.w.awaitEvent(LIMIT).orElseThrow().type());
            equal(ElevatorWorker.Outcome.ACCEPTED, r.w.awaitResult(LIMIT).orElseThrow().outcome());
        }
    }
    public static void unsubscribeStopsFutureFrames() throws Exception {
        try (Rig r = new Rig()) {
            ProtocolMailbox mailbox = new ProtocolMailbox(); r.w.subscribeProtocol(mailbox); r.w.unsubscribeProtocol(mailbox);
            r.w.submitText(destination(1, 1)); r.terminal(1, TerminalKind.COMPLETED);
            check(mailbox.await(Duration.ZERO).isEmpty(), "Sin frames nuevos");
        }
    }
    public static void closeCancelsAcceptedPendingText() throws Exception {
        try (Rig r = new Rig(10_000)) {
            r.w.submitText(destination(1, 5)); r.read(m -> m instanceof Ack);
            r.w.close(); check(r.w.awaitTermination(LIMIT), "Cierre real");
            Terminal cancelled = r.terminal(1, TerminalKind.CANCELLED); equal(Optional.of(CancelReason.SHUTDOWN), cancelled.reason());
        }
    }
    public static void closedOrNewWorkerRefusesToEnqueue() throws Exception {
        var config = new SimulationConfig(1, 8, 25, 25); var w = new ElevatorWorker(1, config, 0);
        check(!w.submitText(destination(1, 3)), "NEW no recibe"); w.close();
        check(!w.submitText(destination(1, 3)), "TERMINATED no recibe");
        equal(0L, w.protocolReception().frames());
    }
    public static void concurrentProducersKeepEveryCorrelation() throws Exception {
        try (Rig r = new Rig(10_000)) {
            var go = new CountDownLatch(1); var done = new CountDownLatch(4); var failure = new AtomicReference<Throwable>();
            List<Thread> producers = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                final int index = i;
                Thread t = new Thread(() -> {
                    try { go.await(); for (int j = 1; j <= 6; j++) { check(r.w.submitText(destination(index * 6 + j, 5)), "Encolado concurrente"); } }
                    catch (Throwable error) { failure.set(error); }
                    finally { done.countDown(); }
                }, "t64-producer-" + i);
                producers.add(t); t.start();
            }
            go.countDown(); check(done.await(6, TimeUnit.SECONDS), "Productores finalizan");
            for (Thread t : producers) { t.join(1000); check(!t.isAlive(), "Productor unido"); }
            if (failure.get() != null) { throw new AssertionError(failure.get()); }
            var ids = new HashSet<Long>();
            for (int i = 0; i < 24; i++) { ids.add(r.read(m -> m instanceof Ack).header().id()); }
            equal(24, ids.size()); equal(24L, r.w.protocolReception().frames());
        }
    }
    public static void mailboxProtectsEdtAndNegativeTimeout() throws Exception {
        var mailbox = new ProtocolMailbox();
        try { mailbox.await(Duration.ofNanos(-1)); throw new AssertionError("Timeout inválido aceptado"); }
        catch (IllegalArgumentException expected) { }
        var failure = new AtomicReference<Throwable>();
        javax.swing.SwingUtilities.invokeAndWait(() -> {
            try { mailbox.await(Duration.ofMillis(1)); failure.set(new AssertionError("EDT bloqueado")); }
            catch (IllegalStateException expected) { }
            catch (InterruptedException error) { failure.set(error); }
        });
        if (failure.get() != null) { throw new AssertionError(failure.get()); }
        check(mailbox.await(Duration.ZERO).isEmpty(), "Sondeo vacío");
    }
    public static void managerDispatchUsesTextAndStillConfirmsService() throws Exception {
        SimulationConfig config = new SimulationConfig(2, 8, 25, 80);
        var a = new ElevatorWorker(1, config, 0); var b = new ElevatorWorker(2, config, 0);
        var ids = new AtomicLong(); var manager = new ManagerWorker(config, 0, List.of(a, b), ids::incrementAndGet);
        try {
            a.start(); b.start(); manager.start(); var request = new HallRequest(id(50), 0, 3, DATE, Direction.UP);
            check(manager.submit(request), "Manejador recibe llamada");
            var result = manager.awaitResult(LIMIT).orElseThrow(); equal(ManagerWorker.Outcome.DISPATCHED, result.outcome());
            var sender = result.command().orElseThrow(); var receiver = sender.elevatorId() == 1 ? a : b;
            var reconstructed = receiver.awaitResult(LIMIT).orElseThrow().command();
            check(reconstructed != sender && reconstructed.sameContent(sender), "Texto real en despacho normal");
            await(() -> manager.call(request.getId()).map(c -> c.status() == HallCallSnapshot.Status.SERVED).orElse(false));
            equal(receiver.threadName(), receiver.protocolReception().processedBy());
        } finally {
            manager.close(); check(manager.awaitTermination(LIMIT), "Manejador cerrado");
            a.close(); b.close(); check(a.awaitTermination(LIMIT) && b.awaitTermination(LIMIT), "Cabinas cerradas");
        }
    }
    public static void controllerDestinationsAndResetUseText() throws Exception {
        var c = new SimulationController(new SimulationConfig(2, 8, 25, 80), 0); var mailbox = new ProtocolMailbox();
        var codec = new ProtocolCodec(c.config()); c.subscribeProtocol(mailbox);
        try {
            c.start().get(6, TimeUnit.SECONDS);
            equal(SimulationController.Outcome.ENQUEUED, c.submit(new CabinRequest(id(70), 0, 3, DATE, 2)).get(6, TimeUnit.SECONDS).outcome());
            await(() -> c.snapshot(2).currentFloor() == 3 && c.snapshot(2).phase() == ElevatorPhase.DWELLING);
            equal(1L, c.protocolReceptions().get(1).frames());
            check(codec.decodeResponse(mailbox.await(LIMIT).orElseThrow()).message().orElseThrow() instanceof Ack, "ACK de destino normal");
            c.reset().get(6, TimeUnit.SECONDS); equal(SimulationState.RESET, c.state());
            check(c.protocolReceptions().stream().allMatch(s -> s.frames() >= 1), "Cada reset cruza como texto");
        } finally { c.closeAsync().get(6, TimeUnit.SECONDS); }
    }
    public static void pauseStopAndResumeRetainTextualWork() throws Exception {
        var c = new SimulationController(new SimulationConfig(1, 8, 150, 80), 0);
        try {
            c.start().get(6, TimeUnit.SECONDS); c.submit(new CabinRequest(id(80), 0, 5, DATE, 1)).get(6, TimeUnit.SECONDS);
            await(() -> c.snapshot(1).phase() == ElevatorPhase.MOVING);
            c.pause().get(6, TimeUnit.SECONDS); equal(SimulationState.PAUSED, c.state());
            long remaining = c.snapshot(1).remainingNanos(); equal(1, c.snapshot(1).cabinRequests().size());
            equal(remaining, c.snapshot(1).remainingNanos());
            c.resume().get(6, TimeUnit.SECONDS); c.stop().get(6, TimeUnit.SECONDS);
            equal(SimulationState.STOPPED, c.state()); equal(1, c.snapshot(1).cabinRequests().size());
            c.start().get(6, TimeUnit.SECONDS); await(() -> c.snapshot(1).currentFloor() == 5);
            equal(1L, c.protocolReceptions().get(0).frames());
        } finally { c.closeAsync().get(6, TimeUnit.SECONDS); }
    }
    public static void syntaxCodecNeverGrantsAdmission() {
        var codec = new ProtocolCodec(new SimulationConfig(1, 2, 1, 1));
        var message = new Destination(new Header(1, 0, 99), id(1), 8);
        String text = codec.encodeSyntax(message);
        check(codec.decodeCommandSyntax(text).isSuccess(), "Forma válida sin edificio");
        equal(ErrorCode.UNKNOWN_ELEVATOR, codec.decodeCommand(text).failure().orElseThrow().code());
        try { codec.encode(message); throw new AssertionError("Codificador estricto omitió edificio"); }
        catch (ProtocolCodec.EncodingException expected) { equal(ErrorCode.UNKNOWN_ELEVATOR, expected.failure().code()); }
    }

    public static void closeTerminatesAcceptedResetInText() throws Exception {
        try (Rig r = new Rig(200)) {
            r.w.submitText(destination(1, 5));
            r.read(m -> m instanceof Move v && v.from() == 2);
            var reset = r.w.requestResetText("V1 CMD 10 EPOCH 1 ELEVATOR 1 RESET");
            r.read(m -> m instanceof Ack a && a.header().id() == 10);
            r.w.close(); check(r.w.awaitTermination(LIMIT), "Join durante retorno");
            Terminal terminal = r.terminal(10, TerminalKind.CANCELLED);
            equal(Optional.of(CancelReason.SHUTDOWN), terminal.reason()); expectFutureFailure(reset);
        }
    }
    public static void closeRejectsQueuedTextWithoutFakeAcceptance() throws Exception {
        try (Rig r = new Rig()) {
            r.w.requestStop().get(6, TimeUnit.SECONDS); r.w.submitText(destination(1, 5));
            r.w.close(); check(r.w.awaitTermination(LIMIT), "Join con inbox pendiente");
            r.error(ErrorCode.WORKER_UNAVAILABLE);
            check(r.seen.stream().noneMatch(m -> m instanceof Ack), "Nunca se admitió la entrada");
        }
    }

    private static String prefix(long id) { return "V1 CMD " + id + " EPOCH 0 ELEVATOR 1 "; }
    private static String destination(long id, int floor) { return prefix(id) + "DESTINATION " + id(id) + " " + floor; }
    private static String pickup(long id, int floor, Direction direction) { return prefix(id) + "PICKUP " + id(id) + " " + floor + " " + direction; }
    private static UUID id(long value) { return new UUID(0, value); }
    private static void check(boolean condition, String detail) { if (!condition) { throw new AssertionError(detail); } }
    private static void equal(Object expected, Object actual) {
        if (!java.util.Objects.equals(expected, actual)) { throw new AssertionError("Esperado " + expected + "; recibido " + actual); }
    }
    private static void await(BooleanSupplier condition) throws Exception {
        long end = System.nanoTime() + LIMIT.toNanos();
        while (!condition.getAsBoolean()) { check(System.nanoTime() < end, "Plazo agotado"); Thread.sleep(2); }
    }
    private static void expectFutureFailure(java.util.concurrent.CompletableFuture<?> future) throws Exception {
        try { future.get(6, TimeUnit.SECONDS); throw new AssertionError("Se esperaba rechazo"); }
        catch (java.util.concurrent.ExecutionException expected) { }
    }
    private static final class Rig implements AutoCloseable {
        final SimulationConfig config;
        final ElevatorWorker w;
        final ProtocolCodec codec;
        final List<ProtocolMessage> seen = new ArrayList<>();
        Rig() { this(25); }
        Rig(long move) {
            config = new SimulationConfig(2, 8, move, 150); codec = new ProtocolCodec(config);
            w = new ElevatorWorker(1, config, 0); check(w.start(), "Arranque");
        }
        ProtocolMessage read(Predicate<ProtocolMessage> filter) throws Exception {
            long end = System.nanoTime() + LIMIT.toNanos();
            while (System.nanoTime() < end) {
                var text = w.awaitProtocolMessage(Duration.ofMillis(100));
                if (text.isPresent()) {
                    var parsed = codec.decodeResponse(text.orElseThrow());
                    check(parsed.isSuccess(), "Respuesta V1 válida: " + text.orElseThrow());
                    ProtocolMessage message = parsed.message().orElseThrow(); seen.add(message);
                    if (filter.test(message)) { return message; }
                }
            }
            throw new AssertionError("No llegó respuesta esperada; vistos=" + seen);
        }
        Terminal terminal(long id, TerminalKind kind) throws Exception {
            return (Terminal) read(m -> m instanceof Terminal t && t.header().id() == id && t.kind() == kind);
        }
        ProtocolMessage.Error error(ErrorCode code) throws Exception {
            return (ProtocolMessage.Error) read(m -> m instanceof ProtocolMessage.Error e && e.code() == code);
        }
        @Override public void close() throws Exception {
            w.close(); check(w.awaitTermination(LIMIT), "Join real"); check(w.failure().isEmpty(), "Actor sin fallo");
        }
    }
    public static void main(String[] args) throws Exception {
        int passed = 0;
        for (var method : TextTransportScenarios.class.getDeclaredMethods()) {
            if (java.lang.reflect.Modifier.isPublic(method.getModifiers())
                    && java.lang.reflect.Modifier.isStatic(method.getModifiers()) && method.getParameterCount() == 0) {
                try { method.invoke(null); passed++; System.out.println("T64_OK " + method.getName()); }
                catch (java.lang.reflect.InvocationTargetException ex) { throw new AssertionError("Fallo " + method.getName(), ex.getCause()); }
            }
        }
        System.out.println("T64_RESULT scenarios=" + passed + "; failures=0");
    }
}
```

## src/test/java/com/elevatormanager/simulation/TextTransportTest.java

```java
package com.elevatormanager.simulation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Los mismos escenarios de transporte real bajo Maven/JUnit. */
@Timeout(60)
class TextTransportTest {
    @Test void rawDestinationIsParsedOnActor() throws Exception {
        TextTransportScenarios.rawDestinationIsParsedOnActor();
    }
    @Test void rawPickupUsesTransmittedDirectionAndUuid() throws Exception {
        TextTransportScenarios.rawPickupUsesTransmittedDirectionAndUuid();
    }
    @Test void changedTextChangesPhysicalDestination() throws Exception {
        TextTransportScenarios.changedTextChangesPhysicalDestination();
    }
    @Test void compatibilitySubmitReconstructsDistinctObjects() throws Exception {
        TextTransportScenarios.compatibilitySubmitReconstructsDistinctObjects();
    }
    @Test void explicitTimestampContextSurvives() throws Exception {
        TextTransportScenarios.explicitTimestampContextSurvives();
    }
    @Test void defaultTimestampIsStableNotWallClock() throws Exception {
        TextTransportScenarios.defaultTimestampIsStableNotWallClock();
    }
    @Test void ackPrecedesPhysicalCompletion() throws Exception {
        TextTransportScenarios.ackPrecedesPhysicalCompletion();
    }
    @Test void moveArrivalAndStopAreTextualEvents() throws Exception {
        TextTransportScenarios.moveArrivalAndStopAreTextualEvents();
    }
    @Test void directionChangesAreReportedInText() throws Exception {
        TextTransportScenarios.directionChangesAreReportedInText();
    }
    @Test void malformedCommandDoesNotMutateAndWorkerRecovers() throws Exception {
        TextTransportScenarios.malformedCommandDoesNotMutateAndWorkerRecovers();
    }
    @Test void invalidFloorIsRejectedBeforeMotion() throws Exception {
        TextTransportScenarios.invalidFloorIsRejectedBeforeMotion();
    }
    @Test void impossibleDirectionIsRejected() throws Exception {
        TextTransportScenarios.impossibleDirectionIsRejected();
    }
    @Test void unknownConfiguredElevatorIsRejected() throws Exception {
        TextTransportScenarios.unknownConfiguredElevatorIsRejected();
    }
    @Test void wrongExistingTargetCannotMoveThisCabin() throws Exception {
        TextTransportScenarios.wrongExistingTargetCannotMoveThisCabin();
    }
    @Test void staleAndFutureServiceEpochsAreRejected() throws Exception {
        TextTransportScenarios.staleAndFutureServiceEpochsAreRejected();
    }
    @Test void badUuidDoesNotCreateRequest() throws Exception {
        TextTransportScenarios.badUuidDoesNotCreateRequest();
    }
    @Test void ackInjectedIntoCommandQueueIsNotExecuted() throws Exception {
        TextTransportScenarios.ackInjectedIntoCommandQueueIsNotExecuted();
    }
    @Test void extraTokensDoNotPartiallyExecute() throws Exception {
        TextTransportScenarios.extraTokensDoNotPartiallyExecute();
    }
    @Test void oversizedFrameIsRejectedOnActor() throws Exception {
        TextTransportScenarios.oversizedFrameIsRejectedOnActor();
    }
    @Test void framingNormalizationIsAppliedAtReceiver() throws Exception {
        TextTransportScenarios.framingNormalizationIsAppliedAtReceiver();
    }
    @Test void malformedBurstCannotKillActor() throws Exception {
        TextTransportScenarios.malformedBurstCannotKillActor();
    }
    @Test void conditionalSendChecksSnapshotBeforeEnqueue() throws Exception {
        TextTransportScenarios.conditionalSendChecksSnapshotBeforeEnqueue();
    }
    @Test void compatibleConditionalSendAlsoSerializes() throws Exception {
        TextTransportScenarios.compatibleConditionalSendAlsoSerializes();
    }
    @Test void enqueueIsNotAdmissionWhileStopped() throws Exception {
        TextTransportScenarios.enqueueIsNotAdmissionWhileStopped();
    }
    @Test void serviceFifoIsPreserved() throws Exception {
        TextTransportScenarios.serviceFifoIsPreserved();
    }
    @Test void aliasesHaveCanonicalAckAndEachOwnTerminal() throws Exception {
        TextTransportScenarios.aliasesHaveCanonicalAckAndEachOwnTerminal();
    }
    @Test void sameCommandReplayDoesNotDuplicatePhysicalService() throws Exception {
        TextTransportScenarios.sameCommandReplayDoesNotDuplicatePhysicalService();
    }
    @Test void servedReplayReturnsKnownTerminalWithoutAnotherStop() throws Exception {
        TextTransportScenarios.servedReplayReturnsKnownTerminalWithoutAnotherStop();
    }
    @Test void conflictingIdCannotReplaceDestination() throws Exception {
        TextTransportScenarios.conflictingIdCannotReplaceDestination();
    }
    @Test void resetQueueAlsoCarriesAndParsesText() throws Exception {
        TextTransportScenarios.resetQueueAlsoCarriesAndParsesText();
    }
    @Test void resetCancelsActiveCommandBeforeReturnCompletes() throws Exception {
        TextTransportScenarios.resetCancelsActiveCommandBeforeReturnCompletes();
    }
    @Test void resetDiscardsQueuedTextBeforeAdmission() throws Exception {
        TextTransportScenarios.resetDiscardsQueuedTextBeforeAdmission();
    }
    @Test void malformedResetFailsFutureWithoutAdoptingEpoch() throws Exception {
        TextTransportScenarios.malformedResetFailsFutureWithoutAdoptingEpoch();
    }
    @Test void serviceCannotEnterThroughResetPriority() throws Exception {
        TextTransportScenarios.serviceCannotEnterThroughResetPriority();
    }
    @Test void resetCannotEnterThroughOrdinaryServiceQueue() throws Exception {
        TextTransportScenarios.resetCannotEnterThroughOrdinaryServiceQueue();
    }
    @Test void controlsPendingT65AreRejectedWithoutFakeAck() throws Exception {
        TextTransportScenarios.controlsPendingT65AreRejectedWithoutFakeAck();
    }
    @Test void textualObserversDoNotStealNativeEvents() throws Exception {
        TextTransportScenarios.textualObserversDoNotStealNativeEvents();
    }
    @Test void unsubscribeStopsFutureFrames() throws Exception {
        TextTransportScenarios.unsubscribeStopsFutureFrames();
    }
    @Test void closeCancelsAcceptedPendingText() throws Exception {
        TextTransportScenarios.closeCancelsAcceptedPendingText();
    }
    @Test void closedOrNewWorkerRefusesToEnqueue() throws Exception {
        TextTransportScenarios.closedOrNewWorkerRefusesToEnqueue();
    }
    @Test void concurrentProducersKeepEveryCorrelation() throws Exception {
        TextTransportScenarios.concurrentProducersKeepEveryCorrelation();
    }
    @Test void mailboxProtectsEdtAndNegativeTimeout() throws Exception {
        TextTransportScenarios.mailboxProtectsEdtAndNegativeTimeout();
    }
    @Test void managerDispatchUsesTextAndStillConfirmsService() throws Exception {
        TextTransportScenarios.managerDispatchUsesTextAndStillConfirmsService();
    }
    @Test void controllerDestinationsAndResetUseText() throws Exception {
        TextTransportScenarios.controllerDestinationsAndResetUseText();
    }
    @Test void pauseStopAndResumeRetainTextualWork() throws Exception {
        TextTransportScenarios.pauseStopAndResumeRetainTextualWork();
    }
    @Test void syntaxCodecNeverGrantsAdmission() throws Exception {
        TextTransportScenarios.syntaxCodecNeverGrantsAdmission();
    }
    @Test void closeTerminatesAcceptedResetInText() throws Exception {
        TextTransportScenarios.closeTerminatesAcceptedResetInText();
    }
    @Test void closeRejectsQueuedTextWithoutFakeAcceptance() throws Exception {
        TextTransportScenarios.closeRejectsQueuedTextWithoutFakeAcceptance();
    }
}
```
