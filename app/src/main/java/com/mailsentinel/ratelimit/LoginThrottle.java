package com.mailsentinel.ratelimit;

import org.springframework.stereotype.Service;

import java.util.Locale;

/**
 * Throttles online password guessing.
 *
 * Counts *failures*, not attempts, so a person signing in repeatedly and correctly is
 * never locked out by their own success -- and a success clears the count outright.
 *
 * Two counters per attempt, both of which must be under quota:
 *
 *  - per IP, which stops one host working through a list of accounts;
 *  - per email address, which stops a botnet working through one account from many
 *    hosts. Credential stuffing rotates IPs, so the per-IP counter alone is the one
 *    an attacker can buy their way around.
 *
 * The email counter is a denial-of-service lever by construction: anyone who knows an
 * address can spend its failure budget and lock its owner out for the window. That is
 * the standard trade and is why the window is short and the response is a 429 with a
 * Retry-After rather than an account lock that needs a human to undo. Sending the
 * owner a "someone is trying to sign in" mail is the usual next step and needs the
 * email delivery that finding #7 also blocks on.
 */
@Service
public class LoginThrottle {

    private final RateLimitService rateLimitService;
    private final RateLimitProperties properties;

    public LoginThrottle(RateLimitService rateLimitService, RateLimitProperties properties) {
        this.rateLimitService = rateLimitService;
        this.properties = properties;
    }

    /**
     * @return the blocking decision when either counter is spent, or null to proceed
     */
    public RateLimitDecision checkAllowed(String ip, String email) {
        if (!properties.isEnabled()) {
            return null;
        }
        RateLimitPolicy policy = properties.getLogin().toPolicy();
        RateLimitDecision byIp = rateLimitService.peek(ipKey(ip), policy);
        if (!byIp.allowed()) {
            return byIp;
        }
        RateLimitDecision byEmail = rateLimitService.peek(emailKey(email), policy);
        return byEmail.allowed() ? null : byEmail;
    }

    public void recordFailure(String ip, String email) {
        if (!properties.isEnabled()) {
            return;
        }
        RateLimitPolicy policy = properties.getLogin().toPolicy();
        rateLimitService.consume(ipKey(ip), policy);
        rateLimitService.consume(emailKey(email), policy);
    }

    /**
     * Clears both counters. The IP one too: a host that proves it holds one valid
     * credential is not the host the guessing limit is aimed at, and leaving its
     * count standing would punish a shared office address for one person's typo.
     */
    public void recordSuccess(String ip, String email) {
        rateLimitService.reset(ipKey(ip));
        rateLimitService.reset(emailKey(email));
    }

    private static String ipKey(String ip) {
        return "login|ip|" + ip;
    }

    /**
     * Lowercased so "User@Example.com" and "user@example.com" share one budget --
     * otherwise the counter is bypassed by varying the case, which the login lookup
     * itself does not distinguish.
     */
    private static String emailKey(String email) {
        return "login|email|" + (email == null ? "" : email.trim().toLowerCase(Locale.ROOT));
    }
}
