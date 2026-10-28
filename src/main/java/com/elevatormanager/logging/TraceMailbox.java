package com.elevatormanager.logging;

import com.elevatormanager.dispatch.HallCallSnapshot;
import com.elevatormanager.dispatch.ManagerWorker;
import com.elevatormanager.simulation.ExecutionEvent;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;

/** Cola adicional fiable en memoria. Sus productores solo encolan: sin I/O ni callbacks. */
public final class TraceMailbox {
    sealed interface Entry permits Cabin, Decision, CallUpdate, ManagerState, Barrier, End { }
    record Cabin(Instant at, ExecutionEvent event, Throwable failure) implements Entry { }
    record Decision(Instant at, long sequence, ManagerWorker.Result result) implements Entry { }
    record CallUpdate(Instant at, long sequence, HallCallSnapshot call) implements Entry { }
    record ManagerState(Instant at, long sequence, long epoch, String state, Throwable failure) implements Entry { }
    record Barrier(CompletableFuture<Void> completion) implements Entry { }
    record End() implements Entry { }
    private static final TraceMailbox DISABLED = new TraceMailbox(false);
    private final boolean enabled;
    private final LinkedBlockingQueue<Entry> queue = new LinkedBlockingQueue<>();
    private boolean sealed;
    private long managerSequence;

    public TraceMailbox() { this(true); }
    private TraceMailbox(boolean enabled) { this.enabled = enabled; }
    public static TraceMailbox disabled() { return DISABLED; }

    public synchronized void cabin(ExecutionEvent event, Throwable failure) {
        if (enabled) { add(new Cabin(Instant.now(), Objects.requireNonNull(event, "event"), failure)); }
    }
    public synchronized void decision(ManagerWorker.Result result) {
        if (enabled) { add(new Decision(Instant.now(), ++managerSequence, Objects.requireNonNull(result, "result"))); }
    }
    public synchronized void call(HallCallSnapshot call) {
        if (enabled) { add(new CallUpdate(Instant.now(), ++managerSequence, Objects.requireNonNull(call, "call"))); }
    }
    public synchronized void managerState(long epoch, String state, Throwable failure) {
        if (enabled) { add(new ManagerState(Instant.now(), ++managerSequence, epoch, Objects.requireNonNull(state, "state"), failure)); }
    }
    synchronized CompletableFuture<Void> barrier() {
        var future = new CompletableFuture<Void>();
        add(new Barrier(future));
        return future.copy();
    }
    synchronized void seal() {
        if (!sealed) { sealed = true; queue.add(new End()); }
    }
    Entry take() throws InterruptedException { return queue.take(); }
    private void add(Entry entry) {
        if (sealed) { throw new IllegalStateException("El canal de logging ya está cerrado."); }
        queue.add(entry);
    }
}
