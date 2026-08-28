package com.mailsentinel.subscription;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "mailsentinel.plans")
public class PlanProperties {

    private PlanConfig free = new PlanConfig();
    private PlanConfig premium = new PlanConfig();

    public PlanConfig getFree() {
        return free;
    }

    public void setFree(PlanConfig free) {
        this.free = free;
    }

    public PlanConfig getPremium() {
        return premium;
    }

    public void setPremium(PlanConfig premium) {
        this.premium = premium;
    }

    public static class PlanConfig {
        // Boxed Integer, not int: null means "not configured, fall back to the Plan
        // enum's default" -- distinguishable from an explicit override of 0.
        private Integer priceCents;
        private Integer aiScansPerMonth;

        public Integer getPriceCents() {
            return priceCents;
        }

        public void setPriceCents(Integer priceCents) {
            this.priceCents = priceCents;
        }

        public Integer getAiScansPerMonth() {
            return aiScansPerMonth;
        }

        public void setAiScansPerMonth(Integer aiScansPerMonth) {
            this.aiScansPerMonth = aiScansPerMonth;
        }
    }
}
