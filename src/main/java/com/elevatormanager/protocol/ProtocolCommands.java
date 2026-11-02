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
