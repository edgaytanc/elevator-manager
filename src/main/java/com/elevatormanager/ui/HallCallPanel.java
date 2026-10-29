package com.elevatormanager.ui;

import com.elevatormanager.model.Direction;
import com.elevatormanager.model.SimulationState;
import com.elevatormanager.model.request.HallRequest;
import com.elevatormanager.simulation.SimulationController;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.time.Instant;
import java.text.ParseException;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFormattedTextField;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.JTextArea;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.text.DefaultFormatterFactory;
import javax.swing.text.NumberFormatter;

/** T49: llamada del edificio por piso/sentido; la cabina la decide el manejador. */
public final class HallCallPanel extends JPanel {
    private static final long serialVersionUID = 1L;
    private final JSpinner floor = new JSpinner(new SpinnerNumberModel(1, 1, 1, 1));
    private final JButton up = new JButton("Subir");
    private final JButton down = new JButton("Bajar");
    private final JTextArea status = new JTextArea(3, 48);
    private final Consumer<Runnable> postToEdt;
    private SimulationController controller;
    private boolean interactive;
    private boolean lastAllowed;
    private SimulationState observedState;
    private long observedEpoch = -1;
    private long sequence;

    public HallCallPanel() { this(SwingUtilities::invokeLater); }

    /** Permite retener callbacks de sesiones reales para probar respuestas tardías. */
    HallCallPanel(Consumer<Runnable> postToEdt) {
        requireEdt();
        this.postToEdt = Objects.requireNonNull(postToEdt, "postToEdt");
        setLayout(new BorderLayout(6, 6));
        setBorder(BorderFactory.createTitledBorder("Llamadas externas del edificio"));
        var actions = new JPanel(new FlowLayout(FlowLayout.LEFT));
        var label = new JLabel("Piso de origen:");
        label.setLabelFor(floor);
        floor.getAccessibleContext().setAccessibleName("Piso de origen de la llamada externa");
        floor.setPreferredSize(new Dimension(90, floor.getPreferredSize().height));
        configureEditor();
        actions.add(label);
        actions.add(floor);
        actions.add(up);
        actions.add(down);
        add(actions, BorderLayout.NORTH);
        status.setEditable(false);
        status.setOpaque(false);
        status.setLineWrap(true);
        status.setWrapStyleWord(true);
        status.setFont(UIManager.getFont("Label.font"));
        status.getAccessibleContext().setAccessibleName("Resultado de la llamada externa");
        add(status, BorderLayout.CENTER);
        floor.addChangeListener(event -> refresh(interactive));
        up.addActionListener(event -> submit(Direction.UP));
        down.addActionListener(event -> submit(Direction.DOWN));
        message("Configura e inicia la simulación para llamar.");
        refresh(false);
    }

    /** Vincula una sesión ya construida; no crea actores ni lee archivos. */
    public void bind(SimulationController session) {
        requireEdt();
        ++sequence;
        controller = Objects.requireNonNull(session, "session");
        observedState = null;
        observedEpoch = -1;
        lastAllowed = false;
        floor.setModel(new SpinnerNumberModel(1, 1, session.config().floorCount(), 1));
        configureEditor();
        refresh(false);
    }

    private void configureEditor() {
        var editor = new JSpinner.NumberEditor(floor, "0");
        var formatter = new NumberFormatter(editor.getFormat()) {
            private static final long serialVersionUID = 1L;
            @Override public Object stringToValue(String text) throws ParseException {
                try {
                    int value = Integer.parseInt(text.strip());
                    int maximum = ((Number) ((SpinnerNumberModel) floor.getModel()).getMaximum()).intValue();
                    if (value < 1 || value > maximum) { throw new NumberFormatException("Piso fuera de rango"); }
                    return value;
                } catch (NumberFormatException error) {
                    throw new ParseException("El piso debe ser un entero dentro del edificio.", 0);
                }
            }
        };
        formatter.setValueClass(Integer.class);
        formatter.setAllowsInvalid(true);
        editor.getTextField().setFormatterFactory(new DefaultFormatterFactory(formatter));
        // No revertir silenciosamente una entrada inválida al cambiar foco al botón.
        editor.getTextField().setFocusLostBehavior(JFormattedTextField.PERSIST);
        floor.setEditor(editor);
    }

    /** Comparte el refresco de T48: estado publicado, sin esperas ni recorrido de cabinas. */
    public void refresh(boolean interactive) {
        requireEdt();
        this.interactive = interactive;
        SimulationState state = controller == null ? null : controller.state();
        long epoch = controller == null ? -1 : controller.epoch();
        boolean allowed = controller != null && interactive && state == SimulationState.RUNNING;
        if (observedState != state || observedEpoch != epoch || lastAllowed != allowed) {
            ++sequence; // Invalida mensajes anteriores al Stop, Reset, cierre o cambio de sesión.
            observedState = state;
            observedEpoch = epoch;
            lastAllowed = allowed;
            message(allowed ? (controller.config().floorCount() == 1
                    ? "Edificio de un piso: no hay llamadas de subida o bajada."
                    : "Selecciona el piso y el sentido de la llamada.")
                    : controller == null ? "Configura e inicia la simulación para llamar."
                    : state == SimulationState.RESET ? "Pulsa Start para habilitar las llamadas."
                    : state == SimulationState.RUNNING ? "Llamadas bloqueadas mientras se procesa un control del sistema."
                    : "Llamadas deshabilitadas mientras el sistema está en " + state + ".");
        }
        floor.setEnabled(controller != null && interactive);
        long selected = ((Number) floor.getValue()).longValue();
        int maximum = controller == null ? 1 : controller.config().floorCount();
        boolean valid = selected >= 1 && selected <= maximum;
        up.setEnabled(allowed && valid && selected < maximum);
        down.setEnabled(allowed && valid && selected > 1);
    }

    private void submit(Direction direction) {
        requireEdt();
        refresh(interactive);
        if (controller == null || !interactive || controller.state() != SimulationState.RUNNING) { return; }
        final int selected;
        try {
            selected = Integer.parseInt(((JSpinner.NumberEditor) floor.getEditor()).getTextField().getText().strip());
        } catch (NumberFormatException error) {
            message("El piso debe ser un entero entre 1 y " + controller.config().floorCount() + ".");
            return;
        }
        int maximum = controller.config().floorCount();
        if (selected >= 1 && selected <= maximum) { floor.setValue(selected); }
        if (selected < 1 || selected > maximum
                || (direction == Direction.UP && selected == maximum)
                || (direction == Direction.DOWN && selected == 1)) {
            refresh(interactive);
            message("El piso o el sentido seleccionado no permite esta llamada.");
            return;
        }
        var session = controller;
        var request = new HallRequest(UUID.randomUUID(), session.epoch(), (int) selected, Instant.now(), direction);
        long token = ++sequence;
        String description = "Piso " + selected + " para " + (direction == Direction.UP ? "subir" : "bajar");
        message(description + ": enviando llamada...");
        session.submit(request).whenComplete((result, error) -> postToEdt.accept(() -> {
            requireEdt();
            if (controller != session) { return; }
            refresh(interactive);
            if (token != sequence || !interactive || session.state() != SimulationState.RUNNING
                    || request.getEpoch() != session.epoch()) { return; }
            if (error != null) {
                Throwable cause = error;
                while (cause instanceof CompletionException && cause.getCause() != null) { cause = cause.getCause(); }
                message(description + ": no se pudo enviar. " + cause.getMessage());
            } else if (session.isCurrent(result)) {
                message(result.outcome() == SimulationController.Outcome.ENQUEUED
                        ? description + ": llamada enviada al manejador; atención pendiente."
                        : description + ": llamada rechazada. " + result.detail());
            }
        }));
    }

    private void message(String text) {
        status.setText(text.length() > 1000 ? text.substring(0, 1000) : text);
        status.setCaretPosition(0);
    }

    // Acceso de paquete para escenarios Swing; no expone controles a los trabajadores.
    JSpinner floorSelector() { requireEdt(); return floor; }
    JButton button(Direction direction) { requireEdt(); return direction == Direction.UP ? up : down; }
    String statusText() { requireEdt(); return status.getText(); }

    private static void requireEdt() {
        if (!SwingUtilities.isEventDispatchThread()) { throw new IllegalStateException("Las llamadas gráficas requieren EDT."); }
    }
}
