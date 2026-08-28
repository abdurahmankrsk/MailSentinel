package com.mailsentinel.ai;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserAiKeyRepository extends JpaRepository<UserAiKey, Long> {
    Optional<UserAiKey> findByUserId(Long userId);

    void deleteByUserId(Long userId);
}
