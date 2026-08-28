package com.mailsentinel.service;

import com.mailsentinel.dto.CheckResult;
import com.mailsentinel.dto.ClaimedAuthResults;
import com.mailsentinel.dto.LiveDnsResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A DNS query that never got an answer is not evidence about the sender.
 *
 * These pin the distinction that {@link LiveDnsResult}'s resolved flags exist for:
 * "this domain publishes no SPF record" is a finding, "we could not reach DNS" is not,
 * and before the flags existed both produced the same empty answer and the same score.
 */
class DnsCheckServiceTest {

    private final DnsCheckService service = new DnsCheckService();

    private CheckResult checkNamed(List<CheckResult> checks, String fragment) {
        return checks.stream()
                .filter(c -> c.name().contains(fragment))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void unresolvedSpfLookupIsNeutralRatherThanAMissingRecord() {
        LiveDnsResult unresolved = new LiveDnsResult(false, false, false, false, null);
        List<CheckResult> checks = service.toCheckResults("example.com", unresolved);

        assertTrue(checkNamed(checks, "SPF record (live DNS)").passed());
        assertTrue(checkNamed(checks, "DMARC record & policy").passed());
    }

    @Test
    void resolvedButAbsentRecordsStillFail() {
        LiveDnsResult absent = new LiveDnsResult(false, true, false, true, null);
        List<CheckResult> checks = service.toCheckResults("example.com", absent);

        assertFalse(checkNamed(checks, "SPF record (live DNS)").passed());
        assertFalse(checkNamed(checks, "DMARC record & policy").passed());
    }

    @Test
    void unenforcedDmarcPolicyStillFailsWhenResolved() {
        LiveDnsResult monitoringOnly = new LiveDnsResult(true, true, true, true, "none");
        List<CheckResult> checks = service.toCheckResults("example.com", monitoringOnly);

        assertTrue(checkNamed(checks, "SPF record (live DNS)").passed());
        assertFalse(checkNamed(checks, "DMARC record & policy").passed());
    }

    @Test
    void headerClaimingPassIsNotContradictedByAFailedLookup() {
        ClaimedAuthResults claimed = new ClaimedAuthResults(true, "pass", "pass", "pass");
        LiveDnsResult unresolved = new LiveDnsResult(false, false, false, false, null);

        assertTrue(service.agreementCheck(claimed, unresolved).passed());
    }

    @Test
    void headerClaimingPassIsContradictedByAResolvedAbsence() {
        ClaimedAuthResults claimed = new ClaimedAuthResults(true, "pass", "pass", "pass");
        LiveDnsResult absent = new LiveDnsResult(false, true, false, true, null);

        assertFalse(service.agreementCheck(claimed, absent).passed());
    }
}
