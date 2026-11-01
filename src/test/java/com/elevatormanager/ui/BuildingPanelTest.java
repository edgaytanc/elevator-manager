package com.elevatormanager.ui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Adaptadores JUnit T58. */
@Timeout(30)
class BuildingPanelTest {
    @Test void singleFloorIsCentered() throws Exception {
        BuildingPanelScenarios.singleFloorIsCentered();
    }
    @Test void floorsAndCabinsHaveOrderedCoordinates() throws Exception {
        BuildingPanelScenarios.floorsAndCabinsHaveOrderedCoordinates();
    }
    @Test void smallBuildingExpandsWithViewport() throws Exception {
        BuildingPanelScenarios.smallBuildingExpandsWithViewport();
    }
    @Test void largeBuildingScrollsToLastCabinAndGroundFloor() throws Exception {
        BuildingPanelScenarios.largeBuildingScrollsToLastCabinAndGroundFloor();
    }
    @Test void paintsEveryCabinAtItsOwnFloor() throws Exception {
        BuildingPanelScenarios.paintsEveryCabinAtItsOwnFloor();
    }
    @Test void clippedPaintingReachesDistantCoordinates() throws Exception {
        BuildingPanelScenarios.clippedPaintingReachesDistantCoordinates();
    }
    @Test void hugeCountsDoNotOverflowOrAllocateControls() throws Exception {
        BuildingPanelScenarios.hugeCountsDoNotOverflowOrAllocateControls();
    }
    @Test void foreignSessionIsRejected() throws Exception {
        BuildingPanelScenarios.foreignSessionIsRejected();
    }
    @Test void oldEpochIsRejectedAndRebindClearsView() throws Exception {
        BuildingPanelScenarios.oldEpochIsRejectedAndRebindClearsView();
    }
    @Test void paintingDoesNotAdvanceLogicalFloor() throws Exception {
        BuildingPanelScenarios.paintingDoesNotAdvanceLogicalFloor();
    }
    @Test void offEdtAccessIsRejected() throws Exception {
        BuildingPanelScenarios.offEdtAccessIsRejected();
    }
    @Test void systemControlsPublishRealModelPositions() throws Exception {
        BuildingPanelScenarios.systemControlsPublishRealModelPositions();
    }
}
