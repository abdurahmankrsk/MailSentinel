package com.mailsentinel.dto;

/**
 * Result of independent DNS TXT queries for SPF and DMARC.
 *
 * @param spfPresent true if a valid SPF TXT record exists
 * @param dmarcPresent true if a DMARC TXT record exists at _dmarc.<domain>
 * @param dmarcPolicy declared policy ("none", "quarantine", "reject") or null
 */
public record LiveDnsResult(
    boolean spfPresent,
    boolean dmarcPresent,
    String dmarcPolicy
) {}
