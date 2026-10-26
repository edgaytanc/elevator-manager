package com.elevatormanager.dispatch;

import com.elevatormanager.model.SimulationConfig;
import com.elevatormanager.model.request.HallRequest;
import com.elevatormanager.simulation.ElevatorWorker;
import com.elevatormanager.simulation.PlanningSnapshot;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** T31: clasificación pura por ETA y después por ID ascendente. */
public final class ElevatorSelector {
    public enum Reason { NOT_RUNNING, OTHER_CONFIGURATION, OTHER_EPOCH, NOT_ESTIMABLE }

    public record Candidate(PlanningSnapshot planning, ElevatorWorker.Lifecycle lifecycle) {
        public Candidate {
            Objects.requireNonNull(planning, "planning");
            Objects.requireNonNull(lifecycle, "lifecycle");
        }
    }

    public record Choice(Candidate candidate, PickupTimeEstimator.Estimate estimate) {
        public int elevatorId() { return candidate.planning().elevator().id(); }
    }

    public record Excluded(int elevatorId, Reason reason) { }

    public record Ranking(List<Choice> choices, List<Excluded> excluded) {
        public Ranking {
            choices = List.copyOf(choices);
            excluded = List.copyOf(excluded);
        }
    }

    private final PickupTimeEstimator estimator = new PickupTimeEstimator();

    /** Una misma referencia temporal para todos los costos. No consulta trabajadores. */
    public Ranking rank(SimulationConfig config, HallRequest request,
            List<Candidate> candidates, long observedAtNanos) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(request, "request");
        if (!request.validateAgainst(config).isValid()) {
            throw new IllegalArgumentException("Solicitud inválida para el edificio.");
        }
        var choices = new ArrayList<Choice>();
        var excluded = new ArrayList<Excluded>();
        var ids = new HashSet<Integer>();
        for (var candidate : List.copyOf(candidates)) {
            var snapshot = candidate.planning().elevator();
            if (!ids.add(snapshot.id())) {
                throw new IllegalArgumentException("ID de cabina repetido.");
            }
            Reason reason = null;
            if (!snapshot.config().equals(config)) { reason = Reason.OTHER_CONFIGURATION; }
            else if (snapshot.epoch() != request.getEpoch()) { reason = Reason.OTHER_EPOCH; }
            else if (candidate.lifecycle() != ElevatorWorker.Lifecycle.RUNNING) { reason = Reason.NOT_RUNNING; }
            if (reason != null) {
                excluded.add(new Excluded(snapshot.id(), reason));
                continue;
            }
            var estimate = estimator.estimate(candidate.planning(), request, observedAtNanos);
            if (estimate.status() == PickupTimeEstimator.Status.ESTIMATED) {
                choices.add(new Choice(candidate, estimate));
            } else {
                excluded.add(new Excluded(snapshot.id(), Reason.NOT_ESTIMABLE));
            }
        }
        choices.sort(Comparator.<Choice, java.math.BigInteger>comparing(
                c -> c.estimate().pickupNanos().orElseThrow()).thenComparingInt(Choice::elevatorId));
        excluded.sort(Comparator.comparingInt(Excluded::elevatorId));
        return new Ranking(choices, excluded);
    }
}
