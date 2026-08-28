package com.mailsentinel.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class UrlUtilsTest {

    @Test
    void extractsHostnameFromWellFormedUrl() {
        assertEquals("paypal.com", UrlUtils.extractHostname("https://paypal.com/login"));
    }

    @Test
    void extractsHostnameFromBareDomain() {
        assertEquals("bit.ly", UrlUtils.extractHostname("bit.ly/3xamplE"));
    }

    @Test
    void extractsHostnameFromRawUnicodeHomoglyphHost() {
        assertEquals("раypal.com", UrlUtils.extractHostname("http://раypal.com/login"));
    }

    @Test
    void hrefWithUnencodedSpaceInHostStillYieldsAHostname() {
        // java.net.URI throws on this (unlike a browser or mail client), which used to
        // make extractHostname return null and silently drop the link from every
        // downstream check. It must still resolve to a usable hostname.
        String host = UrlUtils.extractHostname("http://evil actor.com/x");
        assertNotNull(host, "malformed-but-real-world href must not be dropped entirely");
        assertEquals("evil actor.com", host);
    }

    @Test
    void hrefWithSpaceInUserinfoStillResolvesToTheRealHost() {
        String host = UrlUtils.extractHostname("http://user:pa ss@evil.com/x");
        assertNotNull(host);
        assertEquals("evil.com", host);
    }

    @Test
    void isIpLiteralRecognizesRawIpv4() {
        assertEquals(true, UrlUtils.isIpLiteral("192.0.2.55"));
    }

    @Test
    void isIpLiteralRejectsRegularDomain() {
        assertEquals(false, UrlUtils.isIpLiteral("paypal.com"));
    }
}
