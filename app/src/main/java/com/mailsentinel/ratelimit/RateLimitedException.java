package com.mailsentinel.ratelimit;

/**
 * Thrown from a handler that limits on its own outcome rather than on the request
 * (see LoginThrottle). RateLimitFilter writes its own 429 directly, since it refuses
 * the request before any handler runs.
 */
public class RateLimitedException extends RuntimeException {

    private final long retryAfterSeconds;

    public RateLimitedException(long retryAfterSeconds) {
        super("Rate limit exceeded; retry after " + retryAfterSeconds + "s");
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
