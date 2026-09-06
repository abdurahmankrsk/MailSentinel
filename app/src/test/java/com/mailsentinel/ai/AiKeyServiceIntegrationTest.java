package com.mailsentinel.ai;

import com.mailsentinel.auth.User;
import com.mailsentinel.auth.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AiKeyServiceTest covers this class against a mocked repository, which can only prove
 * the right repository call was made -- not that it works against a real EntityManager.
 * Deleting a key needs a write transaction that a derived delete query does not get on
 * its own, and that gap is invisible to a mock, so it gets a real-persistence test here.
 */
@SpringBootTest
class AiKeyServiceIntegrationTest {

    @Autowired
    private UserAiKeyRepository repository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AiKeyService aiKeyService;

    @Test
    void deleteRemovesTheStoredKey() {
        User user = userRepository.save(new User("ai-key-delete@example.com", "hash"));
        repository.save(new UserAiKey(
                user.getId(), "Groq", "https://api.groq.com/openai/v1", "llama-3.3-70b-versatile", "cipher", "abcd"));
        assertTrue(repository.findByUserId(user.getId()).isPresent(), "key should exist before deleting it");

        aiKeyService.delete(user.getId());

        assertTrue(repository.findByUserId(user.getId()).isEmpty(), "key should be gone after delete");
    }

    @Test
    void aKeyThatNoLongerDecryptsReadsAsUnsetRatherThanThrowing() {
        // The test profile leaves mailsentinel.byok.encryption-key blank, which is the
        // same state an operator reaches by clearing or rotating BYOK_ENCRYPTION_KEY --
        // both supported, and rows written under the old secret stay behind. Letting the
        // decrypt failure escape here made /api/scan return 500 for that user, discarding
        // a deterministic result that never needed a key in the first place.
        User user = userRepository.save(new User("ai-key-stale@example.com", "hash"));
        repository.save(new UserAiKey(
                user.getId(), "Stale", "https://api.example.com/v1", "m", "not-decryptable-ciphertext", "abcd"));

        assertTrue(aiKeyService.activeKeyFor(user.getId()).isEmpty(),
                "an undecryptable key is not a usable key, and must not surface as an exception");
    }

    @Test
    void deleteIsANoOpWhenTheUserHasNoKey() {
        User user = userRepository.save(new User("ai-key-no-key@example.com", "hash"));

        aiKeyService.delete(user.getId());

        assertTrue(repository.findByUserId(user.getId()).isEmpty());
    }
}
