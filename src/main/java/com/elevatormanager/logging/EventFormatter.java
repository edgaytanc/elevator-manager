package com.elevatormanager.logging;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.logging.Formatter;
import java.util.logging.LogRecord;

/** Formato independiente del idioma local; la fecha ISO-8601 se expresa en UTC. */
public final class EventFormatter extends Formatter {
    private final String actor;

    public EventFormatter(int elevatorId) {
        if (elevatorId < 1) { throw new IllegalArgumentException("ID de elevador no positivo."); }
        this.actor = "elevador=" + elevatorId;
    }

    private EventFormatter(String actor) { this.actor = actor; }

    public static EventFormatter forManager() { return new EventFormatter("manejador"); }

    @Override public String format(LogRecord record) {
        Objects.requireNonNull(record, "record");
        String message = String.valueOf(formatMessage(record)).replace("\r", "\\r").replace("\n", "\\n");
        String text = DateTimeFormatter.ISO_INSTANT.format(record.getInstant())
                + " [" + record.getLevel().getName() + "] [" + actor + "] "
                + message + System.lineSeparator();
        if (record.getThrown() != null) {
            var trace = new StringWriter();
            record.getThrown().printStackTrace(new PrintWriter(trace));
            text += trace;
        }
        return text;
    }
}
