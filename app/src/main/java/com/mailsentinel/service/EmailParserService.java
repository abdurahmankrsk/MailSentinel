package com.mailsentinel.service;

import com.mailsentinel.dto.ParsedEmail;
import jakarta.mail.BodyPart;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turn a raw pasted email (headers + MIME body) into structured components:
 * Authentication-Results header, sender domain, plaintext body, and HTML body.
 *
 * Uses Jakarta Mail for robust RFC 5322 MIME parsing with resilient fallbacks.
 */
@Service
public class EmailParserService {

    private static final Pattern FROM_EMAIL_PATTERN = Pattern.compile(
        "(?:<|\\b)([A-Za-z0-9._%+-]+)@([A-Za-z0-9.\\u0080-\\uFFFF-]+)(?:>|\\b)"
    );

    public ParsedEmail parseEmail(String raw) {
        if (raw == null || raw.isBlank()) {
            return new ParsedEmail(null, null, null, null);
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

            // 2. Sender Domain from From header
            String senderDomain = null;
            try {
                String[] fromHeaders = message.getHeader("From");
                if (fromHeaders != null && fromHeaders.length > 0) {
                    String fromHeader = String.join(", ", fromHeaders);
                    Matcher matcher = FROM_EMAIL_PATTERN.matcher(fromHeader);
                    if (matcher.find()) {
                        senderDomain = matcher.group(2).trim().toLowerCase(Locale.ROOT);
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
                acc.textBody,
                acc.htmlBody
            );
        } catch (Exception e) {
            // Fallback for non-compliant or partial raw text
            return new ParsedEmail(null, null, raw, null);
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
