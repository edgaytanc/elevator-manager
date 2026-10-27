package com.elevatormanager.dispatch;

import com.elevatormanager.model.request.HallRequest;
import com.elevatormanager.protocol.Command;
import java.util.Optional;
import java.util.UUID;

/** Vista inmutable de una identidad externa, incluida su relación con la canónica. */
public record HallCallSnapshot(HallRequest request, UUID canonicalRequestId,
        Status status, Optional<Command.Pickup> command, int attempts, String detail) {
    public enum Status {
        RECEIVED, DISPATCHED, ASSIGNED, SERVED, CANCELLED, UNCONFIRMED
    }
}
