package com.mailsentinel.service;

import com.mailsentinel.dto.LookalikeFinding;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Edit distance used to compare whole domains against a flat two-edit budget. That one
 * choice was wrong in three directions at once, every case below measured against the
 * running app before the fix:
 *
 *   false positive  bbc.com       65  "2 characters from hsbc.com"
 *                   pdf.com       65  dpd.com      idea.com    65  ikea.com
 *                   nordic.com    65  nordea.com   irs.com    100  ups.com
 *   double count    github.io    100  edit distance 65 + TLD swap 45, one fact
 *                   ups.ca       100  target.ca 100   adobe.io 100
 *   false negative  paypall.net    0  amazom.org 0   gooogle.de 0
 *                   netflixx.io    0  microsfot.co.uk 0
 */
class EditDistanceTest {

    private final LookalikeDetector detector = new LookalikeDetector();

    private LookalikeFinding distance(String domain) {
        return detector.checkEditDistance(domain);
    }

    @Test
    void unrelatedShortNamesAreNoLongerCalledLookalikes() {
        // Two edits in a three- or four-letter name rewrite most of it.
        assertNull(distance("bbc.com"), "one of the world's largest sites is not a typo of hsbc.com");
        assertNull(distance("pdf.com"));
        assertNull(distance("dpa.com"));
        assertNull(distance("npd.com"));
        assertNull(distance("dhd.com"));
        assertNull(distance("idea.com"));
        assertNull(distance("ike.com"));
        assertNull(distance("irs.com"));
        assertNull(distance("nordic.com"), "two edits in a six-letter name is a different word");
    }

    @Test
    void aTyposquatOnAnotherTopLevelDomainIsNowCaught() {
        // The TLD used to spend the edit budget, so a one-character name typo on any TLD
        // other than the brand's own scored nothing at all.
        assertEquals("paypal.com", distance("paypall.net").matchedBrand());
        assertEquals("amazon.com", distance("amazom.org").matchedBrand());
        assertEquals("google.com", distance("gooogle.de").matchedBrand());
        assertEquals("netflix.com", distance("netflixx.io").matchedBrand());
        assertEquals("microsoft.com", distance("microsfot.co.uk").matchedBrand());
    }

    @Test
    void theSameNameOnAnotherTopLevelDomainIsLeftToTheTldSwapCheck() {
        // Previously scored twice -- here as a typo and again as a TLD swap -- for 100.
        assertNull(distance("github.io"));
        assertNull(distance("gitlab.io"));
        assertNull(distance("target.ca"));
        assertNotNull(detector.checkTldSwap("github.io"), "the TLD-swap check still reports it, once");
        assertNotNull(detector.checkTldSwap("target.ca"));
    }

    @Test
    void theTyposquatsThatAlreadyWorkedStillDo() {
        assertNotNull(distance("paypa1.com"));
        assertNotNull(distance("amazom.com"));
        assertNotNull(distance("gooogle.com"));
        assertNotNull(distance("santand3r.com"));
        assertNotNull(distance("wh4tsapp.com"));
        assertNotNull(distance("bookinq.com"));
    }

    @Test
    void theAllowanceScalesWithTheLengthOfTheName() {
        // Under a quarter of the name: one edit needs five characters, two need nine.
        assertNull(distance("ikex.com"), "one edit in four letters is a quarter of the name");
        assertNotNull(distance("appie.com"), "one edit in five letters is a plausible typo");
        assertNotNull(distance("microsfot.com"), "two edits in nine letters is a plausible typo");
    }

    @Test
    void shortBrandsAreStillCoveredByTheOtherTechniques() {
        // Losing edit distance on three-letter names is the deliberate cost; character
        // substitution is what actually catches dh1.com.
        assertNull(distance("dh1.com"));
        assertNotNull(detector.checkCharSubstitution("dh1.com"));
    }

    @Test
    void realBrandOwnedDomainsTheTldSwapCheckMisflaggedAreNowRecognised() {
        // Each of these scored 45 as a "TLD swap" of its own brand.
        for (String owned : new String[] {"discord.gg", "apple.news", "ups.ca", "adobe.io"}) {
            assertTrue(detector.analyzeDomain(owned).isEmpty(),
                    owned + " belongs to the brand it was flagged as imitating");
        }
    }
}
