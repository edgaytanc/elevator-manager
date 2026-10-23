package com.elevatormanager.simulation;

import com.elevatormanager.model.Direction;
import com.elevatormanager.model.request.Request;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/** Selección pura del siguiente barrido; las paradas se sirven al pasar por cada piso. */
public final class DirectionalAgenda {
    private DirectionalAgenda() { }

    /**
     * Sin barrido anterior: piso más próximo, con empate por admisión.
     * Tras alcanzar una frontera: si hay trabajo detrás, compromete el extremo
     * de ese regreso; así atiende las compatibles en orden espacial sin zigzag.
     * La lista debe contener pendientes validados, en orden de admisión local.
     */
    public static OptionalInt selectTarget(int currentFloor, List<Request> pending,
            Optional<Direction> completedSweep) {
        if (currentFloor < 1) { throw new IllegalArgumentException("Piso actual inválido."); }
        List<Request> ordered = List.copyOf(pending);
        Objects.requireNonNull(completedSweep, "completedSweep");
        if (completedSweep.isPresent()) {
            Request farthestBehind = null;
            long farthestDistance = -1;
            for (Request request : ordered) {
                long delta = (long) request.getFloor() - currentFloor;
                long distance = Math.abs(delta);
                if (delta * completedSweep.get().floorDelta() < 0 && distance > farthestDistance) {
                    farthestBehind = request;
                    farthestDistance = distance;
                }
            }
            if (farthestBehind != null) { return OptionalInt.of(farthestBehind.getFloor()); }
        }
        Request nearest = null;
        long nearestDistance = Long.MAX_VALUE;
        for (Request request : ordered) {
            long distance = Math.abs((long) request.getFloor() - currentFloor);
            if (distance < nearestDistance) {
                nearest = request;
                nearestDistance = distance;
            }
        }
        return nearest == null ? OptionalInt.empty() : OptionalInt.of(nearest.getFloor());
    }
}
