package com.elevatormanager.diagnostics;

import com.elevatormanager.io.RequestEntry;
import com.elevatormanager.io.RequestFileReader;
import com.elevatormanager.model.SimulationConfig;
import java.io.PrintStream;
import java.nio.file.Path;

/** T44: valida un archivo sin crear sesión, solicitudes con identidad ni trabajadores. */
public final class RequestFileDemo {
    private RequestFileDemo() { }

    public static void main(String[] args) {
        int status = run(args, System.out, System.err);
        if (status != 0) { System.exit(status); }
    }

    /** 0: lote válido; 1: archivo rechazado; 2: argumentos de diagnóstico inválidos. */
    public static int run(String[] args, PrintStream output, PrintStream errors) {
        if (args.length != 3) {
            errors.println("Uso: RequestFileDemo <archivo> <cantidadElevadores> <cantidadPisos>");
            return 2;
        }
        try {
            // Los tiempos no intervienen en el formato; no se inicia simulación alguna.
            var config = SimulationConfig.fromText(args[1], args[2], "1", "1");
            var result = new RequestFileReader().read(Path.of(args[0]), config);
            if (!result.isValid()) {
                for (var diagnostic : result.diagnostics()) {
                    String location = diagnostic.lineNumber().isPresent()
                            ? "Línea " + diagnostic.lineNumber().getAsInt() : "Archivo";
                    errors.println(location + " — " + diagnostic.code() + ": " + diagnostic.message());
                    errors.println("Formato esperado: " + diagnostic.expectedFormat());
                    diagnostic.cause().ifPresent(cause -> errors.println("Causa: " + cause));
                }
                errors.println("T44: archivo rechazado; no se preparó ningún lote parcial.");
                return 1;
            }
            var batch = result.batch().orElseThrow();
            output.println("T44: lote válido; instrucciones=" + batch.entries().size()
                    + "; líneas=" + batch.physicalLineCount() + "; bytes=" + batch.byteCount());
            for (var entry : batch.entries()) {
                String instruction = entry instanceof RequestEntry.Hall hall
                        ? "HALL " + hall.floor() + " " + hall.direction()
                        : "CABIN " + ((RequestEntry.Cabin) entry).elevatorId() + " " + entry.floor();
                output.println("Línea " + entry.lineNumber() + ": " + instruction);
            }
            output.println("Validación terminada; no se ejecutó la simulación.");
            return 0;
        } catch (IllegalArgumentException error) {
            errors.println("Argumentos inválidos: " + error.getMessage());
            return 2;
        }
    }
}
