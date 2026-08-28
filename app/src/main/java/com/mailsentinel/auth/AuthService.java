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
    private final PasswordEncoder passwordEncoder;
    private final TokenGenerator tokenGenerator;
    private final Duration tokenTtl;

    public AuthService(
            UserRepository userRepository,
            AuthTokenRepository authTokenRepository,
            SubscriptionService subscriptionService,
            PasswordEncoder passwordEncoder,
            TokenGenerator tokenGenerator,
            @org.springframework.beans.factory.annotation.Value("${mailsentinel.auth.token-ttl:P30D}") Duration tokenTtl
    ) {
        this.userRepository = userRepository;
        this.authTokenRepository = authTokenRepository;
        this.subscriptionService = subscriptionService;
        this.passwordEncoder = passwordEncoder;
        this.tokenGenerator = tokenGenerator;
        this.tokenTtl = tokenTtl;
    }

    @Transactional
    public RegisteredUser register(String email, String rawPassword) {
        String normalizedEmail = normalize(email);
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
