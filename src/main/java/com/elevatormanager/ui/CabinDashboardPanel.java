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
