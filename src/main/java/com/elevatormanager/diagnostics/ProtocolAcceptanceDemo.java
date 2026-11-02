package com.elevatormanager.diagnostics;

import com.elevatormanager.dispatch.HallCallSnapshot;
import com.elevatormanager.dispatch.ManagerWorker;
import com.elevatormanager.model.Direction;
import com.elevatormanager.model.ElevatorPhase;
import com.elevatormanager.model.SimulationConfig;
import com.elevatormanager.model.SimulationState;
import com.elevatormanager.model.request.CabinRequest;
import com.elevatormanager.model.request.HallRequest;
import com.elevatormanager.protocol.ProtocolCodec;
import com.elevatormanager.protocol.ProtocolIdentities;
import com.elevatormanager.protocol.ProtocolMessage;
import com.elevatormanager.protocol.ProtocolReplyTracker;
import com.elevatormanager.simulation.ElevatorWorker;
import com.elevatormanager.simulation.ProtocolMailbox;
import com.elevatormanager.simulation.SimulationController;
import java.io.PrintStream;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import static com.elevatormanager.protocol.ProtocolMessage.*;

/** T66: traza de actores reales; cualquier incumplimiento termina con excepción. */
public final class ProtocolAcceptanceDemo {
    public enum Scenario {
        INVALID_UUID, INVALID_FLOOR, INVALID_DIRECTION, UNKNOWN_COMMAND,
        UNKNOWN_ELEVATOR, WRONG_RECIPIENT, EXTRA_ARGUMENT, UNSUPPORTED_VERSION,
        OVERSIZED_FRAME, WRONG_ROLE, ACTIVE_REPLAY, COMPLETED_REPLAY,
        CONFLICTING_REPLAY, COALESCED_SERVICE, RESET_IN_FLIGHT, QUEUED_BEFORE_RESET,
        LATE_OLD_FRAME, FUTURE_FRAME, STATUS_REPLAY, PARALLEL_CABINS,
        MANAGER_ROUND_TRIP, CONTROLLER_EXTRAS
    }

    private static final Duration LIMIT = Duration.ofSeconds(8);
    private static final SimulationConfig CONFIG = new SimulationConfig(2, 6, 80, 100);
    private ProtocolAcceptanceDemo() { }

    /** Sin argumentos ejecuta todos; un nombre de Scenario permite aislar evidencia. */
    public static void main(String[] args) throws Exception {
        if (args.length > 1) { throw new IllegalArgumentException("Uso: ProtocolAcceptanceDemo [SCENARIO]"); }
        Scenario[] cases = args.length == 0 ? Scenario.values()
                : new Scenario[] { Scenario.valueOf(args[0]) };
        for (Scenario scenario : cases) { run(scenario, System.out); }
        System.out.println("T66_PROTOCOL_OK; scenarios=" + cases.length + "; failures=0; threadsJoined=true");
    }

    /** Misma demostración invocable desde JUnit; no requiere bibliotecas de pruebas. */
    public static void run(Scenario scenario, PrintStream out) throws Exception {
        Objects.requireNonNull(scenario, "scenario"); Objects.requireNonNull(out, "out");
        out.println("T66_BEGIN " + scenario);
        switch (scenario) {
            case INVALID_UUID -> invalid(out, prefix(1, 0, 1) + "DESTINATION bad-uuid 3", ErrorCode.INVALID_ARGUMENT);
            case INVALID_FLOOR -> invalid(out, destination(1, 0, 1, 7), ErrorCode.INVALID_FLOOR);
            case INVALID_DIRECTION -> invalid(out, prefix(1, 0, 1) + "PICKUP " + uuid(1) + " 6 UP", ErrorCode.INVALID_DIRECTION);
            case UNKNOWN_COMMAND -> invalid(out, prefix(1, 0, 1) + "FLY", ErrorCode.UNKNOWN_COMMAND);
            case UNKNOWN_ELEVATOR -> invalid(out, destination(1, 0, 9, 3), ErrorCode.UNKNOWN_ELEVATOR);
            case WRONG_RECIPIENT -> invalid(out, destination(1, 0, 2, 3), ErrorCode.UNKNOWN_ELEVATOR);
            case EXTRA_ARGUMENT -> invalid(out, destination(1, 0, 1, 3) + " EXTRA", ErrorCode.INVALID_FORMAT);
            case UNSUPPORTED_VERSION -> invalid(out, destination(1, 0, 1, 3).replace("V1", "V2"), ErrorCode.UNSUPPORTED_VERSION);
            case OVERSIZED_FRAME -> invalid(out, "X".repeat(ProtocolCodec.MAX_FRAME_BYTES + 1), ErrorCode.MESSAGE_TOO_LONG);
            case WRONG_ROLE -> wrongRole(out);
            case ACTIVE_REPLAY -> activeReplay(out);
            case COMPLETED_REPLAY -> completedReplay(out);
            case CONFLICTING_REPLAY -> conflictingReplay(out);
            case COALESCED_SERVICE -> coalesced(out);
            case RESET_IN_FLIGHT -> resetInFlight(out);
            case QUEUED_BEFORE_RESET -> queuedBeforeReset(out);
            case LATE_OLD_FRAME -> lateOldFrame(out);
            case FUTURE_FRAME -> invalid(out, destination(1, 1, 1, 3), ErrorCode.FUTURE_EPOCH);
            case STATUS_REPLAY -> statusReplay(out);
            case PARALLEL_CABINS -> parallelCabins(out);
            case MANAGER_ROUND_TRIP -> managerRoundTrip(out);
            case CONTROLLER_EXTRAS -> controllerExtras(out);
        }
        // El marcador solo aparece después de cerrar y unir los actores del caso.
        out.println("T66_CASE_OK " + scenario);
    }

    private static void invalid(PrintStream out, String frame, ErrorCode code) throws Exception {
        try (Rig r = new Rig(1, CONFIG, out)) {
            long revision = r.worker.snapshot().revision();
            r.send(frame); var error = r.error(code);
            var syntax = r.codec.decodeCommandSyntax(frame);
            if (syntax.isSuccess()) { equal(syntax.message().orElseThrow().header(), error.header(), "Cabecera rechazada"); }
            equal(revision, r.worker.snapshot().revision(), "Error sin mutación");
            check(r.seen.stream().noneMatch(m -> m instanceof Ack), "Un rechazo no inventa ACK");
            r.send(destination(2, 0, 1, 2)); r.done(2);
            equal(2, r.worker.snapshot().currentFloor(), "Recuperación tras error");
        }
    }

    private static void wrongRole(PrintStream out) throws Exception {
        try (Rig r = new Rig(1, CONFIG, out)) {
            String frame = prefix(1, 0, 1) + "STATUS";
            out.println("T66_TX role=MANAGER " + frame);
            check(r.worker.managerChannel().control(frame), "Encolado de prueba adversarial");
            r.error(ErrorCode.UNAUTHORIZED_CONTROL);
            equal(0L, r.worker.snapshot().revision(), "Rol inválido sin efecto");
            r.control(2, 0, "STATUS"); r.done(2);
            r.sequence(2, Ack.class, State.class, Terminal.class);
        }
    }

    private static void activeReplay(PrintStream out) throws Exception {
        try (Rig r = new Rig(1, CONFIG, out)) {
            r.control(90, 0, "STOP"); r.done(90);
            String frame = destination(1, 0, 1, 3);
            r.send(frame); r.send(frame); // Ambos están pendientes antes de liberar al actor.
            r.control(91, 0, "START"); r.done(1);
            equal(2L, r.count(Ack.class, 1), "Dos ACK para el mismo ID");
            equal(1L, r.count(Terminal.class, 1), "Una finalización física");
            equal(1, r.worker.awaitService(LIMIT).orElseThrow().requests().size(), "Una solicitud física");
            check(r.worker.awaitService(Duration.ZERO).isEmpty(), "Sin segundo servicio");
            equal(1L, r.seen.stream().filter(m -> m instanceof StoppedAt s && s.floor() == 3).count(), "Una parada");
        }
    }

    private static void completedReplay(PrintStream out) throws Exception {
        try (Rig r = new Rig(1, CONFIG, out)) {
            String frame = destination(1, 0, 1, 2); r.send(frame); r.done(1);
            r.worker.awaitService(LIMIT).orElseThrow();
            int mark = r.seen.size(); r.send(frame); r.doneAfter(mark, 1);
            r.control(20, 0, "STATUS"); r.done(20); // Barrera de recepción, sin espera arbitraria.
            equal(2L, r.count(Ack.class, 1), "Replay con ACK histórico");
            equal(2L, r.count(Terminal.class, 1), "Replay con terminal histórico");
            check(r.seen.subList(mark, r.seen.size()).stream().noneMatch(m -> m instanceof Move || m instanceof StoppedAt), "Replay sin nuevo movimiento/parada");
            check(r.worker.awaitService(Duration.ZERO).isEmpty(), "Replay sin nuevo servicio");
        }
    }

    private static void conflictingReplay(PrintStream out) throws Exception {
        try (Rig r = new Rig(1, CONFIG, out)) {
            r.send(destination(1, 0, 1, 2)); r.done(1);
            r.send(destination(1, 0, 1, 5)); r.error(ErrorCode.ID_CONFLICT);
            equal(2, r.worker.snapshot().currentFloor(), "El conflicto no cambia destino");
            check(r.worker.snapshot().cabinRequests().isEmpty(), "Sin agenda nueva");
        }
    }

    private static void coalesced(PrintStream out) throws Exception {
        try (Rig r = new Rig(1, CONFIG, out)) {
            r.control(90, 0, "STOP"); r.done(90);
            r.send(destination(1, 0, 1, 3)); r.send(destination(2, 0, 1, 3));
            r.control(91, 0, "START"); r.done(1); r.done(2);
            Ack alias = (Ack) r.readAfter(0, m -> m instanceof Ack a && a.header().id() == 2);
            equal(AckKind.COALESCED, alias.kind(), "Alias coalescido");
            equal(uuid(1), alias.canonicalRequestId().orElseThrow(), "UUID canónico");
            equal(1L, r.seen.stream().filter(m -> m instanceof StoppedAt s && s.floor() == 3).count(), "Una parada para dos IDs");
            equal(1L, r.count(Terminal.class, 1), "Terminal propio del canónico");
            equal(1L, r.count(Terminal.class, 2), "Terminal propio del alias");
        }
    }

    private static void resetInFlight(PrintStream out) throws Exception {
        try (Rig r = new Rig(1, CONFIG, out)) {
            r.send(destination(1, 0, 1, 6));
            r.readAfter(0, m -> m instanceof Move v && v.from() == 3);
            r.control(10, 1, "RESET"); r.done(10);
            Terminal cancelled = (Terminal) r.readAfter(0, m -> m instanceof Terminal t && t.header().id() == 1);
            equal(TerminalKind.CANCELLED, cancelled.kind(), "Servicio en vuelo cancelado");
            equal(CancelReason.RESET, cancelled.reason().orElseThrow(), "Motivo reset");
            equal(0L, cancelled.header().epoch(), "Cancelación conserva época original");
            r.sequence(10, Ack.class, State.class, Terminal.class);
            State state = r.correlatedState(10); initial(state, 1);
            check(r.seen.stream().anyMatch(m -> m instanceof Move v && v.header().epoch() == 1 && v.direction() == Direction.DOWN), "Retorno físico textual");
            check(r.seen.stream().filter(m -> m instanceof Arrived a && a.header().epoch() == 1)
                    .map(m -> (Arrived) m).anyMatch(a -> a.floor() == 1), "Llegada real antes de finalizar reset");
            equal(1, r.worker.snapshot().currentFloor(), "Snapshot físico en origen");
        }
    }

    private static void queuedBeforeReset(PrintStream out) throws Exception {
        try (Rig r = new Rig(1, CONFIG, out)) {
            r.control(90, 0, "STOP"); r.done(90);
            r.send(destination(1, 0, 1, 6)); r.control(10, 1, "RESET"); r.done(10);
            var rejected = r.error(ErrorCode.STALE_EPOCH);
            equal(new Header(1, 0, 1), rejected.header(), "Inbox antiguo rechazado");
            equal(0L, r.count(Ack.class, 1), "Nunca se admitió el mensaje pendiente");
            equal(0L, r.count(Terminal.class, 1), "No cancelar lo nunca aceptado");
            initial(r.correlatedState(10), 1);
        }
    }

    private static void lateOldFrame(PrintStream out) throws Exception {
        try (Rig r = new Rig(1, CONFIG, out)) {
            r.control(10, 1, "RESET"); r.done(10);
            r.control(11, 1, "START"); r.done(11);
            r.send(destination(1, 0, 1, 6)); r.error(ErrorCode.STALE_EPOCH);
            r.control(12, 1, "STATUS"); r.done(12); initial(r.correlatedState(12), 1);
            r.send(destination(2, 1, 1, 2)); r.done(2);
            equal(2, r.worker.snapshot().currentFloor(), "La época nueva sigue operativa");
        }
    }

    private static void statusReplay(PrintStream out) throws Exception {
        try (Rig r = new Rig(1, CONFIG, out)) {
            r.control(10, 0, "STATUS"); r.done(10); State historical = r.correlatedState(10);
            r.send(destination(1, 0, 1, 3)); r.done(1);
            r.control(11, 0, "STATUS"); r.done(11); State current = r.correlatedState(11);
            int mark = r.seen.size(); r.control(10, 0, "STATUS"); r.doneAfter(mark, 10);
            State replay = (State) r.readAfter(mark, m -> m instanceof State s && s.header().id() == 10);
            equal(historical, replay, "STATUS repetido conserva su snapshot histórico");
            equal(3, r.worker.snapshot().currentFloor(), "Replay no rebobina cabina");
            // Se alimenta el consumidor real con las cadenas observadas, en su orden real.
            var tracker = new ProtocolReplyTracker(CONFIG, 0);
            try {
                tracker.register(new Control(new Header(10, 0, 1), ControlKind.STATUS));
                tracker.register(new Control(new Header(11, 0, 1), ControlKind.STATUS));
                for (ProtocolMessage message : r.seen) { tracker.receive(r.codec.encode(message)); }
                equal(current.revision(), tracker.state(1).orElseThrow().revision(), "Replay no rebobina caché");
                equal(3, tracker.state(1).orElseThrow().floor(), "Caché actual preservada");
            } finally { tracker.close(); }
        }
    }

    private static void parallelCabins(PrintStream out) throws Exception {
        var config = new SimulationConfig(2, 6, 150, 100);
        var identities = new ProtocolIdentities();
        try (Rig first = new Rig(1, config, out, identities); Rig second = new Rig(2, config, out, identities)) {
            first.send(destination(1, 0, 1, 6)); second.send(destination(2, 0, 2, 2));
            first.readAfter(0, m -> m instanceof Move); second.done(2);
            check(first.worker.snapshot().currentFloor() < 6, "El viaje corto concluye durante el largo");
            first.done(1);
            check(!first.worker.protocolReception().processedBy().equals(second.worker.protocolReception().processedBy()), "Hilos independientes");
            equal(6, first.worker.snapshot().currentFloor(), "Destino primera cabina");
            equal(2, second.worker.snapshot().currentFloor(), "Destino segunda cabina");
        }
    }

    private static void managerRoundTrip(PrintStream out) throws Exception {
        try (Rig first = new Rig(1, CONFIG, out); Rig second = new Rig(2, CONFIG, out)) {
            var manager = new ManagerWorker(CONFIG, 0, List.of(first.worker, second.worker), new AtomicLong(40)::incrementAndGet);
            try {
                check(manager.start(), "Arranque manejador");
                var request = new HallRequest(uuid(50), 0, 3, Instant.EPOCH, Direction.DOWN);
                out.println("T66_INPUT HALL " + request.getId() + " FLOOR 3 DOWN");
                check(manager.submit(request), "Entrada externa");
                var command = manager.awaitResult(LIMIT).orElseThrow().command().orElseThrow();
                Rig receiver = command.elevatorId() == 1 ? first : second;
                out.println("T66_TX role=MANAGER " + receiver.codec.encode(com.elevatormanager.protocol.ProtocolCommands.fromDomain(command)));
                receiver.done(command.commandId());
                await(() -> manager.call(request.getId()).map(c -> c.status() == HallCallSnapshot.Status.SERVED).orElse(false));
                var reconstructed = receiver.worker.awaitResult(LIMIT).orElseThrow().command();
                check(command != reconstructed && command.sameContent(reconstructed), "Reconstrucción desde texto");
                check(receiver.seen.stream().anyMatch(m -> m instanceof StoppedAt s && s.floor() == 3 && s.service() == Direction.DOWN), "Parada en el sentido solicitado");
                out.println("T66_MANAGER status=SERVED; reconstructed=true; nativeCommandShared=false");
            } finally { manager.close(); check(manager.awaitTermination(LIMIT), "Join manejador"); }
        }
    }

    private static void controllerExtras(PrintStream out) throws Exception {
        var controller = new SimulationController(new SimulationConfig(2, 6, 180, 100), 0);
        var mailbox = new ProtocolMailbox(); controller.subscribeProtocol(mailbox);
        try {
            controller.start().get(8, TimeUnit.SECONDS);
            controller.submit(new CabinRequest(uuid(60), 0, 6, Instant.EPOCH, 1)).get(8, TimeUnit.SECONDS);
            await(() -> controller.snapshot(1).phase() == ElevatorPhase.MOVING);
            controller.pause().get(8, TimeUnit.SECONDS);
            long frozen = controller.timeView().activeNanos();
            State paused = controller.queryStatus(1).get(8, TimeUnit.SECONDS);
            equal(ElevatorPhase.HELD, paused.phase(), "Pausa conserva tramo");
            equal(HoldReason.PAUSE, paused.hold().orElseThrow(), "Motivo de suspensión");
            equal(frozen, controller.timeView().activeNanos(), "STATUS no libera reloj");
            equal(paused.remainingMillis(), controller.queryStatus(1).get(8, TimeUnit.SECONDS).remainingMillis(), "Restante congelado");
            out.println("T66_EXTRAS pause=true; statusWhilePaused=true; clockFrozen=true");
            controller.resume().get(8, TimeUnit.SECONDS);
            controller.stop().get(8, TimeUnit.SECONDS);
            equal(SimulationState.STOPPED, controller.state(), "Stop confirmado");
            controller.start().get(8, TimeUnit.SECONDS);
            controller.reset().get(8, TimeUnit.SECONDS);
            equal(SimulationState.RESET, controller.state(), "Reset global confirmado");
            for (int id = 1; id <= 2; id++) { initial(controller.queryStatus(id).get(8, TimeUnit.SECONDS), 1); }
            controller.start().get(8, TimeUnit.SECONDS);
            controller.submit(new CabinRequest(uuid(61), 1, 2, Instant.EPOCH, 2)).get(8, TimeUnit.SECONDS);
            await(() -> controller.snapshot(2).currentFloor() == 2);
            out.println("T66_EXTRAS resume=true; stopStart=true; resetAll=true; restartNewEpoch=true");
        } finally {
            controller.closeAsync().get(8, TimeUnit.SECONDS);
            check(controller.awaitTermination(LIMIT), "Join controlador y consumidor");
            drain(mailbox, new ProtocolCodec(controller.config()), out);
        }
        equal(SimulationState.CLOSED, controller.state(), "Cierre confirmado");
        check(controller.failure().isEmpty(), "Sesión sin fallo");
        out.println("T66_EXTRAS state=CLOSED; responsesJoined=true");
    }

    private static void drain(ProtocolMailbox mailbox, ProtocolCodec codec, PrintStream out) throws Exception {
        while (true) {
            var text = mailbox.await(Duration.ZERO); if (text.isEmpty()) { return; }
            check(codec.decodeResponse(text.orElseThrow()).isSuccess(), "Respuesta V1 válida");
            out.println("T66_RX " + text.orElseThrow());
        }
    }

    private static void initial(State state, long epoch) {
        equal(epoch, state.header().epoch(), "Época vigente"); equal(1, state.floor(), "Origen");
        equal(Direction.UP, state.direction(), "Dirección inicial"); equal(ElevatorPhase.IDLE, state.phase(), "Reposo");
        equal(Mode.NORMAL, state.mode(), "Retorno concluido");
        check(state.next().isEmpty() && state.internal().isEmpty() && state.external().isEmpty(), "Sin tramo ni pendientes");
    }
    private static String prefix(long id, long epoch, int cabin) { return "V1 CMD " + id + " EPOCH " + epoch + " ELEVATOR " + cabin + " "; }
    private static String destination(long id, long epoch, int cabin, int floor) { return prefix(id, epoch, cabin) + "DESTINATION " + uuid(id) + " " + floor; }
    private static UUID uuid(long id) { return new UUID(0, id); }
    private static void check(boolean value, String detail) { if (!value) { throw new IllegalStateException(detail); } }
    private static void equal(Object expected, Object actual, String detail) { check(Objects.equals(expected, actual), detail + ": esperado=" + expected + "; recibido=" + actual); }
    private static void await(BooleanSupplier condition) throws Exception {
        long end = System.nanoTime() + LIMIT.toNanos();
        while (!condition.getAsBoolean()) { check(System.nanoTime() < end, "Plazo agotado"); TimeUnit.MILLISECONDS.sleep(2); }
    }

    private static final class Rig implements AutoCloseable {
        final ElevatorWorker worker;
        final ProtocolCodec codec;
        final PrintStream out;
        final List<ProtocolMessage> seen = new ArrayList<>();
        Rig(int cabin, SimulationConfig config, PrintStream out) { this(cabin, config, out, new ProtocolIdentities()); }
        Rig(int cabin, SimulationConfig config, PrintStream out, ProtocolIdentities identities) {
            this.out = out; codec = new ProtocolCodec(config); worker = new ElevatorWorker(cabin, config, 0);
            worker.useProtocolIdentities(identities);
            check(worker.start(), "Arranque cabina");
        }
        void send(String text) {
            out.println("T66_TX role=CONTROLLER " + text);
            check(worker.controllerChannel().send(text, Instant.EPOCH), "Encolado textual");
        }
        void control(long id, long epoch, String verb) {
            String text = prefix(id, epoch, worker.snapshot().id()) + verb;
            out.println("T66_TX role=CONTROLLER " + text);
            check(worker.controllerChannel().control(text), "Encolado prioritario textual");
        }
        ProtocolMessage readAfter(int mark, Predicate<ProtocolMessage> filter) throws Exception {
            for (int i = mark; i < seen.size(); i++) { if (filter.test(seen.get(i))) { return seen.get(i); } }
            long end = System.nanoTime() + LIMIT.toNanos();
            while (System.nanoTime() < end) {
                var frame = worker.awaitProtocolMessage(Duration.ofMillis(100));
                if (frame.isEmpty()) { continue; }
                out.println("T66_RX " + frame.orElseThrow());
                var decoded = codec.decodeResponse(frame.orElseThrow());
                check(decoded.isSuccess(), "Respuesta inválida del receptor: " + frame.orElseThrow());
                ProtocolMessage message = decoded.message().orElseThrow(); seen.add(message);
                if (filter.test(message)) { return message; }
            }
            throw new IllegalStateException("No llegó la respuesta requerida; cabina=" + worker.snapshot().id());
        }
        void done(long id) throws Exception { doneAfter(0, id); }
        void doneAfter(int mark, long id) throws Exception {
            readAfter(mark, m -> m instanceof Terminal t && t.header().id() == id && t.kind() == TerminalKind.COMPLETED);
        }
        ProtocolMessage.Error error(ErrorCode code) throws Exception { return (ProtocolMessage.Error) readAfter(0, m -> m instanceof ProtocolMessage.Error e && e.code() == code); }
        State correlatedState(long id) throws Exception { return (State) readAfter(0, m -> m instanceof State s && s.header().id() == id); }
        long count(Class<?> family, long id) { return seen.stream().filter(m -> family.isInstance(m) && m.header().id() == id).count(); }
        @SafeVarargs final void sequence(long id, Class<? extends ProtocolMessage>... families) {
            int cursor = -1;
            for (Class<?> family : families) {
                int next = -1;
                for (int i = cursor + 1; i < seen.size(); i++) {
                    ProtocolMessage m = seen.get(i);
                    if (m.header().id() == id && family.isInstance(m)) { next = i; break; }
                }
                check(next >= 0, "Orden ACK/STATE/terminal de " + id); cursor = next;
            }
        }
        @Override public void close() throws Exception {
            try {
                if (worker.lifecycle() == ElevatorWorker.Lifecycle.RUNNING) {
                    long shutdownId = 900L + worker.snapshot().id();
                    control(shutdownId, worker.snapshot().epoch(), "SHUTDOWN"); done(shutdownId);
                }
            } finally { worker.close(); check(worker.awaitTermination(LIMIT), "Join real de cabina"); }
            check(worker.failure().isEmpty(), "Cabina sin fallo");
            // La salida final del actor también debe ser texto válido.
            while (true) {
                var frame = worker.awaitProtocolMessage(Duration.ZERO); if (frame.isEmpty()) { break; }
                check(codec.decodeResponse(frame.orElseThrow()).isSuccess(), "Respuesta final válida");
                out.println("T66_RX " + frame.orElseThrow());
            }
        }
    }
}
