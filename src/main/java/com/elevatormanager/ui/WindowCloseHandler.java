package com.elevatormanager.ui;

import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.swing.SwingUtilities;

/** Une botón y X a una única solicitud; callbacks visuales exclusivamente en EDT. */
public final class WindowCloseHandler {
    private final Supplier<? extends CompletionStage<?>> closeAction;
    private final Runnable onClosing;
    private final Runnable onClosed;
    private final Consumer<Throwable> onFailure;
    private boolean closing;
    private boolean closed;

    public WindowCloseHandler(Supplier<? extends CompletionStage<?>> closeAction,
            Runnable onClosing, Runnable onClosed, Consumer<Throwable> onFailure) {
        this.closeAction = Objects.requireNonNull(closeAction, "closeAction");
        this.onClosing = Objects.requireNonNull(onClosing, "onClosing");
        this.onClosed = Objects.requireNonNull(onClosed, "onClosed");
        this.onFailure = Objects.requireNonNull(onFailure, "onFailure");
    }

    public void requestClose() {
        requireEdt();
        if (closing || closed) { return; }
        closing = true;
        try {
            onClosing.run();
            Objects.requireNonNull(closeAction.get(), "closeStage").whenComplete((ignored, error) ->
                    SwingUtilities.invokeLater(() -> finish(error)));
        } catch (RuntimeException exception) { finish(exception); }
    }

    private void finish(Throwable error) {
        requireEdt();
        if (error == null) {
            closing = false;
            closed = true;
            onClosed.run();
        } else {
            closing = false;
            Throwable cause = error instanceof CompletionException && error.getCause() != null
                    ? error.getCause() : error;
            onFailure.accept(cause);
        }
    }

    public boolean isClosing() { requireEdt(); return closing; }
    public boolean isClosed() { requireEdt(); return closed; }

    private static void requireEdt() {
        if (!SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("El control visual de cierre requiere EDT.");
        }
    }
}
