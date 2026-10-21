package com.elevatormanager.model;

import java.util.Objects;

/** Diagnóstico de dominio con código estable y explicación legible. */
public record ValidationError(String code, String message) {
    public ValidationError {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(message, "message");
        if (code.isBlank() || message.isBlank()) {
            throw new IllegalArgumentException("Código y mensaje son obligatorios.");
        }
    }
}
