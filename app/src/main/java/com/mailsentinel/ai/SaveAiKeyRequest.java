package com.mailsentinel.ai;

/**
 * @param label free-text display name for this key (e.g. "Groq", "OpenAI"); purely
 *              cosmetic, never drives behavior
 * @param baseUrl the OpenAI-compatible chat-completions base URL to call
 * @param model the model name to request from that endpoint
 * @param key the raw API key -- validated against the endpoint and encrypted before
 *            storage, never persisted or logged in plaintext
 */
public record SaveAiKeyRequest(String label, String baseUrl, String model, String key) {}
