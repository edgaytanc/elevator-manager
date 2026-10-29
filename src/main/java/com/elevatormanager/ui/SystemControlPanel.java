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
import javax.swing.JSplitPane;

/** T51: controles, cabinas y visor reciente acotado; ningún get/join, lectura o apertura en EDT. */
public final class SystemControlPanel extends JPanel {
    private static final long serialVersionUID = 1L;
    public enum Action { START, STOP, RESET, LOAD, CLOSE }
    @FunctionalInterface interface SessionOpener {
        SimulationController open(SimulationConfig config) throws Exception;
    }
    private final ConfigurationPanel configuration;
    private final HallCallPanel hallCalls;
    private final CabinDashboardPanel cabinDashboard;
    private final EventViewerPanel eventViewer;
    private final EnumMap<Action, JButton> buttons = new EnumMap<>(Action.class);
    private final JLabel stateLabel = new JLabel();
    private final JTextArea detail = new JTextArea(2, 54);
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
        cabinDashboard = new CabinDashboardPanel();
        workspace.setMinimumSize(new java.awt.Dimension(350, 300));
        var split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, workspace, cabinDashboard);
        split.setResizeWeight(0.45);
        var workspaceScroll = new JScrollPane(split);
        workspaceScroll.setMinimumSize(new java.awt.Dimension(200, 120));
        eventViewer = new EventViewerPanel();
        var vertical = new JSplitPane(JSplitPane.VERTICAL_SPLIT, workspaceScroll, eventViewer);
        vertical.setResizeWeight(0.8);
        vertical.setContinuousLayout(true);
        add(vertical, BorderLayout.CENTER);
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
        var detailScroll = new JScrollPane(detail);
        detailScroll.setPreferredSize(new java.awt.Dimension(400, 48));
        controls.add(detailScroll, BorderLayout.SOUTH);
        add(controls, BorderLayout.NORTH);
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
                cabinDashboard.bind(session);
                eventViewer.bind(session);
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

    /** Lee estado y snapshots publicados; no consume eventos ni espera futuros. */
    void updateView() {
        requireEdt();
        SimulationState state = controller == null ? null : controller.state();
        boolean usable = controller != null && !preparing && !closing && !terminal;
        boolean idle = pending == null;
        hallCalls.refresh(usable && idle && !loading);
        cabinDashboard.refresh(usable && idle && !loading);
        eventViewer.refresh();
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
    EventViewerPanel eventViewer() { requireEdt(); return eventViewer; }
    CabinDashboardPanel cabinDashboard() { requireEdt(); return cabinDashboard; }
    HallCallPanel hallCallPanel() { requireEdt(); return hallCalls; }
    ConfigurationPanel configurationPanel() { requireEdt(); return configuration; }
    JButton button(Action action) { requireEdt(); return buttons.get(action); }
    String stateText() { requireEdt(); return stateLabel.getText(); }
    String detailText() { requireEdt(); return detail.getText(); }

    private static void requireEdt() {
        if (!SwingUtilities.isEventDispatchThread()) { throw new IllegalStateException("Los controles requieren EDT."); }
    }
}
