package com.elevatormanager.ui;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.GridLayout;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;

/**
 * Ventana mínima de arranque. Su creación y uso pertenecen al EDT.
 */
public final class MainWindow {

    private final JFrame frame;

    public MainWindow() {
        requireEventDispatchThread();
        frame = new JFrame("Elevator Manager");
        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);

        JPanel content = new JPanel(new BorderLayout(12, 12));
        content.setBorder(BorderFactory.createEmptyBorder(24, 24, 24, 24));

        JPanel messages = new JPanel(new GridLayout(0, 1, 0, 12));
        messages.add(new JLabel("Elevator Manager", SwingConstants.CENTER));
        messages.add(new JLabel("T11: arranque gráfico preparado.",
                SwingConstants.CENTER));
        messages.add(new JLabel("La simulación se incorporará en las siguientes tareas.",
                SwingConstants.CENTER));
        content.add(messages, BorderLayout.CENTER);

        JButton closeButton = new JButton("Cerrar");
        closeButton.addActionListener(event -> frame.dispose());
        JPanel actions = new JPanel();
        actions.add(closeButton);
        content.add(actions, BorderLayout.SOUTH);

        frame.setContentPane(content);
        frame.setMinimumSize(new Dimension(560, 240));
        frame.pack();
        frame.setLocationRelativeTo(null);
    }

    /**
     * Muestra la ventana desde el hilo de eventos de Swing.
     */
    public void showWindow() {
        requireEventDispatchThread();
        frame.setVisible(true);
    }

    private static void requireEventDispatchThread() {
        if (!SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("La ventana debe utilizarse desde el EDT.");
        }
    }
}
