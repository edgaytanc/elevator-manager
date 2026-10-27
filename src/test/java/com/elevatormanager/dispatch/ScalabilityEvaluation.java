package com.elevatormanager.dispatch;

import com.elevatormanager.model.Direction;
import com.elevatormanager.model.ElevatorPhase;
import com.elevatormanager.model.SimulationConfig;
import com.elevatormanager.model.request.HallRequest;
import com.elevatormanager.protocol.Command;
import com.elevatormanager.simulation.ElevatorWorker;
import com.elevatormanager.simulation.ExecutionEvent;
import com.elevatormanager.simulation.ExecutionEventMailbox;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import static com.elevatormanager.model.Direction.*;

/** T34: evaluación reproducible de cargas, con actores reales y métricas de eventos. */
public final class ScalabilityEvaluation {
    private static final long MS = 1_000_000L;
    private ScalabilityEvaluation() { }

    public record Options(int largerN, int repetitions, long movementMillis,
            long dwellMillis, long timeoutSeconds) {
        public Options {
            if (largerN < 3 || repetitions < 1 || timeoutSeconds < 1
                    || timeoutSeconds > Long.MAX_VALUE / 1_000_000_000L) {
                throw new IllegalArgumentException("N debe ser >=3; repeticiones y plazo deben ser positivos y representables.");
            }
            new SimulationConfig(largerN, 8, movementMillis, dwellMillis);
        }
        public List<Integer> cabinCounts() { return List.of(1, 2, largerN); }
        public List<Integer> orderForRepetition(int zeroBased) {
            if (zeroBased < 0) { throw new IllegalArgumentException("Índice de repetición negativo."); }
            var counts = cabinCounts();
            return IntStream.range(0, counts.size()).mapToObj(i -> counts.get((int) (((long) zeroBased + i) % counts.size()))).toList();
        }
        public Duration timeout() { return Duration.ofSeconds(timeoutSeconds); }
        public static Options fromSystemProperties() {
            return new Options(Integer.parseInt(System.getProperty("t34.n", "4")),
                    Integer.parseInt(System.getProperty("t34.repetitions", "3")),
                    Long.parseLong(System.getProperty("t34.movementMillis", "25")),
                    Long.parseLong(System.getProperty("t34.dwellMillis", "10")),
                    Long.parseLong(System.getProperty("t34.timeoutSeconds", "15")));
        }
    }

    public record CallSpec(int floor, Direction direction) {
        public CallSpec {
            Objects.requireNonNull(direction, "direction");
            if (floor < 1 || floor > 8 || floor == 1 && direction == DOWN || floor == 8 && direction == UP) {
                throw new IllegalArgumentException("Llamada inválida para ocho pisos.");
            }
        }
    }

    public record Workload(String name, List<CallSpec> calls) {
        public Workload {
            Objects.requireNonNull(name, "name"); calls = List.copyOf(calls);
            if (!name.matches("[a-z_]+") || calls.isEmpty() || new HashSet<>(calls).size() != calls.size()) {
                throw new IllegalArgumentException("Carga sin nombre estable, vacía o con duplicados funcionales.");
            }
        }
    }

    public static List<Workload> workloads() {
        return List.of(new Workload("subida", List.of(new CallSpec(2, UP), new CallSpec(3, UP),
                        new CallSpec(4, UP), new CallSpec(5, UP), new CallSpec(6, UP), new CallSpec(7, UP))),
                new Workload("mixta", List.of(new CallSpec(7, DOWN), new CallSpec(2, UP),
                        new CallSpec(5, DOWN), new CallSpec(3, UP), new CallSpec(6, DOWN), new CallSpec(4, UP))),
                new Workload("opuestos", List.of(new CallSpec(3, UP), new CallSpec(3, DOWN),
                        new CallSpec(5, UP), new CallSpec(5, DOWN), new CallSpec(7, DOWN), new CallSpec(2, UP))));
    }

    public record CallObservation(int index, int floor, Direction direction, int elevatorId,
            long commandId, long submittedNanos, long completedNanos, long waitNanos) { }

    public record Statistics(double meanMillis, double p95Millis, double maxMillis) {
        public static Statistics of(List<Long> samples) {
            if (samples.isEmpty() || samples.stream().anyMatch(n -> n < 0)) {
                throw new IllegalArgumentException("Se necesitan esperas no negativas.");
            }
            var sorted = samples.stream().sorted().toList();
            double mean = sorted.stream().mapToDouble(n -> n / (double) MS).average().orElseThrow();
            int percentile = (int) Math.ceil(0.95 * sorted.size()) - 1;
            return new Statistics(mean, sorted.get(percentile) / (double) MS,
                    sorted.get(sorted.size() - 1) / (double) MS);
        }
    }

    public record RunResult(String workload, int cabins, int repetition, List<CallObservation> calls,
            long movements, int stops, long makespanNanos, Map<Integer, Integer> servedByCabin,
            Map<Integer, Long> movementsByCabin, List<String> initialDecisions) {
        public RunResult {
            calls = List.copyOf(calls); servedByCabin = Map.copyOf(servedByCabin);
            movementsByCabin = Map.copyOf(movementsByCabin); initialDecisions = List.copyOf(initialDecisions);
        }
        public RunResult withDecisions(List<String> decisions) {
            return new RunResult(workload, cabins, repetition, calls, movements, stops, makespanNanos,
                    servedByCabin, movementsByCabin, decisions);
        }
        public Statistics statistics() { return Statistics.of(calls.stream().map(CallObservation::waitNanos).toList()); }
        public long activeCabins() { return servedByCabin.values().stream().filter(n -> n > 0).count(); }
    }

    public record Report(Options options, List<RunResult> runs) {
        public Report { runs = List.copyOf(runs); }
        public String markdown() {
            var out = new StringBuilder("# T34 — Resultados de eficiencia y escalabilidad\n\n");
            out.append("- **Ejecución UTC:** ").append(Instant.now()).append(".\n")
                    .append("- **Java:** ").append(System.getProperty("java.version")).append("; **SO:** ")
                    .append(System.getProperty("os.name")).append(".\n")
                    .append("- **Cabinas:** ").append(options.cabinCounts()).append("; **repeticiones:** ").append(options.repetitions())
                    .append("; **pisos:** 8; **movimiento:** ").append(options.movementMillis())
                    .append(" ms; **parada:** ").append(options.dwellMillis()).append(" ms.\n\n")
                    .append("Se conserva la misma lista y orden de llamadas por carga. Todas las cabinas parten del piso 1, UP y sin agenda; cada ejecución usa actores nuevos. ")
                    .append("El orden de N rota en cada repetición; con tres repeticiones cada configuración ocupa cada posición una vez. La espera va desde submit hasta el timestamp de COMMAND_COMPLETED compatible. El tiempo total incluye el fin de la última parada, pero excluye crear/cerrar hilos.\n\n")
                    .append("## Resumen comparativo\n\n")
                    .append("Los percentiles se calculan sobre todas las llamadas de las repeticiones del grupo; los movimientos y paradas son promedios por ejecución. ")
                    .append("La mejora de espera compara la media con N=1 de la misma carga; un valor negativo significa más espera.\n\n")
                    .append("| Carga | N | Muestras | Espera media ms | p95 ms | Máxima ms | Movimientos medios | Paradas medias | Tiempo total medio ms | Cabinas con servicio medias | Mejora espera % |\n")
                    .append("| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |\n");
            for (var workload : workloads()) {
                double baseline = groupStatistics(group(workload.name(), 1)).meanMillis();
                for (int n : options.cabinCounts()) {
                    var group = group(workload.name(), n); var s = groupStatistics(group);
                    int count = group.stream().mapToInt(r -> r.calls().size()).sum();
                    double improvement = baseline == 0 ? 0 : 100.0 * (baseline - s.meanMillis()) / baseline;
                    out.append(String.format(Locale.ROOT, "| %s | %d | %d | %.3f | %.3f | %.3f | %.3f | %.3f | %.3f | %.3f | %.2f |%n",
                            workload.name(), n, count, s.meanMillis(), s.p95Millis(), s.maxMillis(),
                            group.stream().mapToLong(RunResult::movements).average().orElseThrow(),
                            group.stream().mapToInt(RunResult::stops).average().orElseThrow(),
                            group.stream().mapToDouble(r -> r.makespanNanos() / (double) MS).average().orElseThrow(),
                            group.stream().mapToLong(RunResult::activeCabins).average().orElseThrow(), improvement));
                }
            }
            out.append("\n## Decisiones y límites\n\n")
                    .append("- La política vigente elige mínimo ETA y desempata por ID ascendente; una cabina con admisión pendiente queda reservada hasta confirmar su agenda.\n")
                    .append("- Los dos sentidos en un piso son pedidos distintos. Estas cargas excluyen duplicados funcionales para no confundir alias con atenciones independientes.\n")
                    .append("- Más cabinas permiten recorridos simultáneos, pero pueden aumentar el desplazamiento agregado y concentrar empates en IDs menores. No se exige mejora monótona ni reparto uniforme.\n")
                    .append("- Es una evaluación de cargas pequeñas y tiempos escalados, sin calentamiento JIT controlado, carga sostenida ni prueba estadística de significancia. No mide capacidad máxima ni demuestra optimalidad global.\n")
                    .append("- Los tiempos incluyen despacho, planificación del SO y reintentos. Los movimientos son tramos adyacentes confirmados por ARRIVED, sumados sobre todas las cabinas.\n\n")
                    .append("## Resultados por ejecución\n\n")
                    .append("| Carga | N | Repetición | Llamadas | Media ms | p95 ms | Movimientos | Paradas | Tiempo total ms | Servicios por cabina | Movimientos por cabina |\n")
                    .append("| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- | --- |\n");
            for (var r : runs) {
                var s = r.statistics();
                out.append(String.format(Locale.ROOT, "| %s | %d | %d | %d | %.3f | %.3f | %d | %d | %.3f | %s | %s |%n",
                        r.workload(), r.cabins(), r.repetition(), r.calls().size(), s.meanMillis(), s.p95Millis(),
                        r.movements(), r.stops(), r.makespanNanos() / (double) MS,
                        distribution(r.cabins(), r.servedByCabin()), distribution(r.cabins(), r.movementsByCabin())));
            }
            out.append("\n## Decisiones iniciales del manejador\n\n")
                    .append("Se conserva el resultado real de cada entrada. Si fue PENDING, la asignación posterior se consulta en las observaciones; no se inventa un ETA inicial.\n\n")
                    .append("| Carga | N | Repetición | Índice | Resultado y decisión inicial |\n")
                    .append("| --- | ---: | ---: | ---: | --- |\n");
            for (var r : runs) {
                for (int i = 0; i < r.initialDecisions().size(); i++) {
                    out.append(String.format(Locale.ROOT, "| %s | %d | %d | %d | %s |%n", r.workload(), r.cabins(),
                            r.repetition(), i + 1, r.initialDecisions().get(i).replace("|", "\\|").replace("\n", " ")));
                }
            }
            out.append("\n## Observaciones por llamada\n\n")
                    .append("Tiempos relativos al inicio de la ejecución, en nanosegundos del reloj monotónico. Espera = atendida - enviada; son timestamps de servicio, no instantes de extracción de la cola.\n\n")
                    .append("| Carga | N | Repetición | Índice | Piso | Sentido | Cabina | Comando | Enviada ns | Atendida ns | Espera ns |\n")
                    .append("| --- | ---: | ---: | ---: | ---: | --- | ---: | ---: | ---: | ---: | ---: |\n");
            for (var r : runs) {
                for (var c : r.calls()) {
                    out.append(String.format(Locale.ROOT, "| %s | %d | %d | %d | %d | %s | %d | %d | %d | %d | %d |%n",
                            r.workload(), r.cabins(), r.repetition(), c.index(), c.floor(), c.direction(), c.elevatorId(),
                            c.commandId(), c.submittedNanos(), c.completedNanos(), c.waitNanos()));
                }
            }
            return out.toString();
        }
        private List<RunResult> group(String workload, int cabins) {
            return runs.stream().filter(r -> r.workload().equals(workload) && r.cabins() == cabins).toList();
        }
        private static Statistics groupStatistics(List<RunResult> group) {
            return Statistics.of(group.stream().flatMap(r -> r.calls().stream()).map(CallObservation::waitNanos).toList());
        }
        private static String distribution(int n, Map<Integer, ? extends Number> values) {
            return IntStream.rangeClosed(1, n).mapToObj(i -> i + ":" + values.get(i)).collect(Collectors.joining("; "));
        }
    }

    public static Report evaluate(Options options) throws Exception {
        var runs = new ArrayList<RunResult>();
        for (var workload : workloads()) {
            for (int repetition = 0; repetition < options.repetitions(); repetition++) {
                for (int n : options.orderForRepetition(repetition)) {
                    runs.add(run(options, workload, n, repetition + 1));
                }
            }
        }
        return new Report(options, runs);
    }

    public static RunResult run(Options options, Workload workload, int cabins, int repetition) throws Exception {
        if (cabins < 1 || repetition < 1) { throw new IllegalArgumentException("Cabinas y repetición positivas."); }
        try (var session = new Session(options, cabins)) {
            session.start();
            var requests = new ArrayList<HallRequest>();
            for (int i = 0; i < workload.calls().size(); i++) {
                var call = workload.calls().get(i);
                var id = UUID.nameUUIDFromBytes(("T34:" + workload.name() + ":" + i).getBytes(StandardCharsets.UTF_8));
                requests.add(new HallRequest(id, 0, call.floor(), Instant.EPOCH, call.direction()));
            }
            var submitted = new LinkedHashMap<UUID, Long>();
            long origin = System.nanoTime();
            for (var request : requests) {
                submitted.put(request.getId(), System.nanoTime());
                check(session.manager.submit(request), "El manejador debe recibir toda la carga.");
            }
            var collector = new Collector(session.config, requests, submitted, origin);
            while (!collector.ready()) {
                var event = session.events.await(remaining(options, origin)).orElseThrow(() -> new AssertionError("Falta evento de servicio/parada."));
                collector.accept(event);
            }
            while (requests.stream().anyMatch(r -> session.manager.call(r.getId())
                    .map(s -> s.status() != HallCallSnapshot.Status.SERVED).orElse(true))) {
                session.manager.awaitUpdate(remaining(options, origin)).orElseThrow(() -> new AssertionError("Falta confirmación global."));
            }
            var commands = new HashSet<Long>();
            for (var request : requests) {
                var state = session.manager.call(request.getId()).orElseThrow();
                check(state.attempts() == 1 && state.canonicalRequestId().equals(request.getId())
                        && commands.add(state.command().orElseThrow().commandId()), "Una asignación y una canónica por llamada única.");
            }
            check(session.manager.calls().size() == requests.size(), "No desaparecen identidades ni se agregan pedidos ajenos.");
            var decisions = new ArrayList<String>();
            for (var request : requests) {
                var result = session.manager.awaitResult(Duration.ZERO).orElseThrow();
                check(result.request().getId().equals(request.getId()), "Un resultado en orden por entrada.");
                decisions.add(result.outcome() + ": " + result.detail()
                        + result.command().map(c -> " Destinatario=" + c.elevatorId() + "; commandId=" + c.commandId()).orElse(""));
            }
            check(session.manager.awaitResult(Duration.ZERO).isEmpty(), "Sin resultados extra de reintentos internos.");
            return collector.result(workload.name(), repetition).withDecisions(decisions);
        }
    }

    private static Duration remaining(Options options, long origin) {
        long remaining = options.timeout().toNanos() - (System.nanoTime() - origin);
        check(remaining > 0, "Se agotó el plazo global de la carga.");
        return Duration.ofNanos(remaining);
    }

    private static final class Session implements AutoCloseable {
        private final Options options;
        private final SimulationConfig config;
        private final List<ElevatorWorker> workers = new ArrayList<>();
        private final ExecutionEventMailbox events = new ExecutionEventMailbox();
        private final ManagerWorker manager;
        private Session(Options options, int count) {
            this.options = options; config = new SimulationConfig(count, 8, options.movementMillis(), options.dwellMillis());
            for (int id = 1; id <= count; id++) { workers.add(new ElevatorWorker(id, config, 0)); }
            var ids = new AtomicLong(); manager = new ManagerWorker(config, 0, workers, ids::incrementAndGet);
        }
        private void start() {
            for (var worker : workers) { worker.subscribeEvents(events); worker.start(); }
            manager.start();
        }
        @Override public void close() throws Exception {
            manager.close();
            for (var worker : workers) { worker.unsubscribeEvents(events); worker.close(); }
            check(manager.awaitTermination(options.timeout()), "El manejador debe terminar.");
            for (var worker : workers) { check(worker.awaitTermination(options.timeout()) && worker.failure().isEmpty(), "Cabina debe terminar sin fallos."); }
            check(manager.failure().isEmpty(), "El manejador no debe fallar.");
        }
    }

    /** Acumulador de observaciones; no usa el reloj del consumidor para medir esperas. */
    static final class Collector {
        private static final class Frame {
            private long sequence;
            private long reference;
            private int floor = 1;
            private ExecutionEvent leg;
            private ExecutionEvent stop;
            private long movements;
            private int stops;
            private int services;
        }
        private final SimulationConfig config;
        private final List<HallRequest> requests;
        private final Map<UUID, Long> submitted;
        private final Map<UUID, CallObservation> completed = new HashMap<>();
        private final List<Frame> frames = new ArrayList<>();
        private final long origin;
        private long maxElapsed;
        Collector(SimulationConfig config, List<HallRequest> requests, Map<UUID, Long> submitted, long origin) {
            this.config = config; this.requests = List.copyOf(requests); this.submitted = Map.copyOf(submitted); this.origin = origin;
            for (int i = 0; i < config.elevatorCount(); i++) { frames.add(new Frame()); }
        }
        void accept(ExecutionEvent event) {
            check(event.snapshot().config().equals(config) && event.epoch() == 0, "Evento de la sesión correcta.");
            var frame = frames.get(event.elevatorId() - 1); var s = event.snapshot();
            check(event.sequence() == frame.sequence + 1, "Secuencia por cabina sin pérdidas ni repeticiones.");
            check(frame.sequence == 0 || event.referenceNanos() - frame.reference >= 0, "Tiempo local monotónico.");
            frame.sequence = event.sequence(); frame.reference = event.referenceNanos();
            long elapsed = event.referenceNanos() - origin; check(elapsed >= 0, "Evento posterior al inicio.");
            maxElapsed = Math.max(maxElapsed, elapsed);
            switch (event.type()) {
                case DEPARTED -> {
                    check(frame.leg == null && frame.stop == null && s.phase() == ElevatorPhase.MOVING
                            && s.currentFloor() == frame.floor && s.nextFloor().orElseThrow() == frame.floor + s.direction().floorDelta(), "Salida adyacente sin solapamientos.");
                    frame.leg = event;
                }
                case ARRIVED -> {
                    check(frame.leg != null && s.currentFloor() == frame.leg.snapshot().nextFloor().orElseThrow(), "Llegada corresponde a un tramo.");
                    check(event.referenceNanos() - frame.leg.referenceNanos() >= config.movementTimeMillis() * MS, "Movimiento no abreviado.");
                    frame.floor = s.currentFloor(); frame.leg = null; frame.movements++;
                }
                case STOP_STARTED -> {
                    check(frame.leg == null && frame.stop == null && s.phase() == ElevatorPhase.DWELLING, "Parada alineada sin apertura doble.");
                    frame.stop = event; frame.stops++;
                }
                case STOP_ENDED -> {
                    check(frame.stop != null && s.currentFloor() == frame.stop.snapshot().currentFloor(), "Fin de la misma parada.");
                    check(event.referenceNanos() - frame.stop.referenceNanos() >= config.dwellTimeMillis() * MS, "Espera no abreviada.");
                    frame.stop = null;
                }
                case COMMAND_COMPLETED -> {
                    check(event.command().orElse(null) instanceof Command.Pickup, "La carga contiene solo llamadas externas.");
                    var command = (Command.Pickup) event.command().orElseThrow(); var request = command.request();
                    check(submitted.containsKey(request.getId()) && !completed.containsKey(request.getId()), "Llamada conocida y finalización única.");
                    check(frame.stop != null && s.phase() == ElevatorPhase.DWELLING && s.currentFloor() == request.getFloor()
                            && s.direction() == request.getDirection() && event.canonicalRequestId().equals(Optional.of(request.getId())), "Servicio compatible de una llamada única.");
                    int index = IntStream.range(0, requests.size()).filter(i -> requests.get(i).getId().equals(request.getId())).findFirst().orElseThrow();
                    long sent = submitted.get(request.getId()) - origin;
                    long wait = event.referenceNanos() - submitted.get(request.getId()); check(wait >= 0, "Espera no negativa.");
                    completed.put(request.getId(), new CallObservation(index + 1, request.getFloor(), request.getDirection(),
                            event.elevatorId(), command.commandId(), sent, elapsed, wait)); frame.services++;
                }
                case WORKER_FAILED, WORKER_TERMINATED -> throw new AssertionError("Trabajador terminó durante la medición.");
                default -> { }
            }
            check(s.currentFloor() == frame.floor, "El piso lógico solo cambia al llegar.");
        }
        boolean ready() {
            return completed.size() == requests.size() && frames.stream().allMatch(f -> f.leg == null && f.stop == null);
        }
        RunResult result(String workload, int repetition) {
            check(ready(), "No medir una carga incompleta.");
            var served = new LinkedHashMap<Integer, Integer>(); var moves = new LinkedHashMap<Integer, Long>();
            for (int i = 0; i < frames.size(); i++) { served.put(i + 1, frames.get(i).services); moves.put(i + 1, frames.get(i).movements); }
            return new RunResult(workload, config.elevatorCount(), repetition,
                    completed.values().stream().sorted(Comparator.comparingInt(CallObservation::index)).toList(),
                    frames.stream().mapToLong(f -> f.movements).sum(), frames.stream().mapToInt(f -> f.stops).sum(),
                    maxElapsed, served, moves, List.of());
        }
    }

    static void check(boolean value, String message) { if (!value) { throw new AssertionError(message); } }

    public static void main(String[] args) throws Exception {
        System.out.print(evaluate(Options.fromSystemProperties()).markdown());
    }
}
