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
