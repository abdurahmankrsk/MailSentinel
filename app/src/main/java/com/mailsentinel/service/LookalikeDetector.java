package com.mailsentinel.service;

import com.mailsentinel.config.BrandConstants;
import com.mailsentinel.dto.LookalikeFinding;
import org.apache.commons.text.similarity.LevenshteinDistance;
import org.springframework.stereotype.Service;

import java.net.IDN;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Lookalike and typosquat detection against the BrandConstants target list.
 *
 * Six independent domain techniques:
 *   - edit distance:      paypa1.com is 1 edit from paypal.com
 *   - char substitution:  digits/letter-runs standing in for similar letters (0->o, 1->l, rn->m)
 *   - homoglyphs:         non-Latin letters visually identical to Latin (Cyrillic а/е/о/р/с/etc.), Punycode (xn--)
 *   - TLD swap:           exact brand name with wrong top-level domain
 *   - brand subdomain:    brand name parked in a subdomain of an unrelated domain
 *   - brand in domain:    brand name inside the registrable label (paypal-secure.com)
 *
 * The first four all match near-exact forms, which left a hole the size of the most
 * common real phishing shape there is: paypal-secure.com is nine edits from
 * paypal.com, substitutes nothing, and has no subdomain, so it scored zero until
 * checkBrandInDomain existed.
 *
 * Plus a seventh, domain-independent technique -- display-name impersonation -- which
 * catches the case none of the above structurally can: an attacker on a domain that
 * resembles no brand at all (secure-docs-exchange.com) simply *naming* one in the
 * From header, which is the half a mail client shows the reader by default.
 */
@Service
public class LookalikeDetector {

    private static final int MAX_EDIT_DISTANCE = 2;

    /**
     * Below this length a brand label is only matched on whole-word boundaries, never
     * inside a run of squashed characters: "ups" would otherwise match "Groups" and
     * "apple" would match "Pineapple Co" once separators are stripped.
     */
    private static final int MIN_COMPACT_LABEL_LENGTH = 8;
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

    /**
     * Does a brand's name sit in the subdomain of a registrable domain that isn't it?
     *
     * paypal.com.verify-account.ru resolves, correctly, to verify-account.ru -- which is
     * exactly why the other techniques score it zero: verify-account.ru resembles no
     * brand. The deception isn't in the registrable domain at all, it's that a human
     * reading the address bar left-to-right sees "paypal.com" first and stops there.
     *
     * Matching is on whole subdomain labels, so "paypal" in paypal.com.evil.ru fires
     * while "mypaypalinvoices" does not. A brand on its own registrable domain
     * (mail.google.com) is the ordinary case and passes.
     */
    public LookalikeFinding checkBrandSubdomain(String hostname) {
        if (hostname == null || hostname.isBlank()) {
            return null;
        }
        String lower = hostname.trim().toLowerCase(Locale.ROOT);
        String domain = UrlUtils.registrableDomain(lower);
        if (domain.isBlank() || BrandConstants.BRAND_SET.contains(domain)) {
            return null;
        }
        String suffix = "." + domain;
        if (!lower.endsWith(suffix)) {
            return null; // no subdomain to inspect
        }

        Set<String> labels = new HashSet<>(
            Arrays.asList(lower.substring(0, lower.length() - suffix.length()).split("\\.")));

        for (String brand : BrandConstants.BRAND_DOMAINS) {
            String label = brand.split("\\.", 2)[0];
            if (labels.contains(label)) {
                return new LookalikeFinding(
                    "brand_subdomain",
                    brand,
                    "Hostname " + hostname + " carries \"" + label + "\" in a subdomain, so it reads as "
                        + brand + " while it actually resolves to " + domain
                );
            }
        }
        return null;
    }

    /**
     * Is a brand's name sitting inside the registrable label itself?
     *
     * <p>This is the single most common real phishing shape, and every other technique
     * structurally missed it. paypal-secure.com is not within edit distance of
     * paypal.com (nine characters apart), substitutes no characters, uses no
     * homoglyphs, is not a TLD swap, and carries no subdomain -- so it scored a clean
     * <b>0</b>, as did apple-support.com, microsoft-login.com, amazon-billing.com and
     * chase-verify.com. In an email the display-name check partly compensated, but only
     * when the attacker obliged by naming the brand: the same domain behind a neutral
     * display name scored 11, "Low risk".
     *
     * <p>Matching is on whole tokens of the registrable label, split on hyphens and
     * underscores, so "paypal" fires in paypal-secure.com but not in "paypalette.com".
     * Unhyphenated concatenation is matched too, but only for brand labels of
     * {@link #MIN_COMPACT_LABEL_LENGTH}+ characters -- the same rule that already stops
     * "Pineapple" matching "apple" -- so microsoftlogin.com is caught while a short
     * brand label buried in a longer word is not.
     *
     * <p>A brand label that is also an ordinary English word needs corroboration: some
     * other token has to be a recognised lure word. Without that rule "start-ups.com"
     * reads as UPS, "paper-chase.com" as Chase and "apple-orchard.com" as Apple. With
     * it, apple-support.com and chase-verify.com still fire, because "support" and
     * "verify" are exactly what a phishing domain adds and a greengrocer does not.
     *
     * <p>Weighted in the Strong tier rather than solo-red: legitimate affiliates and
     * campaign sites do occasionally register brand-adjacent domains, so this should
     * stack with another signal rather than convict on its own.
     */
    public LookalikeFinding checkBrandInDomain(String hostname) {
        if (hostname == null || hostname.isBlank()) {
            return null;
        }
        String domain = UrlUtils.registrableDomain(hostname.trim().toLowerCase(Locale.ROOT));
        if (domain.isBlank() || BrandConstants.BRAND_SET.contains(domain)) {
            return null;
        }
        String label = domain.split("\\.", 2)[0];
        Set<String> tokens = new LinkedHashSet<>(Arrays.asList(label.split("[-_]+")));
        // A label that is nothing but the brand name is a TLD swap, and checkTldSwap
        // already reports it. Firing here as well would score one fact twice.
        if (tokens.size() == 1 && BrandConstants.BRAND_SET.stream()
                .anyMatch(owned -> owned.split("\\.", 2)[0].equals(label))) {
            return null;
        }
        String compact = label.replace("-", "").replace("_", "");

        for (String brand : BrandConstants.BRAND_DOMAINS) {
            String brandLabel = BrandConstants.labelOf(brand);
            boolean tokenMatch = tokens.contains(brandLabel);
            boolean compactMatch = brandLabel.length() >= MIN_COMPACT_LABEL_LENGTH
                    && !compact.equals(brandLabel)
                    && compact.contains(brandLabel);
            if (!tokenMatch && !compactMatch) {
                continue;
            }
            if (BrandConstants.COMMON_WORD_BRAND_LABELS.contains(brandLabel) && !hasLureWord(tokens, brandLabel)) {
                continue;
            }
            return new LookalikeFinding(
                "brand_in_domain",
                brand,
                "Domain " + domain + " puts \"" + brandLabel + "\" in its own name, so it reads as "
                    + brand + " while it is an unrelated domain that brand does not own"
            );
        }
        return null;
    }

    private boolean hasLureWord(Set<String> tokens, String brandLabel) {
        return tokens.stream()
                .filter(token -> !token.equals(brandLabel))
                .anyMatch(BrandConstants.LURE_WORDS::contains);
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

    /**
     * Does the From display name claim a brand that the sending domain doesn't back up?
     *
     * "Microsoft 365" &lt;no-reply@m365-account-security.com&gt; is the whole attack in one
     * line: nothing about the domain resembles microsoft.com, so every distance-based
     * technique above scores it zero, while the recipient's mail client shows them the
     * word "Microsoft". A display name that names the brand it is actually sent from
     * (GitHub &lt;notifications@github.com&gt;) is the normal, legitimate case and passes.
     */
    public LookalikeFinding checkDisplayNameImpersonation(String displayName, String senderDomain) {
        if (displayName == null || displayName.isBlank()) {
            return null;
        }
        String lowerName = displayName.toLowerCase(Locale.ROOT);
        String compactName = lowerName.replaceAll("[^a-z0-9]", "");
        String domain = UrlUtils.registrableDomain(senderDomain);

        List<String> namedBrands = new ArrayList<>();
        for (String brand : BrandConstants.BRAND_DOMAINS) {
            String label = brand.split("\\.", 2)[0];
            boolean namesBrand =
                Pattern.compile("\\b" + Pattern.quote(label) + "\\b").matcher(lowerName).find()
                || (label.length() >= MIN_COMPACT_LABEL_LENGTH && compactName.contains(label));
            if (namesBrand) {
                namedBrands.add(brand);
            }
        }

        if (namedBrands.isEmpty()) {
            return null;
        }
        // Sent from any one of the brands it names -- that's just correctly-branded mail.
        //
        // Matched against the brand's whole owned-domain set rather than its primary
        // domain alone, so "Amazon.co.uk" <no-reply@amazon.co.uk> reads as a display name
        // its sending domain backs up. Comparing only against amazon.com made every
        // regional brand domain look like impersonation of itself.
        for (String brand : namedBrands) {
            if (BrandConstants.isOwnedByBrand(domain, brand)) {
                return null;
            }
        }

        String claimed = namedBrands.get(0);
        return new LookalikeFinding(
            "display_name_impersonation",
            claimed,
            "From display name \"" + displayName + "\" names " + claimed
                + ", but the message was actually sent from "
                + (domain.isBlank() ? "an unknown domain" : domain)
        );
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

        // Takes the full hostname, not the registrable domain: the subdomain is the
        // whole point of this one.
        LookalikeFinding f5 = checkBrandSubdomain(hostname);
        if (f5 != null) findings.add(f5);

        LookalikeFinding f6 = checkBrandInDomain(hostname);
        if (f6 != null) findings.add(f6);

        return findings;
    }
}
