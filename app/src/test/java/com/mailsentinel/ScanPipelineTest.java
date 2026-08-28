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

    @Test
    void testUnknownScanTypeIsRejectedRatherThanSilentlyTreatedAsUrl() {
        assertThrows(IllegalArgumentException.class, () -> scoringService.runScan("banana", "https://paypal.com"));
    }
}
