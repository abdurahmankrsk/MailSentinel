package com.mailsentinel.dto;

/**
 * Authentication verdicts extracted from the Authentication-Results header.
 *
 * @param headerPresent true if an Authentication-Results header was found
 * @param spf claimed SPF qualifier (e.g. "pass", "fail", "softfail") or null
 * @param dkim claimed DKIM qualifier (e.g. "pass", "fail", "none") or null
 * @param dmarc claimed DMARC qualifier (e.g. "pass", "fail") or null
 */
public record ClaimedAuthResults(
    boolean headerPresent,
    String spf,
    String dkim,
    String dmarc
) {
    public static ClaimedAuthResults notPresent() {
        return new ClaimedAuthResults(false, null, null, null);
    }
}
