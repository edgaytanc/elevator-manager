# T48 — Código completo

Archivos Java nuevos o modificados, íntegros. El ZIP contiene además todos los fuentes previos.

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
import java.util.function.Consumer;
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

    private final Consumer<SimulationConfig> onConfirmed;
    private boolean editable = true;

    public ConfigurationPanel() { this(config -> { }); }

    public ConfigurationPanel(Consumer<SimulationConfig> onConfirmed) {
        requireEdt();
        this.onConfirmed = Objects.requireNonNull(onConfirmed, "onConfirmed");
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
        if (!editable) { return configuration; }
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
        onConfirmed.accept(configuration.orElseThrow());
        return configuration;
    }

    /** Solo devuelve el borrador confirmado; cualquier edición lo invalida. */
    public Optional<SimulationConfig> configuration() {
        requireEdt();
        return configuration;
    }

    /** Una sesión mantiene su configuración inmutable, incluso después de Reset. */
    public void setEditable(boolean editable) {
        requireEdt();
        this.editable = editable;
        inputs.values().forEach(input -> input.setEnabled(editable));
        validateButton.setEnabled(editable);
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

## src/main/java/com/elevatormanager/ui/SystemControlPanel.java

```java
package com.elevatormanager.ui;

import com.elevatormanager.logging.SessionLog;
import com.elevatormanager.model.SimulationConfig;
import com.elevatormanager.model.SimulationState;
import com.elevatormanager.simulation.SimulationController;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

/** T48: composición de sesión y controles; ningún get/join, lectura o apertura en EDT. */
public final class SystemControlPanel extends JPanel {
    private static final long serialVersionUID = 1L;
    public enum Action { START, STOP, RESET, LOAD, CLOSE }
    @FunctionalInterface interface SessionOpener {
        SimulationController open(SimulationConfig config) throws Exception;
    }
    private final ConfigurationPanel configuration;
    private final EnumMap<Action, JButton> buttons = new EnumMap<>(Action.class);
    private final JLabel stateLabel = new JLabel();
    private final JTextArea detail = new JTextArea(4, 54);
    private final SessionOpener opener;
    private final Supplier<Optional<Path>> selector;
    private final Timer refresh;
    private SimulationController controller;
    private CompletableFuture<SimulationController> preparation;
    private ExecutorService preparer;
    private Runnable onCloseRequest = () -> { };
    private boolean preparing;
    private boolean loading;
    private boolean closing;
    private boolean terminal;
    private boolean disposed;
    private long operationId;
    private long importId;
    private Action pending;

    public SystemControlPanel() { this(SystemControlPanel::openSession, null); }

    /** Inyección de apertura/selección; conserva controlador y actores reales en las pruebas. */
    SystemControlPanel(SessionOpener opener, Supplier<Optional<Path>> selector) {
        requireEdt();
        this.opener = Objects.requireNonNull(opener, "opener");
        this.selector = selector;
        setLayout(new BorderLayout(8, 8));
        setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        configuration = new ConfigurationPanel(this::prepareSession);
        add(new JScrollPane(configuration), BorderLayout.CENTER);
        var controls = new JPanel(new BorderLayout(6, 6));
        var actions = new JPanel(new FlowLayout(FlowLayout.LEFT));
        String[] titles = {"Start", "Stop", "Reset", "Cargar archivo", "Cerrar"};
        for (var action : Action.values()) {
            var button = new JButton(titles[action.ordinal()]);
            buttons.put(action, button);
            actions.add(button);
        }
        controls.add(stateLabel, BorderLayout.NORTH);
        controls.add(actions, BorderLayout.CENTER);
        detail.setEditable(false);
        detail.setLineWrap(true);
        detail.setWrapStyleWord(true);
        controls.add(new JScrollPane(detail), BorderLayout.SOUTH);
        add(controls, BorderLayout.SOUTH);
        buttons.get(Action.START).addActionListener(event -> command(Action.START));
        buttons.get(Action.STOP).addActionListener(event -> command(Action.STOP));
        buttons.get(Action.RESET).addActionListener(event -> command(Action.RESET));
        buttons.get(Action.LOAD).addActionListener(event -> load());
        buttons.get(Action.CLOSE).addActionListener(event -> onCloseRequest.run());
        refresh = new Timer(100, event -> updateView());
        refresh.setCoalesce(true);
        refresh.start();
        message("Valida la configuración para preparar la sesión.");
        updateView();
    }

    /** Crea archivos y estructuras fuera del EDT; el controlador recibe la propiedad del log. */
    static SimulationController openSession(SimulationConfig config) throws Exception {
        return openSession(config, Path.of("logs", "sesion-" + UUID.randomUUID()));
    }

    static SimulationController openSession(SimulationConfig config, Path directory) throws Exception {
        var log = SessionLog.open(directory, config.elevatorCount());
        try { return new SimulationController(config, 0, log); }
        catch (RuntimeException | Error error) {
            try { log.close(); } catch (RuntimeException cleanup) { error.addSuppressed(cleanup); }
            throw error;
        }
    }

    private void prepareSession(SimulationConfig config) {
        requireEdt();
        if (preparing || controller != null || closing || terminal) { return; }
        preparing = true;
        configuration.setEditable(false);
        message("Preparando sesión y archivos de registro...");
        updateView();
        preparer = Executors.newSingleThreadExecutor(task -> new Thread(task, "ui-session-prepare"));
        preparation = CompletableFuture.supplyAsync(() -> {
            try { return Objects.requireNonNull(opener.open(config), "session"); }
            catch (Exception error) { throw new CompletionException(error); }
        }, preparer);
        preparer.shutdown(); // Una apertura por intento, sin ejecutor ocioso permanente.
        preparation.whenComplete((session, error) -> SwingUtilities.invokeLater(() -> {
            preparing = false;
            controller = session;
            if (closing || terminal) { return; } // closeAsync espera y libera incluso una apertura tardía.
            if (error != null) {
                configuration.setEditable(true);
                message("No se pudo preparar la sesión: " + cause(error));
            } else {
                message("Sesión preparada en RESET. Puedes cargar un archivo o pulsar Start.");
            }
            updateView();
        }));
    }

    private void command(Action action) {
        requireEdt();
        updateView();
        if (!buttons.get(action).isEnabled()) { return; }
        long token = ++operationId;
        pending = action;
        if (action == Action.RESET) { ++importId; loading = false; }
        message(switch (action) {
            case START -> "Iniciando o continuando...";
            case STOP -> "Deteniendo: esperando confirmación de las cabinas...";
            case RESET -> "Reseteando: limpiando solicitudes y retornando al piso 1...";
            default -> throw new IllegalArgumentException("Control desconocido.");
        });
        updateView();
        var result = switch (action) {
            case START -> controller.start();
            case STOP -> controller.stop();
            case RESET -> controller.reset();
            default -> throw new IllegalArgumentException("Control desconocido.");
        };
        result.whenComplete((receipt, error) -> SwingUtilities.invokeLater(() -> {
            if (closing || terminal || token != operationId) { return; }
            pending = null;
            if (error != null) { message("No se completó el control: " + cause(error)); }
            else if (controller.isCurrent(receipt)) { message(receipt.detail()); }
            updateView(); // Se consulta el estado actual; no se restaura el estado del recibo.
        }));
    }

    private void load() {
        requireEdt();
        updateView();
        if (!buttons.get(Action.LOAD).isEnabled()) { return; }
        loading = true;
        long token = ++importId;
        message("Seleccionando o leyendo archivo de solicitudes...");
        updateView();
        java.util.function.Consumer<RequestImportHandler.Result> completed = result -> {
            if (closing || terminal || token != importId) { return; }
            loading = false;
            var text = new StringBuilder(result.detail());
            result.imported().ifPresent(receipt -> {
                receipt.diagnostics().stream().limit(8).forEach(diagnostic -> text.append("\n")
                        .append(diagnostic.lineNumber().isPresent()
                                ? "Línea " + diagnostic.lineNumber().getAsInt() + ": " : "")
                        .append(diagnostic.message()));
                if (receipt.diagnostics().size() > 8) { text.append("\nHay más errores en el archivo."); }
            });
            message(text.toString());
            updateView();
        };
        java.util.function.Consumer<Throwable> failed = error -> {
            if (closing || terminal || token != importId) { return; }
            loading = false;
            message("No se pudo cargar el archivo: " + cause(error));
            updateView();
        };
        var handler = selector == null
                ? RequestImportHandler.forDialog(controller, this, completed, failed)
                : new RequestImportHandler(controller, selector, completed, failed);
        handler.requestImport();
    }

    /** Botón y X deben apuntar al mismo WindowCloseHandler. */
    public void onCloseRequest(Runnable action) { requireEdt(); onCloseRequest = Objects.requireNonNull(action); }

    /** Espera apertura tardía, cierre del controlador y fin del preparador, siempre fuera del EDT. */
    public CompletableFuture<Void> closeAsync() {
        requireEdt();
        closing = true;
        ++operationId;
        ++importId;
        pending = null;
        loading = false;
        configuration.setEditable(false);
        message("Cerrando y liberando recursos...");
        updateView();
        var opened = preparation == null ? CompletableFuture.<SimulationController>completedFuture(controller)
                : preparation.handle((session, error) -> session);
        ExecutorService executor = preparer;
        return opened.thenCompose(session -> session == null ? CompletableFuture.completedFuture(null)
                        : session.closeAsync().thenApply(result -> (Void) null))
                .handleAsync((ignored, error) -> {
                    try {
                        if (executor != null) {
                            while (!executor.awaitTermination(1, TimeUnit.SECONDS)) { /* Espera fuera del EDT. */ }
                        }
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new CompletionException(interrupted);
                    }
                    if (error != null) { throw new CompletionException(error); }
                    return (Void) null;
                });
    }

    public void closed() {
        requireEdt();
        terminal = true;
        disposed = true;
        refresh.stop();
        updateView();
    }

    public void closeFailed(Throwable error) {
        requireEdt();
        // Cierre no confirmado: conserva el panel y permite volver a solicitarlo.
        closing = false;
        terminal = true;
        refresh.stop();
        message("No se confirmó el cierre: " + cause(error));
        updateView();
    }

    /** Solo lee estado publicado; no consume eventos, espera futuros ni recorre cabinas. */
    void updateView() {
        requireEdt();
        SimulationState state = controller == null ? null : controller.state();
        boolean usable = controller != null && !preparing && !closing && !terminal;
        boolean idle = pending == null;
        buttons.get(Action.START).setEnabled(usable && idle && !loading
                && (state == SimulationState.RESET || state == SimulationState.STOPPED));
        buttons.get(Action.STOP).setEnabled(usable && idle && state == SimulationState.RUNNING);
        buttons.get(Action.RESET).setEnabled(usable && pending != Action.RESET && pending != Action.START
                && (state == SimulationState.RESET || state == SimulationState.RUNNING
                || state == SimulationState.STOPPING || state == SimulationState.STOPPED));
        buttons.get(Action.LOAD).setEnabled(usable && idle && !loading && state == SimulationState.RESET);
        buttons.get(Action.CLOSE).setEnabled(!closing && !disposed);
        String current = closing ? "CLOSING — Cerrando..." : preparing ? "Preparando sesión..."
                : state == null ? "SIN CONFIGURAR" : switch (state) {
                    case STOPPING -> "STOPPING — Deteniendo...";
                    case RESETTING -> "RESETTING — Reseteando...";
                    case CLOSING -> "CLOSING — Cerrando...";
                    default -> state.name();
                };
        if (!closing && pending == Action.STOP) { current = "STOPPING — Deteniendo..."; }
        if (!closing && pending == Action.RESET) { current = "RESETTING — Reseteando..."; }
        int count = controller == null ? 0 : controller.preparedBatch().map(batch -> batch.requests().size()).orElse(0);
        stateLabel.setText("Estado: " + current + " | Lote preparado: " + count);
    }

    private void message(String text) {
        // Es el resultado de la última acción, no el visor de eventos de T51.
        detail.setText(text.length() > 4000 ? text.substring(0, 4000) : text);
        detail.setCaretPosition(0);
    }

    private static String cause(Throwable error) {
        while (error instanceof CompletionException && error.getCause() != null) { error = error.getCause(); }
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    public Optional<SimulationController> controller() { requireEdt(); return Optional.ofNullable(controller); }
    ConfigurationPanel configurationPanel() { requireEdt(); return configuration; }
    JButton button(Action action) { requireEdt(); return buttons.get(action); }
    String stateText() { requireEdt(); return stateLabel.getText(); }
    String detailText() { requireEdt(); return detail.getText(); }

    private static void requireEdt() {
        if (!SwingUtilities.isEventDispatchThread()) { throw new IllegalStateException("Los controles requieren EDT."); }
    }
}
```

## src/main/java/com/elevatormanager/ui/MainWindow.java

```java
package com.elevatormanager.ui;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;

/** T48: entrada real de configuración y controles; botón y X comparten cierre confirmado. */
public final class MainWindow {
    private static final Logger LOGGER = Logger.getLogger(MainWindow.class.getName());
    private final JFrame frame;

    public MainWindow() { this(() -> CompletableFuture.completedFuture(null)); }

    /** Conserva la extensión de cierre de T39, después de liberar la sesión de esta ventana. */
    public MainWindow(Supplier<? extends CompletionStage<?>> closeAction) {
        requireEventDispatchThread();
        frame = new JFrame("Elevator Manager");
        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        var controls = new SystemControlPanel();
        var close = new WindowCloseHandler(
                () -> controls.closeAsync().thenCompose(ignored -> {
                    // El proveedor externo puede tener contrato EDT: obtenerlo en ese hilo.
                    var completion = new CompletableFuture<Void>();
                    SwingUtilities.invokeLater(() -> {
                        try {
                            closeAction.get().whenComplete((value, error) -> {
                                if (error == null) { completion.complete(null); }
                                else { completion.completeExceptionally(error); }
                            });
                        } catch (RuntimeException error) { completion.completeExceptionally(error); }
                    });
                    return completion;
                }),
                () -> { },
                () -> { controls.closed(); frame.dispose(); },
                error -> {
                    controls.closeFailed(error);
                    LOGGER.log(Level.SEVERE, "CIERRE_NO_COMPLETADO", error);
                });
        controls.onCloseRequest(close::requestClose);
        frame.addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent event) { close.requestClose(); }
        });
        frame.add(new JLabel("Elevator Manager — Simulador de elevadores", SwingConstants.CENTER), BorderLayout.NORTH);
        frame.add(controls, BorderLayout.CENTER);
        frame.setMinimumSize(new Dimension(800, 650));
        frame.pack();
        frame.setLocationRelativeTo(null);
    }

    public void showWindow() { requireEventDispatchThread(); frame.setVisible(true); }

    private static void requireEventDispatchThread() {
        if (!SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("La ventana debe utilizarse desde el EDT.");
        }
    }
}
```

## src/test/java/com/elevatormanager/ui/SystemControlsScenarios.java

```java
package com.elevatormanager.ui;

import com.elevatormanager.model.*;
import com.elevatormanager.model.SimulationConfig.Field;
import com.elevatormanager.model.request.CabinRequest;
import com.elevatormanager.simulation.SimulationController;
import com.elevatormanager.ui.SystemControlPanel.Action;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import javax.swing.SwingUtilities;

/** T48: controles Swing en EDT y sesiones reales; selección inyectada sin diálogo nativo. */
public final class SystemControlsScenarios {
    private SystemControlsScenarios() { }
    public static void initialControlsRequireConfiguration() throws Exception {
        try (var rig = new Rig()) {
            edt(() -> { for (Action a : Action.values()) check(rig.enabled(a) == (a == Action.CLOSE), "Sin sesión " + a); });
        }
    }

    public static void invalidConfigurationDoesNotOpenSession() throws Exception {
        try (var rig = new Rig()) {
            edt(() -> rig.panel.configurationPanel().validateConfiguration());
            check(rig.opened.get() == 0 && rig.controller() == null, "No crea sesión inválida");
        }
    }

    public static void preparationRunsOutsideEdtAndLocksFields() throws Exception {
        try (var rig = new Rig()) {
            rig.prepare();
            check(rig.opened.get() == 1 && !rig.openedOnEdt.get(), "Apertura fuera de EDT");
            edt(() -> check(!rig.panel.configurationPanel().input(Field.ELEVATORS).isEnabled(), "Configuración bloqueada"));
        }
    }

    public static void resetEnablesOnlyLegalControls() throws Exception {
        try (var rig = new Rig()) {
            rig.prepare();
            edt(() -> { check(rig.enabled(Action.START) && rig.enabled(Action.RESET) && rig.enabled(Action.LOAD), "RESET habilita");
                check(!rig.enabled(Action.STOP), "RESET no Stop"); });
        }
    }

    public static void startStopAndContinuePreserveSession() throws Exception {
        try (var rig = new Rig()) {
            rig.prepare(); var original = rig.controller();
            rig.click(Action.START); rig.awaitState(SimulationState.RUNNING);
            rig.click(Action.STOP); rig.awaitState(SimulationState.STOPPED);
            edt(() -> check(rig.enabled(Action.START) && !rig.enabled(Action.LOAD), "STOPPED continúa sin importar"));
            rig.click(Action.START); rig.awaitState(SimulationState.RUNNING);
            check(rig.controller() == original && rig.opened.get() == 1, "Mismos actores y sesión");
        }
    }

    public static void stoppingIndicatorAndResetRemainAvailable() throws Exception {
        try (var rig = new Rig()) {
            rig.prepare(); rig.moving();
            edt(() -> { rig.panel.button(Action.STOP).doClick(0);
                check(rig.panel.stateText().contains("Deteniendo"), "STOPPING visible");
                check(!rig.enabled(Action.START) && !rig.enabled(Action.LOAD) && rig.enabled(Action.RESET), "Controles durante Stop"); });
            rig.awaitState(SimulationState.STOPPED);
        }
    }

    public static void resettingIndicatorWaitsForReturn() throws Exception {
        try (var rig = new Rig()) {
            rig.prepare(); rig.moving();
            edt(() -> { rig.panel.button(Action.RESET).doClick(0);
                check(rig.panel.stateText().contains("Reseteando"), "RESETTING visible");
                check(!rig.enabled(Action.START) && !rig.enabled(Action.LOAD), "No inicia ni importa durante retorno"); });
            rig.awaitState(SimulationState.RESET);
            check(rig.controller().snapshots().stream().allMatch(s -> s.currentFloor() == 1), "Retorno real confirmado");
        }
    }

    public static void importPreparesWithoutMoving() throws Exception {
        try (var rig = new Rig()) {
            rig.prepare(); rig.select("CABIN 1 4\n"); rig.click(Action.LOAD);
            waitFor(() -> rig.controller().preparedBatch().isPresent());
            waitFor(() -> onEdt(() -> rig.enabled(Action.LOAD)));
            check(rig.controller().state() == SimulationState.RESET && rig.controller().snapshots().get(0).currentFloor() == 1, "Preparación sin viaje");
            edt(() -> check(rig.panel.stateText().contains("Lote preparado: 1"), "Cantidad visible"));
        }
    }

    public static void loadDisabledInRunningAndStopped() throws Exception {
        try (var rig = new Rig()) {
            rig.prepare(); rig.click(Action.START); rig.awaitState(SimulationState.RUNNING);
            edt(() -> check(!rig.enabled(Action.LOAD), "RUNNING no carga"));
            rig.click(Action.STOP); rig.awaitState(SimulationState.STOPPED);
            edt(() -> check(!rig.enabled(Action.LOAD), "STOPPED no carga"));
        }
    }

    public static void cancelSelectionPreservesPreparedBatch() throws Exception {
        try (var rig = new Rig()) {
            rig.prepare(); rig.select("CABIN 1 4\n"); rig.click(Action.LOAD);
            waitFor(() -> onEdt(() -> rig.enabled(Action.LOAD)));
            var old = rig.controller().preparedBatch().orElseThrow(); rig.selected.set(Optional.empty());
            rig.click(Action.LOAD);
            edt(() -> check(rig.panel.detailText().contains("cancelada"), "Cancelación visible"));
            check(rig.controller().preparedBatch().orElseThrow() == old, "Conserva lote");
        }
    }

    public static void invalidImportKeepsPreviousBatchAndShowsLine() throws Exception {
        try (var rig = new Rig()) {
            rig.prepare(); rig.select("CABIN 1 4\n"); rig.click(Action.LOAD);
            waitFor(() -> onEdt(() -> rig.enabled(Action.LOAD)));
            var old = rig.controller().preparedBatch().orElseThrow(); rig.select("CABIN 1 4\nCABIN 1 99\n");
            rig.click(Action.LOAD); waitFor(() -> onEdt(() -> rig.enabled(Action.LOAD)));
            check(rig.controller().preparedBatch().orElseThrow() == old, "Atómico");
            edt(() -> check(rig.panel.detailText().contains("Línea 2"), "Diagnóstico visible"));
        }
    }

    public static void resetClearsImportedBatch() throws Exception {
        try (var rig = new Rig()) {
            rig.prepare(); rig.select("CABIN 1 4\n"); rig.click(Action.LOAD);
            waitFor(() -> onEdt(() -> rig.enabled(Action.LOAD)));
            rig.click(Action.RESET); rig.awaitState(SimulationState.RESET);
            check(rig.controller().preparedBatch().isEmpty(), "Reset elimina lote");
            edt(() -> check(rig.panel.stateText().contains("Lote preparado: 0"), "Cantidad actualizada"));
        }
    }

    public static void closeWithoutConfigurationCompletes() throws Exception {
        try (var rig = new Rig()) {
            var done = onEdt(() -> rig.panel.closeAsync()); done.get(5, TimeUnit.SECONDS);
            edt(() -> { rig.panel.closed(); check(!rig.enabled(Action.CLOSE), "Cerrada sin sesión"); });
        }
    }

    public static void closeDuringPreparationReleasesLateSession() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var created = new AtomicReference<SimulationController>();
        try (var rig = new Rig(config -> {
            entered.countDown(); check(release.await(5, TimeUnit.SECONDS), "Liberar apertura");
            var c = new SimulationController(config, 0); created.set(c); return c;
        })) {
            rig.confirm(); check(entered.await(5, TimeUnit.SECONDS), "Apertura activa");
            var done = onEdt(() -> rig.panel.closeAsync());
            check(!done.isDone(), "Cierre espera apertura"); release.countDown(); done.get(5, TimeUnit.SECONDS);
            check(created.get().awaitTermination(Duration.ofSeconds(5)), "Cerró sesión tardía y actores");
            edt(rig.panel::closed);
        } finally { release.countDown(); }
    }

    public static void preparationFailureAllowsCorrectionAndRetry() throws Exception {
        var attempts = new AtomicInteger();
        try (var rig = new Rig(config -> {
            if (attempts.incrementAndGet() == 1) throw new java.io.IOException("fallo de apertura inyectado");
            return new SimulationController(config, 0);
        })) {
            rig.confirm(); waitFor(() -> onEdt(() -> rig.panel.configurationPanel().input(Field.ELEVATORS).isEnabled()));
            edt(() -> check(rig.panel.detailText().contains("fallo de apertura"), "Fallo visible"));
            rig.prepare(); check(attempts.get() == 2, "Reintento válido");
        }
    }

    public static void closeDuringRunningTerminatesWorkers() throws Exception {
        try (var rig = new Rig()) {
            rig.prepare(); rig.moving(); var c = rig.controller();
            onEdt(() -> rig.panel.closeAsync()).get(5, TimeUnit.SECONDS);
            check(c.awaitTermination(Duration.ofSeconds(5)), "Cierre real con movimiento"); edt(rig.panel::closed);
        }
    }

    public static void closeDuringStopIgnoresLateControlReceipt() throws Exception {
        try (var rig = new Rig()) {
            rig.prepare(); rig.moving();
            var done = onEdt(() -> { rig.panel.button(Action.STOP).doClick(0); return rig.panel.closeAsync(); });
            done.get(5, TimeUnit.SECONDS);
            edt(() -> check(rig.panel.detailText().contains("Cerrando"), "Stop tardío no restaura UI"));
            edt(rig.panel::closed);
        }
    }

    public static void closeDuringResetTerminatesWorkers() throws Exception {
        try (var rig = new Rig()) {
            rig.prepare(); rig.moving();
            onEdt(() -> { rig.panel.button(Action.RESET).doClick(0); return rig.panel.closeAsync(); }).get(5, TimeUnit.SECONDS);
            check(rig.controller().awaitTermination(Duration.ofSeconds(5)), "Cierre durante retorno"); edt(rig.panel::closed);
        }
    }

    public static void repeatedConfirmationDoesNotCreateSecondSession() throws Exception {
        try (var rig = new Rig()) {
            rig.prepare();
            edt(() -> { rig.panel.configurationPanel().validateConfiguration(); rig.panel.configurationPanel().validateConfiguration(); });
            check(rig.opened.get() == 1, "Una sesión");
        }
    }

    public static void realLogFilesAreCreatedAndReleased() throws Exception {
        Path directory = Files.createTempDirectory("t48-log-");
        try {
            try (var rig = new Rig(config -> SystemControlPanel.openSession(config, directory))) {
                rig.prepare(); rig.click(Action.START); rig.awaitState(SimulationState.RUNNING);
                rig.click(Action.STOP); rig.awaitState(SimulationState.STOPPED);
                onEdt(() -> rig.panel.closeAsync()).get(5, TimeUnit.SECONDS); edt(rig.panel::closed);
                check(Files.size(directory.resolve("elevador1.log")) > 0 && Files.size(directory.resolve("manejador.log")) > 0, "Logs reales");
            }
            try (var log = com.elevatormanager.logging.SessionLog.open(directory, 1, java.io.OutputStream.nullOutputStream())) {
                check(log.isAlive(), "Lock liberado para reapertura");
            }
        } finally { deleteTree(directory); }
    }

    public static void offEdtAccessIsRejected() throws Exception {
        try { new SystemControlPanel(); throw new AssertionError("Constructor fuera EDT"); }
        catch (IllegalStateException expected) { }
        try (var rig = new Rig()) {
            try { rig.panel.closeAsync(); throw new AssertionError("Cierre visual fuera EDT"); }
            catch (IllegalStateException expected) { }
            try { rig.panel.controller(); throw new AssertionError("Lectura fuera EDT"); }
            catch (IllegalStateException expected) { }
        }
    }

    public static void edtRespondsDuringPreparation() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        try (var rig = new Rig(config -> {
            entered.countDown(); check(release.await(5, TimeUnit.SECONDS), "Liberar preparación"); return new SimulationController(config, 0);
        })) {
            rig.confirm(); check(entered.await(5, TimeUnit.SECONDS), "Preparación iniciada");
            edt(() -> check(rig.enabled(Action.CLOSE) && !rig.enabled(Action.START), "EDT disponible durante apertura"));
            release.countDown(); waitFor(() -> rig.controller() != null);
        } finally { release.countDown(); }
    }

    public static void closeFailureKeepsPanelAndRetryAvailable() throws Exception {
        try (var rig = new Rig()) {
            rig.prepare();
            rig.controller().registerResource(() -> { throw new java.io.IOException("cierre inyectado"); }).get(5, TimeUnit.SECONDS);
            var result = onEdt(() -> rig.panel.closeAsync());
            try { result.get(5, TimeUnit.SECONDS); throw new AssertionError("Cierre fallido silencioso"); }
            catch (java.util.concurrent.ExecutionException expected) {
                edt(() -> rig.panel.closeFailed(expected.getCause()));
            }
            edt(() -> { check(rig.enabled(Action.CLOSE) && !rig.enabled(Action.START), "No dispone panel ni habilita trabajo");
                check(rig.panel.detailText().contains("No se confirmó"), "Fallo de cierre visible"); });
            rig.expectedCloseFailure = true;
        }
    }

    public static void resetSupersedesStopWithoutRestoringOldState() throws Exception {
        try (var rig = new Rig()) {
            rig.prepare(); rig.moving();
            edt(() -> { rig.panel.button(Action.STOP).doClick(0); rig.panel.button(Action.RESET).doClick(0); });
            rig.awaitState(SimulationState.RESET);
            edt(() -> check(rig.enabled(Action.START) && rig.enabled(Action.LOAD) && !rig.enabled(Action.STOP), "Estado vigente tras Stop sustituido"));
        }
    }

    public static void loadFailureAllowsRetry() throws Exception {
        try (var rig = new Rig()) {
            rig.prepare(); rig.selected.set(Optional.of(rig.directory.resolve("ausente.txt")));
            rig.click(Action.LOAD); waitFor(() -> onEdt(() -> rig.enabled(Action.LOAD)));
            edt(() -> check(rig.panel.detailText().length() > 0 && rig.enabled(Action.START), "Error recuperable"));
            rig.select("CABIN 1 4\n"); rig.click(Action.LOAD);
            waitFor(() -> rig.controller().preparedBatch().isPresent());
        }
    }

    public static void importedBatchRunsThroughStartButton() throws Exception {
        try (var rig = new Rig()) {
            rig.prepare(); rig.select("CABIN 1 4\n"); rig.click(Action.LOAD);
            waitFor(() -> onEdt(() -> rig.enabled(Action.LOAD)));
            rig.click(Action.START); rig.awaitState(SimulationState.RUNNING);
            waitFor(() -> rig.controller().snapshots().get(0).currentFloor() == 4);
            check(rig.controller().preparedBatch().isEmpty() && rig.controller().lastBatchDispatch().isPresent(), "Start consume lote real");
        }
    }

    public static void logOpeningFailureDoesNotPublishPartialSession() throws Exception {
        Path file = Files.createTempFile("t48-no-directory-", ".tmp");
        try (var rig = new Rig(config -> SystemControlPanel.openSession(config, file))) {
            rig.confirm();
            waitFor(() -> onEdt(() -> rig.panel.configurationPanel().input(Field.ELEVATORS).isEnabled()));
            check(rig.controller() == null, "No publica sesión con logging incompleto");
            edt(() -> check(!rig.enabled(Action.START) && rig.enabled(Action.CLOSE), "Recuperable, sin arranque parcial"));
        } finally { Files.deleteIfExists(file); }
    }

    public static void buttonAndWindowRequestShareOneCloseHandler() throws Exception {
        try (var rig = new Rig()) {
            rig.prepare(); var disposed = new AtomicInteger();
            var handler = new AtomicReference<WindowCloseHandler>();
            edt(() -> {
                handler.set(new WindowCloseHandler(rig.panel::closeAsync, () -> { },
                        () -> { rig.panel.closed(); disposed.incrementAndGet(); }, rig.panel::closeFailed));
                rig.panel.onCloseRequest(handler.get()::requestClose);
                rig.panel.button(Action.CLOSE).doClick(0);
                handler.get().requestClose(); // Misma ruta utilizada por la X.
            });
            waitFor(() -> disposed.get() == 1);
            edt(() -> handler.get().requestClose());
            check(disposed.get() == 1 && rig.controller().awaitTermination(Duration.ofSeconds(5)), "Cierre único confirmado");
        }
    }

    private static final class Rig implements AutoCloseable {
        final Path directory = Files.createTempDirectory("t48-controls-");
        final AtomicReference<Optional<Path>> selected = new AtomicReference<>(Optional.empty());
        final AtomicInteger opened = new AtomicInteger();
        final AtomicBoolean openedOnEdt = new AtomicBoolean();
        final SystemControlPanel panel;
        boolean expectedCloseFailure;
        Rig() throws Exception { this(config -> new SimulationController(config, 0)); }
        Rig(SystemControlPanel.SessionOpener opener) throws Exception {
            panel = onEdt(() -> new SystemControlPanel(config -> {
                opened.incrementAndGet(); openedOnEdt.set(SwingUtilities.isEventDispatchThread()); return opener.open(config);
            }, selected::get));
        }
        void confirm() throws Exception {
            edt(() -> {
                var form = panel.configurationPanel();
                String[] values = {"1", "4", "350", "40"};
                for (var field : Field.values()) form.input(field).setText(values[field.ordinal()]);
                form.validateConfiguration();
            });
        }
        void prepare() throws Exception { confirm(); waitFor(() -> controller() != null); }
        SimulationController controller() { return onEdt(() -> panel.controller().orElse(null)); }
        boolean enabled(Action action) { panel.updateView(); return panel.button(action).isEnabled(); }
        void click(Action action) throws Exception { edt(() -> { panel.updateView(); panel.button(action).doClick(0); }); }
        void awaitState(SimulationState state) throws Exception {
            waitFor(() -> controller().state() == state && onEdt(() -> {
                panel.updateView();
                return state != SimulationState.RESET && state != SimulationState.STOPPED
                        || panel.button(Action.START).isEnabled();
            }));
        }
        void moving() throws Exception {
            click(Action.START); awaitState(SimulationState.RUNNING);
            var c = controller();
            c.submit(new CabinRequest(UUID.randomUUID(), c.epoch(), 4, Instant.now(), 1)).get(5, TimeUnit.SECONDS);
            waitFor(() -> c.snapshots().get(0).phase() == ElevatorPhase.MOVING);
        }
        void select(String text) throws Exception {
            Path file = directory.resolve(UUID.randomUUID() + ".txt"); Files.writeString(file, text); selected.set(Optional.of(file));
        }
        @Override public void close() throws Exception {
            try { onEdt(() -> panel.closeAsync()).get(8, TimeUnit.SECONDS); }
            catch (ExecutionException error) { if (!expectedCloseFailure) throw error; }
            finally { edt(panel::closed); deleteTree(directory); }
        }
    }

    private static void deleteTree(Path path) throws Exception {
        try (var paths = Files.walk(path)) {
            for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        }
    }
    private static void check(boolean condition, String text) { if (!condition) throw new AssertionError(text); }
    private static void waitFor(BooleanSupplier condition) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(6);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > until) throw new AssertionError("Tiempo agotado esperando condición");
            Thread.sleep(5);
        }
    }
    private static void edt(Runnable action) throws Exception { onEdt(() -> { action.run(); return null; }); }
    private static <T> T onEdt(Supplier<T> action) {
        if (SwingUtilities.isEventDispatchThread()) return action.get();
        var value = new AtomicReference<T>(); var failure = new AtomicReference<Throwable>();
        try { SwingUtilities.invokeAndWait(() -> {
            try { value.set(action.get()); } catch (Throwable error) { failure.set(error); }
        }); } catch (Exception error) { throw new AssertionError(error); }
        if (failure.get() instanceof Error error) throw error;
        if (failure.get() != null) throw new IllegalStateException(failure.get());
        return value.get();
    }
    public static void main(String[] args) throws Exception {
        initialControlsRequireConfiguration();
        invalidConfigurationDoesNotOpenSession();
        preparationRunsOutsideEdtAndLocksFields();
        resetEnablesOnlyLegalControls();
        startStopAndContinuePreserveSession();
        stoppingIndicatorAndResetRemainAvailable();
        resettingIndicatorWaitsForReturn();
        importPreparesWithoutMoving();
        loadDisabledInRunningAndStopped();
        cancelSelectionPreservesPreparedBatch();
        invalidImportKeepsPreviousBatchAndShowsLine();
        resetClearsImportedBatch();
        closeWithoutConfigurationCompletes();
        closeDuringPreparationReleasesLateSession();
        preparationFailureAllowsCorrectionAndRetry();
        closeDuringRunningTerminatesWorkers();
        closeDuringStopIgnoresLateControlReceipt();
        closeDuringResetTerminatesWorkers();
        repeatedConfirmationDoesNotCreateSecondSession();
        realLogFilesAreCreatedAndReleased();
        offEdtAccessIsRejected();
        edtRespondsDuringPreparation();
        closeFailureKeepsPanelAndRetryAvailable();
        resetSupersedesStopWithoutRestoringOldState();
        loadFailureAllowsRetry();
        importedBatchRunsThroughStartButton();
        logOpeningFailureDoesNotPublishPartialSession();
        buttonAndWindowRequestShareOneCloseHandler();
        System.out.println("T48: 28 escenarios satisfactorios.");
    }
}
```

## src/test/java/com/elevatormanager/ui/SystemControlsTest.java

```java
package com.elevatormanager.ui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Adaptadores JUnit T48. */
@Timeout(30)
class SystemControlsTest {
    @Test void initialControlsRequireConfiguration() throws Exception {
        SystemControlsScenarios.initialControlsRequireConfiguration();
    }
    @Test void invalidConfigurationDoesNotOpenSession() throws Exception {
        SystemControlsScenarios.invalidConfigurationDoesNotOpenSession();
    }
    @Test void preparationRunsOutsideEdtAndLocksFields() throws Exception {
        SystemControlsScenarios.preparationRunsOutsideEdtAndLocksFields();
    }
    @Test void resetEnablesOnlyLegalControls() throws Exception {
        SystemControlsScenarios.resetEnablesOnlyLegalControls();
    }
    @Test void startStopAndContinuePreserveSession() throws Exception {
        SystemControlsScenarios.startStopAndContinuePreserveSession();
    }
    @Test void stoppingIndicatorAndResetRemainAvailable() throws Exception {
        SystemControlsScenarios.stoppingIndicatorAndResetRemainAvailable();
    }
    @Test void resettingIndicatorWaitsForReturn() throws Exception {
        SystemControlsScenarios.resettingIndicatorWaitsForReturn();
    }
    @Test void importPreparesWithoutMoving() throws Exception {
        SystemControlsScenarios.importPreparesWithoutMoving();
    }
    @Test void loadDisabledInRunningAndStopped() throws Exception {
        SystemControlsScenarios.loadDisabledInRunningAndStopped();
    }
    @Test void cancelSelectionPreservesPreparedBatch() throws Exception {
        SystemControlsScenarios.cancelSelectionPreservesPreparedBatch();
    }
    @Test void invalidImportKeepsPreviousBatchAndShowsLine() throws Exception {
        SystemControlsScenarios.invalidImportKeepsPreviousBatchAndShowsLine();
    }
    @Test void resetClearsImportedBatch() throws Exception {
        SystemControlsScenarios.resetClearsImportedBatch();
    }
    @Test void closeWithoutConfigurationCompletes() throws Exception {
        SystemControlsScenarios.closeWithoutConfigurationCompletes();
    }
    @Test void closeDuringPreparationReleasesLateSession() throws Exception {
        SystemControlsScenarios.closeDuringPreparationReleasesLateSession();
    }
    @Test void preparationFailureAllowsCorrectionAndRetry() throws Exception {
        SystemControlsScenarios.preparationFailureAllowsCorrectionAndRetry();
    }
    @Test void closeDuringRunningTerminatesWorkers() throws Exception {
        SystemControlsScenarios.closeDuringRunningTerminatesWorkers();
    }
    @Test void closeDuringStopIgnoresLateControlReceipt() throws Exception {
        SystemControlsScenarios.closeDuringStopIgnoresLateControlReceipt();
    }
    @Test void closeDuringResetTerminatesWorkers() throws Exception {
        SystemControlsScenarios.closeDuringResetTerminatesWorkers();
    }
    @Test void repeatedConfirmationDoesNotCreateSecondSession() throws Exception {
        SystemControlsScenarios.repeatedConfirmationDoesNotCreateSecondSession();
    }
    @Test void realLogFilesAreCreatedAndReleased() throws Exception {
        SystemControlsScenarios.realLogFilesAreCreatedAndReleased();
    }
    @Test void offEdtAccessIsRejected() throws Exception {
        SystemControlsScenarios.offEdtAccessIsRejected();
    }
    @Test void edtRespondsDuringPreparation() throws Exception {
        SystemControlsScenarios.edtRespondsDuringPreparation();
    }
    @Test void closeFailureKeepsPanelAndRetryAvailable() throws Exception {
        SystemControlsScenarios.closeFailureKeepsPanelAndRetryAvailable();
    }
    @Test void resetSupersedesStopWithoutRestoringOldState() throws Exception {
        SystemControlsScenarios.resetSupersedesStopWithoutRestoringOldState();
    }
    @Test void loadFailureAllowsRetry() throws Exception {
        SystemControlsScenarios.loadFailureAllowsRetry();
    }
    @Test void importedBatchRunsThroughStartButton() throws Exception {
        SystemControlsScenarios.importedBatchRunsThroughStartButton();
    }
    @Test void logOpeningFailureDoesNotPublishPartialSession() throws Exception {
        SystemControlsScenarios.logOpeningFailureDoesNotPublishPartialSession();
    }
    @Test void buttonAndWindowRequestShareOneCloseHandler() throws Exception {
        SystemControlsScenarios.buttonAndWindowRequestShareOneCloseHandler();
    }
}
```
