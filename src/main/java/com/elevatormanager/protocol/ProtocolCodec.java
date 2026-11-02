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
