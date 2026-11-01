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
