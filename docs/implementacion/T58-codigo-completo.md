# T58 — Código Java completo

Archivos íntegros de producción y pruebas añadidos/modificados por T58. No contiene parches ni fragmentos. El ZIP incluye además todas las fuentes anteriores.

## src/main/java/com/elevatormanager/ui/BuildingPanel.java

```java
package com.elevatormanager.ui;

import com.elevatormanager.model.ElevatorSnapshot;
import com.elevatormanager.model.SimulationConfig;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import javax.swing.JPanel;
import javax.swing.JViewport;
import javax.swing.Scrollable;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;

/** T58: edificio virtual, sin un componente por piso ni acceso al estado mutable.
 * Muestra el último piso confirmado; la interpolación corresponde a T59.
 */
public final class BuildingPanel extends JPanel implements Scrollable {
    private static final long serialVersionUID = 1L;
    static final Color CABIN_COLOR = new Color(34, 100, 176);
    private UUID sessionId;
    private SimulationConfig config;
    private List<ElevatorSnapshot> snapshots = List.of();
    private UiSnapshotPump.Frame rendered;

    public BuildingPanel() {
        requireEdt();
        setBackground(Color.WHITE);
        getAccessibleContext().setAccessibleName("Edificio: pisos y cabinas identificados por número");
    }

    public void bind(UUID session, SimulationConfig configuration) {
        requireEdt();
        sessionId = Objects.requireNonNull(session, "session");
        config = Objects.requireNonNull(configuration, "configuration");
        snapshots = List.of(); rendered = null;
        revalidate(); repaint();
    }

    /** Solo acepta la sesión/época vigente. Reutiliza el conjunto inmutable de T52. */
    public void render(UiSnapshotPump.Frame frame, long currentEpoch) {
        requireEdt();
        if (config == null || !frame.sessionId().equals(sessionId) || frame.epoch() != currentEpoch
                || frame.snapshots().size() != config.elevatorCount() || frame == rendered) return;
        snapshots = frame.snapshots(); rendered = frame;
        repaint();
    }

    @Override public Dimension getPreferredSize() {
        if (config == null) return new Dimension(600, 320);
        return new Dimension(extent(config.elevatorCount(), 112, Geometry.LEFT + Geometry.RIGHT),
                extent(config.floorCount(), 36, Geometry.TOP + Geometry.BOTTOM));
    }

    /** Satura con long antes de convertir: las cantidades válidas no desbordan Swing. */
    private static int extent(int count, int unit, int margin) {
        return (int) Math.min(Integer.MAX_VALUE - 1024L, (long) count * unit + margin);
    }

    Geometry geometry() {
        requireEdt();
        if (config == null) throw new IllegalStateException("Edificio sin configurar.");
        var minimum = getPreferredSize();
        return new Geometry(config.elevatorCount(), config.floorCount(),
                Math.max(getWidth(), minimum.width), Math.max(getHeight(), minimum.height));
    }

    @Override protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        var g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(new Color(32, 46, 64));
            if (config == null) {
                g.drawString("Valida la configuración para mostrar el edificio.", 16, 30); return;
            }
            var layout = geometry();
            Rectangle clip = g.getClipBounds();
            if (clip == null) clip = new Rectangle(0, 0, getWidth(), getHeight());
            int firstFloor = layout.floorAt((double) clip.y + clip.height);
            int lastFloor = layout.floorAt(clip.y);
            for (int floor = firstFloor; ; floor++) {
                int y = layout.floorY(floor);
                g.setColor(new Color(222, 228, 235));
                g.drawLine(Geometry.LEFT, y, layout.width() - Geometry.RIGHT, y);
                g.setColor(new Color(32, 46, 64));
                g.drawString("Piso " + floor, 8, y + 5);
                if (floor == lastFloor) break;
            }
            int firstCabin = layout.cabinAt(clip.x);
            int lastCabin = layout.cabinAt((double) clip.x + clip.width);
            for (int id = firstCabin; ; id++) {
                int x = layout.cabinX(id);
                int shaftWidth = Math.max(1, (int) Math.min(96, layout.laneWidth() - 12));
                g.setColor(new Color(231, 237, 244));
                g.fillRect(x - shaftWidth / 2, Geometry.TOP, shaftWidth,
                        layout.height() - Geometry.TOP - Geometry.BOTTOM);
                g.setColor(new Color(32, 46, 64));
                String title = "Elevador " + id;
                g.drawString(title, x - g.getFontMetrics().stringWidth(title) / 2, 25);
                int floor = displayedFloor(id);
                Rectangle cabin = layout.cabinBounds(id, floor);
                if (clip.intersects(cabin)) {
                    g.setColor(CABIN_COLOR);
                    g.fillRoundRect(cabin.x, cabin.y, cabin.width, cabin.height, 8, 8);
                    g.setColor(Color.WHITE);
                    String label = "E" + id;
                    g.drawString(label, x - g.getFontMetrics().stringWidth(label) / 2,
                            cabin.y + (cabin.height + g.getFontMetrics().getAscent()) / 2 - 2);
                }
                if (id == lastCabin) break;
            }
        } finally { g.dispose(); }
    }

    int displayedFloor(int id) {
        requireEdt();
        if (config == null || id < 1 || id > config.elevatorCount())
            throw new IllegalArgumentException("Cabina fuera de rango.");
        return snapshots.isEmpty() ? 1 : snapshots.get(id - 1).currentFloor();
    }

    @Override public Dimension getPreferredScrollableViewportSize() { return new Dimension(600, 320); }
    @Override public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) { return 36; }
    @Override public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) {
        return Math.max(36, (orientation == SwingConstants.VERTICAL ? visible.height : visible.width) - 36);
    }
    @Override public boolean getScrollableTracksViewportWidth() {
        return getParent() instanceof JViewport viewport && viewport.getWidth() >= getPreferredSize().width;
    }
    @Override public boolean getScrollableTracksViewportHeight() {
        return getParent() instanceof JViewport viewport && viewport.getHeight() >= getPreferredSize().height;
    }

    /** Coordenadas puras: piso 1 abajo; no divide entre P-1, admite P=1. */
    record Geometry(int cabins, int floors, int width, int height) {
        static final int LEFT = 112, RIGHT = 20, TOP = 48, BOTTOM = 20;
        double laneWidth() { return (width - LEFT - RIGHT) / (double) cabins; }
        double floorHeight() { return (height - TOP - BOTTOM) / (double) floors; }
        int floorY(int floor) { return (int) Math.round(TOP + (floors - floor + 0.5) * floorHeight()); }
        int cabinX(int id) { return (int) Math.round(LEFT + (id - 0.5) * laneWidth()); }
        int floorAt(double y) {
            return (int) Math.max(1, Math.min(floors, floors - Math.floor((y - TOP) / floorHeight())));
        }
        int cabinAt(double x) {
            return (int) Math.max(1, Math.min(cabins, 1 + Math.floor((x - LEFT) / laneWidth())));
        }
        Rectangle cabinBounds(int id, int floor) {
            int w = Math.max(1, (int) Math.min(76, laneWidth() - 24));
            int h = Math.max(1, (int) Math.min(28, floorHeight() * 0.7));
            return new Rectangle(cabinX(id) - w / 2, floorY(floor) - h / 2, w, h);
        }
    }

    private static void requireEdt() {
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("El edificio requiere EDT.");
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
import javax.swing.JSplitPane;

/** T52: captura asíncrona y refresco visual limitado; ningún get/join, lectura o apertura en EDT. */
public final class SystemControlPanel extends JPanel {
    private static final long serialVersionUID = 1L;
    public enum Action { START, PAUSE, RESUME, STOP, RESET, LOAD, CLOSE }
    @FunctionalInterface interface SessionOpener {
        SimulationController open(SimulationConfig config) throws Exception;
    }
    private final ConfigurationPanel configuration;
    private final HallCallPanel hallCalls;
    private final CabinDashboardPanel cabinDashboard;
    private final BuildingPanel buildingPanel;
    private final JScrollPane buildingScroll;
    private final EventViewerPanel eventViewer;
    private final EnumMap<Action, JButton> buttons = new EnumMap<>(Action.class);
    private final JLabel stateLabel = new JLabel();
    private final JTextArea detail = new JTextArea(2, 54);
    private final SessionOpener opener;
    private final java.util.function.Function<SimulationController, UiSnapshotPump> pumpFactory;
    private final Supplier<Optional<Path>> selector;
    private final Timer refresh;
    private final RefreshGate visualGate = new RefreshGate(UiSnapshotPump.INTERVAL_NANOS, System::nanoTime);
    private UiSnapshotPump snapshotPump;
    private Throwable reportedRefreshFailure;
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
        this(opener, selector, UiSnapshotPump::new);
    }

    SystemControlPanel(SessionOpener opener, Supplier<Optional<Path>> selector,
            java.util.function.Function<SimulationController, UiSnapshotPump> pumpFactory) {
        requireEdt();
        this.pumpFactory = Objects.requireNonNull(pumpFactory, "pumpFactory");
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
        buildingPanel = new BuildingPanel();
        buildingScroll = new JScrollPane(buildingPanel);
        buildingScroll.getAccessibleContext().setAccessibleName("Recorrido del edificio");
        var buildingView = new JPanel(new BorderLayout());
        buildingView.add(new JLabel("Ubicación por piso · usa las barras para recorrer el edificio"), BorderLayout.NORTH);
        buildingView.add(buildingScroll, BorderLayout.CENTER);
        var views = new javax.swing.JTabbedPane();
        views.addTab("Cabinas", cabinDashboard);
        views.addTab("Edificio", buildingView);
        views.setSelectedIndex(1);
        views.setMinimumSize(new java.awt.Dimension(330, 250));
        var split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, workspace, views);
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
        String[] titles = {"Start", "Pausar", "Reanudar", "Stop", "Reset", "Cargar archivo", "Cerrar"};
        for (var action : Action.values()) {
            var button = new JButton(titles[action.ordinal()]);
            buttons.put(action, button);
            actions.add(button);
        }
        controls.add(stateLabel, BorderLayout.NORTH);
        var actionScroll = new JScrollPane(actions, JScrollPane.VERTICAL_SCROLLBAR_NEVER,
                JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        actionScroll.setBorder(BorderFactory.createEmptyBorder());
        actionScroll.setPreferredSize(new java.awt.Dimension(500, actions.getPreferredSize().height
                + actionScroll.getHorizontalScrollBar().getPreferredSize().height + 4));
        controls.add(actionScroll, BorderLayout.CENTER);
        detail.setEditable(false);
        detail.setLineWrap(true);
        detail.setWrapStyleWord(true);
        var detailScroll = new JScrollPane(detail);
        detailScroll.setPreferredSize(new java.awt.Dimension(400, 48));
        controls.add(detailScroll, BorderLayout.SOUTH);
        add(controls, BorderLayout.NORTH);
        buttons.get(Action.START).addActionListener(event -> command(Action.START));
        buttons.get(Action.PAUSE).addActionListener(event -> command(Action.PAUSE));
        buttons.get(Action.RESUME).addActionListener(event -> command(Action.RESUME));
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
                buildingPanel.bind(session.sessionId(), session.config());
                SwingUtilities.invokeLater(() -> {
                    if (!closing && !terminal && controller == session) {
                        // Al abrir un edificio alto se presenta el piso inicial.
                        buildingScroll.getViewport().setViewPosition(new java.awt.Point(0,
                                Math.max(0, buildingPanel.getPreferredSize().height
                                - buildingScroll.getViewport().getExtentSize().height)));
                    }
                });
                snapshotPump = Objects.requireNonNull(pumpFactory.apply(session), "snapshotPump");
                cabinDashboard.usePump(snapshotPump); snapshotPump.request();
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
            case PAUSE -> "Pausando: esperando confirmación del manejador y las cabinas...";
            case RESUME -> "Reanudando el tiempo restante...";
            case STOP -> "Deteniendo: esperando confirmación de las cabinas...";
            case RESET -> "Reseteando: limpiando solicitudes y retornando al piso 1...";
            default -> throw new IllegalArgumentException("Control desconocido.");
        });
        updateView();
        var result = switch (action) {
            case START -> controller.start();
            case PAUSE -> controller.pause();
            case RESUME -> controller.resume();
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
        var samplerClosed = snapshotPump == null ? CompletableFuture.<Void>completedFuture(null) : snapshotPump.closeAsync();
        var engineClosed = opened.thenCompose(session -> session == null ? CompletableFuture.completedFuture(null)
                : session.closeAsync().thenApply(result -> (Void) null));
        return CompletableFuture.allOf(engineClosed, samplerClosed)
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
        cabinDashboard.setInteractive(usable && idle && !loading);
        if (visualGate.due()) {
            if (snapshotPump != null && !closing && !terminal) {
                snapshotPump.request();
                snapshotPump.failure().ifPresent(error -> {
                    if (reportedRefreshFailure != error) {
                        reportedRefreshFailure = error;
                        message("No se pudo actualizar la vista: " + cause(error));
                    }
                });
            }
            cabinDashboard.refresh(usable && idle && !loading);
            if (snapshotPump != null && controller != null) {
                snapshotPump.latest().ifPresent(data -> buildingPanel.render(data, controller.epoch()));
            }
            eventViewer.refresh();
        }
        buttons.get(Action.START).setEnabled(usable && idle && !loading
                && (state == SimulationState.RESET || state == SimulationState.STOPPED));
        buttons.get(Action.PAUSE).setEnabled(usable && idle && !loading && state == SimulationState.RUNNING);
        buttons.get(Action.RESUME).setEnabled(usable && idle && !loading && state == SimulationState.PAUSED);
        boolean canOverridePause = idle || pending == Action.PAUSE || pending == Action.RESUME;
        buttons.get(Action.STOP).setEnabled(usable && canOverridePause
                && (state == SimulationState.RUNNING || state == SimulationState.PAUSING || state == SimulationState.PAUSED));
        buttons.get(Action.RESET).setEnabled(usable && pending != Action.RESET && pending != Action.START
                && (state == SimulationState.RESET || state == SimulationState.RUNNING
                || state == SimulationState.STOPPING || state == SimulationState.STOPPED
                || state == SimulationState.PAUSING || state == SimulationState.PAUSED));
        buttons.get(Action.LOAD).setEnabled(usable && idle && !loading && state == SimulationState.RESET);
        buttons.get(Action.CLOSE).setEnabled(!closing && !disposed);
        String current = closing ? "CLOSING — Cerrando..." : preparing ? "Preparando sesión..."
                : state == null ? "SIN CONFIGURAR" : switch (state) {
                    case PAUSING -> "PAUSING — Pausando... " + controller.pauseStatus().acknowledged()
                            + "/" + controller.pauseStatus().participants();
                    case STOPPING -> "STOPPING — Deteniendo...";
                    case RESETTING -> "RESETTING — Reseteando...";
                    case CLOSING -> "CLOSING — Cerrando...";
                    default -> state.name();
                };
        if (!closing && pending == Action.PAUSE && state != SimulationState.PAUSED && state != SimulationState.PAUSING) {
            current = "PAUSING — Pausando...";
        }
        if (!closing && pending == Action.RESUME) { current = "Reanudando..."; }
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
    BuildingPanel buildingPanel() { requireEdt(); return buildingPanel; }
    UiSnapshotPump snapshotPump() { requireEdt(); return snapshotPump; }
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
```

## src/test/java/com/elevatormanager/ui/BuildingPanelScenarios.java

```java
package com.elevatormanager.ui;

import com.elevatormanager.model.*;
import com.elevatormanager.model.request.CabinRequest;
import com.elevatormanager.simulation.SimulationController;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import javax.swing.*;

/** T58: geometría, raster real, desplazamiento e integración sin JFrame. */
public final class BuildingPanelScenarios {
    private BuildingPanelScenarios() { }
    private static final UUID SESSION = UUID.randomUUID();

    public static void singleFloorIsCentered() throws Exception { edt(() -> {
        var p = panel(1, 1, 600, 320);
        var g = p.geometry();
        check(g.floorY(1) == 174, "P=1 centrado en la zona útil, sin dividir entre cero");
        Rectangle cabin = g.cabinBounds(1, 1);
        check(new Rectangle(0, 0, 600, 320).contains(cabin), "Cabina dentro del panel");
        check(p.displayedFloor(1) == 1, "Estado inicial en piso 1");
    }); }

    public static void floorsAndCabinsHaveOrderedCoordinates() throws Exception { edt(() -> {
        var p = panel(4, 8, 800, 500); var g = p.geometry();
        for (int floor = 1; floor < 8; floor++) check(g.floorY(floor) > g.floorY(floor + 1), "Orden vertical");
        for (int id = 1; id < 4; id++) {
            check(g.cabinX(id) < g.cabinX(id + 1), "Orden horizontal");
            check(!g.cabinBounds(id, 1).intersects(g.cabinBounds(id + 1, 1)), "Cabinas separadas");
        }
        for (int floor = 1; floor <= 8; floor++) check(g.floorAt(g.floorY(floor)) == floor, "Piso identificable");
    }); }

    public static void smallBuildingExpandsWithViewport() throws Exception { edt(() -> {
        var p = panel(2, 3, 800, 500);
        var viewport = new JViewport(); viewport.setSize(800, 500); viewport.setView(p); viewport.doLayout();
        check(p.getScrollableTracksViewportWidth() && p.getScrollableTracksViewportHeight(), "Escala al espacio");
        int y = p.geometry().floorY(1);
        viewport.setSize(1000, 700); viewport.doLayout();
        check(p.geometry().floorY(1) > y && p.getWidth() == 1000, "Geometría recalculada al redimensionar");
    }); }

    public static void largeBuildingScrollsToLastCabinAndGroundFloor() throws Exception { edt(() -> {
        var p = panel(20, 100, 600, 320);
        var scroll = new JScrollPane(p); scroll.setSize(600, 320); scroll.doLayout(); scroll.getViewport().doLayout();
        check(!p.getScrollableTracksViewportWidth() && !p.getScrollableTracksViewportHeight(), "Dos ejes desplazables");
        Rectangle last = p.geometry().cabinBounds(20, 1);
        p.scrollRectToVisible(last);
        check(scroll.getViewport().getViewRect().contains(last), "Última cabina y piso 1 alcanzables");
        check(p.getComponentCount() == 0, "Sin controles por piso o cabina");
    }); }

    public static void paintsEveryCabinAtItsOwnFloor() throws Exception { edt(() -> {
        var p = panel(3, 6, 600, 360);
        p.render(frame(new SimulationConfig(3, 6, 100, 100), 0, 1, 3, 6), 0);
        var image = paint(p, new Rectangle(0, 0, 600, 360));
        for (int id = 1; id <= 3; id++) {
            Rectangle b = p.geometry().cabinBounds(id, p.displayedFloor(id));
            check(image.getRGB(b.x + 5, b.y + 5) == BuildingPanel.CABIN_COLOR.getRGB(), "Raster cabina " + id);
        }
        check(p.displayedFloor(2) == 3 && p.displayedFloor(3) == 6, "Posiciones independientes");
    }); }

    public static void clippedPaintingReachesDistantCoordinates() throws Exception { edt(() -> {
        var p = panel(40, 200, 600, 320); var g = p.geometry();
        Rectangle b = g.cabinBounds(40, 1);
        var image = paint(p, new Rectangle(b.x - 5, b.y - 5, 120, 70));
        check(image.getRGB(10, 10) == BuildingPanel.CABIN_COLOR.getRGB(), "Raster lejano con clip trasladado");
    }); }

    public static void hugeCountsDoNotOverflowOrAllocateControls() throws Exception { edt(() -> {
        var p = panel(Integer.MAX_VALUE, Integer.MAX_VALUE, 600, 320);
        var size = p.getPreferredSize(); var g = p.geometry();
        check(size.width > 0 && size.height > 0, "Dimensiones sin overflow");
        check(g.floorY(1) > g.floorY(Integer.MAX_VALUE), "Extremos conservados");
        check(p.getComponentCount() == 0, "Asignación constante");
        paint(p, new Rectangle(0, 0, 600, 320));
    }); }

    public static void foreignSessionIsRejected() throws Exception { edt(() -> {
        var p = panel(1, 6, 600, 320); var cfg = new SimulationConfig(1, 6, 100, 100);
        var f = frame(cfg, 0, 5);
        p.render(new UiSnapshotPump.Frame(UUID.randomUUID(), 0, f.snapshots(), Map.of()), 0);
        check(p.displayedFloor(1) == 1, "Sesión ajena descartada");
        p.render(f, 0); check(p.displayedFloor(1) == 5, "Sesión vigente aceptada");
    }); }

    public static void oldEpochIsRejectedAndRebindClearsView() throws Exception { edt(() -> {
        var p = panel(1, 6, 600, 320); var cfg = new SimulationConfig(1, 6, 100, 100);
        p.render(frame(cfg, 0, 5), 0);
        p.render(frame(cfg, 0, 2), 1); check(p.displayedFloor(1) == 5, "Época antigua no sustituye la vista");
        p.render(frame(cfg, 1, 1), 1); check(p.displayedFloor(1) == 1, "Nueva época aceptada");
        p.bind(UUID.randomUUID(), cfg); check(p.displayedFloor(1) == 1, "Reenlace limpia referencias");
    }); }

    public static void paintingDoesNotAdvanceLogicalFloor() throws Exception { edt(() -> {
        var p = panel(1, 6, 600, 320); var cfg = new SimulationConfig(1, 6, 100, 100);
        var moving = new ElevatorSnapshot(1, cfg, 0, 1, 2, OptionalInt.of(3), Direction.UP,
                ElevatorPhase.MOVING, Optional.empty(), Optional.empty(), ElevatorSnapshot.Mode.NORMAL,
                100_000_000L, 50_000_000L, 1, OptionalInt.empty(), List.of(), List.of());
        p.render(new UiSnapshotPump.Frame(SESSION, 0, List.of(moving), Map.of()), 0);
        paint(p, new Rectangle(0, 0, 600, 320)); paint(p, new Rectangle(0, 0, 600, 320));
        check(p.displayedFloor(1) == 2 && moving.remainingNanos() == 50_000_000L, "Dibujar no simula tiempo ni llegada");
    }); }

    public static void offEdtAccessIsRejected() throws Exception {
        try { new BuildingPanel(); throw new AssertionError("Constructor fuera del EDT"); }
        catch (IllegalStateException expected) { /* Contrato explícito. */ }
        var task = new FutureTask<>(() -> panel(1, 1, 600, 320)); SwingUtilities.invokeAndWait(task);
        try { task.get().bind(SESSION, new SimulationConfig(1, 1, 100, 100)); throw new AssertionError("bind fuera del EDT"); }
        catch (IllegalStateException expected) { /* Contrato explícito. */ }
    }

    public static void systemControlsPublishRealModelPositions() throws Exception {
        var create = new FutureTask<>(() -> new SystemControlPanel(cfg -> new SimulationController(cfg, 0), Optional::empty));
        SwingUtilities.invokeAndWait(create); var controls = create.get();
        try {
            edt(() -> {
                var c = controls.configurationPanel();
                c.input(SimulationConfig.Field.ELEVATORS).setText("2");
                c.input(SimulationConfig.Field.FLOORS).setText("4");
                c.input(SimulationConfig.Field.MOVEMENT).setText("20");
                c.input(SimulationConfig.Field.DWELL).setText("20");
                c.validateButton().doClick(0);
            });
            var ref = new java.util.concurrent.atomic.AtomicReference<SimulationController>();
            await(() -> {
                try { edt(() -> controls.controller().ifPresent(ref::set)); }
                catch (Exception error) { throw new RuntimeException(error); }
                return ref.get() != null;
            });
            var c = ref.get(); c.start().get(5, TimeUnit.SECONDS);
            c.submit(new CabinRequest(UUID.randomUUID(), 0, 4, Instant.now(), 2)).get(5, TimeUnit.SECONDS);
            await(() -> {
                var shown = new java.util.concurrent.atomic.AtomicBoolean();
                try { edt(() -> { controls.updateView(); shown.set(controls.buildingPanel().displayedFloor(2) == 4); }); }
                catch (Exception error) { throw new RuntimeException(error); }
                return shown.get();
            });
            check(c.snapshot(1).currentFloor() == 1 && c.snapshot(2).currentFloor() == 4, "Modelo conserva independencia");
        } finally {
            var closing = new FutureTask<>(controls::closeAsync); SwingUtilities.invokeAndWait(closing);
            try { closing.get().get(5, TimeUnit.SECONDS); } finally { edt(controls::closed); }
        }
    }

    private static BuildingPanel panel(int cabins, int floors, int width, int height) {
        var p = new BuildingPanel(); p.bind(SESSION, new SimulationConfig(cabins, floors, 100, 100));
        var preferred = p.getPreferredSize(); p.setSize(Math.max(width, preferred.width), Math.max(height, preferred.height));
        return p;
    }
    private static UiSnapshotPump.Frame frame(SimulationConfig cfg, long epoch, int... floors) {
        var snapshots = new java.util.ArrayList<ElevatorSnapshot>();
        for (int i = 0; i < floors.length; i++) snapshots.add(new ElevatorSnapshot(i + 1, cfg, epoch, 0,
                floors[i], OptionalInt.empty(), Direction.UP, ElevatorPhase.IDLE, Optional.empty(), Optional.empty(),
                ElevatorSnapshot.Mode.NORMAL, 0, 0, 0, OptionalInt.empty(), List.of(), List.of()));
        return new UiSnapshotPump.Frame(SESSION, epoch, snapshots, Map.of());
    }
    private static BufferedImage paint(BuildingPanel p, Rectangle clip) {
        var image = new BufferedImage(clip.width, clip.height, BufferedImage.TYPE_INT_RGB);
        var g = image.createGraphics();
        try { g.translate(-clip.x, -clip.y); g.setClip(clip); p.paint(g); } finally { g.dispose(); }
        return image;
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static void edt(Runnable action) throws Exception { SwingUtilities.invokeAndWait(action); }
    private static void await(BooleanSupplier condition) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > end) throw new AssertionError("Plazo agotado");
            Thread.sleep(10);
        }
    }
    public static void main(String[] args) throws Exception {
        singleFloorIsCentered(); floorsAndCabinsHaveOrderedCoordinates(); smallBuildingExpandsWithViewport();
        largeBuildingScrollsToLastCabinAndGroundFloor(); paintsEveryCabinAtItsOwnFloor();
        clippedPaintingReachesDistantCoordinates(); hugeCountsDoNotOverflowOrAllocateControls();
        foreignSessionIsRejected(); oldEpochIsRejectedAndRebindClearsView(); paintingDoesNotAdvanceLogicalFloor();
        offEdtAccessIsRejected(); systemControlsPublishRealModelPositions();
        System.out.println("T58: 12 escenarios satisfactorios.");
    }
}
```

## src/test/java/com/elevatormanager/ui/BuildingPanelTest.java

```java
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
```
