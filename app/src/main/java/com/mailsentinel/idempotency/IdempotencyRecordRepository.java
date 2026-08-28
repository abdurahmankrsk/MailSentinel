package com.mailsentinel.idempotency;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface IdempotencyRecordRepository extends JpaRepository<IdempotencyRecord, Long> {

    Optional<IdempotencyRecord> findByUserIdAndIdempotencyKey(Long userId, String idempotencyKey);

    /**
     * Atomically transitions a FAILED (retry) or stale IN_PROGRESS (abandoned, e.g. a
     * crash mid-request) record back to IN_PROGRESS -- guarded by matching the status
     * we last observed, so two concurrent reclaim attempts can't both win.
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE IdempotencyRecord r SET r.status = :newStatus, r.updatedAt = :now "
            + "WHERE r.id = :id AND r.status = :expectedStatus")
    int reclaim(@Param("id") Long id, @Param("expectedStatus") IdempotencyStatus expectedStatus,
                @Param("newStatus") IdempotencyStatus newStatus, @Param("now") Instant now);

    @Modifying(clearAutomatically = true)
    @Query("UPDATE IdempotencyRecord r SET r.status = :status, r.responseSnapshot = :snapshot, r.updatedAt = :now "
            + "WHERE r.id = :id")
    int markSucceeded(@Param("id") Long id, @Param("status") IdempotencyStatus status,
                       @Param("snapshot") String snapshot, @Param("now") Instant now);

    @Modifying(clearAutomatically = true)
    @Query("UPDATE IdempotencyRecord r SET r.status = :status, r.updatedAt = :now WHERE r.id = :id")
    int markFailed(@Param("id") Long id, @Param("status") IdempotencyStatus status, @Param("now") Instant now);
}
