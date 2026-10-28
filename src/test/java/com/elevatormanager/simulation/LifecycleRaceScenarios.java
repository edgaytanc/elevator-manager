package com.elevatormanager.simulation;

import com.elevatormanager.dispatch.ManagerWorker;
import com.elevatormanager.model.*;
import com.elevatormanager.model.request.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.function.Supplier;
import javax.swing.SwingUtilities;
import static com.elevatormanager.simulation.ExecutionEvent.Type.*;
import static com.elevatormanager.simulation.SimulationController.Outcome.*;

/** T40: carreras reales, barreras observables y plazos comunes; sin Thread.sleep. */
public final class LifecycleRaceScenarios {
    private static final Duration LIMIT = Duration.ofSeconds(10);
    private LifecycleRaceScenarios() { }

    public static void resetDuringMovementCancelsOldRoute() throws Exception {
        try (var r = new Rig(1, 6, 80, 10)) {
            get(r.c.start()); var old = cabin(1, 6, 0); get(r.c.submit(old)); r.until(e -> e.type() == DEPARTED);
            get(r.c.reset()); ready(r.c); r.drain();
            check(r.trace.stream().noneMatch(e -> e.type() == COMMAND_COMPLETED
                    && e.canonicalRequestId().filter(old.getId()::equals).isPresent()), "La ruta vieja no se sirve durante el retorno");
            freshService(r, 1);
        }
    }

    public static void resetDuringSeveralCabinJourneysIsGlobal() throws Exception {
        try (var r = new Rig(4, 6, 80, 10)) {
            get(r.c.start());
            for (int id = 1; id <= 4; id++) { get(r.c.submit(cabin(id, 6, 0))); }
            var departed = new HashSet<Integer>();
            while (departed.size() < 4) { departed.add(r.until(e -> e.type() == DEPARTED).elevatorId()); }
            get(r.c.reset()); ready(r.c); r.drain();
            check(r.count(RESET_COMPLETED, 1) == 4, "Confirman las cuatro cabinas en la nueva época");
            sameActors(r);
        }
    }

    public static void resetDuringDwellDoesNotWaitForDwell() throws Exception {
        try (var r = new Rig(1, 3, 10, 60_000)) {
            get(r.c.start()); get(r.c.submit(cabin(1, 2, 0))); r.until(e -> e.type() == COMMAND_COMPLETED);
            get(r.c.reset()); ready(r.c); r.drain();
            check(r.trace.stream().filter(e -> e.epoch() == 1).noneMatch(e -> e.type() == STOP_STARTED
                    || e.type() == COMMAND_COMPLETED), "Retorno sin parada de servicio ni espera de 60 s");
        }
    }

    public static void resetWhileDwellIsStoppedClearsHold() throws Exception {
        try (var r = new Rig(2, 3, 10, 60_000)) {
            get(r.c.start()); get(r.c.submit(cabin(1, 2, 0))); r.until(e -> e.type() == COMMAND_COMPLETED);
            get(r.c.stop()); check(r.c.snapshots().get(0).phase() == ElevatorPhase.HELD, "Parada congelada observada");
            get(r.c.reset()); ready(r.c); freshService(r, 2);
        }
    }

    public static void resetFromStoppedDiscardsPreservedDestination() throws Exception {
        try (var r = new Rig(1, 6, 100, 10)) {
            get(r.c.start()); var old = cabin(1, 6, 0); get(r.c.submit(old)); r.until(e -> e.type() == DEPARTED);
            get(r.c.stop());
            check(r.c.snapshots().get(0).cabinRequests().contains(old), "Stop conservó el destino antes de reset");
            get(r.c.reset()); ready(r.c); freshService(r, 1);
            check(r.trace.stream().noneMatch(e -> e.type() == COMMAND_COMPLETED
                    && e.canonicalRequestId().filter(old.getId()::equals).isPresent()), "Start no restaura el destino cancelado");
        }
    }

    public static void concurrentInitialStartCreatesOneSetOfActors() throws Exception {
        try (var r = new Rig(4, 2, 10, 10)) {
            var results = race(Collections.nCopies(8, r.c::start)); r.capture();
            check(results.stream().filter(v -> v.outcome() == APPLIED).count() == 1, "Un solo Start inicial efectivo");
            check(results.stream().allMatch(v -> v.state() == SimulationState.RUNNING
                    && (v.outcome() == APPLIED || v.outcome() == UNCHANGED)), "Los restantes Start son idempotentes");
            sameActors(r);
        }
    }

    public static void concurrentStartFromStoppedResumesOnce() throws Exception {
        try (var r = new Rig(3, 2, 10, 10)) {
            get(r.c.start()); r.capture(); var original = r.actors(); get(r.c.stop());
            var results = race(Collections.nCopies(8, r.c::start));
            check(results.stream().filter(v -> v.outcome() == APPLIED).count() == 1, "Una continuación efectiva");
            check(r.actors().equals(original), "Continúan las mismas referencias Thread"); sameActors(r);
        }
    }

    public static void concurrentStopWhenIdleHasOnePhysicalBarrier() throws Exception {
        try (var r = new Rig(4, 2, 10, 10)) {
            get(r.c.start()); race(Collections.nCopies(8, r.c::stop)); r.drain();
            check(r.c.state() == SimulationState.STOPPED, "Todos los Stop terminan");
            check(r.count(CONTROL_STOPPED, 0) == 4, "Una confirmación por cabina, sin barreras duplicadas");
            check(get(r.c.stop()).outcome() == UNCHANGED, "Stop posterior no cambia el estado");
        }
    }

    public static void concurrentStopDuringMovementPreservesRoute() throws Exception {
        try (var r = new Rig(1, 6, 100, 10)) {
            get(r.c.start()); var request = cabin(1, 6, 0); get(r.c.submit(request)); r.until(e -> e.type() == DEPARTED);
            var results = race(Collections.nCopies(8, r.c::stop)); r.drain();
            check(results.stream().allMatch(v -> v.state() == SimulationState.STOPPED), "Barrera física confirmada");
            check(r.count(CONTROL_STOPPED, 0) == 1 && r.c.snapshots().get(0).cabinRequests().contains(request), "Ruta conservada sin multiplicar Stop");
            get(r.c.start()); r.until(e -> e.type() == COMMAND_COMPLETED
                    && e.canonicalRequestId().filter(request.getId()::equals).isPresent());
        }
    }

    public static void startAndStopRaceHasAValidSerializableOutcome() throws Exception {
        try (var r = new Rig(2, 1, 1, 1)) {
            get(r.c.start());
            for (int i = 0; i < 40; i++) {
                var results = race(List.of(r.c::start, r.c::stop));
                check(results.get(1).state() == SimulationState.STOPPED, "Stop siempre confirma su barrera");
                var start = results.get(0);
                check((start.outcome() == APPLIED && start.state() == SimulationState.RUNNING)
                        || (start.outcome() == UNCHANGED && start.state() == SimulationState.RUNNING)
                        || (start.outcome() == REJECTED && start.state() == SimulationState.STOPPING), "Start concuerda con el estado que observó");
                check(r.c.state() == (start.outcome() == APPLIED ? SimulationState.RUNNING : SimulationState.STOPPED), "Estado final compatible con el orden efectivo");
                get(r.c.stop()); check(r.c.state() == SimulationState.STOPPED, "Normalización tras la carrera");
                get(r.c.start());
            }
        }
    }

    public static void stopAndResetRaceCannotRestoreStoppedEpoch() throws Exception {
        try (var r = new Rig(2, 3, 10, 10)) {
            get(r.c.start());
            for (int i = 1; i <= 30; i++) {
                var results = race(List.of(r.c::stop, r.c::reset));
                check(results.get(1).outcome() == APPLIED && results.get(1).epoch() == i, "Reset efectivo y época exacta");
                var stop = results.get(0);
                check((stop.outcome() == APPLIED && stop.state() == SimulationState.STOPPED && stop.epoch() == i - 1)
                        || (stop.outcome() == REJECTED && stop.state() == SimulationState.RESETTING && stop.epoch() == i)
                        || (stop.outcome() == UNCHANGED && stop.state() == SimulationState.RESET && stop.epoch() == i), "Stop confirmado antes, sustituido o posterior al reset");
                ready(r.c); check(r.c.failure().isEmpty(), "Un ack Stop tardío no sustituye RESET"); get(r.c.start());
            }
        }
    }

    public static void startAndResetRaceCannotStartOldWork() throws Exception {
        try (var r = new Rig(2, 3, 10, 10)) {
            get(r.c.start()); get(r.c.stop());
            var results = race(List.of(r.c::start, r.c::reset));
            check(results.get(1).outcome() == APPLIED && r.c.epoch() == 1, "Un reset confirmado");
            check(r.c.state() == SimulationState.RESET || r.c.state() == SimulationState.RUNNING, "Start puede anteceder o seguir al retorno completo");
            check(r.c.calls().isEmpty() && r.c.snapshots().stream().allMatch(s -> s.epoch() == 1
                    && s.currentFloor() == 1 && s.cabinRequests().isEmpty()), "Sin trabajo antiguo tras ambos resultados");
            get(r.c.stop()); get(r.c.start()); sameActors(r);
        }
    }

    public static void cabinAdmissionRacingResetDoesNotSurviveIt() throws Exception {
        admissionResetRace(false);
    }

    public static void hallAdmissionRacingResetDoesNotSurviveIt() throws Exception {
        admissionResetRace(true);
    }

    private static void admissionResetRace(boolean hall) throws Exception {
        try (var r = new Rig(2, 4, 10, 10)) {
            get(r.c.start());
            for (int i = 0; i < 20; i++) {
                long oldEpoch = r.c.epoch(); Request old = hall ? hall(3, oldEpoch) : cabin(1, 4, oldEpoch);
                var results = race(List.of(() -> r.c.submit(old), r.c::reset)); ready(r.c);
                check(results.get(0).outcome() == ENQUEUED || results.get(0).outcome() == REJECTED, "Admisión anterior o posterior a invalidación");
                check(get(r.c.start()).state() == SimulationState.RUNNING, "Nueva ejecución disponible");
                check(get(r.c.submit(old)).outcome() == REJECTED, "Mensaje de época vieja no reingresa");
                check(r.c.calls().isEmpty() && r.c.snapshots().stream().allMatch(s -> s.cabinRequests().isEmpty()
                        && s.hallRequests().isEmpty()), "Sin seguimiento ni agenda antigua tras la carrera");
            }
            freshService(r, 2);
        }
    }

    public static void closeWhileResettingResolvesEveryRacingControl() throws Exception {
        try (var r = movingSlowly()) {
            var pending = r.c.reset(); r.until(e -> e.type() == RESET_STARTED);
            var results = race(List.of(r.c::closeAsync, r.c::reset, r.c::start, r.c::stop));
            check(get(pending).outcome() == REJECTED, "Reset físico pendiente invalidado por cierre");
            check(results.get(0).state() == SimulationState.CLOSED, "Cierre confirmado frente a todos los controles");
            check(results.subList(1, results.size()).stream().allMatch(v -> v.outcome() == REJECTED), "No queda control pendiente ni confirma RESET");
        }
    }

    public static void closeWhileStoppingResolvesEveryRacingControl() throws Exception {
        try (var r = movingSlowly()) {
            var pending = r.c.stop();
            var results = race(List.of(r.c::closeAsync, r.c::stop, r.c::start));
            check(get(pending).outcome() == REJECTED, "Stop no espera el tramo de 60 s al cerrar");
            check(results.get(0).state() == SimulationState.CLOSED && results.get(1).outcome() == REJECTED
                    && results.get(2).outcome() == REJECTED, "Cierre domina la barrera en vuelo");
        }
    }

    public static void initialStartRacingCloseCannotResurrectSession() throws Exception {
        for (int i = 0; i < 20; i++) {
            try (var r = new Rig(3, 2, 10, 10)) {
                var results = race(List.of(r.c::start, r.c::closeAsync));
                check(results.get(0).outcome() == APPLIED || results.get(0).outcome() == REJECTED, "Start ejecutado antes del cierre o descartado");
                check(results.get(1).state() == SimulationState.CLOSED, "Cierre inicial confirmado");
                check(get(r.c.start()).outcome() == REJECTED && r.c.state() == SimulationState.CLOSED, "Sin resurrección tardía");
            }
        }
    }

    public static void admissionsRacingCloseAreResolvedWithoutServiceClaim() throws Exception {
        try (var r = new Rig(2, 4, 60_000, 60_000)) {
            get(r.c.start());
            var results = race(List.of(() -> r.c.submit(cabin(1, 4, 0)), () -> r.c.submit(hall(3, 0)), r.c::closeAsync));
            check(results.subList(0, 2).stream().allMatch(v -> v.outcome() == ENQUEUED || v.outcome() == REJECTED), "Admisión no se confunde con servicio");
            check(results.get(2).state() == SimulationState.CLOSED, "Cierre también resuelve productores concurrentes");
            r.drain(); check(r.trace.stream().noneMatch(e -> e.type() == COMMAND_COMPLETED), "No inventa atención al cerrar");
        }
    }

    public static void closingGateRejectsAllControlsUntilResourceRelease() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        try (var r = new Rig(2, 2, 10, 10)) {
            get(r.c.start()); get(r.c.registerResource(() -> {
                entered.countDown(); check(release.await(10, TimeUnit.SECONDS), "Recurso liberado dentro del plazo");
            }));
            try {
                var closing = r.c.closeAsync(); check(entered.await(10, TimeUnit.SECONDS), "Cerrador alcanzó el recurso");
                check(r.c.state() == SimulationState.CLOSING && !closing.isDone(), "No publica CLOSED prematuro");
                var results = race(List.of(r.c::start, r.c::stop, r.c::reset, () -> r.c.submit(cabin(1, 2, 0))));
                check(results.stream().allMatch(v -> v.outcome() == REJECTED), "Todos los controles rechazados durante limpieza");
                release.countDown(); check(get(closing).state() == SimulationState.CLOSED, "Publica CLOSED tras liberar");
            } finally { release.countDown(); }
        }
    }

    public static void delayedEdtMessagesAreRevalidatedBeforeApplication() throws Exception {
        try (var r = new Rig(1, 3, 20, 10)) {
            get(r.c.start()); var response = get(r.c.submit(cabin(1, 3, 0)));
            var event = r.until(e -> e.type() == DEPARTED);
            var release = new CompletableFuture<Void>(); var applied = new AtomicInteger(); var discarded = new AtomicInteger();
            var delivered = release.thenRunAsync(() -> {
                check(SwingUtilities.isEventDispatchThread(), "Entrega diferida en EDT real");
                if (r.c.isCurrent(response)) { applied.incrementAndGet(); } else { discarded.incrementAndGet(); }
                if (r.c.isCurrent(event)) { applied.incrementAndGet(); } else { discarded.incrementAndGet(); }
            }, SwingUtilities::invokeLater);
            get(r.c.reset()); ready(r.c); release.complete(null); delivered.get(10, TimeUnit.SECONDS);
            check(applied.get() == 0 && discarded.get() == 2, "Ni respuesta ni evento extraído alteran la nueva época");
            get(r.c.start()); get(r.c.submit(cabin(1, 2, 1))); var fresh = r.until(e -> e.type() == COMMAND_COMPLETED);
            SwingUtilities.invokeAndWait(() -> { if (r.c.isCurrent(fresh)) { applied.incrementAndGet(); } });
            check(applied.get() == 1, "Un mensaje vigente sí se aplica");
        }
    }

    public static void bufferedHistoryAcrossEpochsDoesNotCompleteFreshWork() throws Exception {
        try (var r = new Rig(1, 3, 10, 60_000)) {
            get(r.c.start());
            for (int epoch = 0; epoch < 6; epoch++) {
                for (int i = 0; i < 80; i++) { get(r.c.submit(cabin(1, 1, epoch))); }
                get(r.c.reset()); get(r.c.start());
            }
            var fresh = cabin(1, 2, 6); var response = get(r.c.submit(fresh));
            var completed = r.until(e -> e.type() == COMMAND_COMPLETED);
            check(completed.epoch() == 6 && completed.canonicalRequestId().orElseThrow().equals(fresh.getId()), "El historial no confirma una identidad nueva");
            check(completed.command().orElseThrow().commandId() == response.command().orElseThrow().commandId(), "Confirmación correlacionada con el comando nuevo");
            check(r.trace.stream().allMatch(e -> e.epoch() == 6 && r.c.isCurrent(e)), "La vista actual excluye seis historiales anteriores");
        }
    }

    public static void cancelledStopObserverDoesNotCancelResetOrClose() throws Exception {
        try (var r = movingSlowly()) {
            var observer = r.c.stop(); observer.cancel(false);
            var reset = r.c.reset(); r.until(e -> e.type() == RESET_STARTED);
            var closing = r.c.closeAsync(); closing.cancel(false);
            check(get(reset).outcome() == REJECTED && get(r.c.closeAsync()).state() == SimulationState.CLOSED, "Observadores cancelados no cancelan controles privados");
        }
    }

    public static void repeatedMixedControlsKeepExactThreadIdentities() throws Exception {
        try (var r = new Rig(3, 1, 1, 1)) {
            get(r.c.start()); r.capture(); var original = r.actors();
            for (int i = 0; i < 100; i++) {
                race(List.of(r.c::stop, r.c::start, r.c::reset));
                get(r.c.stop()); get(r.c.start());
                check(r.actors().equals(original), "Sin crear ni sustituir actores en ciclo " + i);
                check(r.c.failure().isEmpty(), "Sin fallos de concurrencia en ciclo " + i);
            }
            check(r.c.epoch() == 100, "Una nueva época por cada reset mezclado"); sameActors(r);
        }
    }

    public static void repeatedSessionsDoNotAccumulateOwnedThreads() throws Exception {
        var captured = new HashSet<Thread>();
        for (int i = 0; i < 20; i++) {
            try (var r = new Rig(2, 1, 1, 1)) {
                get(r.c.start()); r.capture(); captured.addAll(r.owned);
                race(List.of(r.c::stop, r.c::reset, r.c::closeAsync));
            }
            check(captured.stream().noneMatch(Thread::isAlive), "Sin hilos vivos de sesiones anteriores " + i);
        }
    }

    private static Rig movingSlowly() throws Exception {
        var r = new Rig(2, 4, 60_000, 60_000);
        try {
            get(r.c.start()); get(r.c.submit(cabin(1, 4, 0))); r.until(e -> e.type() == DEPARTED); return r;
        } catch (Exception | Error error) { r.close(); throw error; }
    }

    private static void freshService(Rig r, int id) throws Exception {
        get(r.c.start()); var fresh = cabin(id, 2, r.c.epoch());
        check(get(r.c.submit(fresh)).outcome() == ENQUEUED, "La nueva época admite servicio");
        r.until(e -> e.type() == COMMAND_COMPLETED && e.canonicalRequestId().filter(fresh.getId()::equals).isPresent());
    }

    private static void ready(SimulationController c) {
        check(c.state() == SimulationState.RESET && c.calls().isEmpty(), "Barrera global y registro vacío");
        check(c.snapshots().stream().allMatch(s -> s.epoch() == c.epoch() && s.currentFloor() == 1
                && s.direction() == Direction.UP && s.phase() == ElevatorPhase.IDLE
                && s.mode() == ElevatorSnapshot.Mode.NORMAL && s.nextFloor().isEmpty()
                && s.committedFloor().isEmpty() && s.holdReason().isEmpty()
                && s.cabinRequests().isEmpty() && s.hallRequests().isEmpty()), "Todas las cabinas listas en 1/UP sin pendientes");
    }

    private static void sameActors(Rig r) {
        r.capture(); var actors = r.actors();
        check(actors.size() == r.c.config().elevatorCount() + 1, "Exactamente N cabinas y un manejador por identidad");
        check(actors.stream().allMatch(Thread::isAlive), "Actores originales vivos hasta cierre");
    }

    /** Espera la salida simultánea de los productores, no impone orden al coordinador. */
    private static List<SimulationController.Result> race(List<Supplier<CompletableFuture<SimulationController.Result>>> actions) throws Exception {
        var ready = new CountDownLatch(actions.size()); var release = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(actions.size()); long deadline = System.nanoTime() + LIMIT.toNanos();
        try {
            var launches = new ArrayList<Future<CompletableFuture<SimulationController.Result>>>();
            for (var action : actions) {
                launches.add(pool.submit(() -> {
                    ready.countDown(); check(release.await(10, TimeUnit.SECONDS), "Productor liberado"); return action.get();
                }));
            }
            check(ready.await(10, TimeUnit.SECONDS), "Todos los productores listos antes de liberar"); release.countDown();
            var confirmations = new ArrayList<CompletableFuture<SimulationController.Result>>();
            for (var launch : launches) { confirmations.add(launch.get(left(deadline), TimeUnit.NANOSECONDS)); }
            var results = new ArrayList<SimulationController.Result>();
            for (var confirmation : confirmations) { results.add(confirmation.get(left(deadline), TimeUnit.NANOSECONDS)); }
            return results;
        } finally {
            release.countDown(); pool.shutdownNow(); check(pool.awaitTermination(10, TimeUnit.SECONDS), "Productores de prueba terminados");
        }
    }

    private static long left(long deadline) { return Math.max(0L, deadline - System.nanoTime()); }
    private static CabinRequest cabin(int id, int floor, long epoch) { return new CabinRequest(UUID.randomUUID(), epoch, floor, Instant.now(), id); }
    private static HallRequest hall(int floor, long epoch) { return new HallRequest(UUID.randomUUID(), epoch, floor, Instant.now(), Direction.UP); }
    private static SimulationController.Result get(CompletableFuture<SimulationController.Result> future) throws Exception { return future.get(10, TimeUnit.SECONDS); }
    private static void check(boolean value, String detail) { if (!value) { throw new AssertionError(detail); } }

    private static boolean ownedName(Thread t) {
        return t.getName().startsWith("elevator-worker-") || t.getName().startsWith("manager-worker-")
                || t.getName().equals("simulation-controller") || t.getName().equals("simulation-shutdown");
    }

    /** Las capturas por identidad se ejecutan sin sesiones de prueba paralelas en esta suite. */
    private static final class Rig implements AutoCloseable {
        final Set<Thread> baseline = Thread.getAllStackTraces().keySet();
        final Set<Thread> owned = ConcurrentHashMap.newKeySet();
        final SimulationController c;
        final List<ExecutionEvent> trace = new ArrayList<>();
        Rig(int n, int floors, long move, long dwell) throws Exception {
            c = new SimulationController(new SimulationConfig(n, floors, move, dwell), 0);
            get(c.registerResource(() -> owned.add(Thread.currentThread()))); capture();
        }
        void capture() {
            Thread.getAllStackTraces().keySet().stream().filter(LifecycleRaceScenarios::ownedName)
                    .filter(t -> !baseline.contains(t)).forEach(owned::add);
        }
        Set<Thread> actors() {
            capture(); var result = new HashSet<Thread>();
            owned.stream().filter(t -> t.getName().startsWith("elevator-worker-")
                    || t.getName().startsWith("manager-worker-")).forEach(result::add); return result;
        }
        ExecutionEvent until(Predicate<ExecutionEvent> match) throws Exception {
            long deadline = System.nanoTime() + LIMIT.toNanos();
            while (true) {
                long left = left(deadline); check(left > 0, "Evento esperado dentro de diez segundos");
                var event = c.awaitEvent(Duration.ofNanos(left)).orElseThrow(() -> new AssertionError("Sin evento esperado dentro del plazo"));
                check(event.type() != WORKER_FAILED, "Cabina sin fallo"); trace.add(event);
                if (match.test(event)) { return event; }
            }
        }
        void drain() throws Exception {
            while (true) {
                var event = c.awaitEvent(Duration.ZERO); if (event.isEmpty()) { return; }
                check(event.orElseThrow().type() != WORKER_FAILED, "Sin evento de fallo"); trace.add(event.orElseThrow());
            }
        }
        long count(ExecutionEvent.Type type, long epoch) { return trace.stream().filter(e -> e.type() == type && e.epoch() == epoch).count(); }
        @Override public void close() throws Exception {
            capture(); c.close(); check(c.awaitTermination(LIMIT), "Sesión y cerrador terminados dentro del plazo");
            check(c.state() == SimulationState.CLOSED && c.failure().isEmpty(), "Cierre real sin fallo del coordinador");
            check(c.managerLifecycle() == ManagerWorker.Lifecycle.TERMINATED
                    && c.elevatorLifecycles().stream().allMatch(l -> l == ElevatorWorker.Lifecycle.TERMINATED), "Todos los actores terminados");
            check(owned.stream().noneMatch(Thread::isAlive), "No queda ninguna referencia Thread propia viva");
        }
    }

    public static void main(String[] args) throws Exception {
        resetDuringMovementCancelsOldRoute();
        resetDuringSeveralCabinJourneysIsGlobal();
        resetDuringDwellDoesNotWaitForDwell();
        resetWhileDwellIsStoppedClearsHold();
        resetFromStoppedDiscardsPreservedDestination();
        concurrentInitialStartCreatesOneSetOfActors();
        concurrentStartFromStoppedResumesOnce();
        concurrentStopWhenIdleHasOnePhysicalBarrier();
        concurrentStopDuringMovementPreservesRoute();
        startAndStopRaceHasAValidSerializableOutcome();
        stopAndResetRaceCannotRestoreStoppedEpoch();
        startAndResetRaceCannotStartOldWork();
        cabinAdmissionRacingResetDoesNotSurviveIt();
        hallAdmissionRacingResetDoesNotSurviveIt();
        closeWhileResettingResolvesEveryRacingControl();
        closeWhileStoppingResolvesEveryRacingControl();
        initialStartRacingCloseCannotResurrectSession();
        admissionsRacingCloseAreResolvedWithoutServiceClaim();
        closingGateRejectsAllControlsUntilResourceRelease();
        delayedEdtMessagesAreRevalidatedBeforeApplication();
        bufferedHistoryAcrossEpochsDoesNotCompleteFreshWork();
        cancelledStopObserverDoesNotCancelResetOrClose();
        repeatedMixedControlsKeepExactThreadIdentities();
        repeatedSessionsDoNotAccumulateOwnedThreads();
        System.out.println("T40: 24 escenarios satisfactorios.");
    }
}
