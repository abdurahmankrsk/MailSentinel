package com.mailsentinel.ai;

/**
 * @param label free-text display name for the saved key (e.g. "Groq", "OpenAI")
 * @param last4 the last 4 characters of the raw key, for display -- the key itself
 *              is write-only and never returned once saved
 */
public record AiKeyStatus(String label, String last4) {}
