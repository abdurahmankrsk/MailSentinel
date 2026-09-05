package com.mailsentinel.service;

import com.mailsentinel.config.ScoringConstants;
import com.mailsentinel.dto.CheckResult;
import com.mailsentinel.dto.ClaimedAuthResults;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Check 1: parse the Authentication-Results header (RFC 8601) as claimed
 * by the receiving mail server.
 */
@Service
public class AuthHeaderService {

    private static final Pattern RESULT_PATTERN = Pattern.compile(
        "\\b(spf|dkim|dmarc)\\s*=\\s*([a-zA-Z]+)",
        Pattern.CASE_INSENSITIVE
    );

    public ClaimedAuthResults parseAuthenticationResults(String headerValue) {
        if (headerValue == null || headerValue.isBlank()) {
            return ClaimedAuthResults.notPresent();
        }

        Map<String, String> results = new HashMap<>();
        Matcher matcher = RESULT_PATTERN.matcher(headerValue);
        while (matcher.find()) {
            String mechanism = matcher.group(1).toLowerCase(Locale.ROOT);
            String qualifier = matcher.group(2).toLowerCase(Locale.ROOT);
            results.putIfAbsent(mechanism, qualifier);
        }

        return new ClaimedAuthResults(
            true,
            results.get("spf"),
            results.get("dkim"),
            results.get("dmarc")
        );
    }

    private CheckResult makeCheck(String name, String weightKey, boolean headerPresent, String value, String mechanism) {
        if (!headerPresent) {
            return new CheckResult(
                name,
                true,
                ScoringConstants.getWeight(weightKey),
                "No Authentication-Results header present to evaluate " + mechanism
            );
        }
        // A header that exists but stays silent about one mechanism is a different fact
        // from no header at all, and the reader can verify which they are looking at --
        // the email is on screen in front of them. Reporting "no header present" for a
        // message that visibly has one says something false about what they pasted, and
        // hides the mildly interesting part: the receiving server reported on the other
        // mechanisms and not this one.
        if (value == null) {
            return new CheckResult(
                name,
                true,
                ScoringConstants.getWeight(weightKey),
                "Authentication-Results header is present but reports no " + mechanism + " result"
            );
        }
        boolean passed = "pass".equalsIgnoreCase(value);
        return new CheckResult(
            name,
            passed,
            ScoringConstants.getWeight(weightKey),
            "Authentication-Results reports " + mechanism.toLowerCase(Locale.ROOT) + "=" + value
        );
    }

    public List<CheckResult> toCheckResults(ClaimedAuthResults claimed) {
        List<CheckResult> checks = new ArrayList<>();
        checks.add(makeCheck(
            "SPF authentication (claimed)",
            "spf_claimed",
            claimed.headerPresent(),
            claimed.spf(),
            "SPF"
        ));
        checks.add(makeCheck(
            "DKIM authentication (claimed)",
            "dkim_claimed",
            claimed.headerPresent(),
            claimed.dkim(),
            "DKIM"
        ));
        checks.add(makeCheck(
            "DMARC authentication (claimed)",
            "dmarc_claimed",
            claimed.headerPresent(),
            claimed.dmarc(),
            "DMARC"
        ));
        return checks;
    }
}
