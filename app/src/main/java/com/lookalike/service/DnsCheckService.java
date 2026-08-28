package com.lookalike.service;

import com.lookalike.config.ScoringConstants;
import com.lookalike.dto.CheckResult;
import com.lookalike.dto.ClaimedAuthResults;
import com.lookalike.dto.LiveDnsResult;
import org.springframework.stereotype.Service;
import org.xbill.DNS.Lookup;
import org.xbill.DNS.Record;
import org.xbill.DNS.SimpleResolver;
import org.xbill.DNS.TXTRecord;
import org.xbill.DNS.Type;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Check 2: independent SPF/DMARC verification via live DNS TXT lookups.
 *
 * Direct DNS verification bypasses any forged Authentication-Results headers.
 */
@Service
public class DnsCheckService {

    private static final Duration DNS_TIMEOUT = Duration.ofSeconds(3);

    public List<String> getTxtRecords(String name) {
        List<String> records = new ArrayList<>();
        if (name == null || name.isBlank()) {
            return records;
        }
        try {
            Lookup lookup = new Lookup(name, Type.TXT);
            SimpleResolver resolver = new SimpleResolver();
            resolver.setTimeout(DNS_TIMEOUT);
            lookup.setResolver(resolver);
            lookup.setCache(null);

            Record[] result = lookup.run();
            if (result != null) {
                for (Record r : result) {
                    if (r instanceof TXTRecord txt) {
                        List<String> strings = txt.getStrings();
                        records.add(String.join("", strings));
                    }
                }
            }
        } catch (Exception ignored) {
            // DNS lookup failure returns empty records list
        }
        return records;
    }

    public LiveDnsResult verifySpfDmarc(String domain) {
        if (domain == null || domain.isBlank() || "unknown".equalsIgnoreCase(domain)) {
            return new LiveDnsResult(false, false, null);
        }

        List<String> domainRecords = getTxtRecords(domain);
        boolean spfPresent = domainRecords.stream()
            .anyMatch(r -> r.toLowerCase(Locale.ROOT).startsWith("v=spf1"));

        List<String> dmarcRecords = getTxtRecords("_dmarc." + domain);
        String dmarcTxt = dmarcRecords.stream()
            .filter(r -> r.toLowerCase(Locale.ROOT).startsWith("v=dmarc1"))
            .findFirst()
            .orElse(null);

        String dmarcPolicy = null;
        if (dmarcTxt != null) {
            for (String tag : dmarcTxt.split(";")) {
                String trimmed = tag.trim();
                if (trimmed.toLowerCase(Locale.ROOT).startsWith("p=")) {
                    String[] kv = trimmed.split("=", 2);
                    if (kv.length > 1) {
                        dmarcPolicy = kv[1].trim().toLowerCase(Locale.ROOT);
                    }
                    break;
                }
            }
        }

        return new LiveDnsResult(
            spfPresent,
            dmarcTxt != null,
            dmarcPolicy
        );
    }

    public List<CheckResult> toCheckResults(String domain, LiveDnsResult live) {
        List<CheckResult> checks = new ArrayList<>();

        // SPF check
        checks.add(new CheckResult(
            "SPF record (live DNS)",
            live.spfPresent(),
            ScoringConstants.getWeight("spf_live"),
            live.spfPresent()
                ? domain + " publishes an SPF TXT record"
                : "No SPF TXT record found for " + domain
        ));

        // DMARC check
        boolean dmarcPassed;
        String dmarcDetail;
        if (!live.dmarcPresent()) {
            dmarcPassed = false;
            dmarcDetail = "No DMARC record found at _dmarc." + domain;
        } else if ("none".equalsIgnoreCase(live.dmarcPolicy())) {
            dmarcPassed = false;
            dmarcDetail = domain + " publishes DMARC with policy p=none (monitoring only, not enforced)";
        } else {
            dmarcPassed = true;
            dmarcDetail = domain + " publishes DMARC with policy p=" + live.dmarcPolicy();
        }

        checks.add(new CheckResult(
            "DMARC record & policy (live DNS)",
            dmarcPassed,
            ScoringConstants.getWeight("dmarc_live"),
            dmarcDetail
        ));

        return checks;
    }

    public CheckResult agreementCheck(ClaimedAuthResults claimed, LiveDnsResult live) {
        String name = "Authentication header vs live DNS agreement";
        int weight = ScoringConstants.getWeight("claimed_vs_live_disagreement");

        if (!claimed.headerPresent()) {
            return new CheckResult(
                name,
                true,
                weight,
                "No Authentication-Results header to compare against live DNS"
            );
        }

        List<String> disagreements = new ArrayList<>();
        if ("pass".equalsIgnoreCase(claimed.spf()) && !live.spfPresent()) {
            disagreements.add("header claims spf=pass but no SPF record exists");
        }
        if ("pass".equalsIgnoreCase(claimed.dmarc()) && (!live.dmarcPresent() || "none".equalsIgnoreCase(live.dmarcPolicy()))) {
            disagreements.add("header claims dmarc=pass but DMARC is unenforced or absent");
        }

        if (!disagreements.isEmpty()) {
            return new CheckResult(
                name,
                false,
                weight,
                String.join("; ", disagreements)
            );
        }

        return new CheckResult(
            name,
            true,
            weight,
            "Authentication-Results claims are consistent with live DNS"
        );
    }
}
