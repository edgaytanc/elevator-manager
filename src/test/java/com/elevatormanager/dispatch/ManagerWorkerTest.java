package com.elevatormanager.dispatch;

import org.junit.jupiter.api.Test;

class ManagerWorkerTest {
    @Test void singleCabinDispatchesAndServes() throws Exception {
        ManagerWorkerScenarios.singleCabinDispatchesAndServes();
    }
    @Test void dispatchSupportsNWithCostSelection() throws Exception {
        ManagerWorkerScenarios.dispatchSupportsNWithCostSelection();
    }
    @Test void lifecycleIsOneShot() throws Exception {
        ManagerWorkerScenarios.lifecycleIsOneShot();
    }
    @Test void validatesOnManagerThread() throws Exception {
        ManagerWorkerScenarios.validatesOnManagerThread();
    }
    @Test void noAvailableCabinIsExplicit() throws Exception {
        ManagerWorkerScenarios.noAvailableCabinIsExplicit();
    }
    @Test void closedCabinIsSkipped() throws Exception {
        ManagerWorkerScenarios.closedCabinIsSkipped();
    }
    @Test void edtRemainsFreeDuringDispatch() throws Exception {
        ManagerWorkerScenarios.edtRemainsFreeDuringDispatch();
    }
    @Test void concurrentProducersLoseNoRequests() throws Exception {
        ManagerWorkerScenarios.concurrentProducersLoseNoRequests();
    }
    @Test void closeAccountsForQueuedRequests() throws Exception {
        ManagerWorkerScenarios.closeAccountsForQueuedRequests();
    }
    @Test void sharedCommandSequenceAvoidsCollisions() throws Exception {
        ManagerWorkerScenarios.sharedCommandSequenceAvoidsCollisions();
    }
    @Test void failureIsVisibleAndAccountsForInput() throws Exception {
        ManagerWorkerScenarios.failureIsVisibleAndAccountsForInput();
    }
    @Test void constructorAndNewLifecycleAreValidated() throws Exception {
        ManagerWorkerScenarios.constructorAndNewLifecycleAreValidated();
    }
}
