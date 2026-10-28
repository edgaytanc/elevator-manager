package com.elevatormanager.diagnostics;

import com.elevatormanager.dispatch.HallCallSnapshot;
import com.elevatormanager.logging.SessionLog;
import com.elevatormanager.model.SimulationConfig;
import com.elevatormanager.model.SimulationState;
import com.elevatormanager.model.request.HallRequest;
import com.elevatormanager.protocol.Command;
import com.elevatormanager.simulation.BatchImportResult;
import com.elevatormanager.simulation.ExecutionEvent;
import com.elevatormanager.simulation.ImportedRequestBatch;
import com.elevatormanager.simulation.SimulationController;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** T45: importación real, Start único y seguimiento de servicios con identidades canónicas. */
public final class RequestImportDemo {
    private RequestImportDemo() { }

    public static void main(String[] args) throws Exception {
        int status = run(args);
        if (status != 0) { System.exit(status); }
    }

    public static int run(String[] args) throws Exception {
        if (args.length < 3 || args.length > 4) {
            System.err.println("Uso: RequestImportDemo <archivo> <elevadores> <pisos> [directorioLogs]");
            return 2;
        }
        final SimulationConfig config;
        final Path file;
        final Path logDirectory;
        try {
            config = SimulationConfig.fromText(args[1], args[2], "30", "10");
            file = Path.of(args[0]); logDirectory = Path.of(args.length == 4 ? args[3] : "logs/t45");
        } catch (IllegalArgumentException error) { System.err.println(error.getMessage()); return 2; }
        try (var log = SessionLog.open(logDirectory, config.elevatorCount())) {
            var controller = new SimulationController(config, 0, log);
            try {
                var result = controller.importRequests(file).get(15, TimeUnit.SECONDS);
                if (result.outcome() != BatchImportResult.Outcome.IMPORTED) {
                    System.err.println("T45: " + result.outcome() + "; " + result.detail());
                    for (var diagnostic : result.diagnostics()) {
                        System.err.println(diagnostic.code() + "; linea="
                                + (diagnostic.lineNumber().isPresent() ? diagnostic.lineNumber().getAsInt() : "archivo")
                                + "; " + diagnostic.message());
                        diagnostic.cause().ifPresent(cause -> System.err.println("Causa: " + cause));
                    }
                    return 1;
                }
                ImportedRequestBatch batch = result.batch().orElseThrow();
                if (controller.state() != SimulationState.RESET
                        || controller.snapshots().stream().anyMatch(s -> s.currentFloor() != 1 || s.nextFloor().isPresent())) {
                    throw new IllegalStateException("Importar no debe mover cabinas.");
                }
                System.out.println("T45: lote confirmado en RESET; solicitudes=" + batch.requests().size() + "; sin movimiento.");
                if (controller.start().get(10, TimeUnit.SECONDS).outcome() != SimulationController.Outcome.APPLIED) {
                    throw new IllegalStateException("Start no fue aplicado.");
                }
                var report = controller.lastBatchDispatch().orElseThrow();
                if (report.entries().stream().anyMatch(e -> e.result().outcome() != SimulationController.Outcome.ENQUEUED)) {
                    throw new IllegalStateException("Alguna entrada no pudo enviarse; consultar informe.");
                }
                awaitServices(controller, batch);
                System.out.println("T45: lote enviado una vez; todos sus servicios o alias activos confirmados.");
            } finally {
                controller.closeAsync().get(15, TimeUnit.SECONDS);
                if (!controller.awaitTermination(Duration.ofSeconds(10))) { throw new IllegalStateException("Cierre no confirmado."); }
            }
        }
        System.out.println("T45: sesión cerrada; registros en " + logDirectory.toAbsolutePath().normalize());
        return 0;
    }

    private static void awaitServices(SimulationController controller, ImportedRequestBatch batch) throws Exception {
        var canonical = new HashMap<UUID, UUID>();
        var served = new HashSet<UUID>();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (true) {
            var calls = controller.calls();
            boolean complete = batch.requests().stream().allMatch(request -> request instanceof HallRequest
                    ? calls.containsKey(request.getId()) && calls.get(request.getId()).status() == HallCallSnapshot.Status.SERVED
                    : canonical.containsKey(request.getId()) && served.contains(canonical.get(request.getId())));
            if (complete) { return; }
            long left = deadline - System.nanoTime();
            if (left <= 0) { throw new IllegalStateException("La muestra agotó su plazo de servicio de 30 s."); }
            if (controller.failure().isPresent()) { throw new IllegalStateException("Sesión con incidencia.", controller.failure().orElseThrow()); }
            var optional = controller.awaitEvent(Duration.ofNanos(Math.min(left, TimeUnit.MILLISECONDS.toNanos(100))));
            if (optional.isEmpty()) { continue; }
            ExecutionEvent event = optional.orElseThrow();
            if (event.type() == ExecutionEvent.Type.WORKER_FAILED || event.type() == ExecutionEvent.Type.COMMAND_REJECTED) {
                throw new IllegalStateException("Incidencia del actor: " + event.type() + "; " + event.detail());
            }
            if (event.command().isPresent() && event.canonicalRequestId().isPresent()) {
                Command command = event.command().orElseThrow();
                UUID request = command instanceof Command.Destination destination ? destination.request().getId()
                        : command instanceof Command.Pickup pickup ? pickup.request().getId() : null;
                if (request != null) { canonical.put(request, event.canonicalRequestId().orElseThrow()); }
            }
            if (event.type() == ExecutionEvent.Type.COMMAND_COMPLETED) { event.canonicalRequestId().ifPresent(served::add); }
        }
    }
}
