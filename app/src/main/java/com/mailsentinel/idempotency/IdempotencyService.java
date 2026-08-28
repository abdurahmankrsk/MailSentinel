package com.mailsentinel.idempotency;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Idempotency-key claim/replay state machine (see the plan doc for the full diagram).
 *
 * Every step here runs in its own REQUIRES_NEW transaction via TransactionTemplate,
 * deliberately not plain @Transactional: after a unique-constraint violation, Hibernate
 * poisons the current persistence context/session, so the recovery read that follows a
 * failed insert MUST happen in a genuinely separate transaction, not just a caught
 * exception within the same one. TransactionTemplate also sidesteps the classic Spring
 * AOP self-invocation trap (calling an internal @Transactional method via `this.` would
 * silently skip the proxy and not get a new transaction at all).
 *
 * Retries (a losing insert whose winner hasn't committed yet, or a lost reclaim race) are
 * an explicit bounded loop, not recursion: under real contention a few retries are normal,
 * but recursion for that would grow the call stack per attempt for no reason.
 */
@Service
public class IdempotencyService {

    private static final int MAX_CLAIM_ATTEMPTS = 20;

    private final IdempotencyRecordRepository repository;
    private final TransactionTemplate requiresNew;
    private final Duration abandonedAfter;

    public IdempotencyService(
            IdempotencyRecordRepository repository,
            PlatformTransactionManager transactionManager,
            @Value("${mailsentinel.idempotency.abandoned-after:PT2M}") Duration abandonedAfter
    ) {
        this.repository = repository;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.abandonedAfter = abandonedAfter;
    }

    public static String fingerprint(String type, String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String normalized = (type == null ? "" : type) + "|" + (content == null ? "" : content);
            return HexFormat.of().formatHex(digest.digest(normalized.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    public ClaimResult claim(Long userId, String idempotencyKey, String requestFingerprint) {
        for (int attempt = 0; attempt < MAX_CLAIM_ATTEMPTS; attempt++) {
            Optional<IdempotencyRecord> inserted = tryInsert(userId, idempotencyKey, requestFingerprint);
            if (inserted.isPresent()) {
                return new ClaimResult.Claimed(inserted.get().getId());
            }
            ClaimResult resolved = resolveExisting(userId, idempotencyKey, requestFingerprint);
            if (resolved != null) {
                return resolved;
            }
            // null means "retry from the top" -- either the conflicting row from a
            // concurrent winner isn't visible yet, or we lost a reclaim race; loop back
            // to tryInsert/resolveExisting rather than recursing.
        }
        throw new IllegalStateException(
                "Could not resolve idempotency claim for key '" + idempotencyKey + "' after " + MAX_CLAIM_ATTEMPTS + " attempts");
    }

    public void markSucceeded(Long recordId, String responseSnapshotJson) {
        requiresNew.executeWithoutResult(status ->
                repository.markSucceeded(recordId, IdempotencyStatus.SUCCEEDED, responseSnapshotJson, Instant.now()));
    }

    public void markFailed(Long recordId) {
        requiresNew.executeWithoutResult(status ->
                repository.markFailed(recordId, IdempotencyStatus.FAILED, Instant.now()));
    }

    private Optional<IdempotencyRecord> tryInsert(Long userId, String key, String fingerprint) {
        try {
            return Optional.ofNullable(requiresNew.execute(status ->
                    repository.saveAndFlush(new IdempotencyRecord(userId, key, fingerprint))));
        } catch (DataIntegrityViolationException e) {
            return Optional.empty();
        }
    }

    /** Returns null to signal "the caller should retry from the top" -- never recurses. */
    private ClaimResult resolveExisting(Long userId, String key, String fingerprint) {
        IdempotencyRecord row = requiresNew.execute(status ->
                repository.findByUserIdAndIdempotencyKey(userId, key).orElse(null));

        if (row == null) {
            // The conflicting insert hasn't committed yet (or, vanishingly rarely, its row
            // was since deleted) -- ask the caller to retry rather than assuming either.
            return null;
        }
        if (!row.getRequestFingerprint().equals(fingerprint)) {
            return new ClaimResult.FingerprintMismatch();
        }

        return switch (row.getStatus()) {
            case SUCCEEDED -> new ClaimResult.Cached(row.getResponseSnapshot());
            case FAILED -> reclaim(row, IdempotencyStatus.FAILED);
            case IN_PROGRESS -> row.getUpdatedAt().isBefore(Instant.now().minus(abandonedAfter))
                    ? reclaim(row, IdempotencyStatus.IN_PROGRESS)
                    : new ClaimResult.InProgress();
        };
    }

    /** Returns null (retry) if another attempt reclaimed the row first -- never recurses. */
    private ClaimResult reclaim(IdempotencyRecord row, IdempotencyStatus expectedStatus) {
        Integer updated = requiresNew.execute(status ->
                repository.reclaim(row.getId(), expectedStatus, IdempotencyStatus.IN_PROGRESS, Instant.now()));
        if (updated != null && updated == 1) {
            return new ClaimResult.Claimed(row.getId());
        }
        return null;
    }
}
