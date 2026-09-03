package com.mailsentinel.dto;

import java.util.List;

/**
 * Scan response returning the calculated score and full breakdown of checks.
 *
 * For an anonymous or FREE-plan caller, score/checks are exactly the deterministic
 * result and aiAnalysis is null. For an authenticated PREMIUM caller with a completed
 * AI pass, checks additionally includes the AI's own findings (each named "AI: ...")
 * and score reflects their combined weight -- see AiAnalysisService. aiAnalysis itself
 * is metadata about the AI pass (status, narrative summary, usage figures) and never
 * score-bearing on its own.
 *
 * @param score capped risk score from 0 to 100
 * @param checks list of all applicable checks performed
 * @param aiAnalysis metadata about the AI pass, or null if none applies to this caller
 * @param brandsWatched how many brands the lookalike techniques compared against.
 *        Carried on the response, rather than fetched separately or hardcoded in the
 *        client, so a clean verdict can state its own scope: a score of 0 means "none
 *        of the N brands we watch resemble this", which is a narrower claim than
 *        "this is safe" and the UI should not be able to drift out of sync with it.
 */
public record ScanResponse(
    int score,
    List<CheckResult> checks,
    AiAnalysisMeta aiAnalysis,
    int brandsWatched
) {}
