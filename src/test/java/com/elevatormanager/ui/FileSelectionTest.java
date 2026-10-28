package com.elevatormanager.ui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Adaptadores JUnit de los escenarios T46. */
@Timeout(60)
class FileSelectionTest {
    @Test void cancellingSelectionPreservesBatchAndReceipt() throws Exception {
        FileSelectionScenarios.cancellingSelectionPreservesBatchAndReceipt();
    }
    @Test void approvedSelectionImportsAndCallbacksRunOnEdt() throws Exception {
        FileSelectionScenarios.approvedSelectionImportsAndCallbacksRunOnEdt();
    }
    @Test void invalidSelectionPreservesBatchAndHandlerCanRetry() throws Exception {
        FileSelectionScenarios.invalidSelectionPreservesBatchAndHandlerCanRetry();
    }
    @Test void missingSelectionPreservesBatchAndAllowsRecovery() throws Exception {
        FileSelectionScenarios.missingSelectionPreservesBatchAndAllowsRecovery();
    }
    @Test void emptySelectedFileIsRejectedInsteadOfCancellation() throws Exception {
        FileSelectionScenarios.emptySelectedFileIsRejectedInsteadOfCancellation();
    }
    @Test void requestOutsideResetDoesNotOpenSelector() throws Exception {
        FileSelectionScenarios.requestOutsideResetDoesNotOpenSelector();
    }
    @Test void requestingOffEdtIsRejected() throws Exception {
        FileSelectionScenarios.requestingOffEdtIsRejected();
    }
    @Test void selectorFailureIsReportedOnEdt() throws Exception {
        FileSelectionScenarios.selectorFailureIsReportedOnEdt();
    }
    @Test void nullSelectionIsReportedWithoutMutatingBatch() throws Exception {
        FileSelectionScenarios.nullSelectionIsReportedWithoutMutatingBatch();
    }
    @Test void stateChangeDuringDialogIsRevalidatedByController() throws Exception {
        FileSelectionScenarios.stateChangeDuringDialogIsRevalidatedByController();
    }
    @Test void nestedModalInvocationDoesNotOpenAnotherSelector() throws Exception {
        FileSelectionScenarios.nestedModalInvocationDoesNotOpenAnotherSelector();
    }
    @Test void delayedResultIsMarkedStaleBeforeVisualApplication() throws Exception {
        FileSelectionScenarios.delayedResultIsMarkedStaleBeforeVisualApplication();
    }
}
