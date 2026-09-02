package com.mailsentinel.auth;

/**
 * @param currentPassword proof that the caller is the account's owner and not merely
 *                        the holder of a token, which is the case a password change
 *                        most often exists to shut down
 * @param newPassword     validated against the same rules registration applies
 */
public record ChangePasswordRequest(String currentPassword, String newPassword) {}
