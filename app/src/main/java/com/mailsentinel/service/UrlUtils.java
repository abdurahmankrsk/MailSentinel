package com.mailsentinel.service;

import com.google.common.net.InetAddresses;
import com.google.common.net.InternetDomainName;

import java.net.IDN;
import java.net.URI;
import java.util.Locale;

/**
 * Shared domain and URL parsing helpers.
 *
 * Uses Guava's InternetDomainName for registrable-domain (eTLD+1) parsing
 * against the Public Suffix List so subdomains and multi-part suffixes
 * (e.g. .co.uk, .com.ru) are handled accurately.
 */
public final class UrlUtils {
    private UrlUtils() {}

    /**
     * Ensure a URL has a scheme so URI parser parses the host correctly.
     */
    public static String normalizeUrl(String raw) {
        if (raw == null) {
            return "";
        }
        String trimmed = raw.trim();
        if (!trimmed.contains("://")) {
            return "http://" + trimmed;
        }
        return trimmed;
    }

    /**
     * Pull just the hostname out of a URL (or bare domain) string.
     */
    public static String extractHostname(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            return null;
        }
        try {
            URI uri = URI.create(normalizeUrl(rawUrl));
            String host = uri.getHost();
            if (host != null && !host.isBlank()) {
                return host.toLowerCase(Locale.ROOT);
            }
            // Fallback for bare domains where URI host might be null
            String authority = uri.getAuthority();
            if (authority != null) {
                int colonIdx = authority.indexOf(':');
                String h = colonIdx >= 0 ? authority.substring(0, colonIdx) : authority;
                return h.toLowerCase(Locale.ROOT);
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Return the eTLD+1, e.g. mail.google.com -> google.com, paypal.com.verify-account.ru -> verify-account.ru.
     * Supports both ASCII and Unicode/Punycode hostnames.
     */
    public static String registrableDomain(String hostname) {
        if (hostname == null || hostname.isBlank()) {
            return "";
        }
        String cleanHost = hostname.trim().toLowerCase(Locale.ROOT);
        if (cleanHost.startsWith("[") && cleanHost.endsWith("]")) {
            cleanHost = cleanHost.substring(1, cleanHost.length() - 1);
        }

        try {
            String asciiHost = IDN.toASCII(cleanHost);
            if (InternetDomainName.isValid(asciiHost)) {
                InternetDomainName idn = InternetDomainName.from(asciiHost);
                if (idn.isUnderPublicSuffix()) {
                    String topPrivateAscii = idn.topPrivateDomain().toString();
                    return IDN.toUnicode(topPrivateAscii).toLowerCase(Locale.ROOT);
                }
            }
        } catch (Exception ignored) {
            // Fall through to returning raw cleanHost
        }
        return cleanHost;
    }

    /**
     * Check if this host is a raw IP address (IPv4 or IPv6) instead of a domain name.
     */
    public static boolean isIpLiteral(String hostname) {
        if (hostname == null || hostname.isBlank()) {
            return false;
        }
        String clean = hostname.trim();
        if (clean.startsWith("[") && clean.endsWith("]")) {
            clean = clean.substring(1, clean.length() - 1);
        }
        try {
            return InetAddresses.isInetAddress(clean);
        } catch (Exception e) {
            return false;
        }
    }
}
