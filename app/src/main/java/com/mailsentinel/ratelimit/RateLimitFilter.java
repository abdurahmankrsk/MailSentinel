package com.mailsentinel.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mailsentinel.dto.ErrorResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Per-IP quotas on the endpoints that cost something to abuse.
 *
 * Login is the conspicuous absence here: what deserves counting there is a *failed*
 * attempt, and this filter runs before the handler knows. AuthController owns that
 * one (see LoginThrottle), so a legitimate user with a working password is never
 * locked out by their own successful sign-ins.
 *
 * Runs before authentication so an unauthenticated flood is refused without touching
 * the database -- which is the point on /api/scan, where there is no account at all.
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private final RateLimitService rateLimitService;
    private final RateLimitProperties properties;
    private final ObjectMapper objectMapper;

    public RateLimitFilter(RateLimitService rateLimitService, RateLimitProperties properties,
                           ObjectMapper objectMapper) {
        this.rateLimitService = rateLimitService;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        RateLimitProperties.Quota quota = quotaFor(request);
        if (quota == null) {
            chain.doFilter(request, response);
            return;
        }

        String ip = ClientIpResolver.resolve(request, properties.isTrustForwardedFor());
        String key = request.getServletPath() + "|ip|" + ip;
        RateLimitDecision decision = rateLimitService.consume(key, quota.toPolicy());
        if (decision.allowed()) {
            chain.doFilter(request, response);
            return;
        }
        writeTooManyRequests(response, decision);
    }

    /**
     * Which quota applies, or null for a path this filter leaves alone -- which is
     * most of them, the static frontend included.
     */
    private RateLimitProperties.Quota quotaFor(HttpServletRequest request) {
        if (!properties.isEnabled() || !"POST".equalsIgnoreCase(request.getMethod())) {
            return null;
        }
        String path = request.getServletPath();
        if ("/api/auth/register".equals(path) || "/api/auth/google".equals(path)) {
            // Google sign-in is counted with registration: it is the other way to
            // create an account, so leaving it out would leave the flood path open.
            return properties.getRegister();
        }
        if ("/api/scan".equals(path)) {
            return properties.getScan();
        }
        if ("/api/account/ai-key".equals(path)) {
            return properties.getAiKey();
        }
        return null;
    }

    private void writeTooManyRequests(HttpServletResponse response, RateLimitDecision decision) throws IOException {
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(decision.retryAfterSeconds()));
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), new ErrorResponse(
                "RATE_LIMITED",
                "Too many requests. Try again in " + decision.retryAfterSeconds() + " seconds."));
    }
}
