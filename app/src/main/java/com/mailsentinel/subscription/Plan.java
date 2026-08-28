package com.mailsentinel.subscription;

/**
 * The closed set of subscription tiers. Defaults here are the fallback used when
 * {@link PlanProperties} doesn't override them -- see {@link PlanCatalog}, which is
 * the actual mechanism satisfying "changeable via configuration without code changes."
 */
public enum Plan {
    FREE(0, 0),
    PREMIUM(300, 1000); // price in cents -- integer minor units, never float for money

    private final int defaultPriceCents;
    private final int defaultAiScansPerMonth;

    Plan(int defaultPriceCents, int defaultAiScansPerMonth) {
        this.defaultPriceCents = defaultPriceCents;
        this.defaultAiScansPerMonth = defaultAiScansPerMonth;
    }

    public int getDefaultPriceCents() {
        return defaultPriceCents;
    }

    public int getDefaultAiScansPerMonth() {
        return defaultAiScansPerMonth;
    }
}
