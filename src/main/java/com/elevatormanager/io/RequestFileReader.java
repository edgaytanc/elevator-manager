package com.elevatormanager.io;

import com.elevatormanager.model.Direction;
import com.elevatormanager.model.SimulationConfig;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import static com.elevatormanager.io.BatchDiagnostic.Code.*;
import static com.elevatormanager.io.RequestFileFormat.*;

/**
 * Parser síncrono con configuración inmutable. Invocar lecturas fuera del EDT.
 * No confirma importaciones ni conoce el controlador o los trabajadores.
 */
public final class RequestFileReader {
    @FunctionalInterface interface InputOpener { InputStream open(Path path) throws IOException; }
    private final InputOpener opener;

    public RequestFileReader() { this(path -> Files.newInputStream(path)); }

    /** Punto de prueba del paquete para fallos de apertura/lectura/cierre, sin modificar permisos. */
    RequestFileReader(InputOpener opener) { this.opener = Objects.requireNonNull(opener, "opener"); }

    private static final String GRAMMAR = "HALL <piso> <UP|DOWN> o CABIN <idElevador> <piso>";

    /** Abre y cierra su propio stream, incluso ante límite, interrupción o error. */
    public BatchReadResult read(Path path, SimulationConfig config) {
        Objects.requireNonNull(path, "path"); Objects.requireNonNull(config, "config");
        try {
            checkInterrupted();
            byte[] content;
            try (InputStream input = opener.open(path)) { content = readBounded(input); }
            return parse(content, config);
        } catch (IOException | SecurityException error) { return readError(error); }
    }

    /** Lee desde la posición actual; el stream prestado se conserva abierto y pertenece al llamador. */
    public BatchReadResult read(InputStream input, SimulationConfig config) {
        Objects.requireNonNull(input, "input"); Objects.requireNonNull(config, "config");
        try { return parse(readBounded(input), config); }
        catch (IOException | SecurityException error) { return readError(error); }
    }

    /** Copia de entrada acotada y decodificación estricta; resultado independiente del array original. */
    public BatchReadResult parse(byte[] content, SimulationConfig config) {
        Objects.requireNonNull(content, "content"); Objects.requireNonNull(config, "config");
        if (content.length > MAX_FILE_BYTES) {
            return failure(FILE_TOO_LARGE, OptionalInt.empty(), "El archivo supera 1 MiB.", "Hasta " + MAX_FILE_BYTES + " bytes.", null);
        }
        try { checkInterrupted(); } catch (InterruptedIOException error) { return readError(error); }
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(content.clone())).toString();
        } catch (CharacterCodingException error) {
            return failure(INVALID_UTF8, OptionalInt.empty(), "El archivo no contiene UTF-8 válido.", "UTF-8 estricto, sin sustitución de bytes.", error);
        }
        if (text.startsWith("\uFEFF")) { text = text.substring(1); }
        var entries = new ArrayList<RequestEntry>();
        var errors = new ArrayList<BatchDiagnostic>();
        int start = 0;
        int lineNumber = 1;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\r' && (i + 1 == text.length() || text.charAt(i + 1) != '\n')
                    || c == '\u0085' || c == '\u2028' || c == '\u2029') {
                return failure(INVALID_LINE_ENDING, OptionalInt.of(lineNumber),
                        "Terminador de línea no permitido.", "Utilizar LF o CRLF.", null);
            }
            if (c == '\n' || c == '\r') {
                if (lineNumber > MAX_LINES) { return tooManyLines(); }
                if (Thread.currentThread().isInterrupted()) { return interrupted(); }
                parseLine(text.substring(start, i), lineNumber, config, entries, errors);
                if (c == '\r') { i++; }
                start = i + 1;
                lineNumber++;
            }
        }
        if (start < text.length()) {
            if (lineNumber > MAX_LINES) { return tooManyLines(); }
            if (Thread.currentThread().isInterrupted()) { return interrupted(); }
            parseLine(text.substring(start), lineNumber, config, entries, errors);
            lineNumber++;
        }
        if (!errors.isEmpty()) { return BatchReadResult.invalid(errors); }
        if (entries.isEmpty()) {
            return failure(EMPTY_BATCH, OptionalInt.empty(), "El archivo no contiene instrucciones.", "Incluir al menos una instrucción: " + GRAMMAR, null);
        }
        return BatchReadResult.valid(new PreparedRequestBatch(config, entries, lineNumber - 1, content.length));
    }

    /** A lo sumo límite + 1 bytes, también si el archivo crece o el stream no termina. */
    private static byte[] readBounded(InputStream input) throws IOException {
        var output = new ByteArrayOutputStream(8192);
        var buffer = new byte[8192];
        while (output.size() <= MAX_FILE_BYTES) {
            checkInterrupted();
            int count = input.read(buffer, 0, Math.min(buffer.length, MAX_FILE_BYTES + 1 - output.size()));
            if (count < 0) { break; }
            if (count == 0) {
                int value = input.read();
                if (value < 0) { break; }
                output.write(value);
            } else { output.write(buffer, 0, count); }
        }
        return output.toByteArray();
    }

    private static void parseLine(String line, int number, SimulationConfig config,
            List<RequestEntry> entries, List<BatchDiagnostic> errors) {
        try {
            if (line.indexOf('\uFEFF') >= 0) { throw issue(INVALID_BOM, "BOM repetido o fuera del inicio del archivo."); }
            int first = 0;
            while (first < line.length() && line.charAt(first) == ' ') { first++; }
            boolean comment = first < line.length() && line.charAt(first) == '#';
            for (int i = 0; i < line.length();) {
                int cp = line.codePointAt(i);
                if (Character.isISOControl(cp) || (!comment && cp > 126)) {
                    throw issue(INVALID_CHARACTER, "Carácter prohibido; se requieren separadores ASCII y texto permitido.");
                }
                i += Character.charCount(cp);
            }
            if (line.codePointCount(0, line.length()) > MAX_LINE_CODE_POINTS) {
                throw issue(LINE_TOO_LONG, "La línea supera " + MAX_LINE_CODE_POINTS + " puntos de código Unicode.");
            }
            if (comment || first == line.length()) { return; }
            int last = line.length();
            while (line.charAt(last - 1) == ' ') { last--; }
            String[] tokens = line.substring(first, last).split(" +");
            String kind = tokens[0];
            if (!kind.equals("HALL") && !kind.equals("CABIN")) { throw issue(UNKNOWN_INSTRUCTION, "Instrucción desconocida."); }
            if (tokens.length != 3) { throw issue(INVALID_ARITY, "Se requieren exactamente tres tokens, sin comentario final."); }
            String expected = kind.equals("HALL") ? "HALL <piso> <UP|DOWN>" : "CABIN <idElevador> <piso>";
            RequestEntry entry;
            if (kind.equals("HALL")) {
                int floor = integer(tokens[1]);
                if (floor < 1 || floor > config.floorCount()) { throw issue(FLOOR_OUT_OF_RANGE, "El piso debe estar entre 1 y " + config.floorCount() + "."); }
                if (!tokens[2].equals("UP") && !tokens[2].equals("DOWN")) { throw issue(INVALID_DIRECTION, "La dirección debe ser UP o DOWN."); }
                entry = new RequestEntry.Hall(number, floor, Direction.valueOf(tokens[2]));
            } else {
                int id = integer(tokens[1]);
                if (id < 1 || id > config.elevatorCount()) { throw issue(ELEVATOR_OUT_OF_RANGE, "El ID debe estar entre 1 y " + config.elevatorCount() + "."); }
                int floor = integer(tokens[2]);
                if (floor < 1 || floor > config.floorCount()) { throw issue(FLOOR_OUT_OF_RANGE, "El piso debe estar entre 1 y " + config.floorCount() + "."); }
                entry = new RequestEntry.Cabin(number, id, floor);
            }
            var validation = entry.validateAgainst(config);
            if (!validation.isValid()) {
                var error = validation.errors().get(0);
                throw new LineIssue(BatchDiagnostic.Code.valueOf(error.code()), error.message(), expected);
            }
            entries.add(entry);
        } catch (LineIssue error) {
            errors.add(new BatchDiagnostic(error.code, OptionalInt.of(number), error.getMessage(), error.expected, Optional.empty()));
        }
    }

    private static int integer(String token) throws LineIssue {
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            if (c < '0' || c > '9') { throw issue(INVALID_INTEGER, "Se requieren dígitos ASCII sin signo ni decimales."); }
        }
        int value = 0;
        for (int i = 0; i < token.length(); i++) {
            int digit = token.charAt(i) - '0';
            if (value > (Integer.MAX_VALUE - digit) / 10) { throw issue(INTEGER_OUT_OF_RANGE, "El valor supera " + Integer.MAX_VALUE + "."); }
            value = value * 10 + digit;
        }
        return value;
    }
    private static LineIssue issue(BatchDiagnostic.Code code, String message) { return new LineIssue(code, message, GRAMMAR); }
    private static final class LineIssue extends Exception {
        private static final long serialVersionUID = 1L;
        final BatchDiagnostic.Code code;
        final String expected;
        LineIssue(BatchDiagnostic.Code code, String message, String expected) { super(message); this.code = code; this.expected = expected; }
    }
    private static BatchReadResult failure(BatchDiagnostic.Code code, OptionalInt line, String message, String expected, Exception cause) {
        return BatchReadResult.invalid(List.of(new BatchDiagnostic(code, line, message, expected, Optional.ofNullable(cause))));
    }
    private static BatchReadResult tooManyLines() {
        return failure(TOO_MANY_LINES, OptionalInt.of(MAX_LINES + 1), "El archivo supera " + MAX_LINES + " líneas físicas.", "Hasta " + MAX_LINES + " líneas, incluidos comentarios y vacías.", null);
    }
    private static BatchReadResult readError(Exception error) {
        return failure(READ_ERROR, OptionalInt.empty(), "No se pudo completar la lectura del archivo.", "Archivo legible y lectura no interrumpida.", error);
    }
    private static BatchReadResult interrupted() { return readError(new InterruptedIOException("Lectura o análisis interrumpido.")); }
    private static void checkInterrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) { throw new InterruptedIOException("Lectura o análisis interrumpido."); }
    }
}
