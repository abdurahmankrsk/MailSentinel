package com.mailsentinel.config;

import com.mailsentinel.auth.EmailAlreadyRegisteredException;
import com.mailsentinel.auth.GoogleAuthException;
import com.mailsentinel.auth.InvalidCredentialsException;
import com.mailsentinel.dto.ErrorResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps named application exceptions to the uniform {@code {error, message}} shape.
 * Endpoints that only need a generic 400 keep using {@code ResponseStatusException}
 * inline (existing pattern in ScanController) -- this handler is for exceptions
 * carrying a specific machine-readable error code.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(EmailAlreadyRegisteredException.class)
    public ResponseEntity<ErrorResponse> handleEmailAlreadyRegistered(EmailAlreadyRegisteredException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("EMAIL_ALREADY_REGISTERED", ex.getMessage()));
    }

    @ExceptionHandler(InvalidCredentialsException.class)
    public ResponseEntity<ErrorResponse> handleInvalidCredentials(InvalidCredentialsException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(new ErrorResponse("INVALID_CREDENTIALS", ex.getMessage()));
    }

    @ExceptionHandler(GoogleAuthException.class)
    public ResponseEntity<ErrorResponse> handleGoogleAuth(GoogleAuthException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(new ErrorResponse("GOOGLE_AUTH_FAILED", ex.getMessage()));
    }
}
