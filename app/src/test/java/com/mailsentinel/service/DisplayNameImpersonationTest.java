package com.mailsentinel.service;

import com.mailsentinel.dto.LookalikeFinding;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The display-name check is the third place that asks "does this name a brand?", and
 * it was the last one still asking without the common-word rule the other two use.
 *
 * That mattered once the watch list grew to carry brands whose labels are everyday
 * words -- apple, chase, target, outlook. Measured against the running app before the
 * fix, on mail with no other problem at all:
 *
 *   "Apple Orchard Farm" <hello@orchardfarm.com>     65  ("treat this as hostile")
 *   "Outlook Tips Weekly" <news@techblog.com>        65
 *   "Sales Target Report" <reports@mycompany.com>    56
 *   "Chase the Deal" <news@shopdeals.com>            45
 */
class DisplayNameImpersonationTest {

    private final LookalikeDetector detector = new LookalikeDetector();

    private LookalikeFinding check(String displayName, String senderDomain) {
        return detector.checkDisplayNameImpersonation(displayName, senderDomain);
    }

    @Test
    void anEverydayWordThatHappensToBeABrandIsNotImpersonationOnItsOwn() {
        assertNull(check("Apple Orchard Farm", "orchardfarm.com"));
        assertNull(check("Sales Target Report", "mycompany.com"));
        assertNull(check("Chase the Deal", "shopdeals.com"));
        assertNull(check("Outlook Tips Weekly", "techblog.com"));
        assertNull(check("Start-ups Digest", "newsletter.com"));
    }

    @Test
    void thoseSameWordsAreImpersonationWhenALureWordBacksThemUp() {
        // "support", "billing" and "alert" are what a phishing display name adds and a
        // farm shop does not.
        assertNotNull(check("Apple Support", "random-host.com"));
        assertNotNull(check("Chase Account Alert", "not-a-bank.com"));
        assertNotNull(check("Target Billing", "unrelated.com"));
        assertNotNull(check("Outlook Security Update", "unrelated.com"));
    }

    @Test
    void aDistinctiveBrandNameStillConvictsOnItsOwn() {
        // Unchanged: these labels are nobody's ordinary vocabulary, so naming one from
        // an unrelated domain is the whole attack and needs no corroboration.
        assertNotNull(check("Microsoft 365", "m365-account-security.com"));
        assertNotNull(check("PayPal", "evil-domain.ru"));
        assertNotNull(check("Netflix", "unrelated.com"));
    }

    @Test
    void aLongBrandNameIsStillMatchedWithoutSeparators() {
        LookalikeFinding finding = check("Bank of America Alerts", "secure-notice.com");

        assertNotNull(finding);
        assertEquals("bankofamerica.com", finding.matchedBrand());
    }

    @Test
    void aDisplayNameBackedUpByItsOwnSendingDomainPasses() {
        assertNull(check("GitHub", "github.com"));
        assertNull(check("Amazon.co.uk", "amazon.co.uk"), "a regional domain still backs up its own brand");
        // The common-word brands too, when the mail really is from them.
        assertNull(check("Apple Support", "apple.com"));
        assertNull(check("Chase Account Alert", "chase.com"));
    }

    @Test
    void aBrandNameInsideALongerWordIsNotAMatch() {
        assertNull(check("Pineapple Groups", "pineapple-groups.com"));
    }

    @Test
    void nothingBlowsUpOnDegenerateInput() {
        assertNull(check(null, "example.com"));
        assertNull(check("", "example.com"));
        assertNull(check("   ", "example.com"));
        assertNull(check("Ordinary Newsletter", null));
        assertNull(check("Ordinary Newsletter", ""));
    }

    /**
     * The point of the fix: all three places that ask "does this name a brand?" now
     * answer the same way about the same word.
     */
    @Test
    void theDisplayNameCheckAgreesWithTheHostnameChecks() {
        // Ordinary word, no corroboration: ignored everywhere.
        assertNull(check("Apple Orchard Farm", "orchardfarm.com"));
        assertNull(detector.checkBrandInDomain("apple-orchard.com"));
        assertNull(detector.checkBrandSubdomain("apple.fruit-growers.com"));

        // Ordinary word plus a lure: flagged everywhere.
        assertNotNull(check("Apple Support", "random-host.com"));
        assertNotNull(detector.checkBrandInDomain("apple-support.com"));
        assertNotNull(detector.checkBrandSubdomain("apple-support.evil.com"));
    }
}
