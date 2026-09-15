package com.mailsentinel.service;

import com.mailsentinel.dto.LookalikeFinding;
import org.junit.jupiter.api.Test;

import java.net.IDN;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Script mixing is judged one hostname label at a time, across the whole hostname.
 *
 * It used to be judged over the registrable domain as a single string, which was wrong
 * in both directions -- both measured against the running app before the fix:
 *
 *   false positive  東京.jp, ελλάδα.gr, яндекс.ru, 中国.com   70  the Latin TLD read as a second script
 *   evasion         paypal.evil.com                            62
 *                   pаypаl.evil.com (Cyrillic а)                0  the homoglyph made it score lower
 *
 * Inputs use Java unicode escapes so the source file's encoding cannot change what is
 * being tested -- an earlier probe of exactly this code was misled by mangled bytes.
 */
class HomoglyphTest {

    private static final String TOKYO = "東京";                             // 東京
    private static final String GREECE = "ελλάδα";    // ελλάδα
    private static final String YANDEX = "яндекс";    // яндекс
    private static final String CHINA = "中国";                             // 中国
    private static final char CYRILLIC_A = 'а';                                 // а

    private final LookalikeDetector detector = new LookalikeDetector();

    private LookalikeFinding homoglyph(String hostname) {
        return detector.checkHomoglyph(hostname);
    }

    @Test
    void anInternationalisedDomainOnALatinTldIsNotMixedScript() {
        assertNull(homoglyph(TOKYO + ".jp"), "Tokyo");
        assertNull(homoglyph(GREECE + ".gr"), "Greece");
        assertNull(homoglyph(YANDEX + ".ru"), "Yandex");
        assertNull(homoglyph(CHINA + ".com"), "China");
    }

    @Test
    void theSameDomainsArriveAsPunycodeAndAreStillFine() {
        // What a mail client or a copied link usually carries is the xn-- form.
        assertNull(homoglyph(IDN.toASCII(TOKYO + ".jp")));
        assertNull(homoglyph(IDN.toASCII(YANDEX + ".ru")));
    }

    @Test
    void differentScriptsInDifferentLabelsIsOrdinary() {
        assertNull(homoglyph("mail." + YANDEX + ".рф"), "a Latin subdomain on a Cyrillic domain");
        assertNull(homoglyph("www." + TOKYO + ".jp"));
        assertNull(homoglyph("münchen.de"), "an umlaut is still Latin script");
    }

    @Test
    void aHomoglyphInASubdomainIsNoLongerInvisible() {
        // Scored 0 while the plain-Latin paypal.evil.com scored 62.
        assertNotNull(homoglyph("p" + CYRILLIC_A + "yp" + CYRILLIC_A + "l.evil.com"));
        assertNotNull(homoglyph(CYRILLIC_A + "pple.evil.com"));
        assertNotNull(homoglyph(CYRILLIC_A + "pple-support.evil.com"));
    }

    @Test
    void theFindingNamesTheLabelThatMixesScripts() {
        String label = "p" + CYRILLIC_A + "yp" + CYRILLIC_A + "l";
        LookalikeFinding finding = homoglyph(label + ".evil.com");

        assertTrue(finding.detail().contains("\"" + label + "\""), finding.detail());
        assertTrue(finding.detail().contains("CYRILLIC") && finding.detail().contains("LATIN"), finding.detail());
    }

    @Test
    void addingAHomoglyphNeverLowersTheScore() {
        // The property the evasion broke: disguising a brand must not make it safer.
        assertFalse(detector.analyzeDomain("p" + CYRILLIC_A + "yp" + CYRILLIC_A + "l.evil.com").isEmpty());
        assertFalse(detector.analyzeDomain("paypal.evil.com").isEmpty());
    }

    @Test
    void brandImitationInTheRegistrableDomainIsStillNamed() {
        assertEquals("apple.com", homoglyph(CYRILLIC_A + "pple.com").matchedBrand());
        assertEquals("paypal.com", homoglyph("p" + CYRILLIC_A + "yp" + CYRILLIC_A + "l.com").matchedBrand());
        assertEquals("apple.com", homoglyph("xn--pple-43d.com").matchedBrand(), "the punycode form from the QA report");
    }

    @Test
    void mixingWithinAnUnbrandedNameIsStillFlagged() {
        LookalikeFinding finding = homoglyph("ex" + CYRILLIC_A + "mple.org");

        assertNotNull(finding);
        assertEquals("-", finding.matchedBrand(), "no brand imitated, just scripts mixed inside one name");
    }

    @Test
    void aBrandsOwnDomainIsLeftAlone() {
        assertNull(homoglyph("mail.google.com"));
        assertNull(homoglyph("www.amazon.co.uk"));
    }

    @Test
    void nothingBlowsUpOnDegenerateInput() {
        assertNull(homoglyph(null));
        assertNull(homoglyph(""));
        assertNull(homoglyph("   "));
        assertNull(homoglyph("192.168.1.1"));
    }
}
