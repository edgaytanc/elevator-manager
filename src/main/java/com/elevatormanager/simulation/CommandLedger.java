package com.elevatormanager.simulation;

import com.elevatormanager.model.Direction;
import com.elevatormanager.model.ElevatorState;
import com.elevatormanager.model.request.CabinRequest;
import com.elevatormanager.model.request.HallRequest;
import com.elevatormanager.model.request.Request;
import com.elevatormanager.protocol.Command;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Registro privado del actor; no es thread-safe ni se comparte con productores. */
public final class CommandLedger {
    public enum Kind { ADDED, DUPLICATE, REJECTED }
    public enum RequestStatus { PENDING, SERVED }

    public record Decision(Kind kind, String detail, Optional<UUID> canonicalRequestId,
            Optional<RequestStatus> requestStatus, boolean replay) { }

    private record CommandKey(long epoch, long id) { }
    private record FunctionalKey(boolean cabin, int floor, Direction direction) { }
    private record History(Command command, Decision decision) { }
    private record KnownRequest(Request original, Canonical canonical) { }

    private static final class Canonical {
        private final Request request;
        private RequestStatus status = RequestStatus.PENDING;
        private Canonical(Request request) { this.request = request; }
    }

    private final ElevatorState state;
    private final Map<CommandKey, History> commands = new HashMap<>();
    private final Map<UUID, KnownRequest> requests = new HashMap<>();
    private final Map<FunctionalKey, Canonical> active = new HashMap<>();

    public CommandLedger(ElevatorState state) {
        this.state = Objects.requireNonNull(state, "state");
        if (!state.getPendingRequestsInAdmissionOrder().isEmpty()) {
            throw new IllegalArgumentException("El registro se crea con una agenda vacía.");
        }
    }

    /** Solo el actor ejecuta esta operación, serializada con movimiento y servicio. */
    public Decision admit(Command command) {
        Objects.requireNonNull(command, "command");
        // Mensajes ajenos o no autorizados no reservan identidades locales.
        if (command.elevatorId() != state.getId()) { return reject("Destinatario incorrecto."); }
        if (command instanceof Command.Reset) { return reject("Reset operativo pendiente de fase 6."); }
        if (command.epoch() != state.getEpoch()) { return reject("Época distinta de la vigente."); }
        if (!command.validateAgainst(state.getConfig()).isValid()) {
            return reject("Comando inválido para este edificio.");
        }
        var key = new CommandKey(command.epoch(), command.commandId());
        History previous = commands.get(key);
        Request request = requestOf(command);
        if (previous != null) {
            if (!previous.command().sameContent(command)) {
                return reject("commandId reutilizado con otro contenido.");
            }
            Decision old = previous.decision();
            Optional<RequestStatus> status = old.canonicalRequestId()
                    .map(id -> requests.get(id).canonical().status);
            return new Decision(old.kind(), old.detail(), old.canonicalRequestId(), status, true);
        }
        Decision decision;
        try {
            KnownRequest known = requests.get(request.getId());
            if (known != null) {
                if (!sameRequest(known.original(), request)) {
                    throw new IllegalArgumentException("UUID reutilizado con otro contenido.");
                }
                decision = decision(Kind.DUPLICATE, "Solicitud ya registrada.", known.canonical());
            } else {
                Canonical canonical = active.get(functionalKey(request));
                if (canonical != null) {
                    requests.put(request.getId(), new KnownRequest(request, canonical));
                    decision = decision(Kind.DUPLICATE, "Duplicado de una solicitud pendiente.", canonical);
                } else {
                    if (!state.addPendingRequest(request)) {
                        throw new IllegalStateException("Agenda y registro de solicitudes incoherentes.");
                    }
                    canonical = new Canonical(request);
                    requests.put(request.getId(), new KnownRequest(request, canonical));
                    active.put(functionalKey(request), canonical);
                    decision = decision(Kind.ADDED, "Incorporado a la agenda.", canonical);
                }
            }
        } catch (IllegalArgumentException exception) {
            decision = reject(exception.getMessage());
        }
        commands.put(key, new History(command, decision));
        return decision;
    }

    /** Marca únicamente las identidades canónicas que el modelo acaba de servir. */
    public void markServed(List<Request> served) {
        for (Request request : served) {
            KnownRequest known = requests.get(request.getId());
            if (known == null || !sameRequest(known.canonical().request, request)) {
                throw new IllegalStateException("Servicio sin solicitud canónica registrada.");
            }
        }
        for (Request request : served) {
            Canonical canonical = requests.get(request.getId()).canonical();
            canonical.status = RequestStatus.SERVED;
            active.remove(functionalKey(request), canonical);
        }
    }

    private static Decision decision(Kind kind, String detail, Canonical canonical) {
        return new Decision(kind, detail, Optional.of(canonical.request.getId()),
                Optional.of(canonical.status), false);
    }

    private static Decision reject(String detail) {
        return new Decision(Kind.REJECTED, detail, Optional.empty(), Optional.empty(), false);
    }

    private static Request requestOf(Command command) {
        if (command instanceof Command.Destination destination) { return destination.request(); }
        if (command instanceof Command.Pickup pickup) { return pickup.request(); }
        throw new IllegalArgumentException("Comando sin solicitud de servicio.");
    }

    private static FunctionalKey functionalKey(Request request) {
        return request instanceof HallRequest hall
                ? new FunctionalKey(false, request.getFloor(), hall.getDirection())
                : new FunctionalKey(true, request.getFloor(), null);
    }

    /** Request.equals solo compara UUID; aquí también se protege su contenido inmutable. */
    private static boolean sameRequest(Request first, Request second) {
        if (first.getClass() != second.getClass() || !first.getId().equals(second.getId())
                || first.getEpoch() != second.getEpoch() || first.getFloor() != second.getFloor()
                || !first.getCreatedAt().equals(second.getCreatedAt())) { return false; }
        if (first instanceof CabinRequest cabin) {
            return cabin.getElevatorId() == ((CabinRequest) second).getElevatorId();
        }
        return ((HallRequest) first).getDirection() == ((HallRequest) second).getDirection();
    }
}
