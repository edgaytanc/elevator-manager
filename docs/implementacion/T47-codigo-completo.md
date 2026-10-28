# T47 — Código completo

Archivos Java nuevos o modificados, sin fragmentos ni omisiones. El ZIP contiene también todos los fuentes previos.

## src/main/java/com/elevatormanager/model/SimulationConfig.java

```java
package com.elevatormanager.model;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/**
 * Configuración inmutable compartida por todos los trabajadores de una sesión.
 * Los tiempos se expresan en milisegundos y admiten conversión exacta a nanos.
 */
public record SimulationConfig(
        int elevatorCount,
        int floorCount,
        long movementTimeMillis,
        long dwellTimeMillis) {

    public static final long MAX_TIME_MILLIS = Long.MAX_VALUE / 1_000_000L;

    public SimulationConfig {
        requirePositive(elevatorCount, "Cantidad de elevadores");
        requirePositive(floorCount, "Cantidad de pisos");
        requireTime(movementTimeMillis, "Tiempo de movimiento");
        requireTime(dwellTimeMillis, "Tiempo de parada");
    }

    /** Convierte entradas de formulario sin crear una configuración parcial. */
    public static SimulationConfig fromText(
            String elevatorCount, String floorCount,
            String movementTimeMillis, String dwellTimeMillis) {
        return new SimulationConfig(
                parseCount(elevatorCount, "Cantidad de elevadores"),
                parseCount(floorCount, "Cantidad de pisos"),
                parseLong(movementTimeMillis, "Tiempo de movimiento"),
                parseLong(dwellTimeMillis, "Tiempo de parada"));
    }

    /** Campos estables para asociar cada diagnóstico a su control gráfico. */
    public enum Field { ELEVATORS, FLOORS, MOVEMENT, DWELL }

    /** Valida todos los campos con las mismas reglas que fromText, sin crear sesión. */
    public static Map<Field, String> textErrors(String elevators, String floors,
            String movement, String dwell) {
        var errors = new EnumMap<Field, String>(Field.class);
        collect(errors, Field.ELEVATORS, () -> parseCount(elevators, "Cantidad de elevadores"));
        collect(errors, Field.FLOORS, () -> parseCount(floors, "Cantidad de pisos"));
        collect(errors, Field.MOVEMENT, () -> validateTimeText(movement, "Tiempo de movimiento"));
        collect(errors, Field.DWELL, () -> validateTimeText(dwell, "Tiempo de parada"));
        return Collections.unmodifiableMap(errors);
    }

    private static void validateTimeText(String text, String field) {
        requireTime(parseLong(text, field), field);
    }

    private static void collect(Map<Field, String> errors, Field field, Runnable check) {
        try {
            check.run();
        } catch (IllegalArgumentException exception) {
            errors.put(field, exception.getMessage());
        }
    }

    private static void requirePositive(int value, String field) {
        if (value < 1) {
            throw new IllegalArgumentException(field + " debe ser mayor que cero.");
        }
    }

    private static void requireTime(long value, String field) {
        if (value < 1 || value > MAX_TIME_MILLIS) {
            throw new IllegalArgumentException(
                    field + " debe estar entre 1 y " + MAX_TIME_MILLIS + " ms.");
        }
    }

    private static int parseCount(String text, String field) {
        long value = parseLong(text, field);
        if (value < 1 || value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    field + " debe estar entre 1 y " + Integer.MAX_VALUE + ".");
        }
        return (int) value;
    }

    private static long parseLong(String text, String field) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException(field + " es obligatorio.");
        }
        try {
            return Long.parseLong(text.strip());
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(
                    field + " debe ser un entero decimal dentro del rango de long.",
                    exception);
        }
    }
}
```

## src/main/java/com/elevatormanager/ui/ConfigurationPanel.java

```java
package com.elevatormanager.ui;

import com.elevatormanager.model.SimulationConfig;
import com.elevatormanager.model.SimulationConfig.Field;
import java.awt.Color;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.EnumMap;
import java.util.Objects;
import java.util.Optional;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

/** Formulario T47: solo publica valores íntegros, siempre desde el EDT. */
public final class ConfigurationPanel extends JPanel {
    private static final long serialVersionUID = 1L;
    private final EnumMap<Field, JTextField> inputs = new EnumMap<>(Field.class);
    private final EnumMap<Field, JLabel> errors = new EnumMap<>(Field.class);
    private final JButton validateButton = new JButton("Validar configuración");
    private final JLabel status = new JLabel("Ingresa los parámetros de la simulación.");
    private Optional<SimulationConfig> configuration = Optional.empty();

    public ConfigurationPanel() {
        requireEdt();
        setLayout(new GridBagLayout());
        setBorder(BorderFactory.createTitledBorder("Configuración de la simulación"));
        addField(Field.ELEVATORS, "Cantidad de elevadores (N)", 0);
        addField(Field.FLOORS, "Cantidad de pisos (P)", 1);
        addField(Field.MOVEMENT, "Movimiento entre pisos (ms)", 2);
        addField(Field.DWELL, "Espera en un piso (ms)", 3);
        var action = constraints(0, 8);
        action.gridwidth = 2;
        add(validateButton, action);
        var summary = constraints(0, 9);
        summary.gridwidth = 2;
        add(status, summary);
        validateButton.addActionListener(event -> validateConfiguration());
        DocumentListener edited = new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent event) { invalidateConfiguration(); }
            @Override public void removeUpdate(DocumentEvent event) { invalidateConfiguration(); }
            @Override public void changedUpdate(DocumentEvent event) { invalidateConfiguration(); }
        };
        inputs.values().forEach(input -> {
            input.getDocument().addDocumentListener(edited);
            input.addActionListener(event -> validateConfiguration());
        });
    }

    private void addField(Field field, String title, int row) {
        var label = new JLabel(title);
        var input = new JTextField(16);
        input.setName(field.name());
        label.setLabelFor(input);
        input.getAccessibleContext().setAccessibleName(title);
        var error = new JLabel(" ");
        error.setForeground(new Color(160, 25, 25));
        inputs.put(field, input);
        errors.put(field, error);
        add(label, constraints(0, row * 2));
        var control = constraints(1, row * 2);
        control.weightx = 1;
        control.fill = GridBagConstraints.HORIZONTAL;
        add(input, control);
        var diagnostic = constraints(0, row * 2 + 1);
        diagnostic.gridwidth = 2;
        add(error, diagnostic);
    }

    private static GridBagConstraints constraints(int column, int row) {
        var result = new GridBagConstraints();
        result.gridx = column;
        result.gridy = row;
        result.anchor = GridBagConstraints.WEST;
        result.insets = new Insets(4, 10, 4, 10);
        return result;
    }

    /** Revalida al confirmar; un borrador inválido nunca entrega una configuración. */
    public Optional<SimulationConfig> validateConfiguration() {
        requireEdt();
        var diagnostics = SimulationConfig.textErrors(text(Field.ELEVATORS), text(Field.FLOORS),
                text(Field.MOVEMENT), text(Field.DWELL));
        configuration = Optional.empty();
        errors.forEach((field, label) -> label.setText(diagnostics.getOrDefault(field, " ")));
        if (!diagnostics.isEmpty()) {
            status.setText("Corrige los campos indicados para continuar.");
            inputs.get(diagnostics.keySet().iterator().next()).requestFocusInWindow();
            return configuration;
        }
        configuration = Optional.of(SimulationConfig.fromText(text(Field.ELEVATORS),
                text(Field.FLOORS), text(Field.MOVEMENT), text(Field.DWELL)));
        status.setText("Configuración válida. Lista para iniciar.");
        return configuration;
    }

    /** Solo devuelve el borrador confirmado; cualquier edición lo invalida. */
    public Optional<SimulationConfig> configuration() {
        requireEdt();
        return configuration;
    }

    private String text(Field field) { return inputs.get(field).getText(); }

    private void invalidateConfiguration() {
        requireEdt();
        configuration = Optional.empty();
        errors.values().forEach(label -> label.setText(" "));
        status.setText("Parámetros modificados. Valida la configuración.");
    }

    // Acceso de paquete para escenarios Swing sin JFrame ni diálogos nativos.
    JTextField input(Field field) { requireEdt(); return inputs.get(Objects.requireNonNull(field)); }
    String error(Field field) { requireEdt(); return errors.get(field).getText(); }
    JButton validateButton() { requireEdt(); return validateButton; }

    private static void requireEdt() {
        if (!SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("El formulario debe utilizarse desde el EDT.");
        }
    }
}
```

## src/main/java/com/elevatormanager/ui/MainWindow.java

```java
package com.elevatormanager.ui;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;

/** Entrada gráfica T47; su disposición espera la confirmación de cierre asíncrono. */
public final class MainWindow {
    private static final Logger LOGGER = Logger.getLogger(MainWindow.class.getName());
    private final JFrame frame;

    /** T47 valida parámetros; T48 conectará la creación y los controles de sesión. */
    public MainWindow() { this(() -> CompletableFuture.completedFuture(null)); }

    /** Una sesión existente se conecta con controller::closeAsync. */
    public MainWindow(Supplier<? extends CompletionStage<?>> closeAction) {
        requireEventDispatchThread();
        frame = new JFrame("Elevator Manager");
        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        JPanel content = new JPanel(new BorderLayout(12, 12));
        content.setBorder(BorderFactory.createEmptyBorder(24, 24, 24, 24));
        JPanel messages = new JPanel(new GridLayout(0, 1, 0, 12));
        messages.add(new JLabel("Elevator Manager", SwingConstants.CENTER));
        messages.add(new JLabel("Simulador de elevadores", SwingConstants.CENTER));
        JLabel status = new JLabel("Listo.", SwingConstants.CENTER);
        messages.add(status);
        content.add(messages, BorderLayout.NORTH);
        var configuration = new ConfigurationPanel();
        content.add(new JScrollPane(configuration), BorderLayout.CENTER);
        JButton closeButton = new JButton("Cerrar");
        var close = new WindowCloseHandler(closeAction,
                () -> {
                    closeButton.setEnabled(false);
                    setTreeEnabled(configuration, false);
                    status.setText("Cerrando y liberando recursos...");
                },
                frame::dispose,
                error -> {
                    status.setText("No se pudo completar el cierre. Consulta el registro.");
                    closeButton.setEnabled(true);
                    setTreeEnabled(configuration, true);
                    LOGGER.log(Level.SEVERE, "CIERRE_NO_COMPLETADO", error);
                });
        closeButton.addActionListener(event -> close.requestClose());
        frame.addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent event) { close.requestClose(); }
        });
        JPanel actions = new JPanel();
        actions.add(closeButton);
        content.add(actions, BorderLayout.SOUTH);
        frame.setContentPane(content);
        frame.setMinimumSize(new Dimension(760, 540));
        frame.pack();
        frame.setLocationRelativeTo(null);
    }

    public void showWindow() {
        requireEventDispatchThread();
        frame.setVisible(true);
    }

    private static void setTreeEnabled(java.awt.Component component, boolean enabled) {
        component.setEnabled(enabled);
        if (component instanceof java.awt.Container container) {
            for (var child : container.getComponents()) { setTreeEnabled(child, enabled); }
        }
    }

    private static void requireEventDispatchThread() {
        if (!SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("La ventana debe utilizarse desde el EDT.");
        }
    }
}
```

## src/test/java/com/elevatormanager/ui/ConfigurationFormScenarios.java

```java
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
```

## src/test/java/com/elevatormanager/ui/ConfigurationFormTest.java

```java
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
```
