package com.elevatormanager.ui;

import java.util.Objects;
import java.util.function.LongSupplier;

/** T52: frecuencia monotónica, sin sleeps ni trabajo acumulado para recuperar ticks perdidos. */
final class RefreshGate {
    private final long interval;
    private final LongSupplier clock;
    private boolean started;
    private long previous;
    RefreshGate(long interval, LongSupplier clock) {
        if (interval < 1) throw new IllegalArgumentException("Intervalo no positivo.");
        this.interval = interval; this.clock = Objects.requireNonNull(clock, "clock");
    }
    boolean due() {
        long now = clock.getAsLong();
        if (started && now - previous < interval) return false;
        started = true; previous = now; return true;
    }
}
