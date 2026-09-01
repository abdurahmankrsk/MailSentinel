package com.mailsentinel.ratelimit;

import java.time.Duration;

/**
 * The verdict on one attempt, plus how long until the caller may try again.
 *
 * {@code retryAfter} is carried even when allowed (as the remaining window) so a
 * caller never has to recompute it, and is what fills the {@code Retry-After} header
 * on a 429.
 */
public record RateLimitDecision(boolean allowed, Duration retryAfter) {

    static RateLimitDecision allowed(Duration remaining) {
        return new RateLimitDecision(true, remaining);
    }

    static RateLimitDecision denied(Duration remaining) {
        return new RateLimitDecision(false, remaining);
    }

    /** Whole seconds, rounded up and never below 1 -- {@code Retry-After: 0} invites an instant retry. */
    public long retryAfterSeconds() {
        long seconds = (retryAfter.toMillis() + 999) / 1000;
        return Math.max(1, seconds);
    }
}
