package com.elevatormanager.simulation;

import com.elevatormanager.model.ElevatorSnapshot;
import com.elevatormanager.protocol.Command;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Hecho inmutable capturado por el actor, antes de ejecutar la siguiente transición.
 * sequence ordena eventos de una cabina; revision ordena cambios de su modelo.
 * referenceNanos pertenece al reloj monotónico de esta JVM, no a una fecha civil.
 */
public record ExecutionEvent(long sequence, Type type, ElevatorSnapshot snapshot,
        Optional<Command> command, Optional<UUID> canonicalRequestId, String detail) {
    public enum Type {
        COMMAND_ACCEPTED, COMMAND_DUPLICATE, COMMAND_REJECTED, COMMAND_REPLAYED,
        DEPARTED, ARRIVED, STOP_STARTED, STOP_ENDED, DIRECTION_CHANGED,
        COMMAND_COMPLETED, WORKER_TERMINATED, WORKER_FAILED, CONTROL_STOPPED, CONTROL_RESUMED
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
