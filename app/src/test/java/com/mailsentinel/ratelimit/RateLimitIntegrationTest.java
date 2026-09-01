package com.mailsentinel.ratelimit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The limiter over real HTTP. Quotas are overridden to small numbers so the tests
 * reach them in a few requests instead of dozens, and windows are long enough that
 * nothing lapses mid-test.
 *
 * Every test claims its own client address via X-Forwarded-For (trusted here by
 * configuration), because the counters are per-IP and shared across the one Spring
 * context these methods share -- without it, whichever test ran first would spend the
 * budget for all of them. It also means the header path in ClientIpResolver is the
 * one under test, which is the path that matters in a real deployment.
 *
 * Every finding covered here was measured against the running app: 25 consecutive
 * failed logins all returned 401 with no throttle, 30 registrations in a burst all
 * returned 201, and /api/scan was unauthenticated and CORS-open with no ceiling.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "mailsentinel.rate-limit.enabled=true",
        "mailsentinel.rate-limit.trust-forwarded-for=true",
        "mailsentinel.rate-limit.login.limit=3",
        "mailsentinel.rate-limit.login.window=PT15M",
        "mailsentinel.rate-limit.register.limit=2",
        "mailsentinel.rate-limit.register.window=PT1H",
        "mailsentinel.rate-limit.scan.limit=4",
        "mailsentinel.rate-limit.scan.window=PT1M",
})
class RateLimitIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private ResponseEntity<String> post(String clientIp, String path, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Forwarded-For", clientIp);
        return restTemplate.exchange(url(path), HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    private ResponseEntity<String> register(String clientIp, String email) {
        return post(clientIp, "/api/auth/register",
                "{\"email\":\"" + email + "\",\"password\":\"correct-horse-battery\"}");
    }

    private ResponseEntity<String> login(String clientIp, String email, String password) {
        return post(clientIp, "/api/auth/login",
                "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}");
    }

    @Test
    void repeatedFailedLoginsAreThrottledAndTheReplyCarriesRetryAfter() {
        register("10.0.0.1", "brute@example.com");

        for (int attempt = 1; attempt <= 3; attempt++) {
            assertEquals(HttpStatus.UNAUTHORIZED, login("10.0.0.1", "brute@example.com", "guess-" + attempt).getStatusCode(),
                    "attempt " + attempt + " is within quota and should be a plain credential rejection");
        }

        ResponseEntity<String> throttled = login("10.0.0.1", "brute@example.com", "guess-4");

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, throttled.getStatusCode());
        assertNotNull(throttled.getHeaders().getFirst(HttpHeaders.RETRY_AFTER),
                "a 429 without Retry-After leaves the client guessing when to come back");
        assertTrue(throttled.getBody().contains("RATE_LIMITED"), throttled.getBody());
    }

    @Test
    void theCorrectPasswordStillWorksAfterFailuresThatStayUnderTheQuota() {
        register("10.0.0.2", "fatfinger@example.com");

        login("10.0.0.2", "fatfinger@example.com", "typo-one");
        login("10.0.0.2", "fatfinger@example.com", "typo-two");

        assertEquals(HttpStatus.OK, login("10.0.0.2", "fatfinger@example.com", "correct-horse-battery").getStatusCode(),
                "two typos must not stand between a user and their own account");
    }

    @Test
    void aSuccessfulLoginClearsTheFailureCount() {
        register("10.0.0.3", "recovers@example.com");
        login("10.0.0.3", "recovers@example.com", "typo-one");
        login("10.0.0.3", "recovers@example.com", "typo-two");

        assertEquals(HttpStatus.OK, login("10.0.0.3", "recovers@example.com", "correct-horse-battery").getStatusCode());

        // The budget is whole again, so the next slip is not the one that locks them out.
        assertEquals(HttpStatus.UNAUTHORIZED, login("10.0.0.3", "recovers@example.com", "typo-three").getStatusCode());
        assertEquals(HttpStatus.OK, login("10.0.0.3", "recovers@example.com", "correct-horse-battery").getStatusCode());
    }

    @Test
    void oneAccountsFailuresDoNotLockAnotherAccountOut() {
        // The per-email counter is what stops a botnet working one account from many
        // hosts; it must not become a way to lock out an unrelated account.
        register("10.0.0.4", "noisy@example.com");
        register("10.0.0.5", "quiet@example.com");
        for (int i = 0; i < 4; i++) {
            login("10.0.0.4", "noisy@example.com", "guess-" + i);
        }

        assertEquals(HttpStatus.OK, login("10.0.0.5", "quiet@example.com", "correct-horse-battery").getStatusCode());
    }

    @Test
    void blankCredentialsAreCountedToo() {
        // Same INVALID_CREDENTIALS response as a wrong password, so an uncounted blank
        // request would be an unlimited path to probe with.
        for (int i = 0; i < 3; i++) {
            assertEquals(HttpStatus.UNAUTHORIZED, login("10.0.0.6", "blank@example.com", "").getStatusCode());
        }

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, login("10.0.0.6", "blank@example.com", "").getStatusCode());
    }

    @Test
    void aBurstOfRegistrationsIsCapped() {
        assertEquals(HttpStatus.CREATED, register("10.0.0.7", "flood1@example.com").getStatusCode());
        assertEquals(HttpStatus.CREATED, register("10.0.0.7", "flood2@example.com").getStatusCode());

        ResponseEntity<String> third = register("10.0.0.7", "flood3@example.com");

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, third.getStatusCode());
        assertNotNull(third.getHeaders().getFirst(HttpHeaders.RETRY_AFTER));
    }

    @Test
    void anonymousScanningHasACeilingButTheCeilingIsGenerous() {
        String body = "{\"type\":\"url\",\"content\":\"https://example.com\"}";
        for (int i = 0; i < 4; i++) {
            assertEquals(HttpStatus.OK, post("10.0.0.8", "/api/scan", body).getStatusCode(),
                    "free unlimited scanning is a product promise; the limit is an abuse ceiling above it");
        }

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, post("10.0.0.8", "/api/scan", body).getStatusCode());
    }

    @Test
    void oneNoisyClientDoesNotSpendAnotherClientsScanQuota() {
        String body = "{\"type\":\"url\",\"content\":\"https://example.com\"}";
        for (int i = 0; i < 6; i++) {
            post("10.0.0.9", "/api/scan", body);
        }

        assertEquals(HttpStatus.OK, post("10.0.0.10", "/api/scan", body).getStatusCode());
    }

    @Test
    void unlimitedPathsAreLeftAlone() {
        // The static frontend and the read-only config endpoints are served from the
        // same origin and must not be counted against anything.
        for (int i = 0; i < 12; i++) {
            assertEquals(HttpStatus.OK, restTemplate.getForEntity(url("/api/auth/config"), String.class).getStatusCode());
        }
    }
}
