package com.mailsentinel.usage;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface UsagePeriodRepository extends JpaRepository<UsagePeriod, Long> {

    Optional<UsagePeriod> findFirstByUserIdOrderByPeriodStartDesc(Long userId);

    /**
     * The concurrency enforcement point: reservation and the limit check are the same
     * atomic statement, so there is no window between checking and incrementing for a
     * second concurrent request to slip through. Returns the number of rows updated --
     * 1 means reserved, 0 means the allowance was already exhausted.
     * clearAutomatically evicts the stale in-session entity so a subsequent read in the
     * same transaction sees the post-update value rather than a cached stale one.
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE UsagePeriod p SET p.scansUsed = p.scansUsed + 1, p.updatedAt = :now "
            + "WHERE p.id = :id AND p.scansUsed < p.allowance")
    int reserveOneScan(@Param("id") Long id, @Param("now") Instant now);

    /** Mirror of reserveOneScan, floored at 0 -- used to refund a reservation on AI-provider failure. */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE UsagePeriod p SET p.scansUsed = p.scansUsed - 1, p.updatedAt = :now "
            + "WHERE p.id = :id AND p.scansUsed > 0")
    int refundOneScan(@Param("id") Long id, @Param("now") Instant now);
}
