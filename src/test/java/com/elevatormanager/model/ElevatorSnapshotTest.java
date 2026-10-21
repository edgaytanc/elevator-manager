package com.elevatormanager.model;

import com.elevatormanager.model.request.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ElevatorSnapshotTest {
    private static final SimulationConfig CONFIG = new SimulationConfig(2, 5, 100, 200);
    private static final Instant NOW = Instant.parse("2026-10-21T06:00:00Z");

    private ElevatorSnapshot sample(ElevatorPhase phase, OptionalInt next,
            Optional<ElevatorPhase> suspended, Optional<ElevatorSnapshot.HoldReason> reason,
            long duration, long remaining) {
        return new ElevatorSnapshot(1, CONFIG, 0, 0, 2, next, Direction.UP, phase,
                suspended, reason, ElevatorSnapshot.Mode.NORMAL, duration, remaining, -10,
                OptionalInt.of(5), List.of(), List.of());
    }

    @Test
    void ownerCapturesWholeStateWithoutChangingRevision() {
        ElevatorState state = new ElevatorState(1, CONFIG, 7);
        ElevatorSnapshot initial = state.snapshot(-100);
        assertEquals(1, initial.id());
        assertSame(CONFIG, initial.config());
        assertEquals(7L, initial.epoch());
        assertEquals(0L, initial.revision());
        assertEquals(1, initial.currentFloor());
        assertSame(Direction.UP, initial.direction());
        assertSame(ElevatorPhase.IDLE, initial.phase());
        assertTrue(initial.nextFloor().isEmpty());
        assertEquals(-100L, initial.referenceNanos());
        assertEquals(0.0, initial.progress());
        assertEquals(0L, state.getRevision());
    }

    @Test
    void atomicPublicationKeepsPreviousSnapshotsIndependentOfLaterState() {
        ElevatorState owner = new ElevatorState(1, CONFIG, 0);
        AtomicReference<ElevatorSnapshot> published = new AtomicReference<>(owner.snapshot(1));
        ElevatorSnapshot previous = published.get();
        owner.addPendingRequest(new CabinRequest(UUID.randomUUID(), 0, 4, NOW, 1));
        owner.addPendingRequest(new HallRequest(UUID.randomUUID(), 0, 3, NOW, Direction.DOWN));
        published.set(owner.snapshot(2));
        ElevatorSnapshot current = published.get();
        assertEquals(2L, current.revision());
        assertEquals(1, current.cabinRequests().size());
        assertEquals(1, current.hallRequests().size());
        assertTrue(previous.cabinRequests().isEmpty());
        assertTrue(previous.hallRequests().isEmpty());
        assertEquals(0L, previous.revision());
        assertThrows(UnsupportedOperationException.class, () -> current.cabinRequests().clear());
        assertThrows(UnsupportedOperationException.class, () -> current.hallRequests().clear());
    }

    @Test
    void constructorCopiesCallerCollections() {
        List<CabinRequest> cabin = new ArrayList<>();
        List<HallRequest> hall = new ArrayList<>();
        cabin.add(new CabinRequest(UUID.randomUUID(), 0, 4, NOW, 1));
        hall.add(new HallRequest(UUID.randomUUID(), 0, 3, NOW, Direction.UP));
        ElevatorSnapshot snapshot = new ElevatorSnapshot(1, CONFIG, 0, 1, 1,
                OptionalInt.empty(), Direction.UP, ElevatorPhase.IDLE,
                Optional.empty(), Optional.empty(), ElevatorSnapshot.Mode.NORMAL,
                0, 0, 1, OptionalInt.empty(), cabin, hall);
        cabin.clear();
        hall.clear();
        assertEquals(1, snapshot.cabinRequests().size());
        assertEquals(1, snapshot.hallRequests().size());
    }

    @Test
    void movingSnapshotPreservesLastFloorAndCapturedProgress() {
        ElevatorSnapshot moving = sample(ElevatorPhase.MOVING, OptionalInt.of(3),
                Optional.empty(), Optional.empty(), 100_000_000, 40_000_000);
        assertEquals(2, moving.currentFloor());
        assertEquals(3, moving.nextFloor().orElseThrow());
        assertEquals(0.6, moving.progress(), 0.000001);
        assertEquals(40_000_000L, moving.remainingNanos());
    }

    @Test
    void heldSnapshotRetainsPhysicalPhaseAndReason() {
        ElevatorSnapshot moving = sample(ElevatorPhase.HELD, OptionalInt.of(3),
                Optional.of(ElevatorPhase.MOVING), Optional.of(ElevatorSnapshot.HoldReason.PAUSE),
                100_000_000, 40_000_000);
        ElevatorSnapshot dwelling = sample(ElevatorPhase.HELD, OptionalInt.empty(),
                Optional.of(ElevatorPhase.DWELLING), Optional.of(ElevatorSnapshot.HoldReason.STOP),
                200_000_000, 50_000_000);
        assertEquals(ElevatorPhase.MOVING, moving.suspendedPhase().orElseThrow());
        assertEquals(0.6, moving.progress(), 0.000001);
        assertEquals(0.75, dwelling.progress(), 0.000001);
    }

    @Test
    void inconsistentGeometryAndSuspensionAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> sample(ElevatorPhase.MOVING,
                OptionalInt.of(4), Optional.empty(), Optional.empty(), 100_000_000, 1));
        assertThrows(IllegalArgumentException.class, () -> sample(ElevatorPhase.IDLE,
                OptionalInt.of(3), Optional.empty(), Optional.empty(), 0, 0));
        assertThrows(IllegalArgumentException.class, () -> sample(ElevatorPhase.HELD,
                OptionalInt.of(3), Optional.empty(), Optional.empty(), 100_000_000, 1));
        assertThrows(IllegalArgumentException.class, () -> sample(ElevatorPhase.HELD,
                OptionalInt.of(3), Optional.of(ElevatorPhase.MOVING),
                Optional.of(ElevatorSnapshot.HoldReason.STOP), 100_000_000, 1));
        assertThrows(IllegalArgumentException.class, () -> sample(ElevatorPhase.MOVING,
                OptionalInt.of(3), Optional.of(ElevatorPhase.MOVING),
                Optional.of(ElevatorSnapshot.HoldReason.PAUSE), 100_000_000, 1));
    }

    @Test
    void inconsistentTimingAndNullOptionalsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> sample(ElevatorPhase.MOVING,
                OptionalInt.of(3), Optional.empty(), Optional.empty(), 100_000_000, -1));
        assertThrows(IllegalArgumentException.class, () -> sample(ElevatorPhase.MOVING,
                OptionalInt.of(3), Optional.empty(), Optional.empty(), 100_000_000, 100_000_001));
        assertThrows(IllegalArgumentException.class, () -> sample(ElevatorPhase.DWELLING,
                OptionalInt.empty(), Optional.empty(), Optional.empty(), 100, 1));
        assertThrows(NullPointerException.class, () -> sample(ElevatorPhase.IDLE,
                null, Optional.empty(), Optional.empty(), 0, 0));
    }

    @Test
    void snapshotRejectsPendingFromAnotherCabinOrEpochAndRepeatedUuid() {
        CabinRequest wrongCabin = new CabinRequest(UUID.randomUUID(), 0, 3, NOW, 2);
        CabinRequest oldEpoch = new CabinRequest(UUID.randomUUID(), 1, 3, NOW, 1);
        CabinRequest valid = new CabinRequest(UUID.randomUUID(), 0, 3, NOW, 1);
        for (List<CabinRequest> invalid : List.of(List.of(wrongCabin), List.of(oldEpoch),
                List.of(valid, valid))) {
            assertThrows(IllegalArgumentException.class, () -> new ElevatorSnapshot(1, CONFIG,
                    0, 0, 1, OptionalInt.empty(), Direction.UP, ElevatorPhase.IDLE,
                    Optional.empty(), Optional.empty(), ElevatorSnapshot.Mode.NORMAL,
                    0, 0, 0, OptionalInt.empty(), invalid, List.of()));
        }
    }
}
