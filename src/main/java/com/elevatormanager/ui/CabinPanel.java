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
