package com.mailsentinel.account;

import com.mailsentinel.ai.UserAiKeyRepository;
import com.mailsentinel.auth.AuthResponse;
import com.mailsentinel.auth.AuthService;
import com.mailsentinel.auth.AuthTokenRepository;
import com.mailsentinel.auth.UserRepository;
import com.mailsentinel.idempotency.IdempotencyRecordRepository;
import com.mailsentinel.subscription.SubscriptionRepository;
import com.mailsentinel.usage.UsagePeriodRepository;
import com.mailsentinel.usage.UsageService;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Password change, account deletion and data export over real HTTP.
 *
 * Before these existed, a signed-in user could not change their password, and a GDPR
 * erasure or portability request could not be honoured at all.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AccountLifecycleTest {

    private static final String PASSWORD = "correct-horse-battery";
    private static final String NEW_PASSWORD = "a-different-long-passphrase";

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AuthTokenRepository authTokenRepository;

    @Autowired
    private SubscriptionRepository subscriptionRepository;

    @Autowired
    private UsagePeriodRepository usagePeriodRepository;

    @Autowired
    private UserAiKeyRepository userAiKeyRepository;

    @Autowired
    private IdempotencyRecordRepository idempotencyRecordRepository;

    @Autowired
    private UsageService usageService;

    @Autowired
    private AuthService authService;

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private HttpEntity<String> body(String json, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return new HttpEntity<>(json, headers);
    }

    private String registerAndGetToken(String email) {
        ResponseEntity<AuthResponse> response = restTemplate.postForEntity(
                url("/api/auth/register"),
                body("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}", null),
                AuthResponse.class);
        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        return response.getBody().token();
    }

    private ResponseEntity<String> changePassword(String token, String current, String next) {
        return restTemplate.exchange(url("/api/auth/change-password"), HttpMethod.POST,
                body("{\"currentPassword\":\"" + current + "\",\"newPassword\":\"" + next + "\"}", token),
                String.class);
    }

    private ResponseEntity<String> login(String email, String password) {
        return restTemplate.postForEntity(url("/api/auth/login"),
                body("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}", null), String.class);
    }

    @Test
    void changingThePasswordSwapsWhichPasswordWorks() {
        String token = registerAndGetToken("changer@example.com");

        assertEquals(HttpStatus.OK, changePassword(token, PASSWORD, NEW_PASSWORD).getStatusCode());

        assertEquals(HttpStatus.OK, login("changer@example.com", NEW_PASSWORD).getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED, login("changer@example.com", PASSWORD).getStatusCode(),
                "the old password must stop working, or the change accomplished nothing");
    }

    @Test
    void changingThePasswordRequiresTheCurrentOne() {
        String token = registerAndGetToken("proves-it@example.com");

        ResponseEntity<String> response = changePassword(token, "not-the-current-password", NEW_PASSWORD);

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode(),
                "a stolen token alone must not be enough to lock the real owner out");
        assertEquals(HttpStatus.OK, login("proves-it@example.com", PASSWORD).getStatusCode(),
                "the original password still works after a rejected change");
    }

    @Test
    void changingThePasswordSignsOutEveryOtherSessionButNotThisOne() {
        String firstDevice = registerAndGetToken("multidevice@example.com");
        String secondDevice = restTemplate.postForEntity(url("/api/auth/login"),
                body("{\"email\":\"multidevice@example.com\",\"password\":\"" + PASSWORD + "\"}", null),
                AuthResponse.class).getBody().token();

        ResponseEntity<AuthResponse> changed = restTemplate.exchange(url("/api/auth/change-password"),
                HttpMethod.POST,
                body("{\"currentPassword\":\"" + PASSWORD + "\",\"newPassword\":\"" + NEW_PASSWORD + "\"}", firstDevice),
                AuthResponse.class);

        String replacement = changed.getBody().token();
        assertNotNull(replacement);
        assertNotEquals(firstDevice, replacement, "the token that made the change is revoked with the rest");

        // A password change is how someone reacts to a session they think is stolen, so
        // the other 30-day tokens have to die with it.
        assertEquals(HttpStatus.UNAUTHORIZED, get("/api/usage/me", secondDevice).getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED, get("/api/usage/me", firstDevice).getStatusCode());
        // ...and the device that made the change is handed a working replacement, so it
        // is not signed out by its own action.
        assertEquals(HttpStatus.OK, get("/api/usage/me", replacement).getStatusCode());
    }

    @Test
    void changePasswordEnforcesTheSameRulesAsRegistration() {
        String token = registerAndGetToken("weak@example.com");

        assertEquals(HttpStatus.BAD_REQUEST, changePassword(token, PASSWORD, "short").getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, changePassword(token, PASSWORD, "x".repeat(73)).getStatusCode(),
                "BCrypt ignores input past 72 bytes; a password set here must not be silently truncated either");
    }

    @Test
    void changePasswordIsNotReachableWithoutAToken() {
        assertEquals(HttpStatus.UNAUTHORIZED,
                changePassword(null, PASSWORD, NEW_PASSWORD).getStatusCode(),
                "the rest of /api/auth is public; this path must not inherit that");
    }

    @Test
    void deletingAnAccountRemovesEveryRowBelongingToIt() {
        String token = registerAndGetToken("erasing@example.com");
        Long userId = userRepository.findByEmail("erasing@example.com").orElseThrow().getId();
        // Give the account a row in every child table before erasing it.
        usageService.currentStatus(userId, true);
        idempotencyRecordRepository.save(new com.mailsentinel.idempotency.IdempotencyRecord(
                userId, "some-key", "some-fingerprint"));
        assertFalse(usagePeriodRepository.findAllByUserIdOrderByPeriodStartAsc(userId).isEmpty());
        assertTrue(subscriptionRepository.findByUserId(userId).isPresent());

        ResponseEntity<Void> response = restTemplate.exchange(
                url("/api/account"), HttpMethod.DELETE, body(null, token), Void.class);

        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
        assertTrue(userRepository.findByEmail("erasing@example.com").isEmpty());
        // The FK cascades do this, not application code -- which is exactly why it is
        // asserted: a future table added without ON DELETE CASCADE would leave orphaned
        // personal data behind and nothing else would notice.
        assertTrue(authTokenRepository.findAllByUserId(userId).isEmpty(), "auth_tokens");
        assertTrue(subscriptionRepository.findByUserId(userId).isEmpty(), "subscriptions");
        assertTrue(usagePeriodRepository.findAllByUserIdOrderByPeriodStartAsc(userId).isEmpty(), "usage_periods");
        assertTrue(userAiKeyRepository.findByUserId(userId).isEmpty(), "user_ai_keys");
        assertTrue(idempotencyRecordRepository.findByUserIdAndIdempotencyKey(userId, "some-key").isEmpty(),
                "idempotency_records");
    }

    @Test
    void aDeletedAccountsTokenNoLongerWorksAndItsEmailIsFreeAgain() {
        String token = registerAndGetToken("returning@example.com");

        restTemplate.exchange(url("/api/account"), HttpMethod.DELETE, body(null, token), Void.class);

        assertEquals(HttpStatus.UNAUTHORIZED, get("/api/usage/me", token).getStatusCode());
        // A real delete, not a flag: the address is usable again, as it would not be if
        // the row were merely marked gone.
        assertEquals(HttpStatus.CREATED, restTemplate.postForEntity(url("/api/auth/register"),
                body("{\"email\":\"returning@example.com\",\"password\":\"" + PASSWORD + "\"}", null),
                String.class).getStatusCode());
    }

    @Test
    void deleteIsNotReachableWithoutAToken() {
        ResponseEntity<String> response = restTemplate.exchange(
                url("/api/account"), HttpMethod.DELETE, body(null, null), String.class);

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
    }

    @Test
    void exportReturnsTheAccountsDataAndNoCredentials() {
        String token = registerAndGetToken("exporter@example.com");
        Long userId = userRepository.findByEmail("exporter@example.com").orElseThrow().getId();
        usageService.currentStatus(userId, true);

        ResponseEntity<String> response = get("/api/account/export", token);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        String json = response.getBody();
        assertTrue(json.contains("exporter@example.com"), json);
        assertTrue(json.contains("\"plan\":\"FREE\""), json);
        assertTrue(json.contains("\"activeSessions\":1"), json);
        assertTrue(json.contains("usagePeriods"), json);

        // An export is a document a user may forward to anyone, so no credential may
        // ride along in it. The stored hash starts "$2a$" and the token "mst_".
        assertFalse(json.contains("$2a$"), "the password hash must not be exported");
        assertFalse(json.contains("passwordHash"), "the password hash must not be exported");
        assertFalse(json.contains("mst_"), "no session token may be exported");
        assertFalse(json.contains("ciphertext"), "the encrypted AI key must not be exported");
    }

    @Test
    void exportIsNotReachableWithoutAToken() {
        assertEquals(HttpStatus.UNAUTHORIZED, get("/api/account/export", null).getStatusCode());
    }

    private ResponseEntity<String> get(String path, String token) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return restTemplate.exchange(url(path), HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }
}
