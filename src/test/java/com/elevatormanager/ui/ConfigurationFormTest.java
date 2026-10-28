package com.elevatormanager.ui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Adaptadores JUnit de T47. */
@Timeout(30)
class ConfigurationFormTest {
    @Test void emptyFieldsRejectAll() throws Exception {
        ConfigurationFormScenarios.emptyFieldsRejectAll();
    }
    @Test void allErrorsAreShownTogether() throws Exception {
        ConfigurationFormScenarios.allErrorsAreShownTogether();
    }
    @Test void validValuesReachImmutableConfig() throws Exception {
        ConfigurationFormScenarios.validValuesReachImmutableConfig();
    }
    @Test void minimumValuesAccepted() throws Exception {
        ConfigurationFormScenarios.minimumValuesAccepted();
    }
    @Test void maximumValuesAcceptedWithoutAllocatingWorkers() throws Exception {
        ConfigurationFormScenarios.maximumValuesAcceptedWithoutAllocatingWorkers();
    }
    @Test void countOverflowRejectedAtCorrectField() throws Exception {
        ConfigurationFormScenarios.countOverflowRejectedAtCorrectField();
    }
    @Test void longOverflowRejected() throws Exception {
        ConfigurationFormScenarios.longOverflowRejected();
    }
    @Test void nanosecondOverflowRejected() throws Exception {
        ConfigurationFormScenarios.nanosecondOverflowRejected();
    }
    @Test void decimalsAndScientificNotationRejected() throws Exception {
        ConfigurationFormScenarios.decimalsAndScientificNotationRejected();
    }
    @Test void surroundingSpacesAccepted() throws Exception {
        ConfigurationFormScenarios.surroundingSpacesAccepted();
    }
    @Test void correctionClearsErrors() throws Exception {
        ConfigurationFormScenarios.correctionClearsErrors();
    }
    @Test void editingInvalidatesPreviousConfirmation() throws Exception {
        ConfigurationFormScenarios.editingInvalidatesPreviousConfirmation();
    }
    @Test void enterConfirmsFromEveryField() throws Exception {
        ConfigurationFormScenarios.enterConfirmsFromEveryField();
    }
    @Test void nullTextDiagnosticsAreImmutable() throws Exception {
        ConfigurationFormScenarios.nullTextDiagnosticsAreImmutable();
    }
    @Test void validationAgreesWithDomain() throws Exception {
        ConfigurationFormScenarios.validationAgreesWithDomain();
    }
    @Test void offEdtAccessRejected() throws Exception {
        ConfigurationFormScenarios.offEdtAccessRejected();
    }
}
