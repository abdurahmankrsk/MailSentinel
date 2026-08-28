package com.mailsentinel.usage;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "usage_periods")
public class UsagePeriod {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "period_start", nullable = false)
    private Instant periodStart;

    @Column(name = "period_end", nullable = false)
    private Instant periodEnd;

    // Snapshotted from PlanCatalog at creation time -- never re-read for an already-open
    // period, so a mid-period config change never retroactively changes what a user
    // already has, only what the *next* period gets.
    @Column(nullable = false)
    private int allowance;

    @Column(name = "scans_used", nullable = false)
    private int scansUsed;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected UsagePeriod() {
        // JPA
    }

    public UsagePeriod(Long userId, Instant periodStart, Instant periodEnd, int allowance) {
        this.userId = userId;
        this.periodStart = periodStart;
        this.periodEnd = periodEnd;
        this.allowance = allowance;
        this.scansUsed = 0;
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    public boolean isActive(Instant now) {
        return !now.isBefore(periodStart) && now.isBefore(periodEnd);
    }

    public int remaining() {
        return Math.max(0, allowance - scansUsed);
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public Instant getPeriodStart() {
        return periodStart;
    }

    public Instant getPeriodEnd() {
        return periodEnd;
    }

    public int getAllowance() {
        return allowance;
    }

    public int getScansUsed() {
        return scansUsed;
    }
}
