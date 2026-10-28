package com.elevatormanager.io;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Adaptadores JUnit de los escenarios T46. */
@Timeout(60)
class ReadFailureTest {
    @Test void validCatalogFilesMatchTheirConfigurations() throws Exception {
        ReadFailureScenarios.validCatalogFilesMatchTheirConfigurations();
    }
    @Test void invalidCatalogFilesMatchDiagnosticsAndLines() throws Exception {
        ReadFailureScenarios.invalidCatalogFilesMatchDiagnosticsAndLines();
    }
    @Test void intentionalBytesArePreserved() throws Exception {
        ReadFailureScenarios.intentionalBytesArePreserved();
    }
    @Test void repeatedFixtureKeepsOrderAndAllEntries() throws Exception {
        ReadFailureScenarios.repeatedFixtureKeepsOrderAndAllEntries();
    }
    @Test void deniedOpenPreservesOriginalCause() throws Exception {
        ReadFailureScenarios.deniedOpenPreservesOriginalCause();
    }
    @Test void securityFailureAtOpenIsReported() throws Exception {
        ReadFailureScenarios.securityFailureAtOpenIsReported();
    }
    @Test void partialReadFailureClosesOwnedStreamAndRejectsBatch() throws Exception {
        ReadFailureScenarios.partialReadFailureClosesOwnedStreamAndRejectsBatch();
    }
    @Test void closeFailureRejectsOtherwiseValidRead() throws Exception {
        ReadFailureScenarios.closeFailureRejectsOtherwiseValidRead();
    }
    @Test void primaryReadErrorRetainsSuppressedCloseError() throws Exception {
        ReadFailureScenarios.primaryReadErrorRetainsSuppressedCloseError();
    }
    @Test void sizeLimitRejectionAlsoClosesOwnedStream() throws Exception {
        ReadFailureScenarios.sizeLimitRejectionAlsoClosesOwnedStream();
    }
    @Test void sameReaderRecoversAfterTransientDeniedOpen() throws Exception {
        ReadFailureScenarios.sameReaderRecoversAfterTransientDeniedOpen();
    }
    @Test void independentDeniedReadsDoNotShareResultState() throws Exception {
        ReadFailureScenarios.independentDeniedReadsDoNotShareResultState();
    }
}
