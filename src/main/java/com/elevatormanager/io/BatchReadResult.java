package com.elevatormanager.io;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Un lote completo válido o diagnósticos. Nunca expone entradas parcialmente válidas. */
public record BatchReadResult(Optional<PreparedRequestBatch> batch, List<BatchDiagnostic> diagnostics) {
    public BatchReadResult {
        Objects.requireNonNull(batch, "batch");
        diagnostics = List.copyOf(diagnostics);
        if (batch.isPresent() != diagnostics.isEmpty()) {
            throw new IllegalArgumentException("Se requiere exclusivamente lote válido o diagnósticos.");
        }
    }
    public boolean isValid() { return batch.isPresent(); }
    public static BatchReadResult valid(PreparedRequestBatch batch) {
        return new BatchReadResult(Optional.of(batch), List.of());
    }
    public static BatchReadResult invalid(List<BatchDiagnostic> diagnostics) {
        return new BatchReadResult(Optional.empty(), diagnostics);
    }
}
