package com.mailsentinel.config;

import com.mailsentinel.auth.DisposableEmailDomainException;
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

    /**
     * 422 rather than 400: the request is well-formed and was understood, the address is
     * simply one this service declines to open an account for. That keeps it distinct
     * from the plain 400 the register endpoint returns for a missing email or a short
     * password, so a client can tell "you typed it wrong" from "not that provider".
     */
    @ExceptionHandler(DisposableEmailDomainException.class)
    public ResponseEntity<ErrorResponse> handleDisposableEmailDomain(DisposableEmailDomainException ex) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(new ErrorResponse("DISPOSABLE_EMAIL_DOMAIN", ex.getMessage()));
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
