package com.mailsentinel.auth;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SecurityHeadersTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    private String cspOn(String path) {
        ResponseEntity<String> response =
                restTemplate.getForEntity("http://localhost:" + port + path, String.class);
        return response.getHeaders().getFirst("Content-Security-Policy");
    }

    @Test
    void everyResponseCarriesAContentSecurityPolicy() {
        // Including the served frontend itself, which is the document the policy has to
        // constrain -- a CSP on the API responses alone would protect nothing.
        assertNotNull(cspOn("/"), "the served page");
        assertNotNull(cspOn("/api/auth/config"), "an API response");
    }

    @Test
    void scriptsAreRestrictedToThisOriginAndGoogleSignIn() {
        String csp = cspOn("/");

        assertTrue(csp.contains("script-src 'self' https://accounts.google.com"), csp);
        // The whole point: the session token sits in localStorage with a 30-day life,
        // so an injected inline script is a month-long session.
        assertFalse(csp.contains("script-src 'self' 'unsafe-inline'"),
                "'unsafe-inline' in script-src would defeat the reason this header exists");
        assertFalse(csp.contains("unsafe-eval"), csp);
    }

    @Test
    void framingAndPluginsAreDeniedOutright() {
        String csp = cspOn("/");

        assertTrue(csp.contains("frame-ancestors 'none'"), csp);
        assertTrue(csp.contains("object-src 'none'"), csp);
        assertTrue(csp.contains("base-uri 'self'"), csp);
    }

    @Test
    void theSourcesTheFrontendActuallyNeedsAreAllowed() {
        String csp = cspOn("/");

        // Each of these has a caller: the webfont stylesheet and its faces, and the
        // Google Identity Services client plus the iframe and calls it makes.
        assertTrue(csp.contains("https://fonts.googleapis.com"), csp);
        assertTrue(csp.contains("https://fonts.gstatic.com"), csp);
        assertTrue(csp.contains("frame-src https://accounts.google.com"), csp);
        assertTrue(csp.contains("connect-src 'self' https://accounts.google.com"), csp);
    }

    @Test
    void theExistingHeaderDefaultsAreStillInPlace() {
        ResponseEntity<String> response =
                restTemplate.getForEntity("http://localhost:" + port + "/api/auth/config", String.class);

        // Adding a CSP must not have displaced what was already correct.
        assertEquals("DENY", response.getHeaders().getFirst("X-Frame-Options"));
        assertEquals("nosniff", response.getHeaders().getFirst("X-Content-Type-Options"));
    }
}
