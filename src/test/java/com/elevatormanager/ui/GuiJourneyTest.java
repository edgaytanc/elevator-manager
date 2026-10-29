package com.elevatormanager.ui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** T53: acciones gráficas integradas y motor real. */
@Timeout(60)
class GuiJourneyTest {
    @Test void completeJourneyWithTwoCabins() throws Exception {
        GuiJourneyScenarios.completeJourneyWithTwoCabins();
    }
    @Test void completeJourneyWithOneCabin() throws Exception {
        GuiJourneyScenarios.completeJourneyWithOneCabin();
    }
    @Test void invalidConfigurationCanBeCorrectedThroughForm() throws Exception {
        GuiJourneyScenarios.invalidConfigurationCanBeCorrectedThroughForm();
    }
    @Test void singleFloorHasNoExternalDirectionButServesCabin() throws Exception {
        GuiJourneyScenarios.singleFloorHasNoExternalDirectionButServesCabin();
    }
    @Test void cancelledInvalidAndMissingImportsPreservePreparedBatch() throws Exception {
        GuiJourneyScenarios.cancelledInvalidAndMissingImportsPreservePreparedBatch();
    }
    @Test void importAndRequestsRespectGlobalState() throws Exception {
        GuiJourneyScenarios.importAndRequestsRespectGlobalState();
    }
    @Test void resetCancelsWorkAndClearsLightsAcrossCabins() throws Exception {
        GuiJourneyScenarios.resetCancelsWorkAndClearsLightsAcrossCabins();
    }
    @Test void navigationAndPagingPreservePendingHiddenCabin() throws Exception {
        GuiJourneyScenarios.navigationAndPagingPreservePendingHiddenCabin();
    }
    @Test void clearViewerDoesNotEraseSessionFiles() throws Exception {
        GuiJourneyScenarios.clearViewerDoesNotEraseSessionFiles();
    }
    @Test void closeButtonDuringMovementTerminatesWholeSession() throws Exception {
        GuiJourneyScenarios.closeButtonDuringMovementTerminatesWholeSession();
    }
}
