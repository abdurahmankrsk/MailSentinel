package com.mailsentinel.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mailsentinel.auth.User;
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
import com.mailsentinel.usage.UsageStatusResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.net.InetAddress;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AiAnalysisServiceTest {

    private SubscriptionService subscriptionService;
    private UsageService usageService;
    private IdempotencyService idempotencyService;
    private AiProvider aiProvider;
    private AiKeyService aiKeyService;
    private AiProviderFactory aiProviderFactory;
    private AiAnalysisService service;

    private static final Long USER_ID = 42L;
    private static final String PUBLIC_ADDRESS = "93.184.216.34";

    @BeforeEach
    void setUp() {
        subscriptionService = mock(SubscriptionService.class);
        usageService = mock(UsageService.class);
        idempotencyService = mock(IdempotencyService.class);
        aiProvider = mock(AiProvider.class);
        aiKeyService = mock(AiKeyService.class);
        aiProviderFactory = mock(AiProviderFactory.class);
        // Every test below exercises the existing Plan-gated path; a BYOK key would
        // short-circuit it entirely, so default to "no key configured" here and let
        // the dedicated BYOK tests below override this per-test.
        when(aiKeyService.activeKeyFor(any())).thenReturn(Optional.empty());
        service = serviceWithGuard(guardResolving(PUBLIC_ADDRESS));
    }

    private AiAnalysisService serviceWithGuard(OutboundUrlGuard guard) {
        return new AiAnalysisService(subscriptionService, usageService, idempotencyService, aiProvider,
                aiKeyService, aiProviderFactory, guard, new ObjectMapper(), 35, 4);
    }

    // Stubbed rather than the production resolver: the own-key tests below use real
    // hostnames, and none of them should depend on a live DNS lookup succeeding.
    private static OutboundUrlGuard guardResolving(String address) {
        return new OutboundUrlGuard(false, host -> new InetAddress[]{InetAddress.getByName(address)});
    }

    // The public constructor doesn't accept an id (JPA generates it); reflection sets
    // it directly here purely so this Mockito-only test doesn't need a real database.
    private User userWith(Long id) {
        User user = new User("user" + id + "@example.com", "hash");
        try {
            var idField = User.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(user, id);
            return user;
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    // The one check is FAILED (passed=false) so its weight actually contributes to the
    // score, matching how AiAnalysisService recomputes score from scratch by summing
    // failed-check weights across deterministic + AI findings combined -- it doesn't
    // trust this record's own precomputed score field once AI findings are merged in.
    private ScanResponse deterministic() {
        return new ScanResponse(18, List.of(new CheckResult("SPF", false, 18, "no SPF record")), null, 70);
    }

    // scansUsed is only ever mutated via the real atomic repository UPDATE (see
    // UsageServiceTest for that coverage) -- here we mock UsageService's return value
    // directly, so this just needs a valid period shell (id/allowance/periodEnd) for
    // AiAnalysisService's response-building code to read.
    private UsagePeriod newPeriod(int allowance) {
        return new UsagePeriod(USER_ID, Instant.now(), Instant.now().plusSeconds(3600), allowance);
    }

    @Test
    void anonymousCallerGetsDeterministicResultWithZeroCollaboratorInteraction() {
        ScanResponse deterministic = deterministic();
        ScanResponse result = service.analyze(null, "email", "content", deterministic, null);

        assertEquals(deterministic, result);
        verifyNoInteractions(subscriptionService, usageService, idempotencyService, aiProvider);
    }

    @Test
    void freePlanUserGetsDeterministicResultUnchanged() {
        User user = userWith(USER_ID);
        when(subscriptionService.currentPlan(USER_ID)).thenReturn(Plan.FREE);

        ScanResponse deterministic = deterministic();
        ScanResponse result = service.analyze(user, "email", "content", deterministic, null);

        assertEquals(deterministic, result);
        verifyNoInteractions(usageService, idempotencyService, aiProvider);
    }

    @Test
    void limitReachedNeverCallsTheProviderAndReportsTheStatus() throws Exception {
        User user = userWith(USER_ID);
        when(subscriptionService.currentPlan(USER_ID)).thenReturn(Plan.PREMIUM);
        UsagePeriod period = newPeriod(1000);
        when(usageService.reserveOneScan(USER_ID)).thenReturn(new ReservationResult.LimitReached(period));

        ScanResponse result = service.analyze(user, "email", "content", deterministic(), null);

        verify(aiProvider, never()).analyze(any(), any());
        assertEquals(AiAnalysisStatus.AI_SCAN_LIMIT_REACHED, result.aiAnalysis().status());
        assertEquals(0, result.aiAnalysis().scansRemaining());
        assertEquals(deterministic().score(), result.score(), "score must stay deterministic-only when the AI never ran");
    }

    @Test
    void providerFailureRefundsExactlyOnceAndReportsProviderError() throws Exception {
        User user = userWith(USER_ID);
        when(subscriptionService.currentPlan(USER_ID)).thenReturn(Plan.PREMIUM);
        UsagePeriod period = newPeriod(1000);
        when(usageService.reserveOneScan(USER_ID)).thenReturn(new ReservationResult.Reserved(period));
        when(aiProvider.analyze(any(), any())).thenThrow(new AiProviderException("boom"));

        ScanResponse result = service.analyze(user, "email", "content", deterministic(), null);

        verify(usageService, times(1)).refundOneScan(period.getId());
        assertEquals(AiAnalysisStatus.AI_PROVIDER_ERROR, result.aiAnalysis().status());
        assertEquals(deterministic().checks().size(), result.checks().size(), "no AI findings should be merged in on failure");
    }

    @Test
    void successMergesFindingsAndRecomputesScore() throws Exception {
        User user = userWith(USER_ID);
        when(subscriptionService.currentPlan(USER_ID)).thenReturn(Plan.PREMIUM);
        UsagePeriod period = newPeriod(1000);
        when(usageService.reserveOneScan(USER_ID)).thenReturn(new ReservationResult.Reserved(period));
        when(aiProvider.analyze(any(), any())).thenReturn(new AiAnalysisResult(
                "Looks suspicious.", List.of(new AiFinding("Urgency language", 15, "Pressure tactics detected."))));

        ScanResponse result = service.analyze(user, "email", "content", deterministic(), null);

        assertEquals(AiAnalysisStatus.AI_ANALYSIS_COMPLETED, result.aiAnalysis().status());
        assertEquals("Looks suspicious.", result.aiAnalysis().summary());
        assertEquals(2, result.checks().size(), "deterministic check plus one AI finding");
        assertTrue(result.checks().get(1).name().startsWith("AI: "), "AI findings must be visibly labeled");
        assertEquals(18 + 15, result.score(), "score must include the AI's additive contribution");
    }

    @Test
    void aiFindingWeightIsClampedToTheConfiguredMaximum() throws Exception {
        User user = userWith(USER_ID);
        when(subscriptionService.currentPlan(USER_ID)).thenReturn(Plan.PREMIUM);
        UsagePeriod period = newPeriod(1000);
        when(usageService.reserveOneScan(USER_ID)).thenReturn(new ReservationResult.Reserved(period));
        // The model returns a wildly high weight -- must be clamped, never trusted raw.
        when(aiProvider.analyze(any(), any())).thenReturn(new AiAnalysisResult(
                "Extremely suspicious.", List.of(new AiFinding("Everything is wrong", 9999, "Trust me."))));

        ScanResponse result = service.analyze(user, "email", "content", deterministic(), null);

        assertEquals(18 + 35, result.score(), "a single AI finding must never exceed the configured max weight (35)");
    }

    @Test
    void malformedFindingsAreDroppedAndCountIsTruncated() throws Exception {
        User user = userWith(USER_ID);
        when(subscriptionService.currentPlan(USER_ID)).thenReturn(Plan.PREMIUM);
        UsagePeriod period = newPeriod(1000);
        when(usageService.reserveOneScan(USER_ID)).thenReturn(new ReservationResult.Reserved(period));
        when(aiProvider.analyze(any(), any())).thenReturn(new AiAnalysisResult("Mixed bag.", List.of(
                new AiFinding(null, 10, "missing name -- dropped"),
                new AiFinding("no weight", null, "missing weight -- dropped"),
                new AiFinding("ok1", 5, "kept"),
                new AiFinding("ok2", 5, "kept"),
                new AiFinding("ok3", 5, "kept"),
                new AiFinding("ok4", 5, "kept"),
                new AiFinding("ok5 -- over the count cap", 5, "dropped, only 4 allowed")
        )));

        ScanResponse result = service.analyze(user, "email", "content", deterministic(), null);

        // 1 deterministic + at most 4 AI findings (malformed ones dropped, 5th truncated)
        assertEquals(1 + 4, result.checks().size());
    }

    @Test
    void cachedIdempotencyClaimNeverCallsTheProviderAgain() {
        User user = userWith(USER_ID);
        when(subscriptionService.currentPlan(USER_ID)).thenReturn(Plan.PREMIUM);
        String snapshot = "{\"summary\":\"cached\",\"findings\":[]}";
        when(idempotencyService.claim(eq(USER_ID), eq("key-1"), anyString()))
                .thenReturn(new ClaimResult.Cached(snapshot));
        when(usageService.currentStatus(USER_ID, true))
                .thenReturn(new UsageStatusResponse("PREMIUM", 1000, 5, 995, Instant.now(), Instant.now().plusSeconds(3600)));

        ScanResponse result = service.analyze(user, "email", "content", deterministic(), "key-1");

        verifyNoInteractions(aiProvider);
        assertEquals(AiAnalysisStatus.AI_ANALYSIS_COMPLETED, result.aiAnalysis().status());
        assertEquals("cached", result.aiAnalysis().summary());
    }

    @Test
    void inProgressIdempotencyClaimNeverCallsTheProviderAndReportsInProgress() {
        User user = userWith(USER_ID);
        when(subscriptionService.currentPlan(USER_ID)).thenReturn(Plan.PREMIUM);
        when(idempotencyService.claim(eq(USER_ID), eq("key-2"), anyString()))
                .thenReturn(new ClaimResult.InProgress());

        ScanResponse result = service.analyze(user, "email", "content", deterministic(), "key-2");

        verifyNoInteractions(aiProvider, usageService);
        assertEquals(AiAnalysisStatus.AI_REQUEST_IN_PROGRESS, result.aiAnalysis().status());
    }

    @Test
    void fingerprintMismatchIsRejectedWithConflict() {
        User user = userWith(USER_ID);
        when(subscriptionService.currentPlan(USER_ID)).thenReturn(Plan.PREMIUM);
        when(idempotencyService.claim(eq(USER_ID), eq("key-3"), anyString()))
                .thenReturn(new ClaimResult.FingerprintMismatch());

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.analyze(user, "email", "content", deterministic(), "key-3"));
        assertEquals(409, ex.getStatusCode().value());
        verifyNoInteractions(aiProvider, usageService);
    }

    @Test
    void limitReachedWithAnOpenIdempotencyClaimMarksItFailedSoARetryIsAllowedLater() {
        User user = userWith(USER_ID);
        when(subscriptionService.currentPlan(USER_ID)).thenReturn(Plan.PREMIUM);
        when(idempotencyService.claim(eq(USER_ID), eq("key-4"), anyString()))
                .thenReturn(new ClaimResult.Claimed(777L));
        UsagePeriod period = newPeriod(1000);
        when(usageService.reserveOneScan(USER_ID)).thenReturn(new ReservationResult.LimitReached(period));

        service.analyze(user, "email", "content", deterministic(), "key-4");

        verify(idempotencyService, times(1)).markFailed(777L);
    }

    @Test
    void noIdempotencyKeyStillCompletesNormally() throws Exception {
        User user = userWith(USER_ID);
        when(subscriptionService.currentPlan(USER_ID)).thenReturn(Plan.PREMIUM);
        UsagePeriod period = newPeriod(1000);
        when(usageService.reserveOneScan(USER_ID)).thenReturn(new ReservationResult.Reserved(period));
        when(aiProvider.analyze(any(), any())).thenReturn(new AiAnalysisResult("ok", List.of()));

        ScanResponse result = service.analyze(user, "email", "content", deterministic(), null);

        verifyNoInteractions(idempotencyService);
        assertEquals(AiAnalysisStatus.AI_ANALYSIS_COMPLETED, result.aiAnalysis().status());
    }

    @Test
    void ownKeyRunsAiOnFreePlanWithoutTouchingUsageOrIdempotency() throws Exception {
        User user = userWith(USER_ID);
        ActiveAiKey ownKey = new ActiveAiKey("https://api.groq.com/openai/v1", "llama-3.3-70b-versatile", "user-supplied-key");
        AiProvider ownProvider = mock(AiProvider.class);
        when(aiKeyService.activeKeyFor(USER_ID)).thenReturn(Optional.of(ownKey));
        when(aiProviderFactory.create(ownKey.baseUrl(), ownKey.model(), ownKey.apiKey())).thenReturn(ownProvider);
        when(ownProvider.analyze(any(), isNull())).thenReturn(new AiAnalysisResult(
                "Looks suspicious.", List.of(new AiFinding("Urgency language", 15, "Pressure tactics detected."))));

        ScanResponse result = service.analyze(user, "email", "content", deterministic(), "some-key");

        assertEquals(AiAnalysisStatus.AI_ANALYSIS_COMPLETED, result.aiAnalysis().status());
        assertEquals(18 + 15, result.score(), "own-key findings still contribute to score the same way");
        verifyNoInteractions(subscriptionService, usageService, idempotencyService, aiProvider);
    }

    @Test
    void ownKeyProviderFailureReportsErrorWithoutTouchingUsage() throws Exception {
        User user = userWith(USER_ID);
        ActiveAiKey ownKey = new ActiveAiKey("https://api.example.com/v1", "some-model", "bad-key");
        AiProvider ownProvider = mock(AiProvider.class);
        when(aiKeyService.activeKeyFor(USER_ID)).thenReturn(Optional.of(ownKey));
        when(aiProviderFactory.create(ownKey.baseUrl(), ownKey.model(), ownKey.apiKey())).thenReturn(ownProvider);
        when(ownProvider.analyze(any(), isNull())).thenThrow(new AiProviderException("invalid key"));

        ScanResponse result = service.analyze(user, "email", "content", deterministic(), null);

        assertEquals(AiAnalysisStatus.AI_PROVIDER_ERROR, result.aiAnalysis().status());
        assertEquals(deterministic().score(), result.score(), "score stays deterministic-only when the own-key call fails");
        verifyNoInteractions(subscriptionService, usageService, idempotencyService, aiProvider);
    }

    /**
     * The saved base URL passed the guard once, at save time. If the name it points at
     * now answers with a private address -- DNS rebinding -- the scan must not make the
     * call, and the deterministic result must still come back.
     */
    @Test
    void ownKeyEndpointIsRecheckedAtScanTimeAndSkippedIfItNowResolvesPrivate() {
        service = serviceWithGuard(guardResolving("169.254.169.254"));
        User user = userWith(USER_ID);
        ActiveAiKey ownKey = new ActiveAiKey("https://rebind.example.com/v1", "some-model", "user-supplied-key");
        when(aiKeyService.activeKeyFor(USER_ID)).thenReturn(Optional.of(ownKey));

        ScanResponse result = service.analyze(user, "email", "content", deterministic(), null);

        assertEquals(AiAnalysisStatus.AI_PROVIDER_ERROR, result.aiAnalysis().status());
        assertEquals(deterministic().score(), result.score());
        verifyNoInteractions(aiProviderFactory, subscriptionService, usageService, idempotencyService, aiProvider);
    }
}
