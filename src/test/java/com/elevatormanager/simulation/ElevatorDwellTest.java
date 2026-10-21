package com.elevatormanager.simulation;

import org.junit.jupiter.api.Test;

class ElevatorDwellTest {
    @Test void cabinServedAtStopStart() { ElevatorDwellScenarios.cabinServedAtStopStart(); }
    @Test void oppositeHallRemainsPending() { ElevatorDwellScenarios.oppositeHallRemainsPending(); }
    @Test void compatibleRequestsShareOneStop() { ElevatorDwellScenarios.compatibleRequestsShareOneStop(); }
    @Test void noServiceDoesNotOpenStop() { ElevatorDwellScenarios.noServiceDoesNotOpenStop(); }
    @Test void dwellCompletesOnceAtDeadline() { ElevatorDwellScenarios.dwellCompletesOnceAtDeadline(); }
    @Test void compatibleArrivalDoesNotRestartTimer() {
        ElevatorDwellScenarios.compatibleArrivalDoesNotRestartTimer();
    }
    @Test void oppositeDirectionRequiresAnotherStop() {
        ElevatorDwellScenarios.oppositeDirectionRequiresAnotherStop();
    }
    @Test void expiredStopCannotAcceptLateService() {
        ElevatorDwellScenarios.expiredStopCannotAcceptLateService();
    }
    @Test void motionAndCommittedDirectionAreProtected() {
        ElevatorDwellScenarios.motionAndCommittedDirectionAreProtected();
    }
    @Test void dwellSupportsClockWrapAndMaximum() { ElevatorDwellScenarios.dwellSupportsClockWrapAndMaximum(); }
    @Test void workerApproachesAndServesRequestedDirection() throws Exception {
        ElevatorDwellScenarios.workerApproachesAndServesRequestedDirection();
    }
    @Test void workerSeparatesOppositeServices() throws Exception {
        ElevatorDwellScenarios.workerSeparatesOppositeServices();
    }
    @Test void workerJoinsOpenStopWithoutNewOpening() throws Exception {
        ElevatorDwellScenarios.workerJoinsOpenStopWithoutNewOpening();
    }
    @Test void workerContinuesAfterFullDwell() throws Exception {
        ElevatorDwellScenarios.workerContinuesAfterFullDwell();
    }
    @Test void closeWakesDwellWait() throws Exception { ElevatorDwellScenarios.closeWakesDwellWait(); }
}
