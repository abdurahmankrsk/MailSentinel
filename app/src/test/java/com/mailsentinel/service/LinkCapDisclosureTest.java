package com.mailsentinel.service;

import com.mailsentinel.config.ScoringConstants;
import com.mailsentinel.dto.CheckResult;
import com.mailsentinel.dto.ExtractedLinks;
import com.mailsentinel.dto.ScanResponse;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Both scan paths cap how many links one request may examine, which is right -- an
 * uncapped scan is a cheap CPU sink on an endpoint that needs no account. What was
 * wrong was capping in silence: the checks went on to report "No link uses a raw IP
 * address as the host" about links discarded before anything looked at them.
 *
 * Measured against the running app before the fix: 55 harmless links followed by
 * {@code http://192.168.44.9/verify} scored <b>0</b> and read as clean, as did an
 * email whose 56th link was {@code paypa1.com}.
 */
class LinkCapDisclosureTest {

    private static final int CAP = ScoringConstants.MAX_LINKS_PER_SCAN;

    private final LinkAnalysisService linkAnalysisService = new LinkAnalysisService(new LookalikeDetector());
    private final ScoringService scoringService = new ScoringService(
            new AuthHeaderService(), new DnsCheckService(), new EmailParserService(),
            linkAnalysisService, new LookalikeDetector());

    private static String filler(int count, String suffix) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            sb.append("https://example").append(i).append(".com").append(suffix);
        }
        return sb.toString();
    }

    private CheckResult named(List<CheckResult> checks, String name) {
        return checks.stream().filter(c -> c.name().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError("no check named " + name));
    }

    @Test
    void aUrlScanThatDropsLinksSaysSoRatherThanReportingThemClean() {
        String pasted = filler(CAP + 5, " ") + "http://192.168.44.9/verify";

        ScanResponse response = scoringService.scanUrl(pasted);
        CheckResult ipCheck = named(response.checks(), "Raw IP address as hostname");

        // The raw-IP link is past the cap and genuinely was not examined -- the fix is
        // not to raise the cap, it is to stop claiming the opposite.
        assertTrue(ipCheck.detail().contains("not scanned"), ipCheck.detail());
        assertTrue(ipCheck.detail().contains("6 further links"), ipCheck.detail());
    }

    @Test
    void anEmailThatDropsLinksSaysSoOnEveryLinkCheck() {
        String html = filler(CAP + 5, "|").replace("|", "\">ok</a>").replace("https://", "<a href=\"https://")
                + "<a href=\"http://paypa1.com/login\">PayPal</a>";

        ExtractedLinks extracted = linkAnalysisService.extract(null, html);
        List<CheckResult> checks = linkAnalysisService.analyzeLinks(extracted.analyzed(), extracted.dropped());

        assertEquals(CAP, extracted.analyzed().size());
        assertEquals(6, extracted.dropped());
        for (CheckResult check : checks) {
            assertTrue(check.detail().contains("not scanned"),
                    check.name() + " reported a result without saying the list was cut short: " + check.detail());
        }
    }

    @Test
    void aScanWithinTheCapCarriesNoNotice() {
        ScanResponse response = scoringService.scanUrl("https://github.com https://stripe.com");

        for (CheckResult check : response.checks()) {
            assertFalse(check.detail().contains("not scanned"),
                    "nothing was dropped, so nothing should be claimed dropped: " + check.detail());
        }
    }

    @Test
    void exactlyTheCapIsNotTreatedAsTruncated() {
        ScanResponse response = scoringService.scanUrl(filler(CAP, " "));

        assertFalse(named(response.checks(), "URL shortener").detail().contains("not scanned"));
    }

    @Test
    void repeatsOfOneLinkDoNotCrowdOutTheLinkThatDiffers() {
        // The URL path used to cap the raw token list, so 60 copies of one link left no
        // room for the 61st. It now deduplicates first, matching the email path.
        StringBuilder pasted = new StringBuilder();
        for (int i = 0; i < CAP + 20; i++) {
            pasted.append("https://example.com ");
        }
        pasted.append("http://paypa1.com/login");

        ScanResponse response = scoringService.scanUrl(pasted.toString());

        assertTrue(response.score() >= 60, "the one distinct phishing link must still be examined");
        for (CheckResult check : response.checks()) {
            assertFalse(check.detail().contains("not scanned"),
                    "two distinct links is under the cap; repeats are not dropped links: " + check.detail());
        }
    }

    @Test
    void theNoticeIsSingularForOneDroppedLink() {
        String pasted = filler(CAP + 1, " ");

        ScanResponse response = scoringService.scanUrl(pasted);

        assertTrue(named(response.checks(), "URL shortener").detail().contains("1 further link was"),
                named(response.checks(), "URL shortener").detail());
    }

    @Test
    void aFailingCheckAlsoDisclosesTheTruncation() {
        // A finding does not make the omission irrelevant: what was found is still only
        // what was looked at.
        String pasted = "http://paypa1.com/login " + filler(CAP + 5, " ");

        ScanResponse response = scoringService.scanUrl(pasted);
        CheckResult editDistance = named(response.checks(), "URL domain edit-distance");

        assertFalse(editDistance.passed());
        assertTrue(editDistance.detail().contains("not scanned"), editDistance.detail());
    }
}
