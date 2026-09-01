package com.mailsentinel.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Every quota in one place and overridable without a rebuild, the same way
 * PlanProperties handles plan configuration.
 *
 * The defaults are set as abuse ceilings, not as usage budgets: free unlimited
 * scanning is a deliberate product promise, so /api/scan's limit sits far above what
 * a person could reach by hand and only bites a script.
 */
@ConfigurationProperties(prefix = "mailsentinel.rate-limit")
public class RateLimitProperties {

    /** Turns every limiter off at once. For a single-user local run, not for a deployment. */
    private boolean enabled = true;

    /**
     * Whether to read the client address from X-Forwarded-For. See ClientIpResolver:
     * true behind a proxy that sets it, false when the app is reached directly.
     */
    private boolean trustForwardedFor = false;

    /** Failed logins per IP, and separately per email address. */
    private Quota login = new Quota(5, Duration.ofMinutes(15));

    /** New accounts per IP. */
    private Quota register = new Quota(3, Duration.ofHours(1));

    /** Scans per IP. Generous on purpose -- an abuse ceiling, not a usage budget. */
    private Quota scan = new Quota(60, Duration.ofMinutes(1));

    /** Bring-your-own-key saves per IP. Tight: each save makes this server call a URL the caller chose. */
    private Quota aiKey = new Quota(5, Duration.ofHours(1));

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isTrustForwardedFor() {
        return trustForwardedFor;
    }

    public void setTrustForwardedFor(boolean trustForwardedFor) {
        this.trustForwardedFor = trustForwardedFor;
    }

    public Quota getLogin() {
        return login;
    }

    public void setLogin(Quota login) {
        this.login = login;
    }

    public Quota getRegister() {
        return register;
    }

    public void setRegister(Quota register) {
        this.register = register;
    }

    public Quota getScan() {
        return scan;
    }

    public void setScan(Quota scan) {
        this.scan = scan;
    }

    public Quota getAiKey() {
        return aiKey;
    }

    public void setAiKey(Quota aiKey) {
        this.aiKey = aiKey;
    }

    /** Mutable so Spring can bind it; converted to the immutable RateLimitPolicy at the point of use. */
    public static class Quota {

        private int limit;
        private Duration window;

        public Quota() {
        }

        public Quota(int limit, Duration window) {
            this.limit = limit;
            this.window = window;
        }

        public int getLimit() {
            return limit;
        }

        public void setLimit(int limit) {
            this.limit = limit;
        }

        public Duration getWindow() {
            return window;
        }

        public void setWindow(Duration window) {
            this.window = window;
        }

        public RateLimitPolicy toPolicy() {
            return new RateLimitPolicy(limit, window);
        }
    }
}
