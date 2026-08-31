package com.mailsentinel.ai;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Locale;

/**
 * The egress guard for bring-your-own-key endpoints. A user hands us a base URL and
 * this server then makes a request to it -- first to validate the key, and again on
 * every scan that uses it. Unguarded, that is a server-side request forgery
 * primitive: aim it at 127.0.0.1, 10.0.0.0/8, or 169.254.169.254 and the endpoint
 * becomes an internal port scanner, and on the cloud hosts the README targets, a
 * route to the instance-metadata service and its credentials.
 *
 * Two rules, both cheap:
 *
 *  1. https only. Plaintext http to a third party would leak the user's own API key
 *     on the wire anyway, so rejecting it costs nothing and removes http://-only
 *     internal services from reach in the same move.
 *  2. Every address the host resolves to must be publicly routable. Checking every
 *     returned address, not just the first, matters: a hostname that resolves to a
 *     public A record and a private AAAA record would otherwise pass here and be
 *     connected to over IPv6.
 *
 * Called at save time AND at scan time (see AiKeyService and AiAnalysisService).
 * The second call is the point: a DNS name that is public when the key is saved and
 * private when it is used -- DNS rebinding -- walks straight through a save-time-only
 * check. Re-resolving per request narrows that to the window between this lookup and
 * the connection the HTTP client makes from its own lookup, which closing entirely
 * would mean pinning the address and overriding the Host header inside the client.
 *
 * Self-hosted endpoints on a private network (an Ollama on localhost, a model server
 * on the same LAN) are a legitimate use of this feature, so an operator who actually
 * wants that can set mailsentinel.byok.allow-private-endpoints=true. It is off by
 * default because the safe direction for a multi-user deployment is closed.
 */
@Component
public class OutboundUrlGuard {

    private static final String USER_MESSAGE =
            "That API endpoint could not be reached. Use a public HTTPS address for your provider.";

    /**
     * Seam for tests: the real implementation is a live DNS lookup, which no unit
     * test should depend on.
     */
    @FunctionalInterface
    public interface HostResolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    private final boolean allowPrivateEndpoints;
    private final HostResolver resolver;

    // @Autowired is required, not decorative: the second constructor below means
    // there is no single candidate for Spring to pick on its own.
    @Autowired
    public OutboundUrlGuard(
            @Value("${mailsentinel.byok.allow-private-endpoints:false}") boolean allowPrivateEndpoints) {
        this(allowPrivateEndpoints, InetAddress::getAllByName);
    }

    OutboundUrlGuard(boolean allowPrivateEndpoints, HostResolver resolver) {
        this.allowPrivateEndpoints = allowPrivateEndpoints;
        this.resolver = resolver;
    }

    /**
     * @throws BlockedEndpointException if this server must not issue a request to the URL
     */
    public void requirePublicHttpsEndpoint(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw blocked("base URL was blank");
        }

        URI uri;
        try {
            uri = new URI(baseUrl.trim());
        } catch (URISyntaxException e) {
            throw blocked("base URL is not a valid URI: " + baseUrl);
        }

        String scheme = uri.getScheme();
        if (scheme == null || !scheme.toLowerCase(Locale.ROOT).equals("https")) {
            throw blocked("scheme is not https: " + scheme);
        }

        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            // getHost() returns null for a host the RFC 3986 parser won't accept,
            // including an underscore in the name -- treat that as unusable rather
            // than falling back to a looser parse.
            throw blocked("no parseable host in base URL: " + baseUrl);
        }
        // Literal IPv6 arrives as "[::1]"; InetAddress wants it without the brackets.
        if (host.startsWith("[") && host.endsWith("]")) {
            host = host.substring(1, host.length() - 1);
        }

        if (allowPrivateEndpoints) {
            return;
        }

        InetAddress[] addresses;
        try {
            addresses = resolver.resolve(host);
        } catch (UnknownHostException e) {
            throw blocked("host does not resolve: " + host);
        }
        if (addresses == null || addresses.length == 0) {
            throw blocked("host resolved to no addresses: " + host);
        }
        for (InetAddress address : addresses) {
            if (isPrivate(address)) {
                throw blocked("host " + host + " resolves to non-public address " + address.getHostAddress());
            }
        }
    }

    /**
     * Anything that isn't a globally routable unicast address. The InetAddress
     * predicates cover most of it -- isSiteLocalAddress is 10/8, 172.16/12 and
     * 192.168/16, isLinkLocalAddress is 169.254/16 (the metadata service) and
     * fe80::/10 -- and the byte checks below fill in the ranges it has no predicate
     * for. Java maps ::ffff:127.0.0.1 to an Inet4Address before it reaches here, so
     * IPv4-mapped IPv6 needs no separate case.
     */
    private static boolean isPrivate(InetAddress address) {
        if (address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isAnyLocalAddress()
                || address.isMulticastAddress()) {
            return true;
        }
        byte[] bytes = address.getAddress();
        if (bytes.length == 16) {
            return (bytes[0] & 0xFE) == 0xFC; // fc00::/7 unique-local
        }
        int first = bytes[0] & 0xFF;
        int second = bytes[1] & 0xFF;
        return first == 0                                 // 0.0.0.0/8 "this network"
                || first == 127                           // belt and braces with isLoopbackAddress
                || (first == 100 && second >= 64 && second <= 127) // 100.64/10 carrier-grade NAT
                || first >= 240;                          // 240/4 reserved, incl. 255.255.255.255
    }

    private static BlockedEndpointException blocked(String detail) {
        return new BlockedEndpointException("Refused to call user-supplied endpoint -- " + detail, USER_MESSAGE);
    }
}
