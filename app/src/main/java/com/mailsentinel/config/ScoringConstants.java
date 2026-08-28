package com.mailsentinel.config;

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
        // Authentication-Results header (claimed)
        Map.entry("spf_claimed", 22),
        Map.entry("dkim_claimed", 30),
        Map.entry("dmarc_claimed", 22),
        // Independent live DNS verification
        Map.entry("spf_live", 18),
        Map.entry("dmarc_live", 22),
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
