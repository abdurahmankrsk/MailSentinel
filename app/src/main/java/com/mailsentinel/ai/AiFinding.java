package com.mailsentinel.ai;

/**
 * One suspicious signal the AI found beyond what the deterministic engine caught.
 * weight is Integer (boxed), not int, so a missing/malformed value from the model's
 * JSON output is detectable as null during validation rather than silently defaulting
 * to 0 -- see AiAnalysisService, which clamps and validates every field here before
 * any of it is trusted.
 */
public record AiFinding(String name, Integer weight, String detail) {}
