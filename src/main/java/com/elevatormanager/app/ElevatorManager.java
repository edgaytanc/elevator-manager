package com.elevatormanager.app;

import com.elevatormanager.ui.MainWindow;
import java.util.logging.Logger;
import javax.swing.SwingUtilities;

/**
 * Punto de entrada y composición inicial de la aplicación.
 */
public final class ElevatorManager {

    private static final Logger LOGGER =
            Logger.getLogger(ElevatorManager.class.getName());

    private ElevatorManager() {
        // La clase de entrada no necesita instancias.
    }

    /**
     * Programa el arranque gráfico en el hilo de eventos de Swing.
     *
     * @param args argumentos reservados para futuras versiones
     */
    public static void main(String[] args) {
        LOGGER.info("INICIO_T11; Java=" + System.getProperty("java.version"));
        SwingUtilities.invokeLater(() -> {
            MainWindow window = new MainWindow();
            window.showWindow();
            LOGGER.info("VENTANA_ABIERTA; EDT="
                    + SwingUtilities.isEventDispatchThread());
        });
    }
}
