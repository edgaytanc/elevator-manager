package com.elevatormanager.simulation;

import com.elevatormanager.model.Direction;
import com.elevatormanager.model.ElevatorPhase;
import com.elevatormanager.model.SimulationConfig;
import com.elevatormanager.model.request.CabinRequest;
import com.elevatormanager.protocol.Command;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import static com.elevatormanager.simulation.ExecutionEvent.Type.*;

/** T28: observaciones con hilos reales. No usa sleeps ni mide el tiempo de lectura de colas. */
public final class ParallelTimingScenarios {
    private static final Duration LIMIT = Duration.ofSeconds(8);
    // Margen superior por transición; no se permite terminar antes de la duración configurada.
    private static final long LATE_MARGIN = Duration.ofSeconds(2).toNanos();
    private ParallelTimingScenarios() { }

    public static void simultaneousJourneysOverlap() throws Exception {
        try (var rig = new Rig("recorridos-simultaneos", 5, 120, 90)) {
            simultaneous(rig.a, destination(1, 1, 4), rig.b, destination(1, 2, 4));
            rig.until(rig.a, e -> e.type() == STOP_ENDED);
            rig.until(rig.b, e -> e.type() == STOP_ENDED);
            var a = rig.events(rig.a); var b = rig.events(rig.b);
            overlap(first(a, DEPARTED), last(a, ARRIVED), first(b, DEPARTED), last(b, ARRIVED));
            check(count(a, ARRIVED) == 3 && count(b, ARRIVED) == 3, "Tres llegadas adyacentes por cabina.");
            verifyTrace(a, 120, 90); verifyTrace(b, 120, 90);
            elapsed(first(a, DEPARTED), last(a, ARRIVED), 360, 3, "Viaje A acumulado");
            elapsed(first(b, DEPARTED), last(b, ARRIVED), 360, 3, "Viaje B acumulado");
        }
    }

    public static void oppositeJourneysOverlap() throws Exception {
        try (var rig = new Rig("sentidos-opuestos", 5, 100, 50)) {
            send(rig.a, destination(1, 1, 4));
            rig.until(rig.a, e -> e.type() == STOP_ENDED);
            int startA = rig.events(rig.a).size();
            simultaneous(rig.a, destination(2, 1, 1), rig.b, destination(1, 2, 4));
            rig.until(rig.a, e -> e.type() == STOP_ENDED);
            rig.until(rig.b, e -> e.type() == STOP_ENDED);
            var a = rig.events(rig.a).subList(startA, rig.events(rig.a).size());
            var b = rig.events(rig.b);
            overlap(first(a, DEPARTED), last(a, ARRIVED), first(b, DEPARTED), last(b, ARRIVED));
            check(first(a, DEPARTED).snapshot().direction() == Direction.DOWN
                    && first(b, DEPARTED).snapshot().direction() == Direction.UP, "Recorridos contrarios.");
            verifyTrace(rig.events(rig.a), 100, 50); verifyTrace(b, 100, 50);
        }
    }

    public static void anotherCabinMovesDuringDwell() throws Exception {
        try (var rig = new Rig("movimiento-durante-parada-ajena", 5, 80, 1000)) {
            send(rig.a, destination(1, 1, 1));
            rig.until(rig.a, e -> e.type() == STOP_STARTED);
            send(rig.b, destination(1, 2, 2));
            rig.until(rig.b, e -> e.type() == COMMAND_COMPLETED);
            rig.until(rig.a, e -> e.type() == STOP_ENDED);
            var a = rig.events(rig.a); var b = rig.events(rig.b);
            check(first(b, DEPARTED).referenceNanos() - first(a, STOP_STARTED).referenceNanos() >= 0,
                    "B sale después de abrir la parada A.");
            check(first(a, STOP_ENDED).referenceNanos() - first(b, COMMAND_COMPLETED).referenceNanos() > 0,
                    "B completa su servicio antes de terminar la parada A.");
            verifyTrace(a, 80, 1000); verifyTrace(b, 80, 1000);
        }
    }

    public static void shortJourneyFinishesDuringLongJourney() throws Exception {
        try (var rig = new Rig("viaje-corto-durante-viaje-largo", 5, 180, 60)) {
            simultaneous(rig.a, destination(1, 1, 5), rig.b, destination(1, 2, 2));
            rig.until(rig.a, e -> e.type() == COMMAND_COMPLETED);
            rig.until(rig.b, e -> e.type() == COMMAND_COMPLETED);
            var a = rig.events(rig.a); var b = rig.events(rig.b);
            overlap(first(a, DEPARTED), last(a, ARRIVED), first(b, DEPARTED), last(b, ARRIVED));
            check(first(a, COMMAND_COMPLETED).referenceNanos()
                    - first(b, COMMAND_COMPLETED).referenceNanos() > 0,
                    "B termina antes de que A complete su recorrido largo.");
            verifyTrace(a, 180, 60); verifyTrace(b, 180, 60);
        }
    }

    public static void intermediateStopAddsItsFullDuration() throws Exception {
        try (var rig = new Rig("parada-intermedia-y-tiempo-acumulado", 5, 180, 160)) {
            send(rig.a, destination(1, 1, 2));
            rig.until(rig.a, e -> e.type() == DEPARTED);
            send(rig.a, destination(2, 1, 3));
            rig.until(rig.a, e -> e.type() == COMMAND_COMPLETED && e.command().orElseThrow().commandId() == 2);
            var events = rig.events(rig.a);
            check(count(events, STOP_STARTED) == 2 && count(events, ARRIVED) == 2,
                    "Dos tramos y dos servicios, sin teletransporte.");
            var departures = events.stream().filter(e -> e.type() == DEPARTED).toList();
            elapsed(first(events, STOP_STARTED), departures.get(1), 160, 1, "Parada antes de continuar");
            elapsed(first(events, DEPARTED), last(events, ARRIVED), 520, 3, "Movimiento más parada intermedia");
            verifyTrace(events, 180, 160);
        }
    }

    public static void mailboxWakeupsDoNotShortenOrRestartLeg() throws Exception {
        try (var rig = new Rig("despertares-del-buzon", 5, 400, 60)) {
            var command = destination(1, 1, 2); send(rig.a, command);
            rig.until(rig.a, e -> e.type() == DEPARTED);
            for (int i = 0; i < 32; i++) { send(rig.a, command); }
            rig.until(rig.a, e -> e.type() == COMMAND_COMPLETED);
            var events = rig.events(rig.a);
            // Drena replays si el planificador hizo vencer el tramo durante la ráfaga.
            rig.drain(rig.a);
            check(count(events, COMMAND_REPLAYED) == 32 && count(events, COMMAND_COMPLETED) == 1,
                    "32 retransmisiones, una sola finalización.");
            check(count(events, DEPARTED) == 1 && count(events, ARRIVED) == 1, "Un único tramo.");
            verifyTrace(events, 400, 60);
        }
    }

    public static void closingOneCabinDoesNotStopTheOther() throws Exception {
        try (var rig = new Rig("cierre-local-independiente", 5, 180, 60)) {
            send(rig.a, destination(1, 1, 5));
            rig.until(rig.a, e -> e.type() == DEPARTED);
            rig.a.close(); check(rig.a.awaitTermination(LIMIT), "A termina realmente.");
            rig.until(rig.a, e -> e.type() == WORKER_TERMINATED);
            send(rig.b, destination(1, 2, 3));
            rig.until(rig.b, e -> e.type() == STOP_ENDED);
            check(count(rig.events(rig.b), COMMAND_COMPLETED) == 1, "B conserva servicio y espera de parada.");
            check(last(rig.events(rig.b), ARRIVED).snapshot().currentFloor() == 3, "B llega a su destino.");
            verifyTrace(rig.events(rig.a), 180, 60); verifyTrace(rig.events(rig.b), 180, 60);
        }
    }

    public static void singleFloorStopsOverlapWithoutMovement() throws Exception {
        try (var rig = new Rig("edificio-de-un-piso", 1, 100, 250)) {
            simultaneous(rig.a, destination(1, 1, 1), rig.b, destination(1, 2, 1));
            rig.until(rig.a, e -> e.type() == STOP_ENDED);
            rig.until(rig.b, e -> e.type() == STOP_ENDED);
            var a = rig.events(rig.a); var b = rig.events(rig.b);
            overlap(first(a, STOP_STARTED), first(a, STOP_ENDED), first(b, STOP_STARTED), first(b, STOP_ENDED));
            check(count(a, DEPARTED) + count(a, ARRIVED) + count(b, DEPARTED) + count(b, ARRIVED) == 0,
                    "Edificio de un piso no inventa viajes.");
            verifyTrace(a, 100, 250); verifyTrace(b, 100, 250);
        }
    }

    public static void main(String[] args) throws Exception {
        simultaneousJourneysOverlap(); oppositeJourneysOverlap(); anotherCabinMovesDuringDwell();
        shortJourneyFinishesDuringLongJourney(); intermediateStopAddsItsFullDuration();
        mailboxWakeupsDoNotShortenOrRestartLeg(); closingOneCabinDoesNotStopTheOther();
        singleFloorStopsOverlapWithoutMovement();
        System.out.println("T28: 8 escenarios satisfactorios.");
    }

    private static Command.Destination destination(long id, int cabin, int floor) {
        return new Command.Destination(id, cabin,
                new CabinRequest(UUID.randomUUID(), 0, floor, Instant.now(), cabin));
    }

    private static void send(ElevatorWorker worker, Command command) throws Exception {
        check(worker.submit(command), "Comando encolado.");
        check(worker.awaitResult(LIMIT).orElseThrow().outcome() != ElevatorWorker.Outcome.REJECTED,
                "Comando admitido o retransmitido.");
    }

    /** La barrera sincroniza productores; no exige simultaneidad exacta de los trabajadores. */
    private static void simultaneous(ElevatorWorker a, Command ca, ElevatorWorker b, Command cb)
            throws Exception {
        var ready = new CountDownLatch(2); var gate = new CountDownLatch(1);
        var error = new AtomicReference<Throwable>();
        Thread ta = producer(a, ca, ready, gate, error); Thread tb = producer(b, cb, ready, gate, error);
        ta.start(); tb.start();
        try {
            check(ready.await(LIMIT.toNanos(), TimeUnit.NANOSECONDS), "Productores preparados.");
        } finally { gate.countDown(); }
        ta.join(LIMIT.toMillis()); tb.join(LIMIT.toMillis());
        check(!ta.isAlive() && !tb.isAlive(), "Productores terminados.");
        if (error.get() != null) { throw new AssertionError("Fallo de productor.", error.get()); }
    }

    private static Thread producer(ElevatorWorker worker, Command command, CountDownLatch ready,
            CountDownLatch gate, AtomicReference<Throwable> error) {
        return new Thread(() -> {
            ready.countDown();
            try {
                check(gate.await(LIMIT.toNanos(), TimeUnit.NANOSECONDS), "Barrera liberada.");
                send(worker, command);
            } catch (Throwable failure) { error.compareAndSet(null, failure); }
        }, "t28-producer-" + worker.snapshot().id());
    }

    private static void overlap(ExecutionEvent aStart, ExecutionEvent aEnd,
            ExecutionEvent bStart, ExecutionEvent bEnd) {
        // Diferencias del mismo nanoTime de JVM; no compara tiempos de extracción de eventos.
        long origin = aStart.referenceNanos();
        long start = Math.max(0, bStart.referenceNanos() - origin);
        long end = Math.min(aEnd.referenceNanos() - origin, bEnd.referenceNanos() - origin);
        check(end > start, "Los intervalos reales deben solaparse; inicio=" + start + ", fin=" + end);
        System.out.printf(Locale.ROOT, "T28_CHECK overlap_ms=%.3f%n", (end - start) / 1_000_000.0);
    }

    private static void elapsed(ExecutionEvent start, ExecutionEvent end, long minimumMillis, int transitions, String label) {
        long actual = end.referenceNanos() - start.referenceNanos();
        long minimum = TimeUnit.MILLISECONDS.toNanos(minimumMillis);
        // En recorridos acumulados, el margen permite varios despachos del planificador.
        long margin = LATE_MARGIN * transitions;
        check(actual >= minimum && actual <= minimum + margin,
                label + ": esperado >= " + minimumMillis + " ms, observado " + actual / 1_000_000.0 + " ms");
        System.out.printf(Locale.ROOT, "T28_CHECK %s observed_ms=%.3f minimum_ms=%d%n",
                label, actual / 1_000_000.0, minimumMillis);
    }

    /** Comprueba trazas, no detalles privados del trabajador. Admite un último intervalo abierto por cierre. */
    private static void verifyTrace(List<ExecutionEvent> events, long moveMillis, long dwellMillis) {
        ExecutionEvent leg = null; ExecutionEvent stop = null; ExecutionEvent previous = null;
        int floor = 1;
        for (var event : events) {
            var snapshot = event.snapshot();
            check(snapshot.currentFloor() >= 1 && snapshot.currentFloor() <= snapshot.config().floorCount(),
                    "Piso dentro del edificio.");
            check(event.type() != WORKER_FAILED, "Sin fallo del trabajador.");
            if (previous != null) {
                check(event.sequence() == previous.sequence() + 1, "Secuencia local consecutiva.");
                check(snapshot.revision() >= previous.snapshot().revision(), "Revisión monotónica.");
                check(event.referenceNanos() - previous.referenceNanos() >= 0, "Reloj monotónico.");
            }
            if (event.type() == DEPARTED) {
                check(leg == null && stop == null && snapshot.phase() == ElevatorPhase.MOVING,
                        "Salida sin tramo o parada abierta.");
                check(snapshot.currentFloor() == floor, "Piso lógico no se adelanta al salir.");
                check(snapshot.nextFloor().orElseThrow() == floor + snapshot.direction().floorDelta(),
                        "Próximo piso adyacente en su sentido.");
                leg = event;
            } else if (event.type() == ARRIVED) {
                check(leg != null && snapshot.currentFloor() == leg.snapshot().nextFloor().orElseThrow(),
                        "Llegada corresponde a una salida previa.");
                phaseDuration(leg, event, moveMillis, "tramo"); floor = snapshot.currentFloor(); leg = null;
            } else if (event.type() == STOP_STARTED) {
                check(leg == null && stop == null && snapshot.phase() == ElevatorPhase.DWELLING,
                        "Parada alineada sin apertura duplicada.");
                stop = event;
            } else if (event.type() == STOP_ENDED) {
                check(stop != null && snapshot.currentFloor() == stop.snapshot().currentFloor(),
                        "Fin de la misma parada.");
                phaseDuration(stop, event, dwellMillis, "parada"); stop = null;
            }
            check(snapshot.currentFloor() == floor, "El piso lógico solo cambia al llegar.");
            previous = event;
        }
    }

    private static void phaseDuration(ExecutionEvent start, ExecutionEvent end, long millis, String label) {
        long duration = end.referenceNanos() - start.referenceNanos();
        long expected = TimeUnit.MILLISECONDS.toNanos(millis);
        check(duration >= expected && duration <= expected + LATE_MARGIN,
                label + ": esperado [" + millis + ", " + (millis + 2000)
                        + "] ms; observado " + duration / 1_000_000.0 + " ms");
    }

    private static ExecutionEvent first(List<ExecutionEvent> events, ExecutionEvent.Type type) {
        return events.stream().filter(e -> e.type() == type).findFirst().orElseThrow();
    }
    private static ExecutionEvent last(List<ExecutionEvent> events, ExecutionEvent.Type type) {
        return events.stream().filter(e -> e.type() == type).reduce((a, b) -> b).orElseThrow();
    }
    private static long count(List<ExecutionEvent> events, ExecutionEvent.Type type) {
        return events.stream().filter(e -> e.type() == type).count();
    }
    private static void check(boolean condition, String message) {
        if (!condition) { throw new AssertionError(message); }
    }

    private static final class Rig implements AutoCloseable {
        private final String name;
        private final long origin = System.nanoTime();
        private final ElevatorWorker a;
        private final ElevatorWorker b;
        private final List<ExecutionEvent> aEvents = new ArrayList<>();
        private final List<ExecutionEvent> bEvents = new ArrayList<>();
        private Rig(String name, int floors, long move, long dwell) {
            this.name = name; var config = new SimulationConfig(2, floors, move, dwell);
            a = new ElevatorWorker(1, config, 0); b = new ElevatorWorker(2, config, 0);
            a.start(); b.start();
        }
        private List<ExecutionEvent> events(ElevatorWorker worker) {
            return worker == a ? aEvents : bEvents;
        }
        private void until(ElevatorWorker worker, Predicate<ExecutionEvent> predicate) throws Exception {
            long started = System.nanoTime();
            while (true) {
                long remaining = LIMIT.toNanos() - (System.nanoTime() - started);
                check(remaining > 0, "Plazo global agotado: " + name);
                var event = worker.awaitEvent(Duration.ofNanos(remaining))
                        .orElseThrow(() -> new AssertionError("Falta evento: " + name));
                events(worker).add(event);
                check(event.type() != WORKER_FAILED, "El trabajador falló: " + worker.failure());
                if (predicate.test(event)) { return; }
            }
        }
        private void drain(ElevatorWorker worker) throws Exception {
            while (true) {
                var event = worker.awaitEvent(Duration.ZERO);
                if (event.isEmpty()) { return; }
                events(worker).add(event.orElseThrow());
            }
        }
        @Override public void close() throws Exception {
            a.close(); b.close();
            try {
                boolean endedA = a.awaitTermination(LIMIT); boolean endedB = b.awaitTermination(LIMIT);
                check(endedA && endedB, "Ambos hilos terminan.");
                check(a.failure().isEmpty() && b.failure().isEmpty(), "Sin fallos al cerrar.");
            } finally {
                drain(a); drain(b);
                System.out.println("T28_TRACE scenario=" + name);
                print(aEvents); print(bEvents);
            }
        }
        private void print(List<ExecutionEvent> events) {
            for (var e : events) {
                var s = e.snapshot();
                System.out.printf(Locale.ROOT,
                        "cabin=%d seq=%d t_ms=%.3f type=%s floor=%d next=%s direction=%s phase=%s command=%s%n",
                        e.elevatorId(), e.sequence(), (e.referenceNanos() - origin) / 1_000_000.0,
                        e.type(), s.currentFloor(), s.nextFloor().isPresent() ? s.nextFloor().getAsInt() : "-",
                        s.direction(), s.phase(), e.command().isPresent() ? e.command().orElseThrow().commandId() : "-");
            }
        }
    }
}
