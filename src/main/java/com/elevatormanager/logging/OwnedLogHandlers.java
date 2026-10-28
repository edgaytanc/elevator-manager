package com.elevatormanager.logging;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.logging.Handler;
import java.util.logging.Logger;

/** Recursos de logging propiedad de una sesión; no toca handlers ajenos o del padre. */
public final class OwnedLogHandlers implements AutoCloseable {
    private final Logger logger;
    private final List<Handler> handlers;
    private boolean closed;
    private RuntimeException failure;

    public OwnedLogHandlers(Logger logger, List<? extends Handler> handlers) {
        this.logger = Objects.requireNonNull(logger, "logger");
        this.handlers = List.copyOf(handlers);
        Set<Handler> identities = Collections.newSetFromMap(new IdentityHashMap<>());
        for (var handler : this.handlers) {
            if (!identities.add(handler)) { throw new IllegalArgumentException("Handler repetido por identidad."); }
        }
    }

    /** Desconecta, vacía y cierra cada handler propio; completa los intentos ante fallos. */
    @Override public synchronized void close() {
        if (closed) {
            if (failure != null) { throw failure; }
            return;
        }
        closed = true;
        var errors = new java.util.ArrayList<RuntimeException>();
        for (int i = handlers.size() - 1; i >= 0; i--) {
            Handler handler = handlers.get(i);
            try { logger.removeHandler(handler); } catch (RuntimeException error) { errors.add(error); }
            try { handler.flush(); } catch (RuntimeException error) { errors.add(error); }
            try { handler.close(); } catch (RuntimeException error) { errors.add(error); }
        }
        if (!errors.isEmpty()) {
            failure = new IllegalStateException("No se confirmó el cierre de todos los handlers propios.");
            errors.forEach(failure::addSuppressed);
            throw failure;
        }
    }
}
