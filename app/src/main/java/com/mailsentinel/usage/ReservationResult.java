package com.mailsentinel.usage;

/** Compiler-enforced exhaustive handling at every call site, rather than a nullable/boolean return. */
public sealed interface ReservationResult {
    record Reserved(UsagePeriod period) implements ReservationResult {}

    record LimitReached(UsagePeriod period) implements ReservationResult {}
}
