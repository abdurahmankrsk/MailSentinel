package com.mailsentinel.usage;

import java.time.Instant;

public record UsageStatusResponse(
    String plan,
    int scansAllowance,
    int scansUsed,
    int scansRemaining,
    Instant periodStart,
    Instant periodEnd
) {}
