package com.mailsentinel.dto;

/**
 * Result of independent DNS TXT queries for SPF and DMARC.
 *
 * <p>{@code spfResolved}/{@code dmarcResolved} record whether the lookup actually
 * completed. Without them, "this domain publishes no SPF record" and "we could not
 * reach DNS just now" collapse into the same empty answer, and a transient timeout
 * gets scored as evidence against a legitimate sender.
 *
 * @param spfPresent true if a valid SPF TXT record exists
 * @param spfResolved false if the SPF lookup could not be completed, making
 *                    {@code spfPresent} meaningless rather than merely false
 * @param dmarcPresent true if a DMARC record applies to the domain -- its own, or its
 *                     organisational domain's (RFC 7489 section 6.6.3)
 * @param dmarcResolved false if the DMARC lookup could not be completed
 * @param dmarcPolicy the policy that applies ("none", "quarantine", "reject") or null;
 *                    for a subdomain covered by its organisational domain's record this
 *                    is that record's {@code sp=} when present, else its {@code p=}
 * @param dmarcRecordDomain the domain whose {@code _dmarc} record applied, or null when
 *                          none did -- the sending domain itself, or its organisational
 *                          domain when the sender is a subdomain without a record of its own
 */
public record LiveDnsResult(
    boolean spfPresent,
    boolean spfResolved,
    boolean dmarcPresent,
    boolean dmarcResolved,
    String dmarcPolicy,
    String dmarcRecordDomain
) {
    /** For a result where, if a DMARC record applies, it is the sending domain's own. */
    public LiveDnsResult(boolean spfPresent, boolean spfResolved, boolean dmarcPresent,
                         boolean dmarcResolved, String dmarcPolicy) {
        this(spfPresent, spfResolved, dmarcPresent, dmarcResolved, dmarcPolicy, null);
    }
}
