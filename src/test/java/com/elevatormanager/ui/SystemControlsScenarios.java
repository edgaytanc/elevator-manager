package com.elevatormanager.ui;

import com.elevatormanager.model.*;
import com.elevatormanager.model.SimulationConfig.Field;
import com.elevatormanager.model.request.CabinRequest;
import com.elevatormanager.simulation.SimulationController;
import com.elevatormanager.ui.SystemControlPanel.Action;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import javax.swing.SwingUtilities;

/** T48: controles Swing en EDT y sesiones reales; selección inyectada sin diálogo nativo. */
public final class SystemControlsScenarios {
    private SystemControlsScenarios() { }
    public static void initialControlsRequireConfiguration() throws Exception {
        try (var rig = new Rig()) {
            edt(() -> { for (Action a : Action.values()) check(rig.enabled(a) == (a == Action.CLOSE), "Sin sesión " + a); });
        }
    }

    public static void invalidConfigurationDoesNotOpenSession() throws Exception {
        try (var rig = new Rig()) {
            edt(() -> rig.panel.configurationPanel().validateConfiguration());
            check(rig.opened.get() == 0 && rig.controller() == null, "No crea sesión inválida");
        }
    }

    public static void preparationRunsOutsideEdtAndLocksFields() throws Exception {
        try (var rig = new Rig()) {
            rig.prepare();
            check(rig.opened.get() == 1 && !rig.openedOnEdt.get(), "Apertura fuera de EDT");
            edt(() -> check(!rig.panel.configurationPanel().input(Field.ELEVATORS).isEnabled(), "Configuración bloqueada"));
        }
    }

    public static void resetEnablesOnlyLegalControls() throws Exception {
        try (var rig = new Rig()) {
            rig.prepare();
            edt(() -> { check(rig.enabled(Action.START) && rig.enabled(Action.RESET) && rig.enabled(Action.LOAD), "RESET habilita");
                check(!rig.enabled(Action.STOP), "RESET no Stop"); });
        }
    }

    public static void startStopAndContinuePreserveSession() throws Exception {
        try (var rig = new Rig()) {
            rig.prepare(); var original = rig.controller();
            rig.click(Action.START); rig.awaitState(SimulationState.RUNNING);
            rig.click(Action.STOP); rig.awaitState(SimulationState.STOPPED);
            edt(() -> check(rig.enabled(Action.START) && !rig.enabled(Action.LOAD), "STOPPED continúa sin importar"));
            rig.click(Action.START); rig.awaitState(SimulationState.RUNNING);
            check(rig.controller() == original && rig.opened.get() == 1, "Mismos actores y sesión");
        }
    }

    public static void stoppingIndicatorAndResetRemainAvailable() throws Exception {
        try (var rig = new Rig()) {
            rig.prepare(); rig.moving();
            edt(() -> { rig.panel.button(Action.STOP).doClick(0);
                check(rig.panel.stateText().contains("Deteniendo"), "STOPPING visible");
                check(!rig.enabled(Action.START) && !rig.enabled(Action.LOAD) && rig.enabled(Action.RESET), "Controles durante Stop"); });
            rig.awaitState(SimulationState.STOPPED);
        }
    }

    public static void resettingIndicatorWaitsForReturn() throws Exception {
        try (var rig = new Rig()) {
            rig.prepare(); rig.moving();
            edt(() -> { rig.panel.button(Action.RESET).doClick(0);
                check(rig.panel.stateText().contains("Reseteando"), "RESETTING visible");
                check(!rig.enabled(Action.START) && !rig.enabled(Action.LOAD), "No inicia ni importa durante retorno"); });
            rig.awaitState(SimulationState.RESET);
            check(rig.controller().snapshots().stream().allMatch(s -> s.currentFloor() == 1), "Retorno real confirmado");
        }
    }

    public static void importPreparesWithoutMoving() throws Exception {
        try (var rig = new Rig()) {
            rig.prepare(); rig.select("CABIN 1 4\n"); rig.click(Action.LOAD);
            waitFor(() -> rig.controller().preparedBatch().isPresent());
            waitFor(() -> onEdt(() -> rig.enabled(Action.LOAD)));
            check(rig.controller().state() == SimulationState.RESET && rig.controller().snapshots().get(0).currentFloor() == 1, "Preparación sin viaje");
            edt(() -> check(rig.panel.stateText().contains("Lote preparado: 1"), "Cantidad visible"));
        }
    }

    public static void loadDisabledInRunningAndStopped() throws Exception {
        try (var rig = new Rig()) {
            rig.prepare(); rig.click(Action.START); rig.awaitState(SimulationState.RUNNING);
            edt(() -> check(!rig.enabled(Action.LOAD), "RUNNING no carga"));
            rig.click(Action.STOP); rig.awaitState(SimulationState.STOPPED);
            edt(() -> check(!rig.enabled(Action.LOAD), "STOPPED no carga"));
        }
    }

    public static void cancelSelectionPreservesPreparedBatch() throws Exception {
        try (var rig = new Rig()) {
            rig.prepare(); rig.select("CABIN 1 4\n"); rig.click(Action.LOAD);
            waitFor(() -> onEdt(() -> rig.enabled(Action.LOAD)));
            var old = rig.controller().preparedBatch().orElseThrow(); rig.selected.set(Optional.empty());
            rig.click(Action.LOAD);
            edt(() -> check(rig.panel.detailText().contains("cancelada"), "Cancelación visible"));
            check(rig.controller().preparedBatch().orElseThrow() == old, "Conserva lote");
        }
    }

    public static void invalidImportKeepsPreviousBatchAndShowsLine() throws Exception {
        try (var rig = new Rig()) {
            rig.prepare(); rig.select("CABIN 1 4\n"); rig.click(Action.LOAD);
            waitFor(() -> onEdt(() -> rig.enabled(Action.LOAD)));
            var old = rig.controller().preparedBatch().orElseThrow(); rig.select("CABIN 1 4\nCABIN 1 99\n");
            rig.click(Action.LOAD); waitFor(() -> onEdt(() -> rig.enabled(Action.LOAD)));
            check(rig.controller().preparedBatch().orElseThrow() == old, "Atómico");
            edt(() -> check(rig.panel.detailText().contains("Línea 2"), "Diagnóstico visible"));
        }
    }

    public static void resetClearsImportedBatch() throws Exception {
        try (var rig = new Rig()) {
            rig.prepare(); rig.select("CABIN 1 4\n"); rig.click(Action.LOAD);
            waitFor(() -> onEdt(() -> rig.enabled(Action.LOAD)));
            rig.click(Action.RESET); rig.awaitState(SimulationState.RESET);
            check(rig.controller().preparedBatch().isEmpty(), "Reset elimina lote");
            edt(() -> check(rig.panel.stateText().contains("Lote preparado: 0"), "Cantidad actualizada"));
        }
    }

    public static void closeWithoutConfigurationCompletes() throws Exception {
        try (var rig = new Rig()) {
            var done = onEdt(() -> rig.panel.closeAsync()); done.get(5, TimeUnit.SECONDS);
            edt(() -> { rig.panel.closed(); check(!rig.enabled(Action.CLOSE), "Cerrada sin sesión"); });
        }
    }

    public static void closeDuringPreparationReleasesLateSession() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var created = new AtomicReference<SimulationController>();
        try (var rig = new Rig(config -> {
            entered.countDown(); check(release.await(5, TimeUnit.SECONDS), "Liberar apertura");
            var c = new SimulationController(config, 0); created.set(c); return c;
        })) {
            rig.confirm(); check(entered.await(5, TimeUnit.SECONDS), "Apertura activa");
            var done = onEdt(() -> rig.panel.closeAsync());
            check(!done.isDone(), "Cierre espera apertura"); release.countDown(); done.get(5, TimeUnit.SECONDS);
            check(created.get().awaitTermination(Duration.ofSeconds(5)), "Cerró sesión tardía y actores");
            edt(rig.panel::closed);
        } finally { release.countDown(); }
    }

    public static void preparationFailureAllowsCorrectionAndRetry() throws Exception {
        var attempts = new AtomicInteger();
        try (var rig = new Rig(config -> {
            if (attempts.incrementAndGet() == 1) throw new java.io.IOException("fallo de apertura inyectado");
            return new SimulationController(config, 0);
        })) {
            rig.confirm(); waitFor(() -> onEdt(() -> rig.panel.configurationPanel().input(Field.ELEVATORS).isEnabled()));
            edt(() -> check(rig.panel.detailText().contains("fallo de apertura"), "Fallo visible"));
            rig.prepare(); check(attempts.get() == 2, "Reintento válido");
        }
    }

    public static void closeDuringRunningTerminatesWorkers() throws Exception {
        try (var rig = new Rig()) {
            rig.prepare(); rig.moving(); var c = rig.controller();
            onEdt(() -> rig.panel.closeAsync()).get(5, TimeUnit.SECONDS);
            check(c.awaitTermination(Duration.ofSeconds(5)), "Cierre real con movimiento"); edt(rig.panel::closed);
        }
    }

    public static void closeDuringStopIgnoresLateControlReceipt() throws Exception {
        try (var rig = new Rig()) {
            rig.prepare(); rig.moving();
            var done = onEdt(() -> { rig.panel.button(Action.STOP).doClick(0); return rig.panel.closeAsync(); });
            done.get(5, TimeUnit.SECONDS);
            edt(() -> check(rig.panel.detailText().contains("Cerrando"), "Stop tardío no restaura UI"));
            edt(rig.panel::closed);
        }
    }

    public static void closeDuringResetTerminatesWorkers() throws Exception {
        try (var rig = new Rig()) {
            rig.prepare(); rig.moving();
            onEdt(() -> { rig.panel.button(Action.RESET).doClick(0); return rig.panel.closeAsync(); }).get(5, TimeUnit.SECONDS);
            check(rig.controller().awaitTermination(Duration.ofSeconds(5)), "Cierre durante retorno"); edt(rig.panel::closed);
        }
    }

    public static void repeatedConfirmationDoesNotCreateSecondSession() throws Exception {
        try (var rig = new Rig()) {
            rig.prepare();
            edt(() -> { rig.panel.configurationPanel().validateConfiguration(); rig.panel.configurationPanel().validateConfiguration(); });
            check(rig.opened.get() == 1, "Una sesión");
        }
    }

    public static void realLogFilesAreCreatedAndReleased() throws Exception {
        Path directory = Files.createTempDirectory("t48-log-");
        try {
            try (var rig = new Rig(config -> SystemControlPanel.openSession(config, directory))) {
                rig.prepare(); rig.click(Action.START); rig.awaitState(SimulationState.RUNNING);
                rig.click(Action.STOP); rig.awaitState(SimulationState.STOPPED);
                onEdt(() -> rig.panel.closeAsync()).get(5, TimeUnit.SECONDS); edt(rig.panel::closed);
                check(Files.size(directory.resolve("elevador1.log")) > 0 && Files.size(directory.resolve("manejador.log")) > 0, "Logs reales");
            }
            try (var log = com.elevatormanager.logging.SessionLog.open(directory, 1, java.io.OutputStream.nullOutputStream())) {
                check(log.isAlive(), "Lock liberado para reapertura");
            }
        } finally { deleteTree(directory); }
    }

    public static void offEdtAccessIsRejected() throws Exception {
        try { new SystemControlPanel(); throw new AssertionError("Constructor fuera EDT"); }
        catch (IllegalStateException expected) { }
        try (var rig = new Rig()) {
            try { rig.panel.closeAsync(); throw new AssertionError("Cierre visual fuera EDT"); }
            catch (IllegalStateException expected) { }
            try { rig.panel.controller(); throw new AssertionError("Lectura fuera EDT"); }
            catch (IllegalStateException expected) { }
        }
    }

    public static void edtRespondsDuringPreparation() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        try (var rig = new Rig(config -> {
            entered.countDown(); check(release.await(5, TimeUnit.SECONDS), "Liberar preparación"); return new SimulationController(config, 0);
        })) {
            rig.confirm(); check(entered.await(5, TimeUnit.SECONDS), "Preparación iniciada");
            edt(() -> check(rig.enabled(Action.CLOSE) && !rig.enabled(Action.START), "EDT disponible durante apertura"));
            release.countDown(); waitFor(() -> rig.controller() != null);
        } finally { release.countDown(); }
    }

    public static void closeFailureKeepsPanelAndRetryAvailable() throws Exception {
        try (var rig = new Rig()) {
            rig.prepare();
            rig.controller().registerResource(() -> { throw new java.io.IOException("cierre inyectado"); }).get(5, TimeUnit.SECONDS);
            var result = onEdt(() -> rig.panel.closeAsync());
            try { result.get(5, TimeUnit.SECONDS); throw new AssertionError("Cierre fallido silencioso"); }
            catch (java.util.concurrent.ExecutionException expected) {
                edt(() -> rig.panel.closeFailed(expected.getCause()));
            }
            edt(() -> { check(rig.enabled(Action.CLOSE) && !rig.enabled(Action.START), "No dispone panel ni habilita trabajo");
                check(rig.panel.detailText().contains("No se confirmó"), "Fallo de cierre visible"); });
            rig.expectedCloseFailure = true;
        }
    }

    public static void resetSupersedesStopWithoutRestoringOldState() throws Exception {
        try (var rig = new Rig()) {
            rig.prepare(); rig.moving();
            edt(() -> { rig.panel.button(Action.STOP).doClick(0); rig.panel.button(Action.RESET).doClick(0); });
            rig.awaitState(SimulationState.RESET);
            edt(() -> check(rig.enabled(Action.START) && rig.enabled(Action.LOAD) && !rig.enabled(Action.STOP), "Estado vigente tras Stop sustituido"));
        }
    }

    public static void loadFailureAllowsRetry() throws Exception {
        try (var rig = new Rig()) {
            rig.prepare(); rig.selected.set(Optional.of(rig.directory.resolve("ausente.txt")));
            rig.click(Action.LOAD); waitFor(() -> onEdt(() -> rig.enabled(Action.LOAD)));
            edt(() -> check(rig.panel.detailText().length() > 0 && rig.enabled(Action.START), "Error recuperable"));
            rig.select("CABIN 1 4\n"); rig.click(Action.LOAD);
            waitFor(() -> rig.controller().preparedBatch().isPresent());
        }
    }

    public static void importedBatchRunsThroughStartButton() throws Exception {
        try (var rig = new Rig()) {
            rig.prepare(); rig.select("CABIN 1 4\n"); rig.click(Action.LOAD);
            waitFor(() -> onEdt(() -> rig.enabled(Action.LOAD)));
            rig.click(Action.START); rig.awaitState(SimulationState.RUNNING);
            waitFor(() -> rig.controller().snapshots().get(0).currentFloor() == 4);
            check(rig.controller().preparedBatch().isEmpty() && rig.controller().lastBatchDispatch().isPresent(), "Start consume lote real");
        }
    }

    public static void logOpeningFailureDoesNotPublishPartialSession() throws Exception {
        Path file = Files.createTempFile("t48-no-directory-", ".tmp");
        try (var rig = new Rig(config -> SystemControlPanel.openSession(config, file))) {
            rig.confirm();
            waitFor(() -> onEdt(() -> rig.panel.configurationPanel().input(Field.ELEVATORS).isEnabled()));
            check(rig.controller() == null, "No publica sesión con logging incompleto");
            edt(() -> check(!rig.enabled(Action.START) && rig.enabled(Action.CLOSE), "Recuperable, sin arranque parcial"));
        } finally { Files.deleteIfExists(file); }
    }

    public static void buttonAndWindowRequestShareOneCloseHandler() throws Exception {
        try (var rig = new Rig()) {
            rig.prepare(); var disposed = new AtomicInteger();
            var handler = new AtomicReference<WindowCloseHandler>();
            edt(() -> {
                handler.set(new WindowCloseHandler(rig.panel::closeAsync, () -> { },
                        () -> { rig.panel.closed(); disposed.incrementAndGet(); }, rig.panel::closeFailed));
                rig.panel.onCloseRequest(handler.get()::requestClose);
                rig.panel.button(Action.CLOSE).doClick(0);
                handler.get().requestClose(); // Misma ruta utilizada por la X.
            });
            waitFor(() -> disposed.get() == 1);
            edt(() -> handler.get().requestClose());
            check(disposed.get() == 1 && rig.controller().awaitTermination(Duration.ofSeconds(5)), "Cierre único confirmado");
        }
    }

    private static final class Rig implements AutoCloseable {
        final Path directory = Files.createTempDirectory("t48-controls-");
        final AtomicReference<Optional<Path>> selected = new AtomicReference<>(Optional.empty());
        final AtomicInteger opened = new AtomicInteger();
        final AtomicBoolean openedOnEdt = new AtomicBoolean();
        final SystemControlPanel panel;
        boolean expectedCloseFailure;
        Rig() throws Exception { this(config -> new SimulationController(config, 0)); }
        Rig(SystemControlPanel.SessionOpener opener) throws Exception {
            panel = onEdt(() -> new SystemControlPanel(config -> {
                opened.incrementAndGet(); openedOnEdt.set(SwingUtilities.isEventDispatchThread()); return opener.open(config);
            }, selected::get));
        }
        void confirm() throws Exception {
            edt(() -> {
                var form = panel.configurationPanel();
                String[] values = {"1", "4", "350", "40"};
                for (var field : Field.values()) form.input(field).setText(values[field.ordinal()]);
                form.validateConfiguration();
            });
        }
        void prepare() throws Exception { confirm(); waitFor(() -> controller() != null); }
        SimulationController controller() { return onEdt(() -> panel.controller().orElse(null)); }
        boolean enabled(Action action) { panel.updateView(); return panel.button(action).isEnabled(); }
        void click(Action action) throws Exception { edt(() -> { panel.updateView(); panel.button(action).doClick(0); }); }
        void awaitState(SimulationState state) throws Exception {
            waitFor(() -> controller().state() == state && onEdt(() -> {
                panel.updateView();
                return state != SimulationState.RESET && state != SimulationState.STOPPED
                        || panel.button(Action.START).isEnabled();
            }));
        }
        void moving() throws Exception {
            click(Action.START); awaitState(SimulationState.RUNNING);
            var c = controller();
            c.submit(new CabinRequest(UUID.randomUUID(), c.epoch(), 4, Instant.now(), 1)).get(5, TimeUnit.SECONDS);
            waitFor(() -> c.snapshots().get(0).phase() == ElevatorPhase.MOVING);
        }
        void select(String text) throws Exception {
            Path file = directory.resolve(UUID.randomUUID() + ".txt"); Files.writeString(file, text); selected.set(Optional.of(file));
        }
        @Override public void close() throws Exception {
            try { onEdt(() -> panel.closeAsync()).get(8, TimeUnit.SECONDS); }
            catch (ExecutionException error) { if (!expectedCloseFailure) throw error; }
            finally { edt(panel::closed); deleteTree(directory); }
        }
    }

    private static void deleteTree(Path path) throws Exception {
        try (var paths = Files.walk(path)) {
            for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        }
    }
    private static void check(boolean condition, String text) { if (!condition) throw new AssertionError(text); }
    private static void waitFor(BooleanSupplier condition) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(6);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > until) throw new AssertionError("Tiempo agotado esperando condición");
            Thread.sleep(5);
        }
    }
    private static void edt(Runnable action) throws Exception { onEdt(() -> { action.run(); return null; }); }
    private static <T> T onEdt(Supplier<T> action) {
        if (SwingUtilities.isEventDispatchThread()) return action.get();
        var value = new AtomicReference<T>(); var failure = new AtomicReference<Throwable>();
        try { SwingUtilities.invokeAndWait(() -> {
            try { value.set(action.get()); } catch (Throwable error) { failure.set(error); }
        }); } catch (Exception error) { throw new AssertionError(error); }
        if (failure.get() instanceof Error error) throw error;
        if (failure.get() != null) throw new IllegalStateException(failure.get());
        return value.get();
    }
    public static void main(String[] args) throws Exception {
        initialControlsRequireConfiguration();
        invalidConfigurationDoesNotOpenSession();
        preparationRunsOutsideEdtAndLocksFields();
        resetEnablesOnlyLegalControls();
        startStopAndContinuePreserveSession();
        stoppingIndicatorAndResetRemainAvailable();
        resettingIndicatorWaitsForReturn();
        importPreparesWithoutMoving();
        loadDisabledInRunningAndStopped();
        cancelSelectionPreservesPreparedBatch();
        invalidImportKeepsPreviousBatchAndShowsLine();
        resetClearsImportedBatch();
        closeWithoutConfigurationCompletes();
        closeDuringPreparationReleasesLateSession();
        preparationFailureAllowsCorrectionAndRetry();
        closeDuringRunningTerminatesWorkers();
        closeDuringStopIgnoresLateControlReceipt();
        closeDuringResetTerminatesWorkers();
        repeatedConfirmationDoesNotCreateSecondSession();
        realLogFilesAreCreatedAndReleased();
        offEdtAccessIsRejected();
        edtRespondsDuringPreparation();
        closeFailureKeepsPanelAndRetryAvailable();
        resetSupersedesStopWithoutRestoringOldState();
        loadFailureAllowsRetry();
        importedBatchRunsThroughStartButton();
        logOpeningFailureDoesNotPublishPartialSession();
        buttonAndWindowRequestShareOneCloseHandler();
        System.out.println("T48: 28 escenarios satisfactorios.");
    }
}
