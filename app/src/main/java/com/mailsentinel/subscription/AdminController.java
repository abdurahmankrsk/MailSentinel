package com.mailsentinel.subscription;

import com.mailsentinel.auth.User;
import com.mailsentinel.auth.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Locale;

/**
 * Minimal, clearly-marked manual/dev-only stand-in for a payment webhook -- the only
 * way to grant PREMIUM until Stripe (or similar) exists. Delegates to
 * SubscriptionService.activatePremium/deactivatePremium, the exact methods a real
 * webhook handler will call later; this controller is the only thing that goes away
 * when that ships.
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final UserRepository userRepository;
    private final SubscriptionService subscriptionService;

    public AdminController(UserRepository userRepository, SubscriptionService subscriptionService) {
        this.userRepository = userRepository;
        this.subscriptionService = subscriptionService;
    }

    @PostMapping("/grant-premium")
    public PlanChangeResponse grantPremium(@RequestBody GrantPremiumRequest request) {
        User user = requireUser(request.email());
        subscriptionService.activatePremium(user.getId());
        return new PlanChangeResponse(user.getEmail(), Plan.PREMIUM.name());
    }

    @PostMapping("/revoke-premium")
    public PlanChangeResponse revokePremium(@RequestBody GrantPremiumRequest request) {
        User user = requireUser(request.email());
        subscriptionService.deactivatePremium(user.getId());
        return new PlanChangeResponse(user.getEmail(), Plan.FREE.name());
    }

    private User requireUser(String email) {
        String normalized = email == null ? null : email.trim().toLowerCase(Locale.ROOT);
        return userRepository.findByEmail(normalized)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No user with that email"));
    }
}
