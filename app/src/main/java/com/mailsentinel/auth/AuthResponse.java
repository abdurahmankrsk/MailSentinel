package com.mailsentinel.auth;

public record AuthResponse(String token, String email, String plan) {}
