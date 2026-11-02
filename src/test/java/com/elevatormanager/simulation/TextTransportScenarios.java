package com.elevatormanager.simulation;

import com.elevatormanager.dispatch.HallCallSnapshot;
import com.elevatormanager.dispatch.ManagerWorker;
import com.elevatormanager.model.Direction;
import com.elevatormanager.model.ElevatorPhase;
import com.elevatormanager.model.SimulationConfig;
import com.elevatormanager.model.SimulationState;
import com.elevatormanager.model.request.CabinRequest;
import com.elevatormanager.model.request.HallRequest;
import com.elevatormanager.protocol.Command;
import com.elevatormanager.protocol.ProtocolCodec;
import com.elevatormanager.protocol.ProtocolCommands;
import com.elevatormanager.protocol.ProtocolMessage;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import static com.elevatormanager.protocol.ProtocolMessage.*;

/** Actor real: el cuerpo textual determina la agenda y las respuestas vienen de su hilo. */
public final class TextTransportScenarios {
    private static final Duration LIMIT = Duration.ofSeconds(6);
    private static final Instant DATE = Instant.parse("2026-11-02T00:00:00Z");
    private TextTransportScenarios() { }

    public static void rawDestinationIsParsedOnActor() throws Exception {
        try (Rig r = new Rig()) {
            check(r.w.submitText(destination(1, 3)), "Encolado");
            r.terminal(1, TerminalKind.COMPLETED);
            equal(3, r.w.snapshot().currentFloor());
            equal(r.w.threadName(), r.w.protocolReception().processedBy());
            equal(1L, r.w.protocolReception().frames());
        }
    }
    public static void rawPickupUsesTransmittedDirectionAndUuid() throws Exception {
        try (Rig r = new Rig()) {
            String line = pickup(1, 3, Direction.DOWN);
            check(r.w.submitText(line), "Encolado");
            var result = r.w.awaitResult(LIMIT).orElseThrow();
            var command = (Command.Pickup) result.command();
            equal(id(1), command.request().getId()); equal(Direction.DOWN, command.request().getDirection());
            r.terminal(1, TerminalKind.COMPLETED); equal(Direction.DOWN, r.w.snapshot().direction());
        }
    }
    public static void changedTextChangesPhysicalDestination() throws Exception {
        try (Rig r = new Rig()) {
            String changed = destination(1, 3).replace(" 3", " 4");
            r.w.submitText(changed); r.terminal(1, TerminalKind.COMPLETED);
            equal(4, r.w.snapshot().currentFloor());
            var result = (Command.Destination) r.w.awaitResult(LIMIT).orElseThrow().command();
            equal(4, result.request().getFloor());
        }
    }
    public static void compatibilitySubmitReconstructsDistinctObjects() throws Exception {
        try (Rig r = new Rig()) {
            var original = new Command.Destination(1, 1, new CabinRequest(id(1), 0, 2, DATE, 1));
            check(r.w.submit(original), "Encolado compatible");
            Command reconstructed = r.w.awaitResult(LIMIT).orElseThrow().command();
            check(reconstructed != original, "No se pasa el Command original");
            check(((Command.Destination) reconstructed).request() != original.request(), "Nueva solicitud desde texto");
            check(original.sameContent(reconstructed), "Contenido conservado");
        }
    }
    public static void explicitTimestampContextSurvives() throws Exception {
        try (Rig r = new Rig()) {
            r.w.submitText(destination(1, 2), DATE);
            var c = (Command.Destination) r.w.awaitResult(LIMIT).orElseThrow().command();
            equal(DATE, c.request().getCreatedAt());
        }
    }
    public static void defaultTimestampIsStableNotWallClock() throws Exception {
        try (Rig r = new Rig(10_000)) {
            r.w.submitText(destination(1, 3)); r.w.submitText(destination(1, 3));
            var first = r.w.awaitResult(LIMIT).orElseThrow(); var second = r.w.awaitResult(LIMIT).orElseThrow();
            equal(Instant.EPOCH, ((Command.Destination) first.command()).request().getCreatedAt());
            check(second.replay(), "Reintento no inventa otra fecha");
        }
    }
    public static void ackPrecedesPhysicalCompletion() throws Exception {
        try (Rig r = new Rig(300)) {
            r.w.submitText(destination(1, 3));
            r.read(m -> m instanceof Ack); check(r.w.snapshot().currentFloor() < 3, "ACK no es llegada");
            r.terminal(1, TerminalKind.COMPLETED); equal(3, r.w.snapshot().currentFloor());
        }
    }
    public static void moveArrivalAndStopAreTextualEvents() throws Exception {
        try (Rig r = new Rig()) {
            r.w.submitText(destination(1, 2)); r.terminal(1, TerminalKind.COMPLETED);
            check(r.seen.stream().anyMatch(m -> m instanceof Move v && v.from() == 1 && v.to() == 2), "MOVE real");
            check(r.seen.stream().anyMatch(m -> m instanceof Arrived v && v.floor() == 2), "ARRIVED real");
            check(r.seen.stream().anyMatch(m -> m instanceof StoppedAt v && v.floor() == 2), "Parada real");
        }
    }
    public static void directionChangesAreReportedInText() throws Exception {
        try (Rig r = new Rig()) {
            r.w.submitText(pickup(1, 3, Direction.DOWN)); r.terminal(1, TerminalKind.COMPLETED);
            check(r.seen.stream().anyMatch(m -> m instanceof DirectionChanged v
                    && v.from() == Direction.UP && v.to() == Direction.DOWN), "Inversión publicada");
        }
    }
    public static void malformedCommandDoesNotMutateAndWorkerRecovers() throws Exception {
        try (Rig r = new Rig()) {
            long revision = r.w.snapshot().revision();
            r.w.submitText(prefix(1) + "FLY"); r.error(ErrorCode.UNKNOWN_COMMAND);
            equal(revision, r.w.snapshot().revision());
            r.w.submitText(destination(2, 2)); r.terminal(2, TerminalKind.COMPLETED);
            check(r.w.failure().isEmpty(), "Trabajador vivo");
        }
    }
    public static void invalidFloorIsRejectedBeforeMotion() throws Exception {
        try (Rig r = new Rig()) {
            r.w.submitText(destination(1, 9)); r.error(ErrorCode.INVALID_FLOOR);
            equal(1, r.w.snapshot().currentFloor()); check(r.w.snapshot().cabinRequests().isEmpty(), "Sin agenda parcial");
        }
    }
    public static void impossibleDirectionIsRejected() throws Exception {
        try (Rig r = new Rig()) { r.w.submitText(pickup(1, 8, Direction.UP)); r.error(ErrorCode.INVALID_DIRECTION); }
    }
    public static void unknownConfiguredElevatorIsRejected() throws Exception {
        try (Rig r = new Rig()) {
            r.w.submitText(destination(1, 3).replace("ELEVATOR 1", "ELEVATOR 9"));
            var error = r.error(ErrorCode.UNKNOWN_ELEVATOR); equal(9, error.header().elevatorId());
        }
    }
    public static void wrongExistingTargetCannotMoveThisCabin() throws Exception {
        try (Rig r = new Rig()) {
            r.w.submitText(destination(1, 3).replace("ELEVATOR 1", "ELEVATOR 2"));
            r.error(ErrorCode.UNKNOWN_ELEVATOR); equal(1, r.w.snapshot().currentFloor());
        }
    }
    public static void staleAndFutureServiceEpochsAreRejected() throws Exception {
        try (Rig r = new Rig()) {
            r.w.submitText(destination(1, 3).replace("EPOCH 0", "EPOCH 1")); r.error(ErrorCode.FUTURE_EPOCH);
            r.w.requestResetText("V1 CMD 10 EPOCH 1 ELEVATOR 1 RESET").get(6, TimeUnit.SECONDS);
            check(r.w.resume(), "Liberar después de reset");
            r.w.submitText(destination(2, 3)); r.error(ErrorCode.STALE_EPOCH);
        }
    }
    public static void badUuidDoesNotCreateRequest() throws Exception {
        try (Rig r = new Rig()) {
            r.w.submitText(prefix(1) + "DESTINATION 123 3"); r.error(ErrorCode.INVALID_ARGUMENT);
            check(r.w.snapshot().cabinRequests().isEmpty(), "Sin UUID inventado");
        }
    }
    public static void ackInjectedIntoCommandQueueIsNotExecuted() throws Exception {
        try (Rig r = new Rig()) {
            r.w.submitText("V1 ACK 1 EPOCH 0 ELEVATOR 1 ACCEPTED"); r.error(ErrorCode.INVALID_FORMAT);
            equal(0L, r.w.snapshot().revision());
        }
    }
    public static void extraTokensDoNotPartiallyExecute() throws Exception {
        try (Rig r = new Rig()) {
            r.w.submitText(destination(1, 3) + " EXTRA"); r.error(ErrorCode.INVALID_FORMAT);
            check(r.w.snapshot().cabinRequests().isEmpty(), "Rechazo atómico");
        }
    }
    public static void oversizedFrameIsRejectedOnActor() throws Exception {
        try (Rig r = new Rig()) {
            r.w.submitText("X".repeat(ProtocolCodec.MAX_FRAME_BYTES + 1)); r.error(ErrorCode.MESSAGE_TOO_LONG);
            equal(r.w.threadName(), r.w.protocolReception().processedBy());
            r.w.submitText(destination(2, 2)); r.terminal(2, TerminalKind.COMPLETED);
        }
    }
    public static void framingNormalizationIsAppliedAtReceiver() throws Exception {
        try (Rig r = new Rig()) {
            r.w.submitText(destination(1, 2).replace(" ", "   ") + "\r\n"); r.terminal(1, TerminalKind.COMPLETED);
            equal(2, r.w.snapshot().currentFloor());
        }
    }
    public static void malformedBurstCannotKillActor() throws Exception {
        try (Rig r = new Rig()) {
            for (int i = 1; i <= 100; i++) { r.w.submitText(prefix(i) + "BOGUS"); }
            int errors = 0;
            while (errors < 100) { r.read(m -> m instanceof ProtocolMessage.Error); errors++; }
            r.w.submitText(destination(101, 2)); r.terminal(101, TerminalKind.COMPLETED);
            equal(100L, r.w.protocolReception().malformed());
        }
    }
    public static void conditionalSendChecksSnapshotBeforeEnqueue() throws Exception {
        try (Rig r = new Rig(10_000)) {
            var initial = r.w.planningSnapshot();
            equal(ElevatorWorker.Submission.ENQUEUED,
                    r.w.submitTextIfCurrent(destination(1, 3), DATE, initial));
            r.w.awaitResult(LIMIT).orElseThrow();
            equal(ElevatorWorker.Submission.STALE,
                    r.w.submitTextIfCurrent(destination(2, 4), DATE, initial));
            equal(1L, r.w.protocolReception().frames());
        }
    }
    public static void compatibleConditionalSendAlsoSerializes() throws Exception {
        try (Rig r = new Rig(10_000)) {
            var original = new Command.Destination(1, 1, new CabinRequest(id(1), 0, 3, DATE, 1));
            equal(ElevatorWorker.Submission.ENQUEUED, r.w.submitIfCurrent(original, r.w.planningSnapshot()));
            var parsed = r.w.awaitResult(LIMIT).orElseThrow().command();
            check(parsed != original && original.sameContent(parsed), "Ruta compatible también cruza como texto");
        }
    }
    public static void enqueueIsNotAdmissionWhileStopped() throws Exception {
        try (Rig r = new Rig()) {
            r.w.requestStop().get(6, TimeUnit.SECONDS);
            check(r.w.submitText(destination(1, 3)), "Solo encolado");
            equal(0L, r.w.protocolReception().frames());
            check(r.w.awaitProtocolMessage(Duration.ZERO).isEmpty(), "Sin ACK antes de leer");
            check(r.w.resume(), "Reanudar"); r.terminal(1, TerminalKind.COMPLETED);
        }
    }
    public static void serviceFifoIsPreserved() throws Exception {
        try (Rig r = new Rig(10_000)) {
            for (int i = 1; i <= 5; i++) { r.w.submitText(destination(i, i + 1)); }
            List<Long> accepted = new ArrayList<>();
            for (int i = 0; i < 5; i++) { accepted.add(r.read(m -> m instanceof Ack).header().id()); }
            equal(List.of(1L, 2L, 3L, 4L, 5L), accepted);
        }
    }
    public static void aliasesHaveCanonicalAckAndEachOwnTerminal() throws Exception {
        try (Rig r = new Rig(100)) {
            r.w.submitText(destination(1, 2)); r.w.submitText(destination(2, 2));
            Ack first = (Ack) r.read(m -> m instanceof Ack); Ack second = (Ack) r.read(m -> m instanceof Ack);
            equal(AckKind.ACCEPTED, first.kind()); equal(AckKind.COALESCED, second.kind());
            equal(Optional.of(id(1)), second.canonicalRequestId());
            r.terminal(1, TerminalKind.COMPLETED); r.terminal(2, TerminalKind.COMPLETED);
        }
    }
    public static void sameCommandReplayDoesNotDuplicatePhysicalService() throws Exception {
        try (Rig r = new Rig(100)) {
            String frame = destination(1, 2); r.w.submitText(frame); r.w.submitText(frame);
            r.read(m -> m instanceof Ack); r.read(m -> m instanceof Ack);
            r.terminal(1, TerminalKind.COMPLETED);
            var service = r.w.awaitService(LIMIT).orElseThrow(); equal(1, service.requests().size());
            check(r.w.awaitService(Duration.ZERO).isEmpty(), "Un servicio físico");
        }
    }
    public static void servedReplayReturnsKnownTerminalWithoutAnotherStop() throws Exception {
        try (Rig r = new Rig()) {
            String frame = destination(1, 1); r.w.submitText(frame); r.terminal(1, TerminalKind.COMPLETED);
            r.w.awaitService(LIMIT).orElseThrow();
            r.w.submitText(frame); r.read(m -> m instanceof Ack); r.terminal(1, TerminalKind.COMPLETED);
            check(r.w.awaitService(Duration.ZERO).isEmpty(), "Replay textual sin servicio nuevo");
        }
    }
    public static void conflictingIdCannotReplaceDestination() throws Exception {
        try (Rig r = new Rig(100)) {
            r.w.submitText(destination(1, 2)); r.w.submitText(destination(1, 3)); r.error(ErrorCode.ID_CONFLICT);
            r.terminal(1, TerminalKind.COMPLETED); equal(2, r.w.snapshot().currentFloor());
        }
    }
    public static void resetQueueAlsoCarriesAndParsesText() throws Exception {
        try (Rig r = new Rig()) {
            r.w.requestResetText("V1 CMD 10 EPOCH 1 ELEVATOR 1 RESET").get(6, TimeUnit.SECONDS);
            r.terminal(10, TerminalKind.COMPLETED); equal(1L, r.w.snapshot().epoch());
            equal(r.w.threadName(), r.w.protocolReception().processedBy());
        }
    }
    public static void resetCancelsActiveCommandBeforeReturnCompletes() throws Exception {
        try (Rig r = new Rig(100)) {
            r.w.submitText(destination(1, 5)); r.read(m -> m instanceof Move);
            r.w.requestResetText("V1 CMD 10 EPOCH 1 ELEVATOR 1 RESET").get(6, TimeUnit.SECONDS);
            Terminal cancelled = r.terminal(1, TerminalKind.CANCELLED);
            equal(Optional.of(CancelReason.RESET), cancelled.reason()); equal(0L, cancelled.header().epoch());
            r.terminal(10, TerminalKind.COMPLETED); equal(1, r.w.snapshot().currentFloor());
        }
    }
    public static void resetDiscardsQueuedTextBeforeAdmission() throws Exception {
        try (Rig r = new Rig()) {
            r.w.requestStop().get(6, TimeUnit.SECONDS); r.w.submitText(destination(1, 5));
            r.w.requestResetText("V1 CMD 10 EPOCH 1 ELEVATOR 1 RESET").get(6, TimeUnit.SECONDS);
            r.error(ErrorCode.STALE_EPOCH); r.terminal(10, TerminalKind.COMPLETED);
            equal(1, r.w.snapshot().currentFloor()); check(r.w.snapshot().cabinRequests().isEmpty(), "Inbox viejo no revive");
        }
    }
    public static void malformedResetFailsFutureWithoutAdoptingEpoch() throws Exception {
        try (Rig r = new Rig()) {
            expectFutureFailure(r.w.requestResetText("V1 CMD 10 EPOCH 1 ELEVATOR 1 RESET EXTRA"));
            r.error(ErrorCode.INVALID_FORMAT); equal(0L, r.w.snapshot().epoch());
        }
    }
    public static void serviceCannotEnterThroughResetPriority() throws Exception {
        try (Rig r = new Rig()) {
            expectFutureFailure(r.w.requestResetText(destination(1, 3)));
            r.error(ErrorCode.INVALID_STATE); check(r.w.snapshot().cabinRequests().isEmpty(), "No admitir servicio por control");
        }
    }
    public static void resetCannotEnterThroughOrdinaryServiceQueue() throws Exception {
        try (Rig r = new Rig()) {
            r.w.submitText("V1 CMD 10 EPOCH 1 ELEVATOR 1 RESET"); r.error(ErrorCode.INVALID_STATE);
            equal(0L, r.w.snapshot().epoch());
        }
    }
    public static void controlsPendingT65AreRejectedWithoutFakeAck() throws Exception {
        try (Rig r = new Rig()) {
            for (String verb : List.of("STOP", "START", "PAUSE", "RESUME", "STATUS", "SHUTDOWN")) {
                r.w.submitText(prefix(1) + verb); r.error(ErrorCode.INVALID_STATE);
            }
            check(r.w.failure().isEmpty(), "Actor sigue vivo");
        }
    }
    public static void textualObserversDoNotStealNativeEvents() throws Exception {
        try (Rig r = new Rig()) {
            ProtocolMailbox first = new ProtocolMailbox(), second = new ProtocolMailbox();
            r.w.subscribeProtocol(first); r.w.subscribeProtocol(second);
            r.w.submitText(destination(1, 1)); r.terminal(1, TerminalKind.COMPLETED);
            equal(first.await(LIMIT), second.await(LIMIT));
            equal(ExecutionEvent.Type.COMMAND_ACCEPTED, r.w.awaitEvent(LIMIT).orElseThrow().type());
            equal(ElevatorWorker.Outcome.ACCEPTED, r.w.awaitResult(LIMIT).orElseThrow().outcome());
        }
    }
    public static void unsubscribeStopsFutureFrames() throws Exception {
        try (Rig r = new Rig()) {
            ProtocolMailbox mailbox = new ProtocolMailbox(); r.w.subscribeProtocol(mailbox); r.w.unsubscribeProtocol(mailbox);
            r.w.submitText(destination(1, 1)); r.terminal(1, TerminalKind.COMPLETED);
            check(mailbox.await(Duration.ZERO).isEmpty(), "Sin frames nuevos");
        }
    }
    public static void closeCancelsAcceptedPendingText() throws Exception {
        try (Rig r = new Rig(10_000)) {
            r.w.submitText(destination(1, 5)); r.read(m -> m instanceof Ack);
            r.w.close(); check(r.w.awaitTermination(LIMIT), "Cierre real");
            Terminal cancelled = r.terminal(1, TerminalKind.CANCELLED); equal(Optional.of(CancelReason.SHUTDOWN), cancelled.reason());
        }
    }
    public static void closedOrNewWorkerRefusesToEnqueue() throws Exception {
        var config = new SimulationConfig(1, 8, 25, 25); var w = new ElevatorWorker(1, config, 0);
        check(!w.submitText(destination(1, 3)), "NEW no recibe"); w.close();
        check(!w.submitText(destination(1, 3)), "TERMINATED no recibe");
        equal(0L, w.protocolReception().frames());
    }
    public static void concurrentProducersKeepEveryCorrelation() throws Exception {
        try (Rig r = new Rig(10_000)) {
            var go = new CountDownLatch(1); var done = new CountDownLatch(4); var failure = new AtomicReference<Throwable>();
            List<Thread> producers = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                final int index = i;
                Thread t = new Thread(() -> {
                    try { go.await(); for (int j = 1; j <= 6; j++) { check(r.w.submitText(destination(index * 6 + j, 5)), "Encolado concurrente"); } }
                    catch (Throwable error) { failure.set(error); }
                    finally { done.countDown(); }
                }, "t64-producer-" + i);
                producers.add(t); t.start();
            }
            go.countDown(); check(done.await(6, TimeUnit.SECONDS), "Productores finalizan");
            for (Thread t : producers) { t.join(1000); check(!t.isAlive(), "Productor unido"); }
            if (failure.get() != null) { throw new AssertionError(failure.get()); }
            var ids = new HashSet<Long>();
            for (int i = 0; i < 24; i++) { ids.add(r.read(m -> m instanceof Ack).header().id()); }
            equal(24, ids.size()); equal(24L, r.w.protocolReception().frames());
        }
    }
    public static void mailboxProtectsEdtAndNegativeTimeout() throws Exception {
        var mailbox = new ProtocolMailbox();
        try { mailbox.await(Duration.ofNanos(-1)); throw new AssertionError("Timeout inválido aceptado"); }
        catch (IllegalArgumentException expected) { }
        var failure = new AtomicReference<Throwable>();
        javax.swing.SwingUtilities.invokeAndWait(() -> {
            try { mailbox.await(Duration.ofMillis(1)); failure.set(new AssertionError("EDT bloqueado")); }
            catch (IllegalStateException expected) { }
            catch (InterruptedException error) { failure.set(error); }
        });
        if (failure.get() != null) { throw new AssertionError(failure.get()); }
        check(mailbox.await(Duration.ZERO).isEmpty(), "Sondeo vacío");
    }
    public static void managerDispatchUsesTextAndStillConfirmsService() throws Exception {
        SimulationConfig config = new SimulationConfig(2, 8, 25, 80);
        var a = new ElevatorWorker(1, config, 0); var b = new ElevatorWorker(2, config, 0);
        var ids = new AtomicLong(); var manager = new ManagerWorker(config, 0, List.of(a, b), ids::incrementAndGet);
        try {
            a.start(); b.start(); manager.start(); var request = new HallRequest(id(50), 0, 3, DATE, Direction.UP);
            check(manager.submit(request), "Manejador recibe llamada");
            var result = manager.awaitResult(LIMIT).orElseThrow(); equal(ManagerWorker.Outcome.DISPATCHED, result.outcome());
            var sender = result.command().orElseThrow(); var receiver = sender.elevatorId() == 1 ? a : b;
            var reconstructed = receiver.awaitResult(LIMIT).orElseThrow().command();
            check(reconstructed != sender && reconstructed.sameContent(sender), "Texto real en despacho normal");
            await(() -> manager.call(request.getId()).map(c -> c.status() == HallCallSnapshot.Status.SERVED).orElse(false));
            equal(receiver.threadName(), receiver.protocolReception().processedBy());
        } finally {
            manager.close(); check(manager.awaitTermination(LIMIT), "Manejador cerrado");
            a.close(); b.close(); check(a.awaitTermination(LIMIT) && b.awaitTermination(LIMIT), "Cabinas cerradas");
        }
    }
    public static void controllerDestinationsAndResetUseText() throws Exception {
        var c = new SimulationController(new SimulationConfig(2, 8, 25, 80), 0); var mailbox = new ProtocolMailbox();
        var codec = new ProtocolCodec(c.config()); c.subscribeProtocol(mailbox);
        try {
            c.start().get(6, TimeUnit.SECONDS);
            equal(SimulationController.Outcome.ENQUEUED, c.submit(new CabinRequest(id(70), 0, 3, DATE, 2)).get(6, TimeUnit.SECONDS).outcome());
            await(() -> c.snapshot(2).currentFloor() == 3 && c.snapshot(2).phase() == ElevatorPhase.DWELLING);
            equal(2L, c.protocolReceptions().get(1).frames()); // START y destino, ambos textuales.
            check(codec.decodeResponse(mailbox.await(LIMIT).orElseThrow()).message().orElseThrow() instanceof Ack, "ACK de destino normal");
            c.reset().get(6, TimeUnit.SECONDS); equal(SimulationState.RESET, c.state());
            check(c.protocolReceptions().stream().allMatch(s -> s.frames() >= 1), "Cada reset cruza como texto");
        } finally { c.closeAsync().get(6, TimeUnit.SECONDS); }
    }
    public static void pauseStopAndResumeRetainTextualWork() throws Exception {
        var c = new SimulationController(new SimulationConfig(1, 8, 150, 80), 0);
        try {
            c.start().get(6, TimeUnit.SECONDS); c.submit(new CabinRequest(id(80), 0, 5, DATE, 1)).get(6, TimeUnit.SECONDS);
            await(() -> c.snapshot(1).phase() == ElevatorPhase.MOVING);
            c.pause().get(6, TimeUnit.SECONDS); equal(SimulationState.PAUSED, c.state());
            long remaining = c.snapshot(1).remainingNanos(); equal(1, c.snapshot(1).cabinRequests().size());
            equal(remaining, c.snapshot(1).remainingNanos());
            c.resume().get(6, TimeUnit.SECONDS); c.stop().get(6, TimeUnit.SECONDS);
            equal(SimulationState.STOPPED, c.state()); equal(1, c.snapshot(1).cabinRequests().size());
            c.start().get(6, TimeUnit.SECONDS); await(() -> c.snapshot(1).currentFloor() == 5);
            check(c.protocolReceptions().get(0).frames() >= 6, "Controles textuales sin reenviar el destino.");
        } finally { c.closeAsync().get(6, TimeUnit.SECONDS); }
    }
    public static void syntaxCodecNeverGrantsAdmission() {
        var codec = new ProtocolCodec(new SimulationConfig(1, 2, 1, 1));
        var message = new Destination(new Header(1, 0, 99), id(1), 8);
        String text = codec.encodeSyntax(message);
        check(codec.decodeCommandSyntax(text).isSuccess(), "Forma válida sin edificio");
        equal(ErrorCode.UNKNOWN_ELEVATOR, codec.decodeCommand(text).failure().orElseThrow().code());
        try { codec.encode(message); throw new AssertionError("Codificador estricto omitió edificio"); }
        catch (ProtocolCodec.EncodingException expected) { equal(ErrorCode.UNKNOWN_ELEVATOR, expected.failure().code()); }
    }

    public static void closeTerminatesAcceptedResetInText() throws Exception {
        try (Rig r = new Rig(200)) {
            r.w.submitText(destination(1, 5));
            r.read(m -> m instanceof Move v && v.from() == 2);
            var reset = r.w.requestResetText("V1 CMD 10 EPOCH 1 ELEVATOR 1 RESET");
            r.read(m -> m instanceof Ack a && a.header().id() == 10);
            r.w.close(); check(r.w.awaitTermination(LIMIT), "Join durante retorno");
            Terminal terminal = r.terminal(10, TerminalKind.CANCELLED);
            equal(Optional.of(CancelReason.SHUTDOWN), terminal.reason()); expectFutureFailure(reset);
        }
    }
    public static void closeRejectsQueuedTextWithoutFakeAcceptance() throws Exception {
        try (Rig r = new Rig()) {
            r.w.requestStop().get(6, TimeUnit.SECONDS); r.w.submitText(destination(1, 5));
            r.w.close(); check(r.w.awaitTermination(LIMIT), "Join con inbox pendiente");
            r.error(ErrorCode.WORKER_UNAVAILABLE);
            check(r.seen.stream().noneMatch(m -> m instanceof Ack), "Nunca se admitió la entrada");
        }
    }

    private static String prefix(long id) { return "V1 CMD " + id + " EPOCH 0 ELEVATOR 1 "; }
    private static String destination(long id, int floor) { return prefix(id) + "DESTINATION " + id(id) + " " + floor; }
    private static String pickup(long id, int floor, Direction direction) { return prefix(id) + "PICKUP " + id(id) + " " + floor + " " + direction; }
    private static UUID id(long value) { return new UUID(0, value); }
    private static void check(boolean condition, String detail) { if (!condition) { throw new AssertionError(detail); } }
    private static void equal(Object expected, Object actual) {
        if (!java.util.Objects.equals(expected, actual)) { throw new AssertionError("Esperado " + expected + "; recibido " + actual); }
    }
    private static void await(BooleanSupplier condition) throws Exception {
        long end = System.nanoTime() + LIMIT.toNanos();
        while (!condition.getAsBoolean()) { check(System.nanoTime() < end, "Plazo agotado"); Thread.sleep(2); }
    }
    private static void expectFutureFailure(java.util.concurrent.CompletableFuture<?> future) throws Exception {
        try { future.get(6, TimeUnit.SECONDS); throw new AssertionError("Se esperaba rechazo"); }
        catch (java.util.concurrent.ExecutionException expected) { }
    }
    private static final class Rig implements AutoCloseable {
        final SimulationConfig config;
        final ElevatorWorker w;
        final ProtocolCodec codec;
        final List<ProtocolMessage> seen = new ArrayList<>();
        Rig() { this(25); }
        Rig(long move) {
            config = new SimulationConfig(2, 8, move, 150); codec = new ProtocolCodec(config);
            w = new ElevatorWorker(1, config, 0); check(w.start(), "Arranque");
        }
        ProtocolMessage read(Predicate<ProtocolMessage> filter) throws Exception {
            long end = System.nanoTime() + LIMIT.toNanos();
            while (System.nanoTime() < end) {
                var text = w.awaitProtocolMessage(Duration.ofMillis(100));
                if (text.isPresent()) {
                    var parsed = codec.decodeResponse(text.orElseThrow());
                    check(parsed.isSuccess(), "Respuesta V1 válida: " + text.orElseThrow());
                    ProtocolMessage message = parsed.message().orElseThrow(); seen.add(message);
                    if (filter.test(message)) { return message; }
                }
            }
            throw new AssertionError("No llegó respuesta esperada; vistos=" + seen);
        }
        Terminal terminal(long id, TerminalKind kind) throws Exception {
            return (Terminal) read(m -> m instanceof Terminal t && t.header().id() == id && t.kind() == kind);
        }
        ProtocolMessage.Error error(ErrorCode code) throws Exception {
            return (ProtocolMessage.Error) read(m -> m instanceof ProtocolMessage.Error e && e.code() == code);
        }
        @Override public void close() throws Exception {
            w.close(); check(w.awaitTermination(LIMIT), "Join real"); check(w.failure().isEmpty(), "Actor sin fallo");
        }
    }
    public static void main(String[] args) throws Exception {
        int passed = 0;
        for (var method : TextTransportScenarios.class.getDeclaredMethods()) {
            if (java.lang.reflect.Modifier.isPublic(method.getModifiers())
                    && java.lang.reflect.Modifier.isStatic(method.getModifiers()) && method.getParameterCount() == 0) {
                try { method.invoke(null); passed++; System.out.println("T64_OK " + method.getName()); }
                catch (java.lang.reflect.InvocationTargetException ex) { throw new AssertionError("Fallo " + method.getName(), ex.getCause()); }
            }
        }
        System.out.println("T64_RESULT scenarios=" + passed + "; failures=0");
    }
}
