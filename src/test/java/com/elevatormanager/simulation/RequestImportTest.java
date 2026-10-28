package com.elevatormanager.simulation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Los mismos 40 escenarios de importación y ciclo de vida, con actores reales. */
@Timeout(60)
class RequestImportTest {
    @Test void initialImportDoesNotStartActors() throws Exception {
        RequestImportScenarios.initialImportDoesNotStartActors();
    }
    @Test void importAfterResetKeepsCabinsIdle() throws Exception {
        RequestImportScenarios.importAfterResetKeepsCabinsIdle();
    }
    @Test void startingServicesMixedImportedBatch() throws Exception {
        RequestImportScenarios.startingServicesMixedImportedBatch();
    }
    @Test void repeatedStartNeverReleasesBatchAgain() throws Exception {
        RequestImportScenarios.repeatedStartNeverReleasesBatchAgain();
    }
    @Test void stopStartContinuesOriginalBatchRequests() throws Exception {
        RequestImportScenarios.stopStartContinuesOriginalBatchRequests();
    }
    @Test void validReplacementIsAtomicAndUsesFreshIdentities() throws Exception {
        RequestImportScenarios.validReplacementIsAtomicAndUsesFreshIdentities();
    }
    @Test void invalidFinalLinePreservesPreviousBatch() throws Exception {
        RequestImportScenarios.invalidFinalLinePreservesPreviousBatch();
    }
    @Test void missingFilePreservesPreviousBatch() throws Exception {
        RequestImportScenarios.missingFilePreservesPreviousBatch();
    }
    @Test void emptyFilePreservesPreviousBatch() throws Exception {
        RequestImportScenarios.emptyFilePreservesPreviousBatch();
    }
    @Test void duplicateEntriesRemainDistinctAtConfirmation() throws Exception {
        RequestImportScenarios.duplicateEntriesRemainDistinctAtConfirmation();
    }
    @Test void runningImportIsRejectedBeforeReading() throws Exception {
        RequestImportScenarios.runningImportIsRejectedBeforeReading();
    }
    @Test void stoppedImportIsRejectedBeforeReading() throws Exception {
        RequestImportScenarios.stoppedImportIsRejectedBeforeReading();
    }
    @Test void stoppingImportIsRejectedBeforeReading() throws Exception {
        RequestImportScenarios.stoppingImportIsRejectedBeforeReading();
    }
    @Test void resettingImportIsRejectedBeforeReading() throws Exception {
        RequestImportScenarios.resettingImportIsRejectedBeforeReading();
    }
    @Test void closingAndClosedImportsAreRejected() throws Exception {
        RequestImportScenarios.closingAndClosedImportsAreRejected();
    }
    @Test void startDuringReadInvalidatesLateSuccess() throws Exception {
        RequestImportScenarios.startDuringReadInvalidatesLateSuccess();
    }
    @Test void resetDuringReadRejectsOldEpochEvenBackInReset() throws Exception {
        RequestImportScenarios.resetDuringReadRejectsOldEpochEvenBackInReset();
    }
    @Test void resetClearsConfirmedBatchBeforeNextStart() throws Exception {
        RequestImportScenarios.resetClearsConfirmedBatchBeforeNextStart();
    }
    @Test void closeWaitsForActualReaderTermination() throws Exception {
        RequestImportScenarios.closeWaitsForActualReaderTermination();
    }
    @Test void newestReadSupersedesAndPreservesOldBatchWhilePending() throws Exception {
        RequestImportScenarios.newestReadSupersedesAndPreservesOldBatchWhilePending();
    }
    @Test void repeatedSupersedingKeepsOneQueuedRead() throws Exception {
        RequestImportScenarios.repeatedSupersedingKeepsOneQueuedRead();
    }
    @Test void explicitCancellationKeepsPreviousBatch() throws Exception {
        RequestImportScenarios.explicitCancellationKeepsPreviousBatch();
    }
    @Test void cancellingVisibleFutureDoesNotCancelOperation() throws Exception {
        RequestImportScenarios.cancellingVisibleFutureDoesNotCancelOperation();
    }
    @Test void incompatibleConfigurationCannotReplaceBatch() throws Exception {
        RequestImportScenarios.incompatibleConfigurationCannotReplaceBatch();
    }
    @Test void loaderExceptionIsInvalidAndKeepsOriginalCause() throws Exception {
        RequestImportScenarios.loaderExceptionIsInvalidAndKeepsOriginalCause();
    }
    @Test void nullLoaderResultCannotLeaveUnresolvedFuture() throws Exception {
        RequestImportScenarios.nullLoaderResultCannotLeaveUnresolvedFuture();
    }
    @Test void edtAndCoordinatorStayResponsiveDuringRead() throws Exception {
        RequestImportScenarios.edtAndCoordinatorStayResponsiveDuringRead();
    }
    @Test void confirmationDoesNotConsumeCommandIds() throws Exception {
        RequestImportScenarios.confirmationDoesNotConsumeCommandIds();
    }
    @Test void receiptsAreBoundToTheirSession() throws Exception {
        RequestImportScenarios.receiptsAreBoundToTheirSession();
    }
    @Test void delayedReceiptIsRejectedAfterReplacement() throws Exception {
        RequestImportScenarios.delayedReceiptIsRejectedAfterReplacement();
    }
    @Test void confirmedRequestsShareTimeAndHaveUniqueIds() throws Exception {
        RequestImportScenarios.confirmedRequestsShareTimeAndHaveUniqueIds();
    }
    @Test void dispatchReportPreservesOrderWithoutClaimingService() throws Exception {
        RequestImportScenarios.dispatchReportPreservesOrderWithoutClaimingService();
    }
    @Test void importingAfterResetKeepsActorThreadIdentities() throws Exception {
        RequestImportScenarios.importingAfterResetKeepsActorThreadIdentities();
    }
    @Test void importingAfterCompletedResetUsesNewEpoch() throws Exception {
        RequestImportScenarios.importingAfterCompletedResetUsesNewEpoch();
    }
    @Test void closeClearsConfirmedBatch() throws Exception {
        RequestImportScenarios.closeClearsConfirmedBatch();
    }
    @Test void importedAndManualRequestsUseSameAdmissionPath() throws Exception {
        RequestImportScenarios.importedAndManualRequestsUseSameAdmissionPath();
    }
    @Test void cancellationWithoutReadDoesNotEraseBatch() throws Exception {
        RequestImportScenarios.cancellationWithoutReadDoesNotEraseBatch();
    }
    @Test void staleInvalidResultCannotEraseNewerBatch() throws Exception {
        RequestImportScenarios.staleInvalidResultCannotEraseNewerBatch();
    }
    @Test void publicRecordsAreImmutableAndValidateCorrelation() throws Exception {
        RequestImportScenarios.publicRecordsAreImmutableAndValidateCorrelation();
    }
    @Test void repeatedSessionsReleaseEveryOwnedThread() throws Exception {
        RequestImportScenarios.repeatedSessionsReleaseEveryOwnedThread();
    }
}
