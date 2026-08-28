package com.mailsentinel.ai;

import com.mailsentinel.dto.CheckResult;

import java.util.List;

/**
 * Grounds the model in what the deterministic engine already found, rather than
 * asking it to re-derive everything blind.
 */
public record AiAnalysisRequest(
    String contentType,
    String content,
    int deterministicScore,
    List<CheckResult> deterministicChecks
) {}
