package com.firstclub.membership.service;

import com.firstclub.membership.domain.Subscription;
import com.firstclub.membership.domain.SubscriptionStatus;
import com.firstclub.membership.exception.ConflictException;
import com.firstclub.membership.exception.NotFoundException;
import com.firstclub.membership.repository.SubscriptionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * User-initiated subscription lifecycle: subscribe, upgrade/downgrade tier, cancel, and status
 * lookup. This class is the RETRY/ORCHESTRATION policy layer; the actual transactional units of
 * work live in {@link SubscriptionMutationTransactions} - see that class's javadoc for why the
 * split exists. See {@link SubscriptionStateMachine} for transition rules and
 * {@link TierEvaluationService} for the SYSTEM side of tier changes.
 * <p>
 * Optimistic-lock retry is standardized across every mutation that touches an existing
 * {@code @Version}-controlled row (changeTier, cancel): one retry through a fresh transaction on
 * {@link ObjectOptimisticLockingFailureException}, then a 409 to the caller. {@code subscribe}
 * does not need this pattern - a duplicate-active-subscription conflict there is a genuine,
 * real conflict rather than a transient lock collision, so it is surfaced immediately rather
 * than retried (see {@link SubscriptionMutationTransactions#createSubscription}).
 */
@Service
public class SubscriptionService {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionService.class);

    private final SubscriptionRepository subscriptionRepository;
    private final SubscriptionMutationTransactions mutations;

    public SubscriptionService(SubscriptionRepository subscriptionRepository,
                                SubscriptionMutationTransactions mutations) {
        this.subscriptionRepository = subscriptionRepository;
        this.mutations = mutations;
    }

    public Subscription subscribe(Long userId, Long planId, Long tierId) {
        return mutations.createSubscription(userId, planId, tierId);
    }

    public Subscription changeTier(Long subscriptionId, Long newTierId) {
        return withOptimisticRetry("change tier for subscription " + subscriptionId,
                () -> mutations.changeTier(subscriptionId, newTierId));
    }

    public Subscription cancel(Long subscriptionId) {
        return withOptimisticRetry("cancel subscription " + subscriptionId,
                () -> mutations.cancel(subscriptionId));
    }

    private Subscription withOptimisticRetry(String description, java.util.function.Supplier<Subscription> attempt) {
        try {
            return attempt.get();
        } catch (ObjectOptimisticLockingFailureException conflict) {
            log.warn("Optimistic lock conflict attempting to {} - retrying once", description);
            try {
                return attempt.get();
            } catch (ObjectOptimisticLockingFailureException stillConflicting) {
                throw new ConflictException(
                        "Could not " + description + " - it was modified concurrently, please retry");
            }
        }
    }

    /**
     * Tracks the user's most recent membership regardless of status, lazily correcting status
     * to EXPIRED on read if the end date has passed but a background job hasn't caught up yet -
     * see Subscription#isCurrentlyActive javadoc. Deliberately does NOT filter to ACTIVE only:
     * "track current membership and expiry" means an expired subscription's details (expiry
     * date, last tier) should still be visible, not a 404.
     * <p>
     * Does NOT also delete a stale {@code ActiveMembershipLock} row here - that cleanup only
     * needs to happen by the time the user tries to subscribe again, and
     * {@link SubscriptionMutationTransactions#createSubscription} already does it there. Doing
     * it here too would duplicate the same cleanup logic in two places for no behavioural gain.
     */
    @Transactional
    public Subscription getCurrentMembership(Long userId) {
        Subscription subscription = subscriptionRepository
                .findFirstByUserIdOrderByStartDateDesc(userId)
                .orElseThrow(() -> new NotFoundException("No subscription found for user " + userId));

        if (subscription.getStatus() == SubscriptionStatus.ACTIVE && !subscription.isCurrentlyActive(Instant.now())) {
            subscription.setStatus(SubscriptionStatus.EXPIRED);
            subscriptionRepository.save(subscription);
        }
        return subscription;
    }
}
