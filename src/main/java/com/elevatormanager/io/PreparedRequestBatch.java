package com.elevatormanager.io;

import com.elevatormanager.model.SimulationConfig;
import java.util.List;
import java.util.Objects;

/** Lote válido e inmutable, todavía sin confirmar ni ejecutar en una simulación. */
public record PreparedRequestBatch(SimulationConfig config, List<RequestEntry> entries,
        int physicalLineCount, int byteCount) {
    public PreparedRequestBatch {
        Objects.requireNonNull(config, "config");
        entries = List.copyOf(entries);
        if (entries.isEmpty() || physicalLineCount < 1 || physicalLineCount > RequestFileFormat.MAX_LINES
                || byteCount < 1 || byteCount > RequestFileFormat.MAX_FILE_BYTES) {
            throw new IllegalArgumentException("Lote vacío o metadatos fuera del contrato.");
        }
        int previous = 0;
        for (var entry : entries) {
            if (entry.lineNumber() <= previous || entry.lineNumber() > physicalLineCount
                    || !entry.validateAgainst(config).isValid()) {
                throw new IllegalArgumentException("Entrada inválida, fuera de orden o incompatible con el edificio.");
            }
            previous = entry.lineNumber();
        }
    }
}
