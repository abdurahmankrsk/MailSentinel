package com.mailsentinel.dto;

/**
 * Uniform structured error body for named application exceptions.
 *
 * @param error machine-readable error code
 * @param message human-readable explanation
 */
public record ErrorResponse(String error, String message) {}
