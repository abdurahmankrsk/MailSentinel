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

class GroqAiProviderTest {

    private MockWebServer server;
    private GroqAiProvider provider;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        provider = new GroqAiProvider(server.url("/").toString(), "test-key", "test-model", new ObjectMapper());
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    private AiAnalysisRequest sampleRequest() {
        return new AiAnalysisRequest("email", "Dear customer, verify your account now.", 20, List.of());
    }

    @Test
    void parsesAWellFormedSuccessResponse() throws Exception {
        String innerJson = "{\"summary\":\"Looks like a phishing attempt.\","
                + "\"findings\":[{\"name\":\"Urgency language\",\"weight\":15,\"detail\":\"Uses pressure tactics.\"}]}";
        String body = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":"
                + new ObjectMapper().writeValueAsString(innerJson) + "}}]}";
        server.enqueue(new MockResponse().setResponseCode(200).setBody(body).addHeader("Content-Type", "application/json"));

        AiAnalysisResult result = provider.analyze(sampleRequest());

        assertEquals("Looks like a phishing attempt.", result.summary());
        assertEquals(1, result.findings().size());
        assertEquals("Urgency language", result.findings().get(0).name());
        assertEquals(15, result.findings().get(0).weight());
    }

    @Test
    void sendsBearerAuthAndJsonModeRequest() throws Exception {
        String body = "{\"choices\":[{\"message\":{\"content\":\"{\\\"summary\\\":\\\"ok\\\",\\\"findings\\\":[]}\"}}]}";
        server.enqueue(new MockResponse().setResponseCode(200).setBody(body).addHeader("Content-Type", "application/json"));

        provider.analyze(sampleRequest());

        RecordedRequest recorded = server.takeRequest();
        assertEquals("Bearer test-key", recorded.getHeader("Authorization"));
        String requestBody = recorded.getBody().readUtf8();
        assertTrue(requestBody.contains("\"response_format\""));
        assertTrue(requestBody.contains("json_object"));
        assertTrue(requestBody.contains("test-model"));
    }

    @Test
    void throwsOnNon2xxStatus() {
        server.enqueue(new MockResponse().setResponseCode(500).setBody("internal error"));
        assertThrows(AiProviderException.class, () -> provider.analyze(sampleRequest()));
    }

    @Test
    void throwsOnEmptyChoices() {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("{\"choices\":[]}"));
        assertThrows(AiProviderException.class, () -> provider.analyze(sampleRequest()));
    }

    @Test
    void throwsWhenInnerContentIsNotValidJson() {
        String body = "{\"choices\":[{\"message\":{\"content\":\"this is not json\"}}]}";
        server.enqueue(new MockResponse().setResponseCode(200).setBody(body));
        assertThrows(AiProviderException.class, () -> provider.analyze(sampleRequest()));
    }

    @Test
    void providerNameIsGroq() {
        assertEquals("groq", provider.providerName());
    }
}
