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
