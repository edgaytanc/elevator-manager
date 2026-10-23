package com.elevatormanager.simulation;

import com.elevatormanager.model.Direction;
import com.elevatormanager.model.ElevatorPhase;
import com.elevatormanager.model.SimulationConfig;
import com.elevatormanager.model.request.CabinRequest;
import com.elevatormanager.model.request.HallRequest;
import com.elevatormanager.protocol.Command;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import static com.elevatormanager.simulation.ExecutionEvent.Type.*;

/** Escenarios con hilo real, plazos acotados y sin dependencias de JUnit. */
public final class ExecutionEventScenarios {
    private static final Duration LIMIT = Duration.ofSeconds(5);
    private ExecutionEventScenarios() { }

    public static void travelHasOrderedAdjacentEvents() throws Exception {
        try (var w = worker(20, 30)) {
            w.start(); send(w, cabin(1, 3));
            var events = through(w, e -> e.type() == STOP_ENDED);
            check(types(events).equals(List.of(COMMAND_ACCEPTED, DEPARTED, ARRIVED,
                    DEPARTED, ARRIVED, STOP_STARTED, COMMAND_COMPLETED, STOP_ENDED)), "Orden de viaje.");
            check(events.get(1).snapshot().currentFloor() == 1
                    && events.get(1).snapshot().nextFloor().orElseThrow() == 2, "Salida adyacente.");
            check(events.get(2).snapshot().currentFloor() == 2
                    && events.get(2).snapshot().phase() == ElevatorPhase.IDLE, "Llegada antes de otro tramo.");
            check(count(events, COMMAND_COMPLETED) == 1, "Pasar por piso 2 no completa.");
            ordered(events);
        }
    }

    public static void approachChangesDirectionBeforeStop() throws Exception {
        try (var w = worker(20, 60_000)) {
            w.start(); var request = new HallRequest(UUID.randomUUID(), 0, 3, Instant.now(), Direction.DOWN);
            send(w, new Command.Pickup(1, 1, request));
            var events = through(w, e -> e.type() == COMMAND_COMPLETED);
            int n = events.size();
            check(events.get(n - 4).type() == ARRIVED && events.get(n - 3).type() == DIRECTION_CHANGED
                    && events.get(n - 2).type() == STOP_STARTED, "Llegada, inversión, parada.");
            check(events.get(n - 3).snapshot().direction() == Direction.DOWN, "Nuevo sentido.");
            check(events.get(n - 1).canonicalRequestId().orElseThrow().equals(request.getId()), "UUID servido.");
        }
    }

    public static void aliasesCompleteEachDistinctCommandOnce() throws Exception {
        try (var w = worker(100, 60_000)) {
            w.start(); var first = cabin(1, 2); var alias = cabin(2, 2);
            send(w, first); send(w, alias);
            var events = completions(w, 2);
            check(count(events, COMMAND_ACCEPTED) == 1 && count(events, COMMAND_DUPLICATE) == 1,
                    "Una admisión funcional.");
            var done = events.stream().filter(e -> e.type() == COMMAND_COMPLETED).toList();
            check(done.get(0).command().orElseThrow().commandId() == 1
                    && done.get(1).command().orElseThrow().commandId() == 2, "Orden de comandos asociados.");
            check(done.stream().allMatch(e -> e.canonicalRequestId().orElseThrow().equals(first.request().getId())),
                    "Alias conservan canónica.");
            check(count(events, STOP_STARTED) == 1 && w.awaitService(LIMIT).orElseThrow().requests().size() == 1,
                    "Una sola atención física.");
        }
    }

    public static void pendingReplayDoesNotCompleteTwice() throws Exception {
        try (var w = worker(100, 60_000)) {
            w.start(); var c = cabin(1, 2); send(w, c); send(w, c);
            var events = completions(w, 1);
            check(count(events, COMMAND_REPLAYED) == 1 && count(events, COMMAND_ACCEPTED) == 1,
                    "Retransmisión diferenciada.");
            w.close(); check(w.awaitTermination(LIMIT), "Cierre.");
            events.addAll(through(w, e -> e.type() == WORKER_TERMINATED));
            check(count(events, COMMAND_COMPLETED) == 1, "Sin finalización repetida.");
        }
    }

    public static void lateDistinctCommandUsesHistoricalService() throws Exception {
        try (var w = worker(20, 60_000)) {
            w.start(); var c = cabin(1, 1); send(w, c); completions(w, 1);
            send(w, new Command.Destination(2, 1, c.request()));
            var events = completions(w, 1);
            check(types(events).equals(List.of(COMMAND_DUPLICATE, COMMAND_COMPLETED)), "Satisfecho históricamente.");
            check(events.get(1).command().orElseThrow().commandId() == 2, "Nuevo comando confirmado.");
            check(w.awaitService(Duration.ZERO).isPresent() && w.awaitService(Duration.ZERO).isEmpty(),
                    "Solo servicio original.");
        }
    }

    public static void servedReplayIsOnlyReplay() throws Exception {
        try (var w = worker(20, 60_000)) {
            w.start(); var c = cabin(1, 1); send(w, c); completions(w, 1); send(w, c);
            check(event(w).type() == COMMAND_REPLAYED, "Respuesta histórica sin otro éxito.");
            w.close(); check(w.awaitTermination(LIMIT), "Cierre.");
            check(count(through(w, e -> e.type() == WORKER_TERMINATED), COMMAND_COMPLETED) == 0,
                    "No vuelve a completar.");
        }
    }

    public static void rejectedCommandHasNoCompletion() throws Exception {
        try (var w = worker(20, 60_000)) {
            w.start(); send(w, new Command.Reset(1, 1, 1));
            var e = event(w);
            check(e.type() == COMMAND_REJECTED && e.command().orElseThrow().commandId() == 1
                    && e.canonicalRequestId().isEmpty() && !e.detail().isBlank(), "Rechazo correlacionable.");
            w.close(); check(w.awaitTermination(LIMIT), "Cierre.");
            check(count(through(w, x -> x.type() == WORKER_TERMINATED), COMMAND_COMPLETED) == 0,
                    "Rechazo no simula atención.");
        }
    }

    public static void conflictingIdDoesNotReplaceCompletion() throws Exception {
        try (var w = worker(100, 60_000)) {
            w.start(); var c = cabin(1, 2); send(w, c); send(w, cabin(1, 3));
            var events = completions(w, 1);
            check(count(events, COMMAND_REJECTED) == 1, "Conflicto rechazado.");
            var done = events.get(events.size() - 1);
            check(done.command().orElseThrow().equals(c) && done.snapshot().currentFloor() == 2,
                    "Original permanece correlacionado.");
        }
    }

    public static void currentFloorDoesNotInventTravel() throws Exception {
        try (var w = worker(20, 60_000)) {
            w.start(); send(w, cabin(1, 1));
            check(types(completions(w, 1)).equals(List.of(COMMAND_ACCEPTED, STOP_STARTED, COMMAND_COMPLETED)),
                    "Atención directa sin salida ni llegada ficticia.");
        }
    }

    public static void joiningDwellDoesNotOpenAnotherStop() throws Exception {
        try (var w = worker(20, 60_000)) {
            w.start(); send(w, cabin(1, 1)); var first = completions(w, 1);
            var r = new HallRequest(UUID.randomUUID(), 0, 1, Instant.now(), Direction.UP);
            send(w, new Command.Pickup(2, 1, r)); var joined = completions(w, 1);
            check(types(joined).equals(List.of(COMMAND_ACCEPTED, COMMAND_COMPLETED)), "Sin nueva apertura.");
            check(joined.get(1).snapshot().remainingNanos() <= first.get(1).snapshot().remainingNanos(),
                    "No reinicia duración.");
        }
    }

    public static void eventsRetainImmutableSnapshots() throws Exception {
        try (var w = worker(20, 60_000)) {
            w.start(); send(w, cabin(1, 2)); var first = event(w); completions(w, 1);
            check(first.snapshot().currentFloor() == 1 && first.snapshot().cabinRequests().size() == 1,
                    "Admisión histórica intacta tras servicio.");
            boolean immutable = false;
            try { first.snapshot().cabinRequests().clear(); }
            catch (UnsupportedOperationException expected) { immutable = true; }
            check(immutable, "Colecciones inmutables.");
        }
    }

    public static void sequencesAreLocalToEachCabin() throws Exception {
        var config = new SimulationConfig(2, 5, 20, 60_000);
        try (var a = new ElevatorWorker(1, config, 0); var b = new ElevatorWorker(2, config, 0)) {
            a.start(); b.start(); send(a, cabin(1, 1));
            send(b, new Command.Destination(1, 2, new CabinRequest(UUID.randomUUID(), 0, 1, Instant.now(), 2)));
            var ea = completions(a, 1); var eb = completions(b, 1); ordered(ea); ordered(eb);
            check(ea.get(0).sequence() == 1 && eb.get(0).sequence() == 1, "Secuencias independientes.");
            check(ea.stream().allMatch(e -> e.elevatorId() == 1 && e.epoch() == 0)
                    && eb.stream().allMatch(e -> e.elevatorId() == 2), "Cabina y época correctas.");
        }
    }

    public static void closeDoesNotCompleteUnservedJourney() throws Exception {
        var w = worker(60_000, 60_000);
        w.start(); send(w, cabin(1, 5)); var events = through(w, e -> e.type() == DEPARTED);
        w.close(); check(w.awaitTermination(LIMIT), "Cierre despierta espera.");
        events.addAll(through(w, e -> e.type() == WORKER_TERMINATED));
        check(count(events, COMMAND_COMPLETED) == 0 && count(events, ARRIVED) == 0,
                "Cierre no inventa llegada ni servicio.");
        ordered(events);
    }

    public static void idleAndTimeoutContract() throws Exception {
        try (var w = worker(20, 20)) {
            w.start(); check(w.awaitEvent(Duration.ZERO).isEmpty(), "Reposo sin eventos artificiales.");
            boolean rejected = false;
            try { w.awaitEvent(Duration.ofNanos(-1)); }
            catch (IllegalArgumentException expected) { rejected = true; }
            check(rejected, "Timeout negativo rechazado.");
        }
    }

    public static void concurrentRetransmissionsHaveOneCompletion() throws Exception {
        try (var w = worker(20, 60_000)) {
            w.start(); var c = cabin(1, 1); var gate = new CountDownLatch(1);
            var error = new AtomicReference<Throwable>(); var threads = new ArrayList<Thread>();
            for (int i = 0; i < 24; i++) {
                var t = new Thread(() -> {
                    try { gate.await(); check(w.submit(c), "Encolado concurrente."); }
                    catch (Throwable e) { error.compareAndSet(null, e); }
                }); threads.add(t); t.start();
            }
            gate.countDown();
            for (var t : threads) { t.join(5000); check(!t.isAlive(), "Productor termina."); }
            if (error.get() != null) { throw new AssertionError(error.get()); }
            for (int i = 0; i < 24; i++) { check(w.awaitResult(LIMIT).isPresent(), "Resultado por envío."); }
            w.close(); check(w.awaitTermination(LIMIT), "Cierre.");
            var events = through(w, e -> e.type() == WORKER_TERMINATED);
            check(count(events, COMMAND_ACCEPTED) == 1 && count(events, COMMAND_REPLAYED) == 23
                    && count(events, COMMAND_COMPLETED) == 1, "Ejecución única con 24 envíos.");
            ordered(events);
        }
    }

    public static void nextJourneyFollowsStopEndAndDirectionChange() throws Exception {
        try (var w = worker(100, 40)) {
            w.start(); send(w, cabin(1, 2)); send(w, cabin(2, 1));
            var events = completions(w, 2);
            int end = types(events).indexOf(STOP_ENDED);
            check(end > 0 && events.get(end + 1).type() == DIRECTION_CHANGED
                    && events.get(end + 2).type() == DEPARTED, "Fin parada, inversión y salida.");
            check(events.get(end + 2).snapshot().direction() == Direction.DOWN, "Regreso DOWN.");
            ordered(events);
        }
    }

    public static void rejectedHistoryHasReplayEvent() throws Exception {
        try (var w = worker(100, 60_000)) {
            w.start(); var first = cabin(1, 2); send(w, first);
            var invalidIdentity = new Command.Destination(2, 1,
                    new CabinRequest(first.request().getId(), 0, 3, first.request().getCreatedAt(), 1));
            send(w, invalidIdentity); send(w, invalidIdentity);
            var events = completions(w, 1);
            check(count(events, COMMAND_REJECTED) == 1 && count(events, COMMAND_REPLAYED) == 1,
                    "Rechazo registrado también se retransmite sin mutación.");
            check(count(events, COMMAND_COMPLETED) == 1, "Solo termina original válido.");
        }
    }

    public static void routingRejectionIdentifiesActualWorker() throws Exception {
        try (var w = worker(20, 20)) {
            w.start(); var wrong = new Command.Destination(1, 2,
                    new CabinRequest(UUID.randomUUID(), 0, 2, Instant.now(), 2));
            send(w, wrong); var e = event(w);
            check(e.type() == COMMAND_REJECTED && e.elevatorId() == 1
                    && e.command().orElseThrow().elevatorId() == 2, "Emisor y destinatario distinguibles.");
        }
    }

    public static void ledgerServiceIsIdempotentForCompletions() {
        var state = new com.elevatormanager.model.ElevatorState(1,
                new SimulationConfig(1, 5, 20, 20), 0);
        var ledger = new CommandLedger(state); var c = cabin(1, 1); ledger.admit(c);
        var served = state.beginDwell(Direction.UP, 0); ledger.markServed(served);
        check(ledger.drainCompletions().size() == 1, "Primera confirmación.");
        ledger.markServed(served);
        check(ledger.drainCompletions().isEmpty(), "Confirmación repetida no recrea finalización.");
        ledger.admit(c);
        check(ledger.drainCompletions().isEmpty(), "Replay tampoco recrea finalización.");
    }

    public static void closeAccountsForEveryQueuedAdmissionEvent() throws Exception {
        var w = worker(60_000, 60_000); w.start();
        int submitted = 0;
        for (int i = 1; i <= 200; i++) { if (w.submit(cabin(i, 5))) { submitted++; } }
        w.close(); check(w.awaitTermination(LIMIT), "Cierre con buzón ocupado.");
        var events = through(w, e -> e.type() == WORKER_TERMINATED);
        long responses = events.stream().filter(e -> e.command().isPresent()).count();
        check(responses == submitted && count(events, COMMAND_COMPLETED) == 0,
                "Todo envío tiene admisión o rechazo al cerrar, sin servicio ficticio.");
        ordered(events);
    }

    public static void main(String[] args) throws Exception {
        travelHasOrderedAdjacentEvents(); approachChangesDirectionBeforeStop();
        aliasesCompleteEachDistinctCommandOnce(); pendingReplayDoesNotCompleteTwice();
        lateDistinctCommandUsesHistoricalService(); servedReplayIsOnlyReplay();
        rejectedCommandHasNoCompletion(); conflictingIdDoesNotReplaceCompletion();
        currentFloorDoesNotInventTravel(); joiningDwellDoesNotOpenAnotherStop();
        eventsRetainImmutableSnapshots(); sequencesAreLocalToEachCabin();
        closeDoesNotCompleteUnservedJourney(); idleAndTimeoutContract();
        concurrentRetransmissionsHaveOneCompletion(); nextJourneyFollowsStopEndAndDirectionChange();
        rejectedHistoryHasReplayEvent(); routingRejectionIdentifiesActualWorker();
        ledgerServiceIsIdempotentForCompletions(); closeAccountsForEveryQueuedAdmissionEvent();
        System.out.println("T27: 20 escenarios satisfactorios.");
    }

    private static ElevatorWorker worker(long move, long dwell) {
        return new ElevatorWorker(1, new SimulationConfig(1, 5, move, dwell), 0);
    }
    private static Command.Destination cabin(long id, int floor) {
        return new Command.Destination(id, 1, new CabinRequest(UUID.randomUUID(), 0, floor, Instant.now(), 1));
    }
    private static void send(ElevatorWorker w, Command c) throws Exception {
        check(w.submit(c), "Encolado."); check(w.awaitResult(LIMIT).isPresent(), "Respuesta.");
    }
    private static ExecutionEvent event(ElevatorWorker w) throws Exception {
        return w.awaitEvent(LIMIT).orElseThrow(() -> new AssertionError("Falta evento."));
    }
    private static ArrayList<ExecutionEvent> through(ElevatorWorker w, Predicate<ExecutionEvent> stop)
            throws Exception {
        var events = new ArrayList<ExecutionEvent>(); long start = System.nanoTime();
        while (System.nanoTime() - start < LIMIT.toNanos()) {
            var e = event(w); events.add(e); if (stop.test(e)) { return events; }
        }
        throw new AssertionError("No apareció el evento esperado.");
    }
    private static ArrayList<ExecutionEvent> completions(ElevatorWorker w, int count) throws Exception {
        int[] remaining = {count};
        return through(w, e -> e.type() == COMMAND_COMPLETED && --remaining[0] == 0);
    }
    private static List<ExecutionEvent.Type> types(List<ExecutionEvent> events) {
        return events.stream().map(ExecutionEvent::type).toList();
    }
    private static long count(List<ExecutionEvent> events, ExecutionEvent.Type type) {
        return events.stream().filter(e -> e.type() == type).count();
    }
    private static void ordered(List<ExecutionEvent> events) {
        for (int i = 1; i < events.size(); i++) {
            var a = events.get(i - 1); var b = events.get(i);
            check(b.sequence() == a.sequence() + 1, "Secuencia consecutiva.");
            check(b.snapshot().revision() >= a.snapshot().revision(), "Revisión no retrocede.");
            check(b.referenceNanos() - a.referenceNanos() >= 0, "Reloj monotónico.");
        }
    }
    private static void check(boolean condition, String message) {
        if (!condition) { throw new AssertionError(message); }
    }
}
