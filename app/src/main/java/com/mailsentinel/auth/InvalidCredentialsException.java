package com.mailsentinel.auth;

/** Deliberately generic: covers both "no such email" and "wrong password" so error
 * responses never let a caller enumerate registered accounts. */
public class InvalidCredentialsException extends RuntimeException {
    public InvalidCredentialsException() {
        super("Invalid email or password");
    }
}
