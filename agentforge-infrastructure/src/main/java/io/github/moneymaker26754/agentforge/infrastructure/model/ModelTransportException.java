package io.github.moneymaker26754.agentforge.infrastructure.model;

public final class ModelTransportException extends RuntimeException {
    public ModelTransportException(String message) {
        super(message);
    }

    public ModelTransportException(String message, Throwable cause) {
        super(message, cause);
    }
}

