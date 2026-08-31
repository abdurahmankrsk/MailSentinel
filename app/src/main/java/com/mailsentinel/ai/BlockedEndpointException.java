package com.mailsentinel.ai;

/**
 * A user-supplied AI endpoint that OutboundUrlGuard refused to let this server call.
 *
 * Carries two messages on purpose. {@link #getMessage()} is the operator-facing
 * detail (which host, which resolved address, why) and belongs in the log only;
 * {@link #userMessage()} is the fixed, non-specific text that goes back over the
 * wire. Reflecting the real reason would hand the caller exactly the oracle the
 * guard exists to remove -- "blocked: loopback" versus "could not resolve" already
 * distinguishes an open internal host from a closed one.
 */
public class BlockedEndpointException extends RuntimeException {

    private final String userMessage;

    public BlockedEndpointException(String detail, String userMessage) {
        super(detail);
        this.userMessage = userMessage;
    }

    public String userMessage() {
        return userMessage;
    }
}
