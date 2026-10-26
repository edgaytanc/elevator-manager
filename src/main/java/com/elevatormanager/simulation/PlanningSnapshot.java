package com.elevatormanager.simulation;

import com.elevatormanager.model.Direction;
import com.elevatormanager.model.ElevatorSnapshot;
import com.elevatormanager.model.request.CabinRequest;
import com.elevatormanager.model.request.HallRequest;
import com.elevatormanager.model.request.Request;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Captura atómica del estado público y del contexto privado de planificación. */
public record PlanningSnapshot(ElevatorSnapshot elevator,
        List<Request> admissionOrder, Optional<Direction> completedSweep) {
    public PlanningSnapshot {
        Objects.requireNonNull(elevator, "elevator");
        admissionOrder = List.copyOf(admissionOrder);
        Objects.requireNonNull(completedSweep, "completedSweep");
        var expected = new HashMap<UUID, Request>();
        for (var request : elevator.cabinRequests()) { expected.put(request.getId(), request); }
        for (var request : elevator.hallRequests()) { expected.put(request.getId(), request); }
        var seen = new HashSet<UUID>();
        if (expected.size() != admissionOrder.size()) {
            throw new IllegalArgumentException("Orden de admisión incompleto.");
        }
        for (var request : admissionOrder) {
            if (!seen.add(request.getId()) || !sameRequest(request, expected.get(request.getId()))) {
                throw new IllegalArgumentException("Orden de admisión incompatible con la agenda.");
            }
        }
    }

    /** Request.equals compara identidad; aquí también se verifica su contenido. */
    public static boolean sameRequest(Request a, Request b) {
        if (a == null || b == null || a.getClass() != b.getClass()
                || !a.getId().equals(b.getId()) || a.getEpoch() != b.getEpoch()
                || a.getFloor() != b.getFloor() || !a.getCreatedAt().equals(b.getCreatedAt())) {
            return false;
        }
        if (a instanceof CabinRequest cabin) {
            return cabin.getElevatorId() == ((CabinRequest) b).getElevatorId();
        }
        return a instanceof HallRequest hall
                && hall.getDirection() == ((HallRequest) b).getDirection();
    }
}
