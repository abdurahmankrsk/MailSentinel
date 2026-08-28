package com.mailsentinel.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Locale;

/**
 * Selects the active AiProvider implementation from ai.provider.active. Groq and Gemini
 * are plain classes (not @Component/@Service) specifically so Spring never sees two
 * ambiguous AiProvider candidates -- this factory method is the only place that decides
 * which one is live.
 */
@Configuration
public class AiProviderConfig {

    @Bean
    public AiProvider aiProvider(AiProviderProperties properties, ObjectMapper objectMapper) {
        String active = properties.getActive() == null ? "" : properties.getActive().toLowerCase(Locale.ROOT);
        return switch (active) {
            case "groq" -> new GroqAiProvider(
                    properties.getGroq().getBaseUrl(),
                    properties.getGroq().getApiKey(),
                    properties.getGroq().getModel(),
                    objectMapper);
            case "gemini" -> new GeminiAiProvider(
                    properties.getGemini().getBaseUrl(),
                    properties.getGemini().getApiKey(),
                    properties.getGemini().getModel(),
                    objectMapper);
            default -> throw new IllegalStateException(
                    "Unknown ai.provider.active '" + properties.getActive() + "' -- expected 'groq' or 'gemini'");
        };
    }
}
