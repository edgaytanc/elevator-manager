package com.elevatormanager.simulation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Adaptadores JUnit de los escenarios T46. */
@Timeout(60)
class ImportReadErrorTest {
    @Test void catalogErrorsPreserveBatchAndSessionRecovers() throws Exception {
        ImportReadErrorScenarios.catalogErrorsPreserveBatchAndSessionRecovers();
    }
    @Test void accessDeniedPreservesBatchAndSamePathCanRecover() throws Exception {
        ImportReadErrorScenarios.accessDeniedPreservesBatchAndSamePathCanRecover();
    }
    @Test void partialReadFailureKeepsPreparedAndNextImportWorks() throws Exception {
        ImportReadErrorScenarios.partialReadFailureKeepsPreparedAndNextImportWorks();
    }
    @Test void cancellingSelectorDoesNotCancelAnotherActiveRead() throws Exception {
        ImportReadErrorScenarios.cancellingSelectorDoesNotCancelAnotherActiveRead();
    }
    @Test void readErrorDoesNotPreventResetAndFreshImport() throws Exception {
        ImportReadErrorScenarios.readErrorDoesNotPreventResetAndFreshImport();
    }
    @Test void selectedFileMayDisappearBeforeReadWithoutBreakingSession() throws Exception {
        ImportReadErrorScenarios.selectedFileMayDisappearBeforeReadWithoutBreakingSession();
    }
    @Test void repeatedErrorsAndRecoveryReleaseSessionThreads() throws Exception {
        ImportReadErrorScenarios.repeatedErrorsAndRecoveryReleaseSessionThreads();
    }
    @Test void everyValidDistributedExampleRunsWithRealActors() throws Exception {
        ImportReadErrorScenarios.everyValidDistributedExampleRunsWithRealActors();
    }
}
