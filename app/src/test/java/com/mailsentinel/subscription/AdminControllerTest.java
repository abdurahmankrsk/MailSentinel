package com.mailsentinel.subscription;

import com.mailsentinel.auth.AuthResponse;
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

/** mailsentinel.admin.emails in application-test.properties is admin@mailsentinel.test. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AdminControllerTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private String registerAndGetToken(String email) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> body = new HttpEntity<>(
                "{\"email\":\"" + email + "\",\"password\":\"correct-horse-battery\"}", headers);
        ResponseEntity<AuthResponse> response = restTemplate.postForEntity(url("/api/auth/register"), body, AuthResponse.class);
        return response.getBody().token();
    }

    private HttpEntity<String> authedJson(String token, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);
        return new HttpEntity<>(body, headers);
    }

    @Test
    void adminCanGrantAndRevokePremiumForAnotherUser() {
        registerAndGetToken("target-user@example.com");
        String adminToken = registerAndGetToken("admin@mailsentinel.test");

        ResponseEntity<PlanChangeResponse> grant = restTemplate.postForEntity(
                url("/api/admin/grant-premium"),
                authedJson(adminToken, "{\"email\":\"target-user@example.com\"}"),
                PlanChangeResponse.class);
        assertEquals(HttpStatus.OK, grant.getStatusCode());
        assertEquals("PREMIUM", grant.getBody().plan());

        ResponseEntity<PlanChangeResponse> revoke = restTemplate.postForEntity(
                url("/api/admin/revoke-premium"),
                authedJson(adminToken, "{\"email\":\"target-user@example.com\"}"),
                PlanChangeResponse.class);
        assertEquals(HttpStatus.OK, revoke.getStatusCode());
        assertEquals("FREE", revoke.getBody().plan());
    }

    @Test
    void nonAdminUserIsForbiddenFromGrantingPremium() {
        String plainUserToken = registerAndGetToken("plain-user@example.com");

        ResponseEntity<String> response = restTemplate.postForEntity(
                url("/api/admin/grant-premium"),
                authedJson(plainUserToken, "{\"email\":\"plain-user@example.com\"}"),
                String.class);

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
    }

    @Test
    void anonymousCallerIsUnauthorizedFromAdminEndpoints() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> response = restTemplate.postForEntity(
                url("/api/admin/grant-premium"),
                new HttpEntity<>("{\"email\":\"nobody@example.com\"}", headers),
                String.class);

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
    }
}
