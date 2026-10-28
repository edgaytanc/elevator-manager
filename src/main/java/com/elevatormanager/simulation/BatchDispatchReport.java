package com.elevatormanager.simulation;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Intento único de envío en orden; ENQUEUED no equivale a aceptación ni atención. */
public record BatchDispatchReport(ImportedRequestBatch batch, List<Entry> entries) {
    public record Entry(int lineNumber, UUID requestId, SimulationController.Result result) {
        public Entry {
            Objects.requireNonNull(requestId, "requestId"); Objects.requireNonNull(result, "result");
            if (lineNumber < 1) { throw new IllegalArgumentException("Línea no positiva."); }
        }
    }
    public BatchDispatchReport {
        Objects.requireNonNull(batch, "batch"); entries = List.copyOf(entries);
        if (entries.size() != batch.requests().size()) { throw new IllegalArgumentException("Informe incompleto."); }
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).lineNumber() != batch.source().entries().get(i).lineNumber()
                    || !entries.get(i).requestId().equals(batch.requests().get(i).getId())
                    || entries.get(i).result().epoch() != batch.epoch()
                    || (entries.get(i).result().outcome() != SimulationController.Outcome.ENQUEUED
                    && entries.get(i).result().outcome() != SimulationController.Outcome.REJECTED)) {
                throw new IllegalArgumentException("Informe no correlacionado con el lote.");
            }
            var request = batch.requests().get(i);
            var result = entries.get(i).result();
            if (result.command().isPresent()) {
                var command = result.command().orElseThrow();
                if (!(request instanceof com.elevatormanager.model.request.CabinRequest)
                        || !command.request().getId().equals(request.getId()) || command.epoch() != batch.epoch()) {
                    throw new IllegalArgumentException("Comando ajeno a la solicitud informada.");
                }
            } else if (request instanceof com.elevatormanager.model.request.CabinRequest
                    && result.outcome() == SimulationController.Outcome.ENQUEUED) {
                throw new IllegalArgumentException("Envío interno sin comando correlacionado.");
            }
        }
    }
}
