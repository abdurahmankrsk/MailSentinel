package com.mailsentinel.ai;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.UnknownHostException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every resolver here is stubbed. Testing the address rules against real DNS would
 * mean the suite passes or fails on what a public name happens to resolve to today.
 */
class OutboundUrlGuardTest {

    private static final String PUBLIC_ADDRESS = "93.184.216.34";

    private static OutboundUrlGuard guardResolving(String... addresses) {
        return new OutboundUrlGuard(false, host -> {
            InetAddress[] resolved = new InetAddress[addresses.length];
            for (int i = 0; i < addresses.length; i++) {
                resolved[i] = InetAddress.getByName(addresses[i]);
            }
            return resolved;
        });
    }

    @Test
    void allowsAPublicHttpsEndpoint() {
        assertDoesNotThrow(() ->
                guardResolving(PUBLIC_ADDRESS).requirePublicHttpsEndpoint("https://api.groq.com/openai/v1"));
    }

    @Test
    void rejectsPlaintextHttp() {
        assertThrows(BlockedEndpointException.class, () ->
                guardResolving(PUBLIC_ADDRESS).requirePublicHttpsEndpoint("http://api.groq.com/openai/v1"));
    }

    @Test
    void rejectsANonHttpScheme() {
        OutboundUrlGuard guard = guardResolving(PUBLIC_ADDRESS);
        assertThrows(BlockedEndpointException.class, () -> guard.requirePublicHttpsEndpoint("ftp://example.com"));
        assertThrows(BlockedEndpointException.class, () -> guard.requirePublicHttpsEndpoint("file:///etc/passwd"));
        assertThrows(BlockedEndpointException.class, () -> guard.requirePublicHttpsEndpoint("api.groq.com"));
    }

    @Test
    void rejectsBlankAndUnparseableUrls() {
        OutboundUrlGuard guard = guardResolving(PUBLIC_ADDRESS);
        assertThrows(BlockedEndpointException.class, () -> guard.requirePublicHttpsEndpoint(null));
        assertThrows(BlockedEndpointException.class, () -> guard.requirePublicHttpsEndpoint("   "));
        assertThrows(BlockedEndpointException.class, () -> guard.requirePublicHttpsEndpoint("https://"));
        assertThrows(BlockedEndpointException.class, () -> guard.requirePublicHttpsEndpoint("https://ho st/v1"));
    }

    /**
     * The reproduction from the QA report: each of these was a live probe of the
     * host MailSentinel itself runs on.
     */
    @Test
    void rejectsLoopbackLinkLocalAndPrivateRanges() {
        String[] blocked = {
                "127.0.0.1",        // loopback -- the app's own port
                "169.254.169.254",  // cloud instance metadata, and its credentials
                "192.168.1.1",      // RFC1918
                "10.0.0.5",
                "172.16.0.1",
                "0.0.0.0",
                "100.64.0.1",       // carrier-grade NAT
                "255.255.255.255",
                "::1",              // IPv6 loopback
                "fd00::1",          // IPv6 unique-local
                "fe80::1",          // IPv6 link-local
        };
        for (String address : blocked) {
            assertThrows(BlockedEndpointException.class,
                    () -> guardResolving(address).requirePublicHttpsEndpoint("https://ai.example.com/v1"),
                    address + " must not be reachable from a user-supplied base URL");
        }
    }

    @Test
    void rejectsALiteralPrivateAddressInTheUrlItself() {
        // InetAddress.getByName parses a literal without a lookup, so the stub is never
        // consulted -- assert the literal path is guarded too, not just the DNS one.
        OutboundUrlGuard guard = new OutboundUrlGuard(false, InetAddress::getAllByName);
        assertThrows(BlockedEndpointException.class,
                () -> guard.requirePublicHttpsEndpoint("https://127.0.0.1:8099/api"));
        assertThrows(BlockedEndpointException.class,
                () -> guard.requirePublicHttpsEndpoint("https://[::1]:8099/api"));
        assertThrows(BlockedEndpointException.class,
                () -> guard.requirePublicHttpsEndpoint("https://169.254.169.254/latest/meta-data"));
    }

    /**
     * A split-horizon name that answers with one public and one private address must
     * fail. Passing on the first address alone would leave the private one reachable
     * over whichever family the HTTP client happens to prefer.
     */
    @Test
    void rejectsWhenAnyResolvedAddressIsPrivate() {
        assertThrows(BlockedEndpointException.class, () ->
                guardResolving(PUBLIC_ADDRESS, "10.1.2.3").requirePublicHttpsEndpoint("https://split.example.com/v1"));
    }

    @Test
    void rejectsAHostThatDoesNotResolve() {
        OutboundUrlGuard guard = new OutboundUrlGuard(false, host -> {
            throw new UnknownHostException(host);
        });

        assertThrows(BlockedEndpointException.class, () -> guard.requirePublicHttpsEndpoint("https://nope.example/v1"));
    }

    @Test
    void theUserFacingMessageCarriesNoDetailAboutWhatWasBlocked() {
        BlockedEndpointException loopback = assertThrows(BlockedEndpointException.class, () ->
                guardResolving("127.0.0.1").requirePublicHttpsEndpoint("https://ai.example.com/v1"));
        BlockedEndpointException unresolvable = assertThrows(BlockedEndpointException.class, () ->
                new OutboundUrlGuard(false, host -> {
                    throw new UnknownHostException(host);
                }).requirePublicHttpsEndpoint("https://ai.example.com/v1"));

        // Identical replies: "blocked because internal" vs "does not exist" is itself
        // the signal a port scan reads.
        assertEquals(loopback.userMessage(), unresolvable.userMessage());
        assertTrue(loopback.getMessage().contains("127.0.0.1"),
                "the operator-facing detail should still name the address, for the log");
    }

    /**
     * The escape hatch for an operator running a self-hosted model on their own
     * network, who is accepting the trade knowingly.
     */
    @Test
    void allowsPrivateAddressesOnlyWhenTheOperatorOptsIn() {
        OutboundUrlGuard permissive = new OutboundUrlGuard(true, InetAddress::getAllByName);

        assertDoesNotThrow(() -> permissive.requirePublicHttpsEndpoint("https://127.0.0.1:11434/v1"));
        // Still https-only: the opt-in is about which addresses are reachable, not
        // about sending the user's API key in the clear.
        assertThrows(BlockedEndpointException.class,
                () -> permissive.requirePublicHttpsEndpoint("http://127.0.0.1:11434/v1"));
    }
}
