package com.elevatormanager.dispatch;

import com.elevatormanager.model.Direction;
import com.elevatormanager.model.ElevatorPhase;
import com.elevatormanager.model.ElevatorSnapshot;
import com.elevatormanager.model.request.CabinRequest;
import com.elevatormanager.model.request.HallRequest;
import com.elevatormanager.model.request.Request;
import com.elevatormanager.simulation.DirectionalAgenda;
import com.elevatormanager.simulation.PlanningSnapshot;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * T30: proyección pura del tiempo hasta servicio de una llamada externa.
 * Simula fronteras y paradas; no recorre piso por piso ni modifica el motor.
 * Supone admisión inmediata, agenda estable y ausencia de nuevas solicitudes.
 */
public final class PickupTimeEstimator {
    public enum Status { ESTIMATED, UNAVAILABLE, INVALID_REQUEST }
    public enum StepType { REMAINING_LEG, MOVEMENT, WAIT, PICKUP }

    /** WAIT incluye solo paradas anteriores a la recogida, nunca su duración posterior. */
    public record Step(StepType type, int fromFloor, int toFloor,
            Direction direction, BigInteger durationNanos) { }

    /** Tiempo relativo a observedAtNanos; ausencia de tiempo no significa costo cero. */
    public record Estimate(Status status, Optional<BigInteger> pickupNanos,
            long observedAtNanos, List<Step> steps, String detail) {
        public Estimate { steps = List.copyOf(steps); }
    }

    /** Estima desde el instante de captura, sin consultar un reloj real. */
    public Estimate estimate(PlanningSnapshot planning, HallRequest request) {
        Objects.requireNonNull(planning, "planning");
        return estimate(planning, request, planning.elevator().referenceNanos());
    }

    /**
     * observedAtNanos usa el mismo nanoTime de la JVM. Solo descuenta edad de la
     * fase capturada; no declara como reales llegadas todavía no publicadas.
     */
    public Estimate estimate(PlanningSnapshot planning, HallRequest request, long observedAtNanos) {
        Objects.requireNonNull(planning, "planning");
        Objects.requireNonNull(request, "request");
        var snapshot = planning.elevator();
        long age = observedAtNanos - snapshot.referenceNanos();
        if (age < 0) {
            throw new IllegalArgumentException("Observación anterior a la captura o intervalo no representable.");
        }
        if (request.getEpoch() != snapshot.epoch()
                || !request.validateAgainst(snapshot.config()).isValid()) {
            return rejected(Status.INVALID_REQUEST, observedAtNanos,
                    "Solicitud inválida para el edificio o la época.");
        }
        if (snapshot.mode() != ElevatorSnapshot.Mode.NORMAL
                || snapshot.phase() == ElevatorPhase.HELD
                || snapshot.phase() == ElevatorPhase.TERMINATED) {
            return rejected(Status.UNAVAILABLE, observedAtNanos,
                    "La cabina no está disponible para servicio normal.");
        }
        var pending = new ArrayList<>(planning.admissionOrder());
        HallRequest target = request;
        for (var existing : pending) {
            if (existing.getId().equals(request.getId())
                    && !PlanningSnapshot.sameRequest(existing, request)) {
                return rejected(Status.INVALID_REQUEST, observedAtNanos,
                        "UUID pendiente reutilizado con contenido diferente.");
            }
        }
        for (var existing : pending) {
            if (existing instanceof HallRequest hall && hall.getFloor() == request.getFloor()
                    && hall.getDirection() == request.getDirection()) {
                target = hall;
                break;
            }
        }
        if (target == request && !pending.contains(request)) { pending.add(request); }
        long remaining = age >= snapshot.remainingNanos() ? 0 : snapshot.remainingNanos() - age;
        return new Projection(planning, pending, target, remaining, observedAtNanos).run();
    }

    private static Estimate rejected(Status status, long now, String detail) {
        return new Estimate(status, Optional.empty(), now, List.of(), detail);
    }

    /** Estado exclusivo de una invocación; no contiene referencias mutables del actor. */
    private static final class Projection {
        private final ElevatorSnapshot source;
        private final ArrayList<Request> pending;
        private final HallRequest target;
        private final List<Step> steps = new ArrayList<>();
        private final long remaining;
        private final long observedAt;
        private final BigInteger movement;
        private final BigInteger dwell;
        private BigInteger elapsed = BigInteger.ZERO;
        private int floor;
        private int frontier;
        private Direction direction;
        private Optional<Direction> completedSweep;

        private Projection(PlanningSnapshot planning, ArrayList<Request> pending,
                HallRequest target, long remaining, long observedAt) {
            source = planning.elevator();
            this.pending = pending;
            this.target = target;
            this.remaining = remaining;
            this.observedAt = observedAt;
            movement = BigInteger.valueOf(source.config().movementTimeMillis()).multiply(BigInteger.valueOf(1_000_000));
            dwell = BigInteger.valueOf(source.config().dwellTimeMillis()).multiply(BigInteger.valueOf(1_000_000));
            floor = source.currentFloor();
            direction = source.direction();
            frontier = source.committedFloor().orElse(0);
            completedSweep = planning.completedSweep();
        }

        private Estimate run() {
            if (source.phase() == ElevatorPhase.MOVING) {
                if (frontier == 0 || (long) (frontier - floor) * direction.floorDelta() <= 0) {
                    throw new IllegalArgumentException("Movimiento sin frontera compatible.");
                }
                advance(StepType.REMAINING_LEG, source.nextFloor().orElseThrow(),
                        BigInteger.valueOf(remaining));
                reachedFrontier();
            } else if (source.phase() == ElevatorPhase.DWELLING) {
                if (remaining > 0 && matches(target, direction)) { return pickup(); }
                add(StepType.WAIT, floor, BigInteger.valueOf(remaining));
            }

            // Cada iteración atiende trabajo o avanza a una parada/frontera.
            // La cota detecta capturas incoherentes sin depender del número de pisos.
            long budget = 8L * (pending.size() + 1L) + 16;
            while (budget-- > 0) {
                var served = compatible(direction);
                if (!served.isEmpty()) {
                    if (served.contains(target)) { return pickup(); }
                    pending.removeAll(served);
                    add(StepType.WAIT, floor, dwell);
                    continue;
                }

                if (frontier != 0) {
                    if ((long) (frontier - floor) * direction.floorDelta() <= 0) {
                        throw new IllegalArgumentException("Frontera incompatible con piso y dirección.");
                    }
                    completedSweep = Optional.empty();
                    int next = frontier;
                    long distance = Math.abs((long) frontier - floor);
                    for (var request : pending) {
                        long delta = (long) request.getFloor() - floor;
                        if (delta * direction.floorDelta() > 0 && Math.abs(delta) <= distance
                                && compatibleDirection(request, direction)) {
                            next = request.getFloor();
                            distance = Math.abs(delta);
                        }
                    }
                    advance(StepType.MOVEMENT, next, movement.multiply(BigInteger.valueOf(distance)));
                    reachedFrontier();
                    continue;
                }

                if (!compatible(direction.opposite()).isEmpty()) {
                    direction = direction.opposite();
                    continue;
                }

                int next = DirectionalAgenda.selectTarget(floor, pending, completedSweep).orElseThrow();
                if (next == floor) { throw new IllegalStateException("Solicitud local sin servicio compatible."); }
                direction = next > floor ? Direction.UP : Direction.DOWN;
                frontier = next;
                // Igual que ElevatorState.beginJourney: destinos internos amplían la frontera.
                for (var request : pending) {
                    if (request instanceof CabinRequest) {
                        frontier = direction == Direction.UP
                                ? Math.max(frontier, request.getFloor()) : Math.min(frontier, request.getFloor());
                    }
                }
                completedSweep = Optional.empty();
            }
            throw new IllegalStateException("No se pudo proyectar una agenda finita coherente.");
        }

        private List<Request> compatible(Direction serviceDirection) {
            return pending.stream().filter(r -> matches(r, serviceDirection)).toList();
        }

        private boolean matches(Request request, Direction serviceDirection) {
            return request.getFloor() == floor && compatibleDirection(request, serviceDirection);
        }

        private static boolean compatibleDirection(Request request, Direction serviceDirection) {
            return request instanceof CabinRequest
                    || ((HallRequest) request).getDirection() == serviceDirection;
        }

        private void reachedFrontier() {
            if (floor == frontier) {
                frontier = 0;
                completedSweep = Optional.of(direction);
            }
        }

        private void advance(StepType type, int to, BigInteger duration) {
            add(type, to, duration);
            floor = to;
        }

        private void add(StepType type, int to, BigInteger duration) {
            steps.add(new Step(type, floor, to, direction, duration));
            elapsed = elapsed.add(duration);
        }

        private Estimate pickup() {
            add(StepType.PICKUP, floor, BigInteger.ZERO);
            return new Estimate(Status.ESTIMATED, Optional.of(elapsed), observedAt, steps,
                    "Proyección de servicio compatible; excluye la parada posterior a recoger.");
        }
    }
}
