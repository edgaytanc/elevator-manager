package com.elevatormanager.logging;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Adaptadores JUnit para los 16 escenarios de T41. */
@Timeout(60)
class LoggingTest {
    @Test void filesAreIsolatedAndConsoleContainsBoth() throws Exception {
        LoggingScenarios.filesAreIsolatedAndConsoleContainsBoth();
    }
    @Test void formatterHasDateIdLevelAndParameters() throws Exception {
        LoggingScenarios.formatterHasDateIdLevelAndParameters();
    }
    @Test void utf8AndLineBreaksArePreservedSafely() throws Exception {
        LoggingScenarios.utf8AndLineBreaksArePreservedSafely();
    }
    @Test void parentHandlersAreNotInvoked() throws Exception {
        LoggingScenarios.parentHandlersAreNotInvoked();
    }
    @Test void repeatedLookupDoesNotAddHandlers() throws Exception {
        LoggingScenarios.repeatedLookupDoesNotAddHandlers();
    }
    @Test void recordsAreFlushedBeforeClose() throws Exception {
        LoggingScenarios.recordsAreFlushedBeforeClose();
    }
    @Test void closeIsIdempotentAndDoesNotCloseConsole() throws Exception {
        LoggingScenarios.closeIsIdempotentAndDoesNotCloseConsole();
    }
    @Test void reopeningAppendsAndReleasesFileLocks() throws Exception {
        LoggingScenarios.reopeningAppendsAndReleasesFileLocks();
    }
    @Test void simultaneousSessionInSameDirectoryIsRejected() throws Exception {
        LoggingScenarios.simultaneousSessionInSameDirectoryIsRejected();
    }
    @Test void differentDirectoriesHaveIndependentSessions() throws Exception {
        LoggingScenarios.differentDirectoriesHaveIndependentSessions();
    }
    @Test void percentInDirectoryIsLiteral() throws Exception {
        LoggingScenarios.percentInDirectoryIsLiteral();
    }
    @Test void failedSetupReleasesEarlierHandlersAndDirectoryLock() throws Exception {
        LoggingScenarios.failedSetupReleasesEarlierHandlersAndDirectoryLock();
    }
    @Test void invalidArgumentsDoNotCreateFiles() throws Exception {
        LoggingScenarios.invalidArgumentsDoNotCreateFiles();
    }
    @Test void concurrentCabinsWriteExactlyOncePerDestination() throws Exception {
        LoggingScenarios.concurrentCabinsWriteExactlyOncePerDestination();
    }
    @Test void controllerOwnsAndReleasesLogging() throws Exception {
        LoggingScenarios.controllerOwnsAndReleasesLogging();
    }
    @Test void levelsAndExceptionsRemainVisible() throws Exception {
        LoggingScenarios.levelsAndExceptionsRemainVisible();
    }
}
