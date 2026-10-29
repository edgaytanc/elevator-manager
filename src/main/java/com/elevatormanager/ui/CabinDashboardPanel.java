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
    private JSpinner selector;
    private final JLabel navigationStatus = new JLabel();

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
        selector = new JSpinner(new SpinnerNumberModel(1, 1, cabins.size(), 1));
        selector.setEditor(new JSpinner.NumberEditor(selector, "0"));
        selector.getAccessibleContext().setAccessibleName("Número de cabina a inspeccionar");
        var show = new JButton("Ver cabina");
        show.addActionListener(event -> selectTypedCabin());
        var navigation = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT));
        navigation.add(new JLabel("Cabina:")); navigation.add(selector); navigation.add(show);
        navigationStatus.setText(cabins.size() + " cabinas"); navigation.add(navigationStatus);
        add(navigation, BorderLayout.NORTH);
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

    private void selectTypedCabin() {
        requireEdt();
        try {
            int id = Integer.parseInt(((JSpinner.NumberEditor) selector.getEditor()).getTextField().getText().strip());
            selectCabin(id);
            navigationStatus.setText(cabins.size() + " cabinas");
        } catch (IllegalArgumentException error) {
            navigationStatus.setText("Indica una cabina entre 1 y " + cabins.size() + ".");
        }
    }

    void selectCabin(int id) {
        requireEdt();
        if (id < 1 || id > cabins.size()) throw new IllegalArgumentException("Cabina fuera de rango.");
        selector.setValue(id); tabs.setSelectedIndex(id - 1);
    }
    int selectedCabin() { requireEdt(); return tabs.getSelectedIndex() + 1; }
    CabinPanel cabin(int id) { requireEdt(); return cabins.get(id - 1); }
    int cabinCount() { requireEdt(); return cabins.size(); }
    private static void requireEdt() {
        if (!SwingUtilities.isEventDispatchThread()) { throw new IllegalStateException("El conjunto de cabinas requiere EDT."); }
    }
}
