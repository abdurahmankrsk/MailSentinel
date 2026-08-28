package com.mailsentinel.ai;

import java.util.List;

/** Raw, not-yet-validated output from an AiProvider -- see AiAnalysisService for clamping. */
public record AiAnalysisResult(String summary, List<AiFinding> findings) {}
