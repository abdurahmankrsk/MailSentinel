package com.mailsentinel.usage;

import com.mailsentinel.auth.User;
import com.mailsentinel.subscription.Plan;
import com.mailsentinel.subscription.SubscriptionService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * /api/usage/** requires authentication (see SecurityConfig), so currentUser is
 * guaranteed non-null here -- an unauthenticated request never reaches this handler.
 */
@RestController
@RequestMapping("/api/usage")
public class UsageController {

    private final UsageService usageService;
    private final SubscriptionService subscriptionService;

    public UsageController(UsageService usageService, SubscriptionService subscriptionService) {
        this.usageService = usageService;
        this.subscriptionService = subscriptionService;
    }

    @GetMapping("/me")
    public UsageStatusResponse me(@AuthenticationPrincipal User currentUser) {
        boolean isPremium = subscriptionService.currentPlan(currentUser.getId()) == Plan.PREMIUM;
        return usageService.currentStatus(currentUser.getId(), isPremium);
    }
}
