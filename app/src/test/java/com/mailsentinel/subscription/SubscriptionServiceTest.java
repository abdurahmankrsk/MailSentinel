package com.mailsentinel.subscription;

import com.mailsentinel.auth.User;
import com.mailsentinel.auth.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@Transactional
class SubscriptionServiceTest {

    @Autowired
    private SubscriptionService subscriptionService;

    @Autowired
    private UserRepository userRepository;

    private Long newUserId(String email) {
        return userRepository.save(new User(email, "irrelevant-hash")).getId();
    }

    @Test
    void freshlyCreatedSubscriptionDefaultsToFree() {
        Long userId = newUserId("sub1@example.com");
        Subscription subscription = subscriptionService.createFreeSubscription(userId);
        assertEquals(Plan.FREE, subscription.getPlan());
        assertEquals(Plan.FREE, subscriptionService.currentPlan(userId));
        assertFalse(subscriptionService.isPremiumActive(userId));
    }

    @Test
    void userWithNoSubscriptionRowIsTreatedAsFree() {
        assertEquals(Plan.FREE, subscriptionService.currentPlan(999999L));
    }

    @Test
    void activateAndDeactivatePremiumRoundTrip() {
        Long userId = newUserId("sub2@example.com");
        subscriptionService.createFreeSubscription(userId);

        subscriptionService.activatePremium(userId);
        assertEquals(Plan.PREMIUM, subscriptionService.currentPlan(userId));
        assertTrue(subscriptionService.isPremiumActive(userId));

        subscriptionService.deactivatePremium(userId);
        assertEquals(Plan.FREE, subscriptionService.currentPlan(userId));
        assertFalse(subscriptionService.isPremiumActive(userId));
    }

    @Test
    void activatingPremiumForAUserWithNoSubscriptionRowFails() {
        Long userId = newUserId("sub3@example.com");
        assertThrows(IllegalStateException.class, () -> subscriptionService.activatePremium(userId));
    }
}
