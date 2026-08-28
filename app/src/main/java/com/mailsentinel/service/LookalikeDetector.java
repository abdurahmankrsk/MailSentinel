package com.mailsentinel.service;

import com.mailsentinel.config.BrandConstants;
import com.mailsentinel.dto.LookalikeFinding;
import org.apache.commons.text.similarity.LevenshteinDistance;
import org.springframework.stereotype.Service;

import java.net.IDN;
import java.util.*;

/**
 * Lookalike and typosquat detection against the BrandConstants target list.
 *
 * Four independent techniques:
 *   - edit distance:      paypa1.com is 1 edit from paypal.com
 *   - char substitution:  digits/letter-runs standing in for similar letters (0->o, 1->l, rn->m)
 *   - homoglyphs:         non-Latin letters visually identical to Latin (Cyrillic а/е/о/р/с/etc.), Punycode (xn--)
 *   - TLD swap:           exact brand name with wrong top-level domain
 */
@Service
public class LookalikeDetector {

    private static final int MAX_EDIT_DISTANCE = 2;
    private static final LevenshteinDistance LEVENSHTEIN = LevenshteinDistance.getDefaultInstance();

    private static final List<Map.Entry<String, String>> SUBSTITUTIONS = List.of(
        Map.entry("rn", "m"),
        Map.entry("0", "o"),
        Map.entry("1", "l")
    );

    private static final Map<Character, Character> HOMOGLYPHS = Map.ofEntries(
        Map.entry('а', 'a'), // Cyrillic small letter a
        Map.entry('е', 'e'), // Cyrillic small letter ie
        Map.entry('о', 'o'), // Cyrillic small letter o
        Map.entry('р', 'p'), // Cyrillic small letter er
        Map.entry('с', 'c'), // Cyrillic small letter es
        Map.entry('х', 'x'), // Cyrillic small letter ha
        Map.entry('у', 'y'), // Cyrillic small letter u
        Map.entry('і', 'i'), // Cyrillic small letter byelorussian-ukrainian i
        Map.entry('ѕ', 's'), // Cyrillic small letter dze
        Map.entry('ј', 'j')  // Cyrillic small letter je
    );

    private record BrandDistance(String brand, int distance) {}

    public static String decodePunycode(String hostname) {
        try {
            return IDN.toUnicode(hostname);
        } catch (Exception e) {
            return hostname;
        }
    }

    private BrandDistance closestBrandByDistance(String candidate) {
        BrandDistance best = null;
        for (String brand : BrandConstants.BRAND_DOMAINS) {
            if (candidate.equalsIgnoreCase(brand)) {
                continue;
            }
            int dist = LEVENSHTEIN.apply(candidate.toLowerCase(Locale.ROOT), brand);
            if (best == null || dist < best.distance()) {
                best = new BrandDistance(brand, dist);
            }
        }
        return best;
    }

    public LookalikeFinding checkEditDistance(String domain) {
        String lower = domain.toLowerCase(Locale.ROOT);
        if (BrandConstants.BRAND_SET.contains(lower)) {
            return null;
        }
        BrandDistance best = closestBrandByDistance(lower);
        if (best != null && best.distance() <= MAX_EDIT_DISTANCE) {
            String plural = best.distance() != 1 ? "s" : "";
            return new LookalikeFinding(
                "edit_distance",
                best.brand(),
                "Domain " + domain + " is " + best.distance() + " character" + plural + " from " + best.brand()
            );
        }
        return null;
    }

    public LookalikeFinding checkCharSubstitution(String domain) {
        String lower = domain.toLowerCase(Locale.ROOT);
        if (BrandConstants.BRAND_SET.contains(lower)) {
            return null;
        }

        String normalized = lower;
        for (var entry : SUBSTITUTIONS) {
            normalized = normalized.replace(entry.getKey(), entry.getValue());
        }

        if (normalized.equals(lower)) {
            return null;
        }

        if (BrandConstants.BRAND_SET.contains(normalized)) {
            return new LookalikeFinding(
                "char_substitution",
                normalized,
                "Domain " + domain + " normalizes to " + normalized +
                " via character substitution (0/o, 1/l, rn/m), an exact match for a known brand"
            );
        }

        BrandDistance best = closestBrandByDistance(normalized);
        if (best != null && best.distance() <= MAX_EDIT_DISTANCE) {
            return new LookalikeFinding(
                "char_substitution",
                best.brand(),
                "Domain " + domain + " normalizes to '" + normalized +
                "' via character substitution (0/o, 1/l, rn/m), closely matching " + best.brand()
            );
        }
        return null;
    }

    private String scriptOf(int codePoint) {
        if (!Character.isLetter(codePoint)) {
            return null;
        }
        try {
            Character.UnicodeScript script = Character.UnicodeScript.of(codePoint);
            String name = script.name();
            if (name.contains("LATIN")) return "LATIN";
            if (name.contains("CYRILLIC")) return "CYRILLIC";
            if (name.contains("GREEK")) return "GREEK";
            return "OTHER";
        } catch (Exception e) {
            return "OTHER";
        }
    }

    public LookalikeFinding checkHomoglyph(String domain) {
        String decoded = decodePunycode(domain).toLowerCase(Locale.ROOT);
        if (BrandConstants.BRAND_SET.contains(decoded)) {
            return null;
        }

        Set<String> scripts = new TreeSet<>();
        for (int i = 0; i < decoded.length(); i++) {
            int cp = decoded.codePointAt(i);
            String s = scriptOf(cp);
            if (s != null) {
                scripts.add(s);
            }
        }
        boolean mixedScript = scripts.size() > 1;

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < decoded.length(); i++) {
            char c = decoded.charAt(i);
            sb.append(HOMOGLYPHS.getOrDefault(c, c));
        }
        String transliterated = sb.toString();

        String homoglyphMatch = null;
        if (!transliterated.equals(decoded)) {
            if (BrandConstants.BRAND_SET.contains(transliterated)) {
                homoglyphMatch = transliterated;
            } else {
                BrandDistance best = closestBrandByDistance(transliterated);
                if (best != null && best.distance() <= MAX_EDIT_DISTANCE) {
                    homoglyphMatch = best.brand();
                }
            }
        }

        if (homoglyphMatch != null) {
            return new LookalikeFinding(
                "homoglyph",
                homoglyphMatch,
                "Domain " + domain + " uses non-Latin homoglyph characters that visually imitate " + homoglyphMatch
            );
        }

        if (mixedScript) {
            return new LookalikeFinding(
                "homoglyph",
                "-",
                "Domain " + domain + " mixes Unicode scripts (" + String.join(", ", scripts) +
                ") within a single hostname, a common homoglyph-spoofing pattern"
            );
        }

        return null;
    }

    public LookalikeFinding checkTldSwap(String domain) {
        String lower = domain.toLowerCase(Locale.ROOT);
        if (BrandConstants.BRAND_SET.contains(lower)) {
            return null;
        }
        String[] parts = lower.split("\\.", 2);
        String label = parts[0];

        for (String brand : BrandConstants.BRAND_DOMAINS) {
            String brandLabel = brand.split("\\.", 2)[0];
            if (label.equals(brandLabel) && !lower.equals(brand)) {
                return new LookalikeFinding(
                    "tld_swap",
                    brand,
                    "Domain " + domain + " matches brand name '" + brandLabel +
                    "' but uses the wrong top-level domain (real domain is " + brand + ")"
                );
            }
        }
        return null;
    }

    public List<LookalikeFinding> analyzeDomain(String hostname) {
        String domain = UrlUtils.registrableDomain(hostname);
        List<LookalikeFinding> findings = new ArrayList<>();

        LookalikeFinding f1 = checkEditDistance(domain);
        if (f1 != null) findings.add(f1);

        LookalikeFinding f2 = checkCharSubstitution(domain);
        if (f2 != null) findings.add(f2);

        LookalikeFinding f3 = checkHomoglyph(domain);
        if (f3 != null) findings.add(f3);

        LookalikeFinding f4 = checkTldSwap(domain);
        if (f4 != null) findings.add(f4);

        return findings;
    }
}
