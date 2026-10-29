package com.elevatormanager.ui;

import java.awt.BorderLayout;
import java.awt.Dimension;
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
        frame.setMinimumSize(new Dimension(820, 760));
        frame.pack();
        frame.setLocationRelativeTo(null);
    }

    public void showWindow() { requireEventDispatchThread(); frame.setVisible(true); }

    private static void requireEventDispatchThread() {
        if (!SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("La ventana debe utilizarse desde el EDT.");
        }
    }
}
