package com.elevatormanager.model;

import org.junit.jupiter.api.Test;

/** JUnit ejecuta los mismos escenarios que el comprobador Java independiente. */
class ModelRulesTest {
    @Test
    void buildingBoundaryMatrix() {
        ModelRulesScenarios.buildingBoundaryMatrix();
    }

    @Test
    void requestsCommandsAgendasAndSnapshots() {
        ModelRulesScenarios.requestsCommandsAgendasAndSnapshots();
    }

    @Test
    void populatedStateSurvivesRejectedInputs() {
        ModelRulesScenarios.populatedStateSurvivesRejectedInputs();
    }

    @Test
    void commandConflictMatrix() {
        ModelRulesScenarios.commandConflictMatrix();
    }

    @Test
    void snapshotsCrossThreadsWithoutSharingMutableState() throws InterruptedException {
        ModelRulesScenarios.snapshotsCrossThreadsWithoutSharingMutableState();
    }

    @Test
    void numericBoundariesRemainRepresentable() {
        ModelRulesScenarios.numericBoundariesRemainRepresentable();
    }

    @Test
    void snapshotPendingRulesAndStationaryDirection() {
        ModelRulesScenarios.snapshotPendingRulesAndStationaryDirection();
    }
}
