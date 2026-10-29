# T49 — Código completo

Archivos Java nuevos o modificados, íntegros. El ZIP contiene además todos los fuentes previos.

## src/main/java/com/elevatormanager/ui/HallCallPanel.java

```java
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

/** T49: controles de sesión y llamadas externas; ningún get/join, lectura o apertura en EDT. */
public final class SystemControlPanel extends JPanel {
    private static final long serialVersionUID = 1L;
    public enum Action { START, STOP, RESET, LOAD, CLOSE }
    @FunctionalInterface interface SessionOpener {
        SimulationController open(SimulationConfig config) throws Exception;
    }
    private final ConfigurationPanel configuration;
    private final HallCallPanel hallCalls;
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
        hallCalls = new HallCallPanel();
        var workspace = new JPanel(new BorderLayout(8, 8));
        workspace.add(new JScrollPane(configuration), BorderLayout.CENTER);
        workspace.add(hallCalls, BorderLayout.SOUTH);
        add(workspace, BorderLayout.CENTER);
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
                hallCalls.bind(session);
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
        hallCalls.refresh(usable && idle && !loading);
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
    HallCallPanel hallCallPanel() { requireEdt(); return hallCalls; }
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
        frame.setMinimumSize(new Dimension(820, 760));
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

## src/test/java/com/elevatormanager/ui/HallCallsScenarios.java

```java
package com.elevatormanager.ui;

import com.elevatormanager.dispatch.HallCallSnapshot;
import com.elevatormanager.model.*;
import com.elevatormanager.model.SimulationConfig.Field;
import com.elevatormanager.simulation.SimulationController;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.*;
import javax.swing.*;

/** T49: llamadas Swing y manejador/cabinas reales, sin ventana ni selector nativo. */
public final class HallCallsScenarios {
    private HallCallsScenarios() { }
    public static void unboundPanelDisablesCalls() throws Exception {
        var panel = onEdt(HallCallPanel::new);
        edt(() -> check(!panel.button(Direction.UP).isEnabled() && !panel.button(Direction.DOWN).isEnabled()
                && !panel.floorSelector().isEnabled() && !panel.statusText().isBlank(), "Sin sesión"));
    }

    public static void resetSessionDisablesCalls() throws Exception {
        try (var rig = new Rig()) {
            edt(() -> { rig.panel.refresh(true); check(!rig.enabled(Direction.UP) && !rig.enabled(Direction.DOWN), "RESET sin llamadas"); });
            check(rig.c.calls().isEmpty(), "No envíos antes de Start");
        }
    }

    public static void bottomFloorAllowsOnlyUp() throws Exception {
        try (var rig = new Rig()) {
            rig.start(); rig.select(1);
            edt(() -> check(rig.enabled(Direction.UP) && !rig.enabled(Direction.DOWN), "Extremo inferior"));
        }
    }

    public static void upperFloorAllowsOnlyDown() throws Exception {
        try (var rig = new Rig()) {
            rig.start(); rig.select(5);
            edt(() -> check(!rig.enabled(Direction.UP) && rig.enabled(Direction.DOWN), "Extremo superior"));
        }
    }

    public static void interiorFloorAllowsBothDirections() throws Exception {
        try (var rig = new Rig()) {
            rig.start(); rig.select(3);
            edt(() -> check(rig.enabled(Direction.UP) && rig.enabled(Direction.DOWN), "Piso interior"));
        }
    }

    public static void singleFloorDisablesBothDirections() throws Exception {
        try (var rig = new Rig(1, false)) {
            rig.start();
            edt(() -> check(!rig.enabled(Direction.UP) && !rig.enabled(Direction.DOWN), "P=1 sin extremos posibles"));
        }
    }

    public static void spinnerReflectsConfiguredFloorBounds() throws Exception {
        try (var rig = new Rig()) {
            edt(() -> {
                var model = (javax.swing.SpinnerNumberModel) rig.panel.floorSelector().getModel();
                check(model.getMinimum().equals(1) && model.getMaximum().equals(5), "Rango 1..P");
                check(rig.panel.floorSelector().getPreviousValue() == null, "Sin piso cero");
                rig.panel.floorSelector().setValue(5);
                check(rig.panel.floorSelector().getNextValue() == null, "Sin piso P+1");
            });
        }
    }

    public static void upCallGoesThroughManagerAndIsServed() throws Exception {
        try (var rig = new Rig()) {
            rig.start(); rig.select(3); rig.click(Direction.UP);
            waitFor(() -> rig.c.calls().size() == 1);
            var call = rig.c.calls().values().iterator().next();
            check(call.request().getFloor() == 3 && call.request().getDirection() == Direction.UP, "Piso/sentido correctos");
            check(call.request().getEpoch() == rig.c.epoch() && call.request().getId() != null, "Época e identidad propias");
            waitFor(() -> rig.c.calls().values().stream().allMatch(s -> s.status() == HallCallSnapshot.Status.SERVED));
            var served = rig.c.calls().values().iterator().next();
            check(served.command().isPresent() && served.command().orElseThrow().elevatorId() >= 1, "Asignación del manejador y atención real");
        }
    }

    public static void downCallGoesThroughManagerAndIsServed() throws Exception {
        try (var rig = new Rig()) {
            rig.start(); rig.select(3); rig.click(Direction.DOWN);
            waitFor(() -> rig.c.calls().size() == 1);
            check(rig.c.calls().values().iterator().next().request().getDirection() == Direction.DOWN, "Dirección DOWN");
            waitFor(() -> rig.c.calls().values().stream().allMatch(s -> s.status() == HallCallSnapshot.Status.SERVED));
        }
    }

    public static void oppositeCallsAtSameFloorRemainDistinct() throws Exception {
        try (var rig = new Rig()) {
            rig.start(); rig.select(3);
            edt(() -> { rig.panel.button(Direction.UP).doClick(0); rig.panel.button(Direction.DOWN).doClick(0); });
            waitFor(() -> rig.c.calls().size() == 2);
            check(rig.c.calls().values().stream().map(s -> s.request().getDirection()).distinct().count() == 2, "Dos sentidos distintos");
            check(rig.c.calls().values().stream().map(HallCallSnapshot::canonicalRequestId).distinct().count() == 2, "Canónicas distintas");
        }
    }

    public static void repeatedClicksUseManagerDeduplication() throws Exception {
        try (var rig = new Rig()) {
            rig.start(); rig.select(4);
            edt(() -> { rig.panel.button(Direction.UP).doClick(0); rig.panel.button(Direction.UP).doClick(0); });
            waitFor(() -> rig.c.calls().size() == 2);
            check(rig.c.calls().values().stream().map(HallCallSnapshot::canonicalRequestId).distinct().count() == 1, "Deduplicación activa del manejador");
            check(rig.c.calls().values().stream().map(s -> s.request().getId()).distinct().count() == 2, "Identidad por pulsación");
        }
    }

    public static void enqueuedReceiptDoesNotClaimService() throws Exception {
        try (var rig = new Rig(5, true)) {
            rig.start(); rig.select(4); rig.click(Direction.UP); var delivery = rig.delivery();
            edt(delivery);
            edt(() -> check(rig.panel.statusText().contains("enviada al manejador")
                    && rig.panel.statusText().contains("atención pendiente"), "Envío no es servicio"));
        }
    }

    public static void stopDisablesAndResumeRestoresCalls() throws Exception {
        try (var rig = new Rig()) {
            rig.start(); rig.c.stop().get(5, TimeUnit.SECONDS);
            edt(() -> { rig.panel.refresh(true); check(!rig.enabled(Direction.UP) && !rig.enabled(Direction.DOWN), "STOPPED bloqueado"); });
            rig.c.start().get(5, TimeUnit.SECONDS);
            edt(() -> { rig.panel.refresh(true); check(rig.enabled(Direction.UP), "Continuación habilita"); });
        }
    }

    public static void resetInvalidatesDelayedReceipt() throws Exception {
        try (var rig = new Rig(5, true)) {
            rig.start(); rig.select(4); rig.click(Direction.UP); var delivery = rig.delivery();
            rig.c.reset().get(5, TimeUnit.SECONDS);
            edt(() -> { rig.panel.refresh(true); String message = rig.panel.statusText(); delivery.run();
                check(message.equals(rig.panel.statusText()) && !rig.enabled(Direction.UP), "Recibo viejo tras Reset ignorado"); });
        }
    }

    public static void stopInvalidatesDelayedReceipt() throws Exception {
        try (var rig = new Rig(5, true)) {
            rig.start(); rig.select(4); rig.click(Direction.UP); var delivery = rig.delivery();
            rig.c.stop().get(5, TimeUnit.SECONDS);
            edt(() -> { rig.panel.refresh(true); String message = rig.panel.statusText(); delivery.run();
                check(message.equals(rig.panel.statusText()) && !rig.enabled(Direction.UP), "Recibo viejo tras Stop ignorado"); });
        }
    }

    public static void closeInvalidatesDelayedReceipt() throws Exception {
        try (var rig = new Rig(5, true)) {
            rig.start(); rig.select(4); rig.click(Direction.UP); var delivery = rig.delivery();
            rig.c.closeAsync().get(5, TimeUnit.SECONDS);
            edt(() -> { rig.panel.refresh(false); String message = rig.panel.statusText(); delivery.run();
                check(message.equals(rig.panel.statusText()) && !rig.enabled(Direction.UP), "Recibo viejo tras cierre ignorado"); });
        }
    }

    public static void newerCallMessageWinsAgainstOutOfOrderCallbacks() throws Exception {
        try (var rig = new Rig(5, true)) {
            rig.start(); rig.select(2); rig.click(Direction.UP); var first = rig.delivery();
            rig.select(3); rig.click(Direction.DOWN); var second = rig.delivery();
            edt(() -> { second.run(); String message = rig.panel.statusText(); first.run();
                check(message.equals(rig.panel.statusText()) && message.contains("Piso 3 para bajar"), "No retrocede mensaje"); });
        }
    }

    public static void rebindingSessionInvalidatesOldReceipt() throws Exception {
        try (var rig = new Rig(5, true); var another = new SimulationController(new SimulationConfig(1, 3, 20, 5), 0)) {
            try {
                rig.start(); rig.select(4); rig.click(Direction.UP); var delivery = rig.delivery();
                edt(() -> { rig.panel.bind(another); rig.panel.refresh(true); String message = rig.panel.statusText(); delivery.run();
                    check(message.equals(rig.panel.statusText()) && !rig.enabled(Direction.UP), "Otra sesión no recibe mensaje anterior"); });
            } finally { another.closeAsync().get(5, TimeUnit.SECONDS); check(another.awaitTermination(Duration.ofSeconds(5)), "Otra sesión cerrada"); }
        }
    }

    public static void invalidTypedFloorDoesNotSubmitPreviousValue() throws Exception {
        try (var rig = new Rig()) {
            rig.start(); rig.select(3); rig.type("abc"); rig.click(Direction.UP);
            edt(() -> check(rig.panel.statusText().contains("entero"), "Error visible"));
            check(rig.c.calls().isEmpty(), "No envía el valor anterior");
        }
    }

    public static void decimalAndOverflowTextAreRejected() throws Exception {
        try (var rig = new Rig()) {
            rig.start(); rig.select(3);
            for (String text : new String[] {"2.5", "2147483648", "1e2", ""}) {
                rig.type(text);
                edt(() -> {
                    try { ((JSpinner.NumberEditor) rig.panel.floorSelector().getEditor()).getTextField().commitEdit();
                        throw new AssertionError("El editor aceptó un no entero"); }
                    catch (java.text.ParseException expected) { }
                });
                rig.click(Direction.UP);
                edt(() -> check(rig.panel.statusText().contains("entero"), "Rechazo numérico"));
            }
            check(rig.c.calls().isEmpty(), "Sin llamadas truncadas");
        }
    }

    public static void outOfRangeTypedFloorDoesNotSubmit() throws Exception {
        try (var rig = new Rig()) {
            rig.start(); rig.select(3);
            for (String text : new String[] {"0", "-1", "6"}) {
                rig.type(text); rig.click(Direction.UP);
                edt(() -> check(rig.panel.statusText().contains("no permite"), "Fuera de rango visible"));
            }
            check(rig.c.calls().isEmpty(), "No hay pisos ilegales");
        }
    }

    public static void validTypedFloorIsUsedForRequest() throws Exception {
        try (var rig = new Rig()) {
            rig.start(); rig.select(2); rig.type("4"); rig.click(Direction.UP);
            waitFor(() -> rig.c.calls().size() == 1);
            check(rig.c.calls().values().iterator().next().request().getFloor() == 4, "Usa entrada confirmada, no valor viejo");
            rig.select(5); rig.type("2");
            edt(() -> {
                try { ((JSpinner.NumberEditor) rig.panel.floorSelector().getEditor()).getTextField().commitEdit(); }
                catch (java.text.ParseException error) { throw new AssertionError(error); }
                check(rig.enabled(Direction.UP), "Confirmar piso habilita botón desde el extremo");
            });
        }
    }

    public static void forbiddenTypedDirectionCannotSend() throws Exception {
        try (var rig = new Rig()) {
            rig.start(); rig.select(3); rig.type("5"); rig.click(Direction.UP);
            check(rig.c.calls().isEmpty(), "No subir desde techo aunque texto no estuviera confirmado");
            edt(() -> check(!rig.enabled(Direction.UP), "Extremo digitado deshabilita Subir"));
            rig.select(3); rig.type("1"); rig.click(Direction.DOWN);
            check(rig.c.calls().isEmpty(), "No bajar desde piso 1");
            edt(() -> check(!rig.enabled(Direction.DOWN), "Extremo digitado deshabilita Bajar"));
        }
    }

    public static void panelRejectsOffEdtAccess() throws Exception {
        try { new HallCallPanel(); throw new AssertionError("Construcción fuera EDT"); }
        catch (IllegalStateException expected) { }
        try (var rig = new Rig()) {
            try { rig.panel.refresh(true); throw new AssertionError("Refresco fuera EDT"); }
            catch (IllegalStateException expected) { }
            try { rig.panel.bind(rig.c); throw new AssertionError("Vinculación fuera EDT"); }
            catch (IllegalStateException expected) { }
        }
    }

    public static void largeBuildingUsesBoundedNumberOfComponents() throws Exception {
        try (var rig = new Rig(Integer.MAX_VALUE, false)) {
            rig.start(); rig.select(Integer.MAX_VALUE);
            edt(() -> check(!rig.enabled(Direction.UP) && rig.enabled(Direction.DOWN)
                    && rig.panel.getComponentCount() == 2, "Selector fijo independiente de P"));
        }
    }

    public static void parentControlBlocksCallsImmediately() throws Exception {
        withSystem((panel, c) -> {
            edt(() -> {
                panel.hallCallPanel().floorSelector().setValue(3);
                check(panel.hallCallPanel().button(Direction.UP).isEnabled(), "RUNNING habilitado");
                panel.button(SystemControlPanel.Action.STOP).doClick(0);
                check(!panel.hallCallPanel().button(Direction.UP).isEnabled(), "Bloqueo inmediato antes de callback Stop");
            });
        });
    }

    public static void fullWindowCompositionCanCallWithoutImporting() throws Exception {
        withSystem((panel, c) -> {
            edt(() -> { panel.hallCallPanel().floorSelector().setValue(3); panel.hallCallPanel().button(Direction.UP).doClick(0); });
            waitFor(() -> c.calls().size() == 1);
            waitFor(() -> c.calls().values().stream().allMatch(call -> call.status() == HallCallSnapshot.Status.SERVED));
            check(c.preparedBatch().isEmpty(), "Admisión manual sin archivo");
        });
    }

    public static void parentCloseDisablesHallControls() throws Exception {
        withSystem((panel, c) -> {
            var closed = onEdt(() -> {
                var future = panel.closeAsync();
                check(!panel.hallCallPanel().button(Direction.UP).isEnabled()
                        && !panel.hallCallPanel().floorSelector().isEnabled(), "Cierre bloquea panel");
                return future;
            });
            closed.get(5, TimeUnit.SECONDS);
        });
    }

    private static final class Rig implements AutoCloseable {
        final SimulationController c;
        final HallCallPanel panel;
        final BlockingQueue<Runnable> deliveries = new LinkedBlockingQueue<>();
        Rig() { this(5, false); }
        Rig(int floors, boolean delayed) {
            c = new SimulationController(new SimulationConfig(2, floors, 100, 10), 0);
            panel = onEdt(() -> {
                var p = delayed ? new HallCallPanel(deliveries::add) : new HallCallPanel(); p.bind(c); return p;
            });
        }
        void start() throws Exception { c.start().get(5, TimeUnit.SECONDS); edt(() -> panel.refresh(true)); }
        void select(int number) throws Exception { edt(() -> panel.floorSelector().setValue(number)); }
        void type(String text) throws Exception { edt(() -> ((JSpinner.NumberEditor) panel.floorSelector().getEditor()).getTextField().setText(text)); }
        boolean enabled(Direction direction) { return panel.button(direction).isEnabled(); }
        void click(Direction direction) throws Exception { edt(() -> panel.button(direction).doClick(0)); }
        Runnable delivery() throws Exception {
            Runnable delivery = deliveries.poll(5, TimeUnit.SECONDS); check(delivery != null, "Callback recibido"); return delivery;
        }
        @Override public void close() throws Exception {
            c.closeAsync().get(5, TimeUnit.SECONDS);
            check(c.awaitTermination(Duration.ofSeconds(5)), "Hilos cerrados");
            edt(() -> panel.refresh(false));
        }
    }
    @FunctionalInterface private interface SystemCheck { void run(SystemControlPanel panel, SimulationController c) throws Exception; }
    private static void withSystem(SystemCheck check) throws Exception {
        var panel = onEdt(() -> new SystemControlPanel(config -> new SimulationController(config, 0), Optional::empty));
        try {
            edt(() -> {
                var form = panel.configurationPanel(); String[] values = {"2", "5", "50", "10"};
                for (var field : Field.values()) form.input(field).setText(values[field.ordinal()]);
                form.validateConfiguration();
            });
            waitFor(() -> onEdt(() -> panel.controller().isPresent()));
            edt(() -> panel.button(SystemControlPanel.Action.START).doClick(0));
            waitFor(() -> onEdt(() -> { panel.updateView(); return panel.hallCallPanel().button(Direction.UP).isEnabled(); }));
            check.run(panel, onEdt(() -> panel.controller().orElseThrow()));
        } finally {
            onEdt(panel::closeAsync).get(5, TimeUnit.SECONDS);
            var c = onEdt(() -> panel.controller().orElse(null));
            if (c != null) check(c.awaitTermination(Duration.ofSeconds(5)), "Sesión compuesta cerrada");
            edt(panel::closed);
        }
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
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
        unboundPanelDisablesCalls();
        resetSessionDisablesCalls();
        bottomFloorAllowsOnlyUp();
        upperFloorAllowsOnlyDown();
        interiorFloorAllowsBothDirections();
        singleFloorDisablesBothDirections();
        spinnerReflectsConfiguredFloorBounds();
        upCallGoesThroughManagerAndIsServed();
        downCallGoesThroughManagerAndIsServed();
        oppositeCallsAtSameFloorRemainDistinct();
        repeatedClicksUseManagerDeduplication();
        enqueuedReceiptDoesNotClaimService();
        stopDisablesAndResumeRestoresCalls();
        resetInvalidatesDelayedReceipt();
        stopInvalidatesDelayedReceipt();
        closeInvalidatesDelayedReceipt();
        newerCallMessageWinsAgainstOutOfOrderCallbacks();
        rebindingSessionInvalidatesOldReceipt();
        invalidTypedFloorDoesNotSubmitPreviousValue();
        decimalAndOverflowTextAreRejected();
        outOfRangeTypedFloorDoesNotSubmit();
        validTypedFloorIsUsedForRequest();
        forbiddenTypedDirectionCannotSend();
        panelRejectsOffEdtAccess();
        largeBuildingUsesBoundedNumberOfComponents();
        parentControlBlocksCallsImmediately();
        fullWindowCompositionCanCallWithoutImporting();
        parentCloseDisablesHallControls();
        System.out.println("T49: 28 escenarios satisfactorios.");
    }
}
```

## src/test/java/com/elevatormanager/ui/HallCallsTest.java

```java
package com.elevatormanager.ui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Adaptadores JUnit T49. */
@Timeout(30)
class HallCallsTest {
    @Test void unboundPanelDisablesCalls() throws Exception {
        HallCallsScenarios.unboundPanelDisablesCalls();
    }
    @Test void resetSessionDisablesCalls() throws Exception {
        HallCallsScenarios.resetSessionDisablesCalls();
    }
    @Test void bottomFloorAllowsOnlyUp() throws Exception {
        HallCallsScenarios.bottomFloorAllowsOnlyUp();
    }
    @Test void upperFloorAllowsOnlyDown() throws Exception {
        HallCallsScenarios.upperFloorAllowsOnlyDown();
    }
    @Test void interiorFloorAllowsBothDirections() throws Exception {
        HallCallsScenarios.interiorFloorAllowsBothDirections();
    }
    @Test void singleFloorDisablesBothDirections() throws Exception {
        HallCallsScenarios.singleFloorDisablesBothDirections();
    }
    @Test void spinnerReflectsConfiguredFloorBounds() throws Exception {
        HallCallsScenarios.spinnerReflectsConfiguredFloorBounds();
    }
    @Test void upCallGoesThroughManagerAndIsServed() throws Exception {
        HallCallsScenarios.upCallGoesThroughManagerAndIsServed();
    }
    @Test void downCallGoesThroughManagerAndIsServed() throws Exception {
        HallCallsScenarios.downCallGoesThroughManagerAndIsServed();
    }
    @Test void oppositeCallsAtSameFloorRemainDistinct() throws Exception {
        HallCallsScenarios.oppositeCallsAtSameFloorRemainDistinct();
    }
    @Test void repeatedClicksUseManagerDeduplication() throws Exception {
        HallCallsScenarios.repeatedClicksUseManagerDeduplication();
    }
    @Test void enqueuedReceiptDoesNotClaimService() throws Exception {
        HallCallsScenarios.enqueuedReceiptDoesNotClaimService();
    }
    @Test void stopDisablesAndResumeRestoresCalls() throws Exception {
        HallCallsScenarios.stopDisablesAndResumeRestoresCalls();
    }
    @Test void resetInvalidatesDelayedReceipt() throws Exception {
        HallCallsScenarios.resetInvalidatesDelayedReceipt();
    }
    @Test void stopInvalidatesDelayedReceipt() throws Exception {
        HallCallsScenarios.stopInvalidatesDelayedReceipt();
    }
    @Test void closeInvalidatesDelayedReceipt() throws Exception {
        HallCallsScenarios.closeInvalidatesDelayedReceipt();
    }
    @Test void newerCallMessageWinsAgainstOutOfOrderCallbacks() throws Exception {
        HallCallsScenarios.newerCallMessageWinsAgainstOutOfOrderCallbacks();
    }
    @Test void rebindingSessionInvalidatesOldReceipt() throws Exception {
        HallCallsScenarios.rebindingSessionInvalidatesOldReceipt();
    }
    @Test void invalidTypedFloorDoesNotSubmitPreviousValue() throws Exception {
        HallCallsScenarios.invalidTypedFloorDoesNotSubmitPreviousValue();
    }
    @Test void decimalAndOverflowTextAreRejected() throws Exception {
        HallCallsScenarios.decimalAndOverflowTextAreRejected();
    }
    @Test void outOfRangeTypedFloorDoesNotSubmit() throws Exception {
        HallCallsScenarios.outOfRangeTypedFloorDoesNotSubmit();
    }
    @Test void validTypedFloorIsUsedForRequest() throws Exception {
        HallCallsScenarios.validTypedFloorIsUsedForRequest();
    }
    @Test void forbiddenTypedDirectionCannotSend() throws Exception {
        HallCallsScenarios.forbiddenTypedDirectionCannotSend();
    }
    @Test void panelRejectsOffEdtAccess() throws Exception {
        HallCallsScenarios.panelRejectsOffEdtAccess();
    }
    @Test void largeBuildingUsesBoundedNumberOfComponents() throws Exception {
        HallCallsScenarios.largeBuildingUsesBoundedNumberOfComponents();
    }
    @Test void parentControlBlocksCallsImmediately() throws Exception {
        HallCallsScenarios.parentControlBlocksCallsImmediately();
    }
    @Test void fullWindowCompositionCanCallWithoutImporting() throws Exception {
        HallCallsScenarios.fullWindowCompositionCanCallWithoutImporting();
    }
    @Test void parentCloseDisablesHallControls() throws Exception {
        HallCallsScenarios.parentCloseDisablesHallControls();
    }
}
```
