package com.mailsentinel.dto;

/**
 * Metadata about an AI analysis pass -- never score-bearing itself (the AI's actual
 * contribution to score/checks, when status is AI_ANALYSIS_COMPLETED, already lives
 * in ScanResponse.checks as "AI: ..."-prefixed entries).
 *
 * @param status which of the four AI outcomes this response represents
 * @param summary AI's narrative context, non-null only when status is AI_ANALYSIS_COMPLETED
 * @param message human-readable explanation, populated for the non-completed statuses
 * @param scansUsed AI scans used this billing period, or null if not applicable
 * @param scansRemaining AI scans remaining this billing period, or null if not applicable
 * @param scansAllowance total monthly AI scan allowance, or null if not applicable
 * @param resetDate ISO-8601 instant when the current billing period ends, or null if not applicable
 */
public record AiAnalysisMeta(
    AiAnalysisStatus status,
    String summary,
    String message,
    Integer scansUsed,
    Integer scansRemaining,
    Integer scansAllowance,
    String resetDate
) {}
