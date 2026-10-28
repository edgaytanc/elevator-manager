package com.elevatormanager.io;

import com.elevatormanager.model.Direction;
import com.elevatormanager.model.SimulationConfig;
import com.elevatormanager.model.request.CabinRequest;
import com.elevatormanager.model.request.HallRequest;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static com.elevatormanager.io.BatchDiagnostic.Code.*;
import static com.elevatormanager.io.RequestFileFormat.*;

/** T44: escenarios independientes, sin trabajadores ni importación al controlador. */
public final class RequestFileScenarios {
    private static final SimulationConfig CONFIG = new SimulationConfig(2, 8, 10, 10);
    private static final RequestFileReader READER = new RequestFileReader();
    private RequestFileScenarios() { }

    public static void minimalCabinBatch() {
        var batch = valid("CABIN 1 4\n", CONFIG);
        check(batch.entries().equals(List.of(new RequestEntry.Cabin(1, 1, 4))), "Descriptor interno");
        check(batch.config().equals(CONFIG) && batch.physicalLineCount() == 1 && batch.byteCount() == 10, "Metadatos exactos");
    }
    public static void mixedBatchPreservesPhysicalOrder() {
        var batch = valid("# Inicio\nHALL 3 UP\nCABIN 1 8\n\nHALL 8 DOWN\nCABIN 2 1\n", CONFIG);
        check(batch.entries().stream().map(RequestEntry::lineNumber).toList().equals(List.of(2, 3, 5, 6)), "Orden y líneas");
        check(batch.entries().get(0) instanceof RequestEntry.Hall && batch.entries().get(1) instanceof RequestEntry.Cabin, "Tipos distintos");
    }
    public static void repeatedEntriesAreNotRemoved() {
        var batch = valid("HALL 3 UP\nHALL 003 UP\nHALL 3 DOWN\nCABIN 1 5\nCABIN 1 005\nCABIN 2 5", CONFIG);
        check(batch.entries().size() == 6, "Conserva seis instrucciones");
        check(batch.entries().get(0).floor() == batch.entries().get(1).floor(), "Normaliza valor numérico");
        check(batch.entries().get(3).floor() == batch.entries().get(4).floor(), "Repetidos internos conservados");
    }
    public static void commentsAllowUnicode() {
        var batch = valid(" # Dirección: petición ñ 🛗\nCABIN 1 2", CONFIG);
        check(batch.entries().get(0).lineNumber() == 2, "Comentario Unicode ignorado como instrucción");
    }
    public static void asciiSpacesAreAccepted() {
        var batch = valid("   \n  HALL   3  UP   \n  CABIN  2   4  ", CONFIG);
        check(batch.entries().size() == 2 && batch.physicalLineCount() == 3, "Espacios exteriores y repetidos");
    }
    public static void mixedLfAndCrlfHaveNoPhantomLine() {
        var batch = valid("CABIN 1 2\r\nHALL 3 UP\nCABIN 2 4\r\n", CONFIG);
        check(batch.physicalLineCount() == 3, "CRLF cuenta una sola vez; salto final no añade línea");
    }
    public static void finalNewlineIsOptional() {
        var a = valid("CABIN 1 2", CONFIG); var b = valid("CABIN 1 2\n", CONFIG);
        check(a.entries().equals(b.entries()) && a.physicalLineCount() == b.physicalLineCount(), "Mismo contenido lógico");
        check(a.byteCount() + 1 == b.byteCount(), "Bytes conservan terminador");
    }
    public static void oneInitialBomIsAccepted() {
        var batch = valid("\uFEFFHALL 3 UP\r\n", CONFIG);
        check(batch.entries().get(0).lineNumber() == 1 && batch.byteCount() == 14, "BOM cuenta bytes, no línea");
    }
    public static void repeatedBomIsRejected() { error("\uFEFF\uFEFFCABIN 1 2", INVALID_BOM, 1); }
    public static void interiorBomIsRejectedEvenInComments() {
        error("# Comentario\n# BOM \uFEFF interior\nCABIN 1 2", INVALID_BOM, 2);
        error(" \uFEFFCABIN 1 2", INVALID_BOM, 1);
    }
    public static void truncatedUtf8HasOriginalCodingCause() {
        var result = READER.parse(new byte[] { (byte) 0xE2, (byte) 0x82 }, CONFIG);
        var diagnostic = only(result, INVALID_UTF8);
        check(diagnostic.lineNumber().isEmpty() && diagnostic.cause().orElseThrow() instanceof java.nio.charset.CharacterCodingException,
                "No inventa línea; conserva causa de decodificación");
    }
    public static void malformedUtf8IsNeverReplaced() {
        for (byte[] bytes : List.of(new byte[] {(byte)0xC0,(byte)0xAF}, new byte[] {(byte)0xED,(byte)0xA0,(byte)0x80},
                new byte[] {(byte)0xFF}, new byte[] {(byte)0xFE,(byte)0xFF,0,65})) { only(READER.parse(bytes, CONFIG), INVALID_UTF8); }
    }
    public static void emptyVariantsRejectWithoutFakeLine() {
        for (String text : List.of("", "\uFEFF", "\n", " \r\n", "# comentario\n  # otro\n\n")) {
            check(only(parse(text), EMPTY_BATCH).lineNumber().isEmpty(), "Lote vacío sin línea falsa");
        }
    }
    public static void controlsAreInvalidInsideCommentsAndBlankLines() {
        for (String line : List.of("\t", "# texto\t", "# NUL\u0000", "# DEL\u007F", "# control\u009F", "CABIN\t1 2")) {
            error(line + "\nCABIN 1 2", INVALID_CHARACTER, 1);
        }
    }
    public static void unicodeIsNotAcceptedInInstructions() {
        for (String line : List.of("HALL ٣ UP", "HALL\u00A03 UP", "ＣＡＢＩＮ 1 2", "CABIN 1 2\u200B")) { error(line, INVALID_CHARACTER, 1); }
    }
    public static void unknownInstructionsAndControlsAreRejected() {
        for (String line : List.of("hall 3 UP", "RESET", "START", "STOP", "WAIT 4", "V1 CMD 1 EPOCH 0 ELEVATOR 1 RESET", "HALL,3,UP")) {
            error(line, UNKNOWN_INSTRUCTION, 1);
        }
    }
    public static void directionsAreExactUppercaseTokens() {
        for (String value : List.of("up", "down", "ARRIBA", "UP|DOWN", "-")) { error("HALL 3 " + value, INVALID_DIRECTION, 1); }
    }
    public static void arityIsExactAndInlineCommentsAreRejected() {
        for (String line : List.of("HALL", "HALL 3", "CABIN 1 4 EXTRA", "HALL 3 UP # comentario", "HALL 3 UP; CABIN 1 4")) {
            error(line, INVALID_ARITY, 1);
        }
    }
    public static void signedDecimalAndExponentNumbersAreRejected() {
        for (String token : List.of("+3", "-3", "3.0", "3e0", "1_000", "0x3", "\"3\"")) { error("HALL " + token + " UP", INVALID_INTEGER, 1); }
    }
    public static void integerOverflowIsRejectedWithoutWrapping() {
        error("CABIN 1 2147483648", INTEGER_OUT_OF_RANGE, 1);
        error("HALL " + "9".repeat(100) + " UP", INTEGER_OUT_OF_RANGE, 1);
        error("HALL 999999999999999999x UP", INVALID_INTEGER, 1);
        var maximum = new SimulationConfig(Integer.MAX_VALUE, Integer.MAX_VALUE, 1, 1);
        check(valid("CABIN 2147483647 2147483647", maximum).entries().size() == 1, "int máximo representable");
    }
    public static void leadingZerosDoNotImplyNumericOverflow() {
        check(valid("CABIN " + "0".repeat(100) + "1 " + "0".repeat(100) + "2", CONFIG).entries().get(0).floor() == 2,
                "Ceros iniciales largos con valor pequeño");
        error("HALL 000 UP", FLOOR_OUT_OF_RANGE, 1);
    }
    public static void floorBoundsAreChecked() {
        for (String line : List.of("HALL 0 UP", "HALL 9 UP", "CABIN 1 0", "CABIN 1 9")) { error(line, FLOOR_OUT_OF_RANGE, 1); }
    }
    public static void cabinIdBoundsAreCheckedBeforeFollowingArguments() {
        for (String line : List.of("CABIN 0 4", "CABIN 3 4", "CABIN 3 noNumerico")) { error(line, ELEVATOR_OUT_OF_RANGE, 1); }
        error("CABIN +1 4", INVALID_INTEGER, 1);
    }
    public static void impossibleHallDirectionsAreRejected() {
        error("HALL 8 UP", HALL_UP_AT_TOP, 1); error("HALL 1 DOWN", HALL_DOWN_AT_BOTTOM, 1);
        check(valid("HALL 1 UP\nHALL 8 DOWN", CONFIG).entries().size() == 2, "Sentidos extremos válidos");
    }
    public static void singleFloorBuildingAcceptsOnlyCabinRequests() {
        var one = new SimulationConfig(1, 1, 1, 1);
        check(valid("CABIN 1 1", one).entries().size() == 1, "Destino en piso actual");
        only(READER.parse(bytes("HALL 1 UP"), one), HALL_UP_AT_TOP);
        only(READER.parse(bytes("HALL 1 DOWN"), one), HALL_DOWN_AT_BOTTOM);
    }
    public static void everyInvalidLineHasOneOrderedDiagnostic() {
        var result = parse("# Primero\nHALL 8 UP\n\nCABIN 3 4\nHALL 3 ARRIBA\nCABIN 1 2");
        check(!result.isValid() && result.batch().isEmpty(), "No entrega entradas parcialmente válidas");
        check(result.diagnostics().stream().map(d -> d.lineNumber().orElseThrow()).toList().equals(List.of(2,4,5)), "Errores por línea en orden");
        for (var d : result.diagnostics()) { check(!d.message().isBlank() && !d.expectedFormat().isBlank(), "Explicación y formato esperado"); }
    }
    public static void finalLineErrorRejectsEntireBatch() {
        var result = parse("# Completo\nHALL 3 UP\nCABIN 1 8\nHALL 8 UP");
        var diagnostic = only(result, HALL_UP_AT_TOP);
        check(diagnostic.lineNumber().orElseThrow() == 4 && result.batch().isEmpty(), "Error final, ningún lote parcial");
    }
    public static void invalidReadCannotChangePreviousValidBatch() {
        var previous = valid("CABIN 1 4\nHALL 3 UP", CONFIG);
        var snapshot = List.copyOf(previous.entries());
        for (String invalid : List.of("HALL 8 UP", "# vacío", "CABIN 1 4\nERROR")) { check(!parse(invalid).isValid(), "Lectura inválida"); }
        check(previous.entries().equals(snapshot) && previous.config().equals(CONFIG), "Lote previo inmutable e intacto");
    }
    public static void exactByteLimitIsAccepted() {
        byte[] content = documentOfBytes(MAX_FILE_BYTES);
        var batch = READER.parse(content, CONFIG).batch().orElseThrow();
        check(batch.byteCount() == MAX_FILE_BYTES && batch.entries().size() == 1, "Límite exacto de bytes");
    }
    public static void oneByteOverLimitIsRejected() {
        var diagnostic = only(READER.parse(documentOfBytes(MAX_FILE_BYTES + 1), CONFIG), FILE_TOO_LARGE);
        check(diagnostic.lineNumber().isEmpty(), "Límite de archivo sin línea inventada");
    }
    public static void exactlyTenThousandPhysicalLinesAreAccepted() {
        var batch = valid("CABIN 1 2\n" + "#\n".repeat(MAX_LINES - 1), CONFIG);
        check(batch.physicalLineCount() == MAX_LINES && batch.entries().size() == 1, "Comentarios y última nueva línea");
    }
    public static void tenThousandAndOneLinesAreRejected() {
        var result = parse("CABIN 1 2\n" + "\n".repeat(MAX_LINES));
        check(only(result, TOO_MANY_LINES).lineNumber().orElseThrow() == MAX_LINES + 1, "Cuenta líneas vacías reales");
    }
    public static void supplementaryCodePointsUseUnicodeLength() {
        String comment = "#" + "🛗".repeat(MAX_LINE_CODE_POINTS - 1);
        check(comment.length() > MAX_LINE_CODE_POINTS, "Más unidades UTF-16 que puntos de código");
        check(valid(comment + "\nCABIN 1 2", CONFIG).entries().get(0).lineNumber() == 2, "4096 puntos, comentario válido");
    }
    public static void overlongLinesAreRejectedWithoutTruncation() {
        error("#" + "🛗".repeat(MAX_LINE_CODE_POINTS) + "\nCABIN 1 2", LINE_TOO_LONG, 1);
        error(" ".repeat(MAX_LINE_CODE_POINTS + 1) + "\nCABIN 1 2", LINE_TOO_LONG, 1);
    }
    public static void bareCarriageReturnIsRejected() { error("# cabecera\nCABIN 1 2\rHALL 3 UP", INVALID_LINE_ENDING, 2); }
    public static void unicodeLineSeparatorsAreRejectedEvenInComments() {
        for (char c : new char[] {'\u0085','\u2028','\u2029'}) { error("# " + c + "\nCABIN 1 2", INVALID_LINE_ENDING, 1); }
    }
    public static void diagnosticsUsePhysicalLinesAfterBomAndComments() {
        var result = parse("\uFEFF# inicio\r\n  \n# otro\r\nCABIN 1 2\nHALL 8 UP");
        check(only(result, HALL_UP_AT_TOP).lineNumber().orElseThrow() == 5, "BOM y comentarios no distorsionan línea");
    }
    public static void realPathIsReadAndReleased() throws Exception {
        Path file = Files.createTempFile("t44-", ".datos");
        try {
            Files.writeString(file, "# ñ\nCABIN 2 4", StandardCharsets.UTF_8);
            var batch = READER.read(file, CONFIG).batch().orElseThrow();
            check(batch.entries().get(0).equals(new RequestEntry.Cabin(2, 2, 4)), "Contenido determina validez, no extensión");
            Files.delete(file); // En Windows verifica que la lectura no conserve el handle abierto.
        } finally { Files.deleteIfExists(file); }
    }
    public static void missingPathReturnsReadErrorWithCause() throws Exception {
        Path directory = Files.createTempDirectory("t44-");
        try {
            var diagnostic = only(READER.read(directory.resolve("no-existe.txt"), CONFIG), READ_ERROR);
            check(diagnostic.cause().orElseThrow() instanceof IOException && diagnostic.lineNumber().isEmpty(), "Causa de apertura");
        } finally { Files.delete(directory); }
    }
    public static void directoryCannotBeReadAsRequestFile() throws Exception {
        Path directory = Files.createTempDirectory("t44-");
        try { check(only(READER.read(directory, CONFIG), READ_ERROR).cause().isPresent(), "Causa de lectura de directorio"); }
        finally { Files.delete(directory); }
    }
    public static void streamFailureKeepsOriginalExceptionAndNoPartialBatch() {
        var original = new IOException("causa de lectura inyectada");
        var stream = new ByteArrayInputStream(bytes("CABIN 1 2\n")) {
            private boolean first = true;
            @Override public synchronized int read(byte[] buffer, int offset, int length) {
                if (first) { first = false; return super.read(buffer,offset,length); }
                throw new java.io.UncheckedIOException(original);
            }
        };
        // Adaptador que entrega la IOException original tras un prefijo válido.
        InputStream failing = new InputStream() {
            @Override public int read() throws IOException { throw original; }
            @Override public int read(byte[] buffer, int offset, int length) throws IOException {
                try { return stream.read(buffer,offset,length); }
                catch (java.io.UncheckedIOException error) { throw error.getCause(); }
            }
        };
        var result = READER.read(failing, CONFIG);
        check(only(result, READ_ERROR).cause().orElseThrow() == original && result.batch().isEmpty(), "Causa por identidad, sin parcial");
    }
    public static void borrowedStreamsRemainOpenOnSuccessAndFailure() {
        var closes = new AtomicInteger();
        for (String text : List.of("CABIN 1 2", "HALL 8 UP")) {
            var stream = new ByteArrayInputStream(bytes(text)) { @Override public void close() { closes.incrementAndGet(); } };
            READER.read(stream, CONFIG);
        }
        check(closes.get() == 0, "El llamador conserva propiedad del stream prestado");
    }
    public static void unboundedStreamConsumesOnlyLimitPlusOneBytes() {
        var read = new AtomicInteger();
        InputStream infinite = new InputStream() {
            @Override public int read() { read.incrementAndGet(); return ' '; }
            @Override public int read(byte[] buffer, int offset, int length) {
                java.util.Arrays.fill(buffer, offset, offset + length, (byte)' '); read.addAndGet(length); return length;
            }
        };
        only(READER.read(infinite, CONFIG), FILE_TOO_LARGE);
        check(read.get() == MAX_FILE_BYTES + 1, "No lee indefinidamente ni confía en tamaño previo");
    }
    public static void zeroLengthBulkReadsDoNotLoseBytes() {
        var stream = new ByteArrayInputStream(bytes("CABIN 1 2\nHALL 3 UP")) {
            private boolean zero = true;
            @Override public synchronized int read(byte[] buffer, int offset, int length) {
                zero = !zero; return zero ? 0 : super.read(buffer,offset,Math.min(length,3));
            }
        };
        check(READER.read(stream, CONFIG).batch().orElseThrow().entries().size() == 2, "Fallback de un byte sin bucle vacío");
    }
    public static void interruptionPreservesFlagAndRejectsRead() throws Exception {
        var completion = new CompletableFuture<Boolean>();
        Thread producer = new Thread(() -> {
            try {
                Thread.currentThread().interrupt();
                var diagnostic = only(READER.read(new ByteArrayInputStream(bytes("CABIN 1 2")), CONFIG), READ_ERROR);
                check(diagnostic.cause().orElseThrow() instanceof InterruptedIOException, "Interrupción no se pierde");
                completion.complete(Thread.currentThread().isInterrupted());
            } catch (Throwable error) { completion.completeExceptionally(error); }
            finally { Thread.interrupted(); }
        }, "t44-interrupted-reader");
        producer.start();
        try { check(completion.get(10, TimeUnit.SECONDS), "Flag preservado"); }
        finally { producer.interrupt(); producer.join(10_000); check(!producer.isAlive(), "Productor terminado"); }
    }
    public static void sharedReaderSupportsIndependentConcurrentReads() throws Exception {
        var pool = Executors.newFixedThreadPool(4); var start = new java.util.concurrent.CountDownLatch(1);
        var tasks = new ArrayList<Future<?>>();
        try {
            for (int i = 0; i < 24; i++) {
                int id = i % 2 + 1; int floor = i % 8 + 1;
                tasks.add(pool.submit(() -> {
                    try {
                        check(start.await(10, TimeUnit.SECONDS), "Salida concurrente");
                        var batch = READER.read(new ByteArrayInputStream(bytes("CABIN " + id + " " + floor)), CONFIG).batch().orElseThrow();
                        check(batch.entries().get(0).equals(new RequestEntry.Cabin(1,id,floor)), "Sin estado cruzado entre lecturas");
                    } catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new AssertionError(error); }
                }));
            }
            start.countDown(); for (var task : tasks) { task.get(10, TimeUnit.SECONDS); }
        } finally { start.countDown(); pool.shutdownNow(); check(pool.awaitTermination(10,TimeUnit.SECONDS), "Lectores terminados"); }
    }
    public static void resultAndBatchAreImmutableAndRejectInvalidConstruction() throws Exception {
        var source = new ArrayList<RequestEntry>(); source.add(new RequestEntry.Cabin(1,1,2));
        var batch = new PreparedRequestBatch(CONFIG,source,1,10); source.clear();
        check(batch.entries().size() == 1, "Copia defensiva");
        expect(UnsupportedOperationException.class, () -> batch.entries().clear());
        var invalid = parse("HALL 8 UP"); expect(UnsupportedOperationException.class, () -> invalid.diagnostics().clear());
        expect(IllegalArgumentException.class, () -> new PreparedRequestBatch(CONFIG,List.of(),1,10));
        expect(IllegalArgumentException.class, () -> new PreparedRequestBatch(CONFIG,List.of(new RequestEntry.Cabin(1,3,2)),1,10));
        expect(IllegalArgumentException.class, () -> new PreparedRequestBatch(CONFIG,List.of(new RequestEntry.Cabin(2,1,2),new RequestEntry.Cabin(1,1,2)),2,20));
        expect(IllegalArgumentException.class, () -> new PreparedRequestBatch(CONFIG,batch.entries(),0,10));
        expect(IllegalArgumentException.class, () -> new PreparedRequestBatch(CONFIG,batch.entries(),1,MAX_FILE_BYTES+1));
        expect(IllegalArgumentException.class, () -> new RequestEntry.Hall(0,2,Direction.UP));
        expect(IllegalArgumentException.class, () -> new RequestEntry.Cabin(1,0,2));
        expect(IllegalArgumentException.class, () -> new BatchReadResult(Optional.empty(),List.of()));
        expect(IllegalArgumentException.class, () -> new BatchReadResult(Optional.of(batch),invalid.diagnostics()));
        expect(IllegalArgumentException.class, () -> new BatchDiagnostic(READ_ERROR,OptionalInt.of(0),"causa","formato",Optional.empty()));
        expect(NullPointerException.class, () -> READER.parse(null,CONFIG));
        expect(NullPointerException.class, () -> READER.read((Path)null,CONFIG));
        expect(NullPointerException.class, () -> READER.read((InputStream)null,CONFIG));
        byte[] bytes = bytes("CABIN 1 2"); var parsed = READER.parse(bytes,CONFIG).batch().orElseThrow();
        java.util.Arrays.fill(bytes,(byte)'X'); check(parsed.entries().equals(batch.entries()), "Independiente del array de entrada");
    }
    public static void parserAgreesWithExistingDomainRules() {
        UUID id = new UUID(0,1);
        for (int cabins = 1; cabins <= 3; cabins++) {
            for (int floors = 1; floors <= 8; floors++) {
                var config = new SimulationConfig(cabins,floors,1,1);
                for (int floor = 1; floor <= floors+1; floor++) {
                    for (Direction direction : Direction.values()) {
                        var domain = new HallRequest(id,0,floor,Instant.EPOCH,direction).validateAgainst(config);
                        var result = READER.parse(bytes("HALL " + floor + " " + direction),config);
                        check(result.isValid() == domain.isValid(), "Misma regla HALL");
                        if (!domain.isValid()) { check(result.diagnostics().get(0).code().name().equals(domain.errors().get(0).code()), "Mismo código HALL"); }
                    }
                    for (int cabin = 1; cabin <= cabins+1; cabin++) {
                        var domain = new CabinRequest(id,0,floor,Instant.EPOCH,cabin).validateAgainst(config);
                        var result = READER.parse(bytes("CABIN " + cabin + " " + floor),config);
                        check(result.isValid() == domain.isValid(), "Misma regla CABIN");
                        if (!domain.isValid()) {
                            String expected = cabin > cabins ? "ELEVATOR_OUT_OF_RANGE" : "FLOOR_OUT_OF_RANGE";
                            check(result.diagnostics().get(0).code().name().equals(expected), "Precedencia izquierda a derecha");
                        }
                    }
                }
            }
        }
    }

    private static byte[] documentOfBytes(int size) {
        var text = new StringBuilder("CABIN 1 2\n");
        while (size-text.length() >= MAX_LINE_CODE_POINTS+1) { text.append('#').append("x".repeat(MAX_LINE_CODE_POINTS-1)).append('\n'); }
        int remaining = size-text.length(); if (remaining>0) { text.append('#').append("x".repeat(remaining-1)); }
        byte[] result = bytes(text.toString()); check(result.length==size,"Tamaño de fixture exacto"); return result;
    }
    private static BatchReadResult parse(String text) { return READER.parse(bytes(text),CONFIG); }
    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }
    private static PreparedRequestBatch valid(String text, SimulationConfig config) {
        var result = READER.parse(bytes(text),config); check(result.isValid(),"Debe ser válido: " + result.diagnostics()); return result.batch().orElseThrow();
    }
    private static BatchDiagnostic only(BatchReadResult result, BatchDiagnostic.Code code) {
        check(!result.isValid() && result.batch().isEmpty() && result.diagnostics().size()==1,"Un diagnóstico, ningún lote");
        var diagnostic=result.diagnostics().get(0); check(diagnostic.code()==code,"Código esperado " + code + ", real " + diagnostic); return diagnostic;
    }
    private static void error(String text, BatchDiagnostic.Code code, int line) {
        check(only(parse(text),code).lineNumber().orElseThrow()==line,"Línea física correcta");
    }
    private static void check(boolean condition,String message) { if(!condition) { throw new AssertionError(message); } }
    private interface Action { void run() throws Exception; }
    private static void expect(Class<? extends Throwable> type,Action action) throws Exception {
        try { action.run(); } catch(Throwable error) { if(type.isInstance(error)) { return; } throw new AssertionError("Excepción inesperada",error); }
        throw new AssertionError("Falta excepción " + type.getSimpleName());
    }
    public static void main(String[] args) throws Exception {
        minimalCabinBatch(); mixedBatchPreservesPhysicalOrder(); repeatedEntriesAreNotRemoved(); commentsAllowUnicode();
        asciiSpacesAreAccepted(); mixedLfAndCrlfHaveNoPhantomLine(); finalNewlineIsOptional(); oneInitialBomIsAccepted();
        repeatedBomIsRejected(); interiorBomIsRejectedEvenInComments(); truncatedUtf8HasOriginalCodingCause(); malformedUtf8IsNeverReplaced();
        emptyVariantsRejectWithoutFakeLine(); controlsAreInvalidInsideCommentsAndBlankLines(); unicodeIsNotAcceptedInInstructions();
        unknownInstructionsAndControlsAreRejected(); directionsAreExactUppercaseTokens(); arityIsExactAndInlineCommentsAreRejected();
        signedDecimalAndExponentNumbersAreRejected(); integerOverflowIsRejectedWithoutWrapping(); leadingZerosDoNotImplyNumericOverflow();
        floorBoundsAreChecked(); cabinIdBoundsAreCheckedBeforeFollowingArguments(); impossibleHallDirectionsAreRejected();
        singleFloorBuildingAcceptsOnlyCabinRequests(); everyInvalidLineHasOneOrderedDiagnostic(); finalLineErrorRejectsEntireBatch();
        invalidReadCannotChangePreviousValidBatch(); exactByteLimitIsAccepted(); oneByteOverLimitIsRejected();
        exactlyTenThousandPhysicalLinesAreAccepted(); tenThousandAndOneLinesAreRejected(); supplementaryCodePointsUseUnicodeLength();
        overlongLinesAreRejectedWithoutTruncation(); bareCarriageReturnIsRejected(); unicodeLineSeparatorsAreRejectedEvenInComments();
        diagnosticsUsePhysicalLinesAfterBomAndComments(); realPathIsReadAndReleased(); missingPathReturnsReadErrorWithCause();
        directoryCannotBeReadAsRequestFile(); streamFailureKeepsOriginalExceptionAndNoPartialBatch(); borrowedStreamsRemainOpenOnSuccessAndFailure();
        unboundedStreamConsumesOnlyLimitPlusOneBytes(); zeroLengthBulkReadsDoNotLoseBytes(); interruptionPreservesFlagAndRejectsRead();
        sharedReaderSupportsIndependentConcurrentReads(); resultAndBatchAreImmutableAndRejectInvalidConstruction(); parserAgreesWithExistingDomainRules();
        System.out.println("T44: 48 escenarios satisfactorios.");
    }
}
