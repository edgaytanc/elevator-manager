package com.elevatormanager.io;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/** Diagnóstico sin volcar el contenido del archivo; causa original cuando procede. */
public record BatchDiagnostic(Code code, OptionalInt lineNumber, String message,
        String expectedFormat, Optional<Exception> cause) {
    public enum Code {
        READ_ERROR, INVALID_UTF8, FILE_TOO_LARGE, TOO_MANY_LINES, LINE_TOO_LONG,
        INVALID_BOM, INVALID_LINE_ENDING, INVALID_CHARACTER, UNKNOWN_INSTRUCTION,
        INVALID_ARITY, INVALID_INTEGER, INTEGER_OUT_OF_RANGE, FLOOR_OUT_OF_RANGE,
        ELEVATOR_OUT_OF_RANGE, INVALID_DIRECTION, HALL_UP_AT_TOP, HALL_DOWN_AT_BOTTOM, EMPTY_BATCH
    }
    public BatchDiagnostic {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(lineNumber, "lineNumber");
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(expectedFormat, "expectedFormat");
        Objects.requireNonNull(cause, "cause");
        if ((lineNumber.isPresent() && lineNumber.getAsInt() < 1)
                || message.isBlank() || expectedFormat.isBlank()) {
            throw new IllegalArgumentException("Diagnóstico sin línea válida, causa legible o formato esperado.");
        }
    }
}
