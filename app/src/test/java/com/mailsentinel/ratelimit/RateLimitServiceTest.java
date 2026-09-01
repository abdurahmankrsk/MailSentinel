package com.mailsentinel.ratelimit;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The window is driven by a movable clock rather than by sleeping, so these stay
 * fast and deterministic.
 */
class RateLimitServiceTest {

    private static final RateLimitPolicy THREE_PER_MINUTE = new RateLimitPolicy(3, Duration.ofMinutes(1));

    /** A Clock whose instant the test moves by hand. */
    private static final class MovableClock extends Clock {
        private Instant now = Instant.parse("2026-01-01T00:00:00Z");

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }
    }

    @Test
    void allowsUpToTheLimitThenDenies() {
        RateLimitService service = new RateLimitService(new MovableClock());

        assertTrue(service.consume("k", THREE_PER_MINUTE).allowed());
        assertTrue(service.consume("k", THREE_PER_MINUTE).allowed());
        assertTrue(service.consume("k", THREE_PER_MINUTE).allowed());
        assertFalse(service.consume("k", THREE_PER_MINUTE).allowed(), "the fourth in the window is over quota");
    }

    @Test
    void keysAreIndependent() {
        RateLimitService service = new RateLimitService(new MovableClock());

        for (int i = 0; i < 5; i++) {
            service.consume("noisy", THREE_PER_MINUTE);
        }

        assertTrue(service.consume("quiet", THREE_PER_MINUTE).allowed(),
                "one caller exhausting its quota must not spend anyone else's");
    }

    @Test
    void theWindowStartsAgainOnceItHasElapsed() {
        MovableClock clock = new MovableClock();
        RateLimitService service = new RateLimitService(clock);
        for (int i = 0; i < 4; i++) {
            service.consume("k", THREE_PER_MINUTE);
        }
        assertFalse(service.consume("k", THREE_PER_MINUTE).allowed());

        clock.advance(Duration.ofMinutes(1));

        assertTrue(service.consume("k", THREE_PER_MINUTE).allowed());
    }

    @Test
    void retryAfterCountsDownWithinTheWindowAndIsNeverZero() {
        MovableClock clock = new MovableClock();
        RateLimitService service = new RateLimitService(clock);
        for (int i = 0; i < 4; i++) {
            service.consume("k", THREE_PER_MINUTE);
        }

        assertEquals(60, service.consume("k", THREE_PER_MINUTE).retryAfterSeconds());
        clock.advance(Duration.ofSeconds(45));
        assertEquals(15, service.consume("k", THREE_PER_MINUTE).retryAfterSeconds());
        clock.advance(Duration.ofMillis(59_900 - 45_000));
        // Retry-After: 0 would invite an immediate retry into the same wall.
        assertTrue(service.consume("k", THREE_PER_MINUTE).retryAfterSeconds() >= 1);
    }

    @Test
    void denialStillCountsSoHammeringDoesNotShortenTheWait() {
        MovableClock clock = new MovableClock();
        RateLimitService service = new RateLimitService(clock);
        for (int i = 0; i < 3; i++) {
            service.consume("k", THREE_PER_MINUTE);
        }
        clock.advance(Duration.ofSeconds(30));
        service.consume("k", THREE_PER_MINUTE); // denied, but still counted

        // The window is fixed to its start, so the wait is what remains of it -- the
        // extra attempt neither extends nor shortens it.
        assertEquals(30, service.consume("k", THREE_PER_MINUTE).retryAfterSeconds());
    }

    @Test
    void peekDoesNotConsume() {
        RateLimitService service = new RateLimitService(new MovableClock());

        for (int i = 0; i < 10; i++) {
            assertTrue(service.peek("k", THREE_PER_MINUTE).allowed());
        }
        assertTrue(service.consume("k", THREE_PER_MINUTE).allowed(),
                "peeking ten times must leave the full quota unspent");
    }

    @Test
    void peekReportsDeniedOnceTheQuotaIsSpent() {
        RateLimitService service = new RateLimitService(new MovableClock());
        for (int i = 0; i < 3; i++) {
            service.consume("k", THREE_PER_MINUTE);
        }

        assertFalse(service.peek("k", THREE_PER_MINUTE).allowed());
    }

    @Test
    void resetForgetsTheKey() {
        RateLimitService service = new RateLimitService(new MovableClock());
        for (int i = 0; i < 5; i++) {
            service.consume("k", THREE_PER_MINUTE);
        }

        service.reset("k");

        assertTrue(service.consume("k", THREE_PER_MINUTE).allowed());
    }

    @Test
    void aPolicyMustBeUsable() {
        assertThrows(IllegalArgumentException.class, () -> new RateLimitPolicy(0, Duration.ofMinutes(1)));
        assertThrows(IllegalArgumentException.class, () -> new RateLimitPolicy(5, Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> new RateLimitPolicy(5, null));
    }
}
