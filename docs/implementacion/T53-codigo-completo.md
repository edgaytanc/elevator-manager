# T53 — Código completo

Fuentes nuevas completas. No se modifica código de producción ni el POM. El ZIP incluye todas las fuentes anteriores y el ejemplo de importación.

## src/test/java/com/elevatormanager/ui/GuiJourneyScenarios.java

```java
package com.elevatormanager.ui;

import com.elevatormanager.dispatch.HallCallSnapshot;
import com.elevatormanager.logging.SessionLog;
import com.elevatormanager.model.*;
import com.elevatormanager.model.SimulationConfig.Field;
import com.elevatormanager.simulation.SimulationController;
import com.elevatormanager.ui.SystemControlPanel.Action;
import java.awt.Component;
import java.awt.Container;
import java.io.OutputStream;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import javax.swing.*;

/** T53: recorrido integrado por widgets reales. Solo el selector nativo se sustituye.
 * El controlador se consulta como oráculo; las acciones de negocio nacen de botones.
 * Sin JFrame/Robot: geometría, foco, ratón y diálogo del SO requieren revisión nativa.
 */
public final class GuiJourneyScenarios {
    private GuiJourneyScenarios() { }

    public static void completeJourneyWithTwoCabins() throws Exception { journey(2); }
    public static void completeJourneyWithOneCabin() throws Exception { journey(1); }
    private static void journey(int cabins) throws Exception {
        try (var r = new Rig()) {
            r.prepare(cabins, 8, 180);
            var c = r.controller(); var identity = c.sessionId();
            r.state(SimulationState.RESET); r.start();
            r.hall(3, Direction.UP);
            await("recogida externa atendida", () -> !c.calls().isEmpty() && c.calls().values().stream()
                    .allMatch(call -> call.status() == HallCallSnapshot.Status.SERVED));
            r.selectCabin(cabins); r.destination(8);
            await("luz admitida", () -> onEdt(() -> r.cabin().litFloors().contains(8)));
            r.click(Action.STOP); r.state(SimulationState.STOPPED);
            check(c.snapshot(cabins).cabinRequests().stream().anyMatch(q -> q.getFloor() == 8), "Stop conserva destino");
            edt(() -> check(!r.cabin().destinationButton(8).isEnabled(), "Stop bloquea botones"));
            r.start();
            await("destino atendido y luz apagada", () -> c.snapshot(cabins).currentFloor() == 8
                    && c.snapshot(cabins).cabinRequests().isEmpty()
                    && onEdt(() -> !r.cabin().litFloors().contains(8)));
            check(c.sessionId().equals(identity), "Continuar conserva sesión");
            r.click(Action.RESET); r.state(SimulationState.RESET); r.assertInitial();
            check(c.epoch() == 1, "Reset cambia época");
            if (cabins == 2) {
                r.selected.set(Optional.of(Path.of("src/main/resources/examples/requests/23-recorrido-gui.txt")));
            } else r.file("CABIN 1 8\nHALL 3 UP\nHALL 7 DOWN\n");
            r.load();
            int entries = cabins == 2 ? 4 : 3;
            check(c.preparedBatch().orElseThrow().requests().size() == entries, "Lote completo preparado");
            r.assertInitial(); // Importar no mueve ni admite destinos antes de Start.
            r.start();
            await("distribución del lote", () -> c.lastBatchDispatch().isPresent());
            var report = c.lastBatchDispatch().orElseThrow();
            check(report.entries().size() == entries && c.preparedBatch().isEmpty(), "Lote consumido una vez");
            check(report.entries().stream().allMatch(entry -> entry.result().outcome() == SimulationController.Outcome.ENQUEUED),
                    "Todas las instrucciones se enviaron");
            r.click(Action.STOP); r.state(SimulationState.STOPPED); r.start();
            await("lote atendido", () -> c.calls().size() == 2 && c.calls().values().stream()
                    .allMatch(call -> call.status() == HallCallSnapshot.Status.SERVED)
                    && c.snapshots().stream().allMatch(s -> s.cabinRequests().isEmpty()
                            && s.hallRequests().isEmpty() && s.phase() == ElevatorPhase.IDLE));
            check(c.lastBatchDispatch().orElseThrow() == report, "Continuar no redistribuye lote");
            await("eventos en GUI", () -> onEdt(() -> r.panel.eventViewer().displayedText().contains("COMMAND_COMPLETED")));
            r.closeViaButton();
            for (int id = 1; id <= cabins; id++) {
                String log = Files.readString(r.directory.resolve("logs/elevador" + id + ".log"));
                check(log.contains("RESET_COMPLETED") && log.contains("WORKER_TERMINATED"), "Log completo por cabina");
            }
            for (var request : report.batch().requests()) {
                if (request instanceof com.elevatormanager.model.request.CabinRequest cabin) {
                    String log = Files.readString(r.directory.resolve("logs/elevador" + cabin.getElevatorId() + ".log"));
                    check(log.lines().anyMatch(line -> line.contains("evento=COMMAND_COMPLETED;")
                            && line.contains("canonica=" + cabin.getId())), "Destino importado atendido con su identidad");
                }
            }
            check(Files.readString(r.directory.resolve("logs/manejador.log")).contains("MANAGER_TERMINATED"), "Cierre registrado");
            System.out.println("T53_JOURNEY cabins=" + cabins + " configure/start/hall/cabin/stop/resume/reset/import/start/close=OK");
        }
    }

    public static void invalidConfigurationCanBeCorrectedThroughForm() throws Exception {
        try (var r = new Rig()) {
            edt(() -> {
                r.fill(2, 8, 100); r.panel.configurationPanel().input(Field.ELEVATORS).setText("x");
                button(r.panel, "Validar configuración").doClick(0);
                check(!r.panel.configurationPanel().error(Field.ELEVATORS).isBlank(), "Error visible");
                check(r.panel.controller().isEmpty() && !r.panel.button(Action.START).isEnabled(), "No crea sesión inválida");
                r.panel.configurationPanel().input(Field.ELEVATORS).setText("2");
                r.panel.configurationPanel().input(Field.ELEVATORS).postActionEvent(); // Enter real del campo.
            });
            await("corrección prepara sesión", () -> r.controller() != null && onEdt(() -> r.panel.button(Action.START).isEnabled()));
            r.start(); check(r.controller().config().elevatorCount() == 2, "Configuración corregida aplicada");
        }
    }

    public static void singleFloorHasNoExternalDirectionButServesCabin() throws Exception {
        try (var r = new Rig()) {
            r.prepare(1, 1, 100); r.start();
            edt(() -> check(!r.panel.hallCallPanel().button(Direction.UP).isEnabled()
                    && !r.panel.hallCallPanel().button(Direction.DOWN).isEnabled(), "P=1 sin sentido externo válido"));
            r.destination(1);
            await("servicio mismo piso", () -> onEdt(() -> r.panel.eventViewer().displayedText().contains("COMMAND_COMPLETED")));
            check(r.controller().snapshot(1).currentFloor() == 1, "No sale del único piso");
            r.click(Action.RESET); r.state(SimulationState.RESET); r.assertInitial();
        }
    }

    public static void cancelledInvalidAndMissingImportsPreservePreparedBatch() throws Exception {
        try (var r = new Rig()) {
            r.prepare(2, 8, 100); r.file("CABIN 1 8\n"); r.load();
            var original = r.controller().preparedBatch().orElseThrow();
            r.selected.set(Optional.empty()); r.load();
            edt(() -> check(r.panel.detailText().contains("cancelada"), "Cancelación visible"));
            check(r.controller().preparedBatch().orElseThrow() == original, "Cancelar conserva lote");
            r.file("CABIN 1 8\nHALL 8 UP\n"); r.load();
            edt(() -> check(r.panel.detailText().contains("Línea 2"), "Línea de error visible"));
            check(r.controller().preparedBatch().orElseThrow() == original, "No carga prefijo válido");
            r.selected.set(Optional.of(r.directory.resolve("ausente.txt"))); r.load();
            check(r.controller().preparedBatch().orElseThrow() == original, "Error de lectura conserva lote");
            r.start(); await("lote original distribuido", () -> r.controller().lastBatchDispatch().isPresent());
            check(r.controller().lastBatchDispatch().orElseThrow().entries().size() == 1, "Solo envía original");
        }
    }

    public static void importAndRequestsRespectGlobalState() throws Exception {
        try (var r = new Rig()) {
            r.prepare(2, 8, 150); int selections = r.selections.get();
            edt(() -> check(!r.panel.hallCallPanel().button(Direction.UP).isEnabled()
                    && !r.cabin().destinationButton(8).isEnabled(), "RESET bloquea solicitudes"));
            r.start(); r.clickDisabled(Action.LOAD);
            r.click(Action.STOP); r.state(SimulationState.STOPPED); r.clickDisabled(Action.LOAD);
            check(r.selections.get() == selections, "No abre selector fuera de RESET");
            int calls = r.controller().calls().size();
            edt(() -> { r.panel.hallCallPanel().button(Direction.UP).doClick(0); r.cabin().destinationButton(8).doClick(0); });
            check(r.controller().calls().size() == calls && r.controller().snapshot(1).cabinRequests().isEmpty(), "STOPPED no admite por botones");
            r.click(Action.RESET); r.state(SimulationState.RESET);
            edt(() -> check(r.panel.button(Action.LOAD).isEnabled(), "Reset restituye carga"));
        }
    }

    public static void resetCancelsWorkAndClearsLightsAcrossCabins() throws Exception {
        try (var r = new Rig()) {
            r.prepare(2, 8, 250); r.start(); r.destination(8);
            await("luz cabina 1", () -> onEdt(() -> r.cabin().litFloors().contains(8)));
            r.selectCabin(2); r.destination(8); r.hall(7, Direction.DOWN);
            await("admisión cabina 2 y llamada", () -> !r.controller().snapshot(2).cabinRequests().isEmpty()
                    && !r.controller().calls().isEmpty());
            await("asignación del manejador", () -> r.controller().calls().values().stream().anyMatch(call -> call.command().isPresent()));
            int assigned = r.controller().calls().values().stream().filter(call -> call.command().isPresent())
                    .findFirst().orElseThrow().command().orElseThrow().elevatorId();
            r.selectCabin(assigned);
            await("recogida visible en cabina asignada", () -> onEdt(() -> !r.cabin().externalCalls().isEmpty()));
            r.click(Action.RESET); r.state(SimulationState.RESET); r.assertInitial();
            check(r.controller().calls().values().stream().noneMatch(call -> call.status() == HallCallSnapshot.Status.ASSIGNED
                    || call.status() == HallCallSnapshot.Status.DISPATCHED || call.status() == HallCallSnapshot.Status.UNCONFIRMED), "Sin recogidas activas");
            for (int id = 1; id <= 2; id++) {
                r.selectCabin(id);
                await("limpieza de luz y recogidas", () -> onEdt(() -> r.cabin().litFloors().isEmpty() && r.cabin().externalCalls().isEmpty()));
            }
            r.start(); check(r.controller().snapshots().stream().allMatch(s -> s.cabinRequests().isEmpty()), "No revive trabajo cancelado");
        }
    }

    public static void navigationAndPagingPreservePendingHiddenCabin() throws Exception {
        try (var r = new Rig()) {
            r.prepare(4, 25, 150); r.start();
            edt(() -> { button(r.cabin(), "Siguiente").doClick(0); r.cabin().destinationButton(25).doClick(0); });
            await("destino 25 admitido", () -> r.controller().snapshot(1).cabinRequests().stream().anyMatch(q -> q.getFloor() == 25));
            for (int id : new int[] {2, 3, 4}) r.selectCabin(id);
            edt(() -> check(r.panel.cabinDashboard().cachedPanelCount() == 3, "Recorrido usa caché acotada"));
            r.selectCabin(1);
            await("pendiente reconstruido", () -> onEdt(() -> r.cabin().litFloors().contains(25)));
            edt(() -> { button(r.cabin(), "Siguiente").doClick(0);
                check(r.cabin().destinationButton(25).isSelected(), "Luz de página reconstruida"); });
            r.click(Action.RESET); r.state(SimulationState.RESET); r.assertInitial();
        }
    }

    public static void clearViewerDoesNotEraseSessionFiles() throws Exception {
        try (var r = new Rig()) {
            r.prepare(1, 8, 120); r.start(); r.destination(4);
            await("evento de atención", () -> onEdt(() -> r.panel.eventViewer().displayedText().contains("COMMAND_COMPLETED")));
            r.click(Action.STOP); r.state(SimulationState.STOPPED);
            await("Stop registrado y mostrado", () -> onEdt(() -> r.panel.eventViewer().displayedText().contains("CONTROL_STOPPED")));
            var log = r.directory.resolve("logs/elevador1.log"); String before = Files.readString(log);
            edt(() -> { r.panel.eventViewer().followControl().doClick(0);
                check(!r.panel.eventViewer().followControl().isSelected(), "Desactiva seguimiento");
                button(r.panel.eventViewer(), "Limpiar visor").doClick(0);
                check(r.panel.eventViewer().displayedCount() == 0, "Vista vaciada"); });
            check(Files.readString(log).equals(before) && before.contains("COMMAND_COMPLETED"), "Archivo intacto");
            r.start(); r.destination(5);
            await("eventos posteriores a limpiar", () -> onEdt(() -> r.panel.eventViewer().displayedCount() > 0));
        }
    }

    public static void closeButtonDuringMovementTerminatesWholeSession() throws Exception {
        try (var r = new Rig()) {
            r.prepare(2, 8, 250); r.start(); r.destination(8);
            await("movimiento real", () -> r.controller().snapshot(1).phase() == ElevatorPhase.MOVING);
            r.closeViaButton();
            check(r.controller().failure().isEmpty(), "Cierre sin fallo de sesión");
            edt(() -> check(!r.panel.button(Action.START).isEnabled() && !r.panel.button(Action.CLOSE).isEnabled(), "Controles cerrados"));
        }
    }

    private static final class Rig implements AutoCloseable {
        final Path directory = Files.createTempDirectory("t53-gui-");
        final AtomicReference<Optional<Path>> selected = new AtomicReference<>(Optional.empty());
        final AtomicInteger selections = new AtomicInteger();
        final AtomicBoolean closed = new AtomicBoolean();
        final AtomicReference<Throwable> closeFailure = new AtomicReference<>();
        final SystemControlPanel panel;
        Rig() throws Exception {
            panel = onEdt(() -> new SystemControlPanel(config -> {
                var log = SessionLog.open(directory.resolve("logs"), config.elevatorCount(), OutputStream.nullOutputStream());
                try { return new SimulationController(config, 0, log); }
                catch (RuntimeException | Error error) { log.close(); throw error; }
            }, () -> { selections.incrementAndGet(); return selected.get(); }));
            edt(() -> {
                var handler = new WindowCloseHandler(panel::closeAsync, () -> { },
                        () -> { panel.closed(); closed.set(true); },
                        error -> { closeFailure.set(error); panel.closeFailed(error); });
                panel.onCloseRequest(handler::requestClose);
            });
        }
        void fill(int cabins, int floors, int movement) {
            String[] values = {"" + cabins, "" + floors, "" + movement, "60"};
            for (var field : Field.values()) panel.configurationPanel().input(field).setText(values[field.ordinal()]);
        }
        void prepare(int cabins, int floors, int movement) throws Exception {
            edt(() -> { fill(cabins, floors, movement); button(panel, "Validar configuración").doClick(0); });
            await("sesión configurada", () -> controller() != null && onEdt(() -> panel.button(Action.START).isEnabled()));
        }
        SimulationController controller() { return onEdt(() -> panel.controller().orElse(null)); }
        CabinPanel cabin() { var dashboard = panel.cabinDashboard(); return dashboard.cabin(dashboard.selectedCabin()); }
        void click(Action action) throws Exception {
            await("botón " + action + " habilitado", () -> onEdt(() -> panel.button(action).isEnabled()));
            edt(() -> panel.button(action).doClick(0));
        }
        void clickDisabled(Action action) {
            edt(() -> { check(!panel.button(action).isEnabled(), "Botón bloqueado " + action); panel.button(action).doClick(0); });
        }
        void state(SimulationState state) throws Exception {
            await("estado " + state, () -> controller().state() == state && onEdt(() -> {
                boolean ready = panel.stateText().contains("Estado: " + state.name());
                return ready && panel.button(state == SimulationState.RUNNING ? Action.STOP : Action.START).isEnabled();
            }));
        }
        void start() throws Exception { click(Action.START); state(SimulationState.RUNNING); }
        void hall(int floor, Direction direction) {
            edt(() -> { panel.hallCallPanel().floorSelector().setValue(floor);
                check(panel.hallCallPanel().button(direction).isEnabled(), "Sentido habilitado");
                panel.hallCallPanel().button(direction).doClick(0); });
        }
        void destination(int floor) throws Exception {
            await("destino habilitado", () -> onEdt(() -> cabin().destinationButton(floor).isEnabled()));
            edt(() -> cabin().destinationButton(floor).doClick(0));
        }
        void selectCabin(int id) {
            edt(() -> {
                var dashboard = panel.cabinDashboard();
                var spinner = descendants(dashboard).stream().filter(c -> c instanceof JSpinner
                        && "Número de cabina a inspeccionar".equals(c.getAccessibleContext().getAccessibleName()))
                        .map(c -> (JSpinner) c).findFirst().orElseThrow();
                ((JSpinner.NumberEditor) spinner.getEditor()).getTextField().setText("" + id);
                button(dashboard, "Ver cabina").doClick(0);
                check(dashboard.selectedCabin() == id, "Navega por selector gráfico");
            });
        }
        void file(String text) throws Exception {
            Path path = directory.resolve(UUID.randomUUID() + ".txt"); Files.writeString(path, text); selected.set(Optional.of(path));
        }
        void load() throws Exception { click(Action.LOAD); await("carga terminada", () -> onEdt(() -> panel.button(Action.LOAD).isEnabled())); }
        void assertInitial() {
            check(controller().snapshots().stream().allMatch(s -> s.currentFloor() == 1 && s.direction() == Direction.UP
                    && s.cabinRequests().isEmpty() && s.hallRequests().isEmpty() && s.phase() == ElevatorPhase.IDLE), "Todos iniciales 1/UP sin agendas");
        }
        void closeViaButton() throws Exception {
            click(Action.CLOSE); await("cierre confirmado", () -> closed.get() || closeFailure.get() != null);
            check(closeFailure.get() == null, "Error de cierre: " + closeFailure.get());
            check(controller() == null || controller().awaitTermination(Duration.ofSeconds(5)), "Actores terminados");
            check(controller() == null || onEdt(() -> panel.snapshotPump().closeAsync()).isDone(), "Capturador terminado");
        }
        @Override public void close() throws Exception {
            try {
                if (!closed.get()) { onEdt(panel::closeAsync).get(10, TimeUnit.SECONDS); edt(panel::closed); }
            } finally {
                try (var paths = Files.walk(directory)) {
                    for (var path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
                }
            }
        }
    }
    private static List<Component> descendants(Component root) {
        var result = new ArrayList<Component>(); result.add(root);
        if (root instanceof Container container) for (var child : container.getComponents()) result.addAll(descendants(child));
        return result;
    }
    private static JButton button(Component root, String text) {
        return descendants(root).stream().filter(c -> c instanceof JButton b && text.equals(b.getText()))
                .map(c -> (JButton) c).findFirst().orElseThrow(() -> new AssertionError("Botón ausente: " + text));
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static void await(String message, BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(12);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) throw new AssertionError("Tiempo agotado: " + message);
            Thread.sleep(5);
        }
    }
    private static void edt(Runnable action) { onEdt(() -> { action.run(); return null; }); }
    private static <T> T onEdt(Supplier<T> action) {
        if (SwingUtilities.isEventDispatchThread()) return action.get();
        var result = new AtomicReference<T>(); var failure = new AtomicReference<Throwable>();
        try { SwingUtilities.invokeAndWait(() -> { try { result.set(action.get()); } catch (Throwable error) { failure.set(error); } }); }
        catch (Exception error) { throw new AssertionError(error); }
        if (failure.get() instanceof Error error) throw error;
        if (failure.get() != null) throw new IllegalStateException(failure.get());
        return result.get();
    }
    public static void main(String[] args) throws Exception {
        completeJourneyWithTwoCabins();
        completeJourneyWithOneCabin();
        invalidConfigurationCanBeCorrectedThroughForm();
        singleFloorHasNoExternalDirectionButServesCabin();
        cancelledInvalidAndMissingImportsPreservePreparedBatch();
        importAndRequestsRespectGlobalState();
        resetCancelsWorkAndClearsLightsAcrossCabins();
        navigationAndPagingPreservePendingHiddenCabin();
        clearViewerDoesNotEraseSessionFiles();
        closeButtonDuringMovementTerminatesWholeSession();
        System.out.println("T53: 10 escenarios integrados OK");
    }
}
```

## src/test/java/com/elevatormanager/ui/GuiJourneyTest.java

```java
package com.elevatormanager.ui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** T53: acciones gráficas integradas y motor real. */
@Timeout(60)
class GuiJourneyTest {
    @Test void completeJourneyWithTwoCabins() throws Exception {
        GuiJourneyScenarios.completeJourneyWithTwoCabins();
    }
    @Test void completeJourneyWithOneCabin() throws Exception {
        GuiJourneyScenarios.completeJourneyWithOneCabin();
    }
    @Test void invalidConfigurationCanBeCorrectedThroughForm() throws Exception {
        GuiJourneyScenarios.invalidConfigurationCanBeCorrectedThroughForm();
    }
    @Test void singleFloorHasNoExternalDirectionButServesCabin() throws Exception {
        GuiJourneyScenarios.singleFloorHasNoExternalDirectionButServesCabin();
    }
    @Test void cancelledInvalidAndMissingImportsPreservePreparedBatch() throws Exception {
        GuiJourneyScenarios.cancelledInvalidAndMissingImportsPreservePreparedBatch();
    }
    @Test void importAndRequestsRespectGlobalState() throws Exception {
        GuiJourneyScenarios.importAndRequestsRespectGlobalState();
    }
    @Test void resetCancelsWorkAndClearsLightsAcrossCabins() throws Exception {
        GuiJourneyScenarios.resetCancelsWorkAndClearsLightsAcrossCabins();
    }
    @Test void navigationAndPagingPreservePendingHiddenCabin() throws Exception {
        GuiJourneyScenarios.navigationAndPagingPreservePendingHiddenCabin();
    }
    @Test void clearViewerDoesNotEraseSessionFiles() throws Exception {
        GuiJourneyScenarios.clearViewerDoesNotEraseSessionFiles();
    }
    @Test void closeButtonDuringMovementTerminatesWholeSession() throws Exception {
        GuiJourneyScenarios.closeButtonDuringMovementTerminatesWholeSession();
    }
}
```

## src/main/resources/examples/requests/23-recorrido-gui.txt

```text
# T53: N=2, P=8. Cargar en RESET y ejecutar con Start.
CABIN 1 8
CABIN 2 6
HALL 3 UP
HALL 7 DOWN
```
