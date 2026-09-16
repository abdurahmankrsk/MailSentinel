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

    // --- dmarc=pass under an unenforced policy ------------------------------------------
    //
    // Measured against the running app before the fix: a fully authenticated message
    // from python.org, debian.org, apache.org or fastmail.com -- all p=none -- failed this
    // check and scored 31, while the identical header from a p=reject domain scored 0.

    private static final ClaimedAuthResults ALL_PASS = new ClaimedAuthResults(true, "pass", "pass", "pass");

    @Test
    void aDmarcPassUnderAMonitoringPolicyIsNotAContradiction() {
        // A policy says what to do with mail that fails. Gmail stamps "dmarc=pass (p=NONE)".
        LiveDnsResult monitoringOnly = new LiveDnsResult(true, true, true, true, "none");

        CheckResult agreement = service.agreementCheck(ALL_PASS, monitoringOnly);

        assertTrue(agreement.passed(), agreement.detail());
    }

    @Test
    void aDmarcPassIsConsistentWithEveryPublishedPolicy() {
        for (String policy : new String[] {"none", "quarantine", "reject"}) {
            LiveDnsResult published = new LiveDnsResult(true, true, true, true, policy);
            assertTrue(service.agreementCheck(ALL_PASS, published).passed(), "p=" + policy);
        }
    }

    @Test
    void aDmarcPassWithNoRecordAtAllIsStillAContradiction() {
        // SPF present, so this isolates the DMARC clause: with no record there is nothing
        // a receiver could have evaluated to a pass.
        LiveDnsResult noDmarc = new LiveDnsResult(true, true, false, true, null);

        CheckResult agreement = service.agreementCheck(ALL_PASS, noDmarc);

        assertFalse(agreement.passed());
        assertTrue(agreement.detail().contains("no DMARC record"), agreement.detail());
    }

    @Test
    void anUnenforcedPolicyIsScoredOnceAsAWeaknessNotTwiceAsAForgery() {
        LiveDnsResult monitoringOnly = new LiveDnsResult(true, true, true, true, "none");

        assertFalse(checkNamed(service.toCheckResults("python.org", monitoringOnly), "DMARC record & policy").passed(),
                "the weakness is still reported by the live DMARC check");
        assertTrue(service.agreementCheck(ALL_PASS, monitoringOnly).passed(),
                "but it is not also reported as the header contradicting DNS");
    }

    @Test
    void aClaimedDmarcFailIsNeverTreatedAsADisagreement() {
        ClaimedAuthResults failed = new ClaimedAuthResults(true, "pass", "pass", "fail");
        LiveDnsResult monitoringOnly = new LiveDnsResult(true, true, true, true, "none");

        assertTrue(service.agreementCheck(failed, monitoringOnly).passed());
    }
}
