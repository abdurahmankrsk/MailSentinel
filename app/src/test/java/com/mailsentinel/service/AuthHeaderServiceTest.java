package com.mailsentinel.service;

import com.mailsentinel.dto.ClaimedAuthResults;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthHeaderServiceTest {

    private final AuthHeaderService service = new AuthHeaderService();

    @Test
    void parsesEachMechanismOutOfARealHeader() {
        ClaimedAuthResults claimed = service.parseAuthenticationResults(
                "mx.example.com; spf=fail smtp.mailfrom=paypa1.com; dkim=none; dmarc=fail");

        assertTrue(claimed.headerPresent());
        assertEquals("fail", claimed.spf());
        assertEquals("none", claimed.dkim());
        assertEquals("fail", claimed.dmarc());
    }

    @Test
    void toleratesWhitespaceAndCaseAroundTheAssignments() {
        ClaimedAuthResults claimed = service.parseAuthenticationResults("mx; SPF = Pass; DKIM=PASS; dmarc =pass");
        assertEquals("pass", claimed.spf());
        assertEquals("pass", claimed.dkim());
        assertEquals("pass", claimed.dmarc());
    }

    @Test
    void anAbsentHeaderIsNotPresentRatherThanFailing() {
        // Absence is explicitly neutral in this project: plenty of legitimate mail flows
        // add no Authentication-Results at all, so it must never read as a failure.
        for (String value : new String[] { null, "", "   " }) {
            ClaimedAuthResults claimed = service.parseAuthenticationResults(value);
            assertFalse(claimed.headerPresent());
            assertNull(claimed.spf());
            assertNull(claimed.dkim());
            assertNull(claimed.dmarc());
        }
    }

    @Test
    void aHeaderNamingOnlySomeMechanismsLeavesTheOthersNull() {
        ClaimedAuthResults claimed = service.parseAuthenticationResults("mx.example.com; spf=pass");
        assertTrue(claimed.headerPresent());
        assertEquals("pass", claimed.spf());
        assertNull(claimed.dkim());
        assertNull(claimed.dmarc());
    }

    @Test
    void theFirstVerdictForAMechanismWins() {
        // Multiple hops can each append a result. Taking the first is the current
        // contract; pinning it here so a change to that ordering is a deliberate one.
        ClaimedAuthResults claimed = service.parseAuthenticationResults("mx; dkim=fail; dkim=pass");
        assertEquals("fail", claimed.dkim());
    }

    @Test
    void anAbsentHeaderProducesThreePassingChecksThatSayWhy() {
        List<com.mailsentinel.dto.CheckResult> checks =
                service.toCheckResults(service.parseAuthenticationResults(null));

        assertEquals(3, checks.size(), "every applicable check is always reported");
        assertTrue(checks.stream().allMatch(com.mailsentinel.dto.CheckResult::passed));
        // The detail is what stops a neutral result from reading as a verified one --
        // the browser extension's "headers were not available" warning relies on this.
        assertTrue(checks.stream().allMatch(check -> check.detail().contains("No Authentication-Results header")));
    }

    @Test
    void aHeaderThatOmitsOneMechanismIsNotReportedAsAMissingHeader() {
        // The sibling case to anAbsentHeaderProducesThreePassingChecksThatSayWhy: here the
        // header is right there in the pasted message and simply says nothing about DKIM.
        // Both stay neutral, but they are different facts and the reader can tell which
        // one they are looking at, so the detail must not claim the header is absent.
        List<com.mailsentinel.dto.CheckResult> checks =
                service.toCheckResults(service.parseAuthenticationResults("mx.example.com; spf=pass"));

        com.mailsentinel.dto.CheckResult dkim = checks.get(1);
        assertTrue(dkim.passed(), "an unreported mechanism still must not be scored as a failure");
        assertFalse(dkim.detail().contains("No Authentication-Results header"),
                "the header is present; saying otherwise contradicts the email on screen: " + dkim.detail());
        assertTrue(dkim.detail().contains("reports no DKIM result"), dkim.detail());
    }

    @Test
    void onlyAnExplicitNonPassCountsAgainstTheScore() {
        List<com.mailsentinel.dto.CheckResult> checks =
                service.toCheckResults(service.parseAuthenticationResults("mx; spf=pass; dkim=fail; dmarc=none"));

        assertTrue(checks.get(0).passed(), "spf=pass");
        assertFalse(checks.get(1).passed(), "dkim=fail");
        assertFalse(checks.get(2).passed(), "dmarc=none is not a pass");
    }

    @Test
    void eachCheckCarriesItsConfiguredWeight() {
        List<com.mailsentinel.dto.CheckResult> checks =
                service.toCheckResults(service.parseAuthenticationResults("mx; spf=fail; dkim=fail; dmarc=fail"));
        assertEquals(22, checks.get(0).weight());
        assertEquals(30, checks.get(1).weight());
        assertEquals(22, checks.get(2).weight());
        // All three failing sums to 74, past the 60 red threshold: the "weak signals
        // should stack rather than each spike the score" property this project is built on.
        assertTrue(checks.stream().mapToInt(com.mailsentinel.dto.CheckResult::weight).sum() >= 60);
    }
}
