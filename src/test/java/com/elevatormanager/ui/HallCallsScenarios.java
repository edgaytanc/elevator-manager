package com.elevatormanager.ui;

import com.elevatormanager.dispatch.HallCallSnapshot;
import com.elevatormanager.model.*;
import com.elevatormanager.model.SimulationConfig.Field;
import com.elevatormanager.simulation.SimulationController;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.*;
import javax.swing.*;

/** T49: llamadas Swing y manejador/cabinas reales, sin ventana ni selector nativo. */
public final class HallCallsScenarios {
    private HallCallsScenarios() { }
    public static void unboundPanelDisablesCalls() throws Exception {
        var panel = onEdt(HallCallPanel::new);
        edt(() -> check(!panel.button(Direction.UP).isEnabled() && !panel.button(Direction.DOWN).isEnabled()
                && !panel.floorSelector().isEnabled() && !panel.statusText().isBlank(), "Sin sesión"));
    }

    public static void resetSessionDisablesCalls() throws Exception {
        try (var rig = new Rig()) {
            edt(() -> { rig.panel.refresh(true); check(!rig.enabled(Direction.UP) && !rig.enabled(Direction.DOWN), "RESET sin llamadas"); });
            check(rig.c.calls().isEmpty(), "No envíos antes de Start");
        }
    }

    public static void bottomFloorAllowsOnlyUp() throws Exception {
        try (var rig = new Rig()) {
            rig.start(); rig.select(1);
            edt(() -> check(rig.enabled(Direction.UP) && !rig.enabled(Direction.DOWN), "Extremo inferior"));
        }
    }

    public static void upperFloorAllowsOnlyDown() throws Exception {
        try (var rig = new Rig()) {
            rig.start(); rig.select(5);
            edt(() -> check(!rig.enabled(Direction.UP) && rig.enabled(Direction.DOWN), "Extremo superior"));
        }
    }

    public static void interiorFloorAllowsBothDirections() throws Exception {
        try (var rig = new Rig()) {
            rig.start(); rig.select(3);
            edt(() -> check(rig.enabled(Direction.UP) && rig.enabled(Direction.DOWN), "Piso interior"));
        }
    }

    public static void singleFloorDisablesBothDirections() throws Exception {
        try (var rig = new Rig(1, false)) {
            rig.start();
            edt(() -> check(!rig.enabled(Direction.UP) && !rig.enabled(Direction.DOWN), "P=1 sin extremos posibles"));
        }
    }

    public static void spinnerReflectsConfiguredFloorBounds() throws Exception {
        try (var rig = new Rig()) {
            edt(() -> {
                var model = (javax.swing.SpinnerNumberModel) rig.panel.floorSelector().getModel();
                check(model.getMinimum().equals(1) && model.getMaximum().equals(5), "Rango 1..P");
                check(rig.panel.floorSelector().getPreviousValue() == null, "Sin piso cero");
                rig.panel.floorSelector().setValue(5);
                check(rig.panel.floorSelector().getNextValue() == null, "Sin piso P+1");
            });
        }
    }

    public static void upCallGoesThroughManagerAndIsServed() throws Exception {
        try (var rig = new Rig()) {
            rig.start(); rig.select(3); rig.click(Direction.UP);
            waitFor(() -> rig.c.calls().size() == 1);
            var call = rig.c.calls().values().iterator().next();
            check(call.request().getFloor() == 3 && call.request().getDirection() == Direction.UP, "Piso/sentido correctos");
            check(call.request().getEpoch() == rig.c.epoch() && call.request().getId() != null, "Época e identidad propias");
            waitFor(() -> rig.c.calls().values().stream().allMatch(s -> s.status() == HallCallSnapshot.Status.SERVED));
            var served = rig.c.calls().values().iterator().next();
            check(served.command().isPresent() && served.command().orElseThrow().elevatorId() >= 1, "Asignación del manejador y atención real");
        }
    }

    public static void downCallGoesThroughManagerAndIsServed() throws Exception {
        try (var rig = new Rig()) {
            rig.start(); rig.select(3); rig.click(Direction.DOWN);
            waitFor(() -> rig.c.calls().size() == 1);
            check(rig.c.calls().values().iterator().next().request().getDirection() == Direction.DOWN, "Dirección DOWN");
            waitFor(() -> rig.c.calls().values().stream().allMatch(s -> s.status() == HallCallSnapshot.Status.SERVED));
        }
    }

    public static void oppositeCallsAtSameFloorRemainDistinct() throws Exception {
        try (var rig = new Rig()) {
            rig.start(); rig.select(3);
            edt(() -> { rig.panel.button(Direction.UP).doClick(0); rig.panel.button(Direction.DOWN).doClick(0); });
            waitFor(() -> rig.c.calls().size() == 2);
            check(rig.c.calls().values().stream().map(s -> s.request().getDirection()).distinct().count() == 2, "Dos sentidos distintos");
            check(rig.c.calls().values().stream().map(HallCallSnapshot::canonicalRequestId).distinct().count() == 2, "Canónicas distintas");
        }
    }

    public static void repeatedClicksUseManagerDeduplication() throws Exception {
        try (var rig = new Rig()) {
            rig.start(); rig.select(4);
            edt(() -> { rig.panel.button(Direction.UP).doClick(0); rig.panel.button(Direction.UP).doClick(0); });
            waitFor(() -> rig.c.calls().size() == 2);
            check(rig.c.calls().values().stream().map(HallCallSnapshot::canonicalRequestId).distinct().count() == 1, "Deduplicación activa del manejador");
            check(rig.c.calls().values().stream().map(s -> s.request().getId()).distinct().count() == 2, "Identidad por pulsación");
        }
    }

    public static void enqueuedReceiptDoesNotClaimService() throws Exception {
        try (var rig = new Rig(5, true)) {
            rig.start(); rig.select(4); rig.click(Direction.UP); var delivery = rig.delivery();
            edt(delivery);
            edt(() -> check(rig.panel.statusText().contains("enviada al manejador")
                    && rig.panel.statusText().contains("atención pendiente"), "Envío no es servicio"));
        }
    }

    public static void stopDisablesAndResumeRestoresCalls() throws Exception {
        try (var rig = new Rig()) {
            rig.start(); rig.c.stop().get(5, TimeUnit.SECONDS);
            edt(() -> { rig.panel.refresh(true); check(!rig.enabled(Direction.UP) && !rig.enabled(Direction.DOWN), "STOPPED bloqueado"); });
            rig.c.start().get(5, TimeUnit.SECONDS);
            edt(() -> { rig.panel.refresh(true); check(rig.enabled(Direction.UP), "Continuación habilita"); });
        }
    }

    public static void resetInvalidatesDelayedReceipt() throws Exception {
        try (var rig = new Rig(5, true)) {
            rig.start(); rig.select(4); rig.click(Direction.UP); var delivery = rig.delivery();
            rig.c.reset().get(5, TimeUnit.SECONDS);
            edt(() -> { rig.panel.refresh(true); String message = rig.panel.statusText(); delivery.run();
                check(message.equals(rig.panel.statusText()) && !rig.enabled(Direction.UP), "Recibo viejo tras Reset ignorado"); });
        }
    }

    public static void stopInvalidatesDelayedReceipt() throws Exception {
        try (var rig = new Rig(5, true)) {
            rig.start(); rig.select(4); rig.click(Direction.UP); var delivery = rig.delivery();
            rig.c.stop().get(5, TimeUnit.SECONDS);
            edt(() -> { rig.panel.refresh(true); String message = rig.panel.statusText(); delivery.run();
                check(message.equals(rig.panel.statusText()) && !rig.enabled(Direction.UP), "Recibo viejo tras Stop ignorado"); });
        }
    }

    public static void closeInvalidatesDelayedReceipt() throws Exception {
        try (var rig = new Rig(5, true)) {
            rig.start(); rig.select(4); rig.click(Direction.UP); var delivery = rig.delivery();
            rig.c.closeAsync().get(5, TimeUnit.SECONDS);
            edt(() -> { rig.panel.refresh(false); String message = rig.panel.statusText(); delivery.run();
                check(message.equals(rig.panel.statusText()) && !rig.enabled(Direction.UP), "Recibo viejo tras cierre ignorado"); });
        }
    }

    public static void newerCallMessageWinsAgainstOutOfOrderCallbacks() throws Exception {
        try (var rig = new Rig(5, true)) {
            rig.start(); rig.select(2); rig.click(Direction.UP); var first = rig.delivery();
            rig.select(3); rig.click(Direction.DOWN); var second = rig.delivery();
            edt(() -> { second.run(); String message = rig.panel.statusText(); first.run();
                check(message.equals(rig.panel.statusText()) && message.contains("Piso 3 para bajar"), "No retrocede mensaje"); });
        }
    }

    public static void rebindingSessionInvalidatesOldReceipt() throws Exception {
        try (var rig = new Rig(5, true); var another = new SimulationController(new SimulationConfig(1, 3, 20, 5), 0)) {
            try {
                rig.start(); rig.select(4); rig.click(Direction.UP); var delivery = rig.delivery();
                edt(() -> { rig.panel.bind(another); rig.panel.refresh(true); String message = rig.panel.statusText(); delivery.run();
                    check(message.equals(rig.panel.statusText()) && !rig.enabled(Direction.UP), "Otra sesión no recibe mensaje anterior"); });
            } finally { another.closeAsync().get(5, TimeUnit.SECONDS); check(another.awaitTermination(Duration.ofSeconds(5)), "Otra sesión cerrada"); }
        }
    }

    public static void invalidTypedFloorDoesNotSubmitPreviousValue() throws Exception {
        try (var rig = new Rig()) {
            rig.start(); rig.select(3); rig.type("abc"); rig.click(Direction.UP);
            edt(() -> check(rig.panel.statusText().contains("entero"), "Error visible"));
            check(rig.c.calls().isEmpty(), "No envía el valor anterior");
        }
    }

    public static void decimalAndOverflowTextAreRejected() throws Exception {
        try (var rig = new Rig()) {
            rig.start(); rig.select(3);
            for (String text : new String[] {"2.5", "2147483648", "1e2", ""}) {
                rig.type(text);
                edt(() -> {
                    try { ((JSpinner.NumberEditor) rig.panel.floorSelector().getEditor()).getTextField().commitEdit();
                        throw new AssertionError("El editor aceptó un no entero"); }
                    catch (java.text.ParseException expected) { }
                });
                rig.click(Direction.UP);
                edt(() -> check(rig.panel.statusText().contains("entero"), "Rechazo numérico"));
            }
            check(rig.c.calls().isEmpty(), "Sin llamadas truncadas");
        }
    }

    public static void outOfRangeTypedFloorDoesNotSubmit() throws Exception {
        try (var rig = new Rig()) {
            rig.start(); rig.select(3);
            for (String text : new String[] {"0", "-1", "6"}) {
                rig.type(text); rig.click(Direction.UP);
                edt(() -> check(rig.panel.statusText().contains("no permite"), "Fuera de rango visible"));
            }
            check(rig.c.calls().isEmpty(), "No hay pisos ilegales");
        }
    }

    public static void validTypedFloorIsUsedForRequest() throws Exception {
        try (var rig = new Rig()) {
            rig.start(); rig.select(2); rig.type("4"); rig.click(Direction.UP);
            waitFor(() -> rig.c.calls().size() == 1);
            check(rig.c.calls().values().iterator().next().request().getFloor() == 4, "Usa entrada confirmada, no valor viejo");
            rig.select(5); rig.type("2");
            edt(() -> {
                try { ((JSpinner.NumberEditor) rig.panel.floorSelector().getEditor()).getTextField().commitEdit(); }
                catch (java.text.ParseException error) { throw new AssertionError(error); }
                check(rig.enabled(Direction.UP), "Confirmar piso habilita botón desde el extremo");
            });
        }
    }

    public static void forbiddenTypedDirectionCannotSend() throws Exception {
        try (var rig = new Rig()) {
            rig.start(); rig.select(3); rig.type("5"); rig.click(Direction.UP);
            check(rig.c.calls().isEmpty(), "No subir desde techo aunque texto no estuviera confirmado");
            edt(() -> check(!rig.enabled(Direction.UP), "Extremo digitado deshabilita Subir"));
            rig.select(3); rig.type("1"); rig.click(Direction.DOWN);
            check(rig.c.calls().isEmpty(), "No bajar desde piso 1");
            edt(() -> check(!rig.enabled(Direction.DOWN), "Extremo digitado deshabilita Bajar"));
        }
    }

    public static void panelRejectsOffEdtAccess() throws Exception {
        try { new HallCallPanel(); throw new AssertionError("Construcción fuera EDT"); }
        catch (IllegalStateException expected) { }
        try (var rig = new Rig()) {
            try { rig.panel.refresh(true); throw new AssertionError("Refresco fuera EDT"); }
            catch (IllegalStateException expected) { }
            try { rig.panel.bind(rig.c); throw new AssertionError("Vinculación fuera EDT"); }
            catch (IllegalStateException expected) { }
        }
    }

    public static void largeBuildingUsesBoundedNumberOfComponents() throws Exception {
        try (var rig = new Rig(Integer.MAX_VALUE, false)) {
            rig.start(); rig.select(Integer.MAX_VALUE);
            edt(() -> check(!rig.enabled(Direction.UP) && rig.enabled(Direction.DOWN)
                    && rig.panel.getComponentCount() == 2, "Selector fijo independiente de P"));
        }
    }

    public static void parentControlBlocksCallsImmediately() throws Exception {
        withSystem((panel, c) -> {
            edt(() -> {
                panel.hallCallPanel().floorSelector().setValue(3);
                check(panel.hallCallPanel().button(Direction.UP).isEnabled(), "RUNNING habilitado");
                panel.button(SystemControlPanel.Action.STOP).doClick(0);
                check(!panel.hallCallPanel().button(Direction.UP).isEnabled(), "Bloqueo inmediato antes de callback Stop");
            });
        });
    }

    public static void fullWindowCompositionCanCallWithoutImporting() throws Exception {
        withSystem((panel, c) -> {
            edt(() -> { panel.hallCallPanel().floorSelector().setValue(3); panel.hallCallPanel().button(Direction.UP).doClick(0); });
            waitFor(() -> c.calls().size() == 1);
            waitFor(() -> c.calls().values().stream().allMatch(call -> call.status() == HallCallSnapshot.Status.SERVED));
            check(c.preparedBatch().isEmpty(), "Admisión manual sin archivo");
        });
    }

    public static void parentCloseDisablesHallControls() throws Exception {
        withSystem((panel, c) -> {
            var closed = onEdt(() -> {
                var future = panel.closeAsync();
                check(!panel.hallCallPanel().button(Direction.UP).isEnabled()
                        && !panel.hallCallPanel().floorSelector().isEnabled(), "Cierre bloquea panel");
                return future;
            });
            closed.get(5, TimeUnit.SECONDS);
        });
    }

    private static final class Rig implements AutoCloseable {
        final SimulationController c;
        final HallCallPanel panel;
        final BlockingQueue<Runnable> deliveries = new LinkedBlockingQueue<>();
        Rig() { this(5, false); }
        Rig(int floors, boolean delayed) {
            c = new SimulationController(new SimulationConfig(2, floors, 100, 10), 0);
            panel = onEdt(() -> {
                var p = delayed ? new HallCallPanel(deliveries::add) : new HallCallPanel(); p.bind(c); return p;
            });
        }
        void start() throws Exception { c.start().get(5, TimeUnit.SECONDS); edt(() -> panel.refresh(true)); }
        void select(int number) throws Exception { edt(() -> panel.floorSelector().setValue(number)); }
        void type(String text) throws Exception { edt(() -> ((JSpinner.NumberEditor) panel.floorSelector().getEditor()).getTextField().setText(text)); }
        boolean enabled(Direction direction) { return panel.button(direction).isEnabled(); }
        void click(Direction direction) throws Exception { edt(() -> panel.button(direction).doClick(0)); }
        Runnable delivery() throws Exception {
            Runnable delivery = deliveries.poll(5, TimeUnit.SECONDS); check(delivery != null, "Callback recibido"); return delivery;
        }
        @Override public void close() throws Exception {
            c.closeAsync().get(5, TimeUnit.SECONDS);
            check(c.awaitTermination(Duration.ofSeconds(5)), "Hilos cerrados");
            edt(() -> panel.refresh(false));
        }
    }
    @FunctionalInterface private interface SystemCheck { void run(SystemControlPanel panel, SimulationController c) throws Exception; }
    private static void withSystem(SystemCheck check) throws Exception {
        var panel = onEdt(() -> new SystemControlPanel(config -> new SimulationController(config, 0), Optional::empty));
        try {
            edt(() -> {
                var form = panel.configurationPanel(); String[] values = {"2", "5", "50", "10"};
                for (var field : Field.values()) form.input(field).setText(values[field.ordinal()]);
                form.validateConfiguration();
            });
            waitFor(() -> onEdt(() -> panel.controller().isPresent()));
            edt(() -> panel.button(SystemControlPanel.Action.START).doClick(0));
            waitFor(() -> onEdt(() -> { panel.updateView(); return panel.hallCallPanel().button(Direction.UP).isEnabled(); }));
            check.run(panel, onEdt(() -> panel.controller().orElseThrow()));
        } finally {
            onEdt(panel::closeAsync).get(5, TimeUnit.SECONDS);
            var c = onEdt(() -> panel.controller().orElse(null));
            if (c != null) check(c.awaitTermination(Duration.ofSeconds(5)), "Sesión compuesta cerrada");
            edt(panel::closed);
        }
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
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
        unboundPanelDisablesCalls();
        resetSessionDisablesCalls();
        bottomFloorAllowsOnlyUp();
        upperFloorAllowsOnlyDown();
        interiorFloorAllowsBothDirections();
        singleFloorDisablesBothDirections();
        spinnerReflectsConfiguredFloorBounds();
        upCallGoesThroughManagerAndIsServed();
        downCallGoesThroughManagerAndIsServed();
        oppositeCallsAtSameFloorRemainDistinct();
        repeatedClicksUseManagerDeduplication();
        enqueuedReceiptDoesNotClaimService();
        stopDisablesAndResumeRestoresCalls();
        resetInvalidatesDelayedReceipt();
        stopInvalidatesDelayedReceipt();
        closeInvalidatesDelayedReceipt();
        newerCallMessageWinsAgainstOutOfOrderCallbacks();
        rebindingSessionInvalidatesOldReceipt();
        invalidTypedFloorDoesNotSubmitPreviousValue();
        decimalAndOverflowTextAreRejected();
        outOfRangeTypedFloorDoesNotSubmit();
        validTypedFloorIsUsedForRequest();
        forbiddenTypedDirectionCannotSend();
        panelRejectsOffEdtAccess();
        largeBuildingUsesBoundedNumberOfComponents();
        parentControlBlocksCallsImmediately();
        fullWindowCompositionCanCallWithoutImporting();
        parentCloseDisablesHallControls();
        System.out.println("T49: 28 escenarios satisfactorios.");
    }
}
