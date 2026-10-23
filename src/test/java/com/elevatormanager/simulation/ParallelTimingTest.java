package com.elevatormanager.simulation;

import org.junit.jupiter.api.Test;

class ParallelTimingTest {
    @Test void simultaneousJourneysOverlap() throws Exception {
        ParallelTimingScenarios.simultaneousJourneysOverlap();
    }
    @Test void oppositeJourneysOverlap() throws Exception {
        ParallelTimingScenarios.oppositeJourneysOverlap();
    }
    @Test void anotherCabinMovesDuringDwell() throws Exception {
        ParallelTimingScenarios.anotherCabinMovesDuringDwell();
    }
    @Test void shortJourneyFinishesDuringLongJourney() throws Exception {
        ParallelTimingScenarios.shortJourneyFinishesDuringLongJourney();
    }
    @Test void intermediateStopAddsItsFullDuration() throws Exception {
        ParallelTimingScenarios.intermediateStopAddsItsFullDuration();
    }
    @Test void mailboxWakeupsDoNotShortenOrRestartLeg() throws Exception {
        ParallelTimingScenarios.mailboxWakeupsDoNotShortenOrRestartLeg();
    }
    @Test void closingOneCabinDoesNotStopTheOther() throws Exception {
        ParallelTimingScenarios.closingOneCabinDoesNotStopTheOther();
    }
    @Test void singleFloorStopsOverlapWithoutMovement() throws Exception {
        ParallelTimingScenarios.singleFloorStopsOverlapWithoutMovement();
    }
}
