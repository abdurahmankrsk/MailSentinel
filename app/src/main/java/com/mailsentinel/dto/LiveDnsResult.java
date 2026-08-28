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
 * @param dmarcPresent true if a DMARC TXT record exists at _dmarc.&lt;domain&gt;
 * @param dmarcResolved false if the DMARC lookup could not be completed
 * @param dmarcPolicy declared policy ("none", "quarantine", "reject") or null
 */
public record LiveDnsResult(
    boolean spfPresent,
    boolean spfResolved,
    boolean dmarcPresent,
    boolean dmarcResolved,
    String dmarcPolicy
) {}
