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
 * Groq's chat-completions API is OpenAI-compatible:
 * POST {baseUrl}/chat/completions, Authorization: Bearer, standard OpenAI request/response shape.
 */
public class GroqAiProvider implements AiProvider {

    private static final Duration TIMEOUT = Duration.ofSeconds(25);

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String model;

    public GroqAiProvider(String baseUrl, String apiKey, String model, ObjectMapper objectMapper) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(TIMEOUT);
        requestFactory.setReadTimeout(TIMEOUT);

        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .requestFactory(requestFactory)
                .build();
        this.objectMapper = objectMapper;
        this.model = model;
    }

    @Override
    public String providerName() {
        return "groq";
    }

    @Override
    public AiAnalysisResult analyze(AiAnalysisRequest request) throws AiProviderException {
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
                    .body(chatRequest)
                    .retrieve()
                    .body(ChatResponse.class);
        } catch (RestClientException e) {
            throw new AiProviderException("Groq request failed: " + e.getMessage(), e);
        }

        if (response == null || response.choices() == null || response.choices().isEmpty()) {
            throw new AiProviderException("Groq returned no choices");
        }
        String content = response.choices().get(0).message() != null
                ? response.choices().get(0).message().content() : null;
        if (content == null || content.isBlank()) {
            throw new AiProviderException("Groq returned an empty message");
        }

        try {
            return objectMapper.readValue(content, AiAnalysisResult.class);
        } catch (Exception e) {
            throw new AiProviderException("Groq response did not match the expected JSON shape: " + e.getMessage(), e);
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
