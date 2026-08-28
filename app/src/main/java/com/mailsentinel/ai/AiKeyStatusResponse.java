package com.mailsentinel.ai;

/**
 * @param featureEnabled whether the server has bring-your-own-key configured at all
 *                       (mirrors how AuthConfigResponse reports Google sign-in) --
 *                       the frontend hides the entry point entirely when false
 * @param label the saved key's free-text display name, or null if none is saved
 * @param last4 the saved key's last 4 characters, or null if none is saved
 */
public record AiKeyStatusResponse(boolean featureEnabled, String label, String last4) {}
