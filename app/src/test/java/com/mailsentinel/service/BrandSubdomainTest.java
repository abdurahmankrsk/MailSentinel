package com.mailsentinel.service;

import com.mailsentinel.dto.LookalikeFinding;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The subdomain check and the registrable-label check answer the same question --
 * "does this name a brand?" -- about two positions in the same hostname. They had
 * drifted apart, and the disagreement cut both ways: a false negative on the more
 * convincing phishing hostname, and a false positive that convicted ordinary company
 * subdomains on a solo-red weight.
 *
 * Both scores below were measured against the running app before the fix.
 */
class BrandSubdomainTest {

    private final LookalikeDetector detector = new LookalikeDetector();

    private LookalikeFinding sub(String hostname) {
        return detector.checkBrandSubdomain(hostname);
    }

    @Test
    void brandPlusWordInASubdomainIsFlagged() {
        // Scored 0. The subdomain check wanted the label to be exactly "paypal", and
        // the registrable-label check only ever looks at "evil" -- so this slipped past
        // both, despite reading more convincingly than either shape that did fire.
        assertNotNull(sub("paypal-secure.evil.com"));
        assertNotNull(sub("apple-support.cheap-host.net"));
        assertNotNull(sub("microsoft-login.attacker.io"));
        assertNotNull(sub("amazon-billing.xyz-cdn.com"));
    }

    @Test
    void itCostsAnAttackerNothingWhichIsWhyItMatters() {
        // No domain registration needed at all: any host handing out wildcard
        // subdomains gives this away for free.
        LookalikeFinding finding = sub("paypal-secure.free-hosting.example.com");

        assertNotNull(finding);
        assertEquals("paypal.com", finding.matchedBrand());
        assertTrue(finding.detail().contains("paypal"), finding.detail());
    }

    @Test
    void anOrdinaryCompanySubdomainIsNoLongerConvicted() {
        // Scored 62 -- "High risk", on a weight that convicts alone -- for any company
        // with a shipping, marketing or mail subdomain named after an ordinary word
        // that happens to also be a brand.
        assertNull(sub("ups.acme-logistics.com"));
        assertNull(sub("target.marketing-agency.com"));
        assertNull(sub("outlook.mycompany.com"));
        assertNull(sub("chase.legal-firm.com"));
        assertNull(sub("apple.fruit-growers.com"));
    }

    @Test
    void thoseSameWordsStillFireWithCorroboration() {
        assertNotNull(sub("ups-tracking.evil.com"));
        assertNotNull(sub("apple-support.evil.com"));
        // Corroboration is searched across the whole subdomain, so splitting the lure
        // into its own label changes nothing.
        assertNotNull(sub("ups.tracking.evil.com"));
    }

    @Test
    void theOriginalWholeLabelCaseStillFires() {
        // The behaviour that already worked, which the rewrite must not lose.
        assertNotNull(sub("paypal.evil.com"));
        assertNotNull(sub("paypal.com.verify-account.ru"));
        assertNotNull(sub("netflix.billing-update.xyz"));
    }

    @Test
    void aBrandOnItsOwnDomainIsTheOrdinaryCase() {
        assertNull(sub("mail.google.com"));
        assertNull(sub("login.microsoftonline.com"));
        assertNull(sub("www.amazon.co.uk"));
    }

    @Test
    void aLongBrandNameWrittenWithoutASeparatorIsCaughtInASubdomainToo() {
        assertNotNull(sub("microsoftlogin.evil.com"));
        assertNotNull(sub("facebooksecurity.evil.com"));
    }

    @Test
    void aBrandLabelBuriedInsideALongerWordIsStillNotAMatch() {
        assertNull(sub("mypaypalinvoices.evil.com"), "the short-label rule still applies");
        assertNull(sub("pineapple.evil.com"));
    }

    @Test
    void nothingBlowsUpOnDegenerateInput() {
        assertNull(sub(null));
        assertNull(sub(""));
        assertNull(sub("   "));
        assertNull(sub("evil.com"), "no subdomain to inspect");
        assertNull(sub("192.168.1.1"));
    }

    /**
     * The root cause was two checks answering one question with two rules. This pins
     * that they now agree: the same token gets the same verdict in either position.
     */
    @Test
    void theTwoChecksAgreeAboutTheSameWord() {
        // Distinctive brand: flagged in both positions.
        assertNotNull(sub("paypal-secure.evil.com"));
        assertNotNull(detector.checkBrandInDomain("paypal-secure.com"));

        // Ordinary word without corroboration: ignored in both positions.
        assertNull(sub("apple.fruit-growers.com"));
        assertNull(detector.checkBrandInDomain("apple-orchard.com"));

        // Ordinary word with corroboration: flagged in both positions.
        assertNotNull(sub("apple-support.evil.com"));
        assertNotNull(detector.checkBrandInDomain("apple-support.com"));
    }
}
