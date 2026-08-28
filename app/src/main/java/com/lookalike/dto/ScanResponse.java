package com.lookalike.dto;

import java.util.List;

/**
 * Scan response returning the calculated score and full breakdown of checks.
 *
 * @param score capped risk score from 0 to 100
 * @param checks list of all applicable checks performed
 */
public record ScanResponse(
    int score,
    List<CheckResult> checks
) {}
