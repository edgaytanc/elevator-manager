package com.elevatormanager.model;

import com.elevatormanager.model.request.CabinRequest;
import com.elevatormanager.model.request.HallRequest;
import com.elevatormanager.model.request.Request;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ElevatorStateTest {
    private static final SimulationConfig CONFIG = new SimulationConfig(2, 5, 100, 200);
    private static final Instant NOW = Instant.parse("2026-10-21T06:00:00Z");

    private CabinRequest cabin(int id, int floor, long epoch) {
        return new CabinRequest(UUID.randomUUID(), epoch, floor, NOW, id);
    }

    private HallRequest hall(int floor, Direction direction, long epoch) {
        return new HallRequest(UUID.randomUUID(), epoch, floor, NOW, direction);
    }

    @Test
    void allCabinsStartAtOneUpIdleWithIndependentEmptyAgendas() {
        for (int id = 1; id <= CONFIG.elevatorCount(); id++) {
            ElevatorState state = new ElevatorState(id, CONFIG, 7);
            assertEquals(id, state.getId());
            assertSame(CONFIG, state.getConfig());
            assertEquals(7L, state.getEpoch());
            assertEquals(0L, state.getRevision());
            assertEquals(1, state.getCurrentFloor());
            assertSame(Direction.UP, state.getDirection());
            assertSame(ElevatorPhase.IDLE, state.getPhase());
            assertTrue(state.getCabinRequests().isEmpty());
            assertTrue(state.getHallRequests().isEmpty());
        }
    }

    @Test
    void changingOneCabinDoesNotChangeAnother() {
        ElevatorState first = new ElevatorState(1, CONFIG, 0);
        ElevatorState second = new ElevatorState(2, CONFIG, 0);
        first.addPendingRequest(cabin(1, 4, 0));
        first.addPendingRequest(hall(3, Direction.DOWN, 0));
        assertTrue(second.getCabinRequests().isEmpty());
        assertTrue(second.getHallRequests().isEmpty());
        assertEquals(0L, second.getRevision());
        second.addPendingRequest(cabin(2, 5, 0));
        assertEquals(List.of(4), first.getCabinRequests().stream().map(Request::getFloor).toList());
        assertEquals(2L, first.getRevision());
        assertEquals(1L, second.getRevision());
    }

    @Test
    void agendasStaySeparateAndKeepInsertionOrderWithoutMovingCabin() {
        ElevatorState state = new ElevatorState(1, CONFIG, 0);
        CabinRequest fifth = cabin(1, 5, 0);
        CabinRequest second = cabin(1, 2, 0);
        HallRequest pickup = hall(2, Direction.UP, 0);
        state.addPendingRequest(fifth);
        state.addPendingRequest(second);
        state.addPendingRequest(pickup);
        assertEquals(List.of(fifth, second), state.getCabinRequests());
        assertEquals(List.of(pickup), state.getHallRequests());
        assertEquals(1, state.getCurrentFloor());
        assertSame(Direction.UP, state.getDirection());
        assertSame(ElevatorPhase.IDLE, state.getPhase());
    }

    @Test
    void agendaCopiesAreImmutableAndDoNotBecomeLiveViews() {
        ElevatorState state = new ElevatorState(1, CONFIG, 0);
        var oldCabin = state.getCabinRequests();
        var oldHall = state.getHallRequests();
        state.addPendingRequest(cabin(1, 4, 0));
        state.addPendingRequest(hall(3, Direction.UP, 0));
        assertTrue(oldCabin.isEmpty());
        assertTrue(oldHall.isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> state.getCabinRequests().clear());
        assertThrows(UnsupportedOperationException.class, () -> state.getHallRequests().clear());
    }

    @Test
    void constructorRejectsInvalidIdentityEpochAndConfiguration() {
        for (int id : new int[]{-1, 0, 3}) {
            assertThrows(IllegalArgumentException.class, () -> new ElevatorState(id, CONFIG, 0));
        }
        assertThrows(IllegalArgumentException.class, () -> new ElevatorState(1, CONFIG, -1));
        assertThrows(NullPointerException.class, () -> new ElevatorState(1, null, 0));
    }

    @Test
    void invalidRequestsLeaveAgendasAndRevisionUnchanged() {
        ElevatorState state = new ElevatorState(1, CONFIG, 2);
        List<Request> invalid = List.of(cabin(2, 3, 2), cabin(1, 6, 2),
                hall(5, Direction.UP, 2), hall(1, Direction.DOWN, 2),
                cabin(1, 3, 1), hall(3, Direction.UP, 3));
        for (Request request : invalid) {
            assertThrows(IllegalArgumentException.class, () -> state.addPendingRequest(request));
        }
        assertThrows(NullPointerException.class, () -> state.addPendingRequest(null));
        assertTrue(state.getCabinRequests().isEmpty());
        assertTrue(state.getHallRequests().isEmpty());
        assertEquals(0L, state.getRevision());
    }

    @Test
    void exactRetryIsIdempotentForBothRequestTypes() {
        ElevatorState state = new ElevatorState(1, CONFIG, 0);
        for (Request request : List.of(cabin(1, 4, 0), hall(3, Direction.UP, 0))) {
            assertTrue(state.addPendingRequest(request));
            assertFalse(state.addPendingRequest(request));
        }
        assertEquals(2L, state.getRevision());
        CabinRequest original = state.getCabinRequests().get(0);
        assertFalse(state.addPendingRequest(new CabinRequest(
                original.getId(), 0, 4, NOW, 1)));
        assertEquals(2L, state.getRevision());
    }

    @Test
    void reusedUuidWithDifferentPayloadCannotOverwritePendingWork() {
        ElevatorState state = new ElevatorState(1, CONFIG, 0);
        UUID id = UUID.randomUUID();
        HallRequest original = new HallRequest(id, 0, 3, NOW, Direction.UP);
        state.addPendingRequest(original);
        List<Request> conflicts = List.of(new HallRequest(id, 0, 4, NOW, Direction.UP),
                new HallRequest(id, 0, 3, NOW, Direction.DOWN),
                new HallRequest(id, 0, 3, NOW.plusSeconds(1), Direction.UP),
                new CabinRequest(id, 0, 3, NOW, 1));
        for (Request request : conflicts) {
            assertThrows(IllegalArgumentException.class, () -> state.addPendingRequest(request));
        }
        assertSame(original, state.getHallRequests().get(0));
        assertTrue(state.getCabinRequests().isEmpty());
        assertEquals(1L, state.getRevision());
    }

    @Test
    void functionalDuplicatesWithNewIdsRequireAdmissionResolution() {
        ElevatorState state = new ElevatorState(1, CONFIG, 0);
        state.addPendingRequest(cabin(1, 4, 0));
        state.addPendingRequest(hall(3, Direction.UP, 0));
        assertThrows(IllegalArgumentException.class, () -> state.addPendingRequest(cabin(1, 4, 0)));
        assertThrows(IllegalArgumentException.class,
                () -> state.addPendingRequest(hall(3, Direction.UP, 0)));
        assertEquals(2L, state.getRevision());
    }

    @Test
    void bothHallDirectionsAndCabinDestinationCanCoexistOnSameFloor() {
        ElevatorState state = new ElevatorState(1, CONFIG, 0);
        state.addPendingRequest(hall(3, Direction.UP, 0));
        state.addPendingRequest(hall(3, Direction.DOWN, 0));
        state.addPendingRequest(cabin(1, 3, 0));
        assertEquals(2, state.getHallRequests().size());
        assertEquals(1, state.getCabinRequests().size());
        assertEquals(3L, state.getRevision());
    }

    @Test
    void singleCabinSingleFloorAcceptsItsInternalDestination() {
        ElevatorState state = new ElevatorState(1, new SimulationConfig(1, 1, 1, 1), 0);
        assertTrue(state.addPendingRequest(cabin(1, 1, 0)));
        assertEquals(1, state.getCurrentFloor());
        assertThrows(IllegalArgumentException.class,
                () -> state.addPendingRequest(hall(1, Direction.UP, 0)));
        assertEquals(1L, state.getRevision());
    }

    @Test
    void unsupportedRequestSubclassIsNotStoredAsAnImmutableKnownType() {
        ElevatorState state = new ElevatorState(1, CONFIG, 0);
        Request unknown = new Request(UUID.randomUUID(), 0, 3, NOW) {
            @Override protected ValidationResult validateSpecific(SimulationConfig config) {
                return ValidationResult.valid();
            }
            @Override public String describe() { return "Solicitud no soportada"; }
        };
        assertThrows(IllegalArgumentException.class, () -> state.addPendingRequest(unknown));
        assertEquals(0L, state.getRevision());
    }
}
