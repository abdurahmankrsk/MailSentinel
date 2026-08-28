package com.mailsentinel.ai;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

/**
 * Lets a user supply their own AI API key so their scans get AI-assisted analysis
 * without needing PREMIUM or consuming MailSentinel's own usage allowance -- see
 * AiAnalysisService, which checks here first.
 *
 * Not limited to a named provider: any OpenAI-compatible chat-completions endpoint
 * works (OpenAI, Groq, Together, DeepSeek, a self-hosted Ollama, and others), since
 * the user supplies their own base URL and model alongside the key. GroqAiProvider
 * is a plain OpenAI-compatible client despite its name (see its own class comment)
 * and is reused directly here rather than adding a second implementation of the
 * same wire protocol.
 */
@Service
public class AiKeyService {

    private static final int MIN_KEY_LENGTH = 10;

    private final UserAiKeyRepository repository;
    private final AiKeyCipher cipher;
    private final AiProviderFactory aiProviderFactory;

    public AiKeyService(UserAiKeyRepository repository, AiKeyCipher cipher, AiProviderFactory aiProviderFactory) {
        this.repository = repository;
        this.cipher = cipher;
        this.aiProviderFactory = aiProviderFactory;
    }

    public boolean isFeatureEnabled() {
        return cipher.isConfigured();
    }

    public AiKeyStatus save(Long userId, String label, String baseUrl, String model, String rawKey) {
        requireFeatureEnabled();
        if (baseUrl == null || !(baseUrl.startsWith("http://") || baseUrl.startsWith("https://"))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "That doesn't look like a valid API base URL");
        }
        if (model == null || model.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A model name is required");
        }
        if (rawKey == null || rawKey.isBlank() || rawKey.trim().length() < MIN_KEY_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "That doesn't look like a valid API key");
        }
        String trimmedKey = rawKey.trim();
        String trimmedBaseUrl = baseUrl.trim();
        String trimmedModel = model.trim();
        String displayLabel = label == null || label.isBlank() ? "AI" : label.trim();

        validateAgainstEndpoint(trimmedBaseUrl, trimmedModel, trimmedKey);

        String ciphertext = cipher.encrypt(trimmedKey);
        String last4 = trimmedKey.substring(trimmedKey.length() - 4);
        repository.findByUserId(userId).ifPresent(repository::delete);
        repository.save(new UserAiKey(userId, displayLabel, trimmedBaseUrl, trimmedModel, ciphertext, last4));

        return new AiKeyStatus(displayLabel, last4);
    }

    public void delete(Long userId) {
        repository.deleteByUserId(userId);
    }

    public Optional<AiKeyStatus> status(Long userId) {
        return repository.findByUserId(userId).map(k -> new AiKeyStatus(k.getLabel(), k.getKeyLast4()));
    }

    /**
     * Everything AiAnalysisService needs to call this user's own endpoint, or empty
     * if they haven't configured one. Never exposed outside the backend.
     */
    public Optional<ActiveAiKey> activeKeyFor(Long userId) {
        return repository.findByUserId(userId)
                .map(k -> new ActiveAiKey(k.getBaseUrl(), k.getModel(), cipher.decrypt(k.getKeyCiphertext())));
    }

    /**
     * One real, minimal call to the user's own endpoint before we ever store it, so
     * a typo, a revoked key, or a wrong base URL/model is rejected immediately
     * rather than silently saved and only discovered the next time the user
     * actually scans something. A throwaway client is built here rather than
     * reusing the server's own injected AiProvider bean, which is fixed to this
     * server's own configured endpoint, not the user's.
     */
    private void validateAgainstEndpoint(String baseUrl, String model, String rawKey) {
        AiAnalysisRequest probe = new AiAnalysisRequest(
                "email", "Validation check for a newly added API key.", 0, List.of());
        AiProvider probeProvider = aiProviderFactory.create(baseUrl, model, rawKey);
        try {
            probeProvider.analyze(probe, null);
        } catch (AiProviderException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "That API key doesn't work: " + e.getMessage());
        }
    }

    private void requireFeatureEnabled() {
        if (!isFeatureEnabled()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Bring-your-own-key isn't configured on this server yet");
        }
    }
}
