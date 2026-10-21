package com.elevatormanager.model;

import java.util.List;
import java.util.stream.Stream;

/** Resultado inmutable: una lista vacía representa validación correcta. */
public record ValidationResult(List<ValidationError> errors) {
    public ValidationResult {
        errors = List.copyOf(errors);
    }

    public static ValidationResult valid() {
        return new ValidationResult(List.of());
    }

    public static ValidationResult invalid(String code, String message) {
        return new ValidationResult(List.of(new ValidationError(code, message)));
    }

    public boolean isValid() {
        return errors.isEmpty();
    }

    /** Conserva primero los errores de este resultado y luego los del otro. */
    public ValidationResult combine(ValidationResult other) {
        return new ValidationResult(Stream.concat(errors.stream(), other.errors.stream()).toList());
    }
}
