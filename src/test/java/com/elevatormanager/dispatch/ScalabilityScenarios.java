package com.elevatormanager.dispatch;

import com.elevatormanager.model.Direction;
import com.elevatormanager.model.ElevatorSnapshot;
import com.elevatormanager.model.ElevatorState;
import com.elevatormanager.model.SimulationConfig;
import com.elevatormanager.model.request.HallRequest;
import com.elevatormanager.protocol.Command;
import com.elevatormanager.simulation.ExecutionEvent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import static com.elevatormanager.dispatch.ScalabilityEvaluation.*;
import static com.elevatormanager.simulation.ExecutionEvent.Type.*;

/** T34: verifica mediciones y ejecución completa, sin exigir mejoras monótonas del SO. */
public final class ScalabilityScenarios {
    private static final long MS = 1_000_000L;
    private ScalabilityScenarios() { }

    public static void referenceLoadsAreStableUniqueAndImmutable() {
        check(workloads().equals(workloads()), "Las cargas y su orden deben conservarse.");
        for (var workload : workloads()) {
            check(workload.calls().size() == 6 && new HashSet<>(workload.calls()).size() == 6, "Seis llamadas únicas por carga.");
            expect(UnsupportedOperationException.class, () -> workload.calls().clear());
        }
        var options = shortOptions(5);
        check(options.cabinCounts().equals(List.of(1, 2, 5)), "Comparación 1/2/N sin supuesto de dos cabinas.");
        check(options.orderForRepetition(0).equals(List.of(1, 2, 5))
                && options.orderForRepetition(1).equals(List.of(2, 5, 1))
                && options.orderForRepetition(2).equals(List.of(5, 1, 2)), "Rotación de posiciones entre repeticiones.");
    }

    public static void optionsRejectThirdCountBelowThree() {
        expect(IllegalArgumentException.class, () -> shortOptions(2));
        expect(IllegalArgumentException.class, () -> shortOptions(0));
    }

    public static void optionsRejectBadTimingsRepetitionsAndDeadline() {
        expect(IllegalArgumentException.class, () -> new Options(4, 0, 25, 10, 15));
        expect(IllegalArgumentException.class, () -> new Options(4, 1, 0, 10, 15));
        expect(IllegalArgumentException.class, () -> new Options(4, 1, 25, -1, 15));
        expect(IllegalArgumentException.class, () -> new Options(4, 1, 25, 10, 0));
        expect(IllegalArgumentException.class, () -> new Options(4, 1, 25, 10, Long.MAX_VALUE));
    }

    public static void statisticsUsePerCallSamples() {
        var stats = Statistics.of(List.of(1 * MS, 2 * MS, 3 * MS, 4 * MS));
        check(stats.meanMillis() == 2.5 && stats.p95Millis() == 4 && stats.maxMillis() == 4, "Media y máximo de las muestras.");
        expect(IllegalArgumentException.class, () -> Statistics.of(List.of()));
        expect(IllegalArgumentException.class, () -> Statistics.of(List.of(-1L)));
        // Agrupar muestras evita promediar percentiles de ejecuciones distintas.
        var pooled = new ArrayList<Long>();
        for (int i = 0; i < 11; i++) { pooled.add(1 * MS); }
        pooled.add(100 * MS);
        check(Statistics.of(pooled).p95Millis() == 100, "p95 sobre las muestras combinadas.");
    }

    public static void percentileUsesNearestRank() {
        var samples = new ArrayList<Long>();
        for (int i = 1; i <= 20; i++) { samples.add(i * MS); }
        check(Statistics.of(samples).p95Millis() == 19, "Rango ceil(0.95*n), indexado desde uno.");
        check(Statistics.of(List.of(7 * MS)).p95Millis() == 7, "Una muestra.");
    }

    public static void timestampMetricsExcludeConsumerDelay() {
        var fixture = new Fixture(); var collector = fixture.collector();
        for (var event : fixture.events) { collector.accept(event); }
        var result = collector.result("reloj", 1); var call = result.calls().get(0);
        check(call.waitNanos() == 25 * MS + 200, "Espera calculada con timestamps, no con nanoTime al leer la cola.");
        check(call.completedNanos() - call.submittedNanos() == call.waitNanos(), "Datos auditables consistentes.");
        check(result.movements() == 1 && result.stops() == 1 && result.makespanNanos() == 35 * MS + 400,
                "Un tramo, una parada y tiempo hasta su cierre.");
    }

    public static void metricsRejectDuplicateCompletion() {
        var fixture = new Fixture(); var collector = fixture.collector();
        for (int i = 0; i < 5; i++) { collector.accept(fixture.events.get(i)); }
        check(!collector.ready(), "Completar servicio no termina la medición antes de cerrar la parada.");
        var completion = fixture.events.get(4);
        var duplicate = new ExecutionEvent(6, COMMAND_COMPLETED, completion.snapshot(), completion.command(), completion.canonicalRequestId(), "Duplicada.");
        expect(AssertionError.class, () -> collector.accept(duplicate));
    }

    public static void metricsRejectIncompatibleDirection() {
        var fixture = new Fixture(); var collector = fixture.collector();
        for (int i = 0; i < 4; i++) { collector.accept(fixture.events.get(i)); }
        var original = fixture.events.get(4); var s = original.snapshot();
        var wrong = new ElevatorSnapshot(s.id(), s.config(), s.epoch(), s.revision(), s.currentFloor(), s.nextFloor(),
                Direction.DOWN, s.phase(), s.suspendedPhase(), s.holdReason(), s.mode(), s.phaseDurationNanos(),
                s.remainingNanos(), s.referenceNanos(), s.committedFloor(), s.cabinRequests(), s.hallRequests());
        var event = new ExecutionEvent(5, COMMAND_COMPLETED, wrong, original.command(), original.canonicalRequestId(), "Sentido incompatible.");
        expect(AssertionError.class, () -> collector.accept(event));
    }

    public static void singleCabinCompletesSameLoads() throws Exception {
        for (var workload : workloads()) { verifyRun(run(shortOptions(4), workload, 1, 1), 1); }
    }

    public static void twoCabinsCompleteSameLoads() throws Exception {
        for (var workload : workloads()) { verifyRun(run(shortOptions(4), workload, 2, 1), 2); }
    }

    public static void configurableCabinCountCompletesSameLoads() throws Exception {
        int configured = Options.fromSystemProperties().largerN();
        var counts = new HashSet<>(List.of(configured, 5));
        for (int n : counts) {
            for (var workload : workloads()) { verifyRun(run(shortOptions(n), workload, n, 1), n); }
        }
    }

    public static void comparisonReportContainsAllConfigurations() throws Exception {
        var options = shortOptions(Options.fromSystemProperties().largerN());
        var report = evaluate(options);
        check(report.runs().size() == 9, "Tres cargas por tres cantidades de cabinas.");
        for (var workload : workloads()) {
            for (int n : options.cabinCounts()) {
                var runs = report.runs().stream().filter(r -> r.workload().equals(workload.name()) && r.cabins() == n).toList();
                check(runs.size() == 1, "Cada configuración se ejecuta una vez en la prueba."); verifyRun(runs.get(0), n);
            }
        }
        var markdown = report.markdown();
        check(markdown.contains("p95") && markdown.contains("mínimo ETA") && markdown.contains("Observaciones por llamada"),
                "El informe incluye métricas, decisiones y observaciones auditables.");
        expect(UnsupportedOperationException.class, () -> report.runs().clear());
    }

    public static void workloadRejectsAliasesAsIndependentSamples() {
        var call = new CallSpec(3, Direction.UP);
        expect(IllegalArgumentException.class, () -> new Workload("duplicada", List.of(call, call)));
        expect(IllegalArgumentException.class, () -> new Workload("vacia", List.of()));
        expect(IllegalArgumentException.class, () -> new CallSpec(1, Direction.DOWN));
        expect(IllegalArgumentException.class, () -> new CallSpec(8, Direction.UP));
    }

    public static void main(String[] args) throws Exception {
        referenceLoadsAreStableUniqueAndImmutable(); optionsRejectThirdCountBelowThree();
        optionsRejectBadTimingsRepetitionsAndDeadline(); statisticsUsePerCallSamples(); percentileUsesNearestRank();
        timestampMetricsExcludeConsumerDelay(); metricsRejectDuplicateCompletion(); metricsRejectIncompatibleDirection();
        singleCabinCompletesSameLoads(); twoCabinsCompleteSameLoads(); configurableCabinCountCompletesSameLoads();
        comparisonReportContainsAllConfigurations(); workloadRejectsAliasesAsIndependentSamples();
        System.out.println("T34: 13 escenarios satisfactorios; métricas verificadas y cargas completas con 1, 2 y N cabinas.");
    }

    private static Options shortOptions(int count) { return new Options(count, 1, 25, 10, 15); }
    private static void verifyRun(RunResult result, int cabins) {
        check(result.cabins() == cabins && result.calls().size() == 6 && result.initialDecisions().size() == 6,
                "Seis llamadas completas con sus decisiones iniciales por configuración.");
        check(result.servedByCabin().size() == cabins && result.movementsByCabin().size() == cabins, "Incluye cabinas sin trabajo.");
        check(result.servedByCabin().values().stream().mapToInt(Integer::intValue).sum() == 6, "Reparto suma todas las llamadas.");
        check(result.movementsByCabin().values().stream().mapToLong(Long::longValue).sum() == result.movements(), "Tramos totales consistentes.");
        check(result.activeCabins() >= 1 && result.activeCabins() <= cabins && result.stops() > 0 && result.stops() <= 6, "Conteos físicos válidos.");
        for (var call : result.calls()) {
            check(call.completedNanos() - call.submittedNanos() == call.waitNanos()
                    && call.waitNanos() >= 0 && call.waitNanos() <= result.makespanNanos(), "Espera no negativa y acotada por la ejecución.");
            check(call.elevatorId() >= 1 && call.elevatorId() <= cabins, "Sin destinatarios supuestos o fuera de N.");
        }
        expect(UnsupportedOperationException.class, () -> result.calls().clear());
        expect(UnsupportedOperationException.class, () -> result.servedByCabin().clear());
    }
    private static void expect(Class<? extends Throwable> type, Runnable action) {
        try { action.run(); } catch (Throwable e) { if (type.isInstance(e)) { return; } throw new AssertionError(e); }
        throw new AssertionError("Falta excepción " + type.getSimpleName());
    }

    private static final class Fixture {
        private final SimulationConfig config = new SimulationConfig(1, 8, 25, 10);
        private final HallRequest request = new HallRequest(UUID.randomUUID(), 0, 2, Instant.EPOCH, Direction.UP);
        private final Command.Pickup command = new Command.Pickup(1, 1, request);
        private final List<ExecutionEvent> events = new ArrayList<>();
        private Fixture() {
            var state = new ElevatorState(1, config, 0);
            state.addPendingRequest(request); add(COMMAND_ACCEPTED, state, 1300);
            state.beginJourney(2, 1400); add(DEPARTED, state, 1400);
            long arrived = 1400 + 25 * MS; state.completeLegIfDue(arrived); add(ARRIVED, state, arrived);
            state.beginDwell(Direction.UP, arrived); add(STOP_STARTED, state, arrived); add(COMMAND_COMPLETED, state, arrived);
            state.completeDwellIfDue(arrived + 10 * MS); add(STOP_ENDED, state, arrived + 10 * MS);
        }
        private void add(ExecutionEvent.Type type, ElevatorState state, long now) {
            boolean related = type == COMMAND_ACCEPTED || type == COMMAND_COMPLETED;
            events.add(new ExecutionEvent(events.size() + 1, type, state.snapshot(now),
                    related ? Optional.of(command) : Optional.empty(), related ? Optional.of(request.getId()) : Optional.empty(), "Reloj de prueba."));
        }
        private Collector collector() { return new Collector(config, List.of(request), Map.of(request.getId(), 1200L), 1000); }
    }
}
