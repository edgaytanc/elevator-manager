package com.elevatormanager.simulation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** T55: escenarios completos bajo JUnit. */
@Timeout(30)
class GlobalPauseTest {
    @Test void pauseAndResumeRequireValidState() throws Exception {
        GlobalPauseScenarios.pauseAndResumeRequireValidState();
    }
    @Test void singleCabinIdleBarrier() throws Exception {
        GlobalPauseScenarios.singleCabinIdleBarrier();
    }
    @Test void manyCabinsIdleBarrier() throws Exception {
        GlobalPauseScenarios.manyCabinsIdleBarrier();
    }
    @Test void partialMovementPreservesRemainingTimeAndCommand() throws Exception {
        GlobalPauseScenarios.partialMovementPreservesRemainingTimeAndCommand();
    }
    @Test void partialDwellPreservesRemainingTime() throws Exception {
        GlobalPauseScenarios.partialDwellPreservesRemainingTime();
    }
    @Test void movingCabinsFreezeOnOneTimeDomain() throws Exception {
        GlobalPauseScenarios.movingCabinsFreezeOnOneTimeDomain();
    }
    @Test void repeatedControlsAreIdempotent() throws Exception {
        GlobalPauseScenarios.repeatedControlsAreIdempotent();
    }
    @Test void rapidCyclesKeepSameSessionAndActors() throws Exception {
        GlobalPauseScenarios.rapidCyclesKeepSameSessionAndActors();
    }
    @Test void hallLedgerAndQueuedCommandsSurvivePause() throws Exception {
        GlobalPauseScenarios.hallLedgerAndQueuedCommandsSurvivePause();
    }
    @Test void manualRequestsAndImportsAreRejectedWhilePaused() throws Exception {
        GlobalPauseScenarios.manualRequestsAndImportsAreRejectedWhilePaused();
    }
    @Test void pausedStopAndResetUsePriorityControl() throws Exception {
        GlobalPauseScenarios.pausedStopAndResetUsePriorityControl();
    }
    @Test void edtCanRequestPauseAndResumeWithoutWaiting() throws Exception {
        GlobalPauseScenarios.edtCanRequestPauseAndResumeWithoutWaiting();
    }
    @Test void cancelledPublicFutureDoesNotStrandPause() throws Exception {
        GlobalPauseScenarios.cancelledPublicFutureDoesNotStrandPause();
    }
    @Test void simultaneousPauseRequestsShareOneGeneration() throws Exception {
        GlobalPauseScenarios.simultaneousPauseRequestsShareOneGeneration();
    }
    @Test void closingPausedSessionReleasesEveryActor() throws Exception {
        GlobalPauseScenarios.closingPausedSessionReleasesEveryActor();
    }
    @Test void closeRacingPauseAlwaysCompletesBothFutures() throws Exception {
        GlobalPauseScenarios.closeRacingPauseAlwaysCompletesBothFutures();
    }
    @Test void sessionsHaveIndependentPauseDomains() throws Exception {
        GlobalPauseScenarios.sessionsHaveIndependentPauseDomains();
    }
}
