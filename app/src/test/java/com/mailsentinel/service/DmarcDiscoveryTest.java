package com.mailsentinel.service;

import com.mailsentinel.dto.CheckResult;
import com.mailsentinel.dto.ClaimedAuthResults;
import com.mailsentinel.dto.LiveDnsResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DMARC policy discovery, RFC 7489 section 6.6.3: a subdomain with no record of its own
 * is governed by its organisational domain's.
 *
 * Only the exact From domain used to be queried. Measured with live DNS before the fix,
 * a fully authenticated message from PayPal's real transactional sender,
 * service@intl.paypal.com, scored 31 -- "No DMARC record found at
 * _dmarc.intl.paypal.com", plus the agreement check calling its dmarc=pass a
 * contradiction -- although paypal.com publishes p=reject. notify.wellsfargo.com too.
 *
 * DNS here is answered from a map rather than the network, and every queried name is
 * recorded so the tests can say which lookups happened as well as what they returned.
 */
class DmarcDiscoveryTest {

    private static final ClaimedAuthResults ALL_PASS = new ClaimedAuthResults(true, "pass", "pass", "pass");

    private final List<String> queried = new ArrayList<>();

    /** A missing name answers as a resolved "no record"; a name in {@code failing} does not answer. */
    private DnsCheckService dns(Map<String, List<String>> txt, Set<String> failing) {
        return new DnsCheckService() {
            @Override
            public TxtLookup getTxtRecords(String name) {
                queried.add(name);
                return failing.contains(name)
                    ? new TxtLookup(List.of(), false)
                    : new TxtLookup(txt.getOrDefault(name, List.of()), true);
            }
        };
    }

    private DnsCheckService dns(Map<String, List<String>> txt) {
        return dns(txt, Set.of());
    }

    private static CheckResult dmarcCheck(DnsCheckService service, String domain, LiveDnsResult live) {
        return service.toCheckResults(domain, live).stream()
            .filter(c -> c.name().startsWith("DMARC record"))
            .findFirst()
            .orElseThrow();
    }

    @Test
    void aSubdomainWithoutItsOwnRecordIsCoveredByItsOrganisationalDomain() {
        DnsCheckService service = dns(Map.of("_dmarc.paypal.com", List.of("v=DMARC1; p=reject")));

        LiveDnsResult live = service.verifySpfDmarc("intl.paypal.com");

        assertTrue(live.dmarcResolved());
        assertTrue(live.dmarcPresent(), "paypal.com's p=reject covers intl.paypal.com");
        assertEquals("reject", live.dmarcPolicy());
        assertEquals("paypal.com", live.dmarcRecordDomain());
    }

    @Test
    void theOrganisationalDomainsSubdomainPolicyIsTheOneThatApplies() {
        DnsCheckService service = dns(Map.of("_dmarc.brand.com", List.of("v=DMARC1; p=reject; sp=none")));

        LiveDnsResult live = service.verifySpfDmarc("mail.brand.com");

        assertEquals("none", live.dmarcPolicy(), "sp= governs subdomains; p= is only the fallback");
    }

    @Test
    void aSubdomainsOwnRecordTakesPrecedence() {
        DnsCheckService service = dns(Map.of(
            "_dmarc.mail.brand.com", List.of("v=DMARC1; p=quarantine"),
            "_dmarc.brand.com", List.of("v=DMARC1; p=reject")));

        LiveDnsResult live = service.verifySpfDmarc("mail.brand.com");

        assertEquals("quarantine", live.dmarcPolicy());
        assertEquals("mail.brand.com", live.dmarcRecordDomain());
        assertFalse(queried.contains("_dmarc.brand.com"), "no need to look further once the subdomain answers");
    }

    @Test
    void theOrganisationalDomainIsFoundUnderAMultiPartSuffix() {
        DnsCheckService service = dns(Map.of("_dmarc.brand.co.uk", List.of("v=DMARC1; p=reject")));

        LiveDnsResult live = service.verifySpfDmarc("alerts.brand.co.uk");

        assertEquals("brand.co.uk", live.dmarcRecordDomain());
    }

    @Test
    void noRecordAnywhereIsStillAnAbsence() {
        DnsCheckService service = dns(Map.of());

        LiveDnsResult live = service.verifySpfDmarc("mail.nodmarc.com");

        assertTrue(live.dmarcResolved());
        assertFalse(live.dmarcPresent());
        assertNull(live.dmarcRecordDomain());
    }

    @Test
    void anOrganisationalDomainIsNotQueriedTwice() {
        DnsCheckService service = dns(Map.of());

        service.verifySpfDmarc("nodmarc.com");

        assertEquals(1, queried.stream().filter(n -> n.startsWith("_dmarc.")).count());
    }

    @Test
    void anUnansweredSubdomainLookupIsUnresolvedRatherThanAFallBack() {
        // The subdomain may have a record that simply did not arrive; answering from the
        // parent instead would report a policy that may not be the one that applies.
        DnsCheckService service = dns(
            Map.of("_dmarc.paypal.com", List.of("v=DMARC1; p=reject")),
            Set.of("_dmarc.intl.paypal.com"));

        LiveDnsResult live = service.verifySpfDmarc("intl.paypal.com");

        assertFalse(live.dmarcResolved());
        assertFalse(queried.contains("_dmarc.paypal.com"));
    }

    @Test
    void anUnansweredOrganisationalLookupIsUnresolvedRatherThanAnAbsence() {
        DnsCheckService service = dns(Map.of(), Set.of("_dmarc.paypal.com"));

        LiveDnsResult live = service.verifySpfDmarc("intl.paypal.com");

        assertFalse(live.dmarcResolved(), "a timeout is not evidence that no record exists");
    }

    @Test
    void theDetailSaysWhereAnInheritedPolicyCameFrom() {
        DnsCheckService service = dns(Map.of("_dmarc.paypal.com", List.of("v=DMARC1; p=reject")));
        LiveDnsResult live = service.verifySpfDmarc("intl.paypal.com");

        CheckResult check = dmarcCheck(service, "intl.paypal.com", live);

        assertTrue(check.passed());
        // It must not claim intl.paypal.com publishes a record: the reader can look, and
        // would find nothing there.
        assertTrue(check.detail().contains("covered by paypal.com"), check.detail());
    }

    @Test
    void anAbsenceNamesBothPlacesThatWereChecked() {
        DnsCheckService service = dns(Map.of());
        LiveDnsResult live = service.verifySpfDmarc("mail.nodmarc.com");

        CheckResult check = dmarcCheck(service, "mail.nodmarc.com", live);

        assertFalse(check.passed());
        assertTrue(check.detail().contains("_dmarc.mail.nodmarc.com"), check.detail());
        assertTrue(check.detail().contains("_dmarc.nodmarc.com"), check.detail());
    }

    @Test
    void aGenuineDmarcPassFromACoveredSubdomainIsNotADisagreement() {
        // The real intl.paypal.com publishes SPF (its live score was 31, not 40 with the
        // missing-SPF signal added), so the fixture does too -- this isolates the DMARC clause.
        DnsCheckService service = dns(Map.of(
            "intl.paypal.com", List.of("v=spf1 include:pp._spf.paypal.com -all"),
            "_dmarc.paypal.com", List.of("v=DMARC1; p=reject")));
        LiveDnsResult live = service.verifySpfDmarc("intl.paypal.com");

        assertTrue(service.agreementCheck(ALL_PASS, live).passed(),
            "the reproduction: PayPal's real sender was accused of contradicting DNS");
    }
}
