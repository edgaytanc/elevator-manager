package com.elevatormanager.simulation;

import org.junit.jupiter.api.Test;

class StopContinuationTest {
    @Test void modelPreservesDwellRemaining() throws Exception {
        StopContinuationScenarios.modelPreservesDwellRemaining();
    }
    @Test void modelDoesNotFreezeMovingLeg() throws Exception {
        StopContinuationScenarios.modelDoesNotFreezeMovingLeg();
    }
    @Test void modelDueDwellDoesNotRestart() throws Exception {
        StopContinuationScenarios.modelDueDwellDoesNotRestart();
    }
    @Test void stopInResetDoesNotStartWorkers() throws Exception {
        StopContinuationScenarios.stopInResetDoesNotStartWorkers();
    }
    @Test void idleStopHasGlobalBarrier() throws Exception {
        StopContinuationScenarios.idleStopHasGlobalBarrier();
    }
    @Test void movementStopsAtNextFloor() throws Exception {
        StopContinuationScenarios.movementStopsAtNextFloor();
    }
    @Test void arrivalIsNotService() throws Exception {
        StopContinuationScenarios.arrivalIsNotService();
    }
    @Test void dwellFreezesAndContinues() throws Exception {
        StopContinuationScenarios.dwellFreezesAndContinues();
    }
    @Test void repeatedStopIsIdempotent() throws Exception {
        StopContinuationScenarios.repeatedStopIsIdempotent();
    }
    @Test void rejectsStartWhileStopping() throws Exception {
        StopContinuationScenarios.rejectsStartWhileStopping();
    }
    @Test void rejectsRequestsWhileStopping() throws Exception {
        StopContinuationScenarios.rejectsRequestsWhileStopping();
    }
    @Test void rejectsRequestsWhileStopped() throws Exception {
        StopContinuationScenarios.rejectsRequestsWhileStopped();
    }
    @Test void cabinInboxSurvivesStop() throws Exception {
        StopContinuationScenarios.cabinInboxSurvivesStop();
    }
    @Test void managerInboxSurvivesStop() throws Exception {
        StopContinuationScenarios.managerInboxSurvivesStop();
    }
    @Test void inFlightDispatchIsCoveredByBarrier() throws Exception {
        StopContinuationScenarios.inFlightDispatchIsCoveredByBarrier();
    }
    @Test void severalCabinsAndHallCallsContinue() throws Exception {
        StopContinuationScenarios.severalCabinsAndHallCallsContinue();
    }
    @Test void repeatedCyclesPreserveCompletedWork() throws Exception {
        StopContinuationScenarios.repeatedCyclesPreserveCompletedWork();
    }
    @Test void edtRemainsResponsiveDuringStop() throws Exception {
        StopContinuationScenarios.edtRemainsResponsiveDuringStop();
    }
    @Test void closeDuringStoppingDoesNotLeaveFuturePending() throws Exception {
        StopContinuationScenarios.closeDuringStoppingDoesNotLeaveFuturePending();
    }
    @Test void closeWhileStoppedWakesAllActors() throws Exception {
        StopContinuationScenarios.closeWhileStoppedWakesAllActors();
    }
    @Test void rapidControlsDoNotLoseWakeups() throws Exception {
        StopContinuationScenarios.rapidControlsDoNotLoseWakeups();
    }
}
