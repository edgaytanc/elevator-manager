package com.elevatormanager.model;

import com.elevatormanager.model.request.CabinRequest;
import com.elevatormanager.model.request.HallRequest;
import com.elevatormanager.model.request.Request;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;

/** Instantánea profundamente inmutable; no contiene una referencia a ElevatorState. */
public record ElevatorSnapshot(
        int id, SimulationConfig config, long epoch, long revision,
        int currentFloor, OptionalInt nextFloor, Direction direction, ElevatorPhase phase,
        Optional<ElevatorPhase> suspendedPhase, Optional<HoldReason> holdReason, Mode mode,
        long phaseDurationNanos, long remainingNanos, long referenceNanos,
        OptionalInt committedFloor, List<CabinRequest> cabinRequests, List<HallRequest> hallRequests) {

    public enum HoldReason { STOP, PAUSE }
    public enum Mode { NORMAL, RESET_RETURN }

    public ElevatorSnapshot {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(nextFloor, "nextFloor");
        Objects.requireNonNull(direction, "direction");
        Objects.requireNonNull(phase, "phase");
        Objects.requireNonNull(suspendedPhase, "suspendedPhase");
        Objects.requireNonNull(holdReason, "holdReason");
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(committedFloor, "committedFloor");
        cabinRequests = List.copyOf(cabinRequests);
        hallRequests = List.copyOf(hallRequests);
        if (id < 1 || id > config.elevatorCount() || epoch < 0 || revision < 0
                || currentFloor < 1 || currentFloor > config.floorCount()) {
            throw new IllegalArgumentException("Identidad, época, revisión o piso inválidos.");
        }
        if (committedFloor.isPresent()
                && (committedFloor.getAsInt() < 1 || committedFloor.getAsInt() > config.floorCount())) {
            throw new IllegalArgumentException("Frontera fuera del edificio.");
        }
        ElevatorPhase effective = phase;
        if (phase == ElevatorPhase.HELD) {
            if (suspendedPhase.isEmpty() || holdReason.isEmpty()
                    || (suspendedPhase.get() != ElevatorPhase.MOVING
                    && suspendedPhase.get() != ElevatorPhase.DWELLING)) {
                throw new IllegalArgumentException("HELD exige fase suspendida y motivo.");
            }
            effective = suspendedPhase.get();
            if (holdReason.get() == HoldReason.STOP && effective == ElevatorPhase.MOVING) {
                throw new IllegalArgumentException("Stop termina el tramo; no lo congela.");
            }
        } else if (suspendedPhase.isPresent() || holdReason.isPresent()) {
            throw new IllegalArgumentException("Solo HELD conserva una fase suspendida.");
        }
        long expectedDuration = switch (effective) {
            case MOVING -> config.movementTimeMillis() * 1_000_000L;
            case DWELLING -> config.dwellTimeMillis() * 1_000_000L;
            default -> 0L;
        };
        if (phaseDurationNanos != expectedDuration || remainingNanos < 0
                || remainingNanos > phaseDurationNanos) {
            throw new IllegalArgumentException("Duración o restante incoherentes con la fase.");
        }
        if (effective == ElevatorPhase.MOVING) {
            long expectedNext = (long) currentFloor + direction.floorDelta();
            if (nextFloor.isEmpty() || nextFloor.getAsInt() != expectedNext
                    || expectedNext < 1 || expectedNext > config.floorCount()) {
                throw new IllegalArgumentException("El tramo debe ir a un piso adyacente válido.");
            }
        } else if (nextFloor.isPresent()) {
            throw new IllegalArgumentException("Sin tramo no puede existir siguiente piso.");
        }
        Set<UUID> ids = new HashSet<>();
        if ((mode == Mode.RESET_RETURN || phase == ElevatorPhase.TERMINATED)
                && (!cabinRequests.isEmpty() || !hallRequests.isEmpty())) {
            throw new IllegalArgumentException("Retorno por reset o terminación no conserva servicio pendiente.");
        }
        Set<Integer> internalFloors = new HashSet<>();
        Set<String> externalKeys = new HashSet<>();
        for (CabinRequest request : cabinRequests) {
            validatePending(request, config, epoch, ids);
            if (request.getElevatorId() != id || !internalFloors.add(request.getFloor())) {
                throw new IllegalArgumentException("Destino ajeno o duplicado.");
            }
        }
        for (HallRequest request : hallRequests) {
            validatePending(request, config, epoch, ids);
            if (!externalKeys.add(request.getFloor() + ":" + request.getDirection())) {
                throw new IllegalArgumentException("Recogida duplicada.");
            }
        }
    }

    private static void validatePending(Request request, SimulationConfig config,
            long epoch, Set<UUID> ids) {
        if (request.getEpoch() != epoch || !request.validateAgainst(config).isValid()
                || !ids.add(request.getId())) {
            throw new IllegalArgumentException("Pendiente inválido, de otra época o UUID repetido.");
        }
    }

    /** Progreso capturado; consultar el snapshot no consume tiempo ni confirma llegadas. */
    public double progress() {
        return phaseDurationNanos == 0 ? 0.0 : 1.0 - (double) remainingNanos / phaseDurationNanos;
    }
}
