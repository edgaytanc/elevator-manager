package com.elevatormanager.simulation;

import org.junit.jupiter.api.Test;

class GlobalResetTest {
    @Test void initialResetConfirmsEveryParticipant() throws Exception {
        GlobalResetScenarios.initialResetConfirmsEveryParticipant();
    }
    @Test void waitsForSlowestCabin() throws Exception {
        GlobalResetScenarios.waitsForSlowestCabin();
    }
    @Test void resetFromStoppedReturnsWithoutStart() throws Exception {
        GlobalResetScenarios.resetFromStoppedReturnsWithoutStart();
    }
    @Test void resetSupersedesGlobalStopping() throws Exception {
        GlobalResetScenarios.resetSupersedesGlobalStopping();
    }
    @Test void openDwellIsCancelledGlobally() throws Exception {
        GlobalResetScenarios.openDwellIsCancelledGlobally();
    }
    @Test void repeatedResetSharesOneEpoch() throws Exception {
        GlobalResetScenarios.repeatedResetSharesOneEpoch();
    }
    @Test void anotherResetAfterReadyCreatesNewEpoch() throws Exception {
        GlobalResetScenarios.anotherResetAfterReadyCreatesNewEpoch();
    }
    @Test void controlsAreRejectedWhileResetting() throws Exception {
        GlobalResetScenarios.controlsAreRejectedWhileResetting();
    }
    @Test void oldAdmissionResponseCanBeRecognized() throws Exception {
        GlobalResetScenarios.oldAdmissionResponseCanBeRecognized();
    }
    @Test void extractedOldEventMustBeRevalidated() throws Exception {
        GlobalResetScenarios.extractedOldEventMustBeRevalidated();
    }
    @Test void bufferedOldEventsAreNotReturnedAsCurrent() throws Exception {
        GlobalResetScenarios.bufferedOldEventsAreNotReturnedAsCurrent();
    }
    @Test void staleManualRequestsCannotReenterNewRun() throws Exception {
        GlobalResetScenarios.staleManualRequestsCannotReenterNewRun();
    }
    @Test void freshEpochMayReuseCancelledUuid() throws Exception {
        GlobalResetScenarios.freshEpochMayReuseCancelledUuid();
    }
    @Test void commandIdsKeepGrowingAcrossReset() throws Exception {
        GlobalResetScenarios.commandIdsKeepGrowingAcrossReset();
    }
    @Test void oneFloorResetAndContinuationWork() throws Exception {
        GlobalResetScenarios.oneFloorResetAndContinuationWork();
    }
    @Test void exhaustedEpochDoesNotWrapOrStartWorkers() throws Exception {
        GlobalResetScenarios.exhaustedEpochDoesNotWrapOrStartWorkers();
    }
    @Test void closeCancelsPendingGlobalReset() throws Exception {
        GlobalResetScenarios.closeCancelsPendingGlobalReset();
    }
    @Test void closeAfterResetWakesHeldActors() throws Exception {
        GlobalResetScenarios.closeAfterResetWakesHeldActors();
    }
    @Test void edtRemainsResponsiveDuringGlobalReset() throws Exception {
        GlobalResetScenarios.edtRemainsResponsiveDuringGlobalReset();
    }
    @Test void rapidResetStartStopCyclesPreserveActors() throws Exception {
        GlobalResetScenarios.rapidResetStartStopCyclesPreserveActors();
    }
    @Test void cancelledCallerFutureDoesNotCancelGlobalReset() throws Exception {
        GlobalResetScenarios.cancelledCallerFutureDoesNotCancelGlobalReset();
    }
    @Test void completedWorkDoesNotReappearAfterStart() throws Exception {
        GlobalResetScenarios.completedWorkDoesNotReappearAfterStart();
    }
    @Test void ledgerInvalidatesOpenTrackingButKeepsServedHistory() throws Exception {
        GlobalResetScenarios.ledgerInvalidatesOpenTrackingButKeepsServedHistory();
    }
    @Test void staleEventsCannotPoisonNewEpochOrSequence() throws Exception {
        GlobalResetScenarios.staleEventsCannotPoisonNewEpochOrSequence();
    }
    @Test void staleAdmissionDoesNotReserveFreshIdentity() throws Exception {
        GlobalResetScenarios.staleAdmissionDoesNotReserveFreshIdentity();
    }
    @Test void managerResetWakesStopAndCancelsInbox() throws Exception {
        GlobalResetScenarios.managerResetWakesStopAndCancelsInbox();
    }
    @Test void managerResetWaitsForInFlightDispatch() throws Exception {
        GlobalResetScenarios.managerResetWaitsForInFlightDispatch();
    }
    @Test void managerIgnoresBufferedOldCompletions() throws Exception {
        GlobalResetScenarios.managerIgnoresBufferedOldCompletions();
    }
    @Test void invalidManagerResetDoesNotKillActor() throws Exception {
        GlobalResetScenarios.invalidManagerResetDoesNotKillActor();
    }
    @Test void replayedManagerResetDoesNotClearNewWork() throws Exception {
        GlobalResetScenarios.replayedManagerResetDoesNotClearNewWork();
    }
    @Test void closedManagerRejectsReset() throws Exception {
        GlobalResetScenarios.closedManagerRejectsReset();
    }
    @Test void oldManagerRequestsCannotReappearAfterResume() throws Exception {
        GlobalResetScenarios.oldManagerRequestsCannotReappearAfterResume();
    }
    @Test void currentSnapshotDoesNotAuthorizeOldCommandEvent() throws Exception {
        GlobalResetScenarios.currentSnapshotDoesNotAuthorizeOldCommandEvent();
    }
}
