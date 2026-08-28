package com.mailsentinel.auth;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Set;

/**
 * Verifies a Google ID token and returns the identity it proves.
 *
 * The browser runs Google Identity Services, which hands it a signed ID token; that
 * token is the only thing sent to us, and it is verified here, server-side, before it
 * is allowed to name a user. Nothing the browser claims about who it is -- an email in
 * the request body, a decoded-but-unverified JWT payload -- is trusted.
 *
 * Verification delegates to Google's tokeninfo endpoint rather than validating the
 * RS256 signature locally: it keeps signature checking and key rotation on Google's
 * side instead of hand-rolling crypto here. The cost is one server-to-server HTTPS
 * call per sign-in. At higher volume, swap this for a locally-cached JWKS verifier
 * (com.google.api-client's GoogleIdTokenVerifier) -- the rest of the flow is unchanged.
 */
@Component
public class GoogleTokenVerifier {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final Set<String> VALID_ISSUERS =
            Set.of("accounts.google.com", "https://accounts.google.com");

    private final RestClient restClient;
    private final String clientId;

    public GoogleTokenVerifier(
            @Value("${google.oauth.client-id:}") String clientId,
            @Value("${google.oauth.tokeninfo-url:https://oauth2.googleapis.com/tokeninfo}") String tokenInfoUrl
    ) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(TIMEOUT);
        requestFactory.setReadTimeout(TIMEOUT);
        this.restClient = RestClient.builder()
                .baseUrl(tokenInfoUrl)
                .requestFactory(requestFactory)
                .build();
        this.clientId = clientId == null ? "" : clientId.trim();
    }

    public boolean isConfigured() {
        return !clientId.isBlank();
    }

    public String clientId() {
        return clientId;
    }

    /**
     * @return the verified, Google-confirmed email address for this token
     * @throws GoogleAuthException if the token is missing, unverifiable, minted for a
     *                             different application, expired, or covers an email
     *                             address Google itself has not confirmed
     */
    public String verifyAndExtractEmail(String idToken) {
        if (!isConfigured()) {
            throw new GoogleAuthException("Google sign-in is not configured on this server");
        }
        if (idToken == null || idToken.isBlank()) {
            throw new GoogleAuthException("Missing Google credential");
        }

        TokenInfo info;
        try {
            info = restClient.get()
                    .uri(uriBuilder -> uriBuilder.queryParam("id_token", idToken).build())
                    .retrieve()
                    .body(TokenInfo.class);
        } catch (RestClientException e) {
            // Google rejects malformed/forged/expired tokens with a 4xx, which lands here.
            throw new GoogleAuthException("Google rejected this credential");
        }

        if (info == null || info.email() == null || info.email().isBlank()) {
            throw new GoogleAuthException("Google credential carried no email address");
        }
        // Without this check any Google-issued token -- including one minted for an
        // unrelated application -- would authenticate a user here.
        if (!clientId.equals(info.aud())) {
            throw new GoogleAuthException("Google credential was issued for a different application");
        }
        if (info.iss() == null || !VALID_ISSUERS.contains(info.iss())) {
            throw new GoogleAuthException("Google credential has an unexpected issuer");
        }
        if (!"true".equalsIgnoreCase(info.emailVerified())) {
            throw new GoogleAuthException("Google has not verified this email address");
        }
        if (info.exp() != null && Instant.now().isAfter(Instant.ofEpochSecond(Long.parseLong(info.exp())))) {
            throw new GoogleAuthException("Google credential has expired");
        }

        return info.email().trim().toLowerCase(Locale.ROOT);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record TokenInfo(
            String aud,
            String iss,
            String exp,
            String email,
            @JsonProperty("email_verified") String emailVerified) {}
}
