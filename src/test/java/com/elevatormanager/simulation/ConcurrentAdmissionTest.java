package com.elevatormanager.simulation;

import org.junit.jupiter.api.Test;

class ConcurrentAdmissionTest {
    @Test void firstAdmissionIsCanonical() throws Exception {
        ConcurrentAdmissionScenarios.firstAdmissionIsCanonical();
    }
    @Test void pendingReplayDoesNotMutateState() throws Exception {
        ConcurrentAdmissionScenarios.pendingReplayDoesNotMutateState();
    }
    @Test void servedReplayDoesNotReinsert() throws Exception {
        ConcurrentAdmissionScenarios.servedReplayDoesNotReinsert();
    }
    @Test void commandIdConflictPreservesOriginal() throws Exception {
        ConcurrentAdmissionScenarios.commandIdConflictPreservesOriginal();
    }
    @Test void commandReplayPreservesCanonicalMetadata() throws Exception {
        ConcurrentAdmissionScenarios.commandReplayPreservesCanonicalMetadata();
    }
    @Test void sameUuidWithNewCommandIsDuplicate() throws Exception {
        ConcurrentAdmissionScenarios.sameUuidWithNewCommandIsDuplicate();
    }
    @Test void uuidContentCannotChange() throws Exception {
        ConcurrentAdmissionScenarios.uuidContentCannotChange();
    }
    @Test void uuidTypeCannotChange() throws Exception {
        ConcurrentAdmissionScenarios.uuidTypeCannotChange();
    }
    @Test void functionalDuplicateCreatesAlias() throws Exception {
        ConcurrentAdmissionScenarios.functionalDuplicateCreatesAlias();
    }
    @Test void cabinAndHallDirectionsAreDifferentKeys() throws Exception {
        ConcurrentAdmissionScenarios.cabinAndHallDirectionsAreDifferentKeys();
    }
    @Test void servedAliasDoesNotAttachToNewRequest() throws Exception {
        ConcurrentAdmissionScenarios.servedAliasDoesNotAttachToNewRequest();
    }
    @Test void freshIdentityCanRequestServedFloorAgain() throws Exception {
        ConcurrentAdmissionScenarios.freshIdentityCanRequestServedFloorAgain();
    }
    @Test void invalidRoutingEpochAndResetDoNotReserveId() throws Exception {
        ConcurrentAdmissionScenarios.invalidRoutingEpochAndResetDoNotReserveId();
    }
    @Test void rejectedIdentityDecisionCanBeReplayed() throws Exception {
        ConcurrentAdmissionScenarios.rejectedIdentityDecisionCanBeReplayed();
    }
    @Test void concurrentFunctionalBurstHasOneCanonical() throws Exception {
        ConcurrentAdmissionScenarios.concurrentFunctionalBurstHasOneCanonical();
    }
    @Test void concurrentExactRetriesExecuteAdmissionOnce() throws Exception {
        ConcurrentAdmissionScenarios.concurrentExactRetriesExecuteAdmissionOnce();
    }
    @Test void concurrentUniqueInsertionPublishesCoherentSnapshots() throws Exception {
        ConcurrentAdmissionScenarios.concurrentUniqueInsertionPublishesCoherentSnapshots();
    }
    @Test void concurrentInsertionDuringDwellDoesNotRestartStop() throws Exception {
        ConcurrentAdmissionScenarios.concurrentInsertionDuringDwellDoesNotRestartStop();
    }
    @Test void workerReplayAfterServiceAndFreshRequest() throws Exception {
        ConcurrentAdmissionScenarios.workerReplayAfterServiceAndFreshRequest();
    }
    @Test void commandIdsAreScopedToEachWorker() throws Exception {
        ConcurrentAdmissionScenarios.commandIdsAreScopedToEachWorker();
    }
    @Test void concurrentCloseAccountsForEveryEnqueuedMessage() throws Exception {
        ConcurrentAdmissionScenarios.concurrentCloseAccountsForEveryEnqueuedMessage();
    }
}
