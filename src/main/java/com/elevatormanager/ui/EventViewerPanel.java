package com.elevatormanager.ui;

import com.elevatormanager.logging.RecentEventBuffer;
import com.elevatormanager.simulation.SimulationController;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Point;
import java.util.ArrayDeque;
import javax.swing.*;
import javax.swing.text.DefaultCaret;

/** T51: historial visual acotado, actualizado por lotes desde el Timer existente. */
public final class EventViewerPanel extends JPanel {
    private static final long serialVersionUID = 1L;
    public static final int MAX_LINES = 300;
    public static final int MAX_DOCUMENT_CHARS = 120_000;
    private final JTextArea text = new JTextArea(7, 80);
    private final JScrollPane scroll = new JScrollPane(text);
    private final JCheckBox follow = new JCheckBox("Seguir últimos", true);
    private final JButton clear = new JButton("Limpiar visor");
    private final JLabel summary = new JLabel("Valida la configuración para ver eventos.");
    private final ArrayDeque<String> lines = new ArrayDeque<>();
    private SimulationController controller;
    private RecentEventBuffer source;
    private long cursor;
    private long skipped;
    private long evicted;
    private int chars;

    public EventViewerPanel() {
        requireEdt(); setLayout(new BorderLayout(4, 4));
        setBorder(BorderFactory.createTitledBorder("Eventos recientes — registros completos en archivos de sesión"));
        setPreferredSize(new Dimension(600, 190)); setMinimumSize(new Dimension(200, 100));
        text.setEditable(false); text.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        ((DefaultCaret) text.getCaret()).setUpdatePolicy(DefaultCaret.NEVER_UPDATE);
        text.getAccessibleContext().setAccessibleName("Eventos recientes de la simulación");
        var actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        actions.add(follow); actions.add(clear);
        add(actions, BorderLayout.NORTH); add(scroll, BorderLayout.CENTER); add(summary, BorderLayout.SOUTH);
        clear.addActionListener(event -> clearView());
        follow.addActionListener(event -> { if (follow.isSelected()) scrollToEnd(); });
    }

    public void bind(SimulationController session) {
        requireEdt(); controller = java.util.Objects.requireNonNull(session, "session");
        source = session.recentLogEvents().orElse(null);
        cursor = 0; skipped = 0; evicted = 0; chars = 0; lines.clear(); text.setText("");
        refresh();
    }

    /** Hasta 200 registros por refresco; nunca lee archivos ni espera eventos. */
    public void refresh() {
        requireEdt();
        if (source == null) {
            summary.setText(controller == null ? "Valida la configuración para ver eventos."
                    : "Esta sesión no tiene un canal de registros visuales.");
            return;
        }
        var batch = source.after(cursor, RecentEventBuffer.MAX_BATCH);
        cursor = batch.nextCursor(); skipped += batch.skipped();
        if (!batch.events().isEmpty()) {
            Point oldPosition = scroll.getViewport().getViewPosition();
            int start = text.getSelectionStart(); int end = text.getSelectionEnd();
            int removedChars = 0; int removedLines = 0;
            for (var event : batch.events()) {
                String line = event.sequence() + " " + event.at() + " [" + event.level() + "] ["
                        + event.actor() + "] " + event.text() + "\n";
                lines.addLast(line); chars += line.length();
                while (lines.size() > MAX_LINES || chars > MAX_DOCUMENT_CHARS) {
                    int removed = lines.removeFirst().length(); chars -= removed;
                    removedChars += removed; ++removedLines; ++evicted;
                }
            }
            text.setText(String.join("", lines));
            if (follow.isSelected()) { scrollToEnd(); }
            else {
                int length = text.getDocument().getLength();
                text.select(Math.max(0, Math.min(length, start - removedChars)), Math.max(0, Math.min(length, end - removedChars)));
                int lineHeight = text.getFontMetrics(text.getFont()).getHeight();
                scroll.getViewport().setViewPosition(new Point(oldPosition.x, Math.max(0, oldPosition.y - removedLines * lineHeight)));
            }
        }
        String failure = controller.failure().map(error -> " | Fallo de sesión: " + error.getClass().getSimpleName()).orElse("");
        summary.setText("Mostrados: " + lines.size() + " | Retirados del visor: " + evicted + " | No recuperables del búfer: " + skipped + failure);
    }

    /** Solo limpia esta vista y avanza su cursor: nunca borra archivos ni vacía el búfer. */
    public void clearView() {
        requireEdt(); cursor = source == null ? 0 : source.lastSequence();
        lines.clear(); chars = 0; skipped = 0; evicted = 0; text.setText(""); refresh();
    }

    private void scrollToEnd() {
        text.setCaretPosition(text.getDocument().getLength());
        text.scrollRectToVisible(new java.awt.Rectangle(0, Math.max(0, text.getPreferredSize().height - 1), 1, 1));
    }
    String displayedText() { requireEdt(); return text.getText(); }
    int displayedCount() { requireEdt(); return lines.size(); }
    String summaryText() { requireEdt(); return summary.getText(); }
    JCheckBox followControl() { requireEdt(); return follow; }
    private static void requireEdt() {
        if (!SwingUtilities.isEventDispatchThread()) { throw new IllegalStateException("El visor requiere EDT."); }
    }
}
