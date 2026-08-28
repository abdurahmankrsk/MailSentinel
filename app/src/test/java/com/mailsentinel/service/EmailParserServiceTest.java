package com.mailsentinel.service;

import com.mailsentinel.dto.ParsedEmail;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EmailParserServiceTest {

    private final EmailParserService parser = new EmailParserService();

    private String email(String fromHeader) {
        return "From: " + fromHeader + "\r\n"
            + "To: user@example.com\r\n"
            + "Subject: test\r\n"
            + "\r\n"
            + "Body text.";
    }

    @Test
    void plainAddressResolvesToItsOwnDomain() {
        ParsedEmail parsed = parser.parseEmail(email("PayPal Security <security@paypa1.com>"));
        assertEquals("paypa1.com", parsed.senderDomain());
    }

    @Test
    void quotedDisplayNameCannotShadowTheRealSendingDomain() {
        // A quoted display name that looks like a trusted address is fully RFC 5322
        // legal and a well-known spoofing trick. The real sending domain -- the one
        // in angle brackets -- must win, not the first "word@word" text encountered.
        ParsedEmail parsed = parser.parseEmail(email("\"security@paypal.com\" <phisher@evil-domain.ru>"));
        assertEquals("evil-domain.ru", parsed.senderDomain());
    }

    @Test
    void unquotedLookalikeTextBeforeTheRealAddressStillResolvesToRealDomain() {
        ParsedEmail parsed = parser.parseEmail(email("Account-Update support@paypal.com <phisher@evil-domain.ru>"));
        assertEquals("evil-domain.ru", parsed.senderDomain());
    }

    @Test
    void bareAddressWithoutDisplayNameStillParses() {
        ParsedEmail parsed = parser.parseEmail(email("security@paypal.com <phisher@evil-domain.ru>"));
        assertEquals("evil-domain.ru", parsed.senderDomain());
    }
}
