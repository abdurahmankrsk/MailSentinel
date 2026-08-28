package com.mailsentinel.ai;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * A user's own AI API key, encrypted at rest. One row per user (see the unique
 * constraint on user_id) -- saving a new key replaces the old one rather than
 * accumulating a history.
 *
 * Not tied to a specific named provider: baseUrl and model are stored alongside the
 * key so this works with any OpenAI-compatible chat-completions API (OpenAI, Groq,
 * Together, DeepSeek, a self-hosted Ollama, and others), not just the one provider
 * this server itself happens to be configured with. label is a free-text display
 * name only (e.g. "Groq", "OpenAI") and never drives behavior.
 */
@Entity
@Table(name = "user_ai_keys")
public class UserAiKey {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, unique = true)
    private Long userId;

    @Column(nullable = false, length = 50)
    private String label;

    @Column(name = "base_url", nullable = false)
    private String baseUrl;

    @Column(nullable = false, length = 100)
    private String model;

    @Column(name = "key_ciphertext", nullable = false)
    private String keyCiphertext;

    @Column(name = "key_last4", nullable = false, length = 4)
    private String keyLast4;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected UserAiKey() {
        // JPA
    }

    public UserAiKey(Long userId, String label, String baseUrl, String model, String keyCiphertext, String keyLast4) {
        this.userId = userId;
        this.label = label;
        this.baseUrl = baseUrl;
        this.model = model;
        this.keyCiphertext = keyCiphertext;
        this.keyLast4 = keyLast4;
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public String getLabel() {
        return label;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public String getModel() {
        return model;
    }

    public String getKeyCiphertext() {
        return keyCiphertext;
    }

    public String getKeyLast4() {
        return keyLast4;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
