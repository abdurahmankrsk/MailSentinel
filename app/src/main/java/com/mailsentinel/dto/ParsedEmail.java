package com.mailsentinel.dto;

/**
 * Structured email components extracted from RFC 5322 MIME data.
 *
 * @param authenticationResults Authentication-Results header string or null
 * @param senderDomain domain of From address or null
 * @param textBody plaintext body content or null
 * @param htmlBody HTML body content or null
 */
public record ParsedEmail(
    String authenticationResults,
    String senderDomain,
    String textBody,
    String htmlBody
) {}
