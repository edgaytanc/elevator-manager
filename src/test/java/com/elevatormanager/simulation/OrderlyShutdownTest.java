package com.elevatormanager.simulation;

import org.junit.jupiter.api.Test;

class OrderlyShutdownTest {
    @Test void newSessionClosesAutomatically() throws Exception {
        OrderlyShutdownScenarios.newSessionClosesAutomatically();
    }
    @Test void runningIdleSessionClosesAllWorkers() throws Exception {
        OrderlyShutdownScenarios.runningIdleSessionClosesAllWorkers();
    }
    @Test void movementDoesNotDelayCloseByItsDuration() throws Exception {
        OrderlyShutdownScenarios.movementDoesNotDelayCloseByItsDuration();
    }
    @Test void dwellDoesNotDelayCloseByItsDuration() throws Exception {
        OrderlyShutdownScenarios.dwellDoesNotDelayCloseByItsDuration();
    }
    @Test void stoppedSessionWakesAndCloses() throws Exception {
        OrderlyShutdownScenarios.stoppedSessionWakesAndCloses();
    }
    @Test void pendingStopIsResolvedByClose() throws Exception {
        OrderlyShutdownScenarios.pendingStopIsResolvedByClose();
    }
    @Test void pendingResetIsResolvedByClose() throws Exception {
        OrderlyShutdownScenarios.pendingResetIsResolvedByClose();
    }
    @Test void resetReadySessionCloses() throws Exception {
        OrderlyShutdownScenarios.resetReadySessionCloses();
    }
    @Test void concurrentCloseRunsResourcesOnce() throws Exception {
        OrderlyShutdownScenarios.concurrentCloseRunsResourcesOnce();
    }
    @Test void cancelledVisibleFutureDoesNotCancelShutdown() throws Exception {
        OrderlyShutdownScenarios.cancelledVisibleFutureDoesNotCancelShutdown();
    }
    @Test void resourcesCloseInReverseRegistrationOrder() throws Exception {
        OrderlyShutdownScenarios.resourcesCloseInReverseRegistrationOrder();
    }
    @Test void duplicateResourceIsNotClosedTwice() throws Exception {
        OrderlyShutdownScenarios.duplicateResourceIsNotClosedTwice();
    }
    @Test void rejectedResourceRemainsOwnedByCaller() throws Exception {
        OrderlyShutdownScenarios.rejectedResourceRemainsOwnedByCaller();
    }
    @Test void resourcesAreReleasedAfterActorsTerminate() throws Exception {
        OrderlyShutdownScenarios.resourcesAreReleasedAfterActorsTerminate();
    }
    @Test void blockedResourceDoesNotPublishClosed() throws Exception {
        OrderlyShutdownScenarios.blockedResourceDoesNotPublishClosed();
    }
    @Test void failingResourceDoesNotSkipRemainingResources() throws Exception {
        OrderlyShutdownScenarios.failingResourceDoesNotSkipRemainingResources();
    }
    @Test void severalResourceFailuresArePreserved() throws Exception {
        OrderlyShutdownScenarios.severalResourceFailuresArePreserved();
    }
    @Test void timeoutDoesNotCancelCleanup() throws Exception {
        OrderlyShutdownScenarios.timeoutDoesNotCancelCleanup();
    }
    @Test void positiveWaitIsRejectedOnEdt() throws Exception {
        OrderlyShutdownScenarios.positiveWaitIsRejectedOnEdt();
    }
    @Test void resourceCloseRunsOutsideEdt() throws Exception {
        OrderlyShutdownScenarios.resourceCloseRunsOutsideEdt();
    }
    @Test void cleanupSelfWaitIsRejected() throws Exception {
        OrderlyShutdownScenarios.cleanupSelfWaitIsRejected();
    }
    @Test void normalActionsAreRejectedAsSoonAsCloseIsRequested() throws Exception {
        OrderlyShutdownScenarios.normalActionsAreRejectedAsSoonAsCloseIsRequested();
    }
    @Test void invalidResourceAndTimeoutArgumentsAreRejected() throws Exception {
        OrderlyShutdownScenarios.invalidResourceAndTimeoutArgumentsAreRejected();
    }
    @Test void visualCallbacksRunOnlyOnEdt() throws Exception {
        OrderlyShutdownScenarios.visualCallbacksRunOnlyOnEdt();
    }
    @Test void visualCloseWaitsForActualResourceRelease() throws Exception {
        OrderlyShutdownScenarios.visualCloseWaitsForActualResourceRelease();
    }
    @Test void visualFailureDoesNotDisposeAndCanBeReportedAgain() throws Exception {
        OrderlyShutdownScenarios.visualFailureDoesNotDisposeAndCanBeReportedAgain();
    }
    @Test void visualHandlerRequiresEdtAndValidCallbacks() throws Exception {
        OrderlyShutdownScenarios.visualHandlerRequiresEdtAndValidCallbacks();
    }
    @Test void ownedHandlersLeaveForeignHandlersUntouched() throws Exception {
        OrderlyShutdownScenarios.ownedHandlersLeaveForeignHandlersUntouched();
    }
    @Test void failingHandlerStillReleasesOtherHandlers() throws Exception {
        OrderlyShutdownScenarios.failingHandlerStillReleasesOtherHandlers();
    }
    @Test void realFileHandlerIsReleasedAndReopened() throws Exception {
        OrderlyShutdownScenarios.realFileHandlerIsReleasedAndReopened();
    }
    @Test void handlerOwnershipArgumentsAreValidated() throws Exception {
        OrderlyShutdownScenarios.handlerOwnershipArgumentsAreValidated();
    }
    @Test void successfulTerminationLeavesNoOwnedThreadAlive() throws Exception {
        OrderlyShutdownScenarios.successfulTerminationLeavesNoOwnedThreadAlive();
    }
}
