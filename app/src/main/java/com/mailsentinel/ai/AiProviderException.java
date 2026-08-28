package com.mailsentinel.ai;

/**
 * Checked on purpose: every AiProvider failure (timeout, non-2xx, malformed/empty
 * response) is an expected, first-class outcome the caller must consciously handle
 * (refund the reserved scan, mark the idempotency record failed), not something to
 * let propagate as an unchecked runtime error.
 */
public class AiProviderException extends Exception {
    public AiProviderException(String message) {
        super(message);
    }

    public AiProviderException(String message, Throwable cause) {
        super(message, cause);
    }
}
