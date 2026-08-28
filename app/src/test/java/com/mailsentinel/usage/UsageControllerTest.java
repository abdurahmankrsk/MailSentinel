package com.mailsentinel.usage;

import com.mailsentinel.auth.AuthResponse;
import com.mailsentinel.auth.UserRepository;
import com.mailsentinel.subscription.SubscriptionService;
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
import static org.junit.jupiter.api.Assertions.assertNull;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class UsageControllerTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private SubscriptionService subscriptionService;

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private String registerAndGetToken(String email) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> body = new HttpEntity<>(
                "{\"email\":\"" + email + "\",\"password\":\"correct-horse-battery\"}", headers);
        return restTemplate.postForEntity(url("/api/auth/register"), body, AuthResponse.class).getBody().token();
    }

    private ResponseEntity<UsageStatusResponse> getUsage(String token) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return restTemplate.exchange(url("/api/usage/me"), HttpMethod.GET, new HttpEntity<>(headers), UsageStatusResponse.class);
    }

    @Test
    void freeUserGetsZeroedUsageNotAnError() {
        String token = registerAndGetToken("usage-free@example.com");

        ResponseEntity<UsageStatusResponse> response = getUsage(token);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        UsageStatusResponse body = response.getBody();
        assertEquals("FREE", body.plan());
        assertEquals(0, body.scansAllowance());
        assertEquals(0, body.scansUsed());
        assertEquals(0, body.scansRemaining());
        assertNull(body.periodStart());
        assertNull(body.periodEnd());
    }

    @Test
    void premiumUserGetsRealAllowanceAndAFreshPeriod() {
        String token = registerAndGetToken("usage-premium@example.com");
        Long userId = userRepository.findByEmail("usage-premium@example.com").orElseThrow().getId();
        subscriptionService.activatePremium(userId);

        ResponseEntity<UsageStatusResponse> response = getUsage(token);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        UsageStatusResponse body = response.getBody();
        assertEquals("PREMIUM", body.plan());
        assertEquals(1000, body.scansAllowance());
        assertEquals(0, body.scansUsed());
        assertEquals(1000, body.scansRemaining());
        assertNotNull(body.periodStart());
        assertNotNull(body.periodEnd());
    }

    @Test
    void anonymousCallerIsRejectedWithUnauthorized() {
        ResponseEntity<UsageStatusResponse> response = getUsage(null);
        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
    }
}
