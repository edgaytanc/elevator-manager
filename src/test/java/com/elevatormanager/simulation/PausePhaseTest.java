package com.elevatormanager.simulation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** T57: matriz temporal sobre actores reales. */
@Timeout(30)
class PausePhaseTest {
    @Test void upwardMovementEarlyPause() throws Exception {
        PausePhaseScenarios.upwardMovementEarlyPause();
    }
    @Test void upwardMovementMiddlePause() throws Exception {
        PausePhaseScenarios.upwardMovementMiddlePause();
    }
    @Test void upwardMovementLatePause() throws Exception {
        PausePhaseScenarios.upwardMovementLatePause();
    }
    @Test void downwardMovementMiddlePause() throws Exception {
        PausePhaseScenarios.downwardMovementMiddlePause();
    }
    @Test void downwardMovementLatePause() throws Exception {
        PausePhaseScenarios.downwardMovementLatePause();
    }
    @Test void dwellEarlyPause() throws Exception {
        PausePhaseScenarios.dwellEarlyPause();
    }
    @Test void dwellMiddlePause() throws Exception {
        PausePhaseScenarios.dwellMiddlePause();
    }
    @Test void dwellLatePause() throws Exception {
        PausePhaseScenarios.dwellLatePause();
    }
    @Test void downwardDwellMiddlePause() throws Exception {
        PausePhaseScenarios.downwardDwellMiddlePause();
    }
    @Test void repeatedPausesWithinOneMovementKeepBudget() throws Exception {
        PausePhaseScenarios.repeatedPausesWithinOneMovementKeepBudget();
    }
    @Test void repeatedPausesWithinOneDwellKeepBudget() throws Exception {
        PausePhaseScenarios.repeatedPausesWithinOneDwellKeepBudget();
    }
    @Test void idlePauseBeforeFirstRequestKeepsEmptyState() throws Exception {
        PausePhaseScenarios.idlePauseBeforeFirstRequestKeepsEmptyState();
    }
    @Test void idlePauseAfterWorkPreservesFloorAndDirection() throws Exception {
        PausePhaseScenarios.idlePauseAfterWorkPreservesFloorAndDirection();
    }
    @Test void mixedMovementDwellAndIdleShareOnePause() throws Exception {
        PausePhaseScenarios.mixedMovementDwellAndIdleShareOnePause();
    }
    @Test void oppositeHallCallsAndDestinationSurviveRepeatedPause() throws Exception {
        PausePhaseScenarios.oppositeHallCallsAndDestinationSurviveRepeatedPause();
    }
    @Test void minimumDurationsAllowPauseAtPhaseBoundaries() throws Exception {
        PausePhaseScenarios.minimumDurationsAllowPauseAtPhaseBoundaries();
    }
    @Test void singleFloorDwellCanPauseWithoutCreatingMovement() throws Exception {
        PausePhaseScenarios.singleFloorDwellCanPauseWithoutCreatingMovement();
    }
    @Test void manyIdleCabinsBlockAndKeepTheirThreadIdentities() throws Exception {
        PausePhaseScenarios.manyIdleCabinsBlockAndKeepTheirThreadIdentities();
    }
}
