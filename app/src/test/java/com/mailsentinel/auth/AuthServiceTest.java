package com.mailsentinel.auth;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@Transactional
class AuthServiceTest {

    @Autowired
    private AuthService authService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AuthTokenRepository authTokenRepository;

    @Test
    void registerCreatesUserAndReturnsUsableToken() {
        AuthService.RegisteredUser registered = authService.register("Test@Example.com", "correct-horse");

        assertEquals("test@example.com", registered.user().getEmail(), "email should be lowercased");
        assertTrue(registered.rawToken().startsWith("mst_"));
        assertTrue(userRepository.existsByEmail("test@example.com"));

        String tokenHash = new TokenGenerator().hash(registered.rawToken());
        assertTrue(authTokenRepository.findByTokenHash(tokenHash).isPresent());
    }

    @Test
    void registerRejectsDuplicateEmail() {
        authService.register("dup@example.com", "correct-horse");
        assertThrows(EmailAlreadyRegisteredException.class,
                () -> authService.register("dup@example.com", "another-password"));
    }

    @Test
    void loginSucceedsWithCorrectPassword() {
        authService.register("login@example.com", "correct-horse");
        AuthService.RegisteredUser logged = authService.login("login@example.com", "correct-horse");
        assertEquals("login@example.com", logged.user().getEmail());
    }

    @Test
    void loginFailsWithWrongPassword() {
        authService.register("wrongpw@example.com", "correct-horse");
        assertThrows(InvalidCredentialsException.class,
                () -> authService.login("wrongpw@example.com", "not-the-password"));
    }

    @Test
    void loginFailsWithUnknownEmailUsingTheSameGenericError() {
        assertThrows(InvalidCredentialsException.class,
                () -> authService.login("nobody@example.com", "whatever"));
    }

    @Test
    void logoutRevokesOnlyThePresentedToken() {
        AuthService.RegisteredUser first = authService.register("multi@example.com", "correct-horse");
        String secondToken = authService.login("multi@example.com", "correct-horse").rawToken();
        assertNotEquals(first.rawToken(), secondToken, "a fresh login must issue a distinct token");

        authService.logout(first.rawToken());

        TokenGenerator hasher = new TokenGenerator();
        AuthToken firstToken = authTokenRepository.findByTokenHash(hasher.hash(first.rawToken())).orElseThrow();
        AuthToken second = authTokenRepository.findByTokenHash(hasher.hash(secondToken)).orElseThrow();
        assertTrue(firstToken.getRevokedAt() != null, "logged-out token must be revoked");
        assertTrue(second.getRevokedAt() == null, "the other device's token must be untouched");
    }
}
