package com.mailsentinel.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.util.Map;

/**
 * Access-control boundary for the whole app.
 *
 * /api/scan stays open with no token required (anonymous callers are treated as
 * FREE downstream) -- this preserves the pre-existing anonymous contract exactly.
 * Everything else defaults to permitAll except the explicitly-listed authenticated
 * and admin-only paths, since this app also serves its own static frontend from the
 * same origin and that must stay reachable without a token.
 */
@Configuration
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * Authentication here is entirely via BearerTokenAuthFilter, which populates the
     * SecurityContext directly and never consults an AuthenticationManager or
     * UserDetailsService. This empty bean exists only to satisfy Spring Boot's
     * auto-configuration condition and suppress its "generated security password"
     * warning; httpBasic and formLogin are both disabled below, so it's unreachable.
     */
    @Bean
    public UserDetailsService userDetailsService() {
        return new InMemoryUserDetailsManager();
    }

    @Bean
    public BearerTokenAuthFilter bearerTokenAuthFilter(
            AuthTokenRepository authTokenRepository,
            UserRepository userRepository,
            TokenGenerator tokenGenerator,
            ObjectMapper objectMapper,
            @Value("${mailsentinel.admin.emails:}") String adminEmails
    ) {
        return new BearerTokenAuthFilter(authTokenRepository, userRepository, tokenGenerator, objectMapper, adminEmails);
    }

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http, BearerTokenAuthFilter bearerTokenAuthFilter, ObjectMapper objectMapper) throws Exception {
        AuthenticationEntryPoint jsonUnauthorizedEntryPoint = (request, response, authException) -> {
            response.setStatus(401);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            objectMapper.writeValue(response.getWriter(),
                    Map.of("error", "UNAUTHORIZED", "message", "Authentication required"));
        };

        http
            .csrf(AbstractHttpConfigurer::disable)
            // Disabling anonymous auth means a request with no token at all has no
            // Authentication in the SecurityContext, so a protected endpoint rejects it
            // via AuthenticationException (401, our JSON entry point below) rather than
            // AccessDeniedException (403) -- keeping "no credentials" (401) and "wrong
            // role" (403) as two distinct, semantically correct outcomes.
            .anonymous(AbstractHttpConfigurer::disable)
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .exceptionHandling(handling -> handling.authenticationEntryPoint(jsonUnauthorizedEntryPoint))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/admin/**").hasRole("ADMIN")
                .requestMatchers("/api/usage/**").authenticated()
                .anyRequest().permitAll()
            )
            .addFilterBefore(bearerTokenAuthFilter, UsernamePasswordAuthenticationFilter.class)
            .httpBasic(AbstractHttpConfigurer::disable)
            .formLogin(AbstractHttpConfigurer::disable);

        return http.build();
    }
}
