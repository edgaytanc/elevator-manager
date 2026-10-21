package com.elevatormanager.model.request;

import com.elevatormanager.model.Direction;
import com.elevatormanager.model.SimulationConfig;
import com.elevatormanager.model.ValidationResult;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Llamada externa compartida: piso y sentido, sin cabina asignada. */
public final class HallRequest extends Request {
    private final Direction direction;

    public HallRequest(UUID id, long epoch, int floor, Instant createdAt, Direction direction) {
        super(id, epoch, floor, createdAt);
        this.direction = Objects.requireNonNull(direction, "direction");
    }

    public Direction getDirection() { return direction; }

    @Override
    protected ValidationResult validateSpecific(SimulationConfig config) {
        if (getFloor() > config.floorCount()) {
            return ValidationResult.valid(); // El error de piso ya lo informa la clase base.
        }
        if (direction == Direction.UP && getFloor() == config.floorCount()) {
            return ValidationResult.invalid("HALL_UP_AT_TOP",
                    "No se puede solicitar subida desde el último piso.");
        }
        if (direction == Direction.DOWN && getFloor() == 1) {
            return ValidationResult.invalid("HALL_DOWN_AT_BOTTOM",
                    "No se puede solicitar bajada desde el primer piso.");
        }
        return ValidationResult.valid();
    }

    @Override
    public String describe() {
        return "Llamada externa: piso " + getFloor() + ", dirección " + direction;
    }
}
