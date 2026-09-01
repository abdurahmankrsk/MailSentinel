package com.mailsentinel.ratelimit;

import java.time.Duration;

/**
 * One quota: {@code limit} events per {@code window}.
 *
 * @param limit  how many events the window allows before further ones are refused
 * @param window how long the count is kept before it starts again
 */
public record RateLimitPolicy(int limit, Duration window) {

    public RateLimitPolicy {
        if (limit < 1) {
            throw new IllegalArgumentException("A rate-limit policy must allow at least one request");
        }
        if (window == null || window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException("A rate-limit policy needs a positive window");
        }
    }
}
