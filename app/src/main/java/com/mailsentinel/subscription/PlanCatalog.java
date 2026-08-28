package com.mailsentinel.subscription;

import org.springframework.stereotype.Component;

/**
 * Resolves effective plan pricing/allowance: configured value if set, else the
 * {@link Plan} enum's default. This is the mechanism that satisfies "changeable
 * through configuration without major code changes" -- a future config edit to
 * {@code mailsentinel.plans.premium.ai-scans-per-month} takes effect for every
 * subsequently-created usage period with no code or migration change.
 */
@Component
public class PlanCatalog {

    private final PlanProperties properties;

    public PlanCatalog(PlanProperties properties) {
        this.properties = properties;
    }

    public int priceCents(Plan plan) {
        Integer configured = configFor(plan).getPriceCents();
        return configured != null ? configured : plan.getDefaultPriceCents();
    }

    public int aiScansPerMonth(Plan plan) {
        Integer configured = configFor(plan).getAiScansPerMonth();
        return configured != null ? configured : plan.getDefaultAiScansPerMonth();
    }

    private PlanProperties.PlanConfig configFor(Plan plan) {
        return switch (plan) {
            case FREE -> properties.getFree();
            case PREMIUM -> properties.getPremium();
        };
    }
}
