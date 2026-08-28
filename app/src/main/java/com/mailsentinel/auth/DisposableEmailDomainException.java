package com.mailsentinel.auth;

/**
 * Raised when signup is attempted with a throwaway mailbox provider.
 *
 * The message names the offending domain and not the full address: it is enough for the
 * user to see which part of what they typed was refused, without echoing the local part
 * back into an error body and any log that captures it.
 */
public class DisposableEmailDomainException extends RuntimeException {
    public DisposableEmailDomainException(String domain) {
        super("Disposable email addresses are not accepted. Please sign up with a permanent "
                + "address instead of one at " + domain + ".");
    }
}
