package com.elevatormanager.ui;

import com.elevatormanager.model.*;
import com.elevatormanager.simulation.SimulationController;
import com.elevatormanager.simulation.time.PausableClock;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.lang.management.ManagementFactory;
import java.util.List;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import javax.swing.*;

/** T61: raster, viewport, repintados, hilos, pulsos y mediciones sin JFrame. */
public final class VisualQualityScenarios {
    private static final long MS = 1_000_000L;
    private static final UUID SESSION = UUID.randomUUID();
    private VisualQualityScenarios() { }

    public static void floorLabelsRemainVisibleAfterHorizontalScroll() throws Exception { edt(() -> {
        var r = viewport(20, 100, 600, 360); bottomRight(r);
        Rectangle v = r.getViewRect(); var p = (BuildingPanel) r.getView(); var image = paint(p, v);
        int y = p.geometry().floorY(1) - v.y;
        check(ink(image, new Rectangle(4, y - 12, BuildingPanel.Geometry.LEFT - 8, 24)) > 10,
                "Etiqueta de piso adherida al borde visible tras scroll horizontal");
    }); }
    public static void cabinHeadersRemainVisibleAfterVerticalScroll() throws Exception { edt(() -> {
        var r = viewport(20, 100, 600, 360); bottomRight(r);
        Rectangle v = r.getViewRect(); var p = (BuildingPanel) r.getView(); var image = paint(p, v);
        int x = p.geometry().cabinX(20) - v.x;
        check(ink(image, new Rectangle(x - 48, 4, 96, BuildingPanel.Geometry.TOP - 8)) > 20,
                "ID del último elevador adherido al borde superior");
    }); }
    public static void largeFontAndIdsHaveRoomAndVisibleLabels() throws Exception { edt(() -> {
        var p = new BuildingPanel(); p.setFont(new Font("Dialog", Font.PLAIN, 24));
        p.bind(SESSION, new SimulationConfig(10000, 100000, 1000, 100));
        var metrics = p.getFontMetrics(p.getFont()); var preferred = p.getPreferredSize();
        check((preferred.width - 132.0) / 10000 >= metrics.stringWidth("Elevador 10000") + 24,
                "Título completo dispone de ancho con fuente grande");
        check((preferred.height - 68.0) / 100000 >= metrics.getHeight() + 12, "Filas separadas con fuente grande");
        var r = attach(p, 800, 450); bottomRight(r); var image = paint(p, r.getViewRect());
        check(ink(image, new Rectangle(0, 0, 800, 48)) > 50, "Títulos grandes presentes en raster");
        var layout = p.geometry(); var visible = r.getViewRect(); int id = layout.cabinAt(visible.x + BuildingPanel.Geometry.LEFT);
        int w = (int) layout.laneWidth() - 8, right = layout.cabinX(id) + w / 2 - visible.x;
        if (layout.cabinX(id) - w / 2 < visible.x + BuildingPanel.Geometry.LEFT && right > BuildingPanel.Geometry.LEFT + 4)
            check(ink(image, new Rectangle(BuildingPanel.Geometry.LEFT + 2, 4,
                    right - BuildingPanel.Geometry.LEFT - 4, 40)) == 0, "No muestra un ID parcial junto al margen fijo");
    }); }
    public static void oneFloorCabinDoesNotOverlapAxes() throws Exception { edt(() -> {
        var r = viewport(1, 1, 420, 300); var p = (BuildingPanel) r.getView(); var b = p.geometry().cabinBounds(1, 1);
        check(b.x > BuildingPanel.Geometry.LEFT && b.y > BuildingPanel.Geometry.TOP, "Cabina y ejes separados");
        var image = paint(p, r.getViewRect());
        check(image.getRGB(b.x + 5, b.y + 5) == BuildingPanel.CABIN_COLOR.getRGB(), "Cabina identificable en P=1");
    }); }
    public static void narrowViewportStillReachesLastCabinAndGroundFloor() throws Exception { edt(() -> {
        var r = viewport(20, 100, 280, 220); bottomRight(r); var p = (BuildingPanel) r.getView();
        var b = p.geometry().cabinBounds(20, 1); var v = r.getViewRect();
        check(v.contains(b) && b.x >= v.x + BuildingPanel.Geometry.LEFT && b.y >= v.y + BuildingPanel.Geometry.TOP,
                "Última cabina visible sin quedar tapada por ejes");
    }); }
    public static void resizingInvalidatesOnlyGeometryCache() throws Exception { edt(() -> {
        var r = viewport(2, 3, 600, 360); var p = (BuildingPanel) r.getView();
        check(p.animationChanged(r.getViewRect()), "Primer raster");
        check(!p.animationChanged(r.getViewRect()), "Reposo sin cambios");
        r.setSize(900, 600); r.doLayout();
        check(p.animationChanged(r.getViewRect()), "Redimensionar exige nueva geometría");
        check(!p.animationChanged(r.getViewRect()) && p.displayedFloor(1) == 1, "Modelo conservado");
    }); }
    public static void pausedWallTimeDoesNotRequestRepeatedPaints() throws Exception { edt(() -> {
        var rig = new MotionRig(); rig.clock.set(200 * MS); rig.view.set(new PausableClock.View(200 * MS, 200 * MS, true));
        check(rig.p.animationChanged(rig.visible), "Primera posición pausada");
        for (int i = 0; i < 120; i++) {
            rig.clock.addAndGet(1000 * MS); check(!rig.p.animationChanged(rig.visible), "Pausa no repinta por pared");
        }
    }); }
    public static void subpixelTicksDoNotRequestIdenticalRasters() throws Exception { edt(() -> {
        var rig = new MotionRig(); check(rig.p.animationChanged(rig.visible), "Posición inicial");
        rig.clock.set(1); check(!rig.p.animationChanged(rig.visible), "Un nanosegundo no cambia píxel");
        rig.clock.set(500 * MS); check(rig.p.animationChanged(rig.visible), "Movimiento visible repinta");
        check(!rig.p.animationChanged(rig.visible), "Mismo píxel no repinta otra vez");
    }); }
    public static void freshIdleFramesDoNotRequestIdenticalRasters() throws Exception { edt(() -> {
        var r = viewport(1, 3, 600, 360); var p = (BuildingPanel) r.getView(); var cfg = new SimulationConfig(1, 3, 1000, 100);
        p.animationChanged(r.getViewRect());
        for (int i = 0; i < 100; i++) {
            var s = new ElevatorSnapshot(1, cfg, 0, i, 1, OptionalInt.empty(), Direction.UP, ElevatorPhase.IDLE,
                    Optional.empty(), Optional.empty(), ElevatorSnapshot.Mode.NORMAL, 0, 0, i, OptionalInt.empty(), List.of(), List.of());
            p.render(new UiSnapshotPump.Frame(SESSION, 0, List.of(s), Map.of()), 0);
            check(!p.animationChanged(r.getViewRect()), "Frame nuevo sin cambio visual");
        }
    }); }
    public static void hiddenPanelDoesNotScheduleAnimationRepaints() throws Exception { edt(() -> {
        var rig = new MotionRig(); var previous = RepaintManager.currentManager(rig.p); var requests = new AtomicInteger();
        var watch = new RepaintManager() {
            @Override public void addDirtyRegion(JComponent c, int x, int y, int w, int h) {
                if (c == rig.p) requests.incrementAndGet();
                super.addDirtyRegion(c, x, y, w, h);
            }
        };
        try {
            RepaintManager.setCurrentManager(watch);
            for (int i = 0; i < 100; i++) { rig.clock.addAndGet(MS); rig.p.refreshAnimation(); }
            check(requests.get() == 0, "Edificio oculto no solicita fotogramas");
        } finally { RepaintManager.setCurrentManager(previous); }
    }); }
    public static void animationCacheIsBoundedByViewport() throws Exception { edt(() -> {
        var r = viewport(10000, 100000, 600, 360); bottomRight(r); var p = (BuildingPanel) r.getView();
        p.animationChanged(r.getViewRect());
        check(p.cachedAnimationCabins() <= 8 && p.getComponentCount() == 0, "Caché visible y sin controles por piso");
        r.setViewPosition(new Point(0, 0)); p.animationChanged(r.getViewRect());
        check(p.cachedAnimationCabins() <= 8, "Recorrer no acumula todas las cabinas");
    }); }
    public static void paintingFramesDoesNotCreateThreads() throws Exception { edt(() -> {
        var rig = new MotionRig(); var image = new BufferedImage(800, 450, BufferedImage.TYPE_INT_RGB);
        for (int i = 0; i < 60; i++) paintInto(rig.p, rig.visible, image);
        var bean = ManagementFactory.getThreadMXBean(); long before = bean.getTotalStartedThreadCount();
        for (int i = 0; i < 300; i++) {
            rig.clock.set(i * 2 * MS); rig.p.animationChanged(rig.visible); paintInto(rig.p, rig.visible, image);
        }
        long after = bean.getTotalStartedThreadCount();
        check(before == after, "300 fotogramas no crean hilos: " + before + " -> " + after);
        System.out.println("T61_THREADS|frames=300|started_before=" + before + "|started_after=" + after);
    }); }
    public static void edtProcessesPulsesWhilePainting() throws Exception {
        var ready = onEdt(() -> viewport(20, 100, 800, 450));
        var p = onEdt(() -> (BuildingPanel) ready.getView());
        var image = new BufferedImage(800, 450, BufferedImage.TYPE_INT_RGB);
        var done = new CountDownLatch(10); var ticks = new AtomicInteger(); var previous = new AtomicLong(); var gap = new AtomicLong();
        var timers = onEdt(() -> {
            var animation = new javax.swing.Timer(16, event -> paintInto(p, ready.getViewRect(), image));
            var pulse = new javax.swing.Timer(20, event -> {
                long now = System.nanoTime(), old = previous.getAndSet(now);
                if (old != 0) gap.accumulateAndGet(now - old, Math::max);
                ticks.incrementAndGet(); done.countDown();
            });
            animation.setCoalesce(true); pulse.setCoalesce(true); animation.start(); pulse.start();
            return List.of(animation, pulse);
        });
        try { check(done.await(5, TimeUnit.SECONDS), "Diez pulsos del EDT durante el dibujo"); }
        finally { edt(() -> timers.forEach(javax.swing.Timer::stop)); }
        System.out.printf(Locale.ROOT, "T61_PULSE|ticks=%d|max_gap_ms=%.3f%n", ticks.get(), gap.get() / 1_000_000.0);
    }
    public static void realActorsAndCaptureKeepTheirThreadIdentities() throws Exception {
        var c = new SimulationController(new SimulationConfig(3, 6, 200, 100), 0); var pump = new UiSnapshotPump(c);
        try {
            c.start().get(5, TimeUnit.SECONDS);
            for (int id = 1; id <= 3; id++) c.submit(new com.elevatormanager.model.request.CabinRequest(
                    UUID.randomUUID(), 0, 6, java.time.Instant.now(), id)).get(5, TimeUnit.SECONDS);
            await(() -> c.snapshots().stream().allMatch(snapshot -> snapshot.phase() == ElevatorPhase.MOVING));
            pump.request(); await(() -> pump.latest().isPresent());
            var before = ownedThreads(); check(before.size() == 5, "Tres cabinas, manejador y capturador");
            edt(() -> {
                var p = new BuildingPanel(); p.bind(c.sessionId(), c.config()); p.useTimeView(c::timeView); p.setSize(800, 450);
                p.render(pump.latest().orElseThrow(), c.epoch());
                var image = new BufferedImage(800, 450, BufferedImage.TYPE_INT_RGB); var visible = new Rectangle(0, 0, 800, 450);
                for (int i = 0; i < 120; i++) { p.animationChanged(visible); paintInto(p, visible, image); }
            });
            check(before.equals(ownedThreads()), "Actores y capturador no se reemplazan por fotograma");
        } finally { pump.closeAsync().get(5, TimeUnit.SECONDS); c.closeAsync().get(5, TimeUnit.SECONDS); }
    }
    public static void repeatedSessionsReleaseActorAndCaptureThreads() throws Exception {
        for (int cycle = 0; cycle < 3; cycle++) {
            var c = new SimulationController(new SimulationConfig(2, 3, 100, 100), 0); var pump = new UiSnapshotPump(c);
            try { c.start().get(5, TimeUnit.SECONDS); pump.request(); await(() -> pump.latest().isPresent()); }
            finally { pump.closeAsync().get(5, TimeUnit.SECONDS); c.closeAsync().get(5, TimeUnit.SECONDS); }
            await(() -> ownedThreads().isEmpty());
        }
    }
    public static void renderingCostIsMeasuredAcrossBuildingSizes() throws Exception { edt(() -> {
        for (int[] size : new int[][] {{1, 1}, {3, 10}, {20, 100}, {1000, 100000}}) {
            var r = viewport(size[0], size[1], 800, 450); bottomRight(r); var p = (BuildingPanel) r.getView();
            Rectangle visible = r.getViewRect(); var image = new BufferedImage(800, 450, BufferedImage.TYPE_INT_RGB);
            for (int i = 0; i < 30; i++) paintInto(p, visible, image);
            var bean = ManagementFactory.getThreadMXBean(); boolean cpuSupported = bean.isCurrentThreadCpuTimeSupported();
            long cpuBefore = cpuSupported ? bean.getCurrentThreadCpuTime() : -1;
            long[] samples = new long[240];
            for (int i = 0; i < samples.length; i++) {
                long start = System.nanoTime(); paintInto(p, visible, image); samples[i] = System.nanoTime() - start;
            }
            long cpu = cpuSupported ? bean.getCurrentThreadCpuTime() - cpuBefore : -1;
            Arrays.sort(samples); p.animationChanged(visible);
            System.out.printf(Locale.ROOT,
                    "T61_RENDER|N=%d|P=%d|frames=240|median_ms=%.3f|p95_ms=%.3f|max_ms=%.3f|cpu_ms=%.3f|cached=%d%n",
                    size[0], size[1], samples[120] / 1_000_000.0, samples[227] / 1_000_000.0,
                    samples[239] / 1_000_000.0, cpu < 0 ? -1.0 : cpu / 1_000_000.0, p.cachedAnimationCabins());
        }
    }); }

    private static final class MotionRig {
        final AtomicLong clock = new AtomicLong();
        final AtomicReference<PausableClock.View> view = new AtomicReference<>(new PausableClock.View(0, 0, false));
        final BuildingPanel p = new BuildingPanel(clock::get);
        final Rectangle visible = new Rectangle(0, 0, 800, 450);
        MotionRig() {
            var cfg = new SimulationConfig(1, 4, 1000, 100); p.bind(SESSION, cfg); p.useTimeView(view::get); p.setSize(800, 450);
            var s = new ElevatorSnapshot(1, cfg, 0, 1, 1, OptionalInt.of(2), Direction.UP, ElevatorPhase.MOVING,
                    Optional.empty(), Optional.empty(), ElevatorSnapshot.Mode.NORMAL, 1000 * MS, 1000 * MS, 0,
                    OptionalInt.empty(), List.of(), List.of());
            p.render(new UiSnapshotPump.Frame(SESSION, 0, List.of(s), Map.of()), 0);
        }
    }
    private static JViewport viewport(int n, int floors, int width, int height) {
        var p = new BuildingPanel(); p.bind(SESSION, new SimulationConfig(n, floors, 1000, 100));
        return attach(p, width, height);
    }
    private static JViewport attach(BuildingPanel p, int width, int height) {
        var r = new JViewport(); r.setSize(width, height); r.setView(p); r.doLayout(); return r;
    }
    private static void bottomRight(JViewport r) {
        var p = r.getView(); r.setViewPosition(new Point(Math.max(0, p.getWidth() - r.getWidth()), Math.max(0, p.getHeight() - r.getHeight())));
    }
    private static BufferedImage paint(BuildingPanel p, Rectangle visible) {
        var image = new BufferedImage(visible.width, visible.height, BufferedImage.TYPE_INT_RGB); paintInto(p, visible, image); return image;
    }
    private static void paintInto(BuildingPanel p, Rectangle visible, BufferedImage image) {
        var g = image.createGraphics();
        try { g.translate(-visible.x, -visible.y); g.setClip(visible); p.paint(g); } finally { g.dispose(); }
    }
    private static int ink(BufferedImage image, Rectangle area) {
        int count = 0; var bounds = area.intersection(new Rectangle(0, 0, image.getWidth(), image.getHeight()));
        for (int y = bounds.y; y < bounds.y + bounds.height; y++) for (int x = bounds.x; x < bounds.x + bounds.width; x++) {
            var color = new Color(image.getRGB(x, y));
            if (color.getRed() < 130 && color.getGreen() < 130 && color.getBlue() < 160) count++;
        }
        return count;
    }
    private static Set<Long> ownedThreads() {
        var ids = new HashSet<Long>();
        for (var thread : Thread.getAllStackTraces().keySet()) if (thread.isAlive() && (thread.getName().startsWith("elevator-worker-")
                || thread.getName().startsWith("manager-worker-") || thread.getName().equals("ui-snapshot-capture"))) ids.add(thread.getId());
        return ids;
    }
    private static void check(boolean condition, String detail) { if (!condition) throw new AssertionError(detail); }
    private static <T> T onEdt(Callable<T> action) throws Exception {
        var task = new FutureTask<>(action); SwingUtilities.invokeAndWait(task); return task.get(8, TimeUnit.SECONDS);
    }
    private static void edt(Runnable action) throws Exception { onEdt(() -> { action.run(); return null; }); }
    private static void await(BooleanSupplier condition) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean()) { check(System.nanoTime() < end, "Plazo agotado"); Thread.sleep(5); }
    }
    public static void main(String[] args) throws Exception {
        floorLabelsRemainVisibleAfterHorizontalScroll(); cabinHeadersRemainVisibleAfterVerticalScroll();
        largeFontAndIdsHaveRoomAndVisibleLabels(); oneFloorCabinDoesNotOverlapAxes();
        narrowViewportStillReachesLastCabinAndGroundFloor(); resizingInvalidatesOnlyGeometryCache();
        pausedWallTimeDoesNotRequestRepeatedPaints(); subpixelTicksDoNotRequestIdenticalRasters();
        freshIdleFramesDoNotRequestIdenticalRasters(); hiddenPanelDoesNotScheduleAnimationRepaints();
        animationCacheIsBoundedByViewport(); paintingFramesDoesNotCreateThreads(); edtProcessesPulsesWhilePainting();
        realActorsAndCaptureKeepTheirThreadIdentities(); repeatedSessionsReleaseActorAndCaptureThreads();
        renderingCostIsMeasuredAcrossBuildingSizes(); System.out.println("T61: 16 escenarios visuales satisfactorios.");
    }
}
