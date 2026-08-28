package com.mailsentinel.ai;

/**
 * Everything needed to call a user's own AI endpoint for one request: which
 * OpenAI-compatible server to hit, which model, and the decrypted key. Never
 * exposed outside the backend.
 */
public record ActiveAiKey(String baseUrl, String model, String apiKey) {}
