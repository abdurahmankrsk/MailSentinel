package com.mailsentinel.ai;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.encrypt.Encryptors;
import org.springframework.security.crypto.encrypt.TextEncryptor;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Reversible encryption for a user's own third-party AI API key -- the one secret in
 * this codebase that has to come back out in plaintext (to call the user's own AI
 * endpoint with it), unlike password hashing (BCrypt) or token hashing (SHA-256, see
 * TokenGenerator), which are both deliberately one-way and never decrypted.
 *
 * Built on spring-security-crypto's Encryptors (AES-GCM under the hood), already a
 * transitive dependency via spring-boot-starter-security and previously unused --
 * not a hand-rolled javax.crypto implementation.
 *
 * Encryptors.text needs a password and a separate hex salt; only one secret
 * (mailsentinel.byok.encryption-key) is configured, so the salt is deterministically
 * derived from it via SHA-256. The salt's job in PBKDF2 is defeating precomputed
 * rainbow tables, not being secret itself -- all the real secrecy comes from the
 * configured key being long and kept out of source control, the same trust model as
 * GROQ_API_KEY or the database password.
 *
 * When the key isn't configured, this component simply reports itself unavailable
 * rather than encrypting with a weak/empty secret -- the same "feature quietly stays
 * off" pattern GoogleTokenVerifier uses when GOOGLE_CLIENT_ID is blank.
 */
@Component
public class AiKeyCipher {

    private final boolean configured;
    private final TextEncryptor encryptor;

    public AiKeyCipher(@Value("${mailsentinel.byok.encryption-key:}") String masterKey) {
        this.configured = masterKey != null && !masterKey.isBlank();
        this.encryptor = configured ? Encryptors.text(masterKey, deriveSalt(masterKey)) : null;
    }

    private static String deriveSalt(String masterKey) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(masterKey.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashed).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is a mandatory JDK algorithm; this can never actually happen.
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    public boolean isConfigured() {
        return configured;
    }

    public String encrypt(String rawValue) {
        requireConfigured();
        return encryptor.encrypt(rawValue);
    }

    public String decrypt(String ciphertext) {
        requireConfigured();
        return encryptor.decrypt(ciphertext);
    }

    private void requireConfigured() {
        if (!configured) {
            throw new IllegalStateException("mailsentinel.byok.encryption-key is not configured");
        }
    }
}
