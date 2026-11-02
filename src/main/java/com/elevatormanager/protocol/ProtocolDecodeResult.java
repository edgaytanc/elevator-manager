package com.elevatormanager.protocol;

import java.util.Objects;
import java.util.Optional;

/** El parser no lanza por entrada malformada ni genera respuestas automáticamente. */
public record ProtocolDecodeResult(Optional<ProtocolMessage> message, Optional<Failure> failure) {
    public ProtocolDecodeResult {
        Objects.requireNonNull(message, "message"); Objects.requireNonNull(failure, "failure");
        if (message.isPresent() == failure.isPresent()) {
            throw new IllegalArgumentException("Debe existir mensaje o fallo, exclusivamente.");
        }
    }

    public boolean isSuccess() { return message.isPresent(); }

    public static ProtocolDecodeResult success(ProtocolMessage message) {
        return new ProtocolDecodeResult(Optional.of(message), Optional.empty());
    }

    public static ProtocolDecodeResult rejected(Failure failure) {
        return new ProtocolDecodeResult(Optional.empty(), Optional.of(failure));
    }

    /** Vista limitada y escapada; no retiene la línea original ni Throwable arbitrario. */
    public record Failure(ProtocolMessage.ErrorCode code, ProtocolMessage.Header header,
            String detail, String preview) {
        public Failure {
            Objects.requireNonNull(code, "code"); Objects.requireNonNull(header, "header");
            Objects.requireNonNull(detail, "detail"); Objects.requireNonNull(preview, "preview");
            if (detail.length() > 256 || preview.length() > 256) {
                throw new IllegalArgumentException("Diagnóstico demasiado extenso.");
            }
            new ProtocolMessage.Error(header, code);
        }

        /** Solo el receptor de órdenes decide enviarlo; nunca contestar ERROR a ERROR. */
        public ProtocolMessage.Error asErrorMessage() { return new ProtocolMessage.Error(header, code); }
    }
}
