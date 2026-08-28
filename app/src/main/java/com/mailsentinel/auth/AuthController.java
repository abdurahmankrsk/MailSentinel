package com.mailsentinel.auth;

import com.mailsentinel.subscription.SubscriptionService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final int MIN_PASSWORD_LENGTH = 8;

    private final AuthService authService;
    private final SubscriptionService subscriptionService;
    private final GoogleTokenVerifier googleTokenVerifier;

    public AuthController(
            AuthService authService,
            SubscriptionService subscriptionService,
            GoogleTokenVerifier googleTokenVerifier) {
        this.authService = authService;
        this.subscriptionService = subscriptionService;
        this.googleTokenVerifier = googleTokenVerifier;
    }

    /**
     * Lets the frontend show "Continue with Google" only when the server can actually
     * honour it, instead of rendering a button that always fails.
     */
    @GetMapping("/config")
    public AuthConfigResponse config() {
        return new AuthConfigResponse(googleTokenVerifier.isConfigured(), googleTokenVerifier.clientId());
    }

    /**
     * Single entry point for both Google login and Google signup: an unrecognised
     * verified email creates the account, a known one signs into it.
     */
    @PostMapping("/google")
    public AuthResponse google(@RequestBody GoogleSignInRequest request) {
        String verifiedEmail = googleTokenVerifier.verifyAndExtractEmail(request == null ? null : request.credential());
        AuthService.RegisteredUser signedIn = authService.loginOrRegisterWithGoogle(verifiedEmail);
        String plan = subscriptionService.currentPlan(signedIn.user().getId()).name();
        return new AuthResponse(signedIn.rawToken(), signedIn.user().getEmail(), plan);
    }

    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@RequestBody RegisterRequest request) {
        validate(request.email(), request.password());
        AuthService.RegisteredUser registered = authService.register(request.email(), request.password());
        String plan = subscriptionService.currentPlan(registered.user().getId()).name();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new AuthResponse(registered.rawToken(), registered.user().getEmail(), plan));
    }

    @PostMapping("/login")
    public AuthResponse login(@RequestBody LoginRequest request) {
        if (request.email() == null || request.email().isBlank()
                || request.password() == null || request.password().isBlank()) {
            throw new InvalidCredentialsException();
        }
        AuthService.RegisteredUser logged = authService.login(request.email(), request.password());
        String plan = subscriptionService.currentPlan(logged.user().getId()).name();
        return new AuthResponse(logged.rawToken(), logged.user().getEmail(), plan);
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@RequestHeader(value = "Authorization", required = false) String authorization) {
        if (authorization != null && authorization.startsWith("Bearer ")) {
            authService.logout(authorization.substring("Bearer ".length()).trim());
        }
        return ResponseEntity.noContent().build();
    }

    private void validate(String email, String password) {
        if (email == null || email.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Email is required");
        }
        if (password == null || password.length() < MIN_PASSWORD_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Password must be at least " + MIN_PASSWORD_LENGTH + " characters");
        }
    }
}
