package com.elevatormanager.model;

/** Estado global de una sesión, publicado por el controlador. */
public enum SimulationState {
    /** Cabinas en piso 1/UP, sin trabajo activo; puede existir un lote preparado. */
    RESET,
    /** Ejecución y admisión manual habilitadas. */
    RUNNING,
    /** Detención segura solicitada, todavía pendiente de confirmaciones. */
    STOPPING,
    /** Detención confirmada; se conservan los pendientes. */
    STOPPED,
    /** Congelación solicitada, todavía pendiente de confirmaciones. */
    PAUSING,
    /** Congelación confirmada; se conservan progreso y tiempo restante. */
    PAUSED,
    /** Limpieza y retorno físico a piso 1 en curso. */
    RESETTING,
    /** Terminación de trabajadores y liberación de recursos en curso. */
    CLOSING,
    /** Trabajadores terminados y recursos liberados. */
    CLOSED;

    /** Condición de estado; no sustituye validación, época ni admisión serializada. */
    public boolean acceptsManualRequests() {
        return this == RUNNING;
    }

    /** La confirmación de un lote también debe comprobar época y operación vigente. */
    public boolean allowsRequestImport() {
        return this == RESET;
    }
}
