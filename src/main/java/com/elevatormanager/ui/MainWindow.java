package com.elevatormanager.ui;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;

/** Entrada gráfica T47; su disposición espera la confirmación de cierre asíncrono. */
public final class MainWindow {
    private static final Logger LOGGER = Logger.getLogger(MainWindow.class.getName());
    private final JFrame frame;

    /** T47 valida parámetros; T48 conectará la creación y los controles de sesión. */
    public MainWindow() { this(() -> CompletableFuture.completedFuture(null)); }

    /** Una sesión existente se conecta con controller::closeAsync. */
    public MainWindow(Supplier<? extends CompletionStage<?>> closeAction) {
        requireEventDispatchThread();
        frame = new JFrame("Elevator Manager");
        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        JPanel content = new JPanel(new BorderLayout(12, 12));
        content.setBorder(BorderFactory.createEmptyBorder(24, 24, 24, 24));
        JPanel messages = new JPanel(new GridLayout(0, 1, 0, 12));
        messages.add(new JLabel("Elevator Manager", SwingConstants.CENTER));
        messages.add(new JLabel("Simulador de elevadores", SwingConstants.CENTER));
        JLabel status = new JLabel("Listo.", SwingConstants.CENTER);
        messages.add(status);
        content.add(messages, BorderLayout.NORTH);
        var configuration = new ConfigurationPanel();
        content.add(new JScrollPane(configuration), BorderLayout.CENTER);
        JButton closeButton = new JButton("Cerrar");
        var close = new WindowCloseHandler(closeAction,
                () -> {
                    closeButton.setEnabled(false);
                    setTreeEnabled(configuration, false);
                    status.setText("Cerrando y liberando recursos...");
                },
                frame::dispose,
                error -> {
                    status.setText("No se pudo completar el cierre. Consulta el registro.");
                    closeButton.setEnabled(true);
                    setTreeEnabled(configuration, true);
                    LOGGER.log(Level.SEVERE, "CIERRE_NO_COMPLETADO", error);
                });
        closeButton.addActionListener(event -> close.requestClose());
        frame.addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent event) { close.requestClose(); }
        });
        JPanel actions = new JPanel();
        actions.add(closeButton);
        content.add(actions, BorderLayout.SOUTH);
        frame.setContentPane(content);
        frame.setMinimumSize(new Dimension(760, 540));
        frame.pack();
        frame.setLocationRelativeTo(null);
    }

    public void showWindow() {
        requireEventDispatchThread();
        frame.setVisible(true);
    }

    private static void setTreeEnabled(java.awt.Component component, boolean enabled) {
        component.setEnabled(enabled);
        if (component instanceof java.awt.Container container) {
            for (var child : container.getComponents()) { setTreeEnabled(child, enabled); }
        }
    }

    private static void requireEventDispatchThread() {
        if (!SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("La ventana debe utilizarse desde el EDT.");
        }
    }
}
