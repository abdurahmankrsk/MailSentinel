package com.mailsentinel.auth;

/**
 * A Google credential was absent, unverifiable, or not acceptable for sign-in.
 *
 * The message is deliberately coarse -- it never distinguishes "no such user" from
 * "bad credential", for the same reason InvalidCredentialsException doesn't.
 */
public class GoogleAuthException extends RuntimeException {
    public GoogleAuthException(String message) {
        super(message);
    }
}
