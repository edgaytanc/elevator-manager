package com.elevatormanager.io;

import com.elevatormanager.model.Direction;
import com.elevatormanager.model.SimulationConfig;
import com.elevatormanager.model.ValidationResult;
import java.util.Objects;

/** Descriptor de archivo sin UUID, época, fecha, comando ni efecto sobre actores. */
public sealed interface RequestEntry permits RequestEntry.Hall, RequestEntry.Cabin {
    int lineNumber();
    int floor();

    /** Validación pura; conserva los códigos del modelo y prioridad de campos del archivo. */
    default ValidationResult validateAgainst(SimulationConfig config) {
        Objects.requireNonNull(config, "config");
        if (this instanceof Cabin cabin && cabin.elevatorId() > config.elevatorCount()) {
            return ValidationResult.invalid("ELEVATOR_OUT_OF_RANGE", "El ID debe estar entre 1 y " + config.elevatorCount() + ".");
        }
        if (floor() > config.floorCount()) {
            return ValidationResult.invalid("FLOOR_OUT_OF_RANGE", "El piso debe estar entre 1 y " + config.floorCount() + ".");
        }
        if (this instanceof Hall hall) {
            if (hall.direction() == Direction.UP && floor() == config.floorCount()) {
                return ValidationResult.invalid("HALL_UP_AT_TOP", "No se puede subir desde el último piso.");
            }
            if (hall.direction() == Direction.DOWN && floor() == 1) {
                return ValidationResult.invalid("HALL_DOWN_AT_BOTTOM", "No se puede bajar desde el primer piso.");
            }
        }
        return ValidationResult.valid();
    }

    record Hall(int lineNumber, int floor, Direction direction) implements RequestEntry {
        public Hall {
            requirePosition(lineNumber, floor);
            Objects.requireNonNull(direction, "direction");
        }
    }

    record Cabin(int lineNumber, int elevatorId, int floor) implements RequestEntry {
        public Cabin {
            requirePosition(lineNumber, floor);
            if (elevatorId < 1) { throw new IllegalArgumentException("ID de cabina no positivo."); }
        }
    }

    private static void requirePosition(int lineNumber, int floor) {
        if (lineNumber < 1 || lineNumber > RequestFileFormat.MAX_LINES || floor < 1) {
            throw new IllegalArgumentException("Línea o piso fuera de rango.");
        }
    }
}
