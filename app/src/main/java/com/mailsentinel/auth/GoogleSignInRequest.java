package com.mailsentinel.auth;

/**
 * @param credential the Google ID token issued to the browser by Google Identity
 *                   Services. Verified server-side before it names a user.
 */
public record GoogleSignInRequest(String credential) {}
