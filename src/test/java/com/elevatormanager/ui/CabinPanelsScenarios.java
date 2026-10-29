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
