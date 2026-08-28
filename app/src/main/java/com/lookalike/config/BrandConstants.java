package com.lookalike.config;

import java.util.List;
import java.util.Set;

/**
 * High-value brand domains commonly impersonated in phishing.
 * Kept separate from the detection logic so this list is trivial to extend.
 */
public final class BrandConstants {
    private BrandConstants() {}

    public static final List<String> BRAND_DOMAINS = List.of(
        // Payments / fintech
        "paypal.com",
        "stripe.com",
        "coinbase.com",
        "binance.com",
        "venmo.com",
        // Banks
        "chase.com",
        "bankofamerica.com",
        "wellsfargo.com",
        "citibank.com",
        "capitalone.com",
        "usbank.com",
        "hsbc.com",
        "americanexpress.com",
        // Big tech / accounts
        "google.com",
        "microsoft.com",
        "apple.com",
        "amazon.com",
        "facebook.com",
        "instagram.com",
        "linkedin.com",
        "yahoo.com",
        "outlook.com",
        "netflix.com",
        "adobe.com",
        "dropbox.com",
        "github.com",
        // Shipping / delivery
        "usps.com",
        "fedex.com",
        "ups.com",
        "dhl.com",
        // Other frequent phishing targets
        "docusign.com",
        "ebay.com",
        "irs.gov"
    );

    public static final Set<String> BRAND_SET = Set.copyOf(BRAND_DOMAINS);
}
