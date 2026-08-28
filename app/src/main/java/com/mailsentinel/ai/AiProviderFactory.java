package com.mailsentinel.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/**
 * Builds an AiProvider client for an arbitrary user-supplied endpoint (bring-your-
 * own-key). A separate, injectable seam rather than AiKeyService/AiAnalysisService
 * calling {@code new GroqAiProvider(...)} directly, so both stay unit-testable with
 * a mocked AiProvider instead of a real HTTP client.
 */
@Component
public class AiProviderFactory {

    private final ObjectMapper objectMapper;

    public AiProviderFactory(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public AiProvider create(String baseUrl, String model, String apiKey) {
        return new GroqAiProvider(baseUrl, apiKey, model, objectMapper);
    }
}
