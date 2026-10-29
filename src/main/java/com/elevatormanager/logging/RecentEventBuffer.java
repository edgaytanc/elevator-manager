package com.elevatormanager.logging;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** T51: copia reciente acotada para inspección; no reemplaza la cola fiable ni los archivos. */
public final class RecentEventBuffer {
    public static final int MAX_EVENTS = 500;
    public static final int MAX_TEXT_CHARS = 1200;
    public static final int MAX_TOTAL_CHARS = 300_000;
    public static final int MAX_BATCH = 200;
    public record Event(long sequence, Instant at, String actor, String level, String text) { }
    public record Batch(List<Event> events, long nextCursor, long skipped) {
        public Batch { events = List.copyOf(events); }
    }
    private final ArrayDeque<Event> events = new ArrayDeque<>();
    private long sequence;
    private int textChars;

    /** Solo texto limitado; no retiene Throwable, snapshots ni referencias a trabajadores. */
    public synchronized void append(Instant at, String actor, String level, String text) {
        Objects.requireNonNull(at, "at");
        String bounded = singleLine(text, MAX_TEXT_CHARS);
        String boundedActor = singleLine(actor, 64);
        String boundedLevel = singleLine(level, 32);
        var event = new Event(++sequence, at, boundedActor, boundedLevel, bounded);
        events.addLast(event); textChars += bounded.length();
        while (events.size() > MAX_EVENTS || textChars > MAX_TOTAL_CHARS) {
            textChars -= events.removeFirst().text().length();
        }
    }

    /** Lectura no destructiva por cursor; varios lectores no se roban registros. */
    public synchronized Batch after(long cursor, int limit) {
        if (cursor < 0 || limit < 1 || limit > MAX_BATCH) throw new IllegalArgumentException("Cursor o límite inválido.");
        long skipped = events.isEmpty() ? 0 : Math.max(0, events.getFirst().sequence() - 1 - cursor);
        var result = new ArrayList<Event>();
        for (var event : events) {
            if (event.sequence() > cursor) {
                result.add(event);
                if (result.size() == limit) break;
            }
        }
        return new Batch(result, result.isEmpty() ? cursor : result.get(result.size() - 1).sequence(), skipped);
    }

    public synchronized long lastSequence() { return sequence; }
    public synchronized int retainedCount() { return events.size(); }
    public synchronized int retainedTextChars() { return textChars; }

    private static String singleLine(String text, int maximum) {
        Objects.requireNonNull(text, "text");
        return text.substring(0, Math.min(text.length(), maximum)).replace('\n', ' ').replace('\r', ' ');
    }
}
