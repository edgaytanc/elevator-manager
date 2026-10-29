package com.elevatormanager.simulation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** T56: escenarios completos bajo JUnit. */
@Timeout(30)
class PausePriorityTest {
    @Test void higherControlReleasesPartialBarrier() throws Exception {
        PausePriorityScenarios.higherControlReleasesPartialBarrier();
    }
    @Test void higherControlWakesAlreadyBlockedActor() throws Exception {
        PausePriorityScenarios.higherControlWakesAlreadyBlockedActor();
    }
    @Test void stopPausedMovementFinishesLegWithoutServing() throws Exception {
        PausePriorityScenarios.stopPausedMovementFinishesLegWithoutServing();
    }
    @Test void stopPausedDwellPreservesBudgetForStart() throws Exception {
        PausePriorityScenarios.stopPausedDwellPreservesBudgetForStart();
    }
    @Test void resetPausedMovementReturnsPhysicallyAndCancels() throws Exception {
        PausePriorityScenarios.resetPausedMovementReturnsPhysicallyAndCancels();
    }
    @Test void resetPausedDwellDoesNotRequireResume() throws Exception {
        PausePriorityScenarios.resetPausedDwellDoesNotRequireResume();
    }
    @Test void pauseStopRaceResolvesEveryOperation() throws Exception {
        PausePriorityScenarios.pauseStopRaceResolvesEveryOperation();
    }
    @Test void pauseResetRaceResolvesEveryOperation() throws Exception {
        PausePriorityScenarios.pauseResetRaceResolvesEveryOperation();
    }
    @Test void resetSupersedesStopOriginatingInPause() throws Exception {
        PausePriorityScenarios.resetSupersedesStopOriginatingInPause();
    }
    @Test void closeSupersedesResetOriginatingInPause() throws Exception {
        PausePriorityScenarios.closeSupersedesResetOriginatingInPause();
    }
    @Test void pauseIsRejectedWhileStopping() throws Exception {
        PausePriorityScenarios.pauseIsRejectedWhileStopping();
    }
    @Test void repeatedResetDuringPausedReturnUsesOneEpoch() throws Exception {
        PausePriorityScenarios.repeatedResetDuringPausedReturnUsesOneEpoch();
    }
    @Test void transitionsAndPriorityReasonsAreLogged() throws Exception {
        PausePriorityScenarios.transitionsAndPriorityReasonsAreLogged();
    }
    @Test void supersededPausesNeverPublishLatePausedTransitions() throws Exception {
        PausePriorityScenarios.supersededPausesNeverPublishLatePausedTransitions();
    }
}
