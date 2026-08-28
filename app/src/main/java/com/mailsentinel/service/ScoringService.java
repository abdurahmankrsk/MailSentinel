package com.mailsentinel.service;

import com.mailsentinel.config.ScoringConstants;
import com.mailsentinel.dto.*;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Orchestrates every check into the final scan response.
 *
 * Fixed, predictable check list per input type with capped sum scoring.
 */
@Service
public class ScoringService {

    private static final Map<String, String> LOOKALIKE_LABELS = new LinkedHashMap<>() {{
        put("edit_distance", "edit-distance");
        put("char_substitution", "character substitution");
        put("homoglyph", "homoglyph / mixed-script");
        put("tld_swap", "TLD swap");
    }};

    private final AuthHeaderService authHeaderService;
    private final DnsCheckService dnsCheckService;
    private final EmailParserService emailParserService;
    private final LinkAnalysisService linkAnalysisService;
    private final LookalikeDetector lookalikeDetector;

    public ScoringService(
        AuthHeaderService authHeaderService,
        DnsCheckService dnsCheckService,
        EmailParserService emailParserService,
        LinkAnalysisService linkAnalysisService,
        LookalikeDetector lookalikeDetector
    ) {
        this.authHeaderService = authHeaderService;
        this.dnsCheckService = dnsCheckService;
        this.emailParserService = emailParserService;
        this.linkAnalysisService = linkAnalysisService;
        this.lookalikeDetector = lookalikeDetector;
    }

    private List<CheckResult> domainLookalikeChecks(String hostname, String labelPrefix) {
        List<LookalikeFinding> findingsList = lookalikeDetector.analyzeDomain(hostname);
        Map<String, LookalikeFinding> findings = new LinkedHashMap<>();
        for (LookalikeFinding f : findingsList) {
            findings.put(f.technique(), f);
        }

        List<CheckResult> checks = new ArrayList<>();
        for (Map.Entry<String, String> entry : LOOKALIKE_LABELS.entrySet()) {
            String technique = entry.getKey();
            String label = entry.getValue();
            LookalikeFinding finding = findings.get(technique);

            checks.add(new CheckResult(
                labelPrefix + " " + label,
                finding == null,
                ScoringConstants.getWeight(technique),
                finding != null
                    ? finding.detail()
                    : "No " + label + " pattern detected for " + hostname
            ));
        }
        return checks;
    }

    private CheckResult ipHostnameCheck(String name, String hostname) {
        boolean flagged = hostname != null && UrlUtils.isIpLiteral(hostname);
        return new CheckResult(
            name,
            !flagged,
            ScoringConstants.getWeight("ip_hostname"),
            flagged
                ? "Host " + hostname + " is a raw IP address, not a domain name"
                : "Host is a domain name, not a raw IP address"
        );
    }

    public ScanResponse scanEmail(String raw) {
        ParsedEmail parsed = emailParserService.parseEmail(raw);
        List<CheckResult> checks = new ArrayList<>();

        // 1. Claimed Authentication-Results header
        ClaimedAuthResults claimed = authHeaderService.parseAuthenticationResults(parsed.authenticationResults());
        checks.addAll(authHeaderService.toCheckResults(claimed));

        // 2. Live DNS SPF & DMARC checks + Agreement
        String senderDomain = parsed.senderDomain() != null ? parsed.senderDomain() : "unknown";
        LiveDnsResult live = dnsCheckService.verifySpfDmarc(senderDomain);
        checks.addAll(dnsCheckService.toCheckResults(senderDomain, live));
        checks.add(dnsCheckService.agreementCheck(claimed, live));

        // 3. Sender domain lookalike checks
        checks.addAll(domainLookalikeChecks(senderDomain, "Sender domain"));

        // 4. In-body link analysis
        List<ExtractedLink> links = linkAnalysisService.extractLinks(parsed.textBody(), parsed.htmlBody());
        checks.addAll(linkAnalysisService.analyzeLinks(links));

        return finalizeScore(checks);
    }

    public ScanResponse scanUrl(String raw) {
        String hostname = UrlUtils.extractHostname(raw);
        if (hostname == null || hostname.isBlank()) {
            hostname = raw != null ? raw.trim() : "";
        }

        List<CheckResult> checks = new ArrayList<>();
        checks.addAll(domainLookalikeChecks(hostname, "URL domain"));
        checks.add(ipHostnameCheck("Raw IP address as hostname", hostname));

        return finalizeScore(checks);
    }

    private ScanResponse finalizeScore(List<CheckResult> checks) {
        int rawScore = checks.stream()
            .filter(c -> !c.passed())
            .mapToInt(CheckResult::weight)
            .sum();
        int score = Math.min(100, rawScore);
        return new ScanResponse(score, checks);
    }

    public ScanResponse runScan(String type, String content) {
        if ("email".equals(type)) {
            return scanEmail(content);
        }
        if ("url".equals(type)) {
            return scanUrl(content);
        }
        throw new IllegalArgumentException("Unknown scan type: " + type);
    }
}
