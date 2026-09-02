package com.mailsentinel.auth;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AuthTokenRepository extends JpaRepository<AuthToken, Long> {
    Optional<AuthToken> findByTokenHash(String tokenHash);

    /**
     * Every token ever issued to a user, revoked ones included.
     *
     * Used to sign out all of a user's other devices when their password changes: a
     * password change is how someone reacts to a session they think is stolen, so
     * leaving the other 30-day tokens valid would defeat the point of changing it.
     */
    List<AuthToken> findAllByUserId(Long userId);
}
