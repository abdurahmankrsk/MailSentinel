package com.mailsentinel.service;

import com.mailsentinel.dto.ParsedEmail;
import jakarta.mail.Address;
import jakarta.mail.BodyPart;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Properties;

/**
 * Turn a raw pasted email (headers + MIME body) into structured components:
 * Authentication-Results header, sender domain, plaintext body, and HTML body.
 *
 * Uses Jakarta Mail for robust RFC 5322 MIME parsing with resilient fallbacks.
 */
@Service
public class EmailParserService {

    public ParsedEmail parseEmail(String raw) {
        if (raw == null || raw.isBlank()) {
            return new ParsedEmail(null, null, null, null, null);
        }

        try {
            Properties props = new Properties();
            props.put("mail.mime.allowutf8", "true");
            props.put("mail.mime.address.strict", "false");
            Session session = Session.getInstance(props);
            ByteArrayInputStream input = new ByteArrayInputStream(raw.getBytes(StandardCharsets.UTF_8));
            MimeMessage message = new MimeMessage(session, input);

            // 1. Authentication-Results header
            String authResults = null;
            try {
                String[] authHeaders = message.getHeader("Authentication-Results");
                if (authHeaders != null && authHeaders.length > 0) {
                    authResults = String.join("; ", authHeaders);
                }
            } catch (Exception ignored) {}

            // 2. Sender domain from the From header. Uses Jakarta Mail's own address
            // parser rather than a hand-rolled regex, so a quoted display name that
            // itself looks like an address -- e.g.
            //   From: "security@paypal.com" <phisher@evil-domain.ru>
            // -- can't shadow the real sending address: getFrom() understands RFC 5322
            // quoting and always resolves to the bracketed address, not the first
            // "word@word" substring encountered while scanning the raw header text.
            String senderDomain = null;
            String senderDisplayName = null;
            try {
                Address[] fromAddresses = message.getFrom();
                if (fromAddresses != null && fromAddresses.length > 0
                        && fromAddresses[0] instanceof InternetAddress internetAddress) {
                    String address = internetAddress.getAddress();
                    int at = address == null ? -1 : address.lastIndexOf('@');
                    if (at >= 0 && at < address.length() - 1) {
                        senderDomain = address.substring(at + 1).trim().toLowerCase(Locale.ROOT);
                    }
                    // The display name is the half a mail client shows most prominently and
                    // the half an attacker controls freely, so it's captured separately for
                    // the impersonation check rather than folded into the address above.
                    String personal = internetAddress.getPersonal();
                    if (personal != null && !personal.isBlank()) {
                        senderDisplayName = personal.trim();
                    }
                }
            } catch (Exception ignored) {}

            // 3. Extract text and HTML body parts
            BodyAccumulator acc = new BodyAccumulator();
            extractBody(message, acc);

            // Fallback for plaintext body if MIME parts were not structured
            if (acc.textBody == null && acc.htmlBody == null) {
                int headerEnd = raw.indexOf("\n\n");
                if (headerEnd == -1) headerEnd = raw.indexOf("\r\n\r\n");
                if (headerEnd != -1) {
                    acc.textBody = raw.substring(headerEnd).trim();
                } else {
                    acc.textBody = raw.trim();
                }
            }

            return new ParsedEmail(
                authResults,
                senderDomain,
                senderDisplayName,
                acc.textBody,
                acc.htmlBody
            );
        } catch (Exception e) {
            // Fallback for non-compliant or partial raw text
            return new ParsedEmail(null, null, null, raw, null);
        }
    }

    private static class BodyAccumulator {
        String textBody = null;
        String htmlBody = null;
    }

    private void extractBody(Part part, BodyAccumulator acc) {
        try {
            if (part.isMimeType("text/plain")) {
                if (acc.textBody == null) {
                    Object content = part.getContent();
                    acc.textBody = content != null ? content.toString() : null;
                }
            } else if (part.isMimeType("text/html")) {
                if (acc.htmlBody == null) {
                    Object content = part.getContent();
                    acc.htmlBody = content != null ? content.toString() : null;
                }
            } else if (part.isMimeType("multipart/*")) {
                Object content = part.getContent();
                if (content instanceof Multipart multipart) {
                    for (int i = 0; i < multipart.getCount(); i++) {
                        BodyPart bodyPart = multipart.getBodyPart(i);
                        extractBody(bodyPart, acc);
                    }
                }
            }
        } catch (Exception ignored) {
            // Suppress body extraction errors on individual parts
        }
    }
}
