package com.elevatormanager.ui;

import com.elevatormanager.model.SimulationConfig;
import com.elevatormanager.model.SimulationConfig.Field;
import java.lang.reflect.InvocationTargetException;
import javax.swing.SwingUtilities;

/** Pruebas T47 con componentes Swing reales en EDT, sin ventana nativa. */
public final class ConfigurationFormScenarios {
    private ConfigurationFormScenarios() { }
    public static void emptyFieldsRejectAll() throws Exception {
        onEdt(() -> {
        var panel = new ConfigurationPanel();
        check(panel.validateConfiguration().isEmpty(), "Vacío no válido");
        for (var field : Field.values()) check(!panel.error(field).isBlank(), "Error obligatorio " + field);
        });
    }

    public static void allErrorsAreShownTogether() throws Exception {
        onEdt(() -> {
        var panel = filled("0", "-1", "abc", "0");
        check(panel.validateConfiguration().isEmpty(), "Sin configuración parcial");
        for (var field : Field.values()) check(!panel.error(field).isBlank(), "Todos los errores " + field);
        });
    }

    public static void validValuesReachImmutableConfig() throws Exception {
        onEdt(() -> {
        var panel = filled("3", "8", "1000", "500");
        panel.validateButton().doClick(0);
        check(panel.configuration().orElseThrow().equals(new SimulationConfig(3, 8, 1000, 500)), "Valores exactos");
        });
    }

    public static void minimumValuesAccepted() throws Exception {
        onEdt(() -> {
        var panel = filled("1", "1", "1", "1");
        check(panel.validateConfiguration().orElseThrow().equals(new SimulationConfig(1, 1, 1, 1)), "Un piso permitido");
        });
    }

    public static void maximumValuesAcceptedWithoutAllocatingWorkers() throws Exception {
        onEdt(() -> {
        String maximum = Long.toString(SimulationConfig.MAX_TIME_MILLIS);
        var panel = filled(Integer.toString(Integer.MAX_VALUE), Integer.toString(Integer.MAX_VALUE), maximum, maximum);
        check(panel.validateConfiguration().orElseThrow().elevatorCount() == Integer.MAX_VALUE, "Solo valida, no crea actores");
        });
    }

    public static void countOverflowRejectedAtCorrectField() throws Exception {
        onEdt(() -> {
        var panel = filled("2147483648", "8", "1000", "500");
        check(panel.validateConfiguration().isEmpty() && !panel.error(Field.ELEVATORS).isBlank(), "Overflow N");
        check(panel.error(Field.FLOORS).isBlank(), "P válido sin error");
        });
    }

    public static void longOverflowRejected() throws Exception {
        onEdt(() -> {
        var panel = filled("2", "8", "9223372036854775808", "500");
        check(panel.validateConfiguration().isEmpty() && !panel.error(Field.MOVEMENT).isBlank(), "Overflow long");
        });
    }

    public static void nanosecondOverflowRejected() throws Exception {
        onEdt(() -> {
        var panel = filled("2", "8", "1000", Long.toString(SimulationConfig.MAX_TIME_MILLIS + 1));
        check(panel.validateConfiguration().isEmpty() && panel.error(Field.DWELL).contains("ms"), "Límite seguro nanos");
        });
    }

    public static void decimalsAndScientificNotationRejected() throws Exception {
        onEdt(() -> {
        var panel = filled("2.5", "1e2", "10.0", "5e3");
        check(panel.validateConfiguration().isEmpty(), "Solo enteros");
        for (var field : Field.values()) check(!panel.error(field).isBlank(), "Decimal rechazado " + field);
        });
    }

    public static void surroundingSpacesAccepted() throws Exception {
        onEdt(() -> {
        var panel = filled(" 2 ", " 8 ", " 1000 ", " 500 ");
        check(panel.validateConfiguration().orElseThrow().equals(new SimulationConfig(2, 8, 1000, 500)), "Strip T16");
        });
    }

    public static void correctionClearsErrors() throws Exception {
        onEdt(() -> {
        var panel = filled("0", "8", "1000", "500");
        panel.validateConfiguration();
        panel.input(Field.ELEVATORS).setText("2");
        check(panel.validateConfiguration().isPresent() && panel.error(Field.ELEVATORS).isBlank(), "Recuperación");
        });
    }

    public static void editingInvalidatesPreviousConfirmation() throws Exception {
        onEdt(() -> {
        var panel = filled("2", "8", "1000", "500");
        panel.validateConfiguration();
        panel.input(Field.FLOORS).setText("0");
        check(panel.configuration().isEmpty(), "No reutiliza configuración obsoleta");
        check(panel.validateConfiguration().isEmpty(), "Revalida antes de confirmar");
        });
    }

    public static void enterConfirmsFromEveryField() throws Exception {
        onEdt(() -> {
        for (var field : Field.values()) {
            var panel = filled("2", "8", "1000", "500");
            panel.input(field).postActionEvent();
            check(panel.configuration().isPresent(), "Enter " + field);
        }
        });
    }

    public static void nullTextDiagnosticsAreImmutable() throws Exception {
        onEdt(() -> {
        var errors = SimulationConfig.textErrors(null, null, null, null);
        check(errors.size() == 4, "Cuatro diagnósticos");
        try { errors.clear(); throw new AssertionError("Mapa mutable"); }
        catch (UnsupportedOperationException expected) { /* Contrato inmutable. */ }
        });
    }

    public static void validationAgreesWithDomain() throws Exception {
        onEdt(() -> {
        String[] samples = {"", "0", "-1", "1", "2", "2147483648", "9223372036854775808", " 7 ", "1.5", "1e3"};
        for (var field : Field.values()) for (String sample : samples) {
            String[] values = {"2", "8", "1000", "500"};
            values[field.ordinal()] = sample;
            boolean valid;
            try { SimulationConfig.fromText(values[0], values[1], values[2], values[3]); valid = true; }
            catch (IllegalArgumentException expected) { valid = false; }
            var errors = SimulationConfig.textErrors(values[0], values[1], values[2], values[3]);
            check(errors.isEmpty() == valid, "Mismas reglas " + field + "=" + sample);
        }
        });
    }

    public static void offEdtAccessRejected() throws Exception {
        try { new ConfigurationPanel(); throw new AssertionError("Construcción fuera de EDT"); }
        catch (IllegalStateException expected) { /* Contrato EDT. */ }
        var reference = new java.util.concurrent.atomic.AtomicReference<ConfigurationPanel>();
        onEdt(() -> reference.set(filled("2", "8", "1000", "500")));
        try { reference.get().validateConfiguration(); throw new AssertionError("Validación fuera de EDT"); }
        catch (IllegalStateException expected) { /* Contrato EDT. */ }
        try { reference.get().configuration(); throw new AssertionError("Lectura fuera de EDT"); }
        catch (IllegalStateException expected) { /* Contrato EDT. */ }
    }

    private static ConfigurationPanel filled(String elevators, String floors, String movement, String dwell) {
        var panel = new ConfigurationPanel();
        String[] values = {elevators, floors, movement, dwell};
        for (var field : Field.values()) panel.input(field).setText(values[field.ordinal()]);
        return panel;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void onEdt(Runnable action) throws Exception {
        try { SwingUtilities.invokeAndWait(action); }
        catch (InvocationTargetException exception) {
            if (exception.getCause() instanceof Error error) throw error;
            if (exception.getCause() instanceof Exception cause) throw cause;
            throw exception;
        }
    }

    public static void main(String[] args) throws Exception {
        emptyFieldsRejectAll();
        allErrorsAreShownTogether();
        validValuesReachImmutableConfig();
        minimumValuesAccepted();
        maximumValuesAcceptedWithoutAllocatingWorkers();
        countOverflowRejectedAtCorrectField();
        longOverflowRejected();
        nanosecondOverflowRejected();
        decimalsAndScientificNotationRejected();
        surroundingSpacesAccepted();
        correctionClearsErrors();
        editingInvalidatesPreviousConfirmation();
        enterConfirmsFromEveryField();
        nullTextDiagnosticsAreImmutable();
        validationAgreesWithDomain();
        offEdtAccessRejected();
        System.out.println("T47: 16 escenarios satisfactorios.");
    }
}
