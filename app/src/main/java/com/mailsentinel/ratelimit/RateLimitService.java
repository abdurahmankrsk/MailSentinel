package com.mailsentinel.ratelimit;

import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Fixed-window counters, held in memory.
 *
 * Deliberately not Bucket4j and not a database table. The API had no throttling at
 * all -- 25 consecutive failed logins, 30 registrations in a burst, and an
 * unauthenticated CORS-open /api/scan were all measured going straight through -- and
 * an in-process counter closes the online-guessing and burst-signup paths today,
 * without a new dependency or a schema change. Two limits come with it, both real:
 *
 *  - Counters reset when the process restarts.
 *  - Each replica counts on its own, so N replicas allow N times the quota.
 *
 * Neither is a reason to keep zero limiting, but both are reasons this wants to move
 * behind the existing datastore (or Redis) before the app runs multi-instance. The
 * seam for that is this class: nothing outside it knows where the counts live.
 *
 * A fixed window rather than a sliding one is the usual trade -- a caller can spend
 * one window's quota at its end and the next at its start, so the true worst case is
 * 2x the limit over a window's span. For an abuse ceiling that is fine; for the login
 * limiter it still cuts unlimited guessing down to tens of attempts an hour.
 */
@Service
public class RateLimitService {

    /**
     * Above this many tracked keys, expired entries are swept before a new one is
     * added. Without it, one key per attacker-chosen IP is an unbounded map -- a
     * memory-exhaustion path opened by the very code meant to close an abuse path.
     */
    private static final int SWEEP_THRESHOLD = 10_000;

    private final Clock clock;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    public RateLimitService() {
        this(Clock.systemUTC());
    }

    // Package-private: tests drive the window boundary with a fixed clock rather than
    // by sleeping through it.
    RateLimitService(Clock clock) {
        this.clock = clock;
    }

    /**
     * Counts this event against {@code key} and says whether it is allowed.
     *
     * <p>Note the counter advances even when the answer is "denied", so a caller that
     * keeps hammering keeps the window occupied rather than slipping through as soon
     * as the count would otherwise have lapsed.
     */
    public RateLimitDecision consume(String key, RateLimitPolicy policy) {
        sweepIfCrowded();
        Instant now = clock.instant();
        Window window = windows.compute(key, (k, existing) ->
                existing == null || existing.hasExpired(now) ? new Window(now, policy.window()) : existing);

        int used = window.count().incrementAndGet();
        Duration remaining = window.remaining(now);
        return used <= policy.limit()
                ? RateLimitDecision.allowed(remaining)
                : RateLimitDecision.denied(remaining);
    }

    /**
     * Whether {@code key} is currently over its quota, without counting this call.
     *
     * Used where the event worth counting isn't the request itself but its outcome --
     * a failed login, say, which the handler only learns after the check has to have
     * happened.
     */
    public RateLimitDecision peek(String key, RateLimitPolicy policy) {
        Instant now = clock.instant();
        Window window = windows.get(key);
        if (window == null || window.hasExpired(now)) {
            return RateLimitDecision.allowed(policy.window());
        }
        Duration remaining = window.remaining(now);
        return window.count().get() < policy.limit()
                ? RateLimitDecision.allowed(remaining)
                : RateLimitDecision.denied(remaining);
    }

    /** Forgets {@code key} entirely -- e.g. a successful login clearing its own failure count. */
    public void reset(String key) {
        windows.remove(key);
    }

    private void sweepIfCrowded() {
        if (windows.size() < SWEEP_THRESHOLD) {
            return;
        }
        Instant now = clock.instant();
        windows.values().removeIf(window -> window.hasExpired(now));
    }

    private record Window(Instant startedAt, Duration length, AtomicInteger count) {
        Window(Instant startedAt, Duration length) {
            this(startedAt, length, new AtomicInteger());
        }

        boolean hasExpired(Instant now) {
            return !now.isBefore(startedAt.plus(length));
        }

        Duration remaining(Instant now) {
            Duration left = Duration.between(now, startedAt.plus(length));
            return left.isNegative() ? Duration.ZERO : left;
        }
    }
}
