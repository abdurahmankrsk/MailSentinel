package com.mailsentinel.service;

import com.mailsentinel.config.BrandConstants;
import com.mailsentinel.dto.LookalikeFinding;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The sixth technique: a brand name inside the registrable label.
 *
 * Every domain in the first test scored a clean 0 before this existed -- they are the
 * QA report's own table, and between them the single most common real phishing shape.
 */
class BrandInDomainTest {

    private final LookalikeDetector detector = new LookalikeDetector();

    private LookalikeFinding check(String hostname) {
        return detector.checkBrandInDomain(hostname);
    }

    @Test
    void brandPlusWordDomainsAreFlagged() {
        String[] flagged = {
                "paypal-secure.com",
                "apple-support.com",
                "microsoft-login.com",
                "amazon-billing.com",
                "netflix-payment.com",
                "chase-verify.com",
                "secure-paypal.com",
                "dhl-tracking-parcel.com",
        };
        for (String domain : flagged) {
            assertNotNull(check(domain), domain + " scored 0 before this technique existed");
        }
    }

    @Test
    void theFindingNamesTheBrandItImitates() {
        LookalikeFinding finding = check("paypal-secure.com");

        assertEquals("brand_in_domain", finding.technique());
        assertEquals("paypal.com", finding.matchedBrand());
        assertTrue(finding.detail().contains("paypal-secure.com"), finding.detail());
    }

    @Test
    void aSubdomainDoesNotHideTheRegistrableLabel() {
        // The technique reads the eTLD+1, so dressing it up changes nothing.
        assertNotNull(check("login.paypal-secure.com"));
        assertNotNull(check("www.amazon-billing.co.uk"));
    }

    @Test
    void realBrandDomainsAreNeverFlagged() {
        // Including the ones whose own name embeds a shorter brand label, which is
        // exactly the shape this technique looks for -- they are safe only because
        // brand-domains.txt lists them as owned.
        String[] legitimate = {
                "paypal.com", "amazon.co.uk", "google.de", "apple.com",
                "microsoftonline.com", "facebookmail.com", "cloudflare-dns.com",
                "lloydsbankinggroup.com", "capitalone360.com", "santanderbank.com",
        };
        for (String domain : legitimate) {
            assertNull(check(domain), domain + " is a real brand domain and must never be flagged");
        }
    }

    @Test
    void aBareBrandNameOnTheWrongTldIsLeftToTheTldSwapCheck() {
        // paypal.co is a TLD swap and checkTldSwap reports it. Firing here as well
        // would score one fact twice, which is the stacking bug that made a genuine
        // amazon.co.uk email read 100/100.
        assertNull(check("paypal.co"));
        assertNull(check("netflix.org"));
        assertNotNull(detector.checkTldSwap("paypal.co"), "the TLD-swap check still owns this case");
    }

    @Test
    void anOrdinaryWordThatHappensToBeABrandNeedsCorroboration() {
        // The false positives that would make this technique cost more than it earns.
        // "ups" sits inside start-ups, "chase" and "apple" are everyday words.
        assertNull(check("start-ups.com"));
        assertNull(check("pop-ups-blocker.com"));
        assertNull(check("apple-orchard.com"));
        assertNull(check("paper-chase.com"));
        assertNull(check("target-practice.com"));
    }

    @Test
    void thoseSameBrandsStillFireAlongsideALureWord() {
        // "support" and "verify" are what a phishing domain adds and a greengrocer
        // does not, so the corroboration rule costs no real detections.
        assertNotNull(check("apple-support.com"));
        assertNotNull(check("chase-verify.com"));
        assertNotNull(check("ups-tracking-update.com"));
        assertNotNull(check("target-account-alert.com"));
    }

    @Test
    void aBrandLabelBuriedInsideALongerWordIsNotAMatch() {
        // The MIN_COMPACT_LABEL_LENGTH rule, which already stops "Pineapple" reading as
        // Apple: a short brand label is only matched as a whole token.
        assertNull(check("pineapple.com"));
        assertNull(check("grapples.com"));
        assertNull(check("paypalette.com"));
    }

    @Test
    void aLongBrandLabelIsMatchedWithoutASeparator() {
        // Long labels do not appear inside unrelated words by accident, so
        // concatenation is safe to match for them -- and it is the obvious way to
        // sidestep a hyphen-only rule.
        assertNotNull(check("microsoftlogin.com"));
        assertNotNull(check("facebooksecurity.com"));
        assertNotNull(check("bankofamericaalerts.com"));
    }

    @Test
    void underscoresSeparateTokensJustAsHyphensDo() {
        assertNotNull(check("paypal_secure.com"));
    }

    @Test
    void nothingBlowsUpOnDegenerateInput() {
        assertNull(check(null));
        assertNull(check(""));
        assertNull(check("   "));
        assertNull(check("localhost"));
        assertNull(check("192.168.1.1"));
    }

    @Test
    void theWatchListIsLoadedFromTheResourceFile() {
        // If brand-domains.txt failed to load, every check above would pass vacuously
        // by finding nothing -- which is the worst failure mode this codebase has.
        assertTrue(BrandConstants.brandCount() > 60,
                "expected the expanded watch list, got " + BrandConstants.brandCount());
        assertTrue(BrandConstants.BRAND_SET.contains("paypal.com"));
        assertTrue(BrandConstants.BRAND_SET.contains("amazon.co.uk"));
        // The European institutions the list was missing while the product priced in EUR.
        assertTrue(BrandConstants.BRAND_SET.contains("santander.com"));
        assertTrue(BrandConstants.BRAND_SET.contains("barclays.co.uk"));
        assertTrue(BrandConstants.BRAND_SET.contains("revolut.com"));
    }

    /**
     * The other half of the coverage gap: convincing typosquats of brands that simply
     * were not on the list. These are the QA report's own examples, and every one of
     * them scored 0 -- reported to the user as "Low risk", which for a security tool is
     * worse than admitting the gap. They are caught by the existing techniques now that
     * the brands they imitate are watched.
     */
    @Test
    void typosquatsOfNewlyWatchedBrandsAreNoLongerSilentlyClean() {
        String[] squats = {
                "santand3r.com",        // Santander
                "barc1ays.com",         // Barclays
                "revo1ut.com",          // Revolut
                "wh4tsapp.com",         // WhatsApp
                "bookinq.com",          // Booking.com
                "steamcornmunity.com",  // Steam, via the rn/m substitution
        };
        for (String domain : squats) {
            assertTrue(!detector.analyzeDomain(domain).isEmpty(),
                    domain + " reported no signal at all, which reads to a user as a clean bill of health");
        }
    }

    @Test
    void theWatchListOrderIsStableAcrossReads() {
        // The brand named in a finding comes from iteration order, so it must not vary
        // between JVM runs -- the reason this is a LinkedHashMap and not Map.copyOf.
        assertEquals("paypal.com", BrandConstants.BRAND_DOMAINS.get(0));
        assertEquals(BrandConstants.BRAND_DOMAINS, BrandConstants.BRAND_DOMAINS.stream().toList());
    }
}
