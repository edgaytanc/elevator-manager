package com.elevatormanager.model;

/** Fase local de una cabina; no determina su dirección ni el estado global. */
public enum ElevatorPhase {
    /** Alineada con un piso, sin tramo ni parada activa; conserva su dirección. */
    IDLE,
    /** Tramo entre pisos adyacentes en ejecución. */
    MOVING,
    /** Parada de servicio en curso. */
    DWELLING,
    /** Fase suspendida; el estado de cabina debe conservar fase previa y restante. */
    HELD,
    /** El trabajador de esta cabina terminó. */
    TERMINATED
}
