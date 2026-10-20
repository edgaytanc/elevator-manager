package com.elevatormanager.diagnostics;

import java.awt.BorderLayout;
import java.awt.GraphicsEnvironment;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntConsumer;
import java.util.logging.ConsoleHandler;
import java.util.logging.FileHandler;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.border.EmptyBorder;

/** Comprobación aislada de Swing, hilos y logging para T09. */
public final class EnvironmentCheck {
    private static final int STEPS = 5;
    private static final Path LOG_PATH = Path.of("logs", "t09-entorno.log");

    private EnvironmentCheck() {
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        boolean consoleOnly = args.length == 1 && "--console".equals(args[0]);
        if (args.length > 0 && !consoleOnly) {
            throw new IllegalArgumentException("Uso: EnvironmentCheck [--console]");
        }
        Logger logger = createLogger();
        if (consoleOnly) {
            AtomicBoolean completed = new AtomicBoolean();
            Thread worker = new Thread(
                    () -> completed.set(runWork(logger, step -> { })), "t09-worker");
            try {
                worker.start();
                worker.join();
                if (!completed.get()) {
                    throw new IllegalStateException("La comprobación no terminó correctamente.");
                }
                logger.info("CONSOLA_VERIFICADA; Swing queda pendiente de comprobación visual.");
            } finally {
                worker.interrupt();
                joinWorker(worker);
                closeLogger(logger);
            }
            return;
        }
        if (GraphicsEnvironment.isHeadless()) {
            logger.severe("SIN_ENTORNO_GRAFICO: no es posible comprobar Swing en este entorno.");
            closeLogger(logger);
            throw new IllegalStateException("Se requiere escritorio gráfico; alternativa: --console.");
        }
        SwingUtilities.invokeLater(() -> showWindow(logger));
    }

    private static Logger createLogger() throws IOException {
        Files.createDirectories(LOG_PATH.toAbsolutePath().getParent());
        Logger logger = Logger.getLogger(EnvironmentCheck.class.getName());
        logger.setUseParentHandlers(false);
        logger.setLevel(Level.INFO);
        FileHandler file = new FileHandler(LOG_PATH.toString(), false);
        try {
            file.setEncoding("UTF-8");
            file.setFormatter(new SimpleFormatter());
            ConsoleHandler console = new ConsoleHandler();
            console.setEncoding("UTF-8");
            console.setFormatter(new SimpleFormatter());
            logger.addHandler(file);
            logger.addHandler(console);
        } catch (IOException | RuntimeException exception) {
            file.close();
            throw exception;
        }
        logger.info("INICIO_T09; archivo=" + LOG_PATH.toAbsolutePath());
        return logger;
    }

    private static void showWindow(Logger logger) {
        JFrame window = new JFrame("T09 - Comprobación del entorno");
        window.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        JLabel status = new JLabel("Trabajo de fondo: 0/5");
        JLabel heartbeat = new JLabel("Pulsos del EDT: 0");
        JButton response = new JButton("Probar respuesta de la ventana: 0");
        int[] clicks = {0};
        int[] pulses = {0};
        response.addActionListener(event -> response.setText(
                "Probar respuesta de la ventana: " + ++clicks[0]));
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.setBorder(new EmptyBorder(16, 16, 16, 16));
        panel.add(status, BorderLayout.NORTH);
        panel.add(response, BorderLayout.CENTER);
        panel.add(heartbeat, BorderLayout.SOUTH);
        window.setContentPane(panel);
        window.setSize(540, 180);
        window.setLocationRelativeTo(null);
        AtomicBoolean closing = new AtomicBoolean();
        Timer timer = new Timer(250, event -> heartbeat.setText(
                "Pulsos del EDT: " + ++pulses[0]));
        Thread worker = new Thread(() -> {
            boolean completed = runWork(logger, step -> SwingUtilities.invokeLater(() -> {
                if (!closing.get()) {
                    status.setText("Trabajo de fondo: " + step + "/" + STEPS);
                }
            }));
            SwingUtilities.invokeLater(() -> {
                if (!closing.get()) {
                    status.setText(completed
                            ? "Comprobación completada: 5/5. Puedes cerrar la ventana."
                            : "Trabajo interrumpido o fallido; revisa la consola.");
                }
            });
        }, "t09-worker");
        window.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent event) {
                if (!closing.compareAndSet(false, true)) {
                    return;
                }
                timer.stop();
                response.setEnabled(false);
                status.setText("Cerrando y liberando recursos...");
                worker.interrupt();
                new Thread(() -> {
                    logger.info("CIERRE_SOLICITADO");
                    joinWorker(worker);
                    logger.info("CIERRE_COMPLETADO; trabajador terminado.");
                    closeLogger(logger);
                    SwingUtilities.invokeLater(window::dispose);
                }, "t09-shutdown").start();
            }
        });
        timer.start();
        worker.start();
        window.setVisible(true);
    }

    private static boolean runWork(Logger logger, IntConsumer progress) {
        try {
            logger.info("HILO_INICIADO; nombre=" + Thread.currentThread().getName()
                    + "; EDT=" + SwingUtilities.isEventDispatchThread());
            if (SwingUtilities.isEventDispatchThread()) {
                throw new IllegalStateException("El trabajo no puede ejecutarse en el EDT.");
            }
            for (int step = 1; step <= STEPS; step++) {
                Thread.sleep(1000);
                logger.info("PASO " + step + "/" + STEPS
                        + "; hilo=" + Thread.currentThread().getName());
                progress.accept(step);
            }
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("Cierre solicitado al completar los pasos.");
            }
            logger.info("TRABAJO_COMPLETADO");
            return true;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            logger.info("TRABAJO_INTERRUMPIDO; se solicitó detener el trabajador.");
            return false;
        } catch (RuntimeException exception) {
            logger.log(Level.SEVERE, "TRABAJO_FALLIDO", exception);
            return false;
        } finally {
            logger.info("HILO_FINALIZANDO; nombre=" + Thread.currentThread().getName());
        }
    }

    private static void joinWorker(Thread worker) {
        boolean interrupted = false;
        while (worker.isAlive()) {
            try {
                worker.join();
            } catch (InterruptedException exception) {
                interrupted = true;
                worker.interrupt();
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static void closeLogger(Logger logger) {
        for (Handler handler : logger.getHandlers()) {
            handler.close();
            logger.removeHandler(handler);
        }
    }
}
