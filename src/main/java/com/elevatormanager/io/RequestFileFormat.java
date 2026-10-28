package com.elevatormanager.io;

/** Límites del contrato documental 07, versión 1.0.0. */
public final class RequestFileFormat {
    public static final String VERSION = "1.0.0";
    public static final int MAX_FILE_BYTES = 1_048_576;
    public static final int MAX_LINES = 10_000;
    public static final int MAX_LINE_CODE_POINTS = 4_096;
    private RequestFileFormat() { }
}
