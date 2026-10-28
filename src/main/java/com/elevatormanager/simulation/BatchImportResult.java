package com.elevatormanager.simulation;

import com.elevatormanager.io.BatchDiagnostic;
import com.elevatormanager.model.SimulationState;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** IMPORTED confirma preparación en RESET, nunca envío ni servicio físico. */
public record BatchImportResult(Outcome outcome, SimulationState state, long epoch,
        UUID sessionId, long generation, String detail,
        Optional<ImportedRequestBatch> batch, List<BatchDiagnostic> diagnostics) {
    public enum Outcome { IMPORTED, INVALID, REJECTED, SUPERSEDED, CANCELLED }
    public BatchImportResult {
        Objects.requireNonNull(outcome, "outcome"); Objects.requireNonNull(state, "state");
        Objects.requireNonNull(sessionId, "sessionId"); Objects.requireNonNull(detail, "detail");
        Objects.requireNonNull(batch, "batch"); diagnostics = List.copyOf(diagnostics);
        if (epoch < 0 || generation < 0 || detail.isBlank()
                || batch.isPresent() != (outcome == Outcome.IMPORTED)
                || !diagnostics.isEmpty() != (outcome == Outcome.INVALID)
                || (batch.isPresent() && (state != SimulationState.RESET || batch.orElseThrow().epoch() != epoch))) {
            throw new IllegalArgumentException("Resultado de importación incoherente.");
        }
    }
}
