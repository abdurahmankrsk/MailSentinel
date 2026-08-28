package com.mailsentinel.ai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Duration;
import java.util.List;

/**
 * Google's Generative Language API has its own distinct shape (not OpenAI-compatible):
 * POST {baseUrl}/models/{model}:generateContent?key=... .
 */
public class GeminiAiProvider implements AiProvider {

    private static final Duration TIMEOUT = Duration.ofSeconds(25);

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String model;
    private final String apiKey;

    public GeminiAiProvider(String baseUrl, String apiKey, String model, ObjectMapper objectMapper) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(TIMEOUT);
        requestFactory.setReadTimeout(TIMEOUT);

        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .build();
        this.objectMapper = objectMapper;
        this.model = model;
        this.apiKey = apiKey;
    }

    @Override
    public String providerName() {
        return "gemini";
    }

    @Override
    public AiAnalysisResult analyze(AiAnalysisRequest request) throws AiProviderException {
        GenerateRequest generateRequest = new GenerateRequest(
                new SystemInstruction(List.of(new Part(AiPrompts.SYSTEM_PROMPT))),
                List.of(new Content(List.of(new Part(AiPrompts.userPrompt(request))))),
                new GenerationConfig("application/json", 0.2)
        );

        GenerateResponse response;
        try {
            response = restClient.post()
                    .uri("/models/{model}:generateContent?key={key}", model, apiKey)
                    .body(generateRequest)
                    .retrieve()
                    .body(GenerateResponse.class);
        } catch (RestClientException e) {
            throw new AiProviderException("Gemini request failed: " + e.getMessage(), e);
        }

        if (response == null || response.candidates() == null || response.candidates().isEmpty()) {
            throw new AiProviderException("Gemini returned no candidates");
        }
        Content content = response.candidates().get(0).content();
        if (content == null || content.parts() == null || content.parts().isEmpty()) {
            throw new AiProviderException("Gemini returned no content parts");
        }
        String text = content.parts().get(0).text();
        if (text == null || text.isBlank()) {
            throw new AiProviderException("Gemini returned an empty text part");
        }

        try {
            return objectMapper.readValue(text, AiAnalysisResult.class);
        } catch (Exception e) {
            throw new AiProviderException("Gemini response did not match the expected JSON shape: " + e.getMessage(), e);
        }
    }

    private record GenerateRequest(
            @JsonProperty("system_instruction") SystemInstruction systemInstruction,
            List<Content> contents,
            @JsonProperty("generationConfig") GenerationConfig generationConfig) {}

    private record SystemInstruction(List<Part> parts) {}

    private record Content(List<Part> parts) {}

    private record Part(String text) {}

    private record GenerationConfig(
            @JsonProperty("responseMimeType") String responseMimeType,
            double temperature) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record GenerateResponse(List<Candidate> candidates) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Candidate(Content content) {}
}
