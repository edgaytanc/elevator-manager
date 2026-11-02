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
