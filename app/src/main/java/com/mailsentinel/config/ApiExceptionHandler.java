package com.mailsentinel.config;

import com.mailsentinel.auth.DisposableEmailDomainException;
import com.mailsentinel.auth.EmailAlreadyRegisteredException;
import com.mailsentinel.auth.GoogleAuthException;
import com.mailsentinel.auth.InvalidCredentialsException;
import com.mailsentinel.dto.ErrorResponse;
import org.springframework.dao.DataIntegrityViolationException;
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

    /**
     * Backstop so a column constraint can never surface as a 500.
     *
     * A 10,000-character email used to reach the insert and throw
     * {@code Value too long for column "EMAIL"}, which no handler caught -- the caller got
     * an opaque server error and the logs got a stack trace, for what is simply invalid
     * input. The length check in AuthService now rejects that case up front, so this
     * handler should be unreachable on the register path; it exists because the next
     * column to gain a constraint should not have to rediscover the same failure mode.
     *
     * The exception's own message is not echoed back: it carries the SQL statement and
     * column definition, which is not something to hand to an unauthenticated caller.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleDataIntegrityViolation(DataIntegrityViolationException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse("INVALID_REQUEST",
                        "That request could not be saved. Please check the values you submitted."));
    }
}
