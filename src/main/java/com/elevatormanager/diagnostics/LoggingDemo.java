package com.elevatormanager.diagnostics;

import com.elevatormanager.logging.LoggerFactory;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** T41: comprobación manual del logging; sus mensajes no son movimientos del motor. */
public final class LoggingDemo {
    private LoggingDemo() { }

    public static void main(String[] args) throws Exception {
        if (args.length > 1) { throw new IllegalArgumentException("Uso: LoggingDemo [directorio]"); }
        Path directory = Path.of(args.length == 0 ? "logs" : args[0]);
        try (var logs = LoggerFactory.open(directory, 2)) {
            var producers = Executors.newFixedThreadPool(2);
            try {
                var tasks = new ArrayList<Future<?>>();
                for (int id = 1; id <= 2; id++) {
                    int cabin = id;
                    tasks.add(producers.submit(() -> {
                        for (int sample = 1; sample <= 5; sample++) {
                            logs.loggerFor(cabin).info("PRUEBA_LOGGING; muestra=" + sample);
                        }
                    }));
                }
                for (var task : tasks) { task.get(10, TimeUnit.SECONDS); }
            } finally {
                producers.shutdownNow();
                if (!producers.awaitTermination(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("No terminaron los productores del diagnóstico.");
                }
            }
        }
        System.out.println("T41: archivos en " + directory.toAbsolutePath().normalize());
    }
}
