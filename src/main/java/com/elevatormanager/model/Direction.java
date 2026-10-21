package com.elevatormanager.model;

/** Sentido del recorrido; se conserva aunque la cabina esté inmóvil. */
public enum Direction {
    UP(1),
    DOWN(-1);

    private final int floorDelta;

    Direction(int floorDelta) {
        this.floorDelta = floorDelta;
    }

    /** Desplazamiento de un tramo; el modelo debe validar los límites del edificio. */
    public int floorDelta() {
        return floorDelta;
    }

    /** Devuelve el sentido opuesto sin modificar una cabina ni autorizar un giro. */
    public Direction opposite() {
        return this == UP ? DOWN : UP;
    }
}
