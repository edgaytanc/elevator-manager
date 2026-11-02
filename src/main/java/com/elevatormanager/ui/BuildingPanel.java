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
