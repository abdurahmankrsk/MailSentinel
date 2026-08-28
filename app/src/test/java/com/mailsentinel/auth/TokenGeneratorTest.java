package com.mailsentinel.auth;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TokenGeneratorTest {

    private final TokenGenerator generator = new TokenGenerator();

    @Test
    void tokensCarryTheRecognisablePrefix() {
        assertTrue(generator.generateRawToken().startsWith("mst_"));
    }

    @Test
    void tokensAreUniqueAcrossManyGenerations() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            assertTrue(seen.add(generator.generateRawToken()), "generated a duplicate token");
        }
    }

    @Test
    void tokensCarryTheFull256BitsOfEntropy() {
        // 32 random bytes, base64url without padding, is 43 characters. A silently
        // shortened token would still look fine and still be unique in the test above.
        String token = generator.generateRawToken();
        assertEquals(43, token.substring("mst_".length()).length());
    }

    @Test
    void tokensAreUrlSafeSoTheySurviveAHeaderOrQueryStringIntact() {
        for (int i = 0; i < 200; i++) {
            String body = generator.generateRawToken().substring("mst_".length());
            assertTrue(body.matches("[A-Za-z0-9_-]+"), "not url-safe: " + body);
        }
    }

    @Test
    void hashingIsDeterministicSoALookupCanFindTheStoredToken() {
        String token = generator.generateRawToken();
        assertEquals(generator.hash(token), generator.hash(token));
        // A second instance must agree, since the instance that hashes on login is never
        // the one that hashed at issue time.
        assertEquals(generator.hash(token), new TokenGenerator().hash(token));
    }

    @Test
    void differentTokensHashDifferently() {
        assertNotEquals(generator.hash(generator.generateRawToken()), generator.hash(generator.generateRawToken()));
    }

    @Test
    void theHashIsSha256Hex() {
        assertTrue(generator.hash("anything").matches("[0-9a-f]{64}"));
        // Pinned against a known vector, so a change of algorithm cannot pass unnoticed
        // and silently invalidate every stored token hash.
        assertEquals(
                "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08",
                generator.hash("test"));
    }

    @Test
    void theRawTokenIsNotRecoverableFromItsHash() {
        String token = generator.generateRawToken();
        assertFalseContains(generator.hash(token), token);
    }

    private static void assertFalseContains(String haystack, String needle) {
        assertTrue(!haystack.contains(needle), "stored hash must not embed the raw token");
    }
}
