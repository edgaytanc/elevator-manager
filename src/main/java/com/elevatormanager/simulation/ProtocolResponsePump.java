package com.elevatormanager.simulation;

import com.elevatormanager.protocol.ProtocolReplyTracker;
import java.time.Duration;

/** Un consumidor por sesión; ningún callback de futuros se ejecuta en una cabina. */
final class ProtocolResponsePump implements AutoCloseable {
    final ProtocolMailbox mailbox = new ProtocolMailbox();
    final ProtocolReplyTracker tracker;
    private final Thread thread;
    private volatile boolean closing;
    private boolean started;
    ProtocolResponsePump(ProtocolReplyTracker tracker) {
        this.tracker = tracker;
        thread = new Thread(this::run, "protocol-responses");
    }
    synchronized void start() { if (!started) { started = true; thread.start(); } }
    private void run() {
        try {
            while (true) {
                var frame = closing ? mailbox.await(Duration.ZERO) : mailbox.await();
                if (frame.isPresent()) { tracker.receive(frame.orElseThrow()); }
                else if (closing) { break; }
            }
        } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
        finally { tracker.close(); }
    }
    @Override public void close() { closing = true; mailbox.wake(); }
    boolean isConsumerThread() { return Thread.currentThread() == thread; }
    void join() throws InterruptedException { if (started) { thread.join(); } else { tracker.close(); } }
}
