package com.mailsentinel.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Lets a user supply their own AI API key so their scans get AI-assisted analysis
 * without needing PREMIUM or consuming MailSentinel's own usage allowance -- see
 * AiAnalysisService, which checks here first.
 *
 * Not limited to a named provider: any OpenAI-compatible chat-completions endpoint
 * works (OpenAI, Groq, Together, DeepSeek, and others), since the user supplies
 * their own base URL and model alongside the key. GroqAiProvider is a plain
 * OpenAI-compatible client despite its name (see its own class comment) and is
 * reused directly here rather than adding a second implementation of the same wire
 * protocol.
 *
 * The base URL is caller-supplied and this server then fetches it, so every save
 * passes OutboundUrlGuard first -- public HTTPS only. That rules out a self-hosted
 * model on localhost or a LAN unless the operator opts back in with
 * mailsentinel.byok.allow-private-endpoints.
 */
@Service
public class AiKeyService {

    private static final Logger log = LoggerFactory.getLogger(AiKeyService.class);

    private static final int MIN_KEY_LENGTH = 10;

    private final UserAiKeyRepository repository;
    private final AiKeyCipher cipher;
    private final AiProviderFactory aiProviderFactory;
    private final OutboundUrlGuard outboundUrlGuard;

    public AiKeyService(UserAiKeyRepository repository, AiKeyCipher cipher, AiProviderFactory aiProviderFactory,
                        OutboundUrlGuard outboundUrlGuard) {
        this.repository = repository;
        this.cipher = cipher;
        this.aiProviderFactory = aiProviderFactory;
        this.outboundUrlGuard = outboundUrlGuard;
    }

    public boolean isFeatureEnabled() {
        return cipher.isConfigured();
    }

    public AiKeyStatus save(Long userId, String label, String baseUrl, String model, String rawKey) {
        requireFeatureEnabled();
        if (baseUrl == null || !baseUrl.trim().toLowerCase(Locale.ROOT).startsWith("https://")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "That doesn't look like a valid API base URL -- it has to start with https://");
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

        // Before the probe, not after: the probe is itself the outbound request the
        // guard exists to stop.
        requireCallableEndpoint(trimmedBaseUrl);
        validateAgainstEndpoint(trimmedBaseUrl, trimmedModel, trimmedKey);

        String ciphertext = cipher.encrypt(trimmedKey);
        String last4 = trimmedKey.substring(trimmedKey.length() - 4);
        repository.findByUserId(userId).ifPresent(repository::delete);
        repository.save(new UserAiKey(userId, displayLabel, trimmedBaseUrl, trimmedModel, ciphertext, last4));

        return new AiKeyStatus(displayLabel, last4);
    }

    /**
     * Transactional because deleteByUserId is a derived delete query: Spring Data runs
     * those as an em.remove() per matching row, which needs a real write transaction.
     * Repository CRUD methods bring their own; a derived one inherits only
     * SimpleJpaRepository's read-only default, so without this the call blows up with
     * "No EntityManager with actual transaction available" and the user can never
     * remove a key they saved.
     */
    @Transactional
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
            // Logged, never echoed. The upstream failure used to be returned verbatim,
            // which told the caller apart an open internal port ("401 : [no body]") from
            // a closed one ("I/O error on POST request") cleanly enough to map a network
            // with. OutboundUrlGuard now refuses internal hosts outright, but a reflected
            // transport error is a needless oracle regardless of where it points.
            log.warn("Bring-your-own-key validation failed for endpoint {}: {}", baseUrl, e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "That API key or endpoint could not be reached");
        }
    }

    /**
     * The egress guard, translated to the wire. Its exception names the host and the
     * address that host resolved to, which is exactly the detail an SSRF probe is
     * fishing for -- so that goes to the log, and the caller gets one fixed sentence.
     */
    private void requireCallableEndpoint(String baseUrl) {
        try {
            outboundUrlGuard.requirePublicHttpsEndpoint(baseUrl);
        } catch (BlockedEndpointException e) {
            log.warn("{}", e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.userMessage());
        }
    }

    private void requireFeatureEnabled() {
        if (!isFeatureEnabled()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Bring-your-own-key isn't configured on this server yet");
        }
    }
}
