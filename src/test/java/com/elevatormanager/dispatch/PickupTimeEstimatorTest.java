package com.elevatormanager.dispatch;

import org.junit.jupiter.api.Test;

class PickupTimeEstimatorTest {
    @Test void idleAndCurrentFloor() throws Exception {
        PickupTimeEstimatorScenarios.idleAndCurrentFloor();
    }
    @Test void movingUsesRemainingLeg() throws Exception {
        PickupTimeEstimatorScenarios.movingUsesRemainingLeg();
    }
    @Test void compatibleIntermediateStopsAreChargedOnce() throws Exception {
        PickupTimeEstimatorScenarios.compatibleIntermediateStopsAreChargedOnce();
    }
    @Test void oppositeDirectionRequiresReturn() throws Exception {
        PickupTimeEstimatorScenarios.oppositeDirectionRequiresReturn();
    }
    @Test void departedFloorCannotBePickedImmediately() throws Exception {
        PickupTimeEstimatorScenarios.departedFloorCannotBePickedImmediately();
    }
    @Test void downSweepIsSymmetric() throws Exception {
        PickupTimeEstimatorScenarios.downSweepIsSymmetric();
    }
    @Test void joinsCompatibleOpenStop() throws Exception {
        PickupTimeEstimatorScenarios.joinsCompatibleOpenStop();
    }
    @Test void oppositeCallWaitsForCommittedDwellAndReturn() throws Exception {
        PickupTimeEstimatorScenarios.oppositeCallWaitsForCommittedDwellAndReturn();
    }
    @Test void completedSweepChangesTheProjectedRoute() throws Exception {
        PickupTimeEstimatorScenarios.completedSweepChangesTheProjectedRoute();
    }
    @Test void crossTypeAdmissionOrderBreaksTies() throws Exception {
        PickupTimeEstimatorScenarios.crossTypeAdmissionOrderBreaksTies();
    }
    @Test void beyondFrontierDoesNotExtendExistingSweep() throws Exception {
        PickupTimeEstimatorScenarios.beyondFrontierDoesNotExtendExistingSweep();
    }
    @Test void nearButOccupiedCanCostMore() throws Exception {
        PickupTimeEstimatorScenarios.nearButOccupiedCanCostMore();
    }
    @Test void functionalDuplicateDoesNotAddAStop() throws Exception {
        PickupTimeEstimatorScenarios.functionalDuplicateDoesNotAddAStop();
    }
    @Test void invalidCallsAndUuidConflictsAreExplicit() throws Exception {
        PickupTimeEstimatorScenarios.invalidCallsAndUuidConflictsAreExplicit();
    }
    @Test void unavailableStatesHaveNoCost() throws Exception {
        PickupTimeEstimatorScenarios.unavailableStatesHaveNoCost();
    }
    @Test void overduePhaseAndNanoTimeWrapAreHandled() throws Exception {
        PickupTimeEstimatorScenarios.overduePhaseAndNanoTimeWrapAreHandled();
    }
    @Test void hugeBuildingAndTimeDoNotOverflow() throws Exception {
        PickupTimeEstimatorScenarios.hugeBuildingAndTimeDoNotOverflow();
    }
    @Test void inputAndTraceAreImmutable() throws Exception {
        PickupTimeEstimatorScenarios.inputAndTraceAreImmutable();
    }
    @Test void publishedPlanningCaptureIsCoherent() throws Exception {
        PickupTimeEstimatorScenarios.publishedPlanningCaptureIsCoherent();
    }
    @Test void generatedRoutesMatchPhysicalModel() throws Exception {
        PickupTimeEstimatorScenarios.generatedRoutesMatchPhysicalModel();
    }
}
