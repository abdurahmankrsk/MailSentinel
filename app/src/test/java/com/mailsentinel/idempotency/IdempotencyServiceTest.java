package com.mailsentinel.idempotency;

import com.mailsentinel.auth.User;
import com.mailsentinel.auth.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class IdempotencyServiceTest {

    @Autowired
    private IdempotencyService idempotencyService;

    @Autowired
    private IdempotencyRecordRepository repository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private Long newUserId(String email) {
        return userRepository.save(new User(email, "irrelevant-hash")).getId();
    }

    @Test
    void firstClaimSucceeds() {
        Long userId = newUserId("idem1@example.com");
        ClaimResult result = idempotencyService.claim(userId, "key-1", "fp-1");
        assertTrue(result instanceof ClaimResult.Claimed);
    }

    @Test
    void secondClaimOfAnAlreadySucceededKeyReturnsTheCachedResponse() {
        Long userId = newUserId("idem2@example.com");
        ClaimResult first = idempotencyService.claim(userId, "key-2", "fp-2");
        Long recordId = ((ClaimResult.Claimed) first).recordId();
        idempotencyService.markSucceeded(recordId, "{\"summary\":\"done\"}");

        ClaimResult replay = idempotencyService.claim(userId, "key-2", "fp-2");
        assertTrue(replay instanceof ClaimResult.Cached);
        assertEquals("{\"summary\":\"done\"}", ((ClaimResult.Cached) replay).responseSnapshotJson());
    }

    @Test
    void claimOfAnInProgressKeyReportsInProgress() {
        Long userId = newUserId("idem3@example.com");
        idempotencyService.claim(userId, "key-3", "fp-3"); // left IN_PROGRESS, never marked

        ClaimResult second = idempotencyService.claim(userId, "key-3", "fp-3");
        assertTrue(second instanceof ClaimResult.InProgress);
    }

    @Test
    void retryAfterFailureIsAllowedToClaimAgain() {
        Long userId = newUserId("idem4@example.com");
        ClaimResult first = idempotencyService.claim(userId, "key-4", "fp-4");
        idempotencyService.markFailed(((ClaimResult.Claimed) first).recordId());

        ClaimResult retry = idempotencyService.claim(userId, "key-4", "fp-4");
        assertTrue(retry instanceof ClaimResult.Claimed, "a failed attempt must not permanently block retries");
    }

    @Test
    void sameKeyReusedForADifferentRequestIsRejected() {
        Long userId = newUserId("idem5@example.com");
        idempotencyService.claim(userId, "key-5", "fp-original");

        ClaimResult mismatched = idempotencyService.claim(userId, "key-5", "fp-different");
        assertTrue(mismatched instanceof ClaimResult.FingerprintMismatch);
    }

    @Test
    void abandonedInProgressRecordCanBeReclaimed() {
        Long userId = newUserId("idem6@example.com");
        ClaimResult first = idempotencyService.claim(userId, "key-6", "fp-6");
        Long recordId = ((ClaimResult.Claimed) first).recordId();

        // Simulate a crash mid-request: back-date updated_at past the abandonment window
        // (PT2M in application-test.properties) instead of waiting for real time to pass.
        // Committed in its own transaction (a @Modifying query needs one, and it must be
        // committed -- not left open -- before the next claim() call, which runs its own
        // separate REQUIRES_NEW transactions and would not see an uncommitted change here).
        IdempotencyRecord record = repository.findById(recordId).orElseThrow();
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                repository.reclaim(recordId, record.getStatus(), IdempotencyStatus.IN_PROGRESS,
                        java.time.Instant.now().minusSeconds(300)));

        ClaimResult reclaimed = idempotencyService.claim(userId, "key-6", "fp-6");
        assertTrue(reclaimed instanceof ClaimResult.Claimed, "a stale abandoned IN_PROGRESS record must be reclaimable");
    }

    @Test
    void differentUsersCanUseTheSameKeyIndependently() {
        Long userA = newUserId("idem7a@example.com");
        Long userB = newUserId("idem7b@example.com");

        ClaimResult a = idempotencyService.claim(userA, "shared-key", "fp");
        ClaimResult b = idempotencyService.claim(userB, "shared-key", "fp");

        assertTrue(a instanceof ClaimResult.Claimed);
        assertTrue(b instanceof ClaimResult.Claimed, "idempotency keys are scoped per-user, not global");
    }

    @Test
    void fingerprintIsStableForTheSameInputAndDiffersForDifferentInput() {
        String fp1 = IdempotencyService.fingerprint("email", "hello world");
        String fp2 = IdempotencyService.fingerprint("email", "hello world");
        String fp3 = IdempotencyService.fingerprint("url", "hello world");
        assertEquals(fp1, fp2);
        assertTrue(!fp1.equals(fp3));
        assertEquals(64, fp1.length(), "SHA-256 hex digest is 64 characters");
    }

    /**
     * Two truly concurrent duplicate submissions racing the same (user, key) pair must
     * result in exactly one Claimed and the other seeing InProgress -- never both Claimed,
     * which would let both proceed to consume a scan and call the AI provider.
     */
    @Test
    void concurrentClaimsOnTheSameKeyOnlyLetOneWin() throws InterruptedException {
        Long userId = newUserId("idem-race@example.com");
        String key = "race-key";
        String fingerprint = "race-fp";

        int attempts = 8;
        ExecutorService executor = Executors.newFixedThreadPool(attempts);
        CountDownLatch ready = new CountDownLatch(attempts);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(attempts);
        AtomicInteger claimed = new AtomicInteger(0);
        AtomicInteger inProgress = new AtomicInteger(0);

        for (int i = 0; i < attempts; i++) {
            executor.submit(() -> {
                try {
                    ready.countDown();
                    start.await();
                    ClaimResult result = idempotencyService.claim(userId, key, fingerprint);
                    if (result instanceof ClaimResult.Claimed) {
                        claimed.incrementAndGet();
                    } else if (result instanceof ClaimResult.InProgress) {
                        inProgress.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        ready.await();
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS));
        executor.shutdown();

        assertEquals(1, claimed.get(), "exactly one concurrent claim on the same key must win");
        assertEquals(attempts - 1, inProgress.get());
    }
}
