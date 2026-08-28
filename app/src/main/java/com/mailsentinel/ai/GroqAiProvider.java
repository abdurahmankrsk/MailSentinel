package com.mailsentinel.ai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * A plain OpenAI-compatible chat-completions client:
 * POST {baseUrl}/chat/completions, Authorization: Bearer, standard OpenAI request/response shape.
 * Groq's own API happens to be exactly this shape (hence the name and this server's own
 * configured usage), but nothing here is Groq-specific: baseUrl/model/apiKey are all
 * caller-supplied, which is exactly what lets this same class serve a user's own
 * bring-your-own-key endpoint too (OpenAI, Together, DeepSeek, a self-hosted model,
 * or anything else that speaks this wire format) -- see AiKeyService/AiAnalysisService.
 * Error messages below are deliberately provider-agnostic for that reason.
 */
public class GroqAiProvider implements AiProvider {

    private static final Duration TIMEOUT = Duration.ofSeconds(25);

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String model;
    private final String apiKey;

    public GroqAiProvider(String baseUrl, String apiKey, String model, ObjectMapper objectMapper) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(TIMEOUT);
        requestFactory.setReadTimeout(TIMEOUT);

        // Authorization is set per-request (see analyze()), not as a client-level
        // default, so a single shared RestClient can serve both this server's own key
        // and a caller-supplied bring-your-own-key override.
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
        return "groq";
    }

    @Override
    public AiAnalysisResult analyze(AiAnalysisRequest request, String overrideApiKey) throws AiProviderException {
        String effectiveKey = overrideApiKey != null ? overrideApiKey : apiKey;
        ChatRequest chatRequest = new ChatRequest(
                model,
                List.of(
                        new ChatMessage("system", AiPrompts.SYSTEM_PROMPT),
                        new ChatMessage("user", AiPrompts.userPrompt(request))
                ),
                Map.of("type", "json_object"),
                0.2
        );

        ChatResponse response;
        try {
            response = restClient.post()
                    .uri("/chat/completions")
                    .header("Authorization", "Bearer " + effectiveKey)
                    .body(chatRequest)
                    .retrieve()
                    .body(ChatResponse.class);
        } catch (RestClientException e) {
            throw new AiProviderException("AI provider request failed: " + e.getMessage(), e);
        }

        if (response == null || response.choices() == null || response.choices().isEmpty()) {
            throw new AiProviderException("AI provider returned no choices");
        }
        String content = response.choices().get(0).message() != null
                ? response.choices().get(0).message().content() : null;
        if (content == null || content.isBlank()) {
            throw new AiProviderException("AI provider returned an empty message");
        }

        try {
            return objectMapper.readValue(content, AiAnalysisResult.class);
        } catch (Exception e) {
            throw new AiProviderException("AI provider response did not match the expected JSON shape: " + e.getMessage(), e);
        }
    }

    private record ChatRequest(
            String model,
            List<ChatMessage> messages,
            @JsonProperty("response_format") Map<String, String> responseFormat,
            double temperature) {}

    private record ChatMessage(String role, String content) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ChatResponse(List<Choice> choices) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Choice(ChatMessage message) {}
}
