package com.mailsentinel.dto;

/**
 * Result of a single phishing detection check.
 *
 * @param name human-readable name of the check
 * @param passed true if the check found no suspicious pattern, false otherwise
 * @param weight penalty points added to score if check failed
 * @param detail explanation of why it passed or failed
 */
public record CheckResult(
    String name,
    boolean passed,
    int weight,
    String detail
) {}
