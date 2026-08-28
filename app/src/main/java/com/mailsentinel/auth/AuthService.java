package com.mailsentinel.auth;

import com.mailsentinel.subscription.SubscriptionService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final AuthTokenRepository authTokenRepository;
    private final SubscriptionService subscriptionService;
    private final DisposableEmailDomainService disposableEmailDomainService;
    private final PasswordEncoder passwordEncoder;
    private final TokenGenerator tokenGenerator;
    private final Duration tokenTtl;

    public AuthService(
            UserRepository userRepository,
            AuthTokenRepository authTokenRepository,
            SubscriptionService subscriptionService,
            DisposableEmailDomainService disposableEmailDomainService,
            PasswordEncoder passwordEncoder,
            TokenGenerator tokenGenerator,
            @org.springframework.beans.factory.annotation.Value("${mailsentinel.auth.token-ttl:P30D}") Duration tokenTtl
    ) {
        this.userRepository = userRepository;
        this.authTokenRepository = authTokenRepository;
        this.subscriptionService = subscriptionService;
        this.disposableEmailDomainService = disposableEmailDomainService;
        this.passwordEncoder = passwordEncoder;
        this.tokenGenerator = tokenGenerator;
        this.tokenTtl = tokenTtl;
    }

    @Transactional
    public RegisteredUser register(String email, String rawPassword) {
        String normalizedEmail = normalize(email);
        // Checked before the duplicate lookup so a throwaway address gets the same answer
        // whether or not it happens to be taken -- the rejection is about the domain, and
        // a 409 here would confirm to the sender that some address is registered.
        disposableEmailDomainService.requireNotDisposable(normalizedEmail);
        if (userRepository.existsByEmail(normalizedEmail)) {
            throw new EmailAlreadyRegisteredException(normalizedEmail);
        }
        User user = new User(normalizedEmail, passwordEncoder.encode(rawPassword));
        user = userRepository.save(user);
        subscriptionService.createFreeSubscription(user.getId());
        String rawToken = issueToken(user.getId());
        return new RegisteredUser(user, rawToken);
    }

    @Transactional
    public RegisteredUser login(String email, String rawPassword) {
        String normalizedEmail = normalize(email);
        User user = userRepository.findByEmail(normalizedEmail)
                .orElseThrow(InvalidCredentialsException::new);
        if (!passwordEncoder.matches(rawPassword, user.getPasswordHash())) {
            throw new InvalidCredentialsException();
        }
        String rawToken = issueToken(user.getId());
        return new RegisteredUser(user, rawToken);
    }

    /**
     * Sign in with a Google-verified email, creating the account on first use.
     *
     * Accounts are linked by email address, which is safe here precisely because the
     * caller has already proved -- via GoogleTokenVerifier -- that Google confirmed
     * ownership of that mailbox. So a user who registered with a password and later
     * clicks "Continue with Google" lands back in their existing account rather than
     * a duplicate one.
     *
     * A brand-new Google account gets an unguessable random password hash rather than
     * a null or empty one: the column is non-null, and this way the password login
     * path stays closed for these accounts instead of being open with a known value.
     *
     * The disposable-domain check applies only to the branch that creates an account.
     * Google having verified the mailbox says nothing about it being permanent -- a
     * throwaway provider can run on Workspace like anyone else -- but an account that
     * already exists keeps signing in, because locking people out of accounts they
     * already have is a different decision from refusing to open new ones.
     */
    @Transactional
    public RegisteredUser loginOrRegisterWithGoogle(String verifiedEmail) {
        String normalizedEmail = normalize(verifiedEmail);
        User user = userRepository.findByEmail(normalizedEmail).orElse(null);
        if (user == null) {
            disposableEmailDomainService.requireNotDisposable(normalizedEmail);
            user = userRepository.save(
                    new User(normalizedEmail, passwordEncoder.encode(tokenGenerator.generateRawToken())));
            subscriptionService.createFreeSubscription(user.getId());
        }
        return new RegisteredUser(user, issueToken(user.getId()));
    }

    @Transactional
    public void logout(String rawToken) {
        String tokenHash = tokenGenerator.hash(rawToken);
        authTokenRepository.findByTokenHash(tokenHash).ifPresent(token -> {
            token.revoke();
            authTokenRepository.save(token);
        });
    }

    private String issueToken(Long userId) {
        String rawToken = tokenGenerator.generateRawToken();
        AuthToken token = new AuthToken(userId, tokenGenerator.hash(rawToken), Instant.now().plus(tokenTtl));
        authTokenRepository.save(token);
        return rawToken;
    }

    private String normalize(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }

    public record RegisteredUser(User user, String rawToken) {}
}
