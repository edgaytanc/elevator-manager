package com.elevatormanager.ui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** T56: escenarios completos bajo JUnit. */
@Timeout(30)
class PauseControlsTest {
    @Test void pauseControlsRequireConfiguredRunningSession() throws Exception {
        PauseControlsScenarios.pauseControlsRequireConfiguredRunningSession();
    }
    @Test void pauseRequestImmediatelyShowsPausingAndKeepsPriorityControls() throws Exception {
        PauseControlsScenarios.pauseRequestImmediatelyShowsPausingAndKeepsPriorityControls();
    }
    @Test void pausedButtonMatrixBlocksRequestsAndImport() throws Exception {
        PauseControlsScenarios.pausedButtonMatrixBlocksRequestsAndImport();
    }
    @Test void resumeButtonKeepsSessionAndPendingLight() throws Exception {
        PauseControlsScenarios.resumeButtonKeepsSessionAndPendingLight();
    }
    @Test void stopButtonWorksFromPausedMovement() throws Exception {
        PauseControlsScenarios.stopButtonWorksFromPausedMovement();
    }
    @Test void resetButtonWorksFromPausedMovement() throws Exception {
        PauseControlsScenarios.resetButtonWorksFromPausedMovement();
    }
    @Test void immediateStopSupersedesPendingPauseInSameEdtTurn() throws Exception {
        PauseControlsScenarios.immediateStopSupersedesPendingPauseInSameEdtTurn();
    }
    @Test void immediateResetSupersedesPendingPauseInSameEdtTurn() throws Exception {
        PauseControlsScenarios.immediateResetSupersedesPendingPauseInSameEdtTurn();
    }
    @Test void closeButtonReleasesPausedActorsAndSampler() throws Exception {
        PauseControlsScenarios.closeButtonReleasesPausedActorsAndSampler();
    }
    @Test void closeButtonCanSupersedePendingPause() throws Exception {
        PauseControlsScenarios.closeButtonCanSupersedePendingPause();
    }
    @Test void viewerShowsPauseAndResumeTransitions() throws Exception {
        PauseControlsScenarios.viewerShowsPauseAndResumeTransitions();
    }
    @Test void repeatedButtonCyclesDoNotCreateSessions() throws Exception {
        PauseControlsScenarios.repeatedButtonCyclesDoNotCreateSessions();
    }
    @Test void stoppedAndResettingDisablePauseAndResume() throws Exception {
        PauseControlsScenarios.stoppedAndResettingDisablePauseAndResume();
    }
    @Test void pausedResetRestoresImportThroughButtons() throws Exception {
        PauseControlsScenarios.pausedResetRestoresImportThroughButtons();
    }
}
