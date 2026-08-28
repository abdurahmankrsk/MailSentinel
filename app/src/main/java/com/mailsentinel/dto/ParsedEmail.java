package com.mailsentinel.dto;

/**
 * Structured email components extracted from RFC 5322 MIME data.
 *
 * @param authenticationResults Authentication-Results header string or null
 * @param senderDomain domain of From address or null
 * @param senderDisplayName human-readable name from the From header, or null when
 *                          the address was sent bare (no display name)
 * @param textBody plaintext body content or null
 * @param htmlBody HTML body content or null
 */
public record ParsedEmail(
    String authenticationResults,
    String senderDomain,
    String senderDisplayName,
    String textBody,
    String htmlBody
) {}
