package com.mailsentinel.account;

import java.time.Instant;
import java.util.List;

/**
 * Everything this service stores about one account, in the shape it is handed back
 * for a portability request.
 *
 * What is deliberately absent is as much the point as what is present. The password
 * hash, the session token hashes and the encrypted bring-your-own-key ciphertext are
 * all excluded: they are credentials, and an export is a document a user may forward
 * to anyone. The AI key appears only as the label and last four characters the UI
 * already shows.
 *
 * Scans are absent because none are kept -- the footer's promise that anonymous scans
 * are never logged or stored is the reason this export is as short as it is, and
 * saying so here is more useful than an empty array.
 */
public record AccountExportResponse(
        String email,
        Instant accountCreatedAt,
        String plan,
        Instant premiumActivatedAt,
        AiKeySummary aiKey,
        List<UsagePeriodSummary> usagePeriods,
        int activeSessions,
        String scanHistory
) {

    public record AiKeySummary(String label, String last4, String baseUrl, String model, Instant addedAt) {}

    public record UsagePeriodSummary(Instant periodStart, Instant periodEnd, int allowance, int scansUsed) {}
}
