package com.elevatormanager.simulation.time;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** T54: mismos escenarios del ejecutable independiente bajo JUnit. */
@Timeout(30)
class PausableClockTest {
    @Test void monotonicOriginAndProgress() throws Exception {
        PausableClockScenarios.monotonicOriginAndProgress();
    }
    @Test void pauseExcludesWallTime() throws Exception {
        PausableClockScenarios.pauseExcludesWallTime();
    }
    @Test void repeatedControlsAreIdempotent() throws Exception {
        PausableClockScenarios.repeatedControlsAreIdempotent();
    }
    @Test void manyCyclesPreserveActiveTime() throws Exception {
        PausableClockScenarios.manyCyclesPreserveActiveTime();
    }
    @Test void nanoTimeWrapIsHandled() throws Exception {
        PausableClockScenarios.nanoTimeWrapIsHandled();
    }
    @Test void invalidDurationsAndMarksAreRejected() throws Exception {
        PausableClockScenarios.invalidDurationsAndMarksAreRejected();
    }
    @Test void zeroAndExpiredBudgetsReturnWhilePaused() throws Exception {
        PausableClockScenarios.zeroAndExpiredBudgetsReturnWhilePaused();
    }
    @Test void pausedWaitDoesNotExpireInWallTime() throws Exception {
        PausableClockScenarios.pausedWaitDoesNotExpireInWallTime();
    }
    @Test void partialWaitKeepsRemainingBudget() throws Exception {
        PausableClockScenarios.partialWaitKeepsRemainingBudget();
    }
    @Test void resumeWakesEveryWaiter() throws Exception {
        PausableClockScenarios.resumeWakesEveryWaiter();
    }
    @Test void interruptWorksDuringPause() throws Exception {
        PausableClockScenarios.interruptWorksDuringPause();
    }
    @Test void interruptWorksDuringTimedWait() throws Exception {
        PausableClockScenarios.interruptWorksDuringTimedWait();
    }
    @Test void interruptedBudgetCanBeRetried() throws Exception {
        PausableClockScenarios.interruptedBudgetCanBeRetried();
    }
    @Test void closeWakesPausedAndTimedWaiters() throws Exception {
        PausableClockScenarios.closeWakesPausedAndTimedWaiters();
    }
    @Test void closedClockRejectsNewOperations() throws Exception {
        PausableClockScenarios.closedClockRejectsNewOperations();
    }
    @Test void movementAndDwellUseOnlyActiveTime() throws Exception {
        PausableClockScenarios.movementAndDwellUseOnlyActiveTime();
    }
    @Test void productionClockWaitsForRealElapsedTime() throws Exception {
        PausableClockScenarios.productionClockWaitsForRealElapsedTime();
    }
}
