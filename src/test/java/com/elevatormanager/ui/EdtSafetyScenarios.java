package com.elevatormanager.ui;

import com.elevatormanager.dispatch.HallCallSnapshot;
import com.elevatormanager.model.*;
import com.elevatormanager.model.SimulationConfig.Field;
import com.elevatormanager.model.request.*;
import com.elevatormanager.simulation.*;
import java.awt.Component;
import java.awt.Container;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import javax.swing.*;
import javax.swing.event.*;
import javax.swing.text.JTextComponent;

/** T52: EDT real, actores/lector reales, auditoría de Swing y retenciones deterministas. */
public final class EdtSafetyScenarios {
    private EdtSafetyScenarios() { }
    public static void gateAllowsFirstRefresh() throws Exception {
        var clock = new AtomicLong(0); check(new RefreshGate(100, clock::get).due(), "Primer refresco");
    }

    public static void gateLimitsRepeatedRefreshes() throws Exception {
        var clock = new AtomicLong(0); var gate = new RefreshGate(100, clock::get);
        check(gate.due() && !gate.due(), "Sin repetición inmediata"); clock.set(99); check(!gate.due(), "Antes del límite");
        clock.set(100); check(gate.due(), "Intervalo cumplido");
    }

    public static void gateDoesNotCatchUpMissedTicks() throws Exception {
        var clock = new AtomicLong(); var gate = new RefreshGate(100, clock::get); gate.due();
        clock.set(10000); check(gate.due() && !gate.due(), "No acumula cien trabajos atrasados");
    }

    public static void gateHandlesNanoTimeWrap() throws Exception {
        var clock = new AtomicLong(Long.MAX_VALUE - 50); var gate = new RefreshGate(100, clock::get); gate.due();
        clock.set(Long.MIN_VALUE + 50); check(gate.due(), "Diferencia monotónica con wrap");
    }

    public static void pumpCapturesOutsideEdt() throws Exception {
        try (var rig = new EngineRig()) {
            var onEdt = new AtomicBoolean(true); var pump = new UiSnapshotPump(() -> { onEdt.set(SwingUtilities.isEventDispatchThread()); return UiSnapshotPump.capture(rig.c); }, System::nanoTime);
            try { edt(pump::request); waitFor(() -> pump.latest().isPresent()); check(!onEdt.get(), "Captura fuera de EDT"); }
            finally { pump.closeAsync().get(5, TimeUnit.SECONDS); }
        }
    }

    public static void pumpCoalescesBusyRequests() throws Exception {
        try (var rig = new EngineRig()) {
            var entered = new CountDownLatch(1); var release = new CountDownLatch(1); var count = new AtomicInteger(); var clock = new AtomicLong();
            var pump = new UiSnapshotPump(() -> { count.incrementAndGet(); entered.countDown(); hold(release); return UiSnapshotPump.capture(rig.c); }, clock::get);
            try {
                pump.request(); check(entered.await(5, TimeUnit.SECONDS), "Captura retenida");
                for (int i = 0; i < 10000; i++) { clock.addAndGet(UiSnapshotPump.INTERVAL_NANOS); pump.request(); }
                check(count.get() == 1, "Una tarea en curso, sin cola de diez mil capturas");
            } finally { release.countDown(); pump.closeAsync().get(5, TimeUnit.SECONDS); }
        }
    }

    public static void pumpEnforcesMinimumCaptureInterval() throws Exception {
        try (var rig = new EngineRig()) {
            var clock = new AtomicLong(); var count = new AtomicInteger();
            var pump = new UiSnapshotPump(() -> { count.incrementAndGet(); return UiSnapshotPump.capture(rig.c); }, clock::get);
            try {
                pump.request(); waitFor(() -> pump.latest().isPresent());
                for (int i = 0; i < 100; i++) pump.request(); check(count.get() == 1, "Captura limitada por reloj");
                clock.addAndGet(UiSnapshotPump.INTERVAL_NANOS); waitFor(() -> { pump.request(); return count.get() == 2; });
            } finally { pump.closeAsync().get(5, TimeUnit.SECONDS); }
        }
    }

    public static void latestFrameIsReplacedAfterReset() throws Exception {
        try (var rig = new EngineRig()) {
            var clock = new AtomicLong(); var pump = new UiSnapshotPump(() -> UiSnapshotPump.capture(rig.c), clock::get);
            try {
                pump.request(); waitFor(() -> pump.latest().isPresent()); var first = pump.latest().orElseThrow();
                rig.c.reset().get(5, TimeUnit.SECONDS); clock.addAndGet(UiSnapshotPump.INTERVAL_NANOS); pump.request();
                waitFor(() -> pump.latest().orElseThrow().epoch() == 1);
                check(pump.latest().orElseThrow() != first && first.epoch() == 0, "Publicación reemplazada, copia anterior inmutable");
            } finally { pump.closeAsync().get(5, TimeUnit.SECONDS); }
        }
    }

    public static void frameCollectionsAreDeeplyImmutable() throws Exception {
        try (var rig = new EngineRig()) {
            rig.c.start().get(5, TimeUnit.SECONDS);
            rig.c.submit(new HallRequest(UUID.randomUUID(), 0, 7, Instant.now(), Direction.UP)).get(5, TimeUnit.SECONDS);
            waitFor(() -> rig.c.calls().values().stream().anyMatch(c -> c.status() == HallCallSnapshot.Status.ASSIGNED));
            var frame = UiSnapshotPump.capture(rig.c);
            try { frame.snapshots().clear(); throw new AssertionError("Snapshots mutables"); } catch (UnsupportedOperationException expected) { }
            try { frame.assigned().clear(); throw new AssertionError("Mapa mutable"); } catch (UnsupportedOperationException expected) { }
            try { frame.assigned().values().iterator().next().clear(); throw new AssertionError("Lista mutable"); } catch (UnsupportedOperationException expected) { }
        }
    }

    public static void captureExcludesServedHistory() throws Exception {
        try (var rig = new EngineRig()) {
            rig.c.start().get(5, TimeUnit.SECONDS);
            rig.c.submit(new HallRequest(UUID.randomUUID(), 0, 1, Instant.now(), Direction.UP)).get(5, TimeUnit.SECONDS);
            waitFor(() -> !rig.c.calls().isEmpty() && rig.c.calls().values().stream().allMatch(c -> c.status() == HallCallSnapshot.Status.SERVED));
            check(UiSnapshotPump.capture(rig.c).assigned().isEmpty() && !rig.c.calls().isEmpty(), "Historial servido no viaja a Swing");
        }
    }

    public static void dashboardRejectsFrameFromOldEpoch() throws Exception {
        try (var rig = new EngineRig()) {
            var saved = UiSnapshotPump.capture(rig.c); var pump = new UiSnapshotPump(() -> saved, System::nanoTime);
            try {
                pump.request(); waitFor(() -> pump.latest().isPresent()); rig.c.reset().get(5, TimeUnit.SECONDS);
                edt(() -> { var dashboard = new CabinDashboardPanel(); dashboard.bind(rig.c); dashboard.usePump(pump); dashboard.refresh(true);
                    check(dashboard.cabin(1).litFloors().isEmpty() && dashboard.cabin(1).locationText().contains("Piso actual: 1"), "No se aplica conjunto de época anterior"); });
            } finally { pump.closeAsync().get(5, TimeUnit.SECONDS); }
        }
    }

    public static void dashboardRejectsFrameFromAnotherSession() throws Exception {
        try (var first = new EngineRig(); var second = new EngineRig()) {
            first.c.start().get(5, TimeUnit.SECONDS);
            first.c.submit(new CabinRequest(UUID.randomUUID(), 0, 7, Instant.now(), 1)).get(5, TimeUnit.SECONDS);
            waitFor(() -> !first.c.snapshot(1).cabinRequests().isEmpty());
            var saved = UiSnapshotPump.capture(first.c); var pump = new UiSnapshotPump(() -> saved, System::nanoTime);
            try {
                pump.request(); waitFor(() -> pump.latest().isPresent());
                edt(() -> { var dashboard = new CabinDashboardPanel(); dashboard.bind(second.c); dashboard.usePump(pump); dashboard.refresh(true);
                    check(dashboard.cabinCount() == 2 && dashboard.cabin(1).litFloors().isEmpty(), "No mezcla sesiones"); });
            } finally { pump.closeAsync().get(5, TimeUnit.SECONDS); }
        }
    }

    public static void captureFailureCanRecover() throws Exception {
        try (var rig = new EngineRig()) {
            var clock = new AtomicLong(); var attempt = new AtomicInteger();
            var pump = new UiSnapshotPump(() -> { if (attempt.incrementAndGet() == 1) throw new IllegalStateException("captura inyectada"); return UiSnapshotPump.capture(rig.c); }, clock::get);
            try {
                pump.request(); waitFor(() -> pump.failure().isPresent());
                clock.addAndGet(UiSnapshotPump.INTERVAL_NANOS); waitFor(() -> { pump.request(); return pump.latest().isPresent(); });
                check(pump.failure().isEmpty(), "Recupera publicación sin tocar Swing desde capturador");
            } finally { pump.closeAsync().get(5, TimeUnit.SECONDS); }
        }
    }

    public static void closedPumpCannotPublishLateFrame() throws Exception {
        try (var rig = new EngineRig()) {
            var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
            var pump = new UiSnapshotPump(() -> { entered.countDown(); hold(release); return UiSnapshotPump.capture(rig.c); }, System::nanoTime);
            try {
                pump.request(); check(entered.await(5, TimeUnit.SECONDS), "Captura activa"); var done = pump.closeAsync(); release.countDown(); done.get(5, TimeUnit.SECONDS);
                pump.request(); check(pump.latest().isEmpty(), "Publicación tardía descartada y nuevas solicitudes ignoradas");
            } finally { release.countDown(); pump.closeAsync().get(5, TimeUnit.SECONDS); }
        }
    }

    public static void closeWaitsForRealCaptureTerminationWithoutBlockingEdt() throws Exception {
        try (var rig = new EngineRig()) {
            var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
            var pump = new UiSnapshotPump(() -> { entered.countDown(); hold(release); return UiSnapshotPump.capture(rig.c); }, System::nanoTime);
            try {
                pump.request(); check(entered.await(5, TimeUnit.SECONDS), "Captura activa"); var done = onEdt(pump::closeAsync);
                check(!done.isDone(), "No anuncia cierre antes de terminar"); pulses(4); release.countDown(); done.get(5, TimeUnit.SECONDS);
            } finally { release.countDown(); pump.closeAsync().get(5, TimeUnit.SECONDS); }
        }
    }

    public static void closeIsIdempotent() throws Exception {
        try (var rig = new EngineRig()) {
            var pump = new UiSnapshotPump(rig.c); pump.request();
            var first = pump.closeAsync(); var second = pump.closeAsync(); first.get(5, TimeUnit.SECONDS); second.get(5, TimeUnit.SECONDS);
            check(first.isDone() && second.isDone() && pump.latest().isEmpty(), "Un cierre real y recibos independientes");
        }
    }

    public static void constantTimeSnapshotValidatesCabinId() throws Exception {
        try (var rig = new EngineRig()) {
            check(rig.c.snapshot(2).id() == 2, "Snapshot directo");
            for (int id : new int[] {0, 3}) try { rig.c.snapshot(id); throw new AssertionError("Cabina inválida"); } catch (IllegalArgumentException expected) { }
        }
    }

    public static void dashboardCachesAtMostThreePanels() throws Exception {
        var c = new SimulationController(new SimulationConfig(30, 25, 20, 10), 0);
        try {
            edt(() -> { var dashboard = new CabinDashboardPanel(); dashboard.bind(c);
                for (int id = 1; id <= 30; id++) { dashboard.selectCabin(id); check(dashboard.cachedPanelCount() <= 3, "Caché limitada"); }
                check(dashboard.cabinCount() == 30 && dashboard.selectedCabin() == 30, "Las treinta siguen accesibles"); });
        } finally { close(c); }
    }

    public static void selectionRemainsConsistentAfterTabNavigation() throws Exception {
        try (var rig = new EngineRig()) {
            edt(() -> { var dashboard = new CabinDashboardPanel(); dashboard.bind(rig.c); dashboard.selectCabin(2);
                check(dashboard.selectedCabin() == 2 && dashboard.cabin(2).locationText().contains("Piso actual: 1"), "Selección coherente"); });
        }
    }

    public static void evictedCabinRebuildsFromPublishedState() throws Exception {
        var c = new SimulationController(new SimulationConfig(8, 8, 200, 20), 0);
        try {
            c.start().get(5, TimeUnit.SECONDS); c.submit(new CabinRequest(UUID.randomUUID(), 0, 8, Instant.now(), 1)).get(5, TimeUnit.SECONDS);
            waitFor(() -> !c.snapshot(1).cabinRequests().isEmpty());
            edt(() -> { var dashboard = new CabinDashboardPanel(); dashboard.bind(c); dashboard.refresh(true);
                for (int id = 2; id <= 8; id++) dashboard.selectCabin(id);
                dashboard.selectCabin(1); check(dashboard.cabin(1).litFloors().contains(8), "Panel recreado usa agenda vigente"); });
        } finally { close(c); }
    }

    public static void unchangedSnapshotDoesNotRewriteDocuments() throws Exception {
        try (var rig = new EngineRig()) {
            edt(() -> { var p = new CabinPanel(rig.c, 1); var snapshot = rig.c.snapshot(1); p.render(snapshot, List.of(), false);
                var mutations = new AtomicInteger(); watchDocuments(p, mutations);
                for (int i = 0; i < 100; i++) p.render(snapshot, List.of(), false);
                check(mutations.get() == 0, "No reescribe textos sin cambios"); });
        }
    }

    public static void repaintAuditAndPulsesDuringConcurrentMovement() throws Exception {
        try (var audit = new Audit(); var rig = new PanelRig(8, null, null, null)) {
            rig.prepare(); rig.start(); var c = rig.controller();
            for (int id = 1; id <= 8; id++) c.submit(new CabinRequest(UUID.randomUUID(), c.epoch(), 8, Instant.now(), id)).get(5, TimeUnit.SECONDS);
            waitFor(() -> c.snapshots().stream().filter(s -> s.phase() == ElevatorPhase.MOVING).count() == 8);
            pulses(8); edt(() -> { rig.panel.cabinDashboard().selectCabin(8); rig.panel.updateView(); });
            audit.assertClean();
        }
    }

    public static void largeRealImportLeavesEdtAvailable() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1); var readOnEdt = new AtomicBoolean(true);
        try (var audit = new Audit(); var rig = new PanelRig(4, config -> EdtImportFixture.create(config, entered, release, readOnEdt), null, null)) {
            rig.prepare(); rig.largeFile(); rig.click(SystemControlPanel.Action.LOAD);
            check(entered.await(5, TimeUnit.SECONDS), "Lectura retenida"); pulses(6); check(!readOnEdt.get(), "Lector fuera de EDT");
            release.countDown(); waitFor(() -> rig.controller().preparedBatch().map(b -> b.requests().size() == 10000).orElse(false));
            check(rig.controller().state() == SimulationState.RESET, "Importación real sin mover cabinas"); audit.assertClean();
        } finally { release.countDown(); }
    }

    public static void closeDuringImportDoesNotFreezeEdt() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1); var readOnEdt = new AtomicBoolean(true);
        try (var audit = new Audit(); var rig = new PanelRig(2, config -> EdtImportFixture.create(config, entered, release, readOnEdt), null, null)) {
            rig.prepare(); rig.largeFile(); rig.click(SystemControlPanel.Action.LOAD); check(entered.await(5, TimeUnit.SECONDS), "Lectura activa");
            var closed = onEdt(rig.panel::closeAsync); pulses(3); release.countDown(); closed.get(5, TimeUnit.SECONDS); audit.assertClean();
        } finally { release.countDown(); }
    }

    public static void slowResourceCloseLeavesEdtAvailable() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        try (var audit = new Audit(); var rig = new PanelRig(2, null, null, null)) {
            rig.prepare(); rig.start(); rig.controller().registerResource(() -> { entered.countDown(); hold(release); }).get(5, TimeUnit.SECONDS);
            var closed = onEdt(rig.panel::closeAsync); check(entered.await(5, TimeUnit.SECONDS), "Recurso cerrando");
            pulses(5); check(!closed.isDone(), "No dispone antes de liberar recurso"); release.countDown(); closed.get(5, TimeUnit.SECONDS); audit.assertClean();
        } finally { release.countDown(); }
    }

    public static void preparationLeavesEdtAvailable() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1); var openedOnEdt = new AtomicBoolean(true);
        try (var audit = new Audit(); var rig = new PanelRig(2, config -> { openedOnEdt.set(SwingUtilities.isEventDispatchThread()); entered.countDown(); hold(release); return new SimulationController(config, 0); }, null, null)) {
            rig.confirm(); check(entered.await(5, TimeUnit.SECONDS), "Preparador activo"); pulses(5); check(!openedOnEdt.get(), "Apertura fuera de EDT");
            release.countDown(); waitFor(() -> rig.controller() != null); audit.assertClean();
        } finally { release.countDown(); }
    }

    public static void windowCloseWaitsForCaptureAndDropsLatePublication() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        try (var audit = new Audit(); var rig = new PanelRig(2, null, null, c -> new UiSnapshotPump(() -> { entered.countDown(); hold(release); return UiSnapshotPump.capture(c); }, System::nanoTime))) {
            rig.prepare(); check(entered.await(5, TimeUnit.SECONDS), "Capturador activo"); var pump = onEdt(rig.panel::snapshotPump);
            var closed = onEdt(rig.panel::closeAsync); pulses(5); check(!closed.isDone(), "Cierre espera al capturador"); release.countDown(); closed.get(5, TimeUnit.SECONDS);
            check(pump.latest().isEmpty(), "No publica después del cierre"); audit.assertClean();
        } finally { release.countDown(); }
    }

    public static void resetAndLateReceiptsNeverMutateSwingOffEdt() throws Exception {
        try (var audit = new Audit(); var rig = new PanelRig(4, null, null, null)) {
            rig.prepare(); rig.start();
            edt(() -> { for (int id = 1; id <= 4; id++) rig.panel.cabinDashboard().cabin(id).destinationButton(8).doClick(0);
                rig.panel.button(SystemControlPanel.Action.STOP).doClick(0); rig.panel.button(SystemControlPanel.Action.RESET).doClick(0); });
            waitFor(() -> rig.controller().state() == SimulationState.RESET); pulses(4);
            edt(() -> check(!rig.panel.hallCallPanel().button(Direction.UP).isEnabled(), "No habilita llamadas con respuesta vieja")); audit.assertClean();
        }
    }

    public static void panelCloseTerminatesSnapshotWorker() throws Exception {
        try (var rig = new PanelRig(2, null, null, null)) {
            rig.prepare(); var pump = onEdt(rig.panel::snapshotPump); waitFor(() -> pump.latest().isPresent());
            onEdt(rig.panel::closeAsync).get(5, TimeUnit.SECONDS); pump.closeAsync().get(5, TimeUnit.SECONDS); pump.request();
            check(pump.latest().isEmpty(), "Sin trabajador/publicación activa tras cerrar");
        }
    }

    public static void captureGuardRejectsEdt() throws Exception {
        try (var audit = new Audit()) {
            var fixture = onEdt(() -> new JLabel("Control positivo"));
            fixture.setText("Mutación deliberada del fixture fuera del EDT");
            check(!audit.violations.isEmpty(), "La auditoría debe detectar una violación deliberada");
            audit.violations.clear(); // No corresponde al código de producción; no contamina la auditoría siguiente.
        }
        try (var rig = new EngineRig()) {
            edt(() -> { try { UiSnapshotPump.capture(rig.c); throw new AssertionError("Captura global en EDT"); } catch (IllegalStateException expected) { } });
        }
    }

    public static void closeHandlerCallbacksStayOnEdt() throws Exception {
        try (var audit = new Audit(); var rig = new PanelRig(2, null, null, null)) {
            rig.prepare(); var finished = new CountDownLatch(1); var outside = new AtomicBoolean();
            edt(() -> { var handler = new WindowCloseHandler(rig.panel::closeAsync,
                    () -> outside.set(!SwingUtilities.isEventDispatchThread()),
                    () -> { outside.set(outside.get() || !SwingUtilities.isEventDispatchThread()); rig.panel.closed(); finished.countDown(); },
                    error -> { throw new AssertionError(error); }); handler.requestClose(); });
            check(finished.await(5, TimeUnit.SECONDS) && !outside.get(), "Callbacks de cierre en EDT"); audit.assertClean();
        }
    }

    public static void resetWhileImportingIgnoresOldUiResult() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1); var readOnEdt = new AtomicBoolean();
        try (var audit = new Audit(); var rig = new PanelRig(2, config -> EdtImportFixture.create(config, entered, release, readOnEdt), null, null)) {
            rig.prepare(); rig.largeFile(); rig.click(SystemControlPanel.Action.LOAD); check(entered.await(5, TimeUnit.SECONDS), "Importación activa");
            rig.click(SystemControlPanel.Action.RESET); release.countDown(); waitFor(() -> rig.controller().state() == SimulationState.RESET && rig.controller().epoch() == 1);
            pulses(4); check(rig.controller().preparedBatch().isEmpty(), "Lote de época antigua no revive"); audit.assertClean();
        } finally { release.countDown(); }
    }

    private static final class EngineRig implements AutoCloseable {
        final SimulationController c = new SimulationController(new SimulationConfig(2, 8, 80, 20), 0);
        @Override public void close() throws Exception { EdtSafetyScenarios.close(c); }
    }
    private static final class PanelRig implements AutoCloseable {
        final Path directory = Files.createTempDirectory("t52-");
        final AtomicReference<Optional<Path>> selected = new AtomicReference<>(Optional.empty());
        final SystemControlPanel panel;
        final int count;
        PanelRig(int count, SystemControlPanel.SessionOpener opener, Supplier<Optional<Path>> selector,
                Function<SimulationController, UiSnapshotPump> pumpFactory) throws Exception {
            this.count = count;
            panel = onEdt(() -> new SystemControlPanel(opener == null ? config -> new SimulationController(config, 0) : opener,
                    selector == null ? selected::get : selector, pumpFactory == null ? UiSnapshotPump::new : pumpFactory));
        }
        void confirm() throws Exception { edt(() -> {
            var form = panel.configurationPanel(); String[] values = {Integer.toString(count), "8", "120", "30"};
            for (var field : Field.values()) form.input(field).setText(values[field.ordinal()]); form.validateConfiguration();
        }); }
        void prepare() throws Exception { confirm(); waitFor(() -> controller() != null); }
        void start() throws Exception { click(SystemControlPanel.Action.START); waitFor(() -> controller().state() == SimulationState.RUNNING);
            waitFor(() -> onEdt(() -> { panel.updateView(); return panel.cabinDashboard().cabin(1).destinationButton(8).isEnabled(); })); }
        SimulationController controller() { return onEdt(() -> panel.controller().orElse(null)); }
        void click(SystemControlPanel.Action action) throws Exception { edt(() -> { panel.updateView(); panel.button(action).doClick(0); }); }
        void largeFile() throws Exception {
            Path file = directory.resolve("large.txt"); Files.writeString(file, (" ".repeat(80) + "CABIN 1 5\n").repeat(10000)); selected.set(Optional.of(file));
        }
        @Override public void close() throws Exception {
            onEdt(panel::closeAsync).get(8, TimeUnit.SECONDS); var c = controller(); if (c != null) check(c.awaitTermination(Duration.ofSeconds(5)), "Sesión terminada");
            edt(panel::closed);
            try (var files = Files.walk(directory)) { for (var path : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path); }
        }
    }
    private static final class Audit extends RepaintManager implements AutoCloseable {
        private final RepaintManager previous;
        private final ConcurrentLinkedQueue<String> violations = new ConcurrentLinkedQueue<>();
        Audit() throws Exception {
            previous = onEdt(() -> RepaintManager.currentManager((JComponent) null)); edt(() -> RepaintManager.setCurrentManager(this));
        }
        @Override public synchronized void addInvalidComponent(JComponent component) { record(component); super.addInvalidComponent(component); }
        @Override public void addDirtyRegion(JComponent component, int x, int y, int width, int height) { record(component); super.addDirtyRegion(component, x, y, width, height); }
        private void record(JComponent component) {
            if (!SwingUtilities.isEventDispatchThread() && violations.size() < 100) violations.add(component.getClass().getSimpleName() + " en " + Thread.currentThread().getName());
        }
        void assertClean() throws Exception { edt(() -> { }); check(violations.isEmpty(), "Mutaciones fuera EDT: " + violations); }
        @Override public void close() throws Exception { try { assertClean(); } finally { edt(() -> RepaintManager.setCurrentManager(previous)); } }
    }
    private static void watchDocuments(Component component, AtomicInteger mutations) {
        if (component instanceof JTextComponent text) text.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { mutations.incrementAndGet(); }
            public void removeUpdate(DocumentEvent e) { mutations.incrementAndGet(); }
            public void changedUpdate(DocumentEvent e) { mutations.incrementAndGet(); }
        });
        if (component instanceof Container container) for (var child : container.getComponents()) watchDocuments(child, mutations);
    }
    private static void pulses(int count) throws Exception {
        var ticks = new CountDownLatch(count); var worst = new AtomicLong(); var previous = new AtomicLong(System.nanoTime());
        var timer = onEdt(() -> { var value = new javax.swing.Timer(20, event -> {
            long now = System.nanoTime(); worst.accumulateAndGet(now - previous.getAndSet(now), Math::max); ticks.countDown();
        }); value.start(); return value; });
        try { check(ticks.await(5, TimeUnit.SECONDS), "El EDT no despachó pulsos durante operación retenida"); }
        finally { edt(timer::stop); }
        System.out.printf(java.util.Locale.ROOT, "T52_PULSE ticks=%d max_gap_ms=%.3f%n", count, worst.get() / 1_000_000.0);
    }
    private static void hold(CountDownLatch latch) {
        boolean interrupted = false;
        try { while (true) { try { latch.await(); return; } catch (InterruptedException error) { interrupted = true; } } }
        finally { if (interrupted) Thread.currentThread().interrupt(); }
    }
    private static void close(SimulationController c) throws Exception {
        c.closeAsync().get(5, TimeUnit.SECONDS); check(c.awaitTermination(Duration.ofSeconds(5)), "Motor cerrado");
    }
    private static void check(boolean condition, String text) { if (!condition) throw new AssertionError(text); }
    private static void waitFor(BooleanSupplier condition) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(7);
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
        gateAllowsFirstRefresh();
        gateLimitsRepeatedRefreshes();
        gateDoesNotCatchUpMissedTicks();
        gateHandlesNanoTimeWrap();
        pumpCapturesOutsideEdt();
        pumpCoalescesBusyRequests();
        pumpEnforcesMinimumCaptureInterval();
        latestFrameIsReplacedAfterReset();
        frameCollectionsAreDeeplyImmutable();
        captureExcludesServedHistory();
        dashboardRejectsFrameFromOldEpoch();
        dashboardRejectsFrameFromAnotherSession();
        captureFailureCanRecover();
        closedPumpCannotPublishLateFrame();
        closeWaitsForRealCaptureTerminationWithoutBlockingEdt();
        closeIsIdempotent();
        constantTimeSnapshotValidatesCabinId();
        dashboardCachesAtMostThreePanels();
        selectionRemainsConsistentAfterTabNavigation();
        evictedCabinRebuildsFromPublishedState();
        unchangedSnapshotDoesNotRewriteDocuments();
        repaintAuditAndPulsesDuringConcurrentMovement();
        largeRealImportLeavesEdtAvailable();
        closeDuringImportDoesNotFreezeEdt();
        slowResourceCloseLeavesEdtAvailable();
        preparationLeavesEdtAvailable();
        windowCloseWaitsForCaptureAndDropsLatePublication();
        resetAndLateReceiptsNeverMutateSwingOffEdt();
        panelCloseTerminatesSnapshotWorker();
        captureGuardRejectsEdt();
        closeHandlerCallbacksStayOnEdt();
        resetWhileImportingIgnoresOldUiResult();
        System.out.println("T52: 32 escenarios satisfactorios.");
    }
}
