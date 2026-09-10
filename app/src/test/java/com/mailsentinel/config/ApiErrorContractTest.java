package com.mailsentinel.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two error shapes the frontend parses, pinned end to end.
 *
 * <p>api.js reads {@code message}, then {@code detail}, then {@code error} -- because the
 * server speaks two shapes: ApiExceptionHandler's {@code {error, message}} for named
 * exceptions, and RFC 7807 problem details for anything thrown as a
 * ResponseStatusException. Nothing tested either, and the problem-detail half was not
 * even switched on while the suite ran, so the reason text every one of those throws
 * carefully writes was being dropped and no test could see it. These assert on the
 * sentence reaching the client, not merely on the status code.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApiErrorContractTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    private ResponseEntity<String> postJson(String path, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.postForEntity("http://localhost:" + port + path, new HttpEntity<>(body, headers), String.class);
    }

    @Test
    void aRejectedScanExplainsWhyRatherThanSayingOnlyBadRequest() {
        ResponseEntity<String> response = postJson("/api/scan", "{\"type\":\"url\",\"content\":\"   \"}");

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertTrue(response.getBody().contains("\"detail\""),
                "the frontend reads body.detail; a body without it renders as a bare status line: " + response.getBody());
        assertTrue(response.getBody().contains("Missing type or content"),
                "the reason the handler wrote must survive to the caller: " + response.getBody());
    }

    @Test
    void anUnknownScanTypeSaysWhichTypesAreAccepted() {
        ResponseEntity<String> response = postJson("/api/scan", "{\"type\":\"URL\",\"content\":\"https://example.com\"}");

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertTrue(response.getBody().contains("type must be 'email' or 'url'"), response.getBody());
    }

    @Test
    void aRejectedRegistrationSaysWhatWasWrongWithTheAddress() {
        ResponseEntity<String> response = postJson("/api/auth/register",
                "{\"email\":\"" + "a".repeat(300) + "@example.com\",\"password\":\"correct-horse-battery\"}");

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertTrue(response.getBody().contains("That doesn't look like a valid email address"), response.getBody());
    }

    @Test
    void aNamedExceptionKeepsTheCodeAndMessageShapeInstead() {
        // The other half of the contract: ApiExceptionHandler builds its own body, so this
        // one carries `error` (machine-readable) alongside `message` (for a person).
        postJson("/api/auth/register", "{\"email\":\"contract@example.com\",\"password\":\"correct-horse-battery\"}");
        ResponseEntity<String> duplicate = postJson("/api/auth/register",
                "{\"email\":\"contract@example.com\",\"password\":\"correct-horse-battery\"}");

        assertEquals(HttpStatus.CONFLICT, duplicate.getStatusCode());
        assertTrue(duplicate.getBody().contains("EMAIL_ALREADY_REGISTERED"), duplicate.getBody());
        assertTrue(duplicate.getBody().contains("Email already registered"), duplicate.getBody());
    }
}
