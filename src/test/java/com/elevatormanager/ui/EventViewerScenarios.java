package com.elevatormanager.ui;

import com.elevatormanager.logging.*;
import com.elevatormanager.model.*;
import com.elevatormanager.model.SimulationConfig.Field;
import com.elevatormanager.model.request.CabinRequest;
import com.elevatormanager.simulation.*;
import java.awt.Rectangle;
import java.io.OutputStream;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.*;
import javax.swing.SwingUtilities;

/** T51: retención acotada, archivos íntegros, Swing real y geometría sin JFrame. */
public final class EventViewerScenarios {
    private EventViewerScenarios() { }
    public static void emptyBufferIsReadable() throws Exception {
        var buffer = new RecentEventBuffer(); var batch = buffer.after(0, 10);
        check(batch.events().isEmpty() && batch.nextCursor() == 0 && batch.skipped() == 0, "Búfer vacío");
    }

    public static void sequencesAreOrderedAndCursorDoesNotRepeat() throws Exception {
        var buffer = new RecentEventBuffer(); append(buffer, 3, "event");
        var first = buffer.after(0, 2); var second = buffer.after(first.nextCursor(), 2);
        check(first.events().get(0).sequence() == 1 && first.nextCursor() == 2
                && second.events().size() == 1 && second.nextCursor() == 3, "Cursor monótono");
    }

    public static void recordLimitEvictsOldest() throws Exception {
        var buffer = new RecentEventBuffer(); append(buffer, 700, "event");
        check(buffer.retainedCount() == 500 && buffer.after(0, 1).events().get(0).sequence() == 201, "Anillo por cantidad");
    }

    public static void characterBudgetEvictsEvenBeforeCountLimit() throws Exception {
        var buffer = new RecentEventBuffer(); append(buffer, 400, "x".repeat(1200));
        check(buffer.retainedTextChars() <= 300_000 && buffer.retainedCount() == 250, "Presupuesto de caracteres");
    }

    public static void messageAndMetadataAreBounded() throws Exception {
        var buffer = new RecentEventBuffer(); buffer.append(Instant.now(), "a".repeat(200), "l".repeat(100), "m".repeat(5000));
        var event = buffer.after(0, 1).events().get(0);
        check(event.actor().length() == 64 && event.level().length() == 32 && event.text().length() == 1200, "Límites individuales");
    }

    public static void embeddedNewlinesCannotCreateUnlimitedVisualLines() throws Exception {
        var buffer = new RecentEventBuffer(); buffer.append(Instant.now(), "a\nb", "INFO", "one\ntwo\rthree");
        var event = buffer.after(0, 1).events().get(0);
        check(!event.text().contains("\n") && !event.text().contains("\r") && !event.actor().contains("\n"), "Una entrada visual por registro");
    }

    public static void batchResultsAreImmutable() throws Exception {
        var buffer = new RecentEventBuffer(); append(buffer, 1, "event");
        try { buffer.after(0, 1).events().clear(); throw new AssertionError("Lista mutable"); }
        catch (UnsupportedOperationException expected) { }
    }

    public static void invalidCursorAndBatchAreRejected() throws Exception {
        var buffer = new RecentEventBuffer();
        for (int limit : new int[] {0, -1, 201}) {
            try { buffer.after(0, limit); throw new AssertionError("Límite aceptado"); } catch (IllegalArgumentException expected) { }
        }
        try { buffer.after(-1, 1); throw new AssertionError("Cursor negativo"); } catch (IllegalArgumentException expected) { }
    }

    public static void skippedRecordsAreCountedOnce() throws Exception {
        var buffer = new RecentEventBuffer(); append(buffer, 800, "event");
        var first = buffer.after(0, 200); var next = buffer.after(first.nextCursor(), 200);
        check(first.skipped() == 300 && next.skipped() == 0, "Pérdidas del búfer contadas al avanzar");
    }

    public static void independentReadersDoNotConsumeEachOther() throws Exception {
        var buffer = new RecentEventBuffer(); append(buffer, 10, "event");
        check(buffer.after(0, 10).equals(buffer.after(0, 10)) && buffer.retainedCount() == 10, "Lectura no destructiva");
    }

    public static void concurrentProducersPreserveSequenceAndBounds() throws Exception {
        var buffer = new RecentEventBuffer(); var pool = Executors.newFixedThreadPool(4);
        try {
            var jobs = new ArrayList<Future<?>>(); for (int i = 0; i < 4; i++) jobs.add(pool.submit(() -> append(buffer, 400, "event")));
            for (var job : jobs) job.get(5, TimeUnit.SECONDS);
            check(buffer.lastSequence() == 1600 && buffer.retainedCount() == 500, "Productores concurrentes");
            var batch = buffer.after(0, 200); long last = batch.events().get(0).sequence() - 1;
            for (var event : batch.events()) check(event.sequence() == ++last, "Orden sin duplicación");
        } finally { pool.shutdownNow(); check(pool.awaitTermination(5, TimeUnit.SECONDS), "Pool terminado"); }
    }

    public static void nullInputDoesNotAdvanceSequence() throws Exception {
        var buffer = new RecentEventBuffer();
        try { buffer.append(Instant.now(), null, "INFO", "event"); throw new AssertionError("Actor nulo"); } catch (NullPointerException expected) { }
        check(buffer.lastSequence() == 0 && buffer.retainedCount() == 0, "Sin mutación parcial");
    }

    public static void fullFilesSurviveVisualBufferOverflow() throws Exception {
        try (var rig = new LogRig()) {
            rig.emit(1200, "T51_FLOOD_");
            String file = Files.readString(rig.directory.resolve("manejador.log"));
            check(file.split("evento=T51_FLOOD_", -1).length - 1 == 1200, "Todos los eventos en disco");
            check(rig.log.recentEvents().retainedCount() <= 500 && rig.log.failure().isEmpty(), "Solo se recorta copia visual");
        }
    }

    public static void longRecordIsCompleteInFileButTruncatedInViewerSource() throws Exception {
        try (var rig = new LogRig()) {
            rig.log.mailbox().managerState(0, "T51_LONG_" + "x".repeat(4000) + "TAIL_END", null); rig.flush();
            check(Files.readString(rig.directory.resolve("manejador.log")).contains("TAIL_END"), "Archivo no truncado");
            var event = rig.log.recentEvents().after(0, 1).events().get(0);
            check(event.text().length() <= 1200 && !event.text().contains("TAIL_END"), "Copia limitada");
        }
    }

    public static void exceptionRemainsInFileAndTextSummary() throws Exception {
        try (var rig = new LogRig()) {
            rig.log.mailbox().managerState(0, "T51_FAILURE", new IllegalStateException("t51-exception")); rig.flush();
            var event = rig.log.recentEvents().after(0, 1).events().get(0);
            check(event.level().equals("SEVERE") && event.text().contains("IllegalStateException"), "Resumen de excepción");
            check(Files.readString(rig.directory.resolve("manejador.log")).contains("t51-exception"), "Excepción completa en archivo");
        }
    }

    public static void viewerReadsManagerAndCabinEvents() throws Exception {
        try (var rig = new LogRig()) {
            rig.c.start().get(5, TimeUnit.SECONDS);
            rig.c.submit(new CabinRequest(UUID.randomUUID(), 0, 3, Instant.now(), 1)).get(5, TimeUnit.SECONDS);
            waitFor(() -> rig.c.snapshots().get(0).currentFloor() == 3); rig.flush(); rig.refreshAll();
            edt(() -> check(rig.viewer.displayedText().contains("[Manejador]") && rig.viewer.displayedText().contains("[Elevador 1]")
                    && rig.viewer.displayedText().contains("evento=ARRIVED"), "Eventos reales de ambos productores"));
        }
    }

    public static void viewerDoesNotStealControllerExecutionEvents() throws Exception {
        try (var rig = new LogRig()) {
            rig.c.start().get(5, TimeUnit.SECONDS);
            rig.c.submit(new CabinRequest(UUID.randomUUID(), 0, 2, Instant.now(), 1)).get(5, TimeUnit.SECONDS);
            waitFor(() -> rig.c.snapshots().get(0).currentFloor() == 2); rig.flush(); rig.refreshAll();
            boolean completed = false;
            for (int i = 0; i < 30; i++) {
                var event = rig.c.awaitEvent(Duration.ofMillis(20));
                if (event.isPresent() && event.orElseThrow().type() == ExecutionEvent.Type.COMMAND_COMPLETED) { completed = true; break; }
            }
            check(completed, "awaitEvent conserva su copia independiente");
        }
    }

    public static void viewerPreservesHistoryAcrossReset() throws Exception {
        try (var rig = new LogRig()) {
            rig.c.start().get(5, TimeUnit.SECONDS); rig.log.mailbox().managerState(0, "T51_BEFORE_RESET", null);
            rig.c.reset().get(5, TimeUnit.SECONDS); rig.log.mailbox().managerState(rig.c.epoch(), "T51_AFTER_RESET", null);
            rig.flush(); rig.refreshAll();
            edt(() -> check(rig.viewer.displayedText().contains("T51_BEFORE_RESET") && rig.viewer.displayedText().contains("T51_AFTER_RESET")
                    && rig.viewer.displayedText().contains("epoca=1"), "Historial conserva épocas"));
        }
    }

    public static void clearDoesNotDeleteFilesOrSharedBuffer() throws Exception {
        try (var rig = new LogRig()) {
            rig.emit(10, "T51_CLEAR_"); rig.refreshAll(); String before = Files.readString(rig.directory.resolve("manejador.log"));
            int retained = rig.log.recentEvents().retainedCount();
            edt(() -> { rig.viewer.clearView(); check(rig.viewer.displayedCount() == 0, "Visor limpio"); });
            check(before.equals(Files.readString(rig.directory.resolve("manejador.log"))) && retained == rig.log.recentEvents().retainedCount(), "Archivos y búfer intactos");
        }
    }

    public static void clearAdvancesCursorAndAllowsNewEvents() throws Exception {
        try (var rig = new LogRig()) {
            rig.emit(5, "T51_OLD_"); rig.refreshAll(); edt(rig.viewer::clearView); rig.emit(1, "T51_NEW_"); rig.refreshAll();
            edt(() -> check(!rig.viewer.displayedText().contains("T51_OLD_") && rig.viewer.displayedText().contains("T51_NEW_"), "Sin repetición tras limpiar"));
        }
    }

    public static void oneRefreshReadsAtMostTwoHundredRecords() throws Exception {
        try (var rig = new LogRig()) {
            rig.emit(400, "T51_BATCH_"); edt(rig.viewer::refresh);
            edt(() -> check(rig.viewer.displayedCount() == 200, "Lote visual acotado"));
        }
    }

    public static void visibleLineCountIsBounded() throws Exception {
        try (var rig = new LogRig()) {
            rig.emit(500, "T51_LINES_"); rig.refreshAll();
            edt(() -> check(rig.viewer.displayedCount() == 300 && rig.viewer.summaryText().contains("Retirados del visor: 200"), "Límite de líneas"));
        }
    }

    public static void visibleDocumentCharacterCountIsBounded() throws Exception {
        try (var rig = new LogRig()) {
            rig.emit(400, "T51_CHARS_" + "x".repeat(1200)); rig.refreshAll();
            edt(() -> check(rig.viewer.displayedText().length() <= 120_000 && rig.viewer.displayedCount() < 300, "Presupuesto del documento"));
        }
    }

    public static void bufferGapsAreReportedInViewer() throws Exception {
        try (var rig = new LogRig()) {
            rig.emit(900, "T51_GAP_"); rig.refreshAll();
            edt(() -> check(rig.viewer.summaryText().contains("No recuperables del búfer: 400"), "Recorte comunicado"));
        }
    }

    public static void viewerRefreshDoesNotDuplicateRecords() throws Exception {
        try (var rig = new LogRig()) {
            rig.emit(20, "T51_UNIQUE_"); rig.refreshAll();
            String before = onEdt(rig.viewer::displayedText); rig.refreshAll();
            edt(() -> check(before.equals(rig.viewer.displayedText()), "Cursor evita duplicación"));
        }
    }

    public static void viewerWithoutSessionHasUsefulPlaceholder() throws Exception {
        var viewer = onEdt(EventViewerPanel::new);
        edt(() -> { viewer.refresh(); check(viewer.displayedCount() == 0 && viewer.summaryText().contains("configuración"), "Sin sesión"); });
    }

    public static void controllerWithoutLoggingIsSupported() throws Exception {
        var c = new SimulationController(new SimulationConfig(1, 2, 10, 10), 0);
        try {
            var viewer = onEdt(EventViewerPanel::new);
            edt(() -> { viewer.bind(c); check(viewer.summaryText().contains("no tiene un canal"), "Sesión sin logging explícito"); });
        } finally { c.closeAsync().get(5, TimeUnit.SECONDS); check(c.awaitTermination(Duration.ofSeconds(5)), "Sin hilos vivos"); }
    }

    public static void rebindingDoesNotMixSessions() throws Exception {
        try (var first = new LogRig(); var second = new LogRig()) {
            first.emit(1, "T51_FIRST_"); first.refreshAll(); second.emit(1, "T51_SECOND_");
            edt(() -> { first.viewer.bind(second.c); check(!first.viewer.displayedText().contains("T51_FIRST_")
                    && first.viewer.displayedText().contains("T51_SECOND_"), "Historial de sesión nueva"); });
        }
    }

    public static void followCanBeDisabledWithoutPausingLogging() throws Exception {
        try (var rig = new LogRig()) {
            edt(() -> rig.viewer.followControl().setSelected(false)); rig.emit(10, "T51_FOLLOW_"); rig.refreshAll();
            edt(() -> check(!rig.viewer.followControl().isSelected() && rig.viewer.displayedCount() == 10, "Seguir no detiene ingesta"));
            check(Files.readString(rig.directory.resolve("manejador.log")).contains("T51_FOLLOW_9"), "Archivo continúa");
        }
    }

    public static void viewerRejectsOffEdtAccess() throws Exception {
        try { new EventViewerPanel(); throw new AssertionError("Construcción fuera EDT"); } catch (IllegalStateException expected) { }
        var viewer = onEdt(EventViewerPanel::new);
        try { viewer.refresh(); throw new AssertionError("Refresco fuera EDT"); } catch (IllegalStateException expected) { }
        try { viewer.clearView(); throw new AssertionError("Limpieza fuera EDT"); } catch (IllegalStateException expected) { }
    }

    public static void manyCabinsCanBeSelectedDirectly() throws Exception {
        var c = new SimulationController(new SimulationConfig(25, 3, 10, 10), 0);
        try {
            edt(() -> { var dashboard = new CabinDashboardPanel(); dashboard.bind(c); dashboard.selectCabin(25);
                check(dashboard.cabinCount() == 25 && dashboard.selectedCabin() == 25
                        && dashboard.cabin(25).locationText().contains("Piso actual: 1"), "Cabina fuera de pestañas iniciales accesible"); });
        } finally { c.closeAsync().get(5, TimeUnit.SECONDS); check(c.awaitTermination(Duration.ofSeconds(5)), "25 cabinas liberadas"); }
    }

    public static void invalidCabinSelectionIsRejected() throws Exception {
        var c = new SimulationController(new SimulationConfig(2, 3, 10, 10), 0);
        try {
            edt(() -> { var dashboard = new CabinDashboardPanel(); dashboard.bind(c);
                for (int id : new int[] {0, 3}) {
                    try { dashboard.selectCabin(id); throw new AssertionError("Cabina inexistente"); } catch (IllegalArgumentException expected) { }
                }
                check(dashboard.selectedCabin() == 1, "Selección conservada"); });
        } finally { c.closeAsync().get(5, TimeUnit.SECONDS); check(c.awaitTermination(Duration.ofSeconds(5)), "Cerrada"); }
    }

    public static void initialWindowFitsSmallDesktop() throws Exception {
        var usable = new Rectangle(0, 0, 800, 600); var bounds = MainWindow.initialBounds(usable);
        check(usable.contains(bounds) && bounds.width == 800 && bounds.height == 600, "Sin exceder escritorio pequeño");
    }

    public static void initialWindowFitsLaptopDesktop() throws Exception {
        var usable = new Rectangle(0, 0, 1366, 728); var bounds = MainWindow.initialBounds(usable);
        check(usable.contains(bounds) && bounds.width == 1180 && bounds.height == 728, "Área útil sin barra de tareas");
    }

    public static void initialWindowSupportsNegativeDesktopCoordinates() throws Exception {
        var usable = new Rectangle(-1920, -100, 1920, 1080); var bounds = MainWindow.initialBounds(usable);
        check(usable.contains(bounds) && bounds.x < 0, "Escritorio virtual");
    }

    public static void minimumWindowFitsTinyDesktop() throws Exception {
        var usable = new Rectangle(0, 0, 640, 360); var minimum = MainWindow.minimumSize(usable);
        check(minimum.width == 640 && minimum.height == 360 && usable.contains(MainWindow.initialBounds(usable)), "Mínimo limitado por pantalla");
    }

    public static void invalidDesktopAreaIsRejected() throws Exception {
        for (var area : List.of(new Rectangle(0, 0, 0, 600), new Rectangle(0, 0, 800, -1))) {
            try { MainWindow.initialBounds(area); throw new AssertionError("Área inválida"); } catch (IllegalArgumentException expected) { }
            try { MainWindow.minimumSize(area); throw new AssertionError("Mínimo inválido"); } catch (IllegalArgumentException expected) { }
        }
    }

    public static void composedWindowReceivesRealLoggingEvents() throws Exception {
        Path directory = Files.createTempDirectory("t51-composition-");
        var panel = onEdt(() -> new SystemControlPanel(config -> {
            var log = SessionLog.open(directory, config.elevatorCount(), OutputStream.nullOutputStream());
            try { return new SimulationController(config, 0, log); } catch (RuntimeException | Error error) { log.close(); throw error; }
        }, Optional::empty));
        try {
            edt(() -> { String[] values = {"2", "4", "40", "20"}; var form = panel.configurationPanel();
                for (var field : Field.values()) form.input(field).setText(values[field.ordinal()]); form.validateConfiguration(); });
            waitFor(() -> onEdt(() -> panel.controller().isPresent()));
            edt(() -> panel.button(SystemControlPanel.Action.START).doClick(0));
            waitFor(() -> onEdt(() -> { panel.updateView(); return panel.eventViewer().displayedText().contains("MANAGER_STARTED"); }));
            edt(() -> panel.cabinDashboard().cabin(1).destinationButton(3).doClick(0));
            waitFor(() -> onEdt(() -> { panel.updateView(); return panel.eventViewer().displayedText().contains("COMMAND_COMPLETED"); }));
        } finally {
            onEdt(panel::closeAsync).get(5, TimeUnit.SECONDS); edt(panel::closed); deleteTree(directory);
        }
    }

    public static void viewerStillContainsTerminationEventsAfterClose() throws Exception {
        try (var rig = new LogRig()) {
            rig.c.start().get(5, TimeUnit.SECONDS); rig.c.closeAsync().get(5, TimeUnit.SECONDS); rig.refreshAll();
            edt(() -> check(rig.viewer.displayedText().contains("WORKER_TERMINATED")
                    && rig.viewer.displayedText().contains("MANAGER_TERMINATED"), "Últimos registros tras cierre"));
        }
    }

    public static void closedLoggingCanStillBeInspectedWithoutIO() throws Exception {
        try (var rig = new LogRig()) {
            rig.emit(3, "T51_CLOSED_"); rig.c.closeAsync().get(5, TimeUnit.SECONDS);
            check(!rig.log.isAlive(), "Consumidor cerrado"); rig.refreshAll();
            edt(() -> check(rig.viewer.displayedText().contains("T51_CLOSED_2"), "Búfer retenido consultable"));
        }
    }

    private static void append(RecentEventBuffer buffer, int count, String text) {
        for (int i = 0; i < count; i++) buffer.append(Instant.now(), "Manejador", "INFO", text);
    }
    private static final class LogRig implements AutoCloseable {
        final Path directory = Files.createTempDirectory("t51-log-");
        final SessionLog log = SessionLog.open(directory, 2, OutputStream.nullOutputStream());
        final SimulationController c = new SimulationController(new SimulationConfig(2, 5, 40, 20), 0, log);
        final EventViewerPanel viewer = onEdt(EventViewerPanel::new);
        LogRig() throws Exception { edt(() -> viewer.bind(c)); }
        void emit(int count, String prefix) throws Exception {
            for (int i = 0; i < count; i++) log.mailbox().managerState(c.epoch(), prefix + i, null); flush();
        }
        void flush() throws Exception { log.flushAsync().get(5, TimeUnit.SECONDS); }
        void refreshAll() throws Exception { for (int i = 0; i < 4; i++) edt(viewer::refresh); }
        @Override public void close() throws Exception {
            c.closeAsync().get(5, TimeUnit.SECONDS); check(c.awaitTermination(Duration.ofSeconds(5)), "Recursos terminados"); deleteTree(directory);
        }
    }
    private static void deleteTree(Path directory) throws Exception {
        try (var paths = Files.walk(directory)) { for (var path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path); }
    }
    private static void check(boolean condition, String text) { if (!condition) throw new AssertionError(text); }
    private static void waitFor(BooleanSupplier condition) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(6);
        while (!condition.getAsBoolean()) { if (System.nanoTime() > until) throw new AssertionError("Tiempo agotado esperando condición"); Thread.sleep(5); }
    }
    private static void edt(Runnable action) throws Exception { onEdt(() -> { action.run(); return null; }); }
    private static <T> T onEdt(Supplier<T> action) {
        if (SwingUtilities.isEventDispatchThread()) return action.get();
        var value = new AtomicReference<T>(); var failure = new AtomicReference<Throwable>();
        try { SwingUtilities.invokeAndWait(() -> { try { value.set(action.get()); } catch (Throwable error) { failure.set(error); } }); }
        catch (Exception error) { throw new AssertionError(error); }
        if (failure.get() instanceof Error error) throw error;
        if (failure.get() != null) throw new IllegalStateException(failure.get()); return value.get();
    }
    public static void main(String[] args) throws Exception {
        emptyBufferIsReadable();
        sequencesAreOrderedAndCursorDoesNotRepeat();
        recordLimitEvictsOldest();
        characterBudgetEvictsEvenBeforeCountLimit();
        messageAndMetadataAreBounded();
        embeddedNewlinesCannotCreateUnlimitedVisualLines();
        batchResultsAreImmutable();
        invalidCursorAndBatchAreRejected();
        skippedRecordsAreCountedOnce();
        independentReadersDoNotConsumeEachOther();
        concurrentProducersPreserveSequenceAndBounds();
        nullInputDoesNotAdvanceSequence();
        fullFilesSurviveVisualBufferOverflow();
        longRecordIsCompleteInFileButTruncatedInViewerSource();
        exceptionRemainsInFileAndTextSummary();
        viewerReadsManagerAndCabinEvents();
        viewerDoesNotStealControllerExecutionEvents();
        viewerPreservesHistoryAcrossReset();
        clearDoesNotDeleteFilesOrSharedBuffer();
        clearAdvancesCursorAndAllowsNewEvents();
        oneRefreshReadsAtMostTwoHundredRecords();
        visibleLineCountIsBounded();
        visibleDocumentCharacterCountIsBounded();
        bufferGapsAreReportedInViewer();
        viewerRefreshDoesNotDuplicateRecords();
        viewerWithoutSessionHasUsefulPlaceholder();
        controllerWithoutLoggingIsSupported();
        rebindingDoesNotMixSessions();
        followCanBeDisabledWithoutPausingLogging();
        viewerRejectsOffEdtAccess();
        manyCabinsCanBeSelectedDirectly();
        invalidCabinSelectionIsRejected();
        initialWindowFitsSmallDesktop();
        initialWindowFitsLaptopDesktop();
        initialWindowSupportsNegativeDesktopCoordinates();
        minimumWindowFitsTinyDesktop();
        invalidDesktopAreaIsRejected();
        composedWindowReceivesRealLoggingEvents();
        viewerStillContainsTerminationEventsAfterClose();
        closedLoggingCanStillBeInspectedWithoutIO();
        System.out.println("T51: 40 escenarios satisfactorios.");
    }
}
