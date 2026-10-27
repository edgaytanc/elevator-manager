package com.elevatormanager.dispatch;

import org.junit.jupiter.api.Test;

class ScalabilityTest {
    @Test void referenceLoadsAreStableUniqueAndImmutable() throws Exception {
        ScalabilityScenarios.referenceLoadsAreStableUniqueAndImmutable();
    }
    @Test void optionsRejectThirdCountBelowThree() throws Exception {
        ScalabilityScenarios.optionsRejectThirdCountBelowThree();
    }
    @Test void optionsRejectBadTimingsRepetitionsAndDeadline() throws Exception {
        ScalabilityScenarios.optionsRejectBadTimingsRepetitionsAndDeadline();
    }
    @Test void statisticsUsePerCallSamples() throws Exception {
        ScalabilityScenarios.statisticsUsePerCallSamples();
    }
    @Test void percentileUsesNearestRank() throws Exception {
        ScalabilityScenarios.percentileUsesNearestRank();
    }
    @Test void timestampMetricsExcludeConsumerDelay() throws Exception {
        ScalabilityScenarios.timestampMetricsExcludeConsumerDelay();
    }
    @Test void metricsRejectDuplicateCompletion() throws Exception {
        ScalabilityScenarios.metricsRejectDuplicateCompletion();
    }
    @Test void metricsRejectIncompatibleDirection() throws Exception {
        ScalabilityScenarios.metricsRejectIncompatibleDirection();
    }
    @Test void singleCabinCompletesSameLoads() throws Exception {
        ScalabilityScenarios.singleCabinCompletesSameLoads();
    }
    @Test void twoCabinsCompleteSameLoads() throws Exception {
        ScalabilityScenarios.twoCabinsCompleteSameLoads();
    }
    @Test void configurableCabinCountCompletesSameLoads() throws Exception {
        ScalabilityScenarios.configurableCabinCountCompletesSameLoads();
    }
    @Test void comparisonReportContainsAllConfigurations() throws Exception {
        ScalabilityScenarios.comparisonReportContainsAllConfigurations();
    }
    @Test void workloadRejectsAliasesAsIndependentSamples() throws Exception {
        ScalabilityScenarios.workloadRejectsAliasesAsIndependentSamples();
    }
}
