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
