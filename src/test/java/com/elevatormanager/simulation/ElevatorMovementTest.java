package com.elevatormanager.simulation;

import org.junit.jupiter.api.Test;

class ElevatorMovementTest {
    @Test void adjacentAndDeadline() { ElevatorMovementScenarios.adjacentAndDeadline(); }
    @Test void lateWakeupDoesNotSkipFloors() { ElevatorMovementScenarios.lateWakeupDoesNotSkipFloors(); }
    @Test void upwardAndDownwardJourney() { ElevatorMovementScenarios.upwardAndDownwardJourney(); }
    @Test void invalidTargetsAreAtomic() { ElevatorMovementScenarios.invalidTargetsAreAtomic(); }
    @Test void commitmentCannotBeReplacedMidJourney() {
        ElevatorMovementScenarios.commitmentCannotBeReplacedMidJourney();
    }
    @Test void snapshotsDoNotConfirmArrivals() { ElevatorMovementScenarios.snapshotsDoNotConfirmArrivals(); }
    @Test void clockWrapAndMaximumDuration() { ElevatorMovementScenarios.clockWrapAndMaximumDuration(); }
    @Test void singleFloorAndCurrentFloor() { ElevatorMovementScenarios.singleFloorAndCurrentFloor(); }
    @Test void workerMovesWithoutServingRequests() throws Exception {
        ElevatorMovementScenarios.workerMovesWithoutServingRequests();
    }
    @Test void closeWakesTimedWait() throws Exception { ElevatorMovementScenarios.closeWakesTimedWait(); }
}
