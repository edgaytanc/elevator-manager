package com.elevatormanager.simulation;

import com.elevatormanager.dispatch.ManagerWorker;
import com.elevatormanager.logging.OwnedLogHandlers;
import com.elevatormanager.model.*;
import com.elevatormanager.model.request.CabinRequest;
import com.elevatormanager.ui.WindowCloseHandler;
import java.nio.file.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.logging.*;
import javax.swing.SwingUtilities;
import static com.elevatormanager.simulation.ExecutionEvent.Type.*;
import static com.elevatormanager.simulation.SimulationController.Outcome.*;

/** T39: cierre real, recursos y puente EDT sin depender de una pantalla gráfica. */
public final class OrderlyShutdownScenarios {
    private static final Duration LIMIT = Duration.ofSeconds(10);
    private OrderlyShutdownScenarios() { }

    public static void newSessionClosesAutomatically() throws Exception {
        var c = create(1, 5, 20, 10);
        check(get(c.closeAsync()).state() == SimulationState.CLOSED, "CLOSED sin usar awaitTermination para impulsarlo");
        ended(c);
    }

    public static void runningIdleSessionClosesAllWorkers() throws Exception {
        var c = create(4, 5, 20, 10); get(c.start()); get(c.closeAsync()); ended(c);
    }

    public static void movementDoesNotDelayCloseByItsDuration() throws Exception {
        var c = create(1, 5, 60_000, 10);
        try {
            get(c.start()); get(c.submit(cabin(5))); until(c, DEPARTED);
            get(c.closeAsync()); ended(c);
            check(c.snapshots().get(0).currentFloor() == 1, "No espera un minuto ni inventa una llegada");
        } finally { c.close(); c.awaitTermination(LIMIT); }
    }

    public static void dwellDoesNotDelayCloseByItsDuration() throws Exception {
        var c = create(1, 5, 10, 60_000);
        try { get(c.start()); get(c.submit(cabin(1))); until(c, COMMAND_COMPLETED); get(c.closeAsync()); ended(c); }
        finally { c.close(); c.awaitTermination(LIMIT); }
    }

    public static void stoppedSessionWakesAndCloses() throws Exception {
        var c = create(3, 5, 20, 10); get(c.start()); get(c.stop()); get(c.closeAsync()); ended(c);
    }

    public static void pendingStopIsResolvedByClose() throws Exception {
        var c = create(1, 5, 60_000, 10);
        try {
            get(c.start()); get(c.submit(cabin(5))); until(c, DEPARTED);
            var stop = c.stop(); var closed = c.closeAsync();
            check(get(stop).outcome() == REJECTED, "Stop pendiente no queda colgado"); get(closed); ended(c);
        } finally { c.close(); c.awaitTermination(LIMIT); }
    }

    public static void pendingResetIsResolvedByClose() throws Exception {
        var c = create(1, 5, 100, 10);
        try {
            get(c.start()); get(c.submit(cabin(4))); until(c, STOP_ENDED);
            var reset = c.reset(); until(c, RESET_STARTED); var closed = c.closeAsync();
            check(get(reset).outcome() == REJECTED, "Reset pendiente invalidado por cierre"); get(closed); ended(c);
        } finally { c.close(); c.awaitTermination(LIMIT); }
    }

    public static void resetReadySessionCloses() throws Exception {
        var c = create(3, 5, 20, 10); get(c.reset()); get(c.closeAsync()); ended(c);
    }

    public static void concurrentCloseRunsResourcesOnce() throws Exception {
        var c = create(2, 5, 20, 10); var calls = new AtomicInteger(); get(c.registerResource(calls::incrementAndGet)); get(c.start());
        var pool = Executors.newFixedThreadPool(8); var gate = new CountDownLatch(1);
        try {
            var futures = new ArrayList<Future<SimulationController.Result>>();
            for (int i = 0; i < 64; i++) { futures.add(pool.submit(() -> { gate.await(); return get(c.closeAsync()); })); }
            gate.countDown();
            for (var f : futures) { check(f.get(10, TimeUnit.SECONDS).state() == SimulationState.CLOSED, "Confirmación compartida"); }
            ended(c); check(calls.get() == 1, "Un único cierre del recurso");
        } finally { gate.countDown(); pool.shutdownNow(); c.close(); c.awaitTermination(LIMIT); }
    }

    public static void cancelledVisibleFutureDoesNotCancelShutdown() throws Exception {
        var c = create(1, 5, 20, 10); var resource = new BlockingResource(); get(c.registerResource(resource));
        var visible = c.closeAsync();
        try {
            resource.entered(); visible.cancel(false); var observer = c.closeAsync(); resource.release();
            check(get(observer).state() == SimulationState.CLOSED, "Cierre privado continúa"); ended(c);
        } finally { resource.release(); c.close(); c.awaitTermination(LIMIT); }
    }

    public static void resourcesCloseInReverseRegistrationOrder() throws Exception {
        var c = create(1, 1, 1, 1); var order = new CopyOnWriteArrayList<Integer>();
        get(c.registerResource(() -> order.add(1))); get(c.registerResource(() -> order.add(2))); get(c.registerResource(() -> order.add(3)));
        get(c.closeAsync()); ended(c); check(order.equals(List.of(3, 2, 1)), "Orden inverso de adquisición");
    }

    public static void duplicateResourceIsNotClosedTwice() throws Exception {
        var c = create(1, 1, 1, 1); var count = new AtomicInteger(); AutoCloseable resource = count::incrementAndGet;
        check(get(c.registerResource(resource)).outcome() == APPLIED, "Propiedad transferida");
        check(get(c.registerResource(resource)).outcome() == UNCHANGED, "Identidad duplicada");
        get(c.closeAsync()); ended(c); check(count.get() == 1, "Solo un cierre");
    }

    public static void rejectedResourceRemainsOwnedByCaller() throws Exception {
        var c = create(1, 1, 1, 1); get(c.closeAsync()); ended(c);
        var count = new AtomicInteger(); AutoCloseable resource = count::incrementAndGet;
        check(get(c.registerResource(resource)).outcome() == REJECTED && count.get() == 0, "No adopta después de cerrar");
        resource.close(); check(count.get() == 1, "Llamador conserva propiedad");
    }

    public static void resourcesAreReleasedAfterActorsTerminate() throws Exception {
        var c = create(2, 5, 60_000, 10); var checked = new AtomicBoolean();
        get(c.registerResource(() -> {
            check(c.managerLifecycle() == ManagerWorker.Lifecycle.TERMINATED, "Manejador terminó antes del recurso");
            check(c.elevatorLifecycles().stream().allMatch(l -> l == ElevatorWorker.Lifecycle.TERMINATED), "Cabinas terminaron antes del recurso");
            check(c.state() == SimulationState.CLOSING, "No publica CLOSED prematuramente"); checked.set(true);
        }));
        get(c.start()); get(c.submit(cabin(5))); until(c, DEPARTED); get(c.closeAsync()); ended(c); check(checked.get(), "Recurso verificado");
    }

    public static void blockedResourceDoesNotPublishClosed() throws Exception {
        var c = create(1, 1, 1, 1); var resource = new BlockingResource(); get(c.registerResource(resource)); var future = c.closeAsync();
        try {
            resource.entered(); check(c.state() == SimulationState.CLOSING && !future.isDone(), "Recursos pendientes no equivalen a CLOSED");
            check(!c.awaitTermination(Duration.ZERO), "Sondeo no afirma terminación");
        } finally { resource.release(); get(future); ended(c); }
    }

    public static void failingResourceDoesNotSkipRemainingResources() throws Exception {
        var c = create(1, 1, 1, 1); var order = new CopyOnWriteArrayList<Integer>();
        get(c.registerResource(() -> order.add(1)));
        get(c.registerResource(() -> { order.add(2); throw new IllegalStateException("fallo previsto"); }));
        get(c.registerResource(() -> order.add(3)));
        expectFailure(c.closeAsync());
        check(order.equals(List.of(3, 2, 1)), "Intenta recursos restantes");
        check(c.state() == SimulationState.CLOSING && c.failure().isPresent(), "Fallo visible sin falso CLOSED");
        check(!c.awaitTermination(Duration.ofMillis(10)), "No afirma éxito ante recurso fallido");
    }

    public static void severalResourceFailuresArePreserved() throws Exception {
        var c = create(1, 1, 1, 1);
        get(c.registerResource(() -> { throw new IllegalArgumentException("primero"); }));
        get(c.registerResource(() -> { throw new IllegalStateException("segundo"); }));
        Throwable error = expectFailure(c.closeAsync()); check(error.getSuppressed().length == 2, "Ambas causas conservadas");
    }

    public static void timeoutDoesNotCancelCleanup() throws Exception {
        var c = create(1, 1, 1, 1); var resource = new BlockingResource(); get(c.registerResource(resource)); var future = c.closeAsync();
        try {
            resource.entered(); check(!c.awaitTermination(Duration.ofMillis(30)), "Plazo corto agotado");
            check(!future.isDone() && c.state() == SimulationState.CLOSING, "Timeout no cancela ni publica CLOSED");
        } finally { resource.release(); get(future); ended(c); }
    }

    public static void positiveWaitIsRejectedOnEdt() throws Exception {
        var c = create(1, 1, 1, 1);
        try {
            SwingUtilities.invokeAndWait(() -> {
                try { c.awaitTermination(Duration.ofMillis(1)); throw new AssertionError("Espera en EDT"); }
                catch (IllegalStateException expected) { }
                catch (InterruptedException error) { throw new AssertionError(error); }
                try { check(!c.awaitTermination(Duration.ZERO), "Sondeo permitido"); }
                catch (InterruptedException error) { throw new AssertionError(error); }
            });
        } finally { get(c.closeAsync()); ended(c); }
    }

    public static void resourceCloseRunsOutsideEdt() throws Exception {
        var c = create(1, 1, 1, 1); var thread = new AtomicReference<Thread>(); get(c.registerResource(() -> thread.set(Thread.currentThread())));
        var future = new AtomicReference<CompletableFuture<SimulationController.Result>>();
        SwingUtilities.invokeAndWait(() -> future.set(c.closeAsync())); get(future.get()); ended(c);
        check(thread.get() != null && thread.get().getName().equals("simulation-shutdown"), "Liberación en cerrador independiente");
    }

    public static void cleanupSelfWaitIsRejected() throws Exception {
        var c = create(1, 1, 1, 1); var detected = new AtomicBoolean();
        get(c.registerResource(() -> {
            try { c.awaitTermination(Duration.ofMillis(1)); throw new AssertionError("Autoespera"); }
            catch (IllegalStateException expected) { detected.set(true); }
        }));
        get(c.closeAsync()); ended(c); check(detected.get(), "No puede esperar su propio cierre");
    }

    public static void normalActionsAreRejectedAsSoonAsCloseIsRequested() throws Exception {
        var c = create(1, 5, 20, 10); var resource = new BlockingResource(); get(c.registerResource(resource)); get(c.start());
        var closing = c.closeAsync();
        try {
            check(get(c.start()).outcome() == REJECTED && get(c.stop()).outcome() == REJECTED, "Controles rechazados");
            check(get(c.reset()).outcome() == REJECTED && get(c.submit(cabin(2))).outcome() == REJECTED, "Sin trabajo nuevo");
            check(get(c.registerResource(() -> { throw new AssertionError("No debía adoptar"); })).outcome() == REJECTED, "Sin recurso nuevo");
        } finally { resource.release(); get(closing); ended(c); }
    }

    public static void invalidResourceAndTimeoutArgumentsAreRejected() throws Exception {
        var c = create(1, 1, 1, 1);
        try {
            try { c.registerResource(null); throw new AssertionError("null"); } catch (NullPointerException expected) { }
            try { c.awaitTermination(Duration.ofNanos(-1)); throw new AssertionError("negativo"); } catch (IllegalArgumentException expected) { }
            try { c.awaitTermination(null); throw new AssertionError("timeout null"); } catch (NullPointerException expected) { }
        } finally { get(c.closeAsync()); ended(c); }
    }

    public static void visualCallbacksRunOnlyOnEdt() throws Exception {
        var stage = new CompletableFuture<Void>(); var requests = new AtomicInteger(); var disposed = new AtomicInteger(); var closed = new CountDownLatch(1);
        var handler = new WindowCloseHandler(() -> { requests.incrementAndGet(); return stage; },
                () -> check(SwingUtilities.isEventDispatchThread(), "Inicio en EDT"),
                () -> { check(SwingUtilities.isEventDispatchThread(), "Fin en EDT"); disposed.incrementAndGet(); closed.countDown(); },
                error -> { throw new AssertionError(error); });
        SwingUtilities.invokeAndWait(() -> { handler.requestClose(); handler.requestClose(); check(handler.isClosing() && !handler.isClosed(), "Cierre en curso"); });
        check(disposed.get() == 0 && requests.get() == 1, "Botón/X repetidos comparten solicitud");
        stage.complete(null); check(closed.await(10, TimeUnit.SECONDS), "Confirmación visual");
        SwingUtilities.invokeAndWait(() -> { check(handler.isClosed() && !handler.isClosing(), "Estado visual final"); handler.requestClose(); });
        check(disposed.get() == 1 && requests.get() == 1, "Una disposición");
    }

    public static void visualCloseWaitsForActualResourceRelease() throws Exception {
        var c = create(2, 5, 20, 10); var resource = new BlockingResource(); get(c.registerResource(resource)); get(c.start());
        var disposed = new CountDownLatch(1); var handler = new WindowCloseHandler(c::closeAsync, () -> { }, disposed::countDown,
                error -> { throw new AssertionError(error); });
        try {
            SwingUtilities.invokeAndWait(handler::requestClose); resource.entered();
            var pulse = new CountDownLatch(1); SwingUtilities.invokeLater(pulse::countDown);
            check(pulse.await(2, TimeUnit.SECONDS) && disposed.getCount() == 1, "EDT responde sin disponer antes de tiempo");
            resource.release(); check(disposed.await(10, TimeUnit.SECONDS), "Dispone tras liberación"); ended(c);
        } finally { resource.release(); c.close(); c.awaitTermination(LIMIT); }
    }

    public static void visualFailureDoesNotDisposeAndCanBeReportedAgain() throws Exception {
        var requests = new AtomicInteger(); var disposed = new CountDownLatch(1); var failed = new CountDownLatch(1);
        var handler = new WindowCloseHandler(() -> requests.incrementAndGet() == 1
                ? CompletableFuture.failedFuture(new IllegalStateException("fallo previsto")) : CompletableFuture.completedFuture(null),
                () -> { }, disposed::countDown, error -> { check(SwingUtilities.isEventDispatchThread(), "Error en EDT"); failed.countDown(); });
        SwingUtilities.invokeAndWait(handler::requestClose); check(failed.await(10, TimeUnit.SECONDS) && disposed.getCount() == 1, "No dispone ante fallo");
        SwingUtilities.invokeAndWait(() -> { check(!handler.isClosed() && !handler.isClosing(), "Error reportado"); handler.requestClose(); });
        check(disposed.await(10, TimeUnit.SECONDS), "Otro proveedor puede completar un nuevo intento");
    }

    public static void visualHandlerRequiresEdtAndValidCallbacks() {
        var handler = new WindowCloseHandler(() -> CompletableFuture.completedFuture(null), () -> { }, () -> { }, error -> { });
        try { handler.requestClose(); throw new AssertionError("Fuera de EDT"); } catch (IllegalStateException expected) { }
        try { handler.isClosed(); throw new AssertionError("Lectura fuera de EDT"); } catch (IllegalStateException expected) { }
        try { new WindowCloseHandler(null, () -> { }, () -> { }, error -> { }); throw new AssertionError("null"); }
        catch (NullPointerException expected) { }
    }

    public static void ownedHandlersLeaveForeignHandlersUntouched() {
        Logger logger = Logger.getAnonymousLogger(); logger.setUseParentHandlers(false);
        var ownA = new CountingHandler(false); var ownB = new CountingHandler(false); var foreign = new CountingHandler(false);
        logger.addHandler(ownA); logger.addHandler(foreign); logger.addHandler(ownB);
        var owner = new OwnedLogHandlers(logger, List.of(ownA, ownB)); owner.close(); owner.close();
        check(ownA.closed.get() == 1 && ownB.closed.get() == 1 && ownA.flushed.get() == 1, "Handlers propios cerrados una vez");
        check(foreign.closed.get() == 0 && Arrays.asList(logger.getHandlers()).equals(List.of(foreign)), "Handler ajeno intacto");
        logger.removeHandler(foreign); foreign.close();
    }

    public static void failingHandlerStillReleasesOtherHandlers() {
        Logger logger = Logger.getAnonymousLogger(); var good = new CountingHandler(false); var bad = new CountingHandler(true);
        logger.addHandler(good); logger.addHandler(bad); var owner = new OwnedLogHandlers(logger, List.of(good, bad));
        try { owner.close(); throw new AssertionError("Debía informar fallos"); }
        catch (IllegalStateException error) { check(error.getSuppressed().length == 2, "Conserva fallo de flush y close"); }
        check(good.closed.get() == 1 && bad.closed.get() == 1 && logger.getHandlers().length == 0, "Intenta todos y desconecta");
        try { owner.close(); throw new AssertionError("Fallo conservado"); } catch (IllegalStateException expected) { }
        check(good.closed.get() == 1 && bad.closed.get() == 1, "No repite cierre fallido");
    }

    public static void realFileHandlerIsReleasedAndReopened() throws Exception {
        Path directory = Files.createTempDirectory("elevator-t39-"); Path log = directory.resolve("session.log");
        Logger logger = Logger.getAnonymousLogger(); logger.setUseParentHandlers(false);
        FileHandler file = new FileHandler(log.toString(), false); logger.addHandler(file);
        var c = create(1, 1, 1, 1);
        try {
            get(c.registerResource(new OwnedLogHandlers(logger, List.of(file)))); logger.info("T39_PRUEBA_ARCHIVO");
            get(c.closeAsync()); ended(c);
            check(logger.getHandlers().length == 0 && Files.exists(log), "Archivo creado y handler retirado");
            check(!Files.exists(directory.resolve("session.log.lck")), "Bloqueo FileHandler liberado");
            Path moved = directory.resolve("renamed.log"); Files.move(log, moved); Files.move(moved, log);
            FileHandler reopened = new FileHandler(log.toString(), true);
            reopened.close();
            check(!Files.exists(directory.resolve("session.log.1")), "Reabre mismo archivo sin generar otro por bloqueo");
        } finally {
            file.close(); logger.removeHandler(file); c.close(); c.awaitTermination(LIMIT);
            try (var children = Files.list(directory)) { for (var child : children.toList()) { Files.deleteIfExists(child); } }
            Files.deleteIfExists(directory);
        }
    }

    public static void handlerOwnershipArgumentsAreValidated() {
        Logger logger = Logger.getAnonymousLogger(); var handler = new CountingHandler(false);
        try { new OwnedLogHandlers(null, List.of()); throw new AssertionError("logger null"); } catch (NullPointerException expected) { }
        try { new OwnedLogHandlers(logger, null); throw new AssertionError("lista null"); } catch (NullPointerException expected) { }
        try { new OwnedLogHandlers(logger, List.of(handler, handler)); throw new AssertionError("identidad repetida"); } catch (IllegalArgumentException expected) { }
        check(handler.closed.get() == 0, "Validación sin adoptar ni cerrar");
    }

    public static void successfulTerminationLeavesNoOwnedThreadAlive() throws Exception {
        var c = create(2, 5, 20, 10); var closer = new AtomicReference<Thread>();
        get(c.registerResource(() -> closer.set(Thread.currentThread()))); get(c.start());
        var actors = Thread.getAllStackTraces().keySet().stream().filter(t -> t.getName().equals("simulation-controller")
                || t.getName().startsWith("elevator-worker-") || t.getName().startsWith("manager-worker-")).toList();
        get(c.closeAsync()); ended(c);
        check(actors.stream().noneMatch(Thread::isAlive) && !closer.get().isAlive(), "Coordinador, actores y cerrador terminaron realmente");
    }

    private static final class CountingHandler extends Handler {
        final AtomicInteger closed = new AtomicInteger(); final AtomicInteger flushed = new AtomicInteger(); final boolean failing;
        CountingHandler(boolean failing) { this.failing = failing; }
        @Override public void publish(LogRecord record) { }
        @Override public void flush() { flushed.incrementAndGet(); if (failing) { throw new IllegalStateException("flush previsto"); } }
        @Override public void close() { closed.incrementAndGet(); if (failing) { throw new IllegalStateException("close previsto"); } }
    }
    private static final class BlockingResource implements AutoCloseable {
        private final CountDownLatch entered = new CountDownLatch(1); private final CountDownLatch release = new CountDownLatch(1);
        @Override public void close() throws InterruptedException { entered.countDown(); check(release.await(10, TimeUnit.SECONDS), "Recurso de prueba liberado"); }
        void entered() throws InterruptedException { check(entered.await(10, TimeUnit.SECONDS), "Cerrador entró al recurso"); }
        void release() { release.countDown(); }
    }
    private static SimulationController create(int n, int floors, long move, long dwell) { return new SimulationController(new SimulationConfig(n, floors, move, dwell), 0); }
    private static CabinRequest cabin(int floor) { return new CabinRequest(UUID.randomUUID(), 0, floor, Instant.now(), 1); }
    private static SimulationController.Result get(CompletableFuture<SimulationController.Result> future) throws Exception { return future.get(10, TimeUnit.SECONDS); }
    private static Throwable expectFailure(CompletableFuture<?> future) throws Exception {
        try { future.get(10, TimeUnit.SECONDS); throw new AssertionError("Debía fallar"); }
        catch (ExecutionException error) { return error.getCause(); }
    }
    private static void until(SimulationController c, ExecutionEvent.Type type) throws Exception {
        long began = System.nanoTime();
        while (true) {
            long left = LIMIT.toNanos() - (System.nanoTime() - began); check(left > 0, "Plazo de evento");
            var event = c.awaitEvent(Duration.ofNanos(left)).orElseThrow(); check(event.type() != WORKER_FAILED, "Cabina sana");
            if (event.type() == type) { return; }
        }
    }
    private static void ended(SimulationController c) throws Exception {
        check(c.awaitTermination(LIMIT), "Terminación real confirmada");
        check(c.state() == SimulationState.CLOSED && c.managerLifecycle() == ManagerWorker.Lifecycle.TERMINATED, "CLOSED con manejador terminado");
        check(c.elevatorLifecycles().stream().allMatch(l -> l == ElevatorWorker.Lifecycle.TERMINATED), "Cabinas terminadas");
        check(c.failure().isEmpty(), "Sin incidencia de cierre: " + c.failure());
    }
    private static void check(boolean value, String detail) { if (!value) { throw new AssertionError(detail); } }

    public static void main(String[] args) throws Exception {
        newSessionClosesAutomatically(); runningIdleSessionClosesAllWorkers(); movementDoesNotDelayCloseByItsDuration();
        dwellDoesNotDelayCloseByItsDuration(); stoppedSessionWakesAndCloses(); pendingStopIsResolvedByClose();
        pendingResetIsResolvedByClose(); resetReadySessionCloses(); concurrentCloseRunsResourcesOnce();
        cancelledVisibleFutureDoesNotCancelShutdown(); resourcesCloseInReverseRegistrationOrder(); duplicateResourceIsNotClosedTwice();
        rejectedResourceRemainsOwnedByCaller(); resourcesAreReleasedAfterActorsTerminate(); blockedResourceDoesNotPublishClosed();
        failingResourceDoesNotSkipRemainingResources(); severalResourceFailuresArePreserved(); timeoutDoesNotCancelCleanup();
        positiveWaitIsRejectedOnEdt(); resourceCloseRunsOutsideEdt(); cleanupSelfWaitIsRejected();
        normalActionsAreRejectedAsSoonAsCloseIsRequested(); invalidResourceAndTimeoutArgumentsAreRejected(); visualCallbacksRunOnlyOnEdt();
        visualCloseWaitsForActualResourceRelease(); visualFailureDoesNotDisposeAndCanBeReportedAgain(); visualHandlerRequiresEdtAndValidCallbacks();
        ownedHandlersLeaveForeignHandlersUntouched(); failingHandlerStillReleasesOtherHandlers(); realFileHandlerIsReleasedAndReopened();
        handlerOwnershipArgumentsAreValidated(); successfulTerminationLeavesNoOwnedThreadAlive();
        System.out.println("T39: 32 escenarios satisfactorios.");
    }
}
