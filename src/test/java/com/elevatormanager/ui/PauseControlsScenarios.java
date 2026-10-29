package com.elevatormanager.ui;

import com.elevatormanager.logging.SessionLog;
import com.elevatormanager.model.*;
import com.elevatormanager.model.SimulationConfig.Field;
import com.elevatormanager.simulation.SimulationController;
import com.elevatormanager.ui.SystemControlPanel.Action;
import java.io.OutputStream;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import javax.swing.*;

/** T56: botones Swing en EDT con motor/log reales y refresco normal; sin JFrame/Robot. */
public final class PauseControlsScenarios {
    private PauseControlsScenarios() { }

    public static void pauseControlsRequireConfiguredRunningSession() throws Exception {
        try (var r = new Rig()) {
            edt(() -> check(!r.enabled(Action.PAUSE) && !r.enabled(Action.RESUME), "Sin sesión no hay pausa"));
            r.prepare(1, 4, 200);
            edt(() -> check(!r.enabled(Action.PAUSE) && !r.enabled(Action.RESUME), "RESET no pausa ni reanuda"));
            r.start();
            edt(() -> check(r.enabled(Action.PAUSE) && !r.enabled(Action.RESUME), "RUNNING ofrece Pausar"));
        }
    }

    public static void pauseRequestImmediatelyShowsPausingAndKeepsPriorityControls() throws Exception {
        try (var r = new Rig()) {
            r.prepare(8, 4, 200); r.start();
            edt(() -> {
                r.panel.button(Action.PAUSE).doClick(0);
                check(r.panel.stateText().contains("PAUSING"), "No anuncia PAUSED al pulsar");
                check(!r.enabled(Action.RESUME) && !r.enabled(Action.PAUSE), "Sin doble pausa ni Resume anticipado");
                check(r.enabled(Action.STOP) && r.enabled(Action.RESET) && r.enabled(Action.CLOSE), "Prioridades disponibles");
            });
            r.state(SimulationState.PAUSED);
            check(r.c().pauseStatus().acknowledged() == 9, "PAUSED solo con nueve ACK");
        }
    }

    public static void pausedButtonMatrixBlocksRequestsAndImport() throws Exception {
        try (var r = new Rig()) {
            r.prepare(1, 4, 200); r.start(); r.pause();
            edt(() -> {
                check(r.enabled(Action.RESUME) && r.enabled(Action.STOP) && r.enabled(Action.RESET) && r.enabled(Action.CLOSE), "Controles legales");
                check(!r.enabled(Action.START) && !r.enabled(Action.PAUSE) && !r.enabled(Action.LOAD), "Controles bloqueados");
                check(!r.cabin().destinationButton(4).isEnabled(), "Destino bloqueado");
                check(!r.panel.hallCallPanel().button(Direction.UP).isEnabled(), "Llamada bloqueada");
            });
        }
    }

    public static void resumeButtonKeepsSessionAndPendingLight() throws Exception {
        try (var r = new Rig()) {
            r.prepare(1, 5, 350); r.start(); r.destination(5);
            await("cabina moviéndose", () -> r.c().snapshot(1).phase() == ElevatorPhase.MOVING);
            UUID identity = r.c().sessionId(); r.pause();
            var frozen = r.c().snapshot(1);
            await("fase visible en pausa", () -> onEdt(() -> r.cabin().phaseText().contains("En pausa")));
            edt(() -> check(r.cabin().litFloors().contains(5), "Luz conservada"));
            Thread.sleep(400); check(r.c().snapshot(1).equals(frozen), "Sin avance mientras se inspecciona");
            r.click(Action.RESUME); r.state(SimulationState.RUNNING);
            check(r.c().sessionId().equals(identity) && r.c().epoch() == 0, "Misma sesión y época");
            await("atención y apagado", () -> r.c().snapshot(1).currentFloor() == 5
                    && onEdt(() -> !r.cabin().litFloors().contains(5)));
        }
    }

    public static void stopButtonWorksFromPausedMovement() throws Exception {
        try (var r = new Rig()) {
            r.prepare(1, 4, 400); r.start(); r.destination(2);
            await("tramo iniciado", () -> r.c().snapshot(1).phase() == ElevatorPhase.MOVING);
            r.pause(); r.click(Action.STOP); r.state(SimulationState.STOPPED);
            check(r.c().snapshot(1).currentFloor() == 2 && r.c().snapshot(1).cabinRequests().size() == 1, "Stop sin servicio intermedio");
            edt(() -> check(r.enabled(Action.START) && !r.enabled(Action.RESUME), "Continúa con Start"));
            r.start(); await("destino atendido", () -> r.c().snapshot(1).cabinRequests().isEmpty());
        }
    }

    public static void resetButtonWorksFromPausedMovement() throws Exception {
        try (var r = new Rig()) {
            r.prepare(1, 5, 250); r.start(); r.destination(5);
            await("movimiento", () -> r.c().snapshot(1).phase() == ElevatorPhase.MOVING);
            r.pause(); r.click(Action.RESET); r.state(SimulationState.RESET);
            check(r.c().epoch() == 1 && r.c().snapshot(1).currentFloor() == 1
                    && r.c().snapshot(1).cabinRequests().isEmpty(), "Retorno real con limpieza");
            edt(() -> check(r.enabled(Action.LOAD) && r.enabled(Action.START) && !r.enabled(Action.RESUME), "RESET habilita carga"));
        }
    }

    public static void immediateStopSupersedesPendingPauseInSameEdtTurn() throws Exception {
        try (var r = new Rig()) {
            r.prepare(8, 4, 200); r.start();
            edt(() -> {
                r.panel.button(Action.PAUSE).doClick(0);
                check(r.enabled(Action.STOP), "Stop habilitado durante petición");
                r.panel.button(Action.STOP).doClick(0);
            });
            r.state(SimulationState.STOPPED); Thread.sleep(120);
            edt(() -> check(r.panel.stateText().contains("STOPPED") && !r.enabled(Action.RESUME), "Respuesta vieja no restaura PAUSED"));
        }
    }

    public static void immediateResetSupersedesPendingPauseInSameEdtTurn() throws Exception {
        try (var r = new Rig()) {
            r.prepare(8, 4, 200); r.start();
            edt(() -> { r.panel.button(Action.PAUSE).doClick(0); r.panel.button(Action.RESET).doClick(0); });
            r.state(SimulationState.RESET); Thread.sleep(120);
            check(r.c().epoch() == 1, "Una época");
            edt(() -> check(r.panel.stateText().contains("RESET") && r.enabled(Action.LOAD), "Respuesta vigente"));
        }
    }

    public static void closeButtonReleasesPausedActorsAndSampler() throws Exception {
        try (var r = new Rig()) {
            r.prepare(3, 4, 300); r.start(); r.destination(4); r.pause();
            r.closeViaButton();
            check(r.c().state() == SimulationState.CLOSED && r.c().failure().isEmpty(), "Cierre sin reanudar");
            check(onEdt(() -> r.panel.snapshotPump().closeAsync()).isDone(), "Capturador cerrado");
        }
    }

    public static void closeButtonCanSupersedePendingPause() throws Exception {
        try (var r = new Rig()) {
            r.prepare(8, 4, 200); r.start();
            edt(() -> { r.panel.button(Action.PAUSE).doClick(0); r.panel.button(Action.CLOSE).doClick(0); });
            r.closed();
            check(r.c().state() == SimulationState.CLOSED && r.c().failure().isEmpty(), "Cierre prioritario desde Pausando");
        }
    }

    public static void viewerShowsPauseAndResumeTransitions() throws Exception {
        try (var r = new Rig()) {
            r.prepare(1, 4, 200); r.start(); r.pause();
            await("PAUSED en visor", () -> onEdt(() -> r.panel.eventViewer().displayedText().contains("SIMULATION_PAUSED")));
            r.click(Action.RESUME); r.state(SimulationState.RUNNING);
            await("RESUMED en visor", () -> onEdt(() -> r.panel.eventViewer().displayedText().contains("SIMULATION_RESUMED")));
        }
    }

    public static void repeatedButtonCyclesDoNotCreateSessions() throws Exception {
        try (var r = new Rig()) {
            r.prepare(2, 4, 100); r.start(); var original = r.c();
            for (int i = 0; i < 5; i++) { r.pause(); r.click(Action.RESUME); r.state(SimulationState.RUNNING); }
            check(r.c() == original && r.c().pauseStatus().generation() == 5, "Cinco ciclos sobre la misma sesión");
        }
    }

    public static void stoppedAndResettingDisablePauseAndResume() throws Exception {
        try (var r = new Rig()) {
            r.prepare(1, 4, 300); r.start(); r.destination(4);
            await("tramo", () -> r.c().snapshot(1).phase() == ElevatorPhase.MOVING);
            edt(() -> {
                r.panel.button(Action.STOP).doClick(0);
                check(!r.enabled(Action.PAUSE) && !r.enabled(Action.RESUME), "STOPPING no pausa ni reanuda");
            });
            r.state(SimulationState.STOPPED);
            edt(() -> check(!r.enabled(Action.PAUSE) && !r.enabled(Action.RESUME), "STOPPED no pausa ni reanuda"));
            edt(() -> {
                r.panel.button(Action.RESET).doClick(0);
                check(!r.enabled(Action.PAUSE) && !r.enabled(Action.RESUME), "RESETTING no pausa ni reanuda");
            });
            r.state(SimulationState.RESET);
        }
    }

    public static void pausedResetRestoresImportThroughButtons() throws Exception {
        try (var r = new Rig()) {
            r.prepare(1, 4, 100); r.start(); r.pause(); r.click(Action.RESET); r.state(SimulationState.RESET);
            Path file = r.directory.resolve("requests.txt"); Files.writeString(file, "CABIN 1 3\n"); r.selected.set(Optional.of(file));
            r.click(Action.LOAD);
            await("lote confirmado", () -> r.c().preparedBatch().isPresent() && onEdt(() -> r.enabled(Action.LOAD)));
            check(r.c().snapshot(1).currentFloor() == 1 && r.c().epoch() == 1, "Importa en nueva época sin mover");
        }
    }

    private static final class Rig implements AutoCloseable {
        final Path directory = Files.createTempDirectory("t56-gui-");
        final AtomicReference<Optional<Path>> selected = new AtomicReference<>(Optional.empty());
        final AtomicBoolean closed = new AtomicBoolean();
        final AtomicReference<Throwable> closeFailure = new AtomicReference<>();
        final SystemControlPanel panel;
        Rig() throws Exception {
            panel = onEdt(() -> new SystemControlPanel(config -> {
                var log = SessionLog.open(directory.resolve("logs"), config.elevatorCount(), OutputStream.nullOutputStream());
                try { return new SimulationController(config, 0, log); }
                catch (RuntimeException | Error e) { log.close(); throw e; }
            }, selected::get));
            edt(() -> {
                var handler = new WindowCloseHandler(panel::closeAsync, () -> { },
                        () -> { panel.closed(); closed.set(true); }, e -> { closeFailure.set(e); panel.closeFailed(e); });
                panel.onCloseRequest(handler::requestClose);
            });
        }
        void prepare(int cabins, int floors, int movement) throws Exception {
            edt(() -> {
                String[] values = {"" + cabins, "" + floors, "" + movement, "60"};
                for (Field field : Field.values()) { panel.configurationPanel().input(field).setText(values[field.ordinal()]); }
                panel.configurationPanel().validateConfiguration();
            });
            await("sesión preparada", () -> c() != null && onEdt(() -> enabled(Action.START)));
        }
        SimulationController c() { return onEdt(() -> panel.controller().orElse(null)); }
        CabinPanel cabin() { return panel.cabinDashboard().cabin(1); }
        boolean enabled(Action action) { return panel.button(action).isEnabled(); }
        void click(Action action) throws Exception {
            await("control " + action, () -> onEdt(() -> enabled(action)));
            edt(() -> panel.button(action).doClick(0));
        }
        void start() throws Exception { click(Action.START); state(SimulationState.RUNNING); }
        void pause() throws Exception { click(Action.PAUSE); state(SimulationState.PAUSED); }
        void state(SimulationState expected) throws Exception {
            await("estado " + expected, () -> c().state() == expected && onEdt(() -> {
                Action ready = expected == SimulationState.RUNNING ? Action.PAUSE
                        : expected == SimulationState.PAUSED ? Action.RESUME : Action.START;
                return panel.stateText().contains("Estado: " + expected) && enabled(ready);
            }));
        }
        void destination(int floor) throws Exception {
            await("destino habilitado", () -> onEdt(() -> cabin().destinationButton(floor).isEnabled()));
            edt(() -> cabin().destinationButton(floor).doClick(0));
        }
        void closeViaButton() throws Exception { click(Action.CLOSE); closed(); }
        void closed() throws Exception {
            await("cierre confirmado", () -> closed.get() || closeFailure.get() != null);
            check(closeFailure.get() == null && c().awaitTermination(Duration.ofSeconds(5)), "Cierre real de sesión");
        }
        @Override public void close() throws Exception {
            try { if (!closed.get()) { onEdt(panel::closeAsync).get(10, TimeUnit.SECONDS); edt(panel::closed); } }
            finally {
                try (var paths = Files.walk(directory)) {
                    for (var path : paths.sorted(Comparator.reverseOrder()).toList()) { Files.deleteIfExists(path); }
                }
            }
        }
    }
    private static void await(String detail, BooleanSupplier condition) throws Exception {
        long start = System.nanoTime();
        while (!condition.getAsBoolean()) { check(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(10), detail); Thread.sleep(3); }
    }
    private static void edt(Runnable action) { onEdt(() -> { action.run(); return null; }); }
    private static <T> T onEdt(Supplier<T> action) {
        if (SwingUtilities.isEventDispatchThread()) { return action.get(); }
        var value = new AtomicReference<T>(); var failure = new AtomicReference<Throwable>();
        try { SwingUtilities.invokeAndWait(() -> { try { value.set(action.get()); } catch (Throwable e) { failure.set(e); } }); }
        catch (Exception e) { throw new AssertionError(e); }
        if (failure.get() instanceof Error e) { throw e; }
        if (failure.get() != null) { throw new IllegalStateException(failure.get()); }
        return value.get();
    }
    private static void check(boolean condition, String detail) { if (!condition) { throw new AssertionError(detail); } }

    public static void main(String[] args) throws Exception {
        pauseControlsRequireConfiguredRunningSession();
        pauseRequestImmediatelyShowsPausingAndKeepsPriorityControls();
        pausedButtonMatrixBlocksRequestsAndImport();
        resumeButtonKeepsSessionAndPendingLight();
        stopButtonWorksFromPausedMovement();
        resetButtonWorksFromPausedMovement();
        immediateStopSupersedesPendingPauseInSameEdtTurn();
        immediateResetSupersedesPendingPauseInSameEdtTurn();
        closeButtonReleasesPausedActorsAndSampler();
        closeButtonCanSupersedePendingPause();
        viewerShowsPauseAndResumeTransitions();
        repeatedButtonCyclesDoNotCreateSessions();
        stoppedAndResettingDisablePauseAndResume();
        pausedResetRestoresImportThroughButtons();
        System.out.println("T56: 14 escenarios de controles Swing satisfactorios.");
    }
}
