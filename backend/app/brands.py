"""High-value brand domains commonly impersonated in phishing.

Kept separate from the detection logic in lookalike.py so this list is
trivial to extend later without touching any check code.
"""

BRAND_DOMAINS = [
    # Payments / fintech
    "paypal.com",
    "stripe.com",
    "coinbase.com",
    "binance.com",
    "venmo.com",
    # Banks
    "chase.com",
    "bankofamerica.com",
    "wellsfargo.com",
    "citibank.com",
    "capitalone.com",
    "usbank.com",
    "hsbc.com",
    "americanexpress.com",
    # Big tech / accounts
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
    # Shipping / delivery
    "usps.com",
    "fedex.com",
    "ups.com",
    "dhl.com",
    # Other frequent phishing targets
    "docusign.com",
    "ebay.com",
    "irs.gov",
]
