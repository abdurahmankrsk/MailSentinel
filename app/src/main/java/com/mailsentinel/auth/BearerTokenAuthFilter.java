package com.mailsentinel.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Resolves the current user from an {@code Authorization: Bearer <token>} header.
 *
 * Absent header -> request proceeds unauthenticated (anonymous / treated as FREE by
 * downstream code); Spring Security's own access rules decide whether that's allowed
 * for the requested endpoint. Present-but-invalid/expired token -> request is
 * rejected here with 401, so an expired token never silently masquerades as "you're
 * just logged out."
 *
 * Deliberately NOT a {@code @Component}: constructed explicitly inside SecurityConfig
 * instead. A plain {@code @Component} implementing Filter gets auto-detected by
 * {@code @WebMvcTest} slices (Filter beans are one of the stereotypes that slice
 * includes), which would then fail to construct it since its repository dependencies
 * aren't part of that slice. Keeping it a plain class means it simply doesn't exist
 * in a slice that never loads SecurityConfig.
 */
public class BearerTokenAuthFilter extends OncePerRequestFilter {

    private final AuthTokenRepository authTokenRepository;
    private final UserRepository userRepository;
    private final TokenGenerator tokenGenerator;
    private final ObjectMapper objectMapper;
    private final Set<String> adminEmails;

    public BearerTokenAuthFilter(
            AuthTokenRepository authTokenRepository,
            UserRepository userRepository,
            TokenGenerator tokenGenerator,
            ObjectMapper objectMapper,
            String adminEmailsProperty
    ) {
        this.authTokenRepository = authTokenRepository;
        this.userRepository = userRepository;
        this.tokenGenerator = tokenGenerator;
        this.objectMapper = objectMapper;
        this.adminEmails = Stream.of(adminEmailsProperty.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(s -> s.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");

        if (header == null || header.isBlank()) {
            chain.doFilter(request, response);
            return;
        }

        if (!header.startsWith("Bearer ")) {
            chain.doFilter(request, response);
            return;
        }

        String rawToken = header.substring("Bearer ".length()).trim();
        if (rawToken.isEmpty()) {
            chain.doFilter(request, response);
            return;
        }

        String tokenHash = tokenGenerator.hash(rawToken);
        Optional<AuthToken> tokenOpt = authTokenRepository.findByTokenHash(tokenHash);

        if (tokenOpt.isEmpty() || !tokenOpt.get().isValid(Instant.now())) {
            rejectUnauthorized(response, "Invalid or expired token");
            return;
        }

        AuthToken authToken = tokenOpt.get();
        Optional<User> userOpt = userRepository.findById(authToken.getUserId());
        if (userOpt.isEmpty()) {
            rejectUnauthorized(response, "Invalid or expired token");
            return;
        }

        User user = userOpt.get();
        authToken.touch();
        authTokenRepository.save(authToken);

        List<GrantedAuthority> authorities = isAdmin(user)
                ? List.of(new SimpleGrantedAuthority("ROLE_USER"), new SimpleGrantedAuthority("ROLE_ADMIN"))
                : List.of(new SimpleGrantedAuthority("ROLE_USER"));

        var authentication = new UsernamePasswordAuthenticationToken(user, null, authorities);
        SecurityContextHolder.getContext().setAuthentication(authentication);

        chain.doFilter(request, response);
    }

    private boolean isAdmin(User user) {
        return adminEmails.contains(user.getEmail().toLowerCase(Locale.ROOT));
    }

    private void rejectUnauthorized(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), Map.of("error", "UNAUTHORIZED", "message", message));
    }
}
