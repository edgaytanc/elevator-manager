package com.elevatormanager.simulation;

import com.elevatormanager.io.PreparedRequestBatch;
import com.elevatormanager.io.RequestEntry;
import com.elevatormanager.model.request.CabinRequest;
import com.elevatormanager.model.request.HallRequest;
import com.elevatormanager.model.request.Request;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Lote confirmado en una sesión/época; conserva identidades hasta su consumo único. */
public record ImportedRequestBatch(UUID id, long epoch, Instant confirmedAt,
        PreparedRequestBatch source, List<Request> requests) {
    public ImportedRequestBatch {
        Objects.requireNonNull(id, "id"); Objects.requireNonNull(confirmedAt, "confirmedAt");
        Objects.requireNonNull(source, "source"); requests = List.copyOf(requests);
        if (epoch < 0 || requests.size() != source.entries().size()) {
            throw new IllegalArgumentException("Época o cantidad de solicitudes incompatible.");
        }
        var identities = new HashSet<UUID>();
        for (int i = 0; i < requests.size(); i++) {
            Request request = requests.get(i);
            RequestEntry entry = source.entries().get(i);
            boolean sameKind = entry instanceof RequestEntry.Hall hall
                    ? request instanceof HallRequest call && call.getDirection() == hall.direction()
                    : request instanceof CabinRequest cabin && cabin.getElevatorId() == ((RequestEntry.Cabin) entry).elevatorId();
            if (!sameKind || request.getFloor() != entry.floor() || request.getEpoch() != epoch
                    || !request.getCreatedAt().equals(confirmedAt) || !identities.add(request.getId())
                    || !request.validateAgainst(source.config()).isValid()) {
                throw new IllegalArgumentException("Solicitud incompatible con el descriptor confirmado.");
            }
        }
    }

    static ImportedRequestBatch confirm(PreparedRequestBatch source, long epoch) {
        Instant at = Instant.now();
        var requests = new ArrayList<Request>();
        for (var entry : source.entries()) {
            UUID id = UUID.randomUUID();
            requests.add(entry instanceof RequestEntry.Hall hall
                    ? new HallRequest(id, epoch, hall.floor(), at, hall.direction())
                    : new CabinRequest(id, epoch, entry.floor(), at, ((RequestEntry.Cabin) entry).elevatorId()));
        }
        return new ImportedRequestBatch(UUID.randomUUID(), epoch, at, source, requests);
    }
}
