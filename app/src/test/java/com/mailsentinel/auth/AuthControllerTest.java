package com.mailsentinel.auth;

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

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuthControllerTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private HttpEntity<String> jsonBody(String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }

    @Test
    void registerReturnsTokenAndDefaultsToFreePlan() {
        ResponseEntity<AuthResponse> response = restTemplate.postForEntity(
                url("/api/auth/register"),
                jsonBody("{\"email\":\"newuser@example.com\",\"password\":\"correct-horse-battery\"}"),
                AuthResponse.class);

        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().token().startsWith("mst_"));
        assertEquals("newuser@example.com", response.getBody().email());
        assertEquals("FREE", response.getBody().plan());
    }

    @Test
    void registerRejectsShortPassword() {
        ResponseEntity<String> response = restTemplate.postForEntity(
                url("/api/auth/register"),
                jsonBody("{\"email\":\"short@example.com\",\"password\":\"short\"}"),
                String.class);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    @Test
    void registerRejectsDuplicateEmailWithConflict() {
        restTemplate.postForEntity(url("/api/auth/register"),
                jsonBody("{\"email\":\"dupe@example.com\",\"password\":\"correct-horse-battery\"}"), AuthResponse.class);

        ResponseEntity<String> second = restTemplate.postForEntity(url("/api/auth/register"),
                jsonBody("{\"email\":\"dupe@example.com\",\"password\":\"another-password\"}"), String.class);

        assertEquals(HttpStatus.CONFLICT, second.getStatusCode());
        assertTrue(second.getBody().contains("EMAIL_ALREADY_REGISTERED"));
    }

    @Test
    void registerRejectsDisposableAddressWithUnprocessableEntity() {
        ResponseEntity<String> response = restTemplate.postForEntity(url("/api/auth/register"),
                jsonBody("{\"email\":\"throwaway@mailinator.com\",\"password\":\"correct-horse-battery\"}"),
                String.class);

        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, response.getStatusCode());
        assertTrue(response.getBody().contains("DISPOSABLE_EMAIL_DOMAIN"));

        ResponseEntity<String> login = restTemplate.postForEntity(url("/api/auth/login"),
                jsonBody("{\"email\":\"throwaway@mailinator.com\",\"password\":\"correct-horse-battery\"}"),
                String.class);
        assertEquals(HttpStatus.UNAUTHORIZED, login.getStatusCode(),
                "the rejected signup must not have left an account behind");
    }

    @Test
    void loginSucceedsWithCorrectCredentials() {
        restTemplate.postForEntity(url("/api/auth/register"),
                jsonBody("{\"email\":\"loginok@example.com\",\"password\":\"correct-horse-battery\"}"), AuthResponse.class);

        ResponseEntity<AuthResponse> response = restTemplate.postForEntity(url("/api/auth/login"),
                jsonBody("{\"email\":\"loginok@example.com\",\"password\":\"correct-horse-battery\"}"), AuthResponse.class);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().token().startsWith("mst_"));
    }

    @Test
    void loginFailsWithWrongPassword() {
        restTemplate.postForEntity(url("/api/auth/register"),
                jsonBody("{\"email\":\"loginbad@example.com\",\"password\":\"correct-horse-battery\"}"), AuthResponse.class);

        ResponseEntity<String> response = restTemplate.postForEntity(url("/api/auth/login"),
                jsonBody("{\"email\":\"loginbad@example.com\",\"password\":\"wrong-password\"}"), String.class);

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        assertTrue(response.getBody().contains("INVALID_CREDENTIALS"));
    }

    @Test
    void scanEndpointStaysReachableWithoutAnyToken() {
        ResponseEntity<String> response = restTemplate.postForEntity(url("/api/scan"),
                jsonBody("{\"type\":\"url\",\"content\":\"https://example.com\"}"), String.class);
        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    @Test
    void invalidBearerTokenIsRejectedWithUnauthorizedEvenOnAPermitAllEndpoint() {
        // The filter's "present-but-invalid -> 401" rule applies uniformly, before any
        // controller runs -- an expired/garbage token must never be silently treated as
        // anonymous, even on an otherwise-open endpoint.
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth("mst_not-a-real-token");
        ResponseEntity<String> response = restTemplate.exchange(
                url("/api/auth/logout"), HttpMethod.POST, new HttpEntity<>(headers), String.class);
        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
    }

    @Test
    void logoutWithNoAuthorizationHeaderIsANoOp() {
        ResponseEntity<String> response = restTemplate.postForEntity(url("/api/auth/logout"), null, String.class);
        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
    }
}
