package com.mailsentinel.service;

import com.mailsentinel.config.ScoringConstants;
import com.mailsentinel.dto.CheckResult;
import com.mailsentinel.dto.ExtractedLink;
import com.mailsentinel.dto.LookalikeFinding;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Check 4: extract every link from an email body and analyze it.
 *
 * Findings are aggregated into one CheckResult per technique.
 */
@Service
public class LinkAnalysisService {

    private static final Pattern URL_PATTERN = Pattern.compile(
        "https?://[^\\s<>\"')\\]]+",
        Pattern.CASE_INSENSITIVE
    );

    /**
     * Finds a domain named anywhere inside anchor text.
     *
     * The anchoring matters. This pattern used to be tied to {@code ^}, which meant the
     * domain had to be the very first thing in the text -- so "paypal.com" was caught
     * while "confirm at paypal.com", "Click here: paypal.com" and "Sign in to paypal.com
     * now" all sailed past, even though they are the more natural way to write the lure.
     * The mismatch check was effectively opt-in for the attacker.
     *
     * The trailing {@code [a-z]{2,}} also stops "3.14" and "555.123.4567" from being read
     * as domains, which the previous pattern did match.
     */
    private static final Pattern ANCHOR_DOMAIN_PATTERN = Pattern.compile(
        "\\b(?:https?://)?([\\w-]+(?:\\.[\\w-]+)*\\.[a-z]{2,})\\b",
        Pattern.CASE_INSENSITIVE
    );

    private final LookalikeDetector lookalikeDetector;

    public LinkAnalysisService(LookalikeDetector lookalikeDetector) {
        this.lookalikeDetector = lookalikeDetector;
    }

    private List<ExtractedLink> extractFromHtml(String htmlBody) {
        List<ExtractedLink> links = new ArrayList<>();
        if (htmlBody == null || htmlBody.isBlank()) {
            return links;
        }
        try {
            Document doc = Jsoup.parse(htmlBody);
            Elements anchors = doc.select("a[href]");
            for (Element anchor : anchors) {
                String href = anchor.attr("href").trim();
                String lower = href.toLowerCase(Locale.ROOT);
                if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
                    continue;
                }
                String text = anchor.text().trim();
                links.add(new ExtractedLink(href, text.isBlank() ? null : text));
            }
        } catch (Exception ignored) {
            // Suppress HTML parsing errors
        }
        return links;
    }

    private List<ExtractedLink> extractFromText(String textBody) {
        List<ExtractedLink> links = new ArrayList<>();
        if (textBody == null || textBody.isBlank()) {
            return links;
        }
        Matcher matcher = URL_PATTERN.matcher(textBody);
        while (matcher.find()) {
            links.add(new ExtractedLink(matcher.group(0)));
        }
        return links;
    }

    public List<ExtractedLink> extractLinks(String textBody, String htmlBody) {
        List<ExtractedLink> sourceLinks = htmlBody != null ? extractFromHtml(htmlBody) : List.of();
        if (sourceLinks.isEmpty() && textBody != null) {
            sourceLinks = extractFromText(textBody);
        }

        List<ExtractedLink> uniqueLinks = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (ExtractedLink link : sourceLinks) {
            if (seen.add(link.href())) {
                uniqueLinks.add(link);
            }
        }
        return uniqueLinks;
    }

    /**
     * The registrable domain the anchor text claims, or null if it names none.
     *
     * Returning the matched token rather than a boolean is what lets the caller compare
     * the right thing: the text around the domain ("confirm at ...") is prose, and
     * feeding the whole string to a hostname parser yields a host that is neither the
     * claimed domain nor anything else meaningful.
     *
     * The public-suffix test is the guard against false positives. Anchor mismatch is a
     * solo-red signal, so "See attached.Please review" must not be read as naming a
     * domain simply because it contains a dot with no space after it.
     */
    private String domainClaimedByAnchorText(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        Matcher matcher = ANCHOR_DOMAIN_PATTERN.matcher(text.trim());
        while (matcher.find()) {
            String candidate = matcher.group(1);
            if (UrlUtils.isRegistrableDomain(candidate)) {
                return UrlUtils.registrableDomain(candidate);
            }
        }
        return null;
    }

    public List<CheckResult> analyzeLinks(List<ExtractedLink> links) {
        List<String> lookalikeHits = new ArrayList<>();
        List<String> shortenerHits = new ArrayList<>();
        List<String> mismatchHits = new ArrayList<>();
        List<String> ipHostHits = new ArrayList<>();

        for (ExtractedLink link : links) {
            String hostname = UrlUtils.extractHostname(link.href());
            if (hostname == null || hostname.isBlank()) {
                continue;
            }
            String domain = UrlUtils.registrableDomain(hostname);

            // 1. Lookalike check
            for (LookalikeFinding finding : lookalikeDetector.analyzeDomain(hostname)) {
                lookalikeHits.add(link.href() + " (" + finding.detail() + ")");
            }

            // 2. Shortener check
            if (UrlUtils.isShortener(hostname)) {
                shortenerHits.add(link.href());
            }

            // 3. Raw IP check
            if (UrlUtils.isIpLiteral(hostname)) {
                ipHostHits.add(link.href());
            }

            // 4. Anchor text mismatch check
            String claimedDomain = domainClaimedByAnchorText(link.anchorText());
            if (claimedDomain != null && !claimedDomain.equalsIgnoreCase(domain)) {
                mismatchHits.add("anchor text '" + link.anchorText() + "' actually points to " + link.href());
            }
        }

        return List.of(
            new CheckResult(
                "Suspicious links in body",
                lookalikeHits.isEmpty(),
                ScoringConstants.getWeight("link_lookalike"),
                lookalikeHits.isEmpty()
                    ? "No lookalike brand domains found among links in the body"
                    : String.join("; ", lookalikeHits)
            ),
            new CheckResult(
                "URL shortener present",
                shortenerHits.isEmpty(),
                ScoringConstants.getWeight("url_shortener"),
                shortenerHits.isEmpty()
                    ? "No known URL-shortener domains found in links"
                    : "Shortened link(s) found: " + String.join(", ", shortenerHits)
            ),
            new CheckResult(
                "Anchor text / link destination mismatch",
                mismatchHits.isEmpty(),
                ScoringConstants.getWeight("anchor_mismatch"),
                mismatchHits.isEmpty()
                    ? "No anchor text found that names a different domain than its link target"
                    : String.join("; ", mismatchHits)
            ),
            new CheckResult(
                "Raw IP address as link host",
                ipHostHits.isEmpty(),
                ScoringConstants.getWeight("ip_hostname"),
                ipHostHits.isEmpty()
                    ? "No links use a raw IP address as the host"
                    : "Link(s) use a raw IP address instead of a domain: " + String.join(", ", ipHostHits)
            )
        );
    }
}
