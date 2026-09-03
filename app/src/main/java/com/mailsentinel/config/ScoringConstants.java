package com.mailsentinel.config;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Scoring configuration: URL shorteners and per-check weights.
 *
 * Weights are hand-tuned into three tiers:
 *   - "solo-red" (55-70): pushes score past the red threshold (60) alone
 *   - "strong" (45-55): serious on their own, slightly more ambiguous
 *   - "medium"/"weak" (10-30): individually inconclusive, but stack into red
 */
public final class ScoringConstants {
    private ScoringConstants() {}

    /**
     * Sanity bound on a single scan, so a huge paste can't turn into unbounded work.
     *
     * Shared by both scan paths on purpose. The URL path capped its split input from
     * the start; the email path did not, and each extracted anchor runs analyzeDomain
     * across every watched brand and five techniques, Levenshtein among them. A 1.4 MB
     * email carrying 20,000 unique lookalike anchors held a request thread for over a
     * second of near-pure CPU on an endpoint that needs no account and allows any
     * origin -- enough concurrent requests to saturate the pool cost the sender
     * nothing.
     */
    public static final int MAX_LINKS_PER_SCAN = 50;

    /**
     * How many individual hits a single aggregated check detail spells out before it
     * summarizes the rest. Reading past a handful tells nobody anything new, and the
     * cap keeps the response bounded even where the input isn't.
     */
    public static final int MAX_HITS_PER_DETAIL = 10;

    /**
     * Joins the hits behind one aggregated check into its detail string, naming the
     * first {@link #MAX_HITS_PER_DETAIL} and counting the remainder. Without the
     * count the reader has no way to tell a truncated list from a complete one.
     */
    public static String joinHits(List<String> hits) {
        if (hits.size() <= MAX_HITS_PER_DETAIL) {
            return String.join("; ", hits);
        }
        int hidden = hits.size() - MAX_HITS_PER_DETAIL;
        return String.join("; ", hits.subList(0, MAX_HITS_PER_DETAIL))
                + "; ...and " + hidden + " more";
    }

    public static final Set<String> SHORTENER_DOMAINS = Set.of(
        "bit.ly",
        "tinyurl.com",
        "t.co",
        "goo.gl",
        "ow.ly",
        "is.gd",
        "buff.ly"
    );

    public static final Map<String, Integer> WEIGHTS = Map.ofEntries(
        // Lookalike domain techniques
        Map.entry("edit_distance", 65),
        Map.entry("char_substitution", 58),
        Map.entry("homoglyph", 70),
        Map.entry("tld_swap", 45),
        // Brand name in a subdomain of an unrelated registrable domain. Solo-red, and
        // pitched at anchor_mismatch's weight because it is the same deception: the
        // thing a human reads names one destination while the real one is elsewhere.
        // Unlike a display name, a hostname has no legitimate reason to do this.
        Map.entry("brand_subdomain", 62),
        // Brand name inside the registrable label itself (paypal-secure.com). "Strong"
        // rather than solo-red on purpose: legitimate affiliates and campaign sites do
        // occasionally register brand-adjacent domains, so this should stack with a
        // second signal rather than convict alone. It is the technique that closes the
        // gap the four near-exact ones structurally could not reach.
        Map.entry("brand_in_domain", 45),
        // Display name naming a brand the sending domain doesn't back up. Deliberately
        // "strong" rather than "solo-red": a display name can legitimately mention a
        // brand it isn't (a partner newsletter, a training provider), so this lands at
        // medium on its own and only reaches red alongside a second signal.
        Map.entry("display_name_impersonation", 45),
        // Authentication-Results header (claimed)
        Map.entry("spf_claimed", 22),
        Map.entry("dkim_claimed", 30),
        Map.entry("dmarc_claimed", 22),
        // Independent live DNS verification
        Map.entry("spf_live", 9),
        Map.entry("dmarc_live", 11),
        Map.entry("claimed_vs_live_disagreement", 20),
        // Link analysis
        Map.entry("link_lookalike", 55),
        Map.entry("url_shortener", 10),
        Map.entry("anchor_mismatch", 62),
        Map.entry("ip_hostname", 65)
    );

    public static int getWeight(String key) {
        return WEIGHTS.getOrDefault(key, 0);
    }
}
