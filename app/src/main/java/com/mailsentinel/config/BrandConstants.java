package com.mailsentinel.config;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * High-value brand domains commonly impersonated in phishing.
 * Kept separate from the detection logic so this list is trivial to extend.
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
 * included one silently allowlists a phishing domain. The regional coverage below is
 * deliberately partial -- extend it as false positives are reported, not speculatively.
 */
public final class BrandConstants {
    private BrandConstants() {}

    /**
     * Primary domain -> every domain the brand legitimately operates on, primary included.
     *
     * <p>A LinkedHashMap, not Map.of: iteration order decides which brand a message names
     * when several match, so it has to be stable across JVM runs.
     */
    private static final Map<String, Set<String>> OWNED_DOMAINS = buildOwnedDomains();

    private static Map<String, Set<String>> buildOwnedDomains() {
        Map<String, Set<String>> owned = new LinkedHashMap<>();

        // Payments / fintech
        put(owned, "paypal.com", "paypal.co.uk", "paypal.de", "paypal.fr", "paypal.it",
                "paypal.es", "paypal.nl", "paypal.ca", "paypal.com.au", "paypal.me");
        put(owned, "stripe.com");
        put(owned, "coinbase.com");
        put(owned, "binance.com", "binance.us");
        put(owned, "venmo.com");
        // Banks
        put(owned, "chase.com", "chase.co.uk");
        put(owned, "bankofamerica.com");
        put(owned, "wellsfargo.com");
        put(owned, "citibank.com", "citi.com");
        put(owned, "capitalone.com", "capitalone.co.uk");
        put(owned, "usbank.com");
        put(owned, "hsbc.com", "hsbc.co.uk", "hsbc.ca", "hsbc.fr", "hsbc.com.au", "hsbc.com.hk");
        put(owned, "americanexpress.com", "americanexpress.co.uk", "amex.com");
        // Big tech / accounts
        put(owned, "google.com", "google.co.uk", "google.de", "google.fr", "google.it",
                "google.es", "google.nl", "google.be", "google.pl", "google.se", "google.dk",
                "google.no", "google.fi", "google.ie", "google.pt", "google.at", "google.ch",
                "google.cz", "google.ro", "google.hu", "google.gr", "google.ca",
                "google.com.au", "google.co.jp", "google.co.in", "google.com.br",
                "google.com.mx");
        put(owned, "microsoft.com", "microsoft.co.uk", "microsoft.de", "microsoft.fr");
        put(owned, "apple.com", "apple.co.uk", "apple.de", "apple.fr", "apple.it",
                "apple.es", "apple.ca", "apple.com.au", "apple.co.jp");
        put(owned, "amazon.com", "amazon.co.uk", "amazon.de", "amazon.fr", "amazon.it",
                "amazon.es", "amazon.nl", "amazon.se", "amazon.pl", "amazon.be", "amazon.ie",
                "amazon.ca", "amazon.co.jp", "amazon.com.au", "amazon.com.br",
                "amazon.com.mx", "amazon.com.tr", "amazon.in", "amazon.ae", "amazon.sa",
                "amazon.sg", "amazon.eg");
        put(owned, "facebook.com", "fb.com");
        put(owned, "instagram.com");
        put(owned, "linkedin.com");
        put(owned, "yahoo.com", "yahoo.co.uk", "yahoo.co.jp", "yahoo.de", "yahoo.fr");
        put(owned, "outlook.com");
        put(owned, "netflix.com");
        put(owned, "adobe.com");
        put(owned, "dropbox.com");
        put(owned, "github.com");
        // Shipping / delivery
        put(owned, "usps.com");
        put(owned, "fedex.com");
        put(owned, "ups.com");
        put(owned, "dhl.com", "dhl.de", "dhl.co.uk");
        // Other frequent phishing targets
        put(owned, "docusign.com");
        put(owned, "ebay.com", "ebay.co.uk", "ebay.de", "ebay.fr", "ebay.it", "ebay.es",
                "ebay.nl", "ebay.be", "ebay.at", "ebay.ch", "ebay.ie", "ebay.pl",
                "ebay.ca", "ebay.com.au");
        put(owned, "irs.gov");

        // Collections.unmodifiableMap over the LinkedHashMap, not Map.copyOf: the latter
        // returns a map with unspecified iteration order, which would randomise
        // BRAND_DOMAINS between JVM runs and with it the brand named in each finding.
        return Collections.unmodifiableMap(owned);
    }

    private static void put(Map<String, Set<String>> target, String primary, String... regional) {
        Set<String> domains = new LinkedHashSet<>();
        domains.add(primary);
        domains.addAll(List.of(regional));
        target.put(primary, Set.copyOf(domains));
    }

    /**
     * The primary domain of every watched brand, in a stable order.
     *
     * <p>These are the edit-distance comparison targets and the domains named in
     * findings. Regional variants are deliberately absent: comparing a typosquat
     * against all ~120 owned domains would report whichever happened to be closest
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
