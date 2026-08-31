package com.mailsentinel.service;

import com.mailsentinel.config.ScoringConstants;
import com.mailsentinel.dto.*;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
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
        put("brand_subdomain", "brand-in-subdomain");
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

    private CheckResult displayNameImpersonationCheck(String displayName, String senderDomain) {
        LookalikeFinding finding = lookalikeDetector.checkDisplayNameImpersonation(displayName, senderDomain);
        String passedDetail = displayName == null || displayName.isBlank()
            ? "From header has no display name to compare against the sending domain"
            : "Display name \"" + displayName + "\" does not claim a brand other than " + senderDomain;
        return new CheckResult(
            "Sender display name impersonation",
            finding == null,
            ScoringConstants.getWeight("display_name_impersonation"),
            finding != null ? finding.detail() : passedDetail
        );
    }

    private CheckResult shortenerCheck(String hostname) {
        boolean flagged = UrlUtils.isShortener(hostname);
        return new CheckResult(
            "URL shortener",
            !flagged,
            ScoringConstants.getWeight("url_shortener"),
            flagged
                ? hostname + " is a known URL shortener, so the real destination is hidden until it is opened"
                : "Host is not a known URL shortener"
        );
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

        // 3b. Display name claiming a brand the sending domain doesn't back up
        checks.add(displayNameImpersonationCheck(parsed.senderDisplayName(), senderDomain));

        // 4. In-body link analysis
        List<ExtractedLink> links = linkAnalysisService.extractLinks(parsed.textBody(), parsed.htmlBody());
        checks.addAll(linkAnalysisService.analyzeLinks(links));

        return finalizeScore(checks);
    }

    /**
     * Split pasted input into individual links.
     *
     * People paste what they copied, which is rarely one tidy URL: it arrives one per
     * line, separated by spaces, or with stray blank lines between. Anything that
     * isn't whitespace counts as a candidate and gets scored on its own.
     */
    private List<String> splitUrls(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return Arrays.stream(raw.trim().split("\\s+"))
            .filter(s -> !s.isBlank())
            .limit(ScoringConstants.MAX_LINKS_PER_SCAN)
            .toList();
    }

    /**
     * One check per technique across every pasted link, rather than a full check list
     * per link: twenty links would otherwise produce a hundred rows to read. The
     * detail names exactly which links tripped it.
     */
    private CheckResult aggregate(String name, String weightKey, List<String> hits, String passedDetail) {
        return new CheckResult(
            name,
            hits.isEmpty(),
            ScoringConstants.getWeight(weightKey),
            hits.isEmpty() ? passedDetail : ScoringConstants.joinHits(hits)
        );
    }

    public ScanResponse scanUrl(String raw) {
        List<String> urls = splitUrls(raw);
        if (urls.isEmpty()) {
            urls = List.of(raw == null ? "" : raw.trim());
        }

        // Every hostname is resolved once and reused by all the checks below.
        Map<String, String> hostByUrl = new LinkedHashMap<>();
        for (String url : urls) {
            String hostname = UrlUtils.extractHostname(url);
            hostByUrl.put(url, hostname == null || hostname.isBlank() ? url : hostname);
        }

        Map<String, List<String>> lookalikeHits = new LinkedHashMap<>();
        List<String> ipHits = new ArrayList<>();
        List<String> shortenerHits = new ArrayList<>();

        for (Map.Entry<String, String> entry : hostByUrl.entrySet()) {
            String url = entry.getKey();
            String hostname = entry.getValue();

            for (LookalikeFinding finding : lookalikeDetector.analyzeDomain(hostname)) {
                lookalikeHits
                    .computeIfAbsent(finding.technique(), k -> new ArrayList<>())
                    .add(finding.detail());
            }
            if (UrlUtils.isIpLiteral(hostname)) {
                ipHits.add(url + " uses a raw IP address instead of a domain name");
            }
            if (UrlUtils.isShortener(hostname)) {
                shortenerHits.add(url + " is a shortener, so its real destination stays hidden until it is opened");
            }
        }

        String scope = urls.size() == 1 ? hostByUrl.values().iterator().next() : urls.size() + " links";

        List<CheckResult> checks = new ArrayList<>();
        for (Map.Entry<String, String> entry : LOOKALIKE_LABELS.entrySet()) {
            checks.add(aggregate(
                "URL domain " + entry.getValue(),
                entry.getKey(),
                lookalikeHits.getOrDefault(entry.getKey(), List.of()),
                "No " + entry.getValue() + " pattern detected for " + scope
            ));
        }
        checks.add(aggregate("Raw IP address as hostname", "ip_hostname", ipHits,
            "No link uses a raw IP address as the host"));
        checks.add(aggregate("URL shortener", "url_shortener", shortenerHits,
            "No link uses a known URL shortener"));

        return finalizeScore(checks);
    }

    private ScanResponse finalizeScore(List<CheckResult> checks) {
        int rawScore = checks.stream()
            .filter(c -> !c.passed())
            .mapToInt(CheckResult::weight)
            .sum();
        int score = Math.min(100, rawScore);
        return new ScanResponse(score, checks, null);
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
