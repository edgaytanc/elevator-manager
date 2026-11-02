# T61 — Código Java completo

Los tres archivos de producción/pruebas añadidos o modificados por T61, íntegros y sin parches ni elipsis. El ZIP contiene todas las fuentes anteriores.

## src/main/java/com/elevatormanager/ui/BuildingPanel.java

```java
package com.elevatormanager.ui;

import com.elevatormanager.model.ElevatorSnapshot;
import com.elevatormanager.simulation.time.PausableClock;
import com.elevatormanager.model.SimulationConfig;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import javax.swing.JPanel;
import javax.swing.JViewport;
import javax.swing.Scrollable;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;

/** T59: edificio virtual interpolado desde snapshots y reloj activo muestreado.
 * La representación nunca modifica el piso lógico ni confirma llegadas.
 */
public final class BuildingPanel extends JPanel implements Scrollable {
    private static final long serialVersionUID = 1L;
    static final Color CABIN_COLOR = new Color(34, 100, 176);
    private UUID sessionId;
    private SimulationConfig config;
    private List<ElevatorSnapshot> snapshots = List.of();
    private UiSnapshotPump.Frame rendered;
    private final LongSupplier wallClock;
    private Supplier<PausableClock.View> timeView;
    private Long frozenActive;
    private boolean frozenResumed;
    private boolean animationClosed;
    private Rectangle lastAnimationView;
    private int lastAnimationWidth, lastAnimationHeight, lastFirstCabin;
    private int[] lastAnimationY = new int[0];

    public BuildingPanel() { this(System::nanoTime); }

    /** Reloj de presentación inyectable; no sustituye el reloj del motor. */
    BuildingPanel(LongSupplier wallClock) {
        requireEdt();
        this.wallClock = Objects.requireNonNull(wallClock, "wallClock");
        setBackground(Color.WHITE);
        getAccessibleContext().setAccessibleName("Edificio: pisos y cabinas identificados por número");
    }

    public void bind(UUID session, SimulationConfig configuration) {
        requireEdt();
        sessionId = Objects.requireNonNull(session, "session");
        config = Objects.requireNonNull(configuration, "configuration");
        snapshots = List.of(); rendered = null; timeView = null; frozenActive = null; frozenResumed = false; animationClosed = false;
        lastAnimationView = null; lastAnimationY = new int[0];
        revalidate(); repaint();
    }

    /** El proveedor de producción solo lee una referencia volatile, sin locks. */
    public void useTimeView(Supplier<PausableClock.View> source) {
        requireEdt(); timeView = Objects.requireNonNull(source, "source");
    }

    /** Cierre: conserva la posición mientras se liberan actores y capturas. */
    public void freezeAnimation() {
        requireEdt();
        if (animationClosed) return;
        animationClosed = true;
        long now = wallClock.getAsLong();
        if (timeView != null) {
            var view = timeView.get(); frozenActive = view.at(now); frozenResumed = !view.paused();
        } else if (rendered != null) frozenActive = rendered.timing().map(t -> t.at(now)).orElse(null);
    }

    /** Solo acepta la sesión/época vigente. Reutiliza el conjunto inmutable de T52. */
    public void render(UiSnapshotPump.Frame frame, long currentEpoch) {
        requireEdt();
        if (animationClosed || config == null || !frame.sessionId().equals(sessionId) || frame.epoch() != currentEpoch
                || frame.snapshots().size() != config.elevatorCount() || frame == rendered) return;
        snapshots = frame.snapshots(); rendered = frame;
        refreshAnimation();
    }

    @Override public Dimension getPreferredSize() {
        if (config == null) return new Dimension(600, 320);
        var metrics = getFontMetrics(getFont());
        int lane = Math.max(112, metrics.stringWidth("Elevador " + config.elevatorCount()) + 24);
        int row = Math.max(36, metrics.getHeight() + 12);
        return new Dimension(extent(config.elevatorCount(), lane, Geometry.LEFT + Geometry.RIGHT),
                extent(config.floorCount(), row, Geometry.TOP + Geometry.BOTTOM));
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
                if (floor == lastFloor) break;
            }
            long wallNow = wallClock.getAsLong();
            var currentTime = timeView == null ? null : timeView.get();
            int firstCabin = layout.cabinAt(clip.x);
            int lastCabin = layout.cabinAt((double) clip.x + clip.width);
            for (int id = firstCabin; ; id++) {
                int x = layout.cabinX(id);
                int shaftWidth = Math.max(1, (int) Math.min(96, layout.laneWidth() - 12));
                g.setColor(new Color(231, 237, 244));
                g.fillRect(x - shaftWidth / 2, Geometry.TOP, shaftWidth,
                        layout.height() - Geometry.TOP - Geometry.BOTTOM);
                Rectangle cabin = layout.cabinBounds(id, positionAt(id, wallNow, currentTime));
                if (clip.intersects(cabin)) {
                    g.setColor(CABIN_COLOR);
                    g.fillRoundRect(cabin.x, cabin.y, cabin.width, cabin.height, 8, 8);
                    g.setColor(Color.WHITE);
                    paintFitted(g, "E" + id, new Rectangle(cabin.x + 4, cabin.y + 2,
                            Math.max(1, cabin.width - 8), Math.max(1, cabin.height - 4)));
                }
                if (id == lastCabin) break;
            }
            paintAxes(g, layout, getVisibleRect(), clip);
        } finally { g.dispose(); }
    }

    /** T61: ejes adheridos al viewport, sin componentes por piso o cabina. */
    private void paintAxes(Graphics2D g, Geometry layout, Rectangle visible, Rectangle clip) {
        var labels = (Graphics2D) g.create();
        try {
            labels.setColor(Color.WHITE);
            labels.fillRect(visible.x, visible.y, Geometry.LEFT, visible.height);
            labels.fillRect(visible.x, visible.y, visible.width, Geometry.TOP);
            labels.setColor(new Color(32, 46, 64));
            var rows = (Graphics2D) labels.create();
            try {
                rows.clip(new Rectangle(visible.x, visible.y + Geometry.TOP, Geometry.LEFT,
                        Math.max(0, visible.height - Geometry.TOP)));
                int first = layout.floorAt((double) clip.y + clip.height), last = layout.floorAt(clip.y);
                int h = Math.max(1, (int) Math.min(32, layout.floorHeight()));
                for (int floor = first; ; floor++) {
                    paintFitted(rows, "Piso " + floor, new Rectangle(visible.x + 4,
                            layout.floorY(floor) - h / 2, Geometry.LEFT - 8, h));
                    if (floor == last) break;
                }
            } finally { rows.dispose(); }
            var columns = (Graphics2D) labels.create();
            try {
                columns.clip(new Rectangle(visible.x + Geometry.LEFT, visible.y,
                        Math.max(0, visible.width - Geometry.LEFT), Geometry.TOP));
                int first = layout.cabinAt(clip.x), last = layout.cabinAt((double) clip.x + clip.width);
                int w = Math.max(1, (int) Math.min(Integer.MAX_VALUE - 16.0, layout.laneWidth() - 8));
                for (int id = first; ; id++) {
                    var title = new Rectangle(layout.cabinX(id) - w / 2, visible.y + 4, w, Geometry.TOP - 8);
                    if (title.x >= visible.x + Geometry.LEFT
                            && (long) title.x + title.width <= (long) visible.x + visible.width)
                        paintFitted(columns, "Elevador " + id, title);
                    if (id == last) break;
                }
            } finally { columns.dispose(); }
            paintFitted(labels, "Edificio", new Rectangle(visible.x + 4, visible.y + 4,
                    Geometry.LEFT - 8, Geometry.TOP - 8));
            labels.setColor(new Color(190, 201, 214));
            labels.drawLine(visible.x + Geometry.LEFT, visible.y,
                    visible.x + Geometry.LEFT, visible.y + visible.height);
            labels.drawLine(visible.x, visible.y + Geometry.TOP,
                    visible.x + visible.width, visible.y + Geometry.TOP);
        } finally { labels.dispose(); }
    }

    private static void paintFitted(Graphics2D g, String text, Rectangle bounds) {
        if (bounds.width < 1 || bounds.height < 1) return;
        Font original = g.getFont();
        var metrics = g.getFontMetrics();
        float scale = Math.min(1f, Math.min((float) bounds.width / Math.max(1, metrics.stringWidth(text)),
                (float) bounds.height / Math.max(1, metrics.getHeight())));
        if (scale < 1f) g.setFont(original.deriveFont(original.getSize2D() * scale));
        metrics = g.getFontMetrics();
        g.drawString(text, bounds.x + (bounds.width - metrics.stringWidth(text)) / 2,
                bounds.y + (bounds.height - metrics.getHeight()) / 2 + metrics.getAscent());
        g.setFont(original);
    }

    int displayedFloor(int id) {
        requireEdt();
        if (config == null || id < 1 || id > config.elevatorCount())
            throw new IllegalArgumentException("Cabina fuera de rango.");
        return snapshots.isEmpty() ? 1 : snapshots.get(id - 1).currentFloor();
    }

    /** Piso fraccional de presentación, distinto de displayedFloor (piso lógico). */
    double displayedPosition(int id) {
        requireEdt();
        return positionAt(id, wallClock.getAsLong(), timeView == null ? null : timeView.get());
    }

    private double positionAt(int id, long wallNow, PausableClock.View currentTime) {
        int logicalFloor = displayedFloor(id);
        if (snapshots.isEmpty()) return logicalFloor;
        var snapshot = snapshots.get(id - 1);
        long active = frozenActive != null ? frozenActive : currentTime != null ? currentTime.at(wallNow)
                : rendered.timing().map(sample -> sample.at(wallNow)).orElse(snapshot.referenceNanos());
        return CabinMotion.position(snapshot, active,
                frozenActive != null ? frozenResumed : currentTime != null && !currentTime.paused());
    }

    /** Un tick solo invalida la región visible; no captura ni recorre todo N/P. */
    void refreshAnimation() {
        requireEdt();
        if (!animationClosed && config != null && isShowing()) {
            var visible = getVisibleRect();
            if (animationChanged(visible)) repaint(visible);
        }
    }

    /** Caché O(columnas visibles): omite reposo, pausa y movimiento fuera del viewport. */
    boolean animationChanged(Rectangle visible) {
        requireEdt();
        if (animationClosed || config == null || visible.width <= 0 || visible.height <= 0) return false;
        var layout = geometry();
        int first = layout.cabinAt(visible.x), last = layout.cabinAt((double) visible.x + visible.width);
        int count = last - first + 1;
        boolean changed = lastAnimationView == null || !lastAnimationView.equals(visible)
                || lastAnimationWidth != getWidth() || lastAnimationHeight != getHeight()
                || lastFirstCabin != first || lastAnimationY.length != count;
        if (lastAnimationY.length != count) lastAnimationY = new int[count];
        long now = wallClock.getAsLong();
        var view = timeView == null ? null : timeView.get();
        for (int index = 0; index < count; index++) {
            int id = first + index;
            var bounds = layout.cabinBounds(id, positionAt(id, now, view));
            int y = visible.intersects(bounds) ? bounds.y : Integer.MIN_VALUE;
            if (lastAnimationY[index] != y) changed = true;
            lastAnimationY[index] = y;
        }
        if (lastAnimationView == null || !lastAnimationView.equals(visible)) lastAnimationView = new Rectangle(visible);
        lastAnimationWidth = getWidth(); lastAnimationHeight = getHeight(); lastFirstCabin = first;
        return changed;
    }

    int cachedAnimationCabins() { requireEdt(); return lastAnimationY.length; }

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
        int floorY(double floor) { return (int) Math.round(TOP + (floors - floor + 0.5) * floorHeight()); }
        int cabinX(int id) { return (int) Math.round(LEFT + (id - 0.5) * laneWidth()); }
        int floorAt(double y) {
            return (int) Math.max(1, Math.min(floors, floors - Math.floor((y - TOP) / floorHeight())));
        }
        int cabinAt(double x) {
            return (int) Math.max(1, Math.min(cabins, 1 + Math.floor((x - LEFT) / laneWidth())));
        }
        Rectangle cabinBounds(int id, double floor) {
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

## src/test/java/com/elevatormanager/ui/VisualQualityScenarios.java

```java
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
```

## src/test/java/com/elevatormanager/ui/VisualQualityTest.java

```java
package com.elevatormanager.ui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Adaptadores JUnit T61. */
@Timeout(30)
class VisualQualityTest {
    @Test void floorLabelsRemainVisibleAfterHorizontalScroll() throws Exception {
        VisualQualityScenarios.floorLabelsRemainVisibleAfterHorizontalScroll();
    }
    @Test void cabinHeadersRemainVisibleAfterVerticalScroll() throws Exception {
        VisualQualityScenarios.cabinHeadersRemainVisibleAfterVerticalScroll();
    }
    @Test void largeFontAndIdsHaveRoomAndVisibleLabels() throws Exception {
        VisualQualityScenarios.largeFontAndIdsHaveRoomAndVisibleLabels();
    }
    @Test void oneFloorCabinDoesNotOverlapAxes() throws Exception {
        VisualQualityScenarios.oneFloorCabinDoesNotOverlapAxes();
    }
    @Test void narrowViewportStillReachesLastCabinAndGroundFloor() throws Exception {
        VisualQualityScenarios.narrowViewportStillReachesLastCabinAndGroundFloor();
    }
    @Test void resizingInvalidatesOnlyGeometryCache() throws Exception {
        VisualQualityScenarios.resizingInvalidatesOnlyGeometryCache();
    }
    @Test void pausedWallTimeDoesNotRequestRepeatedPaints() throws Exception {
        VisualQualityScenarios.pausedWallTimeDoesNotRequestRepeatedPaints();
    }
    @Test void subpixelTicksDoNotRequestIdenticalRasters() throws Exception {
        VisualQualityScenarios.subpixelTicksDoNotRequestIdenticalRasters();
    }
    @Test void freshIdleFramesDoNotRequestIdenticalRasters() throws Exception {
        VisualQualityScenarios.freshIdleFramesDoNotRequestIdenticalRasters();
    }
    @Test void hiddenPanelDoesNotScheduleAnimationRepaints() throws Exception {
        VisualQualityScenarios.hiddenPanelDoesNotScheduleAnimationRepaints();
    }
    @Test void animationCacheIsBoundedByViewport() throws Exception {
        VisualQualityScenarios.animationCacheIsBoundedByViewport();
    }
    @Test void paintingFramesDoesNotCreateThreads() throws Exception {
        VisualQualityScenarios.paintingFramesDoesNotCreateThreads();
    }
    @Test void edtProcessesPulsesWhilePainting() throws Exception {
        VisualQualityScenarios.edtProcessesPulsesWhilePainting();
    }
    @Test void realActorsAndCaptureKeepTheirThreadIdentities() throws Exception {
        VisualQualityScenarios.realActorsAndCaptureKeepTheirThreadIdentities();
    }
    @Test void repeatedSessionsReleaseActorAndCaptureThreads() throws Exception {
        VisualQualityScenarios.repeatedSessionsReleaseActorAndCaptureThreads();
    }
    @Test void renderingCostIsMeasuredAcrossBuildingSizes() throws Exception {
        VisualQualityScenarios.renderingCostIsMeasuredAcrossBuildingSizes();
    }
}
```
