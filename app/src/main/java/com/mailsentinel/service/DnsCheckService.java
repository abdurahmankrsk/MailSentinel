package com.mailsentinel.service;

import com.mailsentinel.config.ScoringConstants;
import com.mailsentinel.dto.CheckResult;
import com.mailsentinel.dto.ClaimedAuthResults;
import com.mailsentinel.dto.LiveDnsResult;
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

    /**
     * A completed lookup and its records, or an explicit "could not resolve".
     *
     * @param resolved whether DNS actually answered. False means the query timed out,
     *                 the resolver failed, or the server returned SERVFAIL -- none of
     *                 which are statements about what the domain publishes.
     */
    public record TxtLookup(List<String> records, boolean resolved) {
        static TxtLookup unresolved() {
            return new TxtLookup(List.of(), false);
        }
    }

    public TxtLookup getTxtRecords(String name) {
        if (name == null || name.isBlank()) {
            return TxtLookup.unresolved();
        }
        try {
            Lookup lookup = new Lookup(name, Type.TXT);
            SimpleResolver resolver = new SimpleResolver();
            resolver.setTimeout(DNS_TIMEOUT);
            lookup.setResolver(resolver);
            lookup.setCache(null);

            Record[] result = lookup.run();
            int status = lookup.getResult();

            // HOST_NOT_FOUND and TYPE_NOT_FOUND are real, authoritative answers meaning
            // "no such record" -- those count as resolved. TRY_AGAIN (timeout/SERVFAIL)
            // and UNRECOVERABLE are failures to get an answer at all.
            boolean resolved = status == Lookup.SUCCESSFUL
                || status == Lookup.HOST_NOT_FOUND
                || status == Lookup.TYPE_NOT_FOUND;
            if (!resolved) {
                return TxtLookup.unresolved();
            }

            List<String> records = new ArrayList<>();
            if (result != null) {
                for (Record r : result) {
                    if (r instanceof TXTRecord txt) {
                        List<String> strings = txt.getStrings();
                        records.add(String.join("", strings));
                    }
                }
            }
            return new TxtLookup(records, true);
        } catch (Exception e) {
            return TxtLookup.unresolved();
        }
    }

    public LiveDnsResult verifySpfDmarc(String domain) {
        if (domain == null || domain.isBlank()) {
            // No domain to ask about is a parse outcome, not a DNS outcome. Marking
            // both lookups *unresolved* routes it down the existing neutral path, so
            // the checks pass rather than scoring the user 20 points for pasting a
            // message body without its headers -- the same care already taken for a
            // lookup that times out.
            return new LiveDnsResult(false, false, false, false, null);
        }

        TxtLookup domainLookup = getTxtRecords(domain);
        boolean spfPresent = domainLookup.records().stream()
            .anyMatch(r -> r.toLowerCase(Locale.ROOT).startsWith("v=spf1"));

        TxtLookup dmarcLookup = getTxtRecords("_dmarc." + domain);
        String dmarcTxt = dmarcLookup.records().stream()
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
            domainLookup.resolved(),
            dmarcTxt != null,
            dmarcLookup.resolved(),
            dmarcPolicy
        );
    }

    /**
     * @param domain the sending domain, or null when none could be parsed from the
     *               message -- which is a distinct outcome from a lookup that failed,
     *               and is worded as such rather than naming a placeholder domain
     */
    public List<CheckResult> toCheckResults(String domain, LiveDnsResult live) {
        List<CheckResult> checks = new ArrayList<>();
        boolean noSenderDomain = domain == null || domain.isBlank();
        String noSenderDetail = "No sender domain could be read from this message, so SPF and DMARC "
            + "were not checked. If you pasted only the message body, use \"Show original\" "
            + "in your mail client and include the headers.";

        // SPF check. An unresolved lookup passes: we have no finding, and scoring a
        // failed query as though the domain published nothing punishes legitimate
        // senders for our own transient network trouble.
        checks.add(new CheckResult(
            "SPF record (live DNS)",
            !live.spfResolved() || live.spfPresent(),
            ScoringConstants.getWeight("spf_live"),
            noSenderDomain
                ? noSenderDetail
                : !live.spfResolved()
                    ? "Could not complete an SPF lookup for " + domain + "; scored as neutral, not as a missing record"
                    : live.spfPresent()
                        ? domain + " publishes an SPF TXT record"
                        : "No SPF TXT record found for " + domain
        ));

        // DMARC check
        boolean dmarcPassed;
        String dmarcDetail;
        if (noSenderDomain) {
            dmarcPassed = true;
            dmarcDetail = noSenderDetail;
        } else if (!live.dmarcResolved()) {
            dmarcPassed = true;
            dmarcDetail = "Could not complete a DMARC lookup for " + domain
                + "; scored as neutral, not as a missing record";
        } else if (!live.dmarcPresent()) {
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

        // Only a lookup that actually completed can contradict the header. Comparing
        // against a failed query would manufacture a disagreement out of a timeout.
        List<String> disagreements = new ArrayList<>();
        if (live.spfResolved() && "pass".equalsIgnoreCase(claimed.spf()) && !live.spfPresent()) {
            disagreements.add("header claims spf=pass but no SPF record exists");
        }
        if (live.dmarcResolved() && "pass".equalsIgnoreCase(claimed.dmarc())
                && (!live.dmarcPresent() || "none".equalsIgnoreCase(live.dmarcPolicy()))) {
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
