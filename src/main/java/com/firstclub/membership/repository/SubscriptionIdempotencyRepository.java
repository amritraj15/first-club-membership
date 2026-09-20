package com.firstclub.membership.repository;

import com.firstclub.membership.domain.SubscriptionIdempotency;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SubscriptionIdempotencyRepository extends JpaRepository<SubscriptionIdempotency, Long> {
    @EntityGraph(attributePaths = {
            "subscription",
            "subscription.user",
            "subscription.plan",
            "subscription.tier",
            "subscription.planVersion"
    })
    Optional<SubscriptionIdempotency> findByUserIdAndIdempotencyKey(Long userId, String idempotencyKey);
}
