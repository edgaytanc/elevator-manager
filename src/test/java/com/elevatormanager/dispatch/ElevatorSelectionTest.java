package com.elevatormanager.dispatch;

import org.junit.jupiter.api.Test;

class ElevatorSelectionTest {
    @Test void smallerEtaWinsRegardlessOfId() throws Exception {
        ElevatorSelectionScenarios.smallerEtaWinsRegardlessOfId();
    }
    @Test void equalCostsUseLowestIdIndependentOfInputOrder() throws Exception {
        ElevatorSelectionScenarios.equalCostsUseLowestIdIndependentOfInputOrder();
    }
    @Test void nearbyBusyCabinLosesToFartherIdleCabin() throws Exception {
        ElevatorSelectionScenarios.nearbyBusyCabinLosesToFartherIdleCabin();
    }
    @Test void lifecycleExcludesEvenCheapCabins() throws Exception {
        ElevatorSelectionScenarios.lifecycleExcludesEvenCheapCabins();
    }
    @Test void otherEpochAndConfigurationAreExcluded() throws Exception {
        ElevatorSelectionScenarios.otherEpochAndConfigurationAreExcluded();
    }
    @Test void heldResetAndTerminatedPhasesAreExcluded() throws Exception {
        ElevatorSelectionScenarios.heldResetAndTerminatedPhasesAreExcluded();
    }
    @Test void costsBeyondLongAreComparedExactly() throws Exception {
        ElevatorSelectionScenarios.costsBeyondLongAreComparedExactly();
    }
    @Test void commonObservationTimeAgesActivePhases() throws Exception {
        ElevatorSelectionScenarios.commonObservationTimeAgesActivePhases();
    }
    @Test void emptyRankingInvalidCallsAndDuplicateIds() throws Exception {
        ElevatorSelectionScenarios.emptyRankingInvalidCallsAndDuplicateIds();
    }
    @Test void rankingAndSourceAreImmutable() throws Exception {
        ElevatorSelectionScenarios.rankingAndSourceAreImmutable();
    }
    @Test void conditionalSubmissionRejectsStaleCapture() throws Exception {
        ElevatorSelectionScenarios.conditionalSubmissionRejectsStaleCapture();
    }
    @Test void conditionalSubmissionEnqueuesOnceAndDetectsClosure() throws Exception {
        ElevatorSelectionScenarios.conditionalSubmissionEnqueuesOnceAndDetectsClosure();
    }
    @Test void managerActuallyDispatchesToLowerEta() throws Exception {
        ElevatorSelectionScenarios.managerActuallyDispatchesToLowerEta();
    }
    @Test void managerTieUsesIdWithReversedList() throws Exception {
        ElevatorSelectionScenarios.managerTieUsesIdWithReversedList();
    }
    @Test void winnerClosingBeforeSendTriggersReselection() throws Exception {
        ElevatorSelectionScenarios.winnerClosingBeforeSendTriggersReselection();
    }
    @Test void changedWinnerIsReestimatedRatherThanJustSkipped() throws Exception {
        ElevatorSelectionScenarios.changedWinnerIsReestimatedRatherThanJustSkipped();
    }
    @Test void previouslyLosingCabinIsRevalidatedToo() throws Exception {
        ElevatorSelectionScenarios.previouslyLosingCabinIsRevalidatedToo();
    }
    @Test void allCabinsClosingBeforeSendRejectsWithoutDispatch() throws Exception {
        ElevatorSelectionScenarios.allCabinsClosingBeforeSendRejectsWithoutDispatch();
    }
}
