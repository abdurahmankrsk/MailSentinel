package com.mailsentinel.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Which AI provider is active, and per-provider config. Adding a third provider later
 * means one more nested ProviderConfig block here plus a new case in AiProviderConfig's
 * factory method -- no other code changes.
 */
@ConfigurationProperties(prefix = "ai.provider")
public class AiProviderProperties {

    private String active = "groq";
    private ProviderConfig groq = new ProviderConfig();
    private ProviderConfig gemini = new ProviderConfig();

    public String getActive() {
        return active;
    }

    public void setActive(String active) {
        this.active = active;
    }

    public ProviderConfig getGroq() {
        return groq;
    }

    public void setGroq(ProviderConfig groq) {
        this.groq = groq;
    }

    public ProviderConfig getGemini() {
        return gemini;
    }

    public void setGemini(ProviderConfig gemini) {
        this.gemini = gemini;
    }

    public static class ProviderConfig {
        private String apiKey;
        private String model;
        private String baseUrl;

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }
    }
}
