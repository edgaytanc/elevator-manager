package com.elevatormanager.protocol;

import java.util.HashMap;
import java.util.Map;

/** Registro corto bajo monitor, sin acceder a actores ni ejecutar callbacks. Una instancia por sesión. */
public final class ProtocolIdentities {
    private final Map<Long, ProtocolMessage> admitted = new HashMap<>();
    public synchronized boolean reserve(ProtocolMessage message) {
        ProtocolMessage old = admitted.get(message.header().id());
        if (old != null) { return old.equals(message); }
        admitted.put(message.header().id(), message); return true;
    }
    public synchronized boolean conflicts(ProtocolMessage message) {
        ProtocolMessage old = admitted.get(message.header().id());
        return old != null && !old.equals(message);
    }
    public synchronized void release(ProtocolMessage message) { admitted.remove(message.header().id(), message); }
}
