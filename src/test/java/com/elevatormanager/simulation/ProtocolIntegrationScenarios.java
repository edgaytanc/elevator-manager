package com.elevatormanager.simulation;

import com.elevatormanager.dispatch.HallCallSnapshot;
import com.elevatormanager.dispatch.HallRequestLedger;
import com.elevatormanager.model.Direction;
import com.elevatormanager.model.ElevatorPhase;
import com.elevatormanager.model.SimulationConfig;
import com.elevatormanager.model.SimulationState;
import com.elevatormanager.model.request.CabinRequest;
import com.elevatormanager.model.request.HallRequest;
import com.elevatormanager.protocol.Command;
import com.elevatormanager.protocol.ProtocolCodec;
import com.elevatormanager.protocol.ProtocolIdentities;
import com.elevatormanager.protocol.ProtocolReplyTracker;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import static com.elevatormanager.protocol.ProtocolMessage.*;
import com.elevatormanager.protocol.ProtocolMessage;

/** T65: correlación adversarial y actores reales; sin JUnit requerido por el main. */
public final class ProtocolIntegrationScenarios {
    private static final SimulationConfig CONFIG = new SimulationConfig(2, 8, 1000, 500);
    private static final ProtocolCodec CODEC = new ProtocolCodec(CONFIG);
    private static final Duration LIMIT = Duration.ofSeconds(6);
    private static final Header H = new Header(10, 0, 1);
    private ProtocolIntegrationScenarios() { }

    public static void ackAloneNeverCompletesTicket() {
        var t = tracker(); var f = t.register(control(H, ControlKind.STATUS)); feed(t, ack(H)); check(!f.isDone(), "ACK solo admite");
    }
    public static void statusRequiresStateAndCompletion() throws Exception {
        var t = tracker(); var f = t.register(control(H, ControlKind.STATUS)); feed(t, ack(H)); feed(t, state(H, 3, 1));
        check(!f.isDone(), "STATE sin terminal"); feed(t, done(H)); equal(1, get(f).state().orElseThrow().floor());
    }
    public static void completionBeforeAckIsIgnored() throws Exception {
        var t = tracker(); var f = t.register(control(H, ControlKind.STOP)); feed(t, done(H)); check(!f.isDone(), "Terminal prematuro");
        feed(t, ack(H)); feed(t, done(H)); get(f);
    }
    public static void stateBeforeAckCannotSatisfyStatus() throws Exception {
        var t = tracker(); var f = t.register(control(H, ControlKind.STATUS)); feed(t, state(H, 3, 1));
        check(t.state(1).isEmpty(), "STATE sin ACK no se instala"); feed(t, ack(H)); feed(t, done(H)); check(!f.isDone(), "Falta STATE posterior al ACK");
        feed(t, state(H, 3, 1)); feed(t, done(H)); get(f);
    }
    public static void wrongCommandIdCannotCompleteTicket() {
        var t = tracker(); var f = t.register(control(H, ControlKind.STOP)); Header other = new Header(11, 0, 1);
        feed(t, ack(other)); feed(t, done(other)); check(!f.isDone(), "ID exacto");
    }
    public static void wrongCabinCannotCompleteTicket() {
        var t = tracker(); var f = t.register(control(H, ControlKind.STOP)); Header other = new Header(10, 0, 2);
        feed(t, ack(other)); feed(t, done(other)); check(!f.isDone(), "Cabina exacta");
    }
    public static void wrongEpochCannotCompleteTicket() {
        var t = tracker(); var f = t.register(control(H, ControlKind.STOP)); Header other = new Header(10, 1, 1);
        feed(t, ack(other)); feed(t, done(other)); check(!f.isDone(), "Época exacta");
    }
    public static void duplicateTerminalCompletesOnlyOnce() throws Exception {
        var t = tracker(); var f = t.register(control(H, ControlKind.STOP)); var count = new java.util.concurrent.atomic.AtomicInteger();
        f.thenRun(count::incrementAndGet); feed(t, ack(H)); feed(t, done(H)); feed(t, done(H)); equal(1, count.get()); get(f);
    }
    public static void coalescedAckCannotAdmitControl() {
        var t = tracker(); var f = t.register(control(H, ControlKind.STOP));
        feed(t, new Ack(H, AckKind.COALESCED, Optional.of(id(1)))); feed(t, done(H)); check(!f.isDone(), "Un control no se coalesce");
    }
    public static void errorBeforeAckIsARealRejection() throws Exception {
        var t = tracker(); var f = t.register(control(H, ControlKind.STATUS)); feed(t, new ProtocolMessage.Error(H, ErrorCode.INVALID_STATE));
        check(failure(f) instanceof ProtocolReplyTracker.Rejected, "ERROR estructurado");
    }
    public static void failedTerminalFailsTicket() throws Exception {
        var t = tracker(); var f = t.register(control(H, ControlKind.STOP)); feed(t, ack(H));
        feed(t, new Terminal(H, TerminalKind.FAILED, Optional.empty(), Optional.of(ErrorCode.INTERNAL_ERROR))); failure(f);
    }
    public static void cancelledTerminalFailsTicket() throws Exception {
        var t = tracker(); var f = t.register(control(H, ControlKind.STOP)); feed(t, ack(H));
        feed(t, new Terminal(H, TerminalKind.CANCELLED, Optional.of(CancelReason.SUPERSEDED), Optional.empty())); failure(f);
    }
    public static void unknownErrorHeaderDoesNotRejectSomeoneElse() {
        var t = tracker(); var f = t.register(control(H, ControlKind.STOP));
        feed(t, new ProtocolMessage.Error(Header.UNKNOWN, ErrorCode.INVALID_FORMAT)); check(!f.isDone(), "Sin correlación no completar ajeno");
    }
    public static void malformedResponsesDoNotEscapeOrGenerateLoops() throws Exception {
        var t = tracker(); var f = t.register(control(H, ControlKind.STOP));
        for (String text : List.of("", "V2 ERROR", "V1 ACK 10 EPOCH 0 ELEVATOR 1 BOGUS")) { t.receive(text); }
        equal(3L, t.malformed()); feed(t, ack(H)); feed(t, done(H)); get(f);
    }
    public static void sameTicketCanBeReRegisteredWithoutNewExecution() throws Exception {
        var t = tracker(); var command = control(H, ControlKind.STOP); var first = t.register(command); var second = t.register(command);
        feed(t, ack(H)); feed(t, done(H)); equal(get(first), get(second));
    }
    public static void registeringChangedContentWithSameIdIsRejected() {
        var t = tracker(); t.register(control(H, ControlKind.STOP));
        illegal(() -> t.register(control(H, ControlKind.START)));
    }
    public static void cancellingPublicFutureDoesNotCancelPrivateTicket() throws Exception {
        var t = tracker(); var command = control(H, ControlKind.STOP); var publicCopy = t.register(command); publicCopy.cancel(true);
        feed(t, ack(H)); feed(t, done(H)); get(t.register(command));
    }
    public static void closeFailsOutstandingAndRefusesNewTickets() throws Exception {
        var t = tracker(); var f = t.register(control(H, ControlKind.STOP)); t.close(); failure(f);
        illegal(() -> t.register(control(new Header(11, 0, 1), ControlKind.STATUS)));
    }
    public static void twoCabinsRequireTheirOwnExactResponses() throws Exception {
        var t = tracker(); Header b = new Header(11, 0, 2); var aFuture = t.register(control(H, ControlKind.STOP));
        var bFuture = t.register(control(b, ControlKind.STOP)); feed(t, ack(H)); feed(t, done(H)); get(aFuture);
        check(!bFuture.isDone(), "N respuestas de la misma cabina no son N cabinas"); feed(t, ack(b)); feed(t, done(b)); get(bFuture);
    }
    public static void resetRequiresCorrelatedStateNotSpontaneousState() throws Exception {
        var t = tracker(); Header reset = new Header(20, 1, 1); t.adoptEpoch(1); var f = t.register(control(reset, ControlKind.RESET));
        feed(t, ack(reset)); feed(t, state(new Header(0, 1, 1), 4, 1)); feed(t, done(reset)); check(!f.isDone(), "Falta STATE del reset");
        feed(t, state(reset, 4, 1)); feed(t, done(reset)); get(f);
    }
    public static void resetCompletionAtAnotherFloorIsIgnored() throws Exception {
        var t = tracker(); Header reset = new Header(20, 1, 1); var f = t.register(control(reset, ControlKind.RESET));
        feed(t, ack(reset)); feed(t, state(reset, 4, 3)); feed(t, done(reset)); check(!f.isDone(), "No hay teleportación por terminal");
        feed(t, state(reset, 5, 1)); feed(t, done(reset)); get(f);
    }
    public static void oldEpochStateNeverOverwritesNewState() {
        var t = tracker(); t.adoptEpoch(1); feed(t, state(new Header(0, 1, 1), 4, 1));
        feed(t, state(new Header(0, 0, 1), Long.MAX_VALUE, 7)); equal(1, t.state(1).orElseThrow().floor());
    }
    public static void lowerRevisionStateDoesNotOverwriteNewerState() {
        var t = tracker(); feed(t, state(new Header(0, 0, 1), 5, 3)); feed(t, state(new Header(0, 0, 1), 4, 2));
        equal(3, t.state(1).orElseThrow().floor());
    }
    public static void equalRevisionMayPublishSmallerRemaining() {
        var t = tracker(); Header h = new Header(0, 0, 1); feed(t, moving(h, 5, 100)); feed(t, moving(h, 5, 80));
        equal(80L, t.state(1).orElseThrow().remainingMillis());
    }
    public static void unsolicitedCorrelatedStateIsIgnored() {
        var t = tracker(); feed(t, state(new Header(99, 0, 1), 5, 7)); check(t.state(1).isEmpty(), "No se solicitó ese ID");
    }
    public static void historicalReplyCanFinishWithoutInstallingOldState() throws Exception {
        var t = tracker(); var f = t.register(control(H, ControlKind.STATUS)); feed(t, ack(H)); t.adoptEpoch(1);
        feed(t, state(new Header(0, 1, 1), 1, 1)); feed(t, state(H, 100, 7)); feed(t, done(H));
        equal(7, get(f).state().orElseThrow().floor()); equal(1, t.state(1).orElseThrow().floor());
    }
    public static void replayAfterCompletedStatusCannotRollbackCache() throws Exception {
        var t = tracker(); var f = t.register(control(H, ControlKind.STATUS)); feed(t, ack(H)); feed(t, state(H, 3, 1)); feed(t, done(H)); get(f);
        feed(t, state(new Header(0, 0, 1), 4, 3)); feed(t, state(H, 3, 1)); equal(3, t.state(1).orElseThrow().floor());
    }
    public static void sessionIdentityIncludesDestinationAndEpoch() {
        var ids = new ProtocolIdentities(); var a = control(H, ControlKind.STATUS); check(ids.reserve(a), "Primera reserva");
        check(ids.conflicts(control(new Header(10, 0, 2), ControlKind.STATUS)), "Destinatario incluido");
        check(ids.conflicts(control(new Header(10, 1, 1), ControlKind.STATUS)), "Época incluida");
    }
    public static void rejectedReservationCannotRemoveAnotherOwner() {
        var ids = new ProtocolIdentities(); var a = control(H, ControlKind.STATUS); var b = control(H, ControlKind.STOP);
        ids.reserve(a); ids.release(b); check(ids.conflicts(b), "Liberación compara contenido completo");
    }
    public static void managerAckMeansAssignedNotServed() {
        var f = ledger(); f.ledger.onProtocol(ack(f.h)); equal(HallCallSnapshot.Status.ASSIGNED, f.status());
    }
    public static void managerArrivalCannotMeanService() {
        var f = ledger(); f.ledger.onProtocol(ack(f.h)); f.ledger.onProtocol(new Arrived(new Header(1, 0, 1), 3));
        equal(HallCallSnapshot.Status.ASSIGNED, f.status());
    }
    public static void managerRequiresCompatibleStopBeforeCompleted() {
        var f = ledger(); f.ledger.onProtocol(ack(f.h)); f.ledger.onProtocol(done(f.h)); equal(HallCallSnapshot.Status.ASSIGNED, f.status());
        f.ledger.onProtocol(new StoppedAt(new Header(1, 0, 1), 3, Direction.UP)); f.ledger.onProtocol(done(f.h)); equal(HallCallSnapshot.Status.SERVED, f.status());
    }
    public static void managerWrongDirectionStopCannotServeCall() {
        var f = ledger(); f.ledger.onProtocol(ack(f.h)); f.ledger.onProtocol(new StoppedAt(new Header(1, 0, 1), 3, Direction.DOWN));
        f.ledger.onProtocol(done(f.h)); equal(HallCallSnapshot.Status.ASSIGNED, f.status());
    }
    public static void managerOldAttemptResponseCannotAssignNewAttempt() {
        var f = ledger(); f.ledger.onProtocol(new ProtocolMessage.Error(f.h, ErrorCode.INVALID_STATE));
        var next = new Command.Pickup(11, 2, f.request); f.ledger.dispatched(f.request.getId(), next);
        f.ledger.onProtocol(ack(f.h)); equal(HallCallSnapshot.Status.DISPATCHED, f.status());
    }
    public static void managerOldEpochTerminalCannotServeNewEpoch() {
        var f = ledger(); f.ledger.onProtocol(ack(new Header(10, 1, 1))); equal(HallCallSnapshot.Status.DISPATCHED, f.status());
    }
    public static void managerDuplicateCompletionDoesNotReopenService() {
        var f = ledger(); f.ledger.onProtocol(ack(f.h)); f.ledger.onProtocol(new StoppedAt(new Header(1, 0, 1), 3, Direction.UP));
        f.ledger.onProtocol(done(f.h)); f.ledger.onProtocol(done(f.h)); equal(HallCallSnapshot.Status.SERVED, f.status());
    }
    public static void managerFailedServiceIsNotAutomaticallyReassigned() {
        var f = ledger(); f.ledger.onProtocol(ack(f.h)); f.ledger.onProtocol(new Terminal(f.h, TerminalKind.FAILED, Optional.empty(), Optional.of(ErrorCode.INTERNAL_ERROR)));
        equal(HallCallSnapshot.Status.UNCONFIRMED, f.status()); check(f.ledger.pending().isEmpty(), "No duplicar recogida incierta");
    }
    public static void statusRunsOnWorkerAndHasExactCorrelation() throws Exception {
        try (Rig r = new Rig()) { r.control(10, 0, ControlKind.STATUS); State s = r.replyState(10); equal(H, s.header()); equal(1, s.floor()); r.done(10); equal(r.w.threadName(), r.w.protocolReception().processedBy()); }
    }
    public static void statusWorksWhileSafelyStopped() throws Exception {
        try (Rig r = new Rig()) { r.control(10, 0, ControlKind.STOP); r.done(10); r.control(11, 0, ControlKind.STATUS); equal(1, r.replyState(11).floor()); r.done(11); }
    }
    public static void statusReplayPreservesOriginalSnapshot() throws Exception {
        try (Rig r = new Rig()) {
            r.control(10, 0, ControlKind.STATUS); State old = r.replyState(10); r.done(10);
            r.destination(20, 3); r.done(20); r.control(10, 0, ControlKind.STATUS); equal(old, r.replyState(10)); r.done(10); equal(3, r.w.snapshot().currentFloor());
        }
    }
    public static void controllerPortCannotSendPickup() throws Exception {
        try (Rig r = new Rig()) { r.w.controllerChannel().send(pickup(10), Instant.EPOCH); r.error(ErrorCode.UNAUTHORIZED_CONTROL); }
    }
    public static void managerPortCannotSendDestinationOrControl() throws Exception {
        try (Rig r = new Rig()) {
            r.w.managerChannel().send(destination(10, 3), Instant.EPOCH); r.error(ErrorCode.UNAUTHORIZED_CONTROL);
            r.w.managerChannel().control(CODEC.encode(control(H, ControlKind.STATUS))); r.error(ErrorCode.UNAUTHORIZED_CONTROL);
        }
    }
    public static void wrongRoleCannotReplayAnAuthorizedStatus() throws Exception {
        try (Rig r = new Rig()) {
            r.control(10, 0, ControlKind.STATUS); r.done(10);
            r.w.managerChannel().control(CODEC.encode(control(H, ControlKind.STATUS))); r.error(ErrorCode.UNAUTHORIZED_CONTROL);
        }
    }
    public static void sameIdWithChangedContentIsRejectedBeforeEpoch() throws Exception {
        try (Rig r = new Rig()) { r.control(10, 0, ControlKind.STATUS); r.done(10); r.control(10, 1, ControlKind.STATUS); r.error(ErrorCode.ID_CONFLICT); }
    }
    public static void sharedIdentityRegistryDetectsCrossCabinConflict() throws Exception {
        var registry = new ProtocolIdentities(); var a = new ElevatorWorker(1, CONFIG, 0); var b = new ElevatorWorker(2, CONFIG, 0);
        a.useProtocolIdentities(registry); b.useProtocolIdentities(registry); a.start(); b.start();
        try {
            a.controllerChannel().control(CODEC.encode(control(H, ControlKind.STATUS))); read(a, m -> m instanceof Terminal t && t.header().id() == 10);
            b.controllerChannel().control(CODEC.encode(control(new Header(10, 0, 2), ControlKind.STATUS)));
            read(b, m -> m instanceof ProtocolMessage.Error e && e.code() == ErrorCode.ID_CONFLICT);
        } finally { a.close(); b.close(); check(a.awaitTermination(LIMIT) && b.awaitTermination(LIMIT), "Join"); }
    }
    public static void strictResetRejectsSkippedOrEqualEpoch() throws Exception {
        try (Rig r = new Rig()) { r.control(10, 2, ControlKind.RESET); r.error(ErrorCode.FUTURE_EPOCH); r.control(11, 0, ControlKind.RESET); r.error(ErrorCode.INVALID_STATE); equal(0L, r.w.snapshot().epoch()); }
    }
    public static void resetPublishesFinalStateBeforeCompletion() throws Exception {
        try (Rig r = new Rig()) {
            r.destination(1, 3); r.done(1); r.control(10, 1, ControlKind.RESET); State state = r.replyState(10);
            equal(1, state.floor()); equal(Direction.UP, state.direction()); equal(1L, state.header().epoch()); r.done(10);
        }
    }
    public static void oldResetReplayCannotMoveOrChangeEpochAgain() throws Exception {
        try (Rig r = new Rig()) {
            r.control(10, 1, ControlKind.RESET); r.done(10); long revision = r.w.snapshot().revision();
            r.control(10, 1, ControlKind.RESET); r.done(10); equal(revision, r.w.snapshot().revision()); equal(1L, r.w.snapshot().epoch());
        }
    }
    public static void laterResetSupersedesReturnWithoutTeleportation() throws Exception {
        try (Rig r = new Rig()) {
            r.destination(1, 6); read(r.w, m -> m instanceof Move v && v.from() == 2);
            r.control(10, 1, ControlKind.RESET); read(r.w, m -> m instanceof Ack a && a.header().id() == 10);
            r.control(11, 2, ControlKind.RESET);
            Terminal old = (Terminal) read(r.w, m -> m instanceof Terminal t && t.header().id() == 10 && t.kind() == TerminalKind.CANCELLED);
            equal(Optional.of(CancelReason.SUPERSEDED), old.reason()); r.done(11); equal(2L, r.w.snapshot().epoch()); equal(1, r.w.snapshot().currentFloor());
        }
    }
    public static void strictStaleQueryCannotChangeCurrentState() throws Exception {
        try (Rig r = new Rig()) { r.control(10, 1, ControlKind.RESET); r.done(10); r.control(11, 0, ControlKind.STATUS); r.error(ErrorCode.STALE_EPOCH); equal(1L, r.w.snapshot().epoch()); }
    }
    public static void stopAndStartCommandsControlPhysicalWork() throws Exception {
        try (Rig r = new Rig()) {
            r.destination(1, 5); read(r.w, m -> m instanceof Move); r.control(10, 0, ControlKind.STOP);
            State stopped = r.replyState(10); r.done(10); equal(2, stopped.floor()); check(stopped.internal().contains(5), "Destino conservado");
            r.control(11, 0, ControlKind.START); r.done(11); r.done(1); equal(5, r.w.snapshot().currentFloor());
        }
    }
    public static void duplicateNewStopIsInvalidButReplayIsIdempotent() throws Exception {
        try (Rig r = new Rig()) { r.control(10, 0, ControlKind.STOP); r.done(10); r.control(11, 0, ControlKind.STOP); r.error(ErrorCode.INVALID_STATE); r.control(10, 0, ControlKind.STOP); r.done(10); }
    }
    public static void shutdownCommandTerminatesActualThread() throws Exception {
        try (Rig r = new Rig()) { r.control(10, 0, ControlKind.SHUTDOWN); r.done(10); check(r.w.awaitTermination(LIMIT), "SHUTDOWN no solo ACK"); }
    }
    public static void shutdownCanCloseAnotherEpoch() throws Exception {
        try (Rig r = new Rig()) { r.control(10, 999, ControlKind.SHUTDOWN); r.done(10); check(r.w.awaitTermination(LIMIT), "Cierre autorizado independiente de época"); }
    }
    public static void controllerStatusAndCacheUseParsedText() throws Exception {
        try (var owned = new OwnedController()) { var c = owned.c; get(c.start()); State status = get(c.queryStatus(1)); equal(1, status.floor()); equal(0L, status.header().epoch()); check(c.protocolState(1).isPresent(), "Caché pública de texto"); }
    }
    public static void statusDuringPauseDoesNotAdvanceClockOrRemaining() throws Exception {
        try (var owned = new OwnedController()) { var c = owned.c;
            get(c.start()); get(c.submit(new CabinRequest(id(1), 0, 6, Instant.EPOCH, 1)));
            await(() -> c.snapshot(1).phase() == ElevatorPhase.MOVING); get(c.pause());
            long clock = c.timeView().activeNanos(), remaining = c.snapshot(1).remainingNanos();
            State status = get(c.queryStatus(1)); equal(ElevatorPhase.HELD, status.phase()); equal(Optional.of(HoldReason.PAUSE), status.hold());
            equal(clock, c.timeView().activeNanos()); equal(remaining, c.snapshot(1).remainingNanos()); equal(SimulationState.PAUSED, c.state());
            get(c.resume()); equal(SimulationState.RUNNING, c.state());
        }
    }
    public static void resetFromPauseReturnsPhysicallyAndClearsCachedGeneration() throws Exception {
        try (var owned = new OwnedController()) { var c = owned.c;
            get(c.start()); get(c.submit(new CabinRequest(id(1), 0, 6, Instant.EPOCH, 1)));
            await(() -> c.snapshot(1).currentFloor() >= 3); get(c.pause()); get(c.queryStatus(1)); get(c.reset());
            equal(SimulationState.RESET, c.state()); equal(1L, c.epoch());
            State status = get(c.queryStatus(1)); equal(1, status.floor()); equal(1L, status.header().epoch()); check(status.internal().isEmpty(), "Sin servicio viejo");
        }
    }
    public static void resetWaitsForAllCabinReturnsNotJustAcks() throws Exception {
        try (var owned = new OwnedController()) { var c = owned.c;
            var mailbox = new ProtocolMailbox(); c.subscribeProtocol(mailbox); get(c.start());
            get(c.submit(new CabinRequest(id(1), 0, 6, Instant.EPOCH, 1))); await(() -> c.snapshot(1).currentFloor() >= 3);
            var reset = c.reset();
            long end = System.nanoTime() + LIMIT.toNanos(); boolean ack = false;
            while (!ack && System.nanoTime() < end) {
                var frame = mailbox.await(Duration.ofMillis(100));
                if (frame.isPresent()) { var m = CODEC.decodeResponse(frame.orElseThrow()).message().orElseThrow(); ack = m instanceof Ack && m.header().epoch() == 1; }
            }
            check(ack, "ACK de reset observado"); check(!reset.isDone(), "ACK no termina viaje de retorno"); get(reset);
            check(c.snapshots().stream().allMatch(s -> s.currentFloor() == 1 && s.epoch() == 1), "Barrera completa");
        }
    }
    public static void resetFromStoppedClearsPendingDestinations() throws Exception {
        try (var owned = new OwnedController()) { var c = owned.c;
            get(c.start()); get(c.submit(new CabinRequest(id(1), 0, 6, Instant.EPOCH, 1)));
            await(() -> c.snapshot(1).phase() == ElevatorPhase.MOVING); get(c.stop());
            check(get(c.queryStatus(1)).internal().contains(6), "Stop conserva servicio"); get(c.reset()); check(get(c.queryStatus(1)).internal().isEmpty(), "Reset elimina servicio");
        }
    }
    public static void controllerCloseInPauseJoinsResponseConsumer() throws Exception {
        var before = responseThreads(); var c = controller(); get(c.start()); get(c.pause()); get(c.queryStatus(1)); get(c.closeAsync());
        equal(SimulationState.CLOSED, c.state()); equal(before, responseThreads());
    }
    public static void queryingClosedControllerFailsWithoutHanging() throws Exception {
        var c = controller(); get(c.start()); get(c.closeAsync()); failure(c.queryStatus(1));
    }
    public static void invalidStatusTargetDoesNotAffectHealthySession() throws Exception {
        try (var owned = new OwnedController()) { var c = owned.c; get(c.start()); failure(c.queryStatus(99)); equal(1, get(c.queryStatus(1)).floor()); }
    }

    public static void queriesRacingCloseNeverLeaveFuturePending() throws Exception {
        try (var owned = new OwnedController()) {
            var c = owned.c; get(c.start());
            List<CompletableFuture<State>> queries = new ArrayList<>();
            for (int i = 0; i < 8; i++) { queries.add(c.queryStatus(1)); }
            get(c.closeAsync());
            for (var query : queries) {
                try { get(query); } catch (java.util.concurrent.ExecutionException expected) { }
                check(query.isDone(), "Consulta terminada con resultado o rechazo");
            }
        }
    }
    public static void stopQueuedBehindInitialStartStillStopsSession() throws Exception {
        try (var owned = new OwnedController()) {
            var c = owned.c; var start = c.start(); var stop = c.stop();
            get(start); equal(SimulationState.STOPPED, get(stop).state());
        }
    }

    private static final class OwnedController implements AutoCloseable {
        final SimulationController c = controller();
        @Override public void close() throws Exception {
            get(c.closeAsync()); check(c.awaitTermination(LIMIT), "Join del controlador completo");
        }
    }

    private static ProtocolReplyTracker tracker() { return new ProtocolReplyTracker(CONFIG, 0); }
    private static Control control(Header h, ControlKind k) { return new Control(h, k); }
    private static Ack ack(Header h) { return new Ack(h, AckKind.ACCEPTED, Optional.empty()); }
    private static Terminal done(Header h) { return new Terminal(h, TerminalKind.COMPLETED, Optional.empty(), Optional.empty()); }
    private static State state(Header h, long revision, int floor) {
        return new State(h, revision, floor, OptionalInt.empty(), Direction.UP, ElevatorPhase.IDLE,
                Optional.empty(), Optional.empty(), Mode.NORMAL, 0, List.of(), List.of());
    }
    private static State moving(Header h, long revision, long remaining) {
        return new State(h, revision, 2, OptionalInt.of(3), Direction.UP, ElevatorPhase.MOVING,
                Optional.empty(), Optional.empty(), Mode.NORMAL, remaining, List.of(), List.of());
    }
    private static void feed(ProtocolReplyTracker tracker, ProtocolMessage message) { tracker.receive(CODEC.encode(message)); }
    private static UUID id(long n) { return new UUID(0, n); }
    private static String destination(long n, int floor) { return CODEC.encode(new Destination(new Header(n, 0, 1), id(n), floor)); }
    private static String pickup(long n) { return CODEC.encode(new Pickup(new Header(n, 0, 1), id(n), 3, Direction.UP)); }
    private static SimulationController controller() { return new SimulationController(new SimulationConfig(2, 8, 150, 100), 0); }
    private static List<Long> responseThreads() {
        return Thread.getAllStackTraces().keySet().stream().filter(t -> t.isAlive() && t.getName().equals("protocol-responses")).map(Thread::getId).sorted().toList();
    }
    private static final class LedgerFixture {
        final HallRequest request = new HallRequest(id(1), 0, 3, Instant.EPOCH, Direction.UP);
        final HallRequestLedger ledger = new HallRequestLedger(CONFIG, 0);
        final Header h = H;
        LedgerFixture() { ledger.receive(request); ledger.dispatched(request.getId(), new Command.Pickup(10, 1, request)); }
        HallCallSnapshot.Status status() { return ledger.get(request.getId()).orElseThrow().status(); }
    }
    private static LedgerFixture ledger() { return new LedgerFixture(); }
    private static final class Rig implements AutoCloseable {
        final ElevatorWorker w = new ElevatorWorker(1, CONFIG, 0);
        Rig() { check(w.start(), "Arranque"); }
        void control(long n, long epoch, ControlKind kind) { check(w.controllerChannel().control(CODEC.encode(new Control(new Header(n, epoch, 1), kind))), "Control encolado"); }
        void destination(long n, int floor) { check(w.controllerChannel().send(ProtocolIntegrationScenarios.destination(n, floor), Instant.EPOCH), "Servicio encolado"); }
        State replyState(long n) throws Exception { return (State) read(w, m -> m instanceof State s && s.header().id() == n); }
        void done(long n) throws Exception { read(w, m -> m instanceof Terminal t && t.header().id() == n && t.kind() == TerminalKind.COMPLETED); }
        void error(ErrorCode code) throws Exception { read(w, m -> m instanceof ProtocolMessage.Error e && e.code() == code); }
        @Override public void close() throws Exception { w.close(); check(w.awaitTermination(LIMIT), "Join cabina"); check(w.failure().isEmpty(), "Cabina sana"); }
    }
    private static ProtocolMessage read(ElevatorWorker w, Predicate<ProtocolMessage> predicate) throws Exception {
        long end = System.nanoTime() + LIMIT.toNanos(); List<ProtocolMessage> seen = new ArrayList<>();
        while (System.nanoTime() < end) {
            var wire = w.awaitProtocolMessage(Duration.ofMillis(100));
            if (wire.isPresent()) {
                var p = CODEC.decodeResponse(wire.orElseThrow()); check(p.isSuccess(), "Respuesta malformada: " + wire.orElseThrow());
                ProtocolMessage m = p.message().orElseThrow(); seen.add(m); if (predicate.test(m)) { return m; }
            }
        }
        throw new AssertionError("Sin respuesta; vistas=" + seen);
    }
    private static <T> T get(CompletableFuture<T> f) throws Exception { return f.get(6, TimeUnit.SECONDS); }
    private static Throwable failure(CompletableFuture<?> f) throws Exception {
        try { get(f); throw new AssertionError("Se esperaba fallo"); } catch (java.util.concurrent.ExecutionException expected) { return expected.getCause(); }
    }
    private static void illegal(Runnable action) { try { action.run(); } catch (IllegalArgumentException | IllegalStateException expected) { return; } throw new AssertionError("Se esperaba rechazo"); }
    private static void await(BooleanSupplier condition) throws Exception {
        long end = System.nanoTime() + LIMIT.toNanos(); while (!condition.getAsBoolean()) { check(System.nanoTime() < end, "Plazo agotado"); Thread.sleep(2); }
    }
    private static void check(boolean value, String detail) { if (!value) { throw new AssertionError(detail); } }
    private static void equal(Object a, Object b) { if (!java.util.Objects.equals(a, b)) { throw new AssertionError("Esperado " + a + "; recibido " + b); } }
    public static void main(String[] args) throws Exception {
        int count = 0;
        for (var m : ProtocolIntegrationScenarios.class.getDeclaredMethods()) {
            if (java.lang.reflect.Modifier.isPublic(m.getModifiers()) && java.lang.reflect.Modifier.isStatic(m.getModifiers()) && m.getParameterCount() == 0) {
                try { m.invoke(null); count++; System.out.println("T65_OK " + m.getName()); }
                catch (java.lang.reflect.InvocationTargetException error) { throw new AssertionError("Fallo " + m.getName(), error.getCause()); }
            }
        }
        System.out.println("T65_RESULT scenarios=" + count + "; failures=0");
    }
}
