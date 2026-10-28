package com.elevatormanager.ui;

import com.elevatormanager.simulation.BatchImportResult;
import com.elevatormanager.simulation.SimulationController;
import java.awt.Component;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.swing.JFileChooser;
import javax.swing.SwingUtilities;
import javax.swing.filechooser.FileNameExtensionFilter;

/** T46: selección en EDT; cancelar el diálogo no llama al lector ni cancela otra lectura. */
public final class RequestImportHandler {
    public enum Outcome { CANCELLED, NOT_ALLOWED, FINISHED, STALE }
    public record Result(Outcome outcome, String detail, Optional<BatchImportResult> imported) {
        public Result {
            Objects.requireNonNull(outcome, "outcome"); Objects.requireNonNull(detail, "detail");
            Objects.requireNonNull(imported, "imported");
            if (detail.isBlank() || imported.isPresent() != (outcome == Outcome.FINISHED || outcome == Outcome.STALE)) {
                throw new IllegalArgumentException("Resultado de selección incoherente.");
            }
        }
    }
    private final SimulationController controller;
    private final Supplier<Optional<Path>> selector;
    private final Consumer<Result> onResult;
    private final Consumer<Throwable> onFailure;
    private final Consumer<Runnable> postToEdt;
    private boolean choosing;

    public RequestImportHandler(SimulationController controller, Supplier<Optional<Path>> selector,
            Consumer<Result> onResult, Consumer<Throwable> onFailure) {
        this(controller, selector, onResult, onFailure, SwingUtilities::invokeLater);
    }

    /** El paquete puede diferir la entrega para probar respuestas obsoletas sin bloquear el EDT. */
    RequestImportHandler(SimulationController controller, Supplier<Optional<Path>> selector,
            Consumer<Result> onResult, Consumer<Throwable> onFailure, Consumer<Runnable> postToEdt) {
        this.controller = Objects.requireNonNull(controller, "controller");
        this.selector = Objects.requireNonNull(selector, "selector");
        this.onResult = Objects.requireNonNull(onResult, "onResult");
        this.onFailure = Objects.requireNonNull(onFailure, "onFailure");
        this.postToEdt = Objects.requireNonNull(postToEdt, "postToEdt");
    }

    public static RequestImportHandler forDialog(SimulationController controller, Component owner,
            Consumer<Result> onResult, Consumer<Throwable> onFailure) {
        return new RequestImportHandler(controller, () -> choose(owner), onResult, onFailure);
    }

    public void requestImport() {
        requireEdt();
        if (choosing || !controller.state().allowsRequestImport()) {
            onResult.accept(new Result(Outcome.NOT_ALLOWED,
                    choosing ? "Ya existe un selector abierto." : "La carga de archivos requiere RESET.", Optional.empty()));
            return;
        }
        Optional<Path> selected;
        choosing = true;
        try { selected = Objects.requireNonNull(selector.get(), "El selector no devolvió una selección válida."); }
        catch (RuntimeException error) { onFailure.accept(error); return; }
        finally { choosing = false; }
        if (selected.isEmpty()) {
            onResult.accept(new Result(Outcome.CANCELLED, "Selección cancelada; lote preparado conservado.", Optional.empty()));
            return;
        }
        try {
            controller.importRequests(selected.orElseThrow()).whenComplete((result, error) ->
                    postToEdt.accept(() -> finish(result, error)));
        } catch (RuntimeException error) { onFailure.accept(error); }
    }

    private void finish(BatchImportResult result, Throwable error) {
        requireEdt();
        if (error != null) {
            Throwable cause = error instanceof java.util.concurrent.CompletionException && error.getCause() != null
                    ? error.getCause() : error;
            onFailure.accept(cause);
            return;
        }
        boolean current = controller.isCurrent(result);
        onResult.accept(new Result(current ? Outcome.FINISHED : Outcome.STALE,
                current ? result.detail() : "La simulación o la lectura cambió; este resultado ya no se aplica.", Optional.of(result)));
    }

    private static Optional<Path> choose(Component owner) {
        requireEdt();
        var chooser = new JFileChooser();
        chooser.setDialogTitle("Cargar solicitudes");
        chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
        chooser.setFileFilter(new FileNameExtensionFilter("Archivos de solicitudes (*.txt)", "txt"));
        // El filtro de texto es una ayuda: otras extensiones siguen permitidas por el contrato.
        int decision = chooser.showOpenDialog(owner);
        if (decision == JFileChooser.CANCEL_OPTION) { return Optional.empty(); }
        if (decision != JFileChooser.APPROVE_OPTION || chooser.getSelectedFile() == null) {
            throw new IllegalStateException("No se pudo completar la selección del archivo.");
        }
        return Optional.of(chooser.getSelectedFile().toPath());
    }
    private static void requireEdt() {
        if (!SwingUtilities.isEventDispatchThread()) { throw new IllegalStateException("La selección de archivos requiere EDT."); }
    }
}
