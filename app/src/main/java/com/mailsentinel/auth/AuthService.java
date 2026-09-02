package com.mailsentinel.auth;

import com.mailsentinel.subscription.SubscriptionService;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.regex.Pattern;

@Service
public class AuthService {

    /**
     * Practical email syntax: a local part, then at least two dot-separated domain labels
     * that each begin and end alphanumerically.
     *
     * <p>Deliberately not an attempt at full RFC 5322 -- that grammar admits quoted
     * strings and comments no signup form should accept anyway. What matters here is
     * that an address <em>has</em> a domain at all, because the disposable-provider check
     * matches on the text after the last {@code @}: without this, "notanemail" registered
     * successfully and skipped that check entirely, since there was no domain to match.
     */
    private static final Pattern EMAIL_PATTERN = Pattern.compile(
            "^[A-Za-z0-9._%+-]+@[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?"
                    + "(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?)+$");

    /** RFC 5321's ceiling, and comfortably inside the {@code varchar(320)} email column. */
    public static final int MAX_EMAIL_LENGTH = 254;

    /**
     * Shared so the controller and this service enforce one definition rather than
     * drifting apart. Checked here as well as at the edge because the edge is not the
     * only caller -- and because a length that passes validation but overflows the
     * column surfaces as a 500, not a 400.
     */
    public static boolean isValidEmailFormat(String email) {
        if (email == null) {
            return false;
        }
        String trimmed = email.trim();
        return trimmed.length() <= MAX_EMAIL_LENGTH && EMAIL_PATTERN.matcher(trimmed).matches();
    }

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
        // Ahead of the disposable check, which can only be meaningful once the address is
        // known to have a domain to inspect at all.
        if (!isValidEmailFormat(normalizedEmail)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "That doesn't look like a valid email address");
        }
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

    /**
     * Changes a signed-in user's password, then revokes every token they hold and
     * issues one fresh token for the caller.
     *
     * The revocation is the point, not a side effect. Changing a password is how
     * someone reacts to a session they believe is stolen, and tokens here live 30 days
     * -- leaving the others valid would mean the change accomplished nothing against
     * the case that prompted it. The caller gets a new token back so the device that
     * made the change isn't signed out by its own action.
     *
     * The current password is required. Without it, anyone holding a token -- which is
     * exactly the attacker this defends against -- could lock the real owner out.
     */
    @Transactional
    public String changePassword(Long userId, String currentPassword, String newPassword) {
        User user = userRepository.findById(userId)
                .orElseThrow(InvalidCredentialsException::new);
        if (currentPassword == null || !passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw new InvalidCredentialsException();
        }
        user.changePasswordHash(passwordEncoder.encode(newPassword));
        userRepository.save(user);

        revokeAllTokens(userId);
        return issueToken(userId);
    }

    /**
     * Erases the account and everything hanging off it.
     *
     * The child rows go with it because every table that references users declares
     * {@code ON DELETE CASCADE} (see the V2-V6 migrations) -- subscriptions,
     * usage_periods, idempotency_records, user_ai_keys and auth_tokens are all removed
     * by the database as part of this one statement. AccountControllerTest asserts
     * that rather than trusting it, since a future table added without the cascade
     * would otherwise fail this silently and leave orphaned personal data behind.
     *
     * A real delete, not a soft one with a flag: this exists to answer a GDPR erasure
     * request, and "still in the table but marked gone" is not erasure. It also frees
     * the email address, so the same person can sign up again later.
     */
    @Transactional
    public void deleteAccount(Long userId) {
        userRepository.findById(userId).ifPresent(userRepository::delete);
    }

    private void revokeAllTokens(Long userId) {
        authTokenRepository.findAllByUserId(userId).forEach(token -> {
            token.revoke();
            authTokenRepository.save(token);
        });
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
