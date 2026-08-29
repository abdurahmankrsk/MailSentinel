package com.mailsentinel;

import com.mailsentinel.dto.CheckResult;
import com.mailsentinel.dto.ScanResponse;
import com.mailsentinel.service.LookalikeDetector;
import com.mailsentinel.service.ScoringService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class ScanPipelineTest {

    @Autowired
    private ScoringService scoringService;

    @Autowired
    private LookalikeDetector lookalikeDetector;

    private String readSample(String filename) throws IOException {
        Path path = Path.of("../test_samples", filename);
        if (!Files.exists(path)) {
            path = Path.of("test_samples", filename);
        }
        return Files.readString(path);
    }

    @Test
    void testUrlLookalikeEditDistance() {
        ScanResponse response = scoringService.scanUrl("https://paypa1.com/login");
        assertTrue(response.score() >= 60, "Expected high risk score for lookalike edit distance");
        assertTrue(response.checks().stream().anyMatch(c -> c.name().contains("edit-distance") && !c.passed()));
    }

    @Test
    void testUrlCharSubstitution() {
        ScanResponse response = scoringService.scanUrl("http://g00gle.com");
        assertTrue(response.score() > 0);
        assertTrue(response.checks().stream().anyMatch(c -> c.name().contains("character substitution") && !c.passed()));
    }

    @Test
    void testUrlHomoglyph() {
        // Cyrillic 'а' in paypal
        ScanResponse response = scoringService.scanUrl("http://pаypal.com");
        assertTrue(response.score() >= 60);
        assertTrue(response.checks().stream().anyMatch(c -> c.name().contains("homoglyph") && !c.passed()));
    }

    @Test
    void testUrlTldSwap() {
        ScanResponse response = scoringService.scanUrl("http://paypal.co");
        assertTrue(response.score() >= 40);
        assertTrue(response.checks().stream().anyMatch(c -> c.name().contains("TLD swap") && !c.passed()));
    }

    /**
     * A brand's own country-specific domains are not typosquats of its .com.
     *
     * Treating amazon.com as the only real Amazon domain made the TLD-swap check fire on
     * every regional domain the brand actually sends from -- see the email case below for
     * why that mattered so much more than one 45-point signal suggests.
     */
    @Test
    void testRegionalBrandDomainsAreNotTldSwaps() {
        List<String> legitimate = List.of(
            "https://www.amazon.co.uk/deals",
            "https://www.amazon.de",
            "https://www.google.co.uk",
            "https://www.apple.co.uk",
            "https://www.paypal.co.uk",
            "https://www.ebay.de"
        );
        for (String url : legitimate) {
            ScanResponse response = scoringService.scanUrl(url);
            assertEquals(0, response.score(), "Expected a clean score for legitimate " + url
                + ", got failing checks: " + response.checks().stream()
                    .filter(c -> !c.passed()).map(CheckResult::name).toList());
        }
    }

    /**
     * The regression this whole change exists for: a genuine, fully-authenticated
     * amazon.co.uk email used to score 100/100 "treat this as hostile" because the TLD
     * swap (45), display-name (45) and in-body link (55) checks all fired on the single
     * fact that .co.uk is not .com, and a capped sum turned that into a maximum verdict.
     */
    @Test
    void testLegitimateRegionalBrandEmailScoresClean() {
        String eml = """
            From: Amazon.co.uk <no-reply@amazon.co.uk>
            To: customer@example.com
            Subject: Your Amazon.co.uk order has dispatched
            Authentication-Results: mx.example.com; spf=pass smtp.mailfrom=amazon.co.uk; dkim=pass; dmarc=pass
            Content-Type: text/html

            <html><body><p>Your order has dispatched.</p>
            <a href="https://www.amazon.co.uk/orders">Track your parcel</a></body></html>
            """;
        ScanResponse response = scoringService.scanEmail(eml);

        assertTrue(response.score() < 30,
            "Legitimate regional brand email should be low risk, got " + response.score()
                + " from " + response.checks().stream()
                    .filter(c -> !c.passed()).map(CheckResult::name).toList());
        assertTrue(response.checks().stream()
            .noneMatch(c -> c.name().contains("TLD swap") && !c.passed()));
        assertTrue(response.checks().stream()
            .noneMatch(c -> c.name().contains("display name") && !c.passed()));
        assertTrue(response.checks().stream()
            .noneMatch(c -> c.name().contains("Suspicious links") && !c.passed()));
    }

    /** The fix must not blunt the signal it was narrowing: a real TLD swap still fires. */
    @Test
    void testUnownedTldVariantIsStillFlagged() {
        for (String url : List.of("http://paypal.co", "http://amazon.co.jp.ru", "http://google.tk")) {
            ScanResponse response = scoringService.scanUrl(url);
            assertTrue(response.score() > 0, "Expected " + url + " to still be flagged");
        }
    }

    @Test
    void testUrlRawIpAddress() {
        ScanResponse response = scoringService.scanUrl("http://192.168.1.1/login");
        assertTrue(response.score() >= 60);
        assertTrue(response.checks().stream().anyMatch(c -> c.name().contains("Raw IP address") && !c.passed()));
    }

    @Test
    void testLegitimateCleanSample() throws IOException {
        String eml = readSample("legitimate_clean.eml");
        ScanResponse response = scoringService.scanEmail(eml);
        assertNotNull(response);
        // Legitimate email from google.com with matching auth headers
        assertTrue(response.score() < 30, "Clean email score should be low, got: " + response.score());
    }

    @Test
    void testPhishingHomoglyphOnlySample() throws IOException {
        String eml = readSample("phishing_homoglyph_only.eml");
        ScanResponse response = scoringService.scanEmail(eml);
        assertNotNull(response);
        assertTrue(response.checks().stream().anyMatch(c -> c.name().contains("homoglyph") && !c.passed()),
            "Should flag homoglyph sender domain");
    }

    @Test
    void testPhishingMultiSignalSample() throws IOException {
        String eml = readSample("phishing_multi_signal.eml");
        ScanResponse response = scoringService.scanEmail(eml);
        assertNotNull(response);
        assertTrue(response.score() >= 60, "Multi-signal phishing email should cross red threshold (>= 60)");
    }

    @Test
    void testBorderlineNewsletterSample() throws IOException {
        String eml = readSample("borderline_newsletter.eml");
        ScanResponse response = scoringService.scanEmail(eml);
        assertNotNull(response);
        // Should produce structured check results without errors
        assertFalse(response.checks().isEmpty());
    }

    private ScanResponse scanFrom(String fromHeader, String extraHeaders) {
        return scoringService.scanEmail(
            "From: " + fromHeader + "\r\n"
                + "To: user@example.com\r\n"
                + "Subject: test\r\n"
                + extraHeaders
                + "\r\n"
                + "Body text.");
    }

    private boolean impersonationFlagged(ScanResponse response) {
        return response.checks().stream()
            .anyMatch(c -> c.name().contains("display name impersonation") && !c.passed());
    }

    @Test
    void displayNameNamingAnUnrelatedBrandIsFlagged() {
        // The domain resembles no brand at all, so every distance-based technique
        // scores it zero -- the display name is the only thing carrying the lie.
        assertTrue(impersonationFlagged(scanFrom("Microsoft 365 <no-reply@m365-account-security.com>", "")));
    }

    @Test
    void displayNameMatchingItsOwnSendingDomainIsNotFlagged() {
        assertFalse(impersonationFlagged(scanFrom("GitHub <notifications@github.com>", "")));
    }

    @Test
    void brandNameEmbeddedInAnUnrelatedWordIsNotFlagged() {
        // "Pineapple" contains "apple" and "Groups" contains "ups"; neither is a
        // brand claim, and matching them would fire on ordinary mail.
        assertFalse(impersonationFlagged(scanFrom("Pineapple Groups <hello@pineapple-groups.example>", "")));
    }

    @Test
    void multiWordBrandNameIsStillMatchedAcrossSpaces() {
        assertTrue(impersonationFlagged(scanFrom("Bank of America Alerts <alerts@secure-notice.example>", "")));
    }

    @Test
    void brandParkedInASubdomainIsFlaggedEvenThoughTheRegistrableDomainIsClean() {
        // verify-account.ru resembles no brand, so every distance-based technique
        // scores this zero; the lie is that a human reads "paypal.com" first.
        ScanResponse response = scoringService.scanUrl("https://paypal.com.verify-account.ru/secure");
        assertTrue(response.score() >= 60, "Expected high risk, got: " + response.score());
        assertTrue(response.checks().stream()
            .anyMatch(c -> c.name().contains("brand-in-subdomain") && !c.passed()));
    }

    @Test
    void brandOnItsOwnSubdomainIsNotFlagged() {
        ScanResponse response = scoringService.scanUrl("https://mail.google.com/inbox");
        assertTrue(response.checks().stream()
            .allMatch(c -> !c.name().contains("brand-in-subdomain") || c.passed()));
    }

    @Test
    void brandNameInsideALongerLabelIsNotFlagged() {
        ScanResponse response = scoringService.scanUrl("https://mypaypalinvoices.example.com/x");
        assertTrue(response.checks().stream()
            .allMatch(c -> !c.name().contains("brand-in-subdomain") || c.passed()));
    }

    @Test
    void shortenerIsScoredTheSameWhetherPastedAloneOrFoundInAnEmail() {
        ScanResponse pasted = scoringService.scanUrl("https://bit.ly/3xamplE");
        assertTrue(pasted.checks().stream()
            .anyMatch(c -> c.name().contains("shortener") && !c.passed()),
            "A shortener pasted on its own must flag, not just one found inside an email");
    }

    @Test
    void severalPastedLinksAreAllScoredNotJustTheFirst() {
        // The clean link comes first, so anything that only reads one URL passes this.
        ScanResponse response = scoringService.scanUrl(
            "https://github.com/octocat/repo\nhttp://paypa1.com/login\nhttps://bit.ly/3xamplE");

        assertTrue(response.score() >= 60, "Expected high risk, got: " + response.score());
        assertTrue(response.checks().stream()
            .anyMatch(c -> c.name().contains("edit-distance") && !c.passed()));
        assertTrue(response.checks().stream()
            .anyMatch(c -> c.name().contains("shortener") && !c.passed()));
    }

    @Test
    void severalCleanLinksStayClean() {
        ScanResponse response = scoringService.scanUrl(
            "https://github.com/octocat/repo https://stripe.com/receipts/abc");
        assertEquals(0, response.score());
    }

    @Test
    void multipleLinksProduceOneRowPerTechniqueNotOnePerLink() {
        ScanResponse one = scoringService.scanUrl("https://github.com/octocat/repo");
        ScanResponse many = scoringService.scanUrl(
            "https://github.com/a https://stripe.com/b https://adobe.com/c");
        assertEquals(one.checks().size(), many.checks().size(),
            "Adding links must not multiply the number of checks the reader has to scan");
    }

    @Test
    void testUnknownScanTypeIsRejectedRatherThanSilentlyTreatedAsUrl() {
        assertThrows(IllegalArgumentException.class, () -> scoringService.runScan("banana", "https://paypal.com"));
    }
}
