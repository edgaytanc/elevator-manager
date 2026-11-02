package com.elevatormanager.diagnostics;

import com.elevatormanager.model.ElevatorPhase;
import com.elevatormanager.model.SimulationConfig;
import com.elevatormanager.model.SimulationState;
import com.elevatormanager.model.request.CabinRequest;
import com.elevatormanager.protocol.ProtocolCodec;
import com.elevatormanager.protocol.ProtocolMessage;
import com.elevatormanager.simulation.ProtocolMailbox;
import com.elevatormanager.simulation.SimulationController;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** T65: STATUS en pausa y reset confirmado por respuestas de todas las cabinas. */
public final class ProtocolIntegrationDemo {
    private ProtocolIntegrationDemo() { }
    public static void main(String[] args) throws Exception {
        var config = new SimulationConfig(2, 6, 100, 100);
        var codec = new ProtocolCodec(config);
        var controller = new SimulationController(config, 0);
        var mailbox = new ProtocolMailbox(); controller.subscribeProtocol(mailbox);
        try {
            controller.start().get(6, TimeUnit.SECONDS);
            controller.submit(new CabinRequest(UUID.fromString("12345678-1234-1234-1234-123456789abc"),
                    0, 6, Instant.EPOCH, 1)).get(6, TimeUnit.SECONDS);
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(6);
            while (controller.snapshot(1).currentFloor() < 3) {
                if (System.nanoTime() > end) { throw new IllegalStateException("No inició recorrido."); }
                TimeUnit.MILLISECONDS.sleep(2);
            }
            controller.pause().get(6, TimeUnit.SECONDS);
            long frozen = controller.timeView().activeNanos();
            ProtocolMessage.State status = controller.queryStatus(1).get(6, TimeUnit.SECONDS);
            if (status.phase() != ElevatorPhase.HELD || controller.timeView().activeNanos() != frozen) {
                throw new IllegalStateException("STATUS modificó la pausa.");
            }
            System.out.println("T65_STATUS cmd=" + status.header().id() + "; cabin=1; epoch=0; phase="
                    + status.phase() + "; remaining_ms=" + status.remainingMillis() + "; clockFrozen=true");
            controller.reset().get(6, TimeUnit.SECONDS);
            for (int id = 1; id <= config.elevatorCount(); id++) {
                var proof = controller.queryStatus(id).get(6, TimeUnit.SECONDS);
                if (proof.header().epoch() != 1 || proof.floor() != 1 || !proof.internal().isEmpty()) {
                    throw new IllegalStateException("Estado posterior al reset inválido.");
                }
            }
            controller.closeAsync().get(6, TimeUnit.SECONDS);
            if (!controller.awaitTermination(Duration.ofSeconds(6)) || controller.state() != SimulationState.CLOSED) {
                throw new IllegalStateException("Cierre sin join completo.");
            }
            while (true) {
                var text = mailbox.await(Duration.ZERO);
                if (text.isEmpty()) { break; }
                var message = codec.decodeResponse(text.orElseThrow()).message().orElseThrow();
                if (message instanceof ProtocolMessage.Ack || message instanceof ProtocolMessage.Terminal
                        || message instanceof ProtocolMessage.State state && state.header().id() > 0) {
                    System.out.println("T65_WIRE " + text.orElseThrow());
                }
            }
            System.out.println("T65_INTEGRATION_OK; epoch=1; state=CLOSED; responsesJoined=true");
        } finally { controller.closeAsync().get(6, TimeUnit.SECONDS); }
    }
}
