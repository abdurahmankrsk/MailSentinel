package com.mailsentinel.idempotency;

public sealed interface ClaimResult {
    record Claimed(Long recordId) implements ClaimResult {}

    record Cached(String responseSnapshotJson) implements ClaimResult {}

    record InProgress() implements ClaimResult {}

    /** The same idempotency key was reused for a different logical request -- a client bug. */
    record FingerprintMismatch() implements ClaimResult {}
}
