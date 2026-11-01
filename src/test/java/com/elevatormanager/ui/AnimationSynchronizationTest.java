package com.elevatormanager.ui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import com.elevatormanager.simulation.time.ClockViewScenarios;

/** Adaptadores JUnit T60: 20 de sincronización y 4 de publicación temporal. */
@Timeout(30)
class AnimationSynchronizationTest {
    @Test void oldMovingFrameFreezesAtActualPauseInstant() throws Exception {
        AnimationSynchronizationScenarios.oldMovingFrameFreezesAtActualPauseInstant();
    }
    @Test void lateHeldFrameDoesNotMovePausedPosition() throws Exception {
        AnimationSynchronizationScenarios.lateHeldFrameDoesNotMovePausedPosition();
    }
    @Test void oldHeldFrameContinuesWithoutPausedWallTime() throws Exception {
        AnimationSynchronizationScenarios.oldHeldFrameContinuesWithoutPausedWallTime();
    }
    @Test void repeatedPauseCyclesPreservePositionAndBudget() throws Exception {
        AnimationSynchronizationScenarios.repeatedPauseCyclesPreservePositionAndBudget();
    }
    @Test void stopFinishesOnlyPublishedAdjacentLeg() throws Exception {
        AnimationSynchronizationScenarios.stopFinishesOnlyPublishedAdjacentLeg();
    }
    @Test void stopFromPausedMovementReleasesHeldProjection() throws Exception {
        AnimationSynchronizationScenarios.stopFromPausedMovementReleasesHeldProjection();
    }
    @Test void startFromStoppedDoesNotResetPosition() throws Exception {
        AnimationSynchronizationScenarios.startFromStoppedDoesNotResetPosition();
    }
    @Test void resetRetainsPhysicalLegAcrossEpochChange() throws Exception {
        AnimationSynchronizationScenarios.resetRetainsPhysicalLegAcrossEpochChange();
    }
    @Test void resetReturnMovesDownThroughIntermediatePositions() throws Exception {
        AnimationSynchronizationScenarios.resetReturnMovesDownThroughIntermediatePositions();
    }
    @Test void resetFromPausedHeldFramePreservesRemainder() throws Exception {
        AnimationSynchronizationScenarios.resetFromPausedHeldFramePreservesRemainder();
    }
    @Test void closeFreezesEvenWhenLastFrameWasHeld() throws Exception {
        AnimationSynchronizationScenarios.closeFreezesEvenWhenLastFrameWasHeld();
    }
    @Test void invalidationDiscardsLateCapture() throws Exception {
        AnimationSynchronizationScenarios.invalidationDiscardsLateCapture();
    }
    @Test void freshCaptureWaitsForOneWorkerAndBypassesGate() throws Exception {
        AnimationSynchronizationScenarios.freshCaptureWaitsForOneWorkerAndBypassesGate();
    }
    @Test void invalidatedFailureCannotReplaceFreshStatus() throws Exception {
        AnimationSynchronizationScenarios.invalidatedFailureCannotReplaceFreshStatus();
    }
    @Test void closeCancelsPendingFreshCapture() throws Exception {
        AnimationSynchronizationScenarios.closeCancelsPendingFreshCapture();
    }
    @Test void realPauseResumeKeepsExactPosition() throws Exception {
        AnimationSynchronizationScenarios.realPauseResumeKeepsExactPosition();
    }
    @Test void realDwellAndIdleRemainFixedWhilePaused() throws Exception {
        AnimationSynchronizationScenarios.realDwellAndIdleRemainFixedWhilePaused();
    }
    @Test void realStopFromPauseCompletesAdjacentLeg() throws Exception {
        AnimationSynchronizationScenarios.realStopFromPauseCompletesAdjacentLeg();
    }
    @Test void realResetFromPauseShowsPhysicalReturn() throws Exception {
        AnimationSynchronizationScenarios.realResetFromPauseShowsPhysicalReturn();
    }
    @Test void systemButtonsSynchronizeAndCloseAnimation() throws Exception {
        AnimationSynchronizationScenarios.systemButtonsSynchronizeAndCloseAnimation();
    }
    @Test void publishedViewMatchesActiveTime() throws Exception {
        ClockViewScenarios.publishedViewMatchesActiveTime();
    }
    @Test void repeatedPauseResumeExcludesWallIntervals() throws Exception {
        ClockViewScenarios.repeatedPauseResumeExcludesWallIntervals();
    }
    @Test void closingFreezesReadableView() throws Exception {
        ClockViewScenarios.closingFreezesReadableView();
    }
    @Test void readingViewDoesNotWaitForClockLock() throws Exception {
        ClockViewScenarios.readingViewDoesNotWaitForClockLock();
    }
}
