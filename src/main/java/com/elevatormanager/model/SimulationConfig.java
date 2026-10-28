package com.elevatormanager.model;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/**
 * Configuración inmutable compartida por todos los trabajadores de una sesión.
 * Los tiempos se expresan en milisegundos y admiten conversión exacta a nanos.
 */
public record SimulationConfig(
        int elevatorCount,
        int floorCount,
        long movementTimeMillis,
        long dwellTimeMillis) {

    public static final long MAX_TIME_MILLIS = Long.MAX_VALUE / 1_000_000L;

    public SimulationConfig {
        requirePositive(elevatorCount, "Cantidad de elevadores");
        requirePositive(floorCount, "Cantidad de pisos");
        requireTime(movementTimeMillis, "Tiempo de movimiento");
        requireTime(dwellTimeMillis, "Tiempo de parada");
    }

    /** Convierte entradas de formulario sin crear una configuración parcial. */
    public static SimulationConfig fromText(
            String elevatorCount, String floorCount,
            String movementTimeMillis, String dwellTimeMillis) {
        return new SimulationConfig(
                parseCount(elevatorCount, "Cantidad de elevadores"),
                parseCount(floorCount, "Cantidad de pisos"),
                parseLong(movementTimeMillis, "Tiempo de movimiento"),
                parseLong(dwellTimeMillis, "Tiempo de parada"));
    }

    /** Campos estables para asociar cada diagnóstico a su control gráfico. */
    public enum Field { ELEVATORS, FLOORS, MOVEMENT, DWELL }

    /** Valida todos los campos con las mismas reglas que fromText, sin crear sesión. */
    public static Map<Field, String> textErrors(String elevators, String floors,
            String movement, String dwell) {
        var errors = new EnumMap<Field, String>(Field.class);
        collect(errors, Field.ELEVATORS, () -> parseCount(elevators, "Cantidad de elevadores"));
        collect(errors, Field.FLOORS, () -> parseCount(floors, "Cantidad de pisos"));
        collect(errors, Field.MOVEMENT, () -> validateTimeText(movement, "Tiempo de movimiento"));
        collect(errors, Field.DWELL, () -> validateTimeText(dwell, "Tiempo de parada"));
        return Collections.unmodifiableMap(errors);
    }

    private static void validateTimeText(String text, String field) {
        requireTime(parseLong(text, field), field);
    }

    private static void collect(Map<Field, String> errors, Field field, Runnable check) {
        try {
            check.run();
        } catch (IllegalArgumentException exception) {
            errors.put(field, exception.getMessage());
        }
    }

    private static void requirePositive(int value, String field) {
        if (value < 1) {
            throw new IllegalArgumentException(field + " debe ser mayor que cero.");
        }
    }

    private static void requireTime(long value, String field) {
        if (value < 1 || value > MAX_TIME_MILLIS) {
            throw new IllegalArgumentException(
                    field + " debe estar entre 1 y " + MAX_TIME_MILLIS + " ms.");
        }
    }

    private static int parseCount(String text, String field) {
        long value = parseLong(text, field);
        if (value < 1 || value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    field + " debe estar entre 1 y " + Integer.MAX_VALUE + ".");
        }
        return (int) value;
    }

    private static long parseLong(String text, String field) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException(field + " es obligatorio.");
        }
        try {
            return Long.parseLong(text.strip());
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(
                    field + " debe ser un entero decimal dentro del rango de long.",
                    exception);
        }
    }
}
