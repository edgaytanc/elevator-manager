package com.elevatormanager.ui;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Rectangle;
import java.awt.GraphicsEnvironment;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;

/** T48: entrada real de configuración y controles; botón y X comparten cierre confirmado. */
public final class MainWindow {
    private static final Logger LOGGER = Logger.getLogger(MainWindow.class.getName());
    private final JFrame frame;

    public MainWindow() { this(() -> CompletableFuture.completedFuture(null)); }

    /** Conserva la extensión de cierre de T39, después de liberar la sesión de esta ventana. */
    public MainWindow(Supplier<? extends CompletionStage<?>> closeAction) {
        requireEventDispatchThread();
        frame = new JFrame("Elevator Manager");
        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        var controls = new SystemControlPanel();
        var close = new WindowCloseHandler(
                () -> controls.closeAsync().thenCompose(ignored -> {
                    // El proveedor externo puede tener contrato EDT: obtenerlo en ese hilo.
                    var completion = new CompletableFuture<Void>();
                    SwingUtilities.invokeLater(() -> {
                        try {
                            closeAction.get().whenComplete((value, error) -> {
                                if (error == null) { completion.complete(null); }
                                else { completion.completeExceptionally(error); }
                            });
                        } catch (RuntimeException error) { completion.completeExceptionally(error); }
                    });
                    return completion;
                }),
                () -> { },
                () -> { controls.closed(); frame.dispose(); },
                error -> {
                    controls.closeFailed(error);
                    LOGGER.log(Level.SEVERE, "CIERRE_NO_COMPLETADO", error);
                });
        controls.onCloseRequest(close::requestClose);
        frame.addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent event) { close.requestClose(); }
        });
        frame.add(new JLabel("Elevator Manager — Simulador de elevadores", SwingConstants.CENTER), BorderLayout.NORTH);
        frame.add(controls, BorderLayout.CENTER);
        Rectangle usable = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        frame.setMinimumSize(minimumSize(usable));
        frame.pack();
        frame.setBounds(initialBounds(usable));
    }

    public void showWindow() { requireEventDispatchThread(); frame.setVisible(true); }

    /** Geometría pura para probar pantallas pequeñas y coordenadas virtuales sin JFrame. */
    static Rectangle initialBounds(Rectangle usable) {
        if (usable.width < 1 || usable.height < 1) throw new IllegalArgumentException("Área de pantalla inválida.");
        int width = Math.min(1180, usable.width); int height = Math.min(860, usable.height);
        return new Rectangle(usable.x + (usable.width - width) / 2, usable.y + (usable.height - height) / 2, width, height);
    }
    static Dimension minimumSize(Rectangle usable) {
        if (usable.width < 1 || usable.height < 1) throw new IllegalArgumentException("Área de pantalla inválida.");
        return new Dimension(Math.min(720, usable.width), Math.min(480, usable.height));
    }

    private static void requireEventDispatchThread() {
        if (!SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("La ventana debe utilizarse desde el EDT.");
        }
    }
}
