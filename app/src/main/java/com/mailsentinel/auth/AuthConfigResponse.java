package com.mailsentinel.auth;

/**
 * Public, non-secret auth configuration the frontend needs in order to render the
 * right sign-in options.
 *
 * The Google client ID is public by design -- it identifies the application to
 * Google and is visible in every OAuth redirect. The client *secret* is never part
 * of this response, and is not needed by the ID-token flow this app uses at all.
 *
 * @param googleEnabled whether the server has Google sign-in configured
 * @param googleClientId the public client ID, or empty when not configured
 */
public record AuthConfigResponse(boolean googleEnabled, String googleClientId) {}
