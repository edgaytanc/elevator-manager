package com.elevatormanager.logging;

import java.time.Instant;
import java.util.Objects;

/** Primera incidencia del logging; permanece consultable aunque falle la consola. */
public record LogFailure(Instant detectedAt, String actor, String destination,
        int code, String message, Exception cause) {
    public LogFailure {
        Objects.requireNonNull(detectedAt, "detectedAt");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(destination, "destination");
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(cause, "cause");
    }
}
