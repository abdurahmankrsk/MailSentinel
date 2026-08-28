package com.mailsentinel.subscription;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "subscriptions")
public class Subscription {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, unique = true)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Plan plan;

    @Column(name = "premium_activated_at")
    private Instant premiumActivatedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Subscription() {
        // JPA
    }

    public Subscription(Long userId) {
        this.userId = userId;
        this.plan = Plan.FREE;
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void activatePremium() {
        this.plan = Plan.PREMIUM;
        this.premiumActivatedAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public void deactivatePremium() {
        this.plan = Plan.FREE;
        this.updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public Plan getPlan() {
        return plan;
    }

    public Instant getPremiumActivatedAt() {
        return premiumActivatedAt;
    }
}
