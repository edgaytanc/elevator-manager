package com.elevatormanager.diagnostics;

import com.elevatormanager.dispatch.HallCallSnapshot;
import com.elevatormanager.logging.SessionLog;
import com.elevatormanager.model.Direction;
import com.elevatormanager.model.SimulationConfig;
import com.elevatormanager.model.request.CabinRequest;
import com.elevatormanager.model.request.HallRequest;
import com.elevatormanager.simulation.ExecutionEvent;
import com.elevatormanager.simulation.SimulationController;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

/** T42: muestra con dos actores de cabina, manejador real y logging en su propio hilo. */
public final class EventLoggingDemo {
    private EventLoggingDemo() { }

    public static void main(String[] args) throws Exception {
        if (args.length > 1) { throw new IllegalArgumentException("Uso: EventLoggingDemo [directorio]"); }
        Path directory = Path.of(args.length == 0 ? "logs/t42" : args[0]);
        var config = new SimulationConfig(2, 6, 60, 30);
        try (var log = SessionLog.open(directory, config.elevatorCount())) {
            var controller = new SimulationController(config, 0, log);
            try {
                requireEnqueued(controller.start().get(10, TimeUnit.SECONDS), SimulationController.Outcome.APPLIED);
                requireEnqueued(controller.submit(cabin(4)).get(10, TimeUnit.SECONDS), SimulationController.Outcome.ENQUEUED);
                until(controller, ExecutionEvent.Type.STOP_ENDED, 1, 4);
                requireEnqueued(controller.submit(cabin(2)).get(10, TimeUnit.SECONDS), SimulationController.Outcome.ENQUEUED);
                until(controller, ExecutionEvent.Type.STOP_ENDED, 1, 2);
                var hall = new HallRequest(UUID.randomUUID(), 0, 3, Instant.now(), Direction.UP);
                requireEnqueued(controller.submit(hall).get(10, TimeUnit.SECONDS), SimulationController.Outcome.ENQUEUED);
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                while (true) {
                    var call = controller.calls().get(hall.getId());
                    if (call != null && call.status() == HallCallSnapshot.Status.SERVED) { break; }
                    if (System.nanoTime() >= deadline) { throw new IllegalStateException("Llamada no confirmada."); }
                    LockSupport.parkNanos(1_000_000);
                }
                requireEnqueued(controller.reset().get(10, TimeUnit.SECONDS), SimulationController.Outcome.APPLIED);
            } finally {
                controller.closeAsync().get(15, TimeUnit.SECONDS);
                if (!controller.awaitTermination(Duration.ofSeconds(10))) {
                    throw new IllegalStateException("Cierre no confirmado.");
                }
            }
        }
        System.out.println("T42: muestra terminada; archivos en " + directory.toAbsolutePath().normalize());
    }

    private static CabinRequest cabin(int floor) {
        return new CabinRequest(UUID.randomUUID(), 0, floor, Instant.now(), 1);
    }

    private static void until(SimulationController controller, ExecutionEvent.Type type, int id, int floor)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (true) {
            long left = deadline - System.nanoTime();
            if (left <= 0) { throw new IllegalStateException("Evento no observado: " + type); }
            var event = controller.awaitEvent(Duration.ofNanos(left)).orElseThrow();
            if (event.type() == type && event.elevatorId() == id && event.snapshot().currentFloor() == floor) { return; }
        }
    }

    private static void requireEnqueued(SimulationController.Result result, SimulationController.Outcome expected) {
        if (result.outcome() != expected) { throw new IllegalStateException(result.detail()); }
    }
}
