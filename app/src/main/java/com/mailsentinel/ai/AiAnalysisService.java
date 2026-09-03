package com.mailsentinel.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mailsentinel.auth.User;
import com.mailsentinel.dto.AiAnalysisMeta;
import com.mailsentinel.dto.AiAnalysisStatus;
import com.mailsentinel.dto.CheckResult;
import com.mailsentinel.dto.ScanResponse;
import com.mailsentinel.idempotency.ClaimResult;
import com.mailsentinel.idempotency.IdempotencyService;
import com.mailsentinel.subscription.Plan;
import com.mailsentinel.subscription.SubscriptionService;
import com.mailsentinel.usage.ReservationResult;
import com.mailsentinel.usage.UsagePeriod;
import com.mailsentinel.usage.UsageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The entire AI-routing flow lives here: entitlement -> idempotency -> reserve ->
 * provider call -> confirm/refund, exactly as specified in the plan doc.
 *
 * Deliberately NOT @Transactional at the class/method level -- each collaborator
 * (UsageService, IdempotencyService) manages its own short transaction, so no DB
 * connection sits idle from the HikariCP pool for the duration of the AI provider's
 * HTTP call, which sits in the gap between them.
 */
@Service
public class AiAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(AiAnalysisService.class);

    private final SubscriptionService subscriptionService;
    private final UsageService usageService;
    private final IdempotencyService idempotencyService;
    private final AiProvider aiProvider;
    private final AiKeyService aiKeyService;
    private final AiProviderFactory aiProviderFactory;
    private final OutboundUrlGuard outboundUrlGuard;
    private final ObjectMapper objectMapper;
    private final int maxFindingWeight;
    private final int maxFindingCount;

    public AiAnalysisService(
            SubscriptionService subscriptionService,
            UsageService usageService,
            IdempotencyService idempotencyService,
            AiProvider aiProvider,
            AiKeyService aiKeyService,
            AiProviderFactory aiProviderFactory,
            OutboundUrlGuard outboundUrlGuard,
            ObjectMapper objectMapper,
            @Value("${mailsentinel.ai.finding.max-weight:35}") int maxFindingWeight,
            @Value("${mailsentinel.ai.finding.max-count:4}") int maxFindingCount
    ) {
        this.subscriptionService = subscriptionService;
        this.usageService = usageService;
        this.idempotencyService = idempotencyService;
        this.aiProvider = aiProvider;
        this.aiProviderFactory = aiProviderFactory;
        this.aiKeyService = aiKeyService;
        this.outboundUrlGuard = outboundUrlGuard;
        this.objectMapper = objectMapper;
        this.maxFindingWeight = maxFindingWeight;
        this.maxFindingCount = maxFindingCount;
    }

    /**
     * @param currentUser resolved principal, or null if the caller is anonymous
     * @param type "email" or "url"
     * @param content the raw content that was scanned
     * @param deterministic the already-computed deterministic result (always runs first, regardless of plan)
     * @param idempotencyKey client-supplied Idempotency-Key header value, or null if absent
     * @return the final response to send back -- deterministic-only, or merged with the AI's contribution
     */
    public ScanResponse analyze(User currentUser, String type, String content,
                                 ScanResponse deterministic, String idempotencyKey) {
        if (currentUser == null) {
            return deterministic;
        }
        Long userId = currentUser.getId();

        // A user's own key always wins, on any plan, and bypasses MailSentinel's usage
        // allowance and idempotency system entirely -- both exist to protect
        // MailSentinel's own AI spend, which isn't at stake when the user is calling
        // their own endpoint directly.
        Optional<ActiveAiKey> ownKey = aiKeyService.activeKeyFor(userId);
        if (ownKey.isPresent()) {
            return analyzeWithOwnKey(type, content, deterministic, ownKey.get());
        }

        if (subscriptionService.currentPlan(userId) != Plan.PREMIUM) {
            return deterministic;
        }

        Long claimedRecordId = null;
        boolean hasIdempotencyKey = idempotencyKey != null && !idempotencyKey.isBlank();
        if (hasIdempotencyKey) {
            String fingerprint = IdempotencyService.fingerprint(type, content);
            ClaimResult claim = idempotencyService.claim(userId, idempotencyKey, fingerprint);

            if (claim instanceof ClaimResult.Cached cached) {
                return replayCached(userId, deterministic, cached.responseSnapshotJson());
            }
            if (claim instanceof ClaimResult.InProgress) {
                return withMeta(deterministic, new AiAnalysisMeta(
                        AiAnalysisStatus.AI_REQUEST_IN_PROGRESS, null,
                        "Another analysis for this request is already in progress.",
                        null, null, null, null));
            }
            if (claim instanceof ClaimResult.FingerprintMismatch) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "This Idempotency-Key was already used for a different request");
            }
            claimedRecordId = ((ClaimResult.Claimed) claim).recordId();
        }

        ReservationResult reservation = usageService.reserveOneScan(userId);
        if (reservation instanceof ReservationResult.LimitReached limitReached) {
            if (claimedRecordId != null) {
                idempotencyService.markFailed(claimedRecordId);
            }
            UsagePeriod period = limitReached.period();
            return withMeta(deterministic, new AiAnalysisMeta(
                    AiAnalysisStatus.AI_SCAN_LIMIT_REACHED, null,
                    "You have used all " + period.getAllowance() + " AI analyses for this billing period.",
                    period.getScansUsed(), 0, period.getAllowance(), period.getPeriodEnd().toString()));
        }

        UsagePeriod reservedPeriod = ((ReservationResult.Reserved) reservation).period();
        AiAnalysisRequest aiRequest = new AiAnalysisRequest(type, content, deterministic.score(), deterministic.checks());

        try {
            AiAnalysisResult rawResult = aiProvider.analyze(aiRequest, null);
            List<AiFinding> validated = validateAndClamp(rawResult.findings());
            AiAnalysisResult toSnapshot = new AiAnalysisResult(rawResult.summary(), validated);

            if (claimedRecordId != null) {
                idempotencyService.markSucceeded(claimedRecordId, objectMapper.writeValueAsString(toSnapshot));
            }

            return mergeIntoResponse(deterministic, toSnapshot, reservedPeriod.getScansUsed(),
                    reservedPeriod.remaining(), reservedPeriod.getAllowance(), reservedPeriod.getPeriodEnd().toString());
        } catch (Exception e) {
            usageService.refundOneScan(reservedPeriod.getId());
            if (claimedRecordId != null) {
                idempotencyService.markFailed(claimedRecordId);
            }
            return withMeta(deterministic, new AiAnalysisMeta(
                    AiAnalysisStatus.AI_PROVIDER_ERROR, null,
                    "AI analysis failed and was not charged against your allowance.",
                    null, null, null, null));
        }
    }

    /**
     * No usage reservation, no idempotency claim -- neither protects anything of
     * MailSentinel's when the AI call is running against the caller's own endpoint.
     * A fresh, throwaway client is built for this one call since the user's base
     * URL and model can differ from this server's own configured provider; the
     * shared aiProvider bean is only for the PREMIUM path below.
     *
     * The stored base URL is re-checked here rather than trusted because it passed
     * OutboundUrlGuard once at save time. A hostname that resolved to a public
     * address then can resolve to 127.0.0.1 now -- DNS rebinding walks straight
     * through a save-time-only check, and this endpoint runs on every scan.
     */
    private ScanResponse analyzeWithOwnKey(String type, String content, ScanResponse deterministic, ActiveAiKey ownKey) {
        AiAnalysisRequest aiRequest = new AiAnalysisRequest(type, content, deterministic.score(), deterministic.checks());
        try {
            outboundUrlGuard.requirePublicHttpsEndpoint(ownKey.baseUrl());
        } catch (BlockedEndpointException e) {
            log.warn("{}", e.getMessage());
            return withMeta(deterministic, new AiAnalysisMeta(
                    AiAnalysisStatus.AI_PROVIDER_ERROR, null,
                    "AI analysis was skipped: " + e.userMessage(),
                    null, null, null, null));
        }
        AiProvider ownProvider = aiProviderFactory.create(ownKey.baseUrl(), ownKey.model(), ownKey.apiKey());
        try {
            AiAnalysisResult rawResult = ownProvider.analyze(aiRequest, null);
            List<AiFinding> validated = validateAndClamp(rawResult.findings());
            AiAnalysisResult toSnapshot = new AiAnalysisResult(rawResult.summary(), validated);
            return mergeIntoResponse(deterministic, toSnapshot, null, null, null, null);
        } catch (Exception e) {
            return withMeta(deterministic, new AiAnalysisMeta(
                    AiAnalysisStatus.AI_PROVIDER_ERROR, null,
                    "AI analysis failed using your own API key.",
                    null, null, null, null));
        }
    }

    private ScanResponse replayCached(Long userId, ScanResponse deterministic, String responseSnapshotJson) {
        try {
            AiAnalysisResult cached = objectMapper.readValue(responseSnapshotJson, AiAnalysisResult.class);
            var status = usageService.currentStatus(userId, true);
            return mergeIntoResponse(deterministic, cached, status.scansUsed(), status.scansRemaining(),
                    status.scansAllowance(), status.periodEnd() == null ? null : status.periodEnd().toString());
        } catch (Exception e) {
            // A cached snapshot that fails to parse shouldn't happen, but degrade to the
            // deterministic-only result rather than surfacing an opaque 500.
            return withMeta(deterministic, new AiAnalysisMeta(
                    AiAnalysisStatus.AI_PROVIDER_ERROR, null,
                    "Could not replay the cached AI analysis.", null, null, null, null));
        }
    }

    private ScanResponse mergeIntoResponse(ScanResponse deterministic, AiAnalysisResult aiResult,
                                            Integer scansUsed, Integer scansRemaining, Integer scansAllowance, String resetDate) {
        List<CheckResult> merged = new ArrayList<>(deterministic.checks());
        for (AiFinding finding : aiResult.findings()) {
            merged.add(new CheckResult("AI: " + finding.name(), false, finding.weight(), finding.detail()));
        }
        int recomputedScore = Math.min(100, merged.stream()
                .filter(c -> !c.passed())
                .mapToInt(CheckResult::weight)
                .sum());

        AiAnalysisMeta meta = new AiAnalysisMeta(
                AiAnalysisStatus.AI_ANALYSIS_COMPLETED, aiResult.summary(), null,
                scansUsed, scansRemaining, scansAllowance, resetDate);

        return new ScanResponse(recomputedScore, merged, meta, deterministic.brandsWatched());
    }

    private List<AiFinding> validateAndClamp(List<AiFinding> rawFindings) {
        if (rawFindings == null) {
            return List.of();
        }
        List<AiFinding> result = new ArrayList<>();
        for (AiFinding finding : rawFindings) {
            if (result.size() >= maxFindingCount) {
                break;
            }
            if (finding == null || finding.name() == null || finding.name().isBlank()
                    || finding.detail() == null || finding.detail().isBlank() || finding.weight() == null) {
                continue; // drop malformed entries rather than trust them
            }
            int clampedWeight = Math.max(0, Math.min(maxFindingWeight, finding.weight()));
            if (clampedWeight == 0) {
                continue; // a zero-weight "finding" contributes nothing; don't clutter the list
            }
            result.add(new AiFinding(finding.name(), clampedWeight, finding.detail()));
        }
        return result;
    }

    private ScanResponse withMeta(ScanResponse deterministic, AiAnalysisMeta meta) {
        return new ScanResponse(deterministic.score(), deterministic.checks(), meta, deterministic.brandsWatched());
    }
}
