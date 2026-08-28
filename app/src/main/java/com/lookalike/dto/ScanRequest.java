package com.lookalike.dto;

/**
 * Request payload for the /api/scan endpoint.
 *
 * @param type "email" or "url"
 * @param content raw email RFC 5322 string or URL string
 */
public record ScanRequest(
    String type,
    String content
) {}
