package com.firstclub.membership.repository;

import com.firstclub.membership.domain.Subscription;
import com.firstclub.membership.domain.SubscriptionStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface SubscriptionRepository extends JpaRepository<Subscription, Long> {

    List<Subscription> findByUserIdAndStatus(Long userId, SubscriptionStatus status);

    Optional<Subscription> findFirstByUserIdAndStatusOrderByStartDateDesc(Long userId, SubscriptionStatus status);

    /** No status filter - used for "track current membership", which must be able to show an
     *  already-expired subscription's details rather than 404 once expiry is detected. */
    Optional<Subscription> findFirstByUserIdOrderByStartDateDesc(Long userId);

    /**
     * Paged rather than a single {@code List<Long>} of every active user - the reconciliation
     * sweep (see {@link com.firstclub.membership.service.TierReconciliationScheduler}) must not
     * pull the entire active user base into memory in one query as the membership base grows.
     * An explicit {@code countQuery} is required alongside a DISTINCT {@code @Query} because
     * Spring Data cannot safely derive a matching count query from an arbitrary projection. The
     * explicit {@code order by} is not cosmetic: offset/limit pagination over an unordered
     * DISTINCT projection has no guaranteed stable row order across separate page fetches, so
     * without it, consecutive pages of one sweep could silently skip or repeat a user id even
     * with zero concurrent writes.
     */
    @Query(value = "select distinct s.user.id from Subscription s where s.status = :status order by s.user.id",
            countQuery = "select count(distinct s.user.id) from Subscription s where s.status = :status")
    Page<Long> findDistinctUserIdsByStatus(@Param("status") SubscriptionStatus status, Pageable pageable);
}
