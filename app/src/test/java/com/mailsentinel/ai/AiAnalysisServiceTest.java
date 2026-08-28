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

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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
    private AiAnalysisService service;

    private static final Long USER_ID = 42L;

    @BeforeEach
    void setUp() {
        subscriptionService = mock(SubscriptionService.class);
        usageService = mock(UsageService.class);
        idempotencyService = mock(IdempotencyService.class);
        aiProvider = mock(AiProvider.class);
        service = new AiAnalysisService(subscriptionService, usageService, idempotencyService, aiProvider,
                new ObjectMapper(), 35, 4);
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
        return new ScanResponse(18, List.of(new CheckResult("SPF", false, 18, "no SPF record")), null);
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

        verify(aiProvider, never()).analyze(any());
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
        when(aiProvider.analyze(any())).thenThrow(new AiProviderException("boom"));

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
        when(aiProvider.analyze(any())).thenReturn(new AiAnalysisResult(
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
        when(aiProvider.analyze(any())).thenReturn(new AiAnalysisResult(
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
        when(aiProvider.analyze(any())).thenReturn(new AiAnalysisResult("Mixed bag.", List.of(
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
        when(aiProvider.analyze(any())).thenReturn(new AiAnalysisResult("ok", List.of()));

        ScanResponse result = service.analyze(user, "email", "content", deterministic(), null);

        verifyNoInteractions(idempotencyService);
        assertEquals(AiAnalysisStatus.AI_ANALYSIS_COMPLETED, result.aiAnalysis().status());
    }
}
