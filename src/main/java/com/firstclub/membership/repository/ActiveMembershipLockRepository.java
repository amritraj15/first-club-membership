package com.firstclub.membership.repository;

import com.firstclub.membership.domain.ActiveMembershipLock;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ActiveMembershipLockRepository extends JpaRepository<ActiveMembershipLock, Long> {
    Optional<ActiveMembershipLock> findByUserId(Long userId);

    void deleteByUserId(Long userId);
}
