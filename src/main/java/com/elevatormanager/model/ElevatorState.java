package com.elevatormanager.model;

import com.elevatormanager.model.request.CabinRequest;
import com.elevatormanager.model.request.HallRequest;
import com.elevatormanager.model.request.Request;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Estado privado de una cabina. No es thread-safe: su futuro ElevatorWorker
 * será el único propietario; otros hilos leerán snapshots publicados.
 */
public final class ElevatorState {
    private final int id;
    private final SimulationConfig config;
    private long epoch;
    private long revision;
    private int currentFloor = 1;
    private Direction direction = Direction.UP;
    private ElevatorPhase phase = ElevatorPhase.IDLE;
    private final Map<UUID, CabinRequest> cabinRequests = new LinkedHashMap<>();
    private final Map<UUID, HallRequest> hallRequests = new LinkedHashMap<>();

    public ElevatorState(int id, SimulationConfig config, long epoch) {
        this.config = Objects.requireNonNull(config, "config");
        if (id < 1 || id > config.elevatorCount()) {
            throw new IllegalArgumentException("ID fuera de 1.." + config.elevatorCount());
        }
        if (epoch < 0) {
            throw new IllegalArgumentException("La época debe ser no negativa.");
        }
        this.id = id;
        this.epoch = epoch;
    }

    public int getId() { return id; }
    public SimulationConfig getConfig() { return config; }
    public long getEpoch() { return epoch; }
    public long getRevision() { return revision; }
    public int getCurrentFloor() { return currentFloor; }
    public Direction getDirection() { return direction; }
    public ElevatorPhase getPhase() { return phase; }

    /** Capturar solo desde el hilo propietario; referenceNanos usa reloj monotónico. */
    public ElevatorSnapshot snapshot(long referenceNanos) {
        return new ElevatorSnapshot(id, config, epoch, revision, currentFloor,
                java.util.OptionalInt.empty(), direction, phase,
                java.util.Optional.empty(), java.util.Optional.empty(),
                ElevatorSnapshot.Mode.NORMAL, 0L, 0L, referenceNanos,
                java.util.OptionalInt.empty(), getCabinRequests(), getHallRequests());
    }

    /** Copia de destinos internos, en orden de incorporación; no es orden de ruta. */
    public List<CabinRequest> getCabinRequests() {
        return List.copyOf(cabinRequests.values());
    }

    /** Copia de recogidas ya dirigidas a esta cabina por el manejador. */
    public List<HallRequest> getHallRequests() {
        return List.copyOf(hallRequests.values());
    }

    /**
     * Incorpora trabajo local validado. Devuelve false para repetición exacta
     * de una identidad pendiente; rechaza inconsistencias sin cambiar el estado.
     * El llamador debe comprobar previamente autorización global y asignación.
     */
    public boolean addPendingRequest(Request request) {
        Objects.requireNonNull(request, "request");
        if (!(request instanceof CabinRequest) && !(request instanceof HallRequest)) {
            throw new IllegalArgumentException("Tipo de solicitud no soportado.");
        }
        if (request.getEpoch() != epoch) {
            throw new IllegalArgumentException("La solicitud pertenece a otra época.");
        }
        ValidationResult validation = request.validateAgainst(config);
        if (!validation.isValid()) {
            throw new IllegalArgumentException("Solicitud inválida: " + validation.errors());
        }
        if (request instanceof CabinRequest cabin && cabin.getElevatorId() != id) {
            throw new IllegalArgumentException("El destino pertenece a otra cabina.");
        }
        Request previous = cabinRequests.get(request.getId());
        if (previous == null) {
            previous = hallRequests.get(request.getId());
        }
        if (previous != null) {
            if (!sameContent(previous, request)) {
                throw new IllegalArgumentException("UUID reutilizado con otro contenido.");
            }
            return false;
        }
        boolean duplicate = request instanceof CabinRequest
                ? cabinRequests.values().stream().anyMatch(r -> r.getFloor() == request.getFloor())
                : hallRequests.values().stream().anyMatch(r -> r.getFloor() == request.getFloor()
                        && r.getDirection() == ((HallRequest) request).getDirection());
        if (duplicate) {
            throw new IllegalArgumentException("Duplicado funcional: debe resolverse en la admisión.");
        }
        long nextRevision = Math.incrementExact(revision);
        if (request instanceof CabinRequest cabin) {
            cabinRequests.put(cabin.getId(), cabin);
        } else {
            hallRequests.put(request.getId(), (HallRequest) request);
        }
        revision = nextRevision;
        return true;
    }

    private static boolean sameContent(Request first, Request second) {
        if (first.getClass() != second.getClass() || first.getEpoch() != second.getEpoch()
                || first.getFloor() != second.getFloor()
                || !first.getCreatedAt().equals(second.getCreatedAt())) {
            return false;
        }
        if (first instanceof CabinRequest cabin) {
            return cabin.getElevatorId() == ((CabinRequest) second).getElevatorId();
        }
        return ((HallRequest) first).getDirection() == ((HallRequest) second).getDirection();
    }
}
