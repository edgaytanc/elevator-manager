package com.elevatormanager.ui;

import com.elevatormanager.model.ElevatorPhase;
import com.elevatormanager.model.ElevatorSnapshot;
import java.util.Objects;

/** T59: proyección visual pura de un único tramo publicado; no confirma llegadas. */
final class CabinMotion {
    private CabinMotion() { }

    /** activeNanos pertenece al mismo reloj lógico que snapshot.referenceNanos(). */
    static double position(ElevatorSnapshot snapshot, long activeNanos) {
        Objects.requireNonNull(snapshot, "snapshot");
        boolean moving = snapshot.phase() == ElevatorPhase.MOVING;
        boolean heldMoving = snapshot.phase() == ElevatorPhase.HELD
                && snapshot.suspendedPhase().orElseThrow() == ElevatorPhase.MOVING;
        if (!moving && !heldMoving) return snapshot.currentFloor();
        long duration = snapshot.phaseDurationNanos();
        long spent = duration - snapshot.remainingNanos();
        // Restar las marcas admite wrap monotónico dentro de un intervalo < Long.MAX_VALUE.
        long elapsed = moving ? Math.max(0L, activeNanos - snapshot.referenceNanos()) : 0L;
        long extra = Math.min(snapshot.remainingNanos(), elapsed);
        // Sumar solo hasta remaining impide overflow y no extrapola otro tramo.
        double progress = Math.min(1.0, (double) (spent + extra) / duration);
        return snapshot.currentFloor()
                + (snapshot.nextFloor().orElseThrow() - snapshot.currentFloor()) * progress;
    }
}
