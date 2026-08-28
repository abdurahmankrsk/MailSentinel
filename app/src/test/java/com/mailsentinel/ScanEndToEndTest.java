package com.mailsentinel;

import com.mailsentinel.ai.AiAnalysisResult;
import com.mailsentinel.ai.AiFinding;
import com.mailsentinel.ai.AiProvider;
import com.mailsentinel.ai.AiProviderException;
import com.mailsentinel.auth.AuthResponse;
import com.mailsentinel.auth.UserRepository;
import com.mailsentinel.dto.ScanResponse;
import com.mailsentinel.subscription.SubscriptionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The full happy path across every subsystem this feature added: register, log in,
 * get granted PREMIUM, scan with a real Idempotency-Key against a mocked AI provider,
 * and confirm a byte-for-byte identical retry replays the cached result rather than
 * consuming a second scan or calling the provider again.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ScanEndToEndTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private SubscriptionService subscriptionService;

    @MockBean
    private AiProvider aiProvider;

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    @Test
    void registerLoginGrantPremiumScanThenIdempotentRetryReplaysWithoutDoubleCharging() throws AiProviderException {
        when(aiProvider.analyze(any(), any())).thenReturn(new AiAnalysisResult(
                "This looks like a phishing attempt.",
                List.of(new AiFinding("Urgency language", 12, "Uses pressure tactics typical of phishing."))));

        HttpHeaders jsonHeaders = new HttpHeaders();
        jsonHeaders.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<AuthResponse> registered = restTemplate.postForEntity(
                url("/api/auth/register"),
                new HttpEntity<>("{\"email\":\"e2e@example.com\",\"password\":\"correct-horse-battery\"}", jsonHeaders),
                AuthResponse.class);
        assertEquals(HttpStatus.CREATED, registered.getStatusCode());
        String token = registered.getBody().token();

        Long userId = userRepository.findByEmail("e2e@example.com").orElseThrow().getId();
        subscriptionService.activatePremium(userId);

        HttpHeaders scanHeaders = new HttpHeaders();
        scanHeaders.setContentType(MediaType.APPLICATION_JSON);
        scanHeaders.setBearerAuth(token);
        scanHeaders.set("Idempotency-Key", "e2e-test-key-1");
        HttpEntity<String> scanRequest = new HttpEntity<>(
                "{\"type\":\"url\",\"content\":\"http://paypa1-secure.com/login\"}", scanHeaders);

        ResponseEntity<ScanResponse> first = restTemplate.postForEntity(url("/api/scan"), scanRequest, ScanResponse.class);
        assertEquals(HttpStatus.OK, first.getStatusCode());
        ScanResponse firstBody = first.getBody();
        assertNotNull(firstBody.aiAnalysis());
        assertEquals("AI_ANALYSIS_COMPLETED", firstBody.aiAnalysis().status().name());
        assertEquals(1, firstBody.aiAnalysis().scansUsed());
        assertTrue(firstBody.checks().stream().anyMatch(c -> c.name().startsWith("AI: ")));

        ResponseEntity<ScanResponse> retry = restTemplate.postForEntity(url("/api/scan"), scanRequest, ScanResponse.class);
        assertEquals(HttpStatus.OK, retry.getStatusCode());
        ScanResponse retryBody = retry.getBody();
        assertEquals("AI_ANALYSIS_COMPLETED", retryBody.aiAnalysis().status().name());
        assertEquals(1, retryBody.aiAnalysis().scansUsed(), "an idempotent retry must not consume a second scan");
        assertEquals(firstBody.aiAnalysis().summary(), retryBody.aiAnalysis().summary(), "retry must replay the cached AI summary");

        verify(aiProvider, times(1)).analyze(any(), any());
    }
}
