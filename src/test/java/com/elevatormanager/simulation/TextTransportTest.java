package com.elevatormanager.simulation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Los mismos escenarios de transporte real bajo Maven/JUnit. */
@Timeout(60)
class TextTransportTest {
    @Test void rawDestinationIsParsedOnActor() throws Exception {
        TextTransportScenarios.rawDestinationIsParsedOnActor();
    }
    @Test void rawPickupUsesTransmittedDirectionAndUuid() throws Exception {
        TextTransportScenarios.rawPickupUsesTransmittedDirectionAndUuid();
    }
    @Test void changedTextChangesPhysicalDestination() throws Exception {
        TextTransportScenarios.changedTextChangesPhysicalDestination();
    }
    @Test void compatibilitySubmitReconstructsDistinctObjects() throws Exception {
        TextTransportScenarios.compatibilitySubmitReconstructsDistinctObjects();
    }
    @Test void explicitTimestampContextSurvives() throws Exception {
        TextTransportScenarios.explicitTimestampContextSurvives();
    }
    @Test void defaultTimestampIsStableNotWallClock() throws Exception {
        TextTransportScenarios.defaultTimestampIsStableNotWallClock();
    }
    @Test void ackPrecedesPhysicalCompletion() throws Exception {
        TextTransportScenarios.ackPrecedesPhysicalCompletion();
    }
    @Test void moveArrivalAndStopAreTextualEvents() throws Exception {
        TextTransportScenarios.moveArrivalAndStopAreTextualEvents();
    }
    @Test void directionChangesAreReportedInText() throws Exception {
        TextTransportScenarios.directionChangesAreReportedInText();
    }
    @Test void malformedCommandDoesNotMutateAndWorkerRecovers() throws Exception {
        TextTransportScenarios.malformedCommandDoesNotMutateAndWorkerRecovers();
    }
    @Test void invalidFloorIsRejectedBeforeMotion() throws Exception {
        TextTransportScenarios.invalidFloorIsRejectedBeforeMotion();
    }
    @Test void impossibleDirectionIsRejected() throws Exception {
        TextTransportScenarios.impossibleDirectionIsRejected();
    }
    @Test void unknownConfiguredElevatorIsRejected() throws Exception {
        TextTransportScenarios.unknownConfiguredElevatorIsRejected();
    }
    @Test void wrongExistingTargetCannotMoveThisCabin() throws Exception {
        TextTransportScenarios.wrongExistingTargetCannotMoveThisCabin();
    }
    @Test void staleAndFutureServiceEpochsAreRejected() throws Exception {
        TextTransportScenarios.staleAndFutureServiceEpochsAreRejected();
    }
    @Test void badUuidDoesNotCreateRequest() throws Exception {
        TextTransportScenarios.badUuidDoesNotCreateRequest();
    }
    @Test void ackInjectedIntoCommandQueueIsNotExecuted() throws Exception {
        TextTransportScenarios.ackInjectedIntoCommandQueueIsNotExecuted();
    }
    @Test void extraTokensDoNotPartiallyExecute() throws Exception {
        TextTransportScenarios.extraTokensDoNotPartiallyExecute();
    }
    @Test void oversizedFrameIsRejectedOnActor() throws Exception {
        TextTransportScenarios.oversizedFrameIsRejectedOnActor();
    }
    @Test void framingNormalizationIsAppliedAtReceiver() throws Exception {
        TextTransportScenarios.framingNormalizationIsAppliedAtReceiver();
    }
    @Test void malformedBurstCannotKillActor() throws Exception {
        TextTransportScenarios.malformedBurstCannotKillActor();
    }
    @Test void conditionalSendChecksSnapshotBeforeEnqueue() throws Exception {
        TextTransportScenarios.conditionalSendChecksSnapshotBeforeEnqueue();
    }
    @Test void compatibleConditionalSendAlsoSerializes() throws Exception {
        TextTransportScenarios.compatibleConditionalSendAlsoSerializes();
    }
    @Test void enqueueIsNotAdmissionWhileStopped() throws Exception {
        TextTransportScenarios.enqueueIsNotAdmissionWhileStopped();
    }
    @Test void serviceFifoIsPreserved() throws Exception {
        TextTransportScenarios.serviceFifoIsPreserved();
    }
    @Test void aliasesHaveCanonicalAckAndEachOwnTerminal() throws Exception {
        TextTransportScenarios.aliasesHaveCanonicalAckAndEachOwnTerminal();
    }
    @Test void sameCommandReplayDoesNotDuplicatePhysicalService() throws Exception {
        TextTransportScenarios.sameCommandReplayDoesNotDuplicatePhysicalService();
    }
    @Test void servedReplayReturnsKnownTerminalWithoutAnotherStop() throws Exception {
        TextTransportScenarios.servedReplayReturnsKnownTerminalWithoutAnotherStop();
    }
    @Test void conflictingIdCannotReplaceDestination() throws Exception {
        TextTransportScenarios.conflictingIdCannotReplaceDestination();
    }
    @Test void resetQueueAlsoCarriesAndParsesText() throws Exception {
        TextTransportScenarios.resetQueueAlsoCarriesAndParsesText();
    }
    @Test void resetCancelsActiveCommandBeforeReturnCompletes() throws Exception {
        TextTransportScenarios.resetCancelsActiveCommandBeforeReturnCompletes();
    }
    @Test void resetDiscardsQueuedTextBeforeAdmission() throws Exception {
        TextTransportScenarios.resetDiscardsQueuedTextBeforeAdmission();
    }
    @Test void malformedResetFailsFutureWithoutAdoptingEpoch() throws Exception {
        TextTransportScenarios.malformedResetFailsFutureWithoutAdoptingEpoch();
    }
    @Test void serviceCannotEnterThroughResetPriority() throws Exception {
        TextTransportScenarios.serviceCannotEnterThroughResetPriority();
    }
    @Test void resetCannotEnterThroughOrdinaryServiceQueue() throws Exception {
        TextTransportScenarios.resetCannotEnterThroughOrdinaryServiceQueue();
    }
    @Test void controlsPendingT65AreRejectedWithoutFakeAck() throws Exception {
        TextTransportScenarios.controlsPendingT65AreRejectedWithoutFakeAck();
    }
    @Test void textualObserversDoNotStealNativeEvents() throws Exception {
        TextTransportScenarios.textualObserversDoNotStealNativeEvents();
    }
    @Test void unsubscribeStopsFutureFrames() throws Exception {
        TextTransportScenarios.unsubscribeStopsFutureFrames();
    }
    @Test void closeCancelsAcceptedPendingText() throws Exception {
        TextTransportScenarios.closeCancelsAcceptedPendingText();
    }
    @Test void closedOrNewWorkerRefusesToEnqueue() throws Exception {
        TextTransportScenarios.closedOrNewWorkerRefusesToEnqueue();
    }
    @Test void concurrentProducersKeepEveryCorrelation() throws Exception {
        TextTransportScenarios.concurrentProducersKeepEveryCorrelation();
    }
    @Test void mailboxProtectsEdtAndNegativeTimeout() throws Exception {
        TextTransportScenarios.mailboxProtectsEdtAndNegativeTimeout();
    }
    @Test void managerDispatchUsesTextAndStillConfirmsService() throws Exception {
        TextTransportScenarios.managerDispatchUsesTextAndStillConfirmsService();
    }
    @Test void controllerDestinationsAndResetUseText() throws Exception {
        TextTransportScenarios.controllerDestinationsAndResetUseText();
    }
    @Test void pauseStopAndResumeRetainTextualWork() throws Exception {
        TextTransportScenarios.pauseStopAndResumeRetainTextualWork();
    }
    @Test void syntaxCodecNeverGrantsAdmission() throws Exception {
        TextTransportScenarios.syntaxCodecNeverGrantsAdmission();
    }
    @Test void closeTerminatesAcceptedResetInText() throws Exception {
        TextTransportScenarios.closeTerminatesAcceptedResetInText();
    }
    @Test void closeRejectsQueuedTextWithoutFakeAcceptance() throws Exception {
        TextTransportScenarios.closeRejectsQueuedTextWithoutFakeAcceptance();
    }
}
