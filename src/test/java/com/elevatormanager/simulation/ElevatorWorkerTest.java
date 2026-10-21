package com.elevatormanager.simulation;

import org.junit.jupiter.api.Test;

class ElevatorWorkerTest {
    @Test void initialState() throws Exception { ElevatorWorkerScenarios.initialState(); }
    @Test void blockingAndWakeup() throws Exception { ElevatorWorkerScenarios.blockingAndWakeup(); }
    @Test void independentWorkers() throws Exception { ElevatorWorkerScenarios.independentWorkers(); }
    @Test void rejectionDoesNotKillWorker() throws Exception {
        ElevatorWorkerScenarios.rejectionDoesNotKillWorker();
    }
    @Test void immutablePublication() throws Exception {
        ElevatorWorkerScenarios.immutablePublication();
    }
    @Test void closeAccountsForQueuedCommands() throws Exception {
        ElevatorWorkerScenarios.closeAccountsForQueuedCommands();
    }
    @Test void singleStartAndCloseBeforeStart() throws Exception {
        ElevatorWorkerScenarios.singleStartAndCloseBeforeStart();
    }
}
