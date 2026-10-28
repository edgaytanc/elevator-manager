package com.elevatormanager.logging;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Los mismos 24 escenarios de integración y errores, ejecutados por JUnit. */
@Timeout(60)
class SessionLoggingTest {
    @Test void obligatoryEventsComeFromPhysicalActors() throws Exception {
        SessionLoggingScenarios.obligatoryEventsComeFromPhysicalActors();
    }
    @Test void realCabinsRemainIsolatedAndConsoleHasOneCopy() throws Exception {
        SessionLoggingScenarios.realCabinsRemainIsolatedAndConsoleHasOneCopy();
    }
    @Test void dispatchAdmissionAndServiceAreDifferentFacts() throws Exception {
        SessionLoggingScenarios.dispatchAdmissionAndServiceAreDifferentFacts();
    }
    @Test void duplicateHallRequestKeepsCanonicalIdentity() throws Exception {
        SessionLoggingScenarios.duplicateHallRequestKeepsCanonicalIdentity();
    }
    @Test void resetKeepsOldHistoryWithoutCompletingCancelledWork() throws Exception {
        SessionLoggingScenarios.resetKeepsOldHistoryWithoutCompletingCancelledWork();
    }
    @Test void sequencesRemainUniqueAcrossResets() throws Exception {
        SessionLoggingScenarios.sequencesRemainUniqueAcrossResets();
    }
    @Test void closingInFlightRecordsTerminationWithoutInventingArrival() throws Exception {
        SessionLoggingScenarios.closingInFlightRecordsTerminationWithoutInventingArrival();
    }
    @Test void closingDrainsEveryQueuedEvent() throws Exception {
        SessionLoggingScenarios.closingDrainsEveryQueuedEvent();
    }
    @Test void appendDistinguishesSessions() throws Exception {
        SessionLoggingScenarios.appendDistinguishesSessions();
    }
    @Test void barrierMakesPriorWritesVisible() throws Exception {
        SessionLoggingScenarios.barrierMakesPriorWritesVisible();
    }
    @Test void consumerFailureIsVisibleAndCleanupStillRuns() throws Exception {
        SessionLoggingScenarios.consumerFailureIsVisibleAndCleanupStillRuns();
    }
    @Test void consoleWriteFailureIsReportedOutOfBand() throws Exception {
        SessionLoggingScenarios.consoleWriteFailureIsReportedOutOfBand();
    }
    @Test void fileWriteFailurePreservesCauseAndHealthyConsole() throws Exception {
        SessionLoggingScenarios.fileWriteFailurePreservesCauseAndHealthyConsole();
    }
    @Test void repeatedHandlerFailuresAreCountedAndFirstIsRetained() throws Exception {
        SessionLoggingScenarios.repeatedHandlerFailuresAreCountedAndFirstIsRetained();
    }
    @Test void flushFailureIsDetected() throws Exception {
        SessionLoggingScenarios.flushFailureIsDetected();
    }
    @Test void closeFailureDoesNotKeepFileLock() throws Exception {
        SessionLoggingScenarios.closeFailureDoesNotKeepFileLock();
    }
    @Test void managerSetupFailureRollsBackCabinResources() throws Exception {
        SessionLoggingScenarios.managerSetupFailureRollsBackCabinResources();
    }
    @Test void unusedControllerClosesConsumerAndFiles() throws Exception {
        SessionLoggingScenarios.unusedControllerClosesConsumerAndFiles();
    }
    @Test void closedSessionRejectsLatePublications() throws Exception {
        SessionLoggingScenarios.closedSessionRejectsLatePublications();
    }
    @Test void sessionOwnershipCannotBeSharedOrMismatched() throws Exception {
        SessionLoggingScenarios.sessionOwnershipCannotBeSharedOrMismatched();
    }
    @Test void openingAndClosingOnEdtAreRejected() throws Exception {
        SessionLoggingScenarios.openingAndClosingOnEdtAreRejected();
    }
    @Test void actorFailureKeepsExceptionTrace() throws Exception {
        SessionLoggingScenarios.actorFailureKeepsExceptionTrace();
    }
    @Test void realManagerFailureIsLogged() throws Exception {
        SessionLoggingScenarios.realManagerFailureIsLogged();
    }
    @Test void degradedLoggingPreventsSuccessfulControllerClose() throws Exception {
        SessionLoggingScenarios.degradedLoggingPreventsSuccessfulControllerClose();
    }
}
