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
