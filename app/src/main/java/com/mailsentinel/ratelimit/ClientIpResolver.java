package com.mailsentinel.ratelimit;

import jakarta.servlet.http.HttpServletRequest;

/**
 * The address to count a request against.
 *
 * On the container platforms the README targets (Render, Railway, Fly, Heroku) the
 * app sits behind the platform's proxy, so {@code getRemoteAddr()} is the proxy for
 * every caller in the world -- a per-IP limit read from it would throttle all users
 * as one. {@code X-Forwarded-For}'s first entry is the real client there.
 *
 * That header is also caller-supplied and trivially spoofed, which is exactly why
 * trusting it is opt-in ({@code mailsentinel.rate-limit.trust-forwarded-for}).
 * Trusting it with no proxy in front hands an attacker a fresh identity per request
 * and disables the limiter; ignoring it behind a proxy collapses every client into
 * one bucket. Both are wrong in one deployment and right in the other, so the
 * deployment has to say which it is -- defaulting to the safe-when-wrong option, the
 * socket address.
 */
public final class ClientIpResolver {

    private ClientIpResolver() {
    }

    public static String resolve(HttpServletRequest request, boolean trustForwardedFor) {
        if (trustForwardedFor) {
            String forwarded = request.getHeader("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                // "client, proxy1, proxy2" -- the leftmost entry is the original client.
                String first = forwarded.split(",", 2)[0].trim();
                if (!first.isEmpty()) {
                    return first;
                }
            }
        }
        String remote = request.getRemoteAddr();
        return remote == null || remote.isBlank() ? "unknown-client" : remote;
    }
}
