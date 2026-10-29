package com.elevatormanager.ui;

import com.elevatormanager.simulation.SimulationController;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.util.*;
import javax.swing.*;

/** T52: pestañas para N cabinas, con hasta tres paneles completos en memoria. */
public final class CabinDashboardPanel extends JPanel {
    private static final long serialVersionUID = 1L;
    static final int MAX_CACHED_PANELS = 3;
    private final JTabbedPane tabs = new JTabbedPane(JTabbedPane.TOP, JTabbedPane.SCROLL_TAB_LAYOUT);
    private final LinkedHashMap<Integer, CabinPanel> cabins = new LinkedHashMap<>(4, 0.75f, true);
    private SimulationController controller;
    private JSpinner selector;
    private final JLabel navigationStatus = new JLabel();
    private UiSnapshotPump pump;
    private boolean interactive;
    private boolean binding;

    public CabinDashboardPanel() {
        requireEdt(); setLayout(new BorderLayout());
        setBorder(BorderFactory.createTitledBorder("Paneles de cabina"));
        setPreferredSize(new Dimension(470, 470)); setMinimumSize(new Dimension(330, 250));
        add(new JLabel("Valida la configuración para crear las cabinas."), BorderLayout.CENTER);
        tabs.addChangeListener(event -> {
            if (!binding && controller != null && tabs.getSelectedIndex() >= 0) {
                selector.setValue(tabs.getSelectedIndex() + 1); refresh(interactive);
            }
        });
    }

    public void bind(SimulationController session) {
        requireEdt(); binding = true;
        try {
            controller = Objects.requireNonNull(session, "session"); pump = null;
            removeAll(); tabs.removeAll(); cabins.clear();
            for (int id = 1; id <= session.config().elevatorCount(); id++) {
                tabs.addTab("Elevador " + id, new JPanel());
            }
            selector = new JSpinner(new SpinnerNumberModel(1, 1, session.config().elevatorCount(), 1));
            selector.setEditor(new JSpinner.NumberEditor(selector, "0"));
            selector.getAccessibleContext().setAccessibleName("Número de cabina a inspeccionar");
            var show = new JButton("Ver cabina"); show.addActionListener(event -> selectTypedCabin());
            var navigation = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT));
            navigation.add(new JLabel("Cabina:")); navigation.add(selector); navigation.add(show);
            navigationStatus.setText(session.config().elevatorCount() + " cabinas"); navigation.add(navigationStatus);
            add(navigation, BorderLayout.NORTH); add(tabs, BorderLayout.CENTER);
        } finally { binding = false; }
        refresh(false); revalidate(); repaint();
    }

    public void usePump(UiSnapshotPump source) { requireEdt(); pump = Objects.requireNonNull(source); }

    /** Solo la cabina visible se renderiza; la agregación global pertenece al trabajador. */
    public void refresh(boolean enabled) {
        requireEdt(); if (controller == null) return;
        setInteractive(enabled);
        int id = tabs.getSelectedIndex() + 1;
        var panel = ensurePanel(id);
        var frame = pump == null ? Optional.<UiSnapshotPump.Frame>empty() : pump.latest();
        if (frame.isPresent() && frame.orElseThrow().sessionId().equals(controller.sessionId())
                && frame.orElseThrow().epoch() == controller.epoch()) {
            var data = frame.orElseThrow();
            panel.render(data.snapshots().get(id - 1), data.assigned().getOrDefault(id, List.of()), enabled);
        } else {
            // Sin conjunto vigente, solo posición/agenda local O(1); nunca recorre el historial global en EDT.
            panel.render(controller.snapshot(id), List.of(), enabled);
        }
    }

    public void setInteractive(boolean enabled) {
        requireEdt(); interactive = enabled;
        for (var panel : cabins.values()) panel.setInteractive(enabled);
    }

    private CabinPanel ensurePanel(int id) {
        var existing = cabins.get(id); if (existing != null) return existing;
        var panel = new CabinPanel(controller, id); cabins.put(id, panel);
        panel.render(controller.snapshot(id), List.of(), interactive);
        tabs.setComponentAt(id - 1, new JScrollPane(panel));
        if (cabins.size() > MAX_CACHED_PANELS) {
            var iterator = cabins.entrySet().iterator();
            while (iterator.hasNext()) {
                var entry = iterator.next();
                if (entry.getKey() != tabs.getSelectedIndex() + 1) {
                    int removed = entry.getKey(); iterator.remove(); tabs.setComponentAt(removed - 1, new JPanel()); break;
                }
            }
        }
        return panel;
    }

    private void selectTypedCabin() {
        requireEdt();
        try {
            int id = Integer.parseInt(((JSpinner.NumberEditor) selector.getEditor()).getTextField().getText().strip());
            selectCabin(id); navigationStatus.setText(controller.config().elevatorCount() + " cabinas");
        } catch (IllegalArgumentException error) { navigationStatus.setText("Indica una cabina entre 1 y " + controller.config().elevatorCount() + "."); }
    }
    void selectCabin(int id) {
        requireEdt(); if (id < 1 || id > cabinCount()) throw new IllegalArgumentException("Cabina fuera de rango.");
        selector.setValue(id); tabs.setSelectedIndex(id - 1); refresh(interactive);
    }
    int selectedCabin() { requireEdt(); return tabs.getSelectedIndex() + 1; }
    CabinPanel cabin(int id) {
        requireEdt(); if (id < 1 || id > cabinCount()) throw new IllegalArgumentException("Cabina fuera de rango.");
        return ensurePanel(id);
    }
    int cachedPanelCount() { requireEdt(); return cabins.size(); }
    int cabinCount() { requireEdt(); return controller == null ? 0 : controller.config().elevatorCount(); }
    private static void requireEdt() {
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("El conjunto de cabinas requiere EDT.");
    }
}
