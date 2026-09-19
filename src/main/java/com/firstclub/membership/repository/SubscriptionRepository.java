package com.firstclub.membership.repository;

import com.firstclub.membership.domain.Subscription;
import com.firstclub.membership.domain.SubscriptionStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SubscriptionRepository extends JpaRepository<Subscription, Long> {

    List<Subscription> findByUserIdAndStatus(Long userId, SubscriptionStatus status);

    Optional<Subscription> findFirstByUserIdAndStatusOrderByStartDateDesc(Long userId, SubscriptionStatus status);

    /** No status filter - used for "track current membership", which must be able to show an
     *  already-expired subscription's details rather than 404 once expiry is detected. */
    Optional<Subscription> findFirstByUserIdOrderByStartDateDesc(Long userId);
}
