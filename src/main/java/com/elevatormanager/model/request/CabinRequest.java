package com.elevatormanager.model.request;

import com.elevatormanager.model.SimulationConfig;
import com.elevatormanager.model.ValidationResult;
import java.time.Instant;
import java.util.UUID;

/** Destino interno de una cabina concreta; el sentido lo decide su recorrido. */
public final class CabinRequest extends Request {
    private final int elevatorId;

    public CabinRequest(UUID id, long epoch, int floor, Instant createdAt, int elevatorId) {
        super(id, epoch, floor, createdAt);
        if (elevatorId < 1) {
            throw new IllegalArgumentException("El ID de elevador debe ser positivo.");
        }
        this.elevatorId = elevatorId;
    }

    public int getElevatorId() { return elevatorId; }

    @Override
    protected ValidationResult validateSpecific(SimulationConfig config) {
        return elevatorId > config.elevatorCount()
                ? ValidationResult.invalid("ELEVATOR_OUT_OF_RANGE",
                        "El ID de elevador debe estar entre 1 y " + config.elevatorCount() + ".")
                : ValidationResult.valid();
    }

    @Override
    public String describe() {
        return "Destino interno: elevador " + elevatorId + ", piso " + getFloor();
    }
}
