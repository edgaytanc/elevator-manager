package com.elevatormanager.dispatch;

import org.junit.jupiter.api.Test;

class DirectionPickupTest {
    @Test void ascendingCompatibleCallPrecedesDestination() throws Exception {
        DirectionPickupScenarios.ascendingCompatibleCallPrecedesDestination();
    }
    @Test void descendingCompatibleCallPrecedesDestination() throws Exception {
        DirectionPickupScenarios.descendingCompatibleCallPrecedesDestination();
    }
    @Test void oppositeAheadWaitsForAscendingBoundary() throws Exception {
        DirectionPickupScenarios.oppositeAheadWaitsForAscendingBoundary();
    }
    @Test void oppositeAheadWaitsForDescendingBoundary() throws Exception {
        DirectionPickupScenarios.oppositeAheadWaitsForDescendingBoundary();
    }
    @Test void callBehindDoesNotReverseAscendingCommitment() throws Exception {
        DirectionPickupScenarios.callBehindDoesNotReverseAscendingCommitment();
    }
    @Test void callBehindDoesNotReverseDescendingCommitment() throws Exception {
        DirectionPickupScenarios.callBehindDoesNotReverseDescendingCommitment();
    }
    @Test void oppositeAtOpenUpStopWaitsForReturn() throws Exception {
        DirectionPickupScenarios.oppositeAtOpenUpStopWaitsForReturn();
    }
    @Test void oppositeAtOpenDownStopWaitsForReturn() throws Exception {
        DirectionPickupScenarios.oppositeAtOpenDownStopWaitsForReturn();
    }
    @Test void compatibleCallJoinsOpenStopWithoutRestart() throws Exception {
        DirectionPickupScenarios.compatibleCallJoinsOpenStopWithoutRestart();
    }
    @Test void departedFloorIsNotServedDuringMovement() throws Exception {
        DirectionPickupScenarios.departedFloorIsNotServedDuringMovement();
    }
    @Test void bothDirectionsAtOneFloorHaveSeparateServices() throws Exception {
        DirectionPickupScenarios.bothDirectionsAtOneFloorHaveSeparateServices();
    }
    @Test void externalBeyondFrontierDoesNotSkipReturn() throws Exception {
        DirectionPickupScenarios.externalBeyondFrontierDoesNotSkipReturn();
    }
    @Test void internalExtensionFinishesBeforeOppositePickup() throws Exception {
        DirectionPickupScenarios.internalExtensionFinishesBeforeOppositePickup();
    }
    @Test void oppositeAtTopChangesDirectionWithoutInventingLeg() throws Exception {
        DirectionPickupScenarios.oppositeAtTopChangesDirectionWithoutInventingLeg();
    }
    @Test void idleCabinCanServeOppositeHistoricalDirection() throws Exception {
        DirectionPickupScenarios.idleCabinCanServeOppositeHistoricalDirection();
    }
    @Test void managerCollectsCompatibleBeforeCommittedDestination() throws Exception {
        DirectionPickupScenarios.managerCollectsCompatibleBeforeCommittedDestination();
    }
    @Test void managerDefersOppositeUntilReturn() throws Exception {
        DirectionPickupScenarios.managerDefersOppositeUntilReturn();
    }
    @Test void managerSeparatesBothDirectionsAtSameFloor() throws Exception {
        DirectionPickupScenarios.managerSeparatesBothDirectionsAtSameFloor();
    }
    @Test void managerCollectsDescendingBeforeCommittedDestination() throws Exception {
        DirectionPickupScenarios.managerCollectsDescendingBeforeCommittedDestination();
    }
    @Test void managerDoesNotReverseForCallBehind() throws Exception {
        DirectionPickupScenarios.managerDoesNotReverseForCallBehind();
    }
}
