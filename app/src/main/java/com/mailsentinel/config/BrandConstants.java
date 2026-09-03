package com.mailsentinel.config;

import org.springframework.core.io.ClassPathResource;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * High-value brand domains commonly impersonated in phishing.
 * Kept separate from the detection logic so this list is trivial to extend.
 *
 * <p>The list itself lives in {@code brand-domains.txt} on the classpath rather than in
 * this file, following the pattern {@code disposable-email-domains.txt} already
 * established: a resource can be reviewed, diffed and refreshed by someone who does not
 * read Java, and the file's own header carries the editing rules where an editor will
 * actually see them.
 *
 * <p>A brand is a <em>set</em> of domains, not a single string. Big brands legitimately
 * operate country-specific domains -- amazon.co.uk, google.de, paypal.fr -- and treating
 * only the .com as real made every one of them look like a typosquat. That was not a
 * cosmetic problem: a genuine, fully-authenticated amazon.co.uk dispatch email tripped
 * the TLD-swap check, the display-name check, and the in-body link check at once, and
 * three supposedly independent signals stacking on one underlying fact scored it
 * 100/100 "treat this as hostile."
 *
 * <p>The map's key is the brand's primary domain, which is what every user-facing
 * message names ("the real domain is amazon.com") and what edit-distance compares
 * against. The value is every domain that brand is known to send from.
 *
 * <p>Membership here is an assertion that a domain is <em>safe</em>, so this list errs
 * towards omission: a missing regional domain costs a false positive, while a wrongly
 * included one silently allowlists a phishing domain. Regional coverage is deliberately
 * partial -- extend it as false positives are reported, not speculatively.
 */
public final class BrandConstants {
    private BrandConstants() {}

    private static final String BRANDS_RESOURCE = "brand-domains.txt";

    /**
     * Primary domain -> every domain the brand legitimately operates on, primary included.
     *
     * <p>A LinkedHashMap, not Map.of: iteration order decides which brand a message names
     * when several match, so it has to be stable across JVM runs, and it has to follow
     * the order of the resource file rather than a hash.
     */
    private static final Map<String, Set<String>> OWNED_DOMAINS = loadOwnedDomains();

    /**
     * Brand labels that are also ordinary English words, or common substrings of them.
     *
     * <p>These need corroboration before {@code checkBrandInDomain} will fire on them.
     * "apple" is a fruit, "chase" is a verb, "ups" sits inside "start-ups" and "pop-ups",
     * "target" and "wise" are everyday words -- flagging every domain containing one
     * would produce more false positives than findings, and a false positive from a
     * security tool is expensive because it teaches people to ignore it.
     *
     * <p>Note this holds back only the brand-in-domain technique. Edit distance,
     * character substitution, homoglyph and TLD swap all still apply to these brands
     * unchanged, because those match near-exact forms where the ambiguity does not arise.
     */
    public static final Set<String> COMMON_WORD_BRAND_LABELS = Set.of(
            "apple", "chase", "ups", "target", "discord", "outlook", "citi", "amex",
            "fb", "irs", "poste", "argos", "halifax", "nationwide");

    /**
     * Words a phishing domain adds around a brand name to make it read like an official
     * one. Their presence is what lets the brand-in-domain technique fire on a
     * common-word brand: "apple-orchard.com" is a shop, "apple-support.com" is not.
     */
    public static final Set<String> LURE_WORDS = Set.of(
            "secure", "security", "login", "signin", "logon", "verify", "verification",
            "verified", "account", "accounts", "billing", "payment", "payments", "pay",
            "support", "update", "updates", "confirm", "confirmation", "alert", "alerts",
            "helpdesk", "recovery", "recover", "unlock", "suspended", "refund", "invoice",
            "tracking", "track", "parcel", "delivery", "notice", "notification", "portal",
            "auth", "authenticate", "myaccount", "customer", "validate", "restore",
            "reactivate", "reset", "access");

    private static Map<String, Set<String>> loadOwnedDomains() {
        Map<String, Set<String>> owned = new LinkedHashMap<>();
        ClassPathResource resource = new ClassPathResource(BRANDS_RESOURCE);
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                String[] domains = trimmed.toLowerCase(Locale.ROOT).split("\\s+");
                Set<String> group = new LinkedHashSet<>();
                Collections.addAll(group, domains);
                owned.put(domains[0], Set.copyOf(group));
            }
        } catch (IOException e) {
            // Fail loudly at startup rather than degrade to an empty watch list. A
            // detector that silently watches no brands reports every phishing domain as
            // clean, which is the worst failure this codebase can have.
            throw new UncheckedIOException("Could not load " + BRANDS_RESOURCE, e);
        }
        if (owned.isEmpty()) {
            throw new IllegalStateException(BRANDS_RESOURCE + " contained no brands");
        }
        // Collections.unmodifiableMap over the LinkedHashMap, not Map.copyOf: the latter
        // returns a map with unspecified iteration order, which would randomise
        // BRAND_DOMAINS between JVM runs and with it the brand named in each finding.
        return Collections.unmodifiableMap(owned);
    }

    /**
     * The primary domain of every watched brand, in a stable order.
     *
     * <p>These are the edit-distance comparison targets and the domains named in
     * findings. Regional variants are deliberately absent: comparing a typosquat
     * against every owned domain would report whichever happened to be closest
     * rather than the brand a reader would recognise.
     */
    public static final List<String> BRAND_DOMAINS = List.copyOf(OWNED_DOMAINS.keySet());

    /**
     * Every domain known to belong to a watched brand, primaries and regional variants
     * alike. The detection techniques use this as their "this is the real thing, stop"
     * short-circuit, so it has to include the regional domains.
     */
    public static final Set<String> BRAND_SET = OWNED_DOMAINS.values().stream()
            .flatMap(Collection::stream)
            .collect(Collectors.toUnmodifiableSet());

    /** How many brands are watched. Surfaced to the user so a clean verdict can state its own scope. */
    public static int brandCount() {
        return BRAND_DOMAINS.size();
    }

    /** The first label of a brand's primary domain -- "paypal" for paypal.com. */
    public static String labelOf(String primaryDomain) {
        return primaryDomain.split("\\.", 2)[0];
    }

    /** Every domain {@code primaryDomain} legitimately sends from, or empty if unwatched. */
    public static Set<String> ownedDomainsOf(String primaryDomain) {
        return OWNED_DOMAINS.getOrDefault(primaryDomain, Set.of());
    }

    /**
     * Is {@code domain} one of the domains {@code primaryDomain}'s brand operates on?
     *
     * <p>This is what lets "Amazon.co.uk &lt;no-reply@amazon.co.uk&gt;" read as a display
     * name backed up by its sending domain rather than as impersonation of amazon.com.
     */
    public static boolean isOwnedByBrand(String domain, String primaryDomain) {
        return domain != null && ownedDomainsOf(primaryDomain).contains(domain.toLowerCase(Locale.ROOT));
    }
}
