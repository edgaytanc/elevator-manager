package com.elevatormanager.dispatch;

import com.elevatormanager.model.ElevatorPhase;
import com.elevatormanager.model.SimulationConfig;
import com.elevatormanager.model.request.HallRequest;
import com.elevatormanager.protocol.Command;
import com.elevatormanager.simulation.ExecutionEvent;
import com.elevatormanager.simulation.PlanningSnapshot;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import static com.elevatormanager.dispatch.HallCallSnapshot.Status.*;

/** T32: registro global privado del actor. No comparte estado mutable con productores. */
public final class HallRequestLedger {
    public enum AdmissionKind { NEW, DUPLICATE, REJECTED }
    public record Admission(AdmissionKind kind, Optional<UUID> canonicalId, String detail) { }
    private record Key(int floor, com.elevatormanager.model.Direction direction) { }
    private record AttemptKey(int elevatorId, long commandId) { }
    private record Identity(HallRequest original, Entry entry) { }
    private static final class Entry {
        private final HallRequest request;
        private HallCallSnapshot.Status status = RECEIVED;
        private Command.Pickup command;
        private UUID receiverCanonical;
        private int attempts;
        private String detail = "Recibida; pendiente de despacho.";
        private final Set<Integer> rejectedBy = new HashSet<>();
        private Entry(HallRequest request) { this.request = request; }
    }

    private final SimulationConfig config;
    private final long epoch;
    private final Map<UUID, Identity> identities = new LinkedHashMap<>();
    private final Map<UUID, Entry> canonical = new LinkedHashMap<>();
    private final Map<Key, Entry> active = new HashMap<>();
    private final Map<AttemptKey, Entry> attempts = new HashMap<>();
    private final Map<Integer, Long> lastSequence = new HashMap<>();

    public HallRequestLedger(SimulationConfig config, long epoch) {
        this.config = Objects.requireNonNull(config, "config");
        if (epoch < 0) { throw new IllegalArgumentException("Época negativa."); }
        this.epoch = epoch;
    }

    public Admission receive(HallRequest request) {
        Objects.requireNonNull(request, "request");
        if (request.getEpoch() != epoch || !request.validateAgainst(config).isValid()) {
            return new Admission(AdmissionKind.REJECTED, Optional.empty(), "Solicitud o época inválida.");
        }
        var previous = identities.get(request.getId());
        if (previous != null) {
            if (!PlanningSnapshot.sameRequest(previous.original(), request)) {
                return new Admission(AdmissionKind.REJECTED, Optional.empty(), "UUID reutilizado con otro contenido.");
            }
            return new Admission(AdmissionKind.DUPLICATE, Optional.of(previous.entry().request.getId()),
                    "Identidad ya registrada; se conserva su estado histórico.");
        }
        var key = key(request);
        var entry = active.get(key);
        var kind = entry == null ? AdmissionKind.NEW : AdmissionKind.DUPLICATE;
        if (entry == null) {
            entry = new Entry(request);
            canonical.put(request.getId(), entry);
            active.put(key, entry);
        }
        identities.put(request.getId(), new Identity(request, entry));
        return new Admission(kind, Optional.of(entry.request.getId()),
                kind == AdmissionKind.NEW ? "Nueva llamada canónica." : "Alias de una llamada activa del mismo piso y sentido.");
    }

    public List<HallRequest> pending() {
        return canonical.values().stream().filter(e -> e.status == RECEIVED).map(e -> e.request).toList();
    }

    public Set<Integer> rejectedCabins(UUID id) { return Set.copyOf(entry(id).rejectedBy); }

    /** Reserva provisional de carga hasta que la agenda confirme la admisión. */
    public Set<Integer> awaitingAdmissionCabins() {
        var ids = new HashSet<Integer>();
        for (var e : canonical.values()) {
            if (e.status == DISPATCHED) { ids.add(e.command.elevatorId()); }
        }
        return Set.copyOf(ids);
    }

    public void pendingReason(UUID id, String detail) {
        var e = entry(id);
        if (e.status == RECEIVED) { e.detail = detail; }
    }

    public void dispatched(UUID id, Command.Pickup command) {
        var e = entry(id);
        if (e.status != RECEIVED || !PlanningSnapshot.sameRequest(e.request, command.request())
                || command.epoch() != epoch || command.elevatorId() > config.elevatorCount()) {
            throw new IllegalStateException("Despacho incompatible con la llamada pendiente.");
        }
        var key = new AttemptKey(command.elevatorId(), command.commandId());
        if (attempts.containsKey(key)) { throw new IllegalStateException("ID de comando reutilizado."); }
        attempts.put(key, e);
        e.command = command;
        e.receiverCanonical = null;
        e.attempts++;
        e.status = DISPATCHED;
        e.detail = "Encolada; falta confirmación de admisión.";
    }

    /** Consume el flujo ordenado de la suscripción, nunca interpreta una llegada como servicio. */
    public void onEvent(ExecutionEvent event) {
        Objects.requireNonNull(event, "event");
        if (event.epoch() != epoch || !event.snapshot().config().equals(config)
                || event.sequence() <= lastSequence.getOrDefault(event.elevatorId(), 0L)) { return; }
        lastSequence.put(event.elevatorId(), event.sequence());
        if (event.type() == ExecutionEvent.Type.WORKER_TERMINATED
                || event.type() == ExecutionEvent.Type.WORKER_FAILED) {
            for (var e : canonical.values()) {
                if ((e.status == DISPATCHED || e.status == ASSIGNED)
                        && e.command.elevatorId() == event.elevatorId()) {
                    if (event.type() == ExecutionEvent.Type.WORKER_FAILED) {
                        e.status = UNCONFIRMED;
                        e.detail = "Cabina fallida: servicio no confirmado; no reasignar automáticamente.";
                    } else {
                        retry(e, "Cabina terminada sin servicio confirmado.");
                    }
                }
            }
            return;
        }
        if (!(event.command().orElse(null) instanceof Command.Pickup command)) { return; }
        var e = attempts.get(new AttemptKey(event.elevatorId(), command.commandId()));
        if (e == null || (e.status != DISPATCHED && e.status != ASSIGNED)
                || !command.sameContent(e.command) || command.epoch() != epoch
                || command.elevatorId() != event.elevatorId()) { return; }
        switch (event.type()) {
            case COMMAND_ACCEPTED, COMMAND_DUPLICATE, COMMAND_REPLAYED -> {
                if (event.canonicalRequestId().isEmpty()) {
                    if (event.type() == ExecutionEvent.Type.COMMAND_REPLAYED) {
                        e.rejectedBy.add(command.elevatorId()); retry(e, "Retransmisión de una decisión rechazada.");
                    }
                } else {
                    e.receiverCanonical = event.canonicalRequestId().orElseThrow();
                    e.status = ASSIGNED;
                    e.detail = "Admisión confirmada; falta servicio compatible.";
                }
            }
            case COMMAND_REJECTED -> {
                e.rejectedBy.add(command.elevatorId());
                retry(e, "Cabina rechazó el comando: " + event.detail());
            }
            case COMMAND_COMPLETED -> {
                if (e.status != ASSIGNED || e.receiverCanonical == null
                        || !event.canonicalRequestId().equals(Optional.of(e.receiverCanonical))) { return; }
                var s = event.snapshot();
                if (s.phase() != ElevatorPhase.DWELLING || s.currentFloor() != e.request.getFloor()
                        || s.direction() != e.request.getDirection()) {
                    e.status = UNCONFIRMED;
                    e.detail = "Confirmación sin evidencia de parada compatible; requiere revisión.";
                } else {
                    e.status = SERVED;
                    e.detail = "Servicio confirmado por COMMAND_COMPLETED en parada compatible.";
                    active.remove(key(e.request), e);
                }
            }
            default -> { }
        }
    }

    /** Un cierre del manejador no cancela físicamente comandos ya enviados a una cabina. */
    public void close() {
        for (var e : canonical.values()) {
            if (e.status == RECEIVED) {
                e.status = CANCELLED;
                e.detail = "Manejador cerrado antes de enviar la llamada.";
                active.remove(key(e.request), e);
            } else if (e.status == DISPATCHED || e.status == ASSIGNED) {
                e.status = UNCONFIRMED;
                e.detail = "Manejador cerrado; la cabina puede continuar. No se confirma ni cancela su servicio.";
            }
        }
    }

    public Optional<HallCallSnapshot> get(UUID id) {
        var identity = identities.get(id);
        return identity == null ? Optional.empty() : Optional.of(snapshot(identity));
    }

    public Map<UUID, HallCallSnapshot> snapshot() {
        var result = new LinkedHashMap<UUID, HallCallSnapshot>();
        identities.forEach((id, identity) -> result.put(id, snapshot(identity)));
        return Map.copyOf(result);
    }

    private static HallCallSnapshot snapshot(Identity identity) {
        var e = identity.entry();
        return new HallCallSnapshot(identity.original(), e.request.getId(), e.status,
                Optional.ofNullable(e.command), e.attempts, e.detail);
    }

    private Entry entry(UUID id) {
        var identity = identities.get(id);
        if (identity == null) { throw new IllegalArgumentException("Solicitud desconocida."); }
        return identity.entry();
    }

    private static void retry(Entry e, String detail) {
        e.status = RECEIVED;
        e.command = null;
        e.receiverCanonical = null;
        e.detail = detail;
    }

    private static Key key(HallRequest request) { return new Key(request.getFloor(), request.getDirection()); }
}
