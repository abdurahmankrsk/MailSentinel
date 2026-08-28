package com.mailsentinel.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeminiAiProviderTest {

    private MockWebServer server;
    private GeminiAiProvider provider;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        provider = new GeminiAiProvider(server.url("/").toString(), "test-key", "gemini-2.5-flash-lite", new ObjectMapper());
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    private AiAnalysisRequest sampleRequest() {
        return new AiAnalysisRequest("url", "http://paypa1-secure.com/login", 65, List.of());
    }

    @Test
    void parsesAWellFormedSuccessResponse() throws Exception {
        String innerJson = "{\"summary\":\"Suspicious login page.\","
                + "\"findings\":[{\"name\":\"Credential harvesting pattern\",\"weight\":20,\"detail\":\"Mimics a login form.\"}]}";
        String body = "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":"
                + new ObjectMapper().writeValueAsString(innerJson) + "}]}}]}";
        server.enqueue(new MockResponse().setResponseCode(200).setBody(body).addHeader("Content-Type", "application/json"));

        AiAnalysisResult result = provider.analyze(sampleRequest(), null);

        assertEquals("Suspicious login page.", result.summary());
        assertEquals(1, result.findings().size());
        assertEquals(20, result.findings().get(0).weight());
    }

    @Test
    void sendsApiKeyAsQueryParamAndJsonResponseMimeType() throws Exception {
        String body = "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"{\\\"summary\\\":\\\"ok\\\",\\\"findings\\\":[]}\"}]}}]}";
        server.enqueue(new MockResponse().setResponseCode(200).setBody(body).addHeader("Content-Type", "application/json"));

        provider.analyze(sampleRequest(), null);

        RecordedRequest recorded = server.takeRequest();
        assertTrue(recorded.getPath().contains("key=test-key"));
        assertTrue(recorded.getPath().contains("gemini-2.5-flash-lite:generateContent"));
        String requestBody = recorded.getBody().readUtf8();
        assertTrue(requestBody.contains("application/json"));
        assertTrue(requestBody.contains("system_instruction"));
    }

    @Test
    void throwsOnNon2xxStatus() {
        server.enqueue(new MockResponse().setResponseCode(429).setBody("rate limited"));
        assertThrows(AiProviderException.class, () -> provider.analyze(sampleRequest(), null));
    }

    @Test
    void throwsOnEmptyCandidates() {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("{\"candidates\":[]}"));
        assertThrows(AiProviderException.class, () -> provider.analyze(sampleRequest(), null));
    }

    @Test
    void throwsWhenInnerTextIsNotValidJson() {
        String body = "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"not json at all\"}]}}]}";
        server.enqueue(new MockResponse().setResponseCode(200).setBody(body));
        assertThrows(AiProviderException.class, () -> provider.analyze(sampleRequest(), null));
    }

    @Test
    void providerNameIsGemini() {
        assertEquals("gemini", provider.providerName());
    }

    @Test
    void overrideApiKeyIsUsedInsteadOfTheConfiguredOne() throws Exception {
        String body = "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"{\\\"summary\\\":\\\"ok\\\",\\\"findings\\\":[]}\"}]}}]}";
        server.enqueue(new MockResponse().setResponseCode(200).setBody(body).addHeader("Content-Type", "application/json"));

        provider.analyze(sampleRequest(), "byok-override-key");

        RecordedRequest recorded = server.takeRequest();
        assertTrue(recorded.getPath().contains("key=byok-override-key"));
    }
}
