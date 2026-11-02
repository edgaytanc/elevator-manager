package com.elevatormanager.protocol;

import com.elevatormanager.model.Direction;
import com.elevatormanager.model.ElevatorPhase;
import com.elevatormanager.model.SimulationConfig;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static com.elevatormanager.protocol.ProtocolMessage.*;

/** Escenarios independientes de JUnit; ejecutan el codec real, sin simular el motor. */
public final class ProtocolCodecScenarios {
    private static final ProtocolCodec CODEC = new ProtocolCodec(new SimulationConfig(2, 8, 1000, 750));
    private static final Header H = new Header(42, 0, 1);
    private static final UUID U = UUID.fromString("12345678-1234-1234-1234-123456789abc");
    private static final String PREFIX = "V1 CMD 42 EPOCH 0 ELEVATOR 1 ";
    private static final String DEST = PREFIX + "DESTINATION " + U + " 5";
    private static final String STATE = "V1 STATE 90 EPOCH 0 ELEVATOR 1 REV 8 FLOOR 2 NEXT 3"
            + " DIRECTION UP PHASE HELD SUSPENDED MOVING HOLD PAUSE MODE NORMAL REMAINING_MS 450 INTERNAL 5 EXTERNAL 3:UP";

    private ProtocolCodecScenarios() { }

    public static void pickupRoundTrip() { round(new Pickup(H, U, 3, Direction.UP)); }
    public static void destinationRoundTrip() { round(new Destination(H, U, 8)); }
    public static void resetRoundTrip() { control(ControlKind.RESET); }
    public static void stopRoundTrip() { control(ControlKind.STOP); }
    public static void startRoundTrip() { control(ControlKind.START); }
    public static void pauseRoundTrip() { control(ControlKind.PAUSE); }
    public static void resumeRoundTrip() { control(ControlKind.RESUME); }
    public static void statusRoundTrip() { control(ControlKind.STATUS); }
    public static void shutdownRoundTrip() { control(ControlKind.SHUTDOWN); }
    public static void acceptedRoundTrip() { round(new Ack(H, AckKind.ACCEPTED, Optional.empty())); }
    public static void coalescedRoundTrip() { round(new Ack(H, AckKind.COALESCED, Optional.of(U))); }
    public static void allErrorsRoundTrip() {
        for (ErrorCode code : ErrorCode.values()) {
            round(new ProtocolMessage.Error(H, code));
            round(new ProtocolMessage.Error(Header.UNKNOWN, code));
        }
        round(new ProtocolMessage.Error(new Header(42, 0, 99), ErrorCode.UNKNOWN_ELEVATOR));
    }
    public static void completedRoundTrip() {
        round(new Terminal(H, TerminalKind.COMPLETED, Optional.empty(), Optional.empty()));
    }
    public static void allCancellationsRoundTrip() {
        for (CancelReason reason : CancelReason.values()) {
            round(new Terminal(H, TerminalKind.CANCELLED, Optional.of(reason), Optional.empty()));
        }
    }
    public static void allFailuresRoundTrip() {
        for (ErrorCode code : ErrorCode.values()) {
            round(new Terminal(H, TerminalKind.FAILED, Optional.empty(), Optional.of(code)));
        }
    }
    public static void moveRoundTrip() {
        round(new Move(H, 2, 3, Direction.UP)); round(new Move(H, 8, 7, Direction.DOWN));
    }
    public static void arrivedRoundTrip() { round(new Arrived(H, 8)); }
    public static void directionRoundTrip() { round(new DirectionChanged(H, Direction.UP, Direction.DOWN)); }
    public static void stoppedAtInternalExtremeRoundTrip() {
        round(new StoppedAt(H, 8, Direction.UP)); round(new StoppedAt(H, 1, Direction.DOWN));
    }
    public static void idleStateRoundTrip() { round(state(ElevatorPhase.IDLE, OptionalInt.empty(), Optional.empty(), Optional.empty(), 0)); }
    public static void movingStateRoundTrip() {
        round(state(ElevatorPhase.MOVING, OptionalInt.of(3), Optional.empty(), Optional.empty(), 1000));
    }
    public static void dwellingStateRoundTrip() {
        round(state(ElevatorPhase.DWELLING, OptionalInt.empty(), Optional.empty(), Optional.empty(), 750));
    }
    public static void heldMovingRoundTrip() {
        ProtocolMessage message = good(CODEC.decode(STATE));
        equal(STATE, CODEC.encode(message)); round(message);
    }
    public static void heldDwellingStopRoundTrip() {
        round(state(ElevatorPhase.HELD, OptionalInt.empty(), Optional.of(ElevatorPhase.DWELLING), Optional.of(HoldReason.STOP), 700));
    }
    public static void resetStateRoundTrip() {
        round(new State(H, 10, 2, OptionalInt.of(1), Direction.DOWN, ElevatorPhase.MOVING,
                Optional.empty(), Optional.empty(), Mode.RESET_RETURN, 300, List.of(), List.of()));
    }
    public static void terminatedStateRoundTrip() {
        round(new State(H, 10, 1, OptionalInt.empty(), Direction.UP, ElevatorPhase.TERMINATED,
                Optional.empty(), Optional.empty(), Mode.NORMAL, 0, List.of(), List.of()));
    }
    public static void spontaneousStateRoundTrip() {
        State s = state(ElevatorPhase.IDLE, OptionalInt.empty(), Optional.empty(), Optional.empty(), 0);
        round(new State(new Header(0, 0, 1), 0, s.floor(), s.next(), s.direction(), s.phase(), s.suspended(),
                s.hold(), s.mode(), 0, s.internal(), s.external()));
    }
    public static void zeroRemainingWhileMovingIsValid() { good(CODEC.decode(STATE.replace("450", "0"))); }
    public static void canonicalSpacingAndTerminators() {
        for (String ending : List.of("", "\n", "\r\n")) {
            equal(DEST, CODEC.encode(good(CODEC.decode(DEST.replace(" ", "   ") + ending))));
        }
    }
    public static void nullAndEmptyRejected() {
        for (String input : new String[] {null, "", " ", "\n", "\r\n"}) { bad(input, ErrorCode.INVALID_FORMAT, Header.UNKNOWN); }
    }
    public static void exteriorSpacesRejected() {
        bad(" " + DEST, ErrorCode.INVALID_FORMAT, Header.UNKNOWN); bad(DEST + " ", ErrorCode.INVALID_FORMAT, Header.UNKNOWN);
        bad(DEST + " \r\n", ErrorCode.INVALID_FORMAT, Header.UNKNOWN);
    }
    public static void tabsBomUnicodeAndControlsRejected() {
        for (String input : List.of(DEST.replaceFirst(" ", "\t"), "\ufeff" + DEST,
                DEST + "é", DEST + "\u0000", DEST + "\ud800", DEST + "\u007f", DEST + "😀")) {
            bad(input, ErrorCode.INVALID_FORMAT, Header.UNKNOWN);
        }
    }
    public static void embeddedOrRepeatedTerminatorsRejected() {
        for (String input : List.of(DEST + "\r", DEST + "\n\n", DEST + "\r\n\r\n", DEST + "\n" + DEST)) {
            bad(input, ErrorCode.INVALID_FORMAT, Header.UNKNOWN);
        }
    }
    public static void commentsQuotesAndPlaceholdersRejected() {
        bad("#" + DEST, ErrorCode.INVALID_FORMAT, Header.UNKNOWN);
        bad(DEST + " # comentario", ErrorCode.INVALID_FORMAT, H);
        bad(PREFIX + "DESTINATION \"" + U + "\" 5", ErrorCode.INVALID_ARGUMENT, H);
        bad(PREFIX + "DESTINATION <requestId> 5", ErrorCode.INVALID_ARGUMENT, H);
    }
    public static void unsupportedAndMalformedVersions() {
        for (String v : List.of("V0", "V2", "V01", "V999999999999999999999999999")) {
            bad(DEST.replaceFirst("V1", v), ErrorCode.UNSUPPORTED_VERSION, Header.UNKNOWN);
        }
        for (String v : List.of("v1", "1", "V-1", "V", "V1X")) {
            bad(DEST.replaceFirst("V1", v), ErrorCode.INVALID_FORMAT, Header.UNKNOWN);
        }
    }
    public static void unknownFamilyAndCommand() {
        bad(DEST.replace("CMD", "BOGUS"), ErrorCode.INVALID_FORMAT, Header.UNKNOWN);
        bad(PREFIX + "FLY", ErrorCode.UNKNOWN_COMMAND, H);
        bad(PREFIX + "status", ErrorCode.UNKNOWN_COMMAND, H);
    }
    public static void commandHeaderMustBeCompleteAndCanonical() {
        for (String text : List.of("V1 CMD", "V1 CMD 42 EPOCH 0", "V1 CMD 42 EPOCH 0 ELEVATOR",
                "V1 CMD 0 EPOCH 0 ELEVATOR 1 STATUS", "V1 CMD 01 EPOCH 0 ELEVATOR 1 STATUS",
                "V1 CMD 42 EPOCH -1 ELEVATOR 1 STATUS", "V1 CMD 42 EPOCH 0 ELEVATOR 0 STATUS",
                "V1 CMD 42 EPOCH 0 ELEVATOR 2147483648 STATUS",
                "V1 CMD 9223372036854775808 EPOCH 0 ELEVATOR 1 STATUS")) {
            bad(text, ErrorCode.INVALID_FORMAT, Header.UNKNOWN);
        }
    }
    public static void headerOnlyRetainsCorrelation() { bad(PREFIX.stripTrailing(), ErrorCode.INVALID_FORMAT, H); }
    public static void arityAndLabelsAreExact() {
        for (String suffix : List.of("STATUS EXTRA", "RESET EXTRA", "DESTINATION " + U,
                "PICKUP " + U + " 3", "PICKUP " + U + " 3 UP EXTRA")) {
            bad(PREFIX + suffix, ErrorCode.INVALID_FORMAT, H);
        }
        bad(DEST.replace("EPOCH", "ERA"), ErrorCode.INVALID_FORMAT, Header.UNKNOWN);
        bad(STATE.replace("REMAINING_MS", "TIME"), ErrorCode.INVALID_FORMAT, new Header(90, 0, 1));
    }
    public static void positiveLongAndIntMaximaRoundTrip() {
        ProtocolCodec huge = new ProtocolCodec(new SimulationConfig(Integer.MAX_VALUE, Integer.MAX_VALUE, 1, 1));
        ProtocolMessage message = new Destination(new Header(Long.MAX_VALUE, Long.MAX_VALUE, Integer.MAX_VALUE), U, Integer.MAX_VALUE);
        equal(message, good(huge.decode(huge.encode(message))));
    }
    public static void numericBodiesAreCanonicalAndBounded() {
        for (String floor : List.of("02", "+2", "-2", "2.0", "2e0", "2147483648", "9223372036854775808")) {
            bad(PREFIX + "DESTINATION " + U + " " + floor, ErrorCode.INVALID_ARGUMENT, H);
        }
        for (String floor : List.of("0", "9", "2147483647")) {
            bad(PREFIX + "DESTINATION " + U + " " + floor, ErrorCode.INVALID_FLOOR, H);
        }
    }
    public static void uuidIsStrictAndPreserved() {
        for (String id : List.of("100", U.toString().toUpperCase(), "1-1-1-1-1", U.toString().replace("-", ""), "-")) {
            bad(PREFIX + "DESTINATION " + id + " 5", ErrorCode.INVALID_ARGUMENT, H);
        }
        equal(U, ((Destination) good(CODEC.decode(DEST))).requestId());
    }
    public static void directionsAndBuildingLimits() {
        for (String d : List.of("up", "ARRIBA", "-", "0")) {
            bad(PREFIX + "PICKUP " + U + " 3 " + d, ErrorCode.INVALID_DIRECTION, H);
        }
        bad(PREFIX + "PICKUP " + U + " 1 DOWN", ErrorCode.INVALID_DIRECTION, H);
        bad(PREFIX + "PICKUP " + U + " 8 UP", ErrorCode.INVALID_DIRECTION, H);
        good(CODEC.decode(PREFIX + "PICKUP " + U + " 1 UP"));
        good(CODEC.decode(PREFIX + "PICKUP " + U + " 8 DOWN"));
    }
    public static void singleFloorBuilding() {
        ProtocolCodec one = new ProtocolCodec(new SimulationConfig(1, 1, 1, 1));
        equal(1, ((Destination) good(one.decode(DEST.replace(" 5", " 1")))).floor());
        for (Direction d : Direction.values()) {
            equal(ErrorCode.INVALID_DIRECTION, one.decode(PREFIX + "PICKUP " + U + " 1 " + d).failure().orElseThrow().code());
        }
    }
    public static void lexicalTypeTargetAndFloorPrecedence() {
        Header unknown = new Header(42, 0, 99);
        bad(DEST.replace("ELEVATOR 1", "ELEVATOR 99").replace(" 5", " 9"), ErrorCode.UNKNOWN_ELEVATOR, unknown);
        bad(PREFIX.replace("ELEVATOR 1", "ELEVATOR 99") + "DESTINATION bad 9", ErrorCode.INVALID_ARGUMENT, unknown);
        bad(PREFIX + "PICKUP " + U + " 9 SIDEWAYS", ErrorCode.INVALID_DIRECTION, H);
        bad(PREFIX + "PICKUP " + U + " 9 UP", ErrorCode.INVALID_FLOOR, H);
    }
    public static void parserDoesNotApplySessionAdmission() {
        good(CODEC.decode(PREFIX.replace("EPOCH 0", "EPOCH 999") + "START"));
        good(CODEC.decode(PREFIX + "RESET"));
        good(CODEC.decode("V1 EVENT 42 EPOCH 999 ELEVATOR 1 COMPLETED"));
        good(CODEC.decode(DEST)); good(CODEC.decode(DEST.replace(" 5", " 6")));
    }
    public static void commandAndResponseChannelsAreDistinct() {
        String ack = "V1 ACK 42 EPOCH 0 ELEVATOR 1 ACCEPTED";
        check(CODEC.decodeCommand(DEST).isSuccess(), "Comando válido");
        check(CODEC.decodeResponse(ack).isSuccess(), "Respuesta válida");
        equal(ErrorCode.INVALID_FORMAT, CODEC.decodeCommand(ack).failure().orElseThrow().code());
        equal(ErrorCode.INVALID_FORMAT, CODEC.decodeResponse(DEST).failure().orElseThrow().code());
    }
    public static void unknownErrorReasonsAndStatusesRejected() {
        for (String line : List.of("V1 ERROR 42 EPOCH 0 ELEVATOR 1 BOGUS",
                "V1 EVENT 42 EPOCH 0 ELEVATOR 1 CANCELLED BOGUS",
                "V1 EVENT 42 EPOCH 0 ELEVATOR 1 FAILED BOGUS",
                "V1 ACK 42 EPOCH 0 ELEVATOR 1 BOGUS")) {
            bad(line, ErrorCode.INVALID_ARGUMENT, H);
        }
    }
    public static void responseAritiesAreExact() {
        for (String line : List.of("V1 ACK 42 EPOCH 0 ELEVATOR 1 ACCEPTED " + U,
                "V1 ACK 42 EPOCH 0 ELEVATOR 1 COALESCED",
                "V1 EVENT 42 EPOCH 0 ELEVATOR 1 COMPLETED EXTRA",
                "V1 EVENT 42 EPOCH 0 ELEVATOR 1 CANCELLED",
                "V1 ERROR 42 EPOCH 0 ELEVATOR 1 INVALID_ARGUMENT EXTRA",
                "V1 ARRIVED 42 EPOCH 0 ELEVATOR 1 FLOOR 3 EXTRA")) {
            bad(line, ErrorCode.INVALID_FORMAT, H);
        }
    }
    public static void unknownErrorHeaderIsAllOrNothing() {
        good(CODEC.decode("V1 ERROR 0 EPOCH 0 ELEVATOR 0 INVALID_FORMAT"));
        for (String line : List.of("V1 ERROR 0 EPOCH 1 ELEVATOR 0 INVALID_FORMAT",
                "V1 ERROR 0 EPOCH 0 ELEVATOR 1 INVALID_FORMAT", "V1 ERROR 42 EPOCH 0 ELEVATOR 0 INVALID_FORMAT")) {
            bad(line, ErrorCode.INVALID_ARGUMENT, Header.UNKNOWN);
        }
    }
    public static void physicalEventsMustBeCoherent() {
        bad("V1 MOVE 42 EPOCH 0 ELEVATOR 1 FROM 2 TO 4 DIRECTION UP", ErrorCode.INVALID_ARGUMENT, H);
        bad("V1 MOVE 42 EPOCH 0 ELEVATOR 1 FROM 2 TO 1 DIRECTION UP", ErrorCode.INVALID_ARGUMENT, H);
        bad("V1 DIRECTION 42 EPOCH 0 ELEVATOR 1 FROM UP TO UP", ErrorCode.INVALID_ARGUMENT, H);
        bad("V1 ARRIVED 42 EPOCH 0 ELEVATOR 1 FLOOR 9", ErrorCode.INVALID_FLOOR, H);
        bad("V1 ARRIVED 0 EPOCH 0 ELEVATOR 1 FLOOR 2", ErrorCode.INVALID_ARGUMENT, Header.UNKNOWN);
    }
    public static void stateCounterBounds() {
        good(CODEC.decode(STATE.replace("REV 8", "REV " + Long.MAX_VALUE)));
        good(CODEC.decode(STATE.replace("REV 8", "REV 0")));
        for (String value : List.of("-1", "01", "9223372036854775808")) {
            bad(STATE.replace("REV 8", "REV " + value), ErrorCode.INVALID_ARGUMENT, new Header(90, 0, 1));
        }
    }
    public static void stateHeldCombinations() {
        for (String line : List.of(STATE.replace("SUSPENDED MOVING", "SUSPENDED -"),
                STATE.replace("HOLD PAUSE", "HOLD -"), STATE.replace("HOLD PAUSE", "HOLD STOP"),
                STATE.replace("SUSPENDED MOVING", "SUSPENDED IDLE"), STATE.replace("PHASE HELD", "PHASE MOVING"))) {
            bad(line, ErrorCode.INVALID_ARGUMENT, new Header(90, 0, 1));
        }
    }
    public static void stateNextAndRemainingConstraints() {
        for (String line : List.of(STATE.replace("NEXT 3", "NEXT -"), STATE.replace("NEXT 3", "NEXT 4"),
                STATE.replace("450", "1001"), STATE.replace("450", "-1"))) {
            bad(line, ErrorCode.INVALID_ARGUMENT, new Header(90, 0, 1));
        }
        bad(STATE.replace("NEXT 3", "NEXT 9"), ErrorCode.INVALID_FLOOR, new Header(90, 0, 1));
        State idle = state(ElevatorPhase.IDLE, OptionalInt.empty(), Optional.empty(), Optional.empty(), 0);
        bad(CODEC.encode(idle).replace("REMAINING_MS 0", "REMAINING_MS 1"), ErrorCode.INVALID_ARGUMENT, H);
    }
    public static void stateListsAreSortedUniqueAndStrict() {
        for (String list : List.of("5,3", "5,5", "05", "5,", ",5", "5,,7")) {
            bad(STATE.replace("INTERNAL 5", "INTERNAL " + list), ErrorCode.INVALID_ARGUMENT, new Header(90, 0, 1));
        }
        bad(STATE.replace("INTERNAL 5", "INTERNAL"), ErrorCode.INVALID_FORMAT, new Header(90, 0, 1));
        bad(STATE.replace("INTERNAL 5", "INTERNAL 9"), ErrorCode.INVALID_FLOOR, new Header(90, 0, 1));
        good(CODEC.decode(STATE.replace("INTERNAL 5", "INTERNAL 1,3,5,8")));
        good(CODEC.decode(STATE.replace("EXTERNAL 3:UP", "EXTERNAL 3:UP,3:DOWN,5:DOWN")));
    }
    public static void stateExternalPairsAreStrict() {
        for (String list : List.of("3:DOWN,3:UP", "3:UP,3:UP", "5:UP,3:DOWN", "3", "3:UP:", "3:UP,", ":UP")) {
            bad(STATE.replace("EXTERNAL 3:UP", "EXTERNAL " + list), ErrorCode.INVALID_ARGUMENT, new Header(90, 0, 1));
        }
        bad(STATE.replace("3:UP", "1:DOWN"), ErrorCode.INVALID_DIRECTION, new Header(90, 0, 1));
        bad(STATE.replace("3:UP", "8:UP"), ErrorCode.INVALID_DIRECTION, new Header(90, 0, 1));
        bad(STATE.replace("3:UP", "3:up"), ErrorCode.INVALID_DIRECTION, new Header(90, 0, 1));
        bad(STATE.replace("3:UP", "0:UP"), ErrorCode.INVALID_FLOOR, new Header(90, 0, 1));
    }
    public static void stateResetAndTerminatedHaveNoPending() {
        bad(STATE.replace("MODE NORMAL", "MODE RESET_RETURN"), ErrorCode.INVALID_ARGUMENT, new Header(90, 0, 1));
        State terminated = new State(H, 0, 1, OptionalInt.empty(), Direction.UP, ElevatorPhase.TERMINATED,
                Optional.empty(), Optional.empty(), Mode.NORMAL, 0, List.of(), List.of());
        bad(CODEC.encode(terminated).replace("INTERNAL -", "INTERNAL 2"), ErrorCode.INVALID_ARGUMENT, H);
    }
    public static void stateProjectionDoesNotGenerateIdentityOrTime() {
        State parsed = (State) good(CODEC.decode(STATE));
        equal(List.of(5), parsed.internal()); equal(450L, parsed.remainingMillis());
        equal(new Header(90, 0, 1), parsed.header()); equal(8L, parsed.revision());
        equal(STATE, CODEC.encode(parsed));
    }
    public static void immutableStateListsAndDtoShapes() {
        List<Integer> internal = new ArrayList<>(List.of(5));
        List<ExternalCall> external = new ArrayList<>(List.of(new ExternalCall(3, Direction.UP)));
        State state = new State(H, 0, 2, OptionalInt.empty(), Direction.UP, ElevatorPhase.IDLE,
                Optional.empty(), Optional.empty(), Mode.NORMAL, 0, internal, external);
        internal.clear(); external.clear(); equal(1, state.internal().size()); equal(1, state.external().size());
        expectIllegal(() -> state.internal().add(8));
        expectIllegal(() -> new Ack(H, AckKind.ACCEPTED, Optional.of(U)));
        expectIllegal(() -> new Terminal(H, TerminalKind.COMPLETED, Optional.of(CancelReason.RESET), Optional.empty()));
        expectIllegal(() -> new Pickup(Header.UNKNOWN, U, 3, Direction.UP));
        expectIllegal(() -> new ProtocolMessage.Error(new Header(0, 0, 1), ErrorCode.INVALID_FORMAT));
    }
    public static void exactFrameLimitIncludesTerminator() {
        for (String ending : List.of("", "\n", "\r\n")) {
            int spaces = ProtocolCodec.MAX_FRAME_BYTES - DEST.length() - ending.length() + 1;
            String exact = DEST.replaceFirst(" ", " ".repeat(spaces)) + ending;
            equal(ProtocolCodec.MAX_FRAME_BYTES, exact.length());
            equal(DEST, CODEC.encode(good(CODEC.decode(exact))));
            bad(exact + " ", ErrorCode.MESSAGE_TOO_LONG, Header.UNKNOWN);
        }
    }
    public static void oversizedUnicodeIsCheckedInBytes() {
        bad("é".repeat(ProtocolCodec.MAX_FRAME_BYTES / 2 + 1), ErrorCode.MESSAGE_TOO_LONG, Header.UNKNOWN);
        bad("😀".repeat(ProtocolCodec.MAX_FRAME_BYTES / 4 + 1), ErrorCode.MESSAGE_TOO_LONG, Header.UNKNOWN);
    }
    public static void excessTokensDoNotAllocateUnboundedTokenArray() {
        bad(PREFIX + "STATUS" + " X".repeat(300_000), ErrorCode.INVALID_FORMAT, H);
        bad(PREFIX + "FLY" + " X".repeat(300_000), ErrorCode.UNKNOWN_COMMAND, H);
    }
    public static void hugeNumericAndUuidTokensAreControlled() {
        bad(PREFIX + "DESTINATION " + "a".repeat(100_000) + " 5", ErrorCode.INVALID_ARGUMENT, H);
        bad(PREFIX + "DESTINATION " + U + " " + "9".repeat(100_000), ErrorCode.INVALID_ARGUMENT, H);
    }
    public static void diagnosticsAreBoundedEscapedAndNotExecutable() {
        ProtocolDecodeResult.Failure f = CODEC.decode(DEST + "\n<script>\t").failure().orElseThrow();
        check(!f.preview().contains("\n") && !f.preview().contains("\t"), "Controles escapados");
        check(f.preview().contains("\\n") && f.preview().contains("\\t"), "Prefijo fiel");
        ProtocolDecodeResult.Failure longFailure = CODEC.decode(PREFIX + "X".repeat(10_000)).failure().orElseThrow();
        check(longFailure.preview().length() <= 256, "Prefijo limitado");
        equal(new ProtocolMessage.Error(H, ErrorCode.UNKNOWN_COMMAND), longFailure.asErrorMessage());
    }
    public static void invalidMessageThenValidMessageRecovers() {
        for (int i = 0; i < 500; i++) {
            bad(PREFIX + "BOGUS", ErrorCode.UNKNOWN_COMMAND, H); equal(U, ((Destination) good(CODEC.decode(DEST))).requestId());
        }
    }
    public static void encodingUsesConfigurationAndControlledFailure() {
        encodingError(() -> CODEC.encode(new Destination(H, U, 9)), ErrorCode.INVALID_FLOOR);
        encodingError(() -> CODEC.encode(new Control(new Header(1, 0, 3), ControlKind.STATUS)), ErrorCode.UNKNOWN_ELEVATOR);
        encodingError(() -> CODEC.encode(new Pickup(H, U, 8, Direction.UP)), ErrorCode.INVALID_DIRECTION);
        encodingError(() -> CODEC.encode(null), ErrorCode.INVALID_ARGUMENT);
    }
    public static void oversizedStateEncodingIsNotTruncated() {
        ProtocolCodec huge = new ProtocolCodec(new SimulationConfig(1, 300_000, 1, 1));
        List<Integer> internal = new ArrayList<>();
        for (int i = 1; i <= 200_000; i++) { internal.add(i); }
        State state = new State(H, 0, 1, OptionalInt.empty(), Direction.UP, ElevatorPhase.IDLE,
                Optional.empty(), Optional.empty(), Mode.NORMAL, 0, internal, List.of());
        encodingError(() -> huge.encode(state), ErrorCode.MESSAGE_TOO_LONG);
    }
    public static void sameRevisionCanCarryDifferentRemaining() {
        State first = (State) good(CODEC.decode(STATE));
        State second = (State) good(CODEC.decode(STATE.replace("450", "400")));
        equal(first.revision(), second.revision()); check(!first.equals(second), "No perder restante");
    }
    public static void randomRoundTripsRetainAllOperationalFields() {
        Random random = new Random(630062L);
        for (int i = 1; i <= 1000; i++) {
            Header h = new Header(i, random.nextInt(10), 1 + random.nextInt(2));
            UUID id = new UUID(random.nextLong(), random.nextLong());
            round(new Destination(h, id, 1 + random.nextInt(8)));
            round(new Pickup(h, id, 2 + random.nextInt(6), random.nextBoolean() ? Direction.UP : Direction.DOWN));
        }
    }
    public static void randomMalformedInputsNeverEscapeTheParser() {
        Random random = new Random(63);
        for (int i = 0; i < 5000; i++) {
            int length = random.nextInt(160); StringBuilder s = new StringBuilder();
            for (int j = 0; j < length; j++) { s.append((char) random.nextInt(256)); }
            ProtocolDecodeResult result = CODEC.decode(s.toString());
            check(result.isSuccess() != result.failure().isPresent(), "Resultado exclusivo");
        }
        for (int i = 0; i < DEST.length(); i++) {
            CODEC.decode(DEST.substring(0, i));
            CODEC.decode(DEST.substring(0, i) + " " + DEST.substring(i));
        }
    }
    public static void sharedCodecIsThreadSafe() throws Exception {
        var pool = Executors.newFixedThreadPool(4);
        try {
            List<Callable<Boolean>> jobs = new ArrayList<>();
            for (int thread = 0; thread < 4; thread++) {
                jobs.add(() -> {
                    for (int i = 0; i < 300; i++) {
                        equal(DEST, CODEC.encode(good(CODEC.decode(DEST))));
                        bad(PREFIX + "FLY", ErrorCode.UNKNOWN_COMMAND, H);
                    }
                    return true;
                });
            }
            for (var future : pool.invokeAll(jobs, 15, TimeUnit.SECONDS)) { check(future.get(), "Hilo terminado"); }
        } finally { pool.shutdownNow(); check(pool.awaitTermination(10, TimeUnit.SECONDS), "Cierre pool"); }
    }

    public static void typedPickupBridgePreservesContentAndExplicitMetadata() {
        var time = java.time.Instant.parse("2026-11-02T00:00:00Z");
        Command original = new Command.Pickup(42, 1, new com.elevatormanager.model.request.HallRequest(U, 0, 3, time, Direction.UP));
        Command restored = ProtocolCommands.toDomain(good(CODEC.decode(CODEC.encode(ProtocolCommands.fromDomain(original)))), time);
        check(original.sameContent(restored), "Contenido completo de PICKUP");
        equal(time, ((Command.Pickup) restored).request().getCreatedAt());
    }
    public static void typedDestinationBridgePreservesContentAndExplicitMetadata() {
        var time = java.time.Instant.parse("2026-11-02T00:00:00Z");
        Command original = new Command.Destination(42, 1, new com.elevatormanager.model.request.CabinRequest(U, 0, 5, time, 1));
        Command restored = ProtocolCommands.toDomain(good(CODEC.decode(CODEC.encode(ProtocolCommands.fromDomain(original)))), time);
        check(original.sameContent(restored), "Contenido completo de DESTINATION");
        equal(time, ((Command.Destination) restored).request().getCreatedAt());
    }
    public static void typedResetBridgeAndOtherControlsRemainExplicit() {
        var time = java.time.Instant.EPOCH;
        Command original = new Command.Reset(42, 1, 1);
        equal(original, ProtocolCommands.toDomain(good(CODEC.decode(CODEC.encode(ProtocolCommands.fromDomain(original)))), time));
        expectIllegal(() -> ProtocolCommands.toDomain(new Control(H, ControlKind.PAUSE), time));
        expectIllegal(() -> ProtocolCommands.toDomain(new Ack(H, AckKind.ACCEPTED, Optional.empty()), time));
    }
    public static void messageEqualityIncludesContentBeyondUuid() {
        check(!new Destination(H, U, 3).equals(new Destination(H, U, 5)), "Igualdad no reducida a UUID");
        check(!new Pickup(H, U, 3, Direction.UP).equals(new Pickup(H, U, 3, Direction.DOWN)), "Sentido incluido");
    }
    public static void malformedMutationsOfEveryFamilyAreControlled() {
        List<String> valid = List.of(DEST, STATE, PREFIX + "STATUS",
                "V1 ACK 42 EPOCH 0 ELEVATOR 1 COALESCED " + U,
                "V1 EVENT 42 EPOCH 0 ELEVATOR 1 CANCELLED RESET",
                "V1 MOVE 42 EPOCH 0 ELEVATOR 1 FROM 2 TO 3 DIRECTION UP",
                "V1 ARRIVED 42 EPOCH 0 ELEVATOR 1 FLOOR 3",
                "V1 DIRECTION 42 EPOCH 0 ELEVATOR 1 FROM UP TO DOWN",
                "V1 STOPPED_AT 42 EPOCH 0 ELEVATOR 1 FLOOR 8 SERVICE UP",
                "V1 ERROR 0 EPOCH 0 ELEVATOR 0 INVALID_FORMAT");
        for (String line : valid) {
            good(CODEC.decode(line));
            for (int i = 0; i < line.length(); i++) {
                CODEC.decode(line.substring(0, i));
                CODEC.decode(line.substring(0, i) + "#" + line.substring(i + 1));
            }
        }
    }

    private static State state(ElevatorPhase phase, OptionalInt next, Optional<ElevatorPhase> suspended,
            Optional<HoldReason> hold, long remaining) {
        return new State(H, 8, 2, next, Direction.UP, phase, suspended, hold, Mode.NORMAL, remaining,
                List.of(5, 8), List.of(new ExternalCall(3, Direction.UP), new ExternalCall(3, Direction.DOWN)));
    }
    private static void control(ControlKind kind) { round(new Control(H, kind)); }
    private static void round(ProtocolMessage message) {
        String encoded = CODEC.encode(message);
        equal(message, good(CODEC.decode(encoded)));
        equal(encoded, CODEC.encode(good(CODEC.decode(encoded))));
        check(!encoded.endsWith("\n") && !encoded.contains("  "), "Emisión canónica");
    }
    private static ProtocolMessage good(ProtocolDecodeResult result) {
        if (!result.isSuccess()) { throw new AssertionError("Rechazado: " + result.failure().orElseThrow()); }
        return result.message().orElseThrow();
    }
    private static void bad(String text, ErrorCode code, Header correlation) {
        ProtocolDecodeResult result = CODEC.decode(text);
        check(!result.isSuccess(), "Se esperaba rechazo");
        var f = result.failure().orElseThrow(); equal(code, f.code()); equal(correlation, f.header());
    }
    private static void encodingError(Runnable action, ErrorCode code) {
        try { action.run(); }
        catch (ProtocolCodec.EncodingException ex) { equal(code, ex.failure().code()); return; }
        throw new AssertionError("Se esperaba error de codificación " + code);
    }
    private static void expectIllegal(Runnable action) {
        try { action.run(); }
        catch (IllegalArgumentException | UnsupportedOperationException expected) { return; }
        throw new AssertionError("Se esperaba rechazo de DTO/colección");
    }
    private static void equal(Object expected, Object actual) {
        if (!java.util.Objects.equals(expected, actual)) { throw new AssertionError("Esperado " + expected + ", recibido " + actual); }
    }
    private static void check(boolean condition, String message) { if (!condition) { throw new AssertionError(message); } }

    public static void main(String[] args) throws Exception {
        int passed = 0;
        for (var method : ProtocolCodecScenarios.class.getDeclaredMethods()) {
            if (java.lang.reflect.Modifier.isPublic(method.getModifiers())
                    && java.lang.reflect.Modifier.isStatic(method.getModifiers())
                    && method.getParameterCount() == 0) {
                try { method.invoke(null); passed++; System.out.println("T63_OK " + method.getName()); }
                catch (java.lang.reflect.InvocationTargetException ex) {
                    throw new AssertionError("Fallo " + method.getName(), ex.getCause());
                }
            }
        }
        System.out.println("T63_RESULT scenarios=" + passed + "; failures=0");
    }
}
