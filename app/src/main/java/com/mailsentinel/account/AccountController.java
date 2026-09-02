package com.mailsentinel.account;

import com.mailsentinel.ai.UserAiKeyRepository;
import com.mailsentinel.auth.AuthService;
import com.mailsentinel.auth.AuthTokenRepository;
import com.mailsentinel.auth.User;
import com.mailsentinel.subscription.Subscription;
import com.mailsentinel.subscription.SubscriptionRepository;
import com.mailsentinel.usage.UsagePeriodRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Erasure and portability -- the two things a service holding EU users' personal data
 * has to be able to do on request, and which this app previously could not do at all.
 *
 * /api/account/** requires authentication (see SecurityConfig), so currentUser is
 * guaranteed non-null in every handler here, and a user can only ever reach their own
 * record: the id comes from the resolved principal, never from the request.
 */
@RestController
@RequestMapping("/api/account")
public class AccountController {

    private final AuthService authService;
    private final AuthTokenRepository authTokenRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final UsagePeriodRepository usagePeriodRepository;
    private final UserAiKeyRepository userAiKeyRepository;

    public AccountController(
            AuthService authService,
            AuthTokenRepository authTokenRepository,
            SubscriptionRepository subscriptionRepository,
            UsagePeriodRepository usagePeriodRepository,
            UserAiKeyRepository userAiKeyRepository) {
        this.authService = authService;
        this.authTokenRepository = authTokenRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.usagePeriodRepository = usagePeriodRepository;
        this.userAiKeyRepository = userAiKeyRepository;
    }

    /**
     * Everything stored about the caller, minus the credentials -- see
     * AccountExportResponse for what is left out and why.
     */
    @GetMapping("/export")
    public AccountExportResponse export(@AuthenticationPrincipal User currentUser) {
        Long userId = currentUser.getId();
        Optional<Subscription> subscription = subscriptionRepository.findByUserId(userId);

        List<AccountExportResponse.UsagePeriodSummary> periods =
                usagePeriodRepository.findAllByUserIdOrderByPeriodStartAsc(userId).stream()
                        .map(p -> new AccountExportResponse.UsagePeriodSummary(
                                p.getPeriodStart(), p.getPeriodEnd(), p.getAllowance(), p.getScansUsed()))
                        .toList();

        AccountExportResponse.AiKeySummary aiKey = userAiKeyRepository.findByUserId(userId)
                .map(k -> new AccountExportResponse.AiKeySummary(
                        k.getLabel(), k.getKeyLast4(), k.getBaseUrl(), k.getModel(), k.getCreatedAt()))
                .orElse(null);

        Instant now = Instant.now();
        int activeSessions = (int) authTokenRepository.findAllByUserId(userId).stream()
                .filter(token -> token.isValid(now))
                .count();

        return new AccountExportResponse(
                currentUser.getEmail(),
                currentUser.getCreatedAt(),
                subscription.map(s -> s.getPlan().name()).orElse("FREE"),
                subscription.map(Subscription::getPremiumActivatedAt).orElse(null),
                aiKey,
                periods,
                activeSessions,
                "Scans are never logged or stored, so there is no scan history to export.");
    }

    /**
     * Erases the account and every row belonging to it.
     *
     * 204 rather than 200: there is nothing left to describe. The caller's token dies
     * with the account, so the next request from that client is a 401 -- which is the
     * correct outcome, and the frontend should clear its session on this response
     * rather than wait to discover it.
     */
    @DeleteMapping
    public ResponseEntity<Void> delete(@AuthenticationPrincipal User currentUser) {
        authService.deleteAccount(currentUser.getId());
        return ResponseEntity.noContent().build();
    }
}
