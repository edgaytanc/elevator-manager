package com.elevatormanager.protocol;

import com.elevatormanager.model.SimulationConfig;
import com.elevatormanager.model.ValidationResult;
import com.elevatormanager.model.request.CabinRequest;
import com.elevatormanager.model.request.HallRequest;
import java.util.Objects;

/** Contratos tipados; codificación textual y ejecución se conectarán después. */
public sealed interface Command permits Command.Pickup, Command.Destination, Command.Reset {
    long commandId();
    long epoch();
    int elevatorId();

    /** Compara contenido operativo; Request.equals solo compara UUID. */
    default boolean sameContent(Command other) {
        if (other == null || getClass() != other.getClass() || commandId() != other.commandId()
                || epoch() != other.epoch() || elevatorId() != other.elevatorId()) {
            return false;
        }
        if (this instanceof Pickup first && other instanceof Pickup second) {
            return first.request().getId().equals(second.request().getId())
                    && first.request().getFloor() == second.request().getFloor()
                    && first.request().getDirection() == second.request().getDirection();
        }
        if (this instanceof Destination first && other instanceof Destination second) {
            return first.request().getId().equals(second.request().getId())
                    && first.request().getFloor() == second.request().getFloor();
        }
        return true;
    }

    /** Valida edificio y contenido; no autoriza ejecución ni adopción de época. */
    default ValidationResult validateAgainst(SimulationConfig config) {
        Objects.requireNonNull(config, "config");
        ValidationResult target = elevatorId() > config.elevatorCount()
                ? ValidationResult.invalid("UNKNOWN_ELEVATOR", "La cabina destinataria no existe.")
                : ValidationResult.valid();
        if (this instanceof Pickup pickup) {
            return target.combine(pickup.request().validateAgainst(config));
        }
        if (this instanceof Destination destination) {
            return target.combine(destination.request().validateAgainst(config));
        }
        return target;
    }

    private static void requireHeader(long commandId, int elevatorId) {
        if (commandId < 1 || elevatorId < 1) {
            throw new IllegalArgumentException("ID de comando y destinatario deben ser positivos.");
        }
    }

    /** Recogida externa ya asignada por el manejador a una cabina concreta. */
    record Pickup(long commandId, int elevatorId, HallRequest request) implements Command {
        public Pickup {
            Command.requireHeader(commandId, elevatorId);
            Objects.requireNonNull(request, "request");
        }

        @Override public long epoch() { return request.getEpoch(); }
    }

    /** Destino interno; el destinatario debe coincidir con la solicitud. */
    record Destination(long commandId, int elevatorId, CabinRequest request) implements Command {
        public Destination {
            Command.requireHeader(commandId, elevatorId);
            Objects.requireNonNull(request, "request");
            if (elevatorId != request.getElevatorId()) {
                throw new IllegalArgumentException("Destinatario distinto de la cabina solicitada.");
            }
        }

        @Override public long epoch() { return request.getEpoch(); }
    }

    /** Nueva época propuesta; su autorización y retorno físico corresponden al receptor. */
    record Reset(long commandId, long epoch, int elevatorId) implements Command {
        public Reset {
            Command.requireHeader(commandId, elevatorId);
            if (epoch < 0) {
                throw new IllegalArgumentException("La época debe ser no negativa.");
            }
        }
    }
}
