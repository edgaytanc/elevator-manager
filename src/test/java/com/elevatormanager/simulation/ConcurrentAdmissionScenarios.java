package com.elevatormanager.simulation;

import com.elevatormanager.model.Direction;
import com.elevatormanager.model.ElevatorPhase;
import com.elevatormanager.model.ElevatorState;
import com.elevatormanager.model.SimulationConfig;
import com.elevatormanager.model.request.CabinRequest;
import com.elevatormanager.model.request.HallRequest;
import com.elevatormanager.model.request.Request;
import com.elevatormanager.protocol.Command;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntFunction;

/** Identidades y alias deterministas; carreras mediante barreras, sin sleeps. */
public final class ConcurrentAdmissionScenarios {
    private static final Duration LIMIT = Duration.ofSeconds(5);
    private ConcurrentAdmissionScenarios() { }

    public static void firstAdmissionIsCanonical() {
        var f = new Fixture(); var request = cabin(2);
        var result = f.ledger.admit(destination(1, request));
        check(result.kind() == CommandLedger.Kind.ADDED && !result.replay(), "Admisión inicial.");
        check(result.canonicalRequestId().orElseThrow().equals(request.getId()), "UUID canónico.");
        check(result.requestStatus().orElseThrow() == CommandLedger.RequestStatus.PENDING,
                "Aceptar no significa atender.");
        check(f.state.getCabinRequests().equals(List.of(request)), "Una entrada en agenda.");
    }

    public static void pendingReplayDoesNotMutateState() {
        var f = new Fixture(); var command = destination(1, cabin(2));
        var first = f.ledger.admit(command); long revision = f.state.getRevision();
        var original = ((Command.Destination) command).request();
        var copy = new CabinRequest(original.getId(), original.getEpoch(), original.getFloor(),
                original.getCreatedAt(), original.getElevatorId());
        var replay = f.ledger.admit(destination(1, copy));
        check(replay.replay() && replay.kind() == first.kind(), "Reproduce decisión original.");
        check(f.state.getRevision() == revision && f.state.getCabinRequests().size() == 1,
                "No reinserta ni cambia revisión.");
    }

    public static void servedReplayDoesNotReinsert() {
        var f = new Fixture(); var command = destination(1, cabin(1));
        var initial = f.ledger.admit(command); f.serveCurrent();
        var replay = f.ledger.admit(command);
        check(replay.replay() && replay.requestStatus().orElseThrow() == CommandLedger.RequestStatus.SERVED,
                "Retransmisión informa servicio previo.");
        check(initial.requestStatus().orElseThrow() == CommandLedger.RequestStatus.PENDING,
                "Decisión anterior es una captura inmutable.");
        check(f.state.getCabinRequests().isEmpty(), "No reactiva un pedido servido.");
    }

    public static void commandIdConflictPreservesOriginal() {
        var f = new Fixture(); var original = destination(1, cabin(2));
        f.ledger.admit(original);
        check(f.ledger.admit(destination(1, cabin(3))).kind() == CommandLedger.Kind.REJECTED,
                "Rechaza commandId reutilizado.");
        check(f.ledger.admit(original).replay(), "Conflicto no reemplaza registro original.");
        check(f.state.getCabinRequests().get(0).getFloor() == 2, "Destino original intacto.");
    }

    public static void commandReplayPreservesCanonicalMetadata() {
        var f = new Fixture(); var request = cabin(2);
        f.ledger.admit(destination(1, request));
        var changed = new CabinRequest(request.getId(), 0, 2,
                request.getCreatedAt().plusNanos(1), 1);
        check(f.ledger.admit(destination(1, changed)).replay(), "Respeta Command.sameContent operativo.");
        check(f.state.getCabinRequests().get(0).getCreatedAt().equals(request.getCreatedAt()),
                "Reproducir la decisión no sobrescribe metadata canónica.");
    }

    public static void sameUuidWithNewCommandIsDuplicate() {
        var f = new Fixture(); var request = cabin(2);
        f.ledger.admit(destination(1, request));
        var duplicate = f.ledger.admit(destination(2, request));
        check(duplicate.kind() == CommandLedger.Kind.DUPLICATE && !duplicate.replay(),
                "Otro comando para UUID conocido.");
        check(duplicate.canonicalRequestId().orElseThrow().equals(request.getId()), "Misma canónica.");
    }

    public static void uuidContentCannotChange() {
        var f = new Fixture(); var request = cabin(2);
        f.ledger.admit(destination(1, request));
        var changed = new CabinRequest(request.getId(), 0, 3, request.getCreatedAt(), 1);
        check(f.ledger.admit(destination(2, changed)).kind() == CommandLedger.Kind.REJECTED,
                "UUID no cambia de piso.");
        var metadata = new CabinRequest(request.getId(), 0, 2, request.getCreatedAt().plusNanos(1), 1);
        check(f.ledger.admit(destination(3, metadata)).kind() == CommandLedger.Kind.REJECTED,
                "Una admisión nueva no redefine metadata de un UUID conocido.");
        check(f.state.getCabinRequests().equals(List.of(request)), "Agenda conserva original.");
    }

    public static void uuidTypeCannotChange() {
        var f = new Fixture(); var request = cabin(2);
        f.ledger.admit(destination(1, request));
        var hall = new HallRequest(request.getId(), 0, 2, request.getCreatedAt(), Direction.UP);
        check(f.ledger.admit(new Command.Pickup(2, 1, hall)).kind() == CommandLedger.Kind.REJECTED,
                "UUID no cambia de tipo.");
    }

    public static void functionalDuplicateCreatesAlias() {
        var f = new Fixture(); var first = cabin(2); var alias = cabin(2);
        f.ledger.admit(destination(1, first));
        var duplicate = f.ledger.admit(destination(2, alias));
        check(duplicate.kind() == CommandLedger.Kind.DUPLICATE, "Duplicado funcional controlado.");
        check(duplicate.canonicalRequestId().orElseThrow().equals(first.getId()), "Alias vinculado.");
        check(f.state.getPendingRequestsInAdmissionOrder().equals(List.of(first)),
                "Alias no duplica agenda ni reordena.");
    }

    public static void cabinAndHallDirectionsAreDifferentKeys() {
        var f = new Fixture();
        check(f.ledger.admit(destination(1, cabin(2))).kind() == CommandLedger.Kind.ADDED, "Interna.");
        check(f.ledger.admit(new Command.Pickup(2, 1, hall(2, Direction.UP))).kind()
                == CommandLedger.Kind.ADDED, "Externa UP distinta.");
        check(f.ledger.admit(new Command.Pickup(3, 1, hall(2, Direction.DOWN))).kind()
                == CommandLedger.Kind.ADDED, "Externa DOWN distinta.");
        check(f.state.getPendingRequestsInAdmissionOrder().size() == 3, "Tres identidades canónicas.");
    }

    public static void servedAliasDoesNotAttachToNewRequest() {
        var f = new Fixture(); var first = cabin(1); var alias = cabin(1);
        f.ledger.admit(destination(1, first));
        var aliasCommand = destination(2, alias);
        f.ledger.admit(aliasCommand); f.serveCurrent();
        var fresh = cabin(1); f.ledger.admit(destination(3, fresh));
        var replay = f.ledger.admit(aliasCommand);
        check(replay.requestStatus().orElseThrow() == CommandLedger.RequestStatus.SERVED,
                "Alias conserva resultado histórico.");
        check(replay.canonicalRequestId().orElseThrow().equals(first.getId()), "No migra al pedido nuevo.");
        f.ledger.markServed(List.of(first));
        var newest = f.ledger.admit(destination(4, cabin(1)));
        check(newest.canonicalRequestId().orElseThrow().equals(fresh.getId()),
                "Confirmación vieja no libera la clave de la nueva canónica.");
    }

    public static void freshIdentityCanRequestServedFloorAgain() {
        var f = new Fixture(); var first = cabin(1);
        f.ledger.admit(destination(1, first)); f.serveCurrent();
        var fresh = cabin(1); var result = f.ledger.admit(destination(2, fresh));
        check(result.kind() == CommandLedger.Kind.ADDED, "Piso servido vuelve a solicitarse.");
        check(result.canonicalRequestId().orElseThrow().equals(fresh.getId()), "Nueva identidad.");
        check(f.state.getCabinRequests().equals(List.of(fresh)), "Nuevo pendiente real.");
    }

    public static void invalidRoutingEpochAndResetDoNotReserveId() {
        var f = new Fixture();
        var wrong = new CabinRequest(UUID.randomUUID(), 0, 2, Instant.now(), 2);
        check(f.ledger.admit(new Command.Destination(10, 2, wrong)).kind()
                == CommandLedger.Kind.REJECTED, "Destinatario ajeno.");
        check(f.ledger.admit(destination(10, cabin(2))).kind() == CommandLedger.Kind.ADDED,
                "Destinatario ajeno no reserva ID local.");
        var old = new CabinRequest(UUID.randomUUID(), 1, 3, Instant.now(), 1);
        check(f.ledger.admit(destination(11, old)).kind() == CommandLedger.Kind.REJECTED, "Otra época.");
        check(f.ledger.admit(destination(11, cabin(3))).kind() == CommandLedger.Kind.ADDED,
                "Época ajena no reserva ID vigente.");
        check(f.ledger.admit(new Command.Reset(12, 1, 1)).kind() == CommandLedger.Kind.REJECTED,
                "Reset operativo pendiente.");
        check(f.ledger.admit(destination(12, cabin(4))).kind() == CommandLedger.Kind.ADDED,
                "Reset rechazado no reserva comando de servicio.");
        check(f.ledger.admit(destination(13, cabin(201))).kind() == CommandLedger.Kind.REJECTED,
                "Piso inválido.");
        check(f.ledger.admit(destination(13, cabin(5))).kind() == CommandLedger.Kind.ADDED,
                "Dato inválido no reserva ID.");
    }

    public static void rejectedIdentityDecisionCanBeReplayed() {
        var f = new Fixture(); var original = cabin(1);
        f.ledger.admit(destination(1, original));
        var bad = destination(2, new CabinRequest(original.getId(), 0, 2, original.getCreatedAt(), 1));
        check(f.ledger.admit(bad).kind() == CommandLedger.Kind.REJECTED, "Rechazo por identidad.");
        f.serveCurrent();
        var replay = f.ledger.admit(bad);
        check(replay.replay() && replay.kind() == CommandLedger.Kind.REJECTED,
                "Rechazo estable aunque cambie estado operativo.");
    }

    public static void concurrentFunctionalBurstHasOneCanonical() throws Exception {
        var worker = worker(1);
        try {
            worker.start();
            List<ElevatorWorker.Result> results = burst(worker, 6, 30,
                    i -> destination(i + 1, cabin(2)));
            check(count(results, ElevatorWorker.Outcome.ACCEPTED) == 1, "Una admisión canónica.");
            check(count(results, ElevatorWorker.Outcome.DUPLICATE) == 179, "179 alias reconocidos.");
            check(results.stream().map(r -> r.canonicalRequestId().orElseThrow()).distinct().count() == 1,
                    "Todos comparten UUID canónico.");
            check(worker.snapshot().cabinRequests().size() == 1, "Un solo pendiente durante movimiento.");
            check(worker.snapshot().phase() == ElevatorPhase.MOVING, "Inserción con tramo activo.");
        } finally { close(worker); }
    }

    public static void concurrentExactRetriesExecuteAdmissionOnce() throws Exception {
        var worker = worker(1); var command = destination(1, cabin(2));
        try {
            worker.start();
            List<ElevatorWorker.Result> results = burst(worker, 6, 20, i -> command);
            check(count(results, ElevatorWorker.Outcome.ACCEPTED) == 120, "Cada envío obtiene resultado.");
            check(results.stream().filter(ElevatorWorker.Result::replay).count() == 119,
                    "Una decisión nueva y 119 reproducciones.");
            check(worker.snapshot().cabinRequests().size() == 1, "No repite inserción.");
        } finally { close(worker); }
    }

    public static void concurrentUniqueInsertionPublishesCoherentSnapshots() throws Exception {
        var worker = worker(1);
        var done = new AtomicBoolean(); var started = new CountDownLatch(1);
        var failure = new AtomicReference<Throwable>();
        var reader = new Thread(() -> {
            long revision = -1;
            try {
                do {
                    var snapshot = worker.snapshot();
                    check(snapshot.revision() >= revision, "Revisión publicada no retrocede.");
                    revision = snapshot.revision();
                    var ids = new HashSet<UUID>();
                    snapshot.cabinRequests().forEach(r -> check(ids.add(r.getId()), "Snapshot sin UUID repetido."));
                    check(snapshot.currentFloor() == 1, "No llega antes de un minuto.");
                    if (snapshot.phase() == ElevatorPhase.MOVING) {
                        check(snapshot.nextFloor().orElseThrow() == 2, "Tramo coherente.");
                    }
                    started.countDown();
                    Thread.yield();
                } while (!done.get());
            } catch (Throwable exception) { failure.set(exception); started.countDown(); }
        }, "t26-snapshot-reader");
        try {
            worker.start(); reader.start();
            check(started.await(5, TimeUnit.SECONDS), "Lector iniciado.");
            var results = burst(worker, 4, 25, i -> destination(i + 1, cabin(i + 2)));
            check(count(results, ElevatorWorker.Outcome.ACCEPTED) == 100, "100 inserciones sin pérdida.");
            check(worker.snapshot().cabinRequests().size() == 100, "Agenda completa.");
        } finally {
            done.set(true); reader.join(5_000);
            check(!reader.isAlive(), "Lector terminado."); close(worker);
        }
        if (failure.get() != null) { throw new AssertionError("Publicación incoherente.", failure.get()); }
    }

    public static void concurrentInsertionDuringDwellDoesNotRestartStop() throws Exception {
        var worker = worker(1);
        try {
            worker.start(); accepted(worker, destination(1, cabin(1))); service(worker);
            var before = worker.snapshot();
            var results = burst(worker, 4, 20, i -> destination(i + 100, cabin(i % 10 + 2)));
            check(count(results, ElevatorWorker.Outcome.ACCEPTED) == 10, "Diez claves pendientes distintas.");
            check(count(results, ElevatorWorker.Outcome.DUPLICATE) == 70, "Duplicados durante parada.");
            var after = worker.snapshot();
            check(after.phase() == ElevatorPhase.DWELLING && after.nextFloor().isEmpty(),
                    "No inicia movimiento con parada pendiente.");
            check(after.remainingNanos() <= before.remainingNanos(), "No reinicia duración.");
            check(after.cabinRequests().size() == 10, "Conserva pendientes nuevos.");
        } finally { close(worker); }
    }

    public static void workerReplayAfterServiceAndFreshRequest() throws Exception {
        var worker = worker(1); var command = destination(1, cabin(1));
        try {
            worker.start(); accepted(worker, command); var first = service(worker);
            var replay = send(worker, command);
            check(replay.replay() && replay.requestStatus().orElseThrow() == CommandLedger.RequestStatus.SERVED,
                    "Replay conoce servicio previo.");
            var duplicate = send(worker, destination(2, ((Command.Destination) command).request()));
            check(duplicate.outcome() == ElevatorWorker.Outcome.DUPLICATE, "UUID servido no se reactiva.");
            check(worker.awaitService(Duration.ZERO).isEmpty(), "No hubo otro servicio por retransmitir.");
            var fresh = cabin(1); accepted(worker, destination(3, fresh));
            var next = service(worker);
            check(!next.openedStop() && next.requests().equals(List.of(fresh)),
                    "Solicitud nueva se sirve dentro de la parada abierta.");
            check(!first.requests().get(0).getId().equals(fresh.getId()), "Identidades diferentes.");
            check(worker.snapshot().cabinRequests().isEmpty(), "Ambas solicitudes atendidas.");
        } finally { close(worker); }
    }

    public static void commandIdsAreScopedToEachWorker() throws Exception {
        var first = worker(1); var second = worker(2);
        try {
            first.start(); second.start();
            var a = send(first, destination(1, cabin(2)));
            var bRequest = new CabinRequest(UUID.randomUUID(), 0, 2, Instant.now(), 2);
            var b = send(second, new Command.Destination(1, 2, bRequest));
            check(a.outcome() == ElevatorWorker.Outcome.ACCEPTED
                    && b.outcome() == ElevatorWorker.Outcome.ACCEPTED, "Mismo ID en distintas cabinas.");
            check(!a.canonicalRequestId().equals(b.canonicalRequestId()), "Registros independientes.");
        } finally { close(first); close(second); }
    }

    public static void concurrentCloseAccountsForEveryEnqueuedMessage() throws Exception {
        var worker = worker(1); var go = new CountDownLatch(1); var done = new CountDownLatch(4);
        var queued = new AtomicInteger(1); var error = new AtomicReference<Throwable>();
        var producers = new ArrayList<Thread>();
        try {
            worker.start(); check(worker.submit(destination(999_999, cabin(2))), "Entrada inicial.");
            for (int p = 0; p < 4; p++) {
                final int producer = p;
                var thread = new Thread(() -> {
                    try {
                        check(go.await(5, TimeUnit.SECONDS), "Barrera de cierre.");
                        for (int i = 0; i < 50; i++) {
                            if (worker.submit(destination(producer * 50 + i + 1, cabin(2)))) {
                                queued.incrementAndGet();
                            }
                        }
                    } catch (Throwable exception) { error.compareAndSet(null, exception); }
                    finally { done.countDown(); }
                }, "t26-close-producer-" + p);
                producers.add(thread); thread.start();
            }
            go.countDown(); worker.close();
            check(done.await(5, TimeUnit.SECONDS), "Productores terminados.");
            check(worker.awaitTermination(LIMIT), "Cierre termina hilo real.");
            for (int i = 0; i < queued.get(); i++) { worker.awaitResult(LIMIT).orElseThrow(); }
            check(worker.awaitResult(Duration.ZERO).isEmpty(), "Exactamente un resultado por encolado.");
            check(!worker.submit(destination(999_998, cabin(3))), "Después de cierre no encola.");
            if (error.get() != null) { throw new AssertionError("Productor falló.", error.get()); }
        } finally {
            go.countDown(); for (Thread thread : producers) { thread.join(5_000); }
            close(worker);
        }
    }

    public static void main(String[] args) throws Exception {
        firstAdmissionIsCanonical(); pendingReplayDoesNotMutateState(); servedReplayDoesNotReinsert();
        commandIdConflictPreservesOriginal(); commandReplayPreservesCanonicalMetadata();
        sameUuidWithNewCommandIsDuplicate(); uuidContentCannotChange(); uuidTypeCannotChange();
        functionalDuplicateCreatesAlias(); cabinAndHallDirectionsAreDifferentKeys();
        servedAliasDoesNotAttachToNewRequest(); freshIdentityCanRequestServedFloorAgain();
        invalidRoutingEpochAndResetDoNotReserveId(); rejectedIdentityDecisionCanBeReplayed();
        concurrentFunctionalBurstHasOneCanonical(); concurrentExactRetriesExecuteAdmissionOnce();
        concurrentUniqueInsertionPublishesCoherentSnapshots(); concurrentInsertionDuringDwellDoesNotRestartStop();
        workerReplayAfterServiceAndFreshRequest(); commandIdsAreScopedToEachWorker();
        concurrentCloseAccountsForEveryEnqueuedMessage();
        System.out.println("T26: 21 escenarios satisfactorios.");
    }

    private static final class Fixture {
        private final ElevatorState state = new ElevatorState(1, new SimulationConfig(2, 200, 100, 200), 0);
        private final CommandLedger ledger = new CommandLedger(state);
        private void serveCurrent() { ledger.markServed(state.beginDwell(Direction.UP, 0)); }
    }

    private static List<ElevatorWorker.Result> burst(ElevatorWorker worker, int producers, int count,
            IntFunction<Command> factory) throws Exception {
        var go = new CountDownLatch(1); var done = new CountDownLatch(producers);
        var error = new AtomicReference<Throwable>(); var threads = new ArrayList<Thread>();
        try {
            for (int p = 0; p < producers; p++) {
                final int producer = p;
                var thread = new Thread(() -> {
                    try {
                        check(go.await(5, TimeUnit.SECONDS), "Inicio concurrente.");
                        for (int i = 0; i < count; i++) {
                            check(worker.submit(factory.apply(producer * count + i)), "Mensaje encolado.");
                        }
                    } catch (Throwable exception) { error.compareAndSet(null, exception); }
                    finally { done.countDown(); }
                }, "t26-producer-" + p);
                threads.add(thread); thread.start();
            }
            go.countDown(); check(done.await(5, TimeUnit.SECONDS), "Ráfaga terminada.");
            if (error.get() != null) { throw new AssertionError("Productor falló.", error.get()); }
            var results = new ArrayList<ElevatorWorker.Result>();
            for (int i = 0; i < producers * count; i++) { results.add(worker.awaitResult(LIMIT).orElseThrow()); }
            return List.copyOf(results);
        } finally {
            go.countDown(); for (Thread thread : threads) { thread.join(5_000); }
        }
    }

    private static long count(List<ElevatorWorker.Result> results, ElevatorWorker.Outcome outcome) {
        return results.stream().filter(r -> r.outcome() == outcome).count();
    }

    private static CabinRequest cabin(int floor) {
        return new CabinRequest(UUID.randomUUID(), 0, floor, Instant.now(), 1);
    }

    private static HallRequest hall(int floor, Direction direction) {
        return new HallRequest(UUID.randomUUID(), 0, floor, Instant.now(), direction);
    }

    private static Command destination(long id, CabinRequest request) {
        return new Command.Destination(id, 1, request);
    }

    private static ElevatorWorker worker(int id) {
        return new ElevatorWorker(id, new SimulationConfig(2, 200, 60_000, 60_000), 0);
    }

    private static ElevatorWorker.Result send(ElevatorWorker worker, Command command) throws Exception {
        check(worker.submit(command), "Mensaje encolado.");
        return worker.awaitResult(LIMIT).orElseThrow();
    }

    private static void accepted(ElevatorWorker worker, Command command) throws Exception {
        check(send(worker, command).outcome() == ElevatorWorker.Outcome.ACCEPTED, "Admisión nueva.");
    }

    private static ElevatorWorker.Service service(ElevatorWorker worker) throws Exception {
        return worker.awaitService(LIMIT).orElseThrow(() -> new AssertionError("Falta servicio."));
    }

    private static void close(ElevatorWorker worker) throws Exception {
        worker.close(); check(worker.awaitTermination(LIMIT), "Trabajador terminado.");
    }

    private static void check(boolean condition, String message) {
        if (!condition) { throw new AssertionError(message); }
    }
}
