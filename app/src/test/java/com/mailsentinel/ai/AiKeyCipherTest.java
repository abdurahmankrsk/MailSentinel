package com.mailsentinel.ai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiKeyCipherTest {

    @Test
    void blankMasterKeyMeansUnconfigured() {
        AiKeyCipher cipher = new AiKeyCipher("");
        assertFalse(cipher.isConfigured());
    }

    @Test
    void unconfiguredCipherRefusesToEncryptOrDecrypt() {
        AiKeyCipher cipher = new AiKeyCipher("");
        assertThrows(IllegalStateException.class, () -> cipher.encrypt("gsk_something"));
        assertThrows(IllegalStateException.class, () -> cipher.decrypt("anything"));
    }

    @Test
    void roundTripsARawKey() {
        AiKeyCipher cipher = new AiKeyCipher("a-long-random-master-key-for-tests");

        String ciphertext = cipher.encrypt("gsk_realapikeyvalue");

        assertTrue(cipher.isConfigured());
        assertNotEquals("gsk_realapikeyvalue", ciphertext, "ciphertext must not equal the plaintext");
        assertEquals("gsk_realapikeyvalue", cipher.decrypt(ciphertext));
    }

    @Test
    void twoCiphersWithDifferentMasterKeysCannotDecryptEachOther() {
        AiKeyCipher a = new AiKeyCipher("master-key-a");
        AiKeyCipher b = new AiKeyCipher("master-key-b");

        String ciphertext = a.encrypt("gsk_realapikeyvalue");

        assertThrows(Exception.class, () -> b.decrypt(ciphertext));
    }
}
