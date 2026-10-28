package com.elevatormanager.diagnostics;

import com.elevatormanager.model.SimulationConfig;
import com.elevatormanager.simulation.SimulationController;
import com.elevatormanager.ui.RequestImportHandler;
import com.elevatormanager.ui.WindowCloseHandler;
import java.awt.BorderLayout;
import java.awt.GraphicsEnvironment;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;

/** T46: selector real para revisar cancelación y errores; no sustituye la ventana principal. */
public final class FileSelectionDemo {
    private FileSelectionDemo() { }
    public static void main(String[] args) {
        if (GraphicsEnvironment.isHeadless()) { throw new IllegalStateException("El diagnóstico requiere un entorno gráfico."); }
        var controller = new SimulationController(new SimulationConfig(2,8,30,10),0);
        SwingUtilities.invokeLater(() -> {
            try { show(controller); }
            catch (RuntimeException | Error error) { controller.closeAsync(); throw error; }
        });
    }
    private static void show(SimulationController controller) {
        var frame = new JFrame("T46 — Archivos de solicitudes");
        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        var content = new JPanel(new BorderLayout(12,12));
        content.setBorder(BorderFactory.createEmptyBorder(16,16,16,16));
        content.add(new JLabel("Configuración: 2 elevadores, 8 pisos. Cargar no inicia movimientos."),BorderLayout.NORTH);
        var messages = new JTextArea(10,72);
        messages.setEditable(false); messages.setLineWrap(true); messages.setWrapStyleWord(true);
        messages.setText("RESET. Ningún lote preparado.\n");
        content.add(new JScrollPane(messages),BorderLayout.CENTER);
        var load = new JButton("Cargar archivo");
        var close = new JButton("Cerrar");
        var handler = RequestImportHandler.forDialog(controller,frame,result -> {
            messages.append(result.detail()+"\n");
            if (result.outcome() == RequestImportHandler.Outcome.FINISHED) { result.imported().ifPresent(imported -> {
                for(var diagnostic:imported.diagnostics()) {
                    messages.append((diagnostic.lineNumber().isPresent()?"Línea "+diagnostic.lineNumber().getAsInt()+": ":"")
                            +diagnostic.message()+"\n");
                    diagnostic.cause().ifPresent(cause -> messages.append("Causa: "+cause.getClass().getSimpleName()+"\n"));
                }
            }); }
            messages.append("Lote preparado actual: "+controller.preparedBatch().map(b->b.requests().size()).orElse(0)+" solicitudes.\n");
        },error -> messages.append("No se pudo completar la selección: "+error.getMessage()+"\n"));
        load.addActionListener(event -> handler.requestImport());
        var closing = new WindowCloseHandler(controller::closeAsync,
                ()->{load.setEnabled(false);close.setEnabled(false);messages.append("Cerrando...\n");},
                frame::dispose,error->{close.setEnabled(true);messages.append("No se confirmó el cierre: "+error.getMessage()+"\n");});
        close.addActionListener(event -> closing.requestClose());
        frame.addWindowListener(new WindowAdapter(){@Override public void windowClosing(WindowEvent event){closing.requestClose();}});
        var buttons = new JPanel();buttons.add(load);buttons.add(close);content.add(buttons,BorderLayout.SOUTH);
        frame.setContentPane(content);frame.pack();frame.setLocationRelativeTo(null);frame.setVisible(true);
    }
}
