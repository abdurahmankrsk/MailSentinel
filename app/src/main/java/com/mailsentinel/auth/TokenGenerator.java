package com.mailsentinel.auth;

import org.springframework.stereotype.Component;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Generates opaque bearer tokens and hashes them for storage.
 *
 * Tokens are high-entropy random values (256 bits), not passwords, so they are
 * hashed with plain SHA-256 rather than BCrypt: BCrypt's deliberate slowness exists
 * to defend low-entropy human-chosen secrets, and would add real latency to every
 * authenticated request for zero benefit against an already-unguessable token.
 */
@Component
public class TokenGenerator {

    private static final String PREFIX = "mst_";
    private static final int RANDOM_BYTES = 32; // 256 bits
    private final SecureRandom secureRandom = new SecureRandom();

    public String generateRawToken() {
        byte[] randomBytes = new byte[RANDOM_BYTES];
        secureRandom.nextBytes(randomBytes);
        String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
        return PREFIX + encoded;
    }

    public String hash(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(rawToken.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashed);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is a mandatory JDK algorithm; this can never actually happen.
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
