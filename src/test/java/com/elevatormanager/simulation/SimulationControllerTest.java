package com.elevatormanager.simulation;

import org.junit.jupiter.api.Test;

class SimulationControllerTest {
    @Test void initialState() throws Exception {
        SimulationControllerScenarios.initialState();
    }
    @Test void repeatedStart() throws Exception {
        SimulationControllerScenarios.repeatedStart();
    }
    @Test void concurrentStart() throws Exception {
        SimulationControllerScenarios.concurrentStart();
    }
    @Test void rejectsBeforeStart() throws Exception {
        SimulationControllerScenarios.rejectsBeforeStart();
    }
    @Test void routesPolymorphically() throws Exception {
        SimulationControllerScenarios.routesPolymorphically();
    }
    @Test void validatesContext() throws Exception {
        SimulationControllerScenarios.validatesContext();
    }
    @Test void repeatedStartDoesNotReplay() throws Exception {
        SimulationControllerScenarios.repeatedStartDoesNotReplay();
    }
    @Test void sharedCommandSequence() throws Exception {
        SimulationControllerScenarios.sharedCommandSequence();
    }
    @Test void duplicatesRemainCanonical() throws Exception {
        SimulationControllerScenarios.duplicatesRemainCanonical();
    }
    @Test void oneFloor() throws Exception {
        SimulationControllerScenarios.oneFloor();
    }
    @Test void edtDoesNotWait() throws Exception {
        SimulationControllerScenarios.edtDoesNotWait();
    }
    @Test void closeAndRejectRestart() throws Exception {
        SimulationControllerScenarios.closeAndRejectRestart();
    }
    @Test void closeBeforeStart() throws Exception {
        SimulationControllerScenarios.closeBeforeStart();
    }
    @Test void validatesArgumentsAndTimeouts() throws Exception {
        SimulationControllerScenarios.validatesArgumentsAndTimeouts();
    }
}
