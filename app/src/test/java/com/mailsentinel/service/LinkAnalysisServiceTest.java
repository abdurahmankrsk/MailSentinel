package com.mailsentinel.service;

import com.mailsentinel.config.ScoringConstants;
import com.mailsentinel.dto.CheckResult;
import com.mailsentinel.dto.ExtractedLink;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Link analysis in isolation. Previously this service was only exercised through the
 * whole-pipeline tests, which meant a gap in one technique could hide behind another
 * technique firing on the same fixture.
 */
class LinkAnalysisServiceTest {

    private final LinkAnalysisService service = new LinkAnalysisService(new LookalikeDetector());

    private CheckResult check(List<ExtractedLink> links, String name) {
        return service.analyzeLinks(links).stream()
                .filter(result -> result.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no check named " + name));
    }

    private CheckResult mismatchFor(String anchorText, String href) {
        return check(List.of(new ExtractedLink(href, anchorText)), "Anchor text / link destination mismatch");
    }

    @Test
    void anchorTextNamingAnotherDomainIsFlagged() {
        assertFalse(mismatchFor("paypal.com", "http://evil-domain.ru/login").passed());
    }

    @Test
    void aDomainNamedMidSentenceIsFlaggedJustTheSameAsOneAtTheStart() {
        // The regression this test exists for: the pattern used to be anchored at ^, so
        // only a domain at the very start of the anchor text counted. Every phrasing
        // below is a more natural lure than the bare domain, and every one of them
        // previously bypassed the check entirely.
        assertFalse(mismatchFor("confirm at paypal.com", "http://evil-domain.ru").passed());
        assertFalse(mismatchFor("Click here: paypal.com", "http://evil-domain.ru").passed());
        assertFalse(mismatchFor("Sign in to paypal.com now", "http://evil-domain.ru").passed());
        assertFalse(mismatchFor("go to www.paypal.com today", "http://evil-domain.ru").passed());
    }

    @Test
    void anchorTextMatchingItsOwnDestinationIsNotFlagged() {
        assertTrue(mismatchFor("paypal.com", "https://paypal.com/login").passed());
        assertTrue(mismatchFor("Sign in at paypal.com", "https://www.paypal.com/signin").passed(),
                "a subdomain of the same registrable domain is the ordinary case");
    }

    @Test
    void ordinaryProseIsNotMistakenForADomain() {
        // Anchor mismatch is a solo-red signal, so a false positive here is expensive.
        assertTrue(mismatchFor("Click here", "http://example.com").passed());
        assertTrue(mismatchFor("version 1.2.3", "http://example.com").passed());
        assertTrue(mismatchFor("call 555.123.4567", "http://example.com").passed());
        assertTrue(mismatchFor("3.14", "http://example.com").passed(),
                "a decimal number is not a domain");
        assertTrue(mismatchFor("See attached.Please review", "http://example.com").passed(),
                "a missing space after a full stop is not a domain");
    }

    @Test
    void linkWithNoAnchorTextCannotMismatch() {
        assertTrue(mismatchFor(null, "http://evil-domain.ru").passed());
    }

    @Test
    void rawIpHostIsFlagged() {
        assertFalse(check(List.of(new ExtractedLink("http://192.168.44.9/verify")), "Raw IP address as link host").passed());
        assertTrue(check(List.of(new ExtractedLink("http://example.com")), "Raw IP address as link host").passed());
    }

    @Test
    void knownShortenerIsFlagged() {
        assertFalse(check(List.of(new ExtractedLink("https://bit.ly/xyz")), "URL shortener present").passed());
        assertTrue(check(List.of(new ExtractedLink("https://example.com/xyz")), "URL shortener present").passed());
    }

    @Test
    void lookalikeLinkDomainIsFlagged() {
        assertFalse(check(List.of(new ExtractedLink("http://paypa1.com/login")), "Suspicious links in body").passed());
    }

    @Test
    void htmlLinksAreExtractedWithTheirAnchorTextAndDeduplicated() {
        List<ExtractedLink> links = service.extractLinks(null,
                "<a href='http://a.com'>first</a><a href='http://a.com'>again</a><a href='http://b.com'>second</a>");
        assertEquals(2, links.size(), "the same href twice is one link");
        assertEquals("first", links.get(0).anchorText(), "the first occurrence's text is kept");
    }

    @Test
    void nonHttpSchemesAreIgnored() {
        List<ExtractedLink> links = service.extractLinks(null,
                "<a href='mailto:a@b.com'>mail</a><a href='javascript:void(0)'>js</a><a href='http://ok.com'>ok</a>");
        assertEquals(1, links.size());
        assertEquals("http://ok.com", links.get(0).href());
    }

    @Test
    void plainTextLinksAreOnlyUsedWhenTheHtmlBodyHasNone() {
        assertEquals(1, service.extractLinks("visit http://text-only.com", null).size());
        // An HTML body that contains links wins outright; the text part of a multipart
        // message is usually the same content and would only produce duplicates.
        assertEquals("http://html.com",
                service.extractLinks("visit http://text-only.com", "<a href='http://html.com'>x</a>").get(0).href());
    }

    @Test
    void extractionIsCappedSoAHugeBodyCannotDriveUnboundedWork() {
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < 500; i++) {
            body.append("<a href='http://paypa1-").append(i).append(".com'>link</a>");
        }

        List<ExtractedLink> links = service.extractLinks(null, body.toString());

        // Each extracted link runs analyzeDomain across every watched brand and five
        // techniques; without the cap this endpoint is a cheap CPU sink for anyone.
        assertEquals(ScoringConstants.MAX_LINKS_PER_SCAN, links.size());
    }

    @Test
    void theCapAppliesAfterDeduplicationNotBefore() {
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            body.append("<a href='http://same.com'>link</a>");
        }
        body.append("<a href='http://different.com'>link</a>");

        List<ExtractedLink> links = service.extractLinks(null, body.toString());

        // 200 copies of one link must not crowd out the one link that differs.
        assertEquals(2, links.size());
        assertEquals("http://different.com", links.get(1).href());
    }

    @Test
    void anAggregatedDetailNamesTheFirstHitsAndCountsTheRest() {
        List<ExtractedLink> links = new java.util.ArrayList<>();
        for (int i = 0; i < 25; i++) {
            links.add(new ExtractedLink("http://" + i + ".paypa1.com"));
        }

        String detail = check(links, "Suspicious links in body").detail();

        // Bounded response, and honest about being bounded -- a silently truncated
        // list reads as a complete one. The exact count isn't asserted here because a
        // single link can trip more than one technique; ScoringConstantsTest pins the
        // arithmetic.
        assertEquals(ScoringConstants.MAX_HITS_PER_DETAIL, detail.split("; ").length - 1, detail);
        assertTrue(detail.matches(".*; \\.\\.\\.and \\d+ more$"), detail);
    }

    @Test
    void everyCheckIsAlwaysReportedEvenWhenNothingIsWrong() {
        // The project's stated contract: a clean scan shows why it is clean.
        assertEquals(4, service.analyzeLinks(List.of()).size());
        assertTrue(service.analyzeLinks(List.of()).stream().allMatch(CheckResult::passed));
    }
}
