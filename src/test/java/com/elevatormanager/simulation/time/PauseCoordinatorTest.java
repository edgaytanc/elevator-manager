package com.elevatormanager.simulation.time;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** T55: escenarios completos bajo JUnit. */
@Timeout(30)
class PauseCoordinatorTest {
    @Test void validatesParticipants() throws Exception {
        PauseCoordinatorScenarios.validatesParticipants();
    }
    @Test void barrierRequiresEveryActor() throws Exception {
        PauseCoordinatorScenarios.barrierRequiresEveryActor();
    }
    @Test void clockFreezesBeforeAcknowledgements() throws Exception {
        PauseCoordinatorScenarios.clockFreezesBeforeAcknowledgements();
    }
    @Test void callbackFinishesBeforeAcknowledgement() throws Exception {
        PauseCoordinatorScenarios.callbackFinishesBeforeAcknowledgement();
    }
    @Test void publicCancellationDoesNotCancelBarrier() throws Exception {
        PauseCoordinatorScenarios.publicCancellationDoesNotCancelBarrier();
    }
    @Test void repeatedPausePreservesGeneration() throws Exception {
        PauseCoordinatorScenarios.repeatedPausePreservesGeneration();
    }
    @Test void repeatedGenerationsCannotReuseOldAcks() throws Exception {
        PauseCoordinatorScenarios.repeatedGenerationsCannotReuseOldAcks();
    }
    @Test void closeCancelsPartialBarrierAndWakesActors() throws Exception {
        PauseCoordinatorScenarios.closeCancelsPartialBarrierAndWakesActors();
    }
    @Test void terminatedParticipantRejectsPause() throws Exception {
        PauseCoordinatorScenarios.terminatedParticipantRejectsPause();
    }
    @Test void failureCompletesBarrierWithoutWaitingForOthers() throws Exception {
        PauseCoordinatorScenarios.failureCompletesBarrierWithoutWaitingForOthers();
    }
    @Test void invalidParticipantIsRejected() throws Exception {
        PauseCoordinatorScenarios.invalidParticipantIsRejected();
    }
    @Test void activeCheckpointDoesNotPublishPause() throws Exception {
        PauseCoordinatorScenarios.activeCheckpointDoesNotPublishPause();
    }
}
