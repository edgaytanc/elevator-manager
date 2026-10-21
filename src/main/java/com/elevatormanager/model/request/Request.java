package com.elevatormanager.model.request;

import com.elevatormanager.model.SimulationConfig;
import com.elevatormanager.model.ValidationResult;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Solicitud lógica inmutable; su seguimiento operativo pertenece a otros componentes. */
public abstract class Request {
    private final UUID id;
    private final long epoch;
    private final int floor;
    private final Instant createdAt;

    protected Request(UUID id, long epoch, int floor, Instant createdAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        if (epoch < 0 || floor < 1) {
            throw new IllegalArgumentException("La época debe ser no negativa y el piso positivo.");
        }
        this.epoch = epoch;
        this.floor = floor;
    }

    public final UUID getId() { return id; }
    public final long getEpoch() { return epoch; }
    public final int getFloor() { return floor; }
    public final Instant getCreatedAt() { return createdAt; }

    /** Método plantilla: reglas comunes más despacho polimórfico específico. */
    public final ValidationResult validateAgainst(SimulationConfig config) {
        Objects.requireNonNull(config, "config");
        ValidationResult common = floor > config.floorCount()
                ? ValidationResult.invalid("FLOOR_OUT_OF_RANGE",
                        "El piso debe estar entre 1 y " + config.floorCount() + ".")
                : ValidationResult.valid();
        return common.combine(validateSpecific(config));
    }

    protected abstract ValidationResult validateSpecific(SimulationConfig config);

    /** Descripción humana; no es el formato del protocolo. */
    public abstract String describe();

    @Override
    public final boolean equals(Object other) {
        return this == other || other instanceof Request request && id.equals(request.id);
    }

    @Override
    public final int hashCode() {
        return id.hashCode();
    }
}
