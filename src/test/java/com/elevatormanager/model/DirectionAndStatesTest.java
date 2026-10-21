package com.elevatormanager.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DirectionAndStatesTest {

    @Test
    void directionsDescribeAdjacentFloorDisplacement() {
        int currentFloor = 3;
        assertAll(
                () -> assertEquals(4, currentFloor + Direction.UP.floorDelta()),
                () -> assertEquals(2, currentFloor + Direction.DOWN.floorDelta()));
    }

    @Test
    void oppositeDirectionIsReversible() {
        assertSame(Direction.DOWN, Direction.UP.opposite());
        assertSame(Direction.UP, Direction.DOWN.opposite());
        for (Direction direction : Direction.values()) {
            assertSame(direction, direction.opposite().opposite());
        }
    }

    @Test
    void manualRequestsAreAcceptedOnlyWhileRunning() {
        for (SimulationState state : SimulationState.values()) {
            assertEquals(state == SimulationState.RUNNING,
                    state.acceptsManualRequests(), state.name());
        }
    }

    @Test
    void requestImportIsAllowedOnlyInReset() {
        for (SimulationState state : SimulationState.values()) {
            assertEquals(state == SimulationState.RESET,
                    state.allowsRequestImport(), state.name());
        }
    }
}
