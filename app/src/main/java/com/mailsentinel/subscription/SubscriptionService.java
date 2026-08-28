package com.mailsentinel.subscription;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Plan entitlement. These activate/deactivate methods are the exact seam a future
 * payment webhook handler will call -- nothing else in the app needs to change when
 * real billing ships.
 */
@Service
public class SubscriptionService {

    private final SubscriptionRepository subscriptionRepository;

    public SubscriptionService(SubscriptionRepository subscriptionRepository) {
        this.subscriptionRepository = subscriptionRepository;
    }

    @Transactional
    public Subscription createFreeSubscription(Long userId) {
        return subscriptionRepository.save(new Subscription(userId));
    }

    public Plan currentPlan(Long userId) {
        return subscriptionRepository.findByUserId(userId)
                .map(Subscription::getPlan)
                .orElse(Plan.FREE);
    }

    public boolean isPremiumActive(Long userId) {
        return currentPlan(userId) == Plan.PREMIUM;
    }

    @Transactional
    public void activatePremium(Long userId) {
        Subscription subscription = requireSubscription(userId);
        subscription.activatePremium();
        subscriptionRepository.save(subscription);
    }

    @Transactional
    public void deactivatePremium(Long userId) {
        Subscription subscription = requireSubscription(userId);
        subscription.deactivatePremium();
        subscriptionRepository.save(subscription);
    }

    private Subscription requireSubscription(Long userId) {
        return subscriptionRepository.findByUserId(userId)
                .orElseThrow(() -> new IllegalStateException("No subscription record for user " + userId));
    }
}
