package com.mailsentinel.subscription;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PlanCatalogTest {

    @Test
    void fallsBackToEnumDefaultsWhenNothingConfigured() {
        PlanCatalog catalog = new PlanCatalog(new PlanProperties());

        assertEquals(0, catalog.priceCents(Plan.FREE));
        assertEquals(0, catalog.aiScansPerMonth(Plan.FREE));
        assertEquals(300, catalog.priceCents(Plan.PREMIUM));
        assertEquals(1000, catalog.aiScansPerMonth(Plan.PREMIUM));
    }

    @Test
    void configuredValuesOverrideEnumDefaults() {
        PlanProperties properties = new PlanProperties();
        PlanProperties.PlanConfig premiumOverride = new PlanProperties.PlanConfig();
        premiumOverride.setPriceCents(500);
        premiumOverride.setAiScansPerMonth(2000);
        properties.setPremium(premiumOverride);

        PlanCatalog catalog = new PlanCatalog(properties);

        assertEquals(500, catalog.priceCents(Plan.PREMIUM));
        assertEquals(2000, catalog.aiScansPerMonth(Plan.PREMIUM));
        // FREE was never overridden, so it still falls back to the enum default.
        assertEquals(0, catalog.priceCents(Plan.FREE));
    }

    @Test
    void explicitZeroOverrideIsRespectedNotTreatedAsUnset() {
        PlanProperties properties = new PlanProperties();
        PlanProperties.PlanConfig freeOverride = new PlanProperties.PlanConfig();
        freeOverride.setAiScansPerMonth(0);
        properties.setFree(freeOverride);

        PlanCatalog catalog = new PlanCatalog(properties);

        assertEquals(0, catalog.aiScansPerMonth(Plan.FREE));
    }
}
