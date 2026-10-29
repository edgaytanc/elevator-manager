# T50 — Código completo

Archivos Java nuevos o modificados, íntegros. El ZIP incluye todos los fuentes previos.

## src/main/java/com/elevatormanager/ui/CabinPanel.java

```java
package com.elevatormanager.ui;

import com.elevatormanager.dispatch.HallCallSnapshot;
import com.elevatormanager.model.Direction;
import com.elevatormanager.model.ElevatorPhase;
import com.elevatormanager.model.ElevatorSnapshot;
import com.elevatormanager.model.SimulationState;
import com.elevatormanager.model.request.CabinRequest;
import com.elevatormanager.simulation.SimulationController;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;
import javax.swing.*;

/** T50: una cabina real, sin modificar agendas desde Swing ni encender por mero envío. */
public final class CabinPanel extends JPanel {
    private static final long serialVersionUID = 1L;
    static final int PAGE_SIZE = 20;
    private final SimulationController session;
    private final int cabinId;
    private final Consumer<Runnable> postToEdt;
    private final JLabel location = new JLabel();
    private final JLabel direction = new JLabel();
    private final JLabel phase = new JLabel();
    private final JTextArea internal = textArea(2);
    private final JTextArea feedback = textArea(2);
    private final DefaultListModel<String> external = new DefaultListModel<>();
    private final JPanel destinations = new JPanel(new GridLayout(0, 4, 6, 6));
    private final Map<Integer, JButton> buttons = new LinkedHashMap<>();
    private final Set<Integer> lit = new TreeSet<>();
    private final int pageCount;
    private final JSpinner page;
    private final JButton previous = new JButton("Anterior");
    private final JButton next = new JButton("Siguiente");
    private boolean available;
    private long epoch = -1;
    private long sequence;
    private ElevatorSnapshot latest;

    public CabinPanel(SimulationController session, int cabinId) { this(session, cabinId, SwingUtilities::invokeLater); }

    CabinPanel(SimulationController session, int cabinId, Consumer<Runnable> postToEdt) {
        requireEdt();
        this.session = Objects.requireNonNull(session, "session");
        if (cabinId < 1 || cabinId > session.config().elevatorCount()) { throw new IllegalArgumentException("Cabina inexistente."); }
        this.cabinId = cabinId;
        this.postToEdt = Objects.requireNonNull(postToEdt, "postToEdt");
        pageCount = (session.config().floorCount() - 1) / PAGE_SIZE + 1;
        page = new JSpinner(new SpinnerNumberModel(1, 1, pageCount, 1));
        page.setEditor(new JSpinner.NumberEditor(page, "0"));
        page.getAccessibleContext().setAccessibleName("Página de destinos del elevador " + cabinId);
        setLayout(new BorderLayout(8, 8));
        setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        var header = new JPanel(new GridLayout(0, 1, 0, 4));
        header.add(new JLabel("Elevador " + cabinId));
        header.add(location); header.add(direction); header.add(phase);
        add(header, BorderLayout.NORTH);
        var body = new JPanel(); body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        var navigation = new JPanel(new FlowLayout(FlowLayout.LEFT));
        navigation.add(new JLabel("Página de pisos:")); navigation.add(page); navigation.add(previous); navigation.add(next);
        navigation.setAlignmentX(0f);
        navigation.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
        body.add(navigation);
        destinations.setAlignmentX(0f);
        destinations.setBorder(BorderFactory.createTitledBorder("Destinos internos — amarillo = pendiente"));
        body.add(destinations);
        var internalLabel = new JLabel("Destinos internos pendientes (todos los pisos):");
        internalLabel.setAlignmentX(0f); body.add(internalLabel);
        var internalScroll = new JScrollPane(internal);
        internalScroll.setAlignmentX(0f);
        internalScroll.setPreferredSize(new Dimension(380, 60));
        internalScroll.setMaximumSize(new Dimension(Integer.MAX_VALUE, 80));
        body.add(internalScroll);
        var externalLabel = new JLabel("Llamadas externas asignadas — piso y sentido:");
        externalLabel.setAlignmentX(0f); body.add(externalLabel);
        var list = new JList<>(external); list.setVisibleRowCount(4);
        list.getAccessibleContext().setAccessibleName("Llamadas externas del elevador " + cabinId);
        var externalScroll = new JScrollPane(list);
        externalScroll.setAlignmentX(0f);
        externalScroll.setPreferredSize(new Dimension(380, 90));
        body.add(externalScroll);
        add(body, BorderLayout.CENTER);
        var feedbackScroll = new JScrollPane(feedback);
        feedbackScroll.setPreferredSize(new Dimension(380, 54));
        add(feedbackScroll, BorderLayout.SOUTH);
        page.addChangeListener(event -> rebuildButtons());
        previous.addActionListener(event -> page.setValue(((Number) page.getValue()).intValue() - 1));
        next.addActionListener(event -> page.setValue(((Number) page.getValue()).intValue() + 1));
        rebuildButtons();
        message("Pulsa Start para habilitar los destinos internos.");
    }

    private static JTextArea textArea(int rows) {
        var area = new JTextArea(rows, 32);
        area.setEditable(false); area.setLineWrap(true); area.setWrapStyleWord(true);
        area.setFont(UIManager.getFont("Label.font"));
        return area;
    }

    private void rebuildButtons() {
        requireEdt();
        int selectedPage = ((Number) page.getValue()).intValue();
        if (selectedPage < 1 || selectedPage > pageCount) { return; }
        destinations.removeAll(); buttons.clear();
        long first = (long) (selectedPage - 1) * PAGE_SIZE + 1;
        long last = Math.min(session.config().floorCount(), first + PAGE_SIZE - 1);
        for (long value = first; value <= last; value++) {
            int floor = (int) value;
            var button = new JButton(Integer.toString(floor));
            button.getAccessibleContext().setAccessibleName("Elevador " + cabinId + ", destino piso " + floor);
            button.addActionListener(event -> submit(floor));
            buttons.put(floor, button); destinations.add(button);
        }
        previous.setEnabled(selectedPage > 1); next.setEnabled(selectedPage < pageCount);
        destinations.setMaximumSize(new Dimension(Integer.MAX_VALUE, destinations.getPreferredSize().height));
        paintButtons(); destinations.revalidate(); destinations.repaint();
    }

    /** Snapshot local y llamadas globales ya agrupadas para esta cabina. No espera trabajo. */
    public void render(ElevatorSnapshot snapshot, List<HallCallSnapshot> assigned, boolean enabled) {
        requireEdt();
        Objects.requireNonNull(snapshot, "snapshot"); Objects.requireNonNull(assigned, "assigned");
        if (snapshot.id() != cabinId || !snapshot.config().equals(session.config())) {
            throw new IllegalArgumentException("Snapshot de otra cabina o configuración.");
        }
        long currentEpoch = session.epoch();
        boolean canSubmit = enabled && session.state() == SimulationState.RUNNING;
        if (epoch != currentEpoch || available != canSubmit) {
            ++sequence;
            if (epoch != currentEpoch) { latest = null; lit.clear(); external.clear(); }
            epoch = currentEpoch;
            available = canSubmit;
            message(canSubmit ? "Selecciona un destino interno para esta cabina."
                    : "Destinos bloqueados mientras el sistema está en " + session.state() + ".");
        }
        if (snapshot.epoch() == epoch && (latest == null || snapshot.revision() >= latest.revision())) { latest = snapshot; }
        lit.clear();
        if (latest == null) {
            location.setText("Esperando posición de la época actual...");
            direction.setText("Dirección: pendiente de actualización");
            phase.setText("Estado del sistema: " + session.state());
            internal.setText("Ninguno"); external.clear(); paintButtons(); return;
        }
        location.setText("Piso actual: " + latest.currentFloor() + latest.nextFloor().stream()
                .mapToObj(value -> " | Siguiente: " + value).findFirst().orElse(""));
        direction.setText("Dirección: " + (latest.direction() == Direction.UP ? "Arriba" : "Abajo"));
        phase.setText("Estado: " + session.state() + " / " + switch (latest.phase()) {
            case IDLE -> "En espera";
            case MOVING -> "En movimiento";
            case DWELLING -> "Parada de servicio";
            case HELD -> "Detenida";
            case TERMINATED -> "Terminada";
        } + (latest.mode() == ElevatorSnapshot.Mode.RESET_RETURN ? " — Retorno por Reset" : ""));
        if (latest.phase() != ElevatorPhase.TERMINATED && session.state() != SimulationState.CLOSED) {
            latest.cabinRequests().forEach(request -> lit.add(request.getFloor()));
        }
        internal.setText(lit.isEmpty() ? "Ninguno" : lit.toString()); internal.setCaretPosition(0);
        record Key(int floor, Direction direction) { }
        var calls = new TreeSet<Key>(Comparator.comparingInt(Key::floor).thenComparing(Key::direction));
        if (latest.phase() != ElevatorPhase.TERMINATED && session.state() != SimulationState.CLOSED) {
            latest.hallRequests().forEach(request -> calls.add(new Key(request.getFloor(), request.getDirection())));
            for (var call : assigned) {
                if (call.request().getEpoch() == epoch && call.command().isPresent()
                        && call.command().orElseThrow().elevatorId() == cabinId
                        && (call.status() == HallCallSnapshot.Status.DISPATCHED || call.status() == HallCallSnapshot.Status.ASSIGNED
                        || call.status() == HallCallSnapshot.Status.UNCONFIRMED)) {
                    calls.add(new Key(call.request().getFloor(), call.request().getDirection()));
                }
            }
        }
        external.clear();
        calls.forEach(call -> external.addElement("Piso " + call.floor() + " — "
                + (call.direction() == Direction.UP ? "Subir" : "Bajar")));
        paintButtons();
    }

    private void paintButtons() {
        buttons.forEach((floor, button) -> {
            boolean pending = lit.contains(floor);
            button.setSelected(pending);
            button.setOpaque(true);
            button.setBackground(pending ? new Color(255, 214, 92) : UIManager.getColor("Button.background"));
            button.setText(Integer.toString(floor) + (pending ? " ●" : ""));
            button.setToolTipText(pending ? "Destino pendiente de atención" : "Solicitar destino piso " + floor);
            button.setEnabled(available);
        });
    }

    private void submit(int floor) {
        requireEdt();
        if (!available || session.state() != SimulationState.RUNNING || epoch != session.epoch()) { return; }
        var request = new CabinRequest(UUID.randomUUID(), epoch, floor, Instant.now(), cabinId);
        long token = ++sequence;
        message("Piso " + floor + ": enviando destino interno...");
        session.submit(request).whenComplete((result, error) -> postToEdt.accept(() -> {
            requireEdt();
            if (token != sequence || !available || session.state() != SimulationState.RUNNING
                    || request.getEpoch() != session.epoch()) { return; }
            if (error != null) {
                Throwable cause = error;
                while (cause instanceof CompletionException && cause.getCause() != null) { cause = cause.getCause(); }
                message("No se pudo enviar el destino: " + cause.getMessage());
            } else if (session.isCurrent(result)) {
                message(result.outcome() == SimulationController.Outcome.ENQUEUED
                        ? "Piso " + floor + ": destino enviado; la luz refleja la agenda confirmada."
                        : "Destino rechazado: " + result.detail());
            }
        }));
    }

    private void message(String text) { feedback.setText(text.length() > 1000 ? text.substring(0, 1000) : text); feedback.setCaretPosition(0); }
    JButton destinationButton(int floor) { requireEdt(); return Objects.requireNonNull(buttons.get(floor), "Piso fuera de la página actual"); }
    void showPage(int number) { requireEdt(); if (number < 1 || number > pageCount) throw new IllegalArgumentException("Página inválida."); page.setValue(number); }
    Set<Integer> litFloors() { requireEdt(); return Set.copyOf(lit); }
    List<String> externalCalls() { requireEdt(); return Collections.list(external.elements()); }
    String locationText() { requireEdt(); return location.getText(); }
    String phaseText() { requireEdt(); return phase.getText(); }
    String directionText() { requireEdt(); return direction.getText(); }
    String feedbackText() { requireEdt(); return feedback.getText(); }
    int visibleButtonCount() { requireEdt(); return buttons.size(); }
    private static void requireEdt() {
        if (!SwingUtilities.isEventDispatchThread()) { throw new IllegalStateException("Los paneles de cabina requieren EDT."); }
    }
}
```

## src/main/java/com/elevatormanager/ui/CabinDashboardPanel.java

```java
package com.elevatormanager.ui;

import com.elevatormanager.dispatch.HallCallSnapshot;
import com.elevatormanager.simulation.SimulationController;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.util.*;
import javax.swing.*;

/** T50: N cabinas con botones 1..P paginados; comparte el refresco de T48. */
public final class CabinDashboardPanel extends JPanel {
    private static final long serialVersionUID = 1L;
    private final JTabbedPane tabs = new JTabbedPane(JTabbedPane.TOP, JTabbedPane.SCROLL_TAB_LAYOUT);
    private final List<CabinPanel> cabins = new ArrayList<>();
    private SimulationController controller;

    public CabinDashboardPanel() {
        requireEdt(); setLayout(new BorderLayout());
        setBorder(BorderFactory.createTitledBorder("Paneles de cabina"));
        setPreferredSize(new Dimension(470, 470));
        setMinimumSize(new Dimension(330, 250));
        add(new JLabel("Valida la configuración para crear las cabinas."), BorderLayout.CENTER);
    }

    public void bind(SimulationController session) {
        requireEdt(); controller = Objects.requireNonNull(session, "session");
        removeAll(); tabs.removeAll(); cabins.clear();
        for (int id = 1; id <= session.config().elevatorCount(); id++) {
            var panel = new CabinPanel(session, id);
            cabins.add(panel); tabs.addTab("Elevador " + id, new JScrollPane(panel));
        }
        add(tabs, BorderLayout.CENTER); refresh(false); revalidate(); repaint();
    }

    /** Una captura por cabina; no representa un instante físico global único. */
    public void refresh(boolean interactive) {
        requireEdt(); if (controller == null) return;
        var snapshots = controller.snapshots();
        var assigned = new HashMap<Integer, List<HallCallSnapshot>>();
        controller.calls().values().forEach(call -> call.command().ifPresent(command ->
                assigned.computeIfAbsent(command.elevatorId(), id -> new ArrayList<>()).add(call)));
        for (var snapshot : snapshots) {
            cabins.get(snapshot.id() - 1).render(snapshot, assigned.getOrDefault(snapshot.id(), List.of()), interactive);
        }
    }

    CabinPanel cabin(int id) { requireEdt(); return cabins.get(id - 1); }
    int cabinCount() { requireEdt(); return cabins.size(); }
    private static void requireEdt() {
        if (!SwingUtilities.isEventDispatchThread()) { throw new IllegalStateException("El conjunto de cabinas requiere EDT."); }
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

/** T50: controles, llamadas y paneles de cabina; ningún get/join, lectura o apertura en EDT. */
public final class SystemControlPanel extends JPanel {
    private static final long serialVersionUID = 1L;
    public enum Action { START, STOP, RESET, LOAD, CLOSE }
    @FunctionalInterface interface SessionOpener {
        SimulationController open(SimulationConfig config) throws Exception;
    }
    private final ConfigurationPanel configuration;
    private final HallCallPanel hallCalls;
    private final CabinDashboardPanel cabinDashboard;
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
        cabinDashboard = new CabinDashboardPanel();
        workspace.setMinimumSize(new java.awt.Dimension(350, 300));
        var split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, workspace, cabinDashboard);
        split.setResizeWeight(0.45);
        add(split, BorderLayout.CENTER);
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
                cabinDashboard.bind(session);
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
        frame.setMinimumSize(new Dimension(1060, 760));
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

## src/test/java/com/elevatormanager/ui/CabinPanelsScenarios.java

```java
package com.elevatormanager.ui;

import com.elevatormanager.dispatch.HallCallSnapshot;
import com.elevatormanager.model.*;
import com.elevatormanager.model.SimulationConfig.Field;
import com.elevatormanager.model.request.HallRequest;
import com.elevatormanager.simulation.SimulationController;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.*;
import javax.swing.SwingUtilities;

/** T50: snapshots, controles Swing, manejador y trabajadores reales. */
public final class CabinPanelsScenarios {
    private CabinPanelsScenarios() { }
    public static void dashboardCreatesConfiguredNumberOfCabins() throws Exception {
        try (var rig = new Rig()) {
            edt(() -> { var dashboard = new CabinDashboardPanel(); dashboard.bind(rig.c);
                check(dashboard.cabinCount() == 2, "N paneles");
                check(dashboard.cabin(2).locationText().contains("Piso actual: 1"), "Cabina 2 en piso inicial"); });
        }
    }

    public static void buttonsCoverConfiguredFloors() throws Exception {
        try (var rig = new Rig()) {
            edt(() -> { check(rig.panel.visibleButtonCount() == 6, "P botones");
                for (int floor = 1; floor <= 6; floor++) check(rig.panel.destinationButton(floor) != null, "Piso " + floor); });
        }
    }

    public static void paginationCoversLastPartialPage() throws Exception {
        try (var rig = new Rig(2, 45, false)) {
            edt(() -> { check(rig.panel.visibleButtonCount() == 20, "Primera página limitada");
                rig.panel.showPage(2); check(rig.panel.destinationButton(21) != null && rig.panel.destinationButton(40) != null, "Segunda página");
                rig.panel.showPage(3); check(rig.panel.visibleButtonCount() == 5 && rig.panel.destinationButton(45) != null, "Última página completa"); });
        }
    }

    public static void largeFloorCountDoesNotCreateBillionsOfButtons() throws Exception {
        try (var rig = new Rig(1, Integer.MAX_VALUE, false)) {
            edt(() -> { rig.panel.showPage((Integer.MAX_VALUE - 1) / CabinPanel.PAGE_SIZE + 1);
                check(rig.panel.visibleButtonCount() <= 20 && rig.panel.destinationButton(Integer.MAX_VALUE) != null, "P máximo sin overflow ni O(P) controles"); });
        }
    }

    public static void initialSnapshotShowsFloorDirectionAndState() throws Exception {
        try (var rig = new Rig()) {
            edt(() -> check(rig.panel.locationText().contains("Piso actual: 1") && rig.panel.directionText().contains("Arriba")
                    && rig.panel.phaseText().contains("RESET") && rig.panel.litFloors().isEmpty(), "Estado inicial real"));
        }
    }

    public static void destinationsRequireRunning() throws Exception {
        try (var rig = new Rig()) {
            edt(() -> { check(!rig.panel.destinationButton(4).isEnabled(), "RESET bloqueado"); rig.panel.destinationButton(4).doClick(0); });
            check(rig.c.snapshots().get(0).cabinRequests().isEmpty(), "No admite antes de Start");
        }
    }

    public static void buttonRoutesToItsOwnCabin() throws Exception {
        try (var rig = new Rig()) {
            rig.start();
            var second = onEdt(() -> { var p = new CabinPanel(rig.c, 2); p.render(rig.c.snapshots().get(1), List.of(), true); return p; });
            edt(() -> second.destinationButton(6).doClick(0));
            waitFor(() -> !rig.c.snapshots().get(1).cabinRequests().isEmpty());
            check(rig.c.snapshots().get(0).cabinRequests().isEmpty(), "No solicita a cabina 1");
            var request = rig.c.snapshots().get(1).cabinRequests().get(0);
            check(request.getElevatorId() == 2 && request.getFloor() == 6 && request.getEpoch() == rig.c.epoch(), "Herencia CabinRequest y ruta real");
        }
    }

    public static void movingSnapshotKeepsLastFloorUntilArrival() throws Exception {
        try (var rig = new Rig()) {
            rig.start(); rig.click(6); waitFor(() -> rig.c.snapshots().get(0).phase() == ElevatorPhase.MOVING);
            var snapshot = rig.c.snapshots().get(0);
            edt(() -> { rig.panel.render(snapshot, List.of(), true);
                check(rig.panel.locationText().contains("Piso actual: " + snapshot.currentFloor())
                        && rig.panel.locationText().contains("Siguiente: " + snapshot.nextFloor().orElseThrow())
                        && rig.panel.phaseText().contains("En movimiento"), "No anticipa llegada"); });
        }
    }

    public static void sendingDoesNotOptimisticallyLightButton() throws Exception {
        try (var rig = new Rig(2, 6, true)) {
            rig.start(); edt(() -> { rig.panel.destinationButton(6).doClick(0);
                check(!rig.panel.destinationButton(6).isSelected() && rig.panel.litFloors().isEmpty(), "Enviar no confirma agenda"); });
            rig.delivery();
        }
    }

    public static void admissionLightsAndReceiptDoesNotClear() throws Exception {
        try (var rig = new Rig(2, 6, true)) {
            rig.start(); rig.click(6); var delivery = rig.delivery(); rig.awaitPending(6); rig.refresh();
            edt(() -> { check(rig.panel.destinationButton(6).isSelected(), "Admisión enciende"); delivery.run();
                check(rig.panel.destinationButton(6).isSelected(), "Recibo ENQUEUED no apaga"); });
        }
    }

    public static void serviceClearsLightAtStopStart() throws Exception {
        try (var rig = new Rig()) {
            rig.start(); rig.click(4); rig.awaitPending(4); rig.refresh();
            waitFor(() -> { var s = rig.c.snapshots().get(0); return s.currentFloor() == 4 && s.phase() == ElevatorPhase.DWELLING; });
            rig.refresh(); edt(() -> check(!rig.panel.destinationButton(4).isSelected() && !rig.panel.litFloors().contains(4), "Se apaga al servir, no al asignar"));
        }
    }

    public static void servingOneFloorKeepsOtherDestinationLit() throws Exception {
        try (var rig = new Rig()) {
            rig.start(); rig.click(4); rig.click(6); rig.awaitPending(4); rig.awaitPending(6); rig.refresh();
            waitFor(() -> { var s = rig.c.snapshots().get(0); return s.currentFloor() == 4 && s.phase() == ElevatorPhase.DWELLING; });
            rig.refresh(); edt(() -> check(!rig.panel.litFloors().contains(4) && rig.panel.litFloors().contains(6), "Luces independientes por destino"));
        }
    }

    public static void stopPreservesPendingLights() throws Exception {
        try (var rig = new Rig()) {
            rig.start(); rig.click(6); rig.awaitPending(6); rig.c.stop().get(5, TimeUnit.SECONDS); rig.refresh();
            edt(() -> check(rig.panel.litFloors().contains(6) && !rig.panel.destinationButton(6).isEnabled(), "Stop conserva luz y bloquea admisión"));
        }
    }

    public static void resumeRestoresButtonsWithoutDroppingPending() throws Exception {
        try (var rig = new Rig()) {
            rig.start(); rig.click(6); rig.awaitPending(6); rig.c.stop().get(5, TimeUnit.SECONDS); rig.refresh();
            rig.c.start().get(5, TimeUnit.SECONDS); rig.refresh();
            edt(() -> check(rig.panel.destinationButton(6).isEnabled() && rig.panel.litFloors().contains(6), "Continuación misma agenda"));
        }
    }

    public static void resetClearsLightsAndRejectsOldEpochSnapshot() throws Exception {
        try (var rig = new Rig()) {
            rig.start(); rig.click(6); rig.awaitPending(6); var old = rig.c.snapshots().get(0); rig.refresh();
            rig.c.reset().get(5, TimeUnit.SECONDS);
            edt(() -> { rig.panel.render(old, List.of(), true); check(rig.panel.litFloors().isEmpty(), "Snapshot antiguo no revive pendientes"); });
            rig.refresh(); edt(() -> check(rig.panel.locationText().contains("Piso actual: 1") && !rig.panel.destinationButton(6).isEnabled(), "RESET confirmado"));
        }
    }

    public static void closeClearsPendingDisplays() throws Exception {
        try (var rig = new Rig()) {
            rig.start(); rig.click(6); rig.awaitPending(6); rig.refresh(); rig.c.closeAsync().get(5, TimeUnit.SECONDS); rig.refresh();
            edt(() -> check(rig.panel.litFloors().isEmpty() && rig.panel.externalCalls().isEmpty()
                    && !rig.panel.destinationButton(6).isEnabled(), "Terminación limpia representación"));
        }
    }

    public static void assignedExternalCallShowsFloorAndDirection() throws Exception {
        try (var rig = new Rig(1, 6, false)) {
            rig.start(); rig.hall(5, Direction.DOWN);
            waitFor(() -> rig.c.calls().values().stream().anyMatch(s -> s.status() == HallCallSnapshot.Status.ASSIGNED));
            rig.refresh(); edt(() -> check(rig.panel.externalCalls().contains("Piso 5 — Bajar"), "Asignada con sentido visible"));
        }
    }

    public static void externalCallStaysVisibleBeforeService() throws Exception {
        try (var rig = new Rig(1, 6, false)) {
            rig.start(); rig.hall(5, Direction.UP);
            waitFor(() -> rig.c.calls().values().stream().anyMatch(s -> s.status() == HallCallSnapshot.Status.ASSIGNED));
            rig.refresh(); edt(() -> check(!rig.panel.externalCalls().isEmpty(), "Asignar no apaga recogida"));
            check(rig.c.calls().values().stream().noneMatch(s -> s.status() == HallCallSnapshot.Status.SERVED), "No hubo servicio aún");
        }
    }

    public static void externalCallClearsAfterConfirmedService() throws Exception {
        try (var rig = new Rig(1, 6, false)) {
            rig.start(); rig.hall(4, Direction.DOWN);
            waitFor(() -> rig.c.calls().values().stream().anyMatch(s -> s.status() == HallCallSnapshot.Status.ASSIGNED));
            rig.refresh();
            waitFor(() -> rig.c.calls().values().stream().allMatch(s -> s.status() == HallCallSnapshot.Status.SERVED));
            rig.refresh(); edt(() -> check(rig.panel.externalCalls().isEmpty(), "Recogida atendida deja de mostrarse"));
        }
    }

    public static void oppositeExternalDirectionsRemainVisible() throws Exception {
        try (var rig = new Rig(1, 6, false)) {
            rig.start(); rig.hall(5, Direction.UP); rig.hall(5, Direction.DOWN);
            waitFor(() -> rig.c.calls().size() == 2 && rig.c.calls().values().stream().allMatch(s -> s.status() == HallCallSnapshot.Status.ASSIGNED));
            rig.refresh(); edt(() -> check(rig.panel.externalCalls().containsAll(List.of("Piso 5 — Subir", "Piso 5 — Bajar")), "Sentidos no fusionados"));
        }
    }

    public static void externalAliasesAreDisplayedOnce() throws Exception {
        try (var rig = new Rig(1, 6, false)) {
            rig.start(); rig.hall(5, Direction.UP); rig.hall(5, Direction.UP);
            waitFor(() -> rig.c.calls().size() == 2 && rig.c.calls().values().stream().allMatch(s -> s.status() == HallCallSnapshot.Status.ASSIGNED));
            rig.refresh(); edt(() -> check(rig.panel.externalCalls().size() == 1, "Una recogida canónica visible"));
        }
    }

    public static void singleFloorInternalButtonWorks() throws Exception {
        try (var rig = new Rig(1, 1, false)) {
            rig.start(); edt(() -> check(rig.panel.destinationButton(1).isEnabled(), "Destino actual permitido"));
            rig.click(1); waitFor(() -> rig.c.snapshots().get(0).phase() == ElevatorPhase.DWELLING);
            rig.refresh(); edt(() -> check(rig.panel.litFloors().isEmpty(), "Atención inmediata sin luz pendiente falsa"));
        }
    }

    public static void repeatedInternalClicksKeepOneCanonicalDestination() throws Exception {
        try (var rig = new Rig()) {
            rig.start(); rig.click(6); rig.click(6); rig.awaitPending(6);
            check(rig.c.snapshots().get(0).cabinRequests().size() == 1, "Deduplicación de cabina existente"); rig.refresh();
            edt(() -> check(rig.panel.litFloors().equals(Set.of(6)), "Una luz por piso"));
        }
    }

    public static void olderRevisionCannotRestoreServedLight() throws Exception {
        try (var rig = new Rig()) {
            rig.start(); rig.click(3); rig.awaitPending(3); var old = rig.c.snapshots().get(0); rig.refresh();
            waitFor(() -> { var s = rig.c.snapshots().get(0); return s.currentFloor() == 3 && s.phase() == ElevatorPhase.DWELLING; });
            rig.refresh();
            edt(() -> { rig.panel.render(old, List.of(), true); check(rig.panel.litFloors().isEmpty()
                    && rig.panel.locationText().contains("Piso actual: 3"), "No retrocede revisión local"); });
        }
    }

    public static void wrongCabinSnapshotIsRejected() throws Exception {
        try (var rig = new Rig()) {
            edt(() -> {
                try { rig.panel.render(rig.c.snapshots().get(1), List.of(), true); throw new AssertionError("Snapshot ajeno"); }
                catch (IllegalArgumentException expected) { }
            });
        }
    }

    public static void resetInvalidatesDelayedSendReceipt() throws Exception {
        try (var rig = new Rig(2, 6, true)) {
            rig.start(); rig.click(6); var delivery = rig.delivery(); rig.c.reset().get(5, TimeUnit.SECONDS); rig.refresh();
            edt(() -> { String message = rig.panel.feedbackText(); delivery.run(); check(message.equals(rig.panel.feedbackText()), "Recibo anterior al Reset ignorado"); });
        }
    }

    public static void stopInvalidatesDelayedSendReceipt() throws Exception {
        try (var rig = new Rig(2, 6, true)) {
            rig.start(); rig.click(6); var delivery = rig.delivery(); rig.c.stop().get(5, TimeUnit.SECONDS); rig.refresh();
            edt(() -> { String message = rig.panel.feedbackText(); delivery.run(); check(message.equals(rig.panel.feedbackText()), "Recibo anterior al Stop ignorado"); });
        }
    }

    public static void lastClickMessageWins() throws Exception {
        try (var rig = new Rig(2, 6, true)) {
            rig.start(); rig.click(4); var first = rig.delivery(); rig.click(6); var second = rig.delivery();
            edt(() -> { second.run(); String message = rig.panel.feedbackText(); first.run();
                check(message.equals(rig.panel.feedbackText()) && message.contains("Piso 6"), "Orden inverso no restaura mensaje viejo"); });
        }
    }

    public static void parentStopAndCloseImmediatelyBlockInternalButtons() throws Exception {
        withSystem((panel, c) -> {
            edt(() -> {
                var cabin = panel.cabinDashboard().cabin(1); check(cabin.destinationButton(4).isEnabled(), "RUNNING");
                panel.button(SystemControlPanel.Action.STOP).doClick(0);
                check(!cabin.destinationButton(4).isEnabled(), "Bloqueo antes del callback Stop");
            });
            var closing = onEdt(() -> { var future = panel.closeAsync();
                check(!panel.cabinDashboard().cabin(1).destinationButton(4).isEnabled(), "Cierre bloquea"); return future; });
            closing.get(5, TimeUnit.SECONDS);
        });
    }

    public static void fullCompositionAcceptsInternalAndExternalRequests() throws Exception {
        withSystem((panel, c) -> {
            edt(() -> {
                panel.cabinDashboard().cabin(2).destinationButton(4).doClick(0);
                panel.hallCallPanel().floorSelector().setValue(3);
                panel.hallCallPanel().button(Direction.UP).doClick(0);
            });
            waitFor(() -> !c.calls().isEmpty() && c.calls().values().stream().allMatch(call -> call.status() == HallCallSnapshot.Status.SERVED)
                    && c.snapshots().get(1).currentFloor() == 4);
            edt(() -> { panel.updateView(); check(panel.cabinDashboard().cabin(2).litFloors().isEmpty(), "Destino interno servido"); });
        });
    }

    public static void offEdtAccessIsRejected() throws Exception {
        try (var rig = new Rig()) {
            try { new CabinPanel(rig.c, 1); throw new AssertionError("Construcción fuera EDT"); } catch (IllegalStateException expected) { }
            try { rig.panel.render(rig.c.snapshots().get(0), List.of(), true); throw new AssertionError("Render fuera EDT"); } catch (IllegalStateException expected) { }
            try { new CabinDashboardPanel(); throw new AssertionError("Dashboard fuera EDT"); } catch (IllegalStateException expected) { }
        }
    }

    public static void pageChangesPreserveOffPageLights() throws Exception {
        try (var rig = new Rig(1, 25, false)) {
            rig.start(); edt(() -> { rig.panel.showPage(2); rig.panel.destinationButton(25).doClick(0); });
            rig.awaitPending(25); rig.refresh();
            edt(() -> { check(rig.panel.destinationButton(25).isSelected(), "Página 2 encendida"); rig.panel.showPage(1);
                check(rig.panel.litFloors().contains(25), "Pendiente fuera de página conservado");
                rig.panel.showPage(2); check(rig.panel.destinationButton(25).isSelected(), "La luz se reconstruye desde el snapshot"); });
        }
    }

    private static final class Rig implements AutoCloseable {
        final SimulationController c;
        final CabinPanel panel;
        final BlockingQueue<Runnable> deliveries = new LinkedBlockingQueue<>();
        Rig() { this(2, 6, false); }
        Rig(int elevators, int floors, boolean delayed) {
            c = new SimulationController(new SimulationConfig(elevators, floors, 140, 150), 0);
            panel = onEdt(() -> delayed ? new CabinPanel(c, 1, deliveries::add) : new CabinPanel(c, 1));
            refresh();
        }
        void start() throws Exception { c.start().get(5, TimeUnit.SECONDS); refresh(); }
        void refresh() { edtUnchecked(() -> panel.render(c.snapshots().get(0), List.copyOf(c.calls().values()), true)); }
        void click(int floor) { edtUnchecked(() -> panel.destinationButton(floor).doClick(0)); }
        void hall(int floor, Direction direction) throws Exception {
            c.submit(new HallRequest(UUID.randomUUID(), c.epoch(), floor, Instant.now(), direction)).get(5, TimeUnit.SECONDS);
        }
        void awaitPending(int floor) throws Exception { waitFor(() -> c.snapshots().get(0).cabinRequests().stream().anyMatch(r -> r.getFloor() == floor)); }
        Runnable delivery() throws Exception { var result = deliveries.poll(5, TimeUnit.SECONDS); check(result != null, "Entrega real del futuro"); return result; }
        @Override public void close() throws Exception { c.closeAsync().get(5, TimeUnit.SECONDS); check(c.awaitTermination(Duration.ofSeconds(5)), "Hilos terminados"); }
    }
    @FunctionalInterface private interface SystemCheck { void run(SystemControlPanel panel, SimulationController c) throws Exception; }
    private static void withSystem(SystemCheck check) throws Exception {
        var panel = onEdt(() -> new SystemControlPanel(config -> new SimulationController(config, 0), Optional::empty));
        try {
            edt(() -> {
                var form = panel.configurationPanel(); String[] values = {"2", "5", "80", "40"};
                for (var field : Field.values()) form.input(field).setText(values[field.ordinal()]); form.validateConfiguration();
            });
            waitFor(() -> onEdt(() -> panel.controller().isPresent()));
            edt(() -> panel.button(SystemControlPanel.Action.START).doClick(0));
            waitFor(() -> onEdt(() -> { panel.updateView(); return panel.cabinDashboard().cabin(1).destinationButton(4).isEnabled(); }));
            check.run(panel, onEdt(() -> panel.controller().orElseThrow()));
        } finally {
            onEdt(panel::closeAsync).get(5, TimeUnit.SECONDS);
            var c = onEdt(() -> panel.controller().orElse(null));
            if (c != null) check(c.awaitTermination(Duration.ofSeconds(5)), "Composición cerrada");
            edt(panel::closed);
        }
    }
    private static void check(boolean condition, String text) { if (!condition) throw new AssertionError(text); }
    private static void waitFor(BooleanSupplier condition) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(6);
        while (!condition.getAsBoolean()) { if (System.nanoTime() > until) throw new AssertionError("Tiempo agotado esperando condición"); Thread.sleep(5); }
    }
    private static void edt(Runnable action) throws Exception { onEdt(() -> { action.run(); return null; }); }
    private static void edtUnchecked(Runnable action) { onEdt(() -> { action.run(); return null; }); }
    private static <T> T onEdt(Supplier<T> action) {
        if (SwingUtilities.isEventDispatchThread()) return action.get();
        var value = new AtomicReference<T>(); var failure = new AtomicReference<Throwable>();
        try { SwingUtilities.invokeAndWait(() -> { try { value.set(action.get()); } catch (Throwable error) { failure.set(error); } }); }
        catch (Exception error) { throw new AssertionError(error); }
        if (failure.get() instanceof Error error) throw error;
        if (failure.get() != null) throw new IllegalStateException(failure.get()); return value.get();
    }
    public static void main(String[] args) throws Exception {
        dashboardCreatesConfiguredNumberOfCabins();
        buttonsCoverConfiguredFloors();
        paginationCoversLastPartialPage();
        largeFloorCountDoesNotCreateBillionsOfButtons();
        initialSnapshotShowsFloorDirectionAndState();
        destinationsRequireRunning();
        buttonRoutesToItsOwnCabin();
        movingSnapshotKeepsLastFloorUntilArrival();
        sendingDoesNotOptimisticallyLightButton();
        admissionLightsAndReceiptDoesNotClear();
        serviceClearsLightAtStopStart();
        servingOneFloorKeepsOtherDestinationLit();
        stopPreservesPendingLights();
        resumeRestoresButtonsWithoutDroppingPending();
        resetClearsLightsAndRejectsOldEpochSnapshot();
        closeClearsPendingDisplays();
        assignedExternalCallShowsFloorAndDirection();
        externalCallStaysVisibleBeforeService();
        externalCallClearsAfterConfirmedService();
        oppositeExternalDirectionsRemainVisible();
        externalAliasesAreDisplayedOnce();
        singleFloorInternalButtonWorks();
        repeatedInternalClicksKeepOneCanonicalDestination();
        olderRevisionCannotRestoreServedLight();
        wrongCabinSnapshotIsRejected();
        resetInvalidatesDelayedSendReceipt();
        stopInvalidatesDelayedSendReceipt();
        lastClickMessageWins();
        parentStopAndCloseImmediatelyBlockInternalButtons();
        fullCompositionAcceptsInternalAndExternalRequests();
        offEdtAccessIsRejected();
        pageChangesPreserveOffPageLights();
        System.out.println("T50: 32 escenarios satisfactorios.");
    }
}
```

## src/test/java/com/elevatormanager/ui/CabinPanelsTest.java

```java
package com.elevatormanager.ui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Adaptadores JUnit T50. */
@Timeout(30)
class CabinPanelsTest {
    @Test void dashboardCreatesConfiguredNumberOfCabins() throws Exception {
        CabinPanelsScenarios.dashboardCreatesConfiguredNumberOfCabins();
    }
    @Test void buttonsCoverConfiguredFloors() throws Exception {
        CabinPanelsScenarios.buttonsCoverConfiguredFloors();
    }
    @Test void paginationCoversLastPartialPage() throws Exception {
        CabinPanelsScenarios.paginationCoversLastPartialPage();
    }
    @Test void largeFloorCountDoesNotCreateBillionsOfButtons() throws Exception {
        CabinPanelsScenarios.largeFloorCountDoesNotCreateBillionsOfButtons();
    }
    @Test void initialSnapshotShowsFloorDirectionAndState() throws Exception {
        CabinPanelsScenarios.initialSnapshotShowsFloorDirectionAndState();
    }
    @Test void destinationsRequireRunning() throws Exception {
        CabinPanelsScenarios.destinationsRequireRunning();
    }
    @Test void buttonRoutesToItsOwnCabin() throws Exception {
        CabinPanelsScenarios.buttonRoutesToItsOwnCabin();
    }
    @Test void movingSnapshotKeepsLastFloorUntilArrival() throws Exception {
        CabinPanelsScenarios.movingSnapshotKeepsLastFloorUntilArrival();
    }
    @Test void sendingDoesNotOptimisticallyLightButton() throws Exception {
        CabinPanelsScenarios.sendingDoesNotOptimisticallyLightButton();
    }
    @Test void admissionLightsAndReceiptDoesNotClear() throws Exception {
        CabinPanelsScenarios.admissionLightsAndReceiptDoesNotClear();
    }
    @Test void serviceClearsLightAtStopStart() throws Exception {
        CabinPanelsScenarios.serviceClearsLightAtStopStart();
    }
    @Test void servingOneFloorKeepsOtherDestinationLit() throws Exception {
        CabinPanelsScenarios.servingOneFloorKeepsOtherDestinationLit();
    }
    @Test void stopPreservesPendingLights() throws Exception {
        CabinPanelsScenarios.stopPreservesPendingLights();
    }
    @Test void resumeRestoresButtonsWithoutDroppingPending() throws Exception {
        CabinPanelsScenarios.resumeRestoresButtonsWithoutDroppingPending();
    }
    @Test void resetClearsLightsAndRejectsOldEpochSnapshot() throws Exception {
        CabinPanelsScenarios.resetClearsLightsAndRejectsOldEpochSnapshot();
    }
    @Test void closeClearsPendingDisplays() throws Exception {
        CabinPanelsScenarios.closeClearsPendingDisplays();
    }
    @Test void assignedExternalCallShowsFloorAndDirection() throws Exception {
        CabinPanelsScenarios.assignedExternalCallShowsFloorAndDirection();
    }
    @Test void externalCallStaysVisibleBeforeService() throws Exception {
        CabinPanelsScenarios.externalCallStaysVisibleBeforeService();
    }
    @Test void externalCallClearsAfterConfirmedService() throws Exception {
        CabinPanelsScenarios.externalCallClearsAfterConfirmedService();
    }
    @Test void oppositeExternalDirectionsRemainVisible() throws Exception {
        CabinPanelsScenarios.oppositeExternalDirectionsRemainVisible();
    }
    @Test void externalAliasesAreDisplayedOnce() throws Exception {
        CabinPanelsScenarios.externalAliasesAreDisplayedOnce();
    }
    @Test void singleFloorInternalButtonWorks() throws Exception {
        CabinPanelsScenarios.singleFloorInternalButtonWorks();
    }
    @Test void repeatedInternalClicksKeepOneCanonicalDestination() throws Exception {
        CabinPanelsScenarios.repeatedInternalClicksKeepOneCanonicalDestination();
    }
    @Test void olderRevisionCannotRestoreServedLight() throws Exception {
        CabinPanelsScenarios.olderRevisionCannotRestoreServedLight();
    }
    @Test void wrongCabinSnapshotIsRejected() throws Exception {
        CabinPanelsScenarios.wrongCabinSnapshotIsRejected();
    }
    @Test void resetInvalidatesDelayedSendReceipt() throws Exception {
        CabinPanelsScenarios.resetInvalidatesDelayedSendReceipt();
    }
    @Test void stopInvalidatesDelayedSendReceipt() throws Exception {
        CabinPanelsScenarios.stopInvalidatesDelayedSendReceipt();
    }
    @Test void lastClickMessageWins() throws Exception {
        CabinPanelsScenarios.lastClickMessageWins();
    }
    @Test void parentStopAndCloseImmediatelyBlockInternalButtons() throws Exception {
        CabinPanelsScenarios.parentStopAndCloseImmediatelyBlockInternalButtons();
    }
    @Test void fullCompositionAcceptsInternalAndExternalRequests() throws Exception {
        CabinPanelsScenarios.fullCompositionAcceptsInternalAndExternalRequests();
    }
    @Test void offEdtAccessIsRejected() throws Exception {
        CabinPanelsScenarios.offEdtAccessIsRejected();
    }
    @Test void pageChangesPreserveOffPageLights() throws Exception {
        CabinPanelsScenarios.pageChangesPreserveOffPageLights();
    }
}
```
