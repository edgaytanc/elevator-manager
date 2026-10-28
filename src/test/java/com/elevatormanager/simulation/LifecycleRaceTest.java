package com.elevatormanager.simulation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.util.concurrent.TimeUnit;

/** Adaptadores de T40; el límite externo complementa los plazos de cada barrera. */
@Timeout(value = 90, unit = TimeUnit.SECONDS)
class LifecycleRaceTest {
    @Test void resetDuringMovementCancelsOldRoute() throws Exception {
        LifecycleRaceScenarios.resetDuringMovementCancelsOldRoute();
    }
    @Test void resetDuringSeveralCabinJourneysIsGlobal() throws Exception {
        LifecycleRaceScenarios.resetDuringSeveralCabinJourneysIsGlobal();
    }
    @Test void resetDuringDwellDoesNotWaitForDwell() throws Exception {
        LifecycleRaceScenarios.resetDuringDwellDoesNotWaitForDwell();
    }
    @Test void resetWhileDwellIsStoppedClearsHold() throws Exception {
        LifecycleRaceScenarios.resetWhileDwellIsStoppedClearsHold();
    }
    @Test void resetFromStoppedDiscardsPreservedDestination() throws Exception {
        LifecycleRaceScenarios.resetFromStoppedDiscardsPreservedDestination();
    }
    @Test void concurrentInitialStartCreatesOneSetOfActors() throws Exception {
        LifecycleRaceScenarios.concurrentInitialStartCreatesOneSetOfActors();
    }
    @Test void concurrentStartFromStoppedResumesOnce() throws Exception {
        LifecycleRaceScenarios.concurrentStartFromStoppedResumesOnce();
    }
    @Test void concurrentStopWhenIdleHasOnePhysicalBarrier() throws Exception {
        LifecycleRaceScenarios.concurrentStopWhenIdleHasOnePhysicalBarrier();
    }
    @Test void concurrentStopDuringMovementPreservesRoute() throws Exception {
        LifecycleRaceScenarios.concurrentStopDuringMovementPreservesRoute();
    }
    @Test void startAndStopRaceHasAValidSerializableOutcome() throws Exception {
        LifecycleRaceScenarios.startAndStopRaceHasAValidSerializableOutcome();
    }
    @Test void stopAndResetRaceCannotRestoreStoppedEpoch() throws Exception {
        LifecycleRaceScenarios.stopAndResetRaceCannotRestoreStoppedEpoch();
    }
    @Test void startAndResetRaceCannotStartOldWork() throws Exception {
        LifecycleRaceScenarios.startAndResetRaceCannotStartOldWork();
    }
    @Test void cabinAdmissionRacingResetDoesNotSurviveIt() throws Exception {
        LifecycleRaceScenarios.cabinAdmissionRacingResetDoesNotSurviveIt();
    }
    @Test void hallAdmissionRacingResetDoesNotSurviveIt() throws Exception {
        LifecycleRaceScenarios.hallAdmissionRacingResetDoesNotSurviveIt();
    }
    @Test void closeWhileResettingResolvesEveryRacingControl() throws Exception {
        LifecycleRaceScenarios.closeWhileResettingResolvesEveryRacingControl();
    }
    @Test void closeWhileStoppingResolvesEveryRacingControl() throws Exception {
        LifecycleRaceScenarios.closeWhileStoppingResolvesEveryRacingControl();
    }
    @Test void initialStartRacingCloseCannotResurrectSession() throws Exception {
        LifecycleRaceScenarios.initialStartRacingCloseCannotResurrectSession();
    }
    @Test void admissionsRacingCloseAreResolvedWithoutServiceClaim() throws Exception {
        LifecycleRaceScenarios.admissionsRacingCloseAreResolvedWithoutServiceClaim();
    }
    @Test void closingGateRejectsAllControlsUntilResourceRelease() throws Exception {
        LifecycleRaceScenarios.closingGateRejectsAllControlsUntilResourceRelease();
    }
    @Test void delayedEdtMessagesAreRevalidatedBeforeApplication() throws Exception {
        LifecycleRaceScenarios.delayedEdtMessagesAreRevalidatedBeforeApplication();
    }
    @Test void bufferedHistoryAcrossEpochsDoesNotCompleteFreshWork() throws Exception {
        LifecycleRaceScenarios.bufferedHistoryAcrossEpochsDoesNotCompleteFreshWork();
    }
    @Test void cancelledStopObserverDoesNotCancelResetOrClose() throws Exception {
        LifecycleRaceScenarios.cancelledStopObserverDoesNotCancelResetOrClose();
    }
    @Test void repeatedMixedControlsKeepExactThreadIdentities() throws Exception {
        LifecycleRaceScenarios.repeatedMixedControlsKeepExactThreadIdentities();
    }
    @Test void repeatedSessionsDoNotAccumulateOwnedThreads() throws Exception {
        LifecycleRaceScenarios.repeatedSessionsDoNotAccumulateOwnedThreads();
    }
}
