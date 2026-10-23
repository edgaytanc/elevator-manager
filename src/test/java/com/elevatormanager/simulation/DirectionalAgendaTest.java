package com.elevatormanager.simulation;

import org.junit.jupiter.api.Test;

class DirectionalAgendaTest {
    @Test void nearestAcrossRequestTypes() throws Exception {
        DirectionalAgendaScenarios.nearestAcrossRequestTypes();
    }
    @Test void tieUsesAdmissionInsteadOfTimestampOrType() throws Exception {
        DirectionalAgendaScenarios.tieUsesAdmissionInsteadOfTimestampOrType();
    }
    @Test void admissionViewIsImmutableAndRemovesServedRequests() throws Exception {
        DirectionalAgendaScenarios.admissionViewIsImmutableAndRemovesServedRequests();
    }
    @Test void ascendingStopsAreSpatiallyOrdered() throws Exception {
        DirectionalAgendaScenarios.ascendingStopsAreSpatiallyOrdered();
    }
    @Test void descendingStopsAreSpatiallyOrdered() throws Exception {
        DirectionalAgendaScenarios.descendingStopsAreSpatiallyOrdered();
    }
    @Test void hallBehindDoesNotReverseCommitment() throws Exception {
        DirectionalAgendaScenarios.hallBehindDoesNotReverseCommitment();
    }
    @Test void compatibleHallIsCollectedOnTheWay() throws Exception {
        DirectionalAgendaScenarios.compatibleHallIsCollectedOnTheWay();
    }
    @Test void oppositeHallWaitsUntilReturn() throws Exception {
        DirectionalAgendaScenarios.oppositeHallWaitsUntilReturn();
    }
    @Test void internalExtendsMovingFrontierWithoutRestartingTime() throws Exception {
        DirectionalAgendaScenarios.internalExtendsMovingFrontierWithoutRestartingTime();
    }
    @Test void externalBeyondFrontierDoesNotExtendSweep() throws Exception {
        DirectionalAgendaScenarios.externalBeyondFrontierDoesNotExtendSweep();
    }
    @Test void initialFrontierIncludesExistingInternalsAhead() throws Exception {
        DirectionalAgendaScenarios.initialFrontierIncludesExistingInternalsAhead();
    }
    @Test void internalExtendsDuringDwellWithoutRestartingStop() throws Exception {
        DirectionalAgendaScenarios.internalExtendsDuringDwellWithoutRestartingStop();
    }
    @Test void requestAtFloorJustLeftIsDeferred() throws Exception {
        DirectionalAgendaScenarios.requestAtFloorJustLeftIsDeferred();
    }
    @Test void returnCommitsFarthestPendingFloorBehind() throws Exception {
        DirectionalAgendaScenarios.returnCommitsFarthestPendingFloorBehind();
    }
    @Test void idleDirectionDoesNotForceNextJourney() throws Exception {
        DirectionalAgendaScenarios.idleDirectionDoesNotForceNextJourney();
    }
    @Test void emptySingleFloorAndExtremeFloors() throws Exception {
        DirectionalAgendaScenarios.emptySingleFloorAndExtremeFloors();
    }
    @Test void duplicateDoesNotReorderOrChangeCommitment() throws Exception {
        DirectionalAgendaScenarios.duplicateDoesNotReorderOrChangeCommitment();
    }
    @Test void workerClassicRoute() throws Exception {
        DirectionalAgendaScenarios.workerClassicRoute();
    }
    @Test void workerExtendsBeforeReturning() throws Exception {
        DirectionalAgendaScenarios.workerExtendsBeforeReturning();
    }
    @Test void workerReturnStopsAreSpatiallyOrdered() throws Exception {
        DirectionalAgendaScenarios.workerReturnStopsAreSpatiallyOrdered();
    }
}
