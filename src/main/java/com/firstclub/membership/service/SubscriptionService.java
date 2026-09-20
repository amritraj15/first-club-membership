package com.firstclub.membership.service;

import com.firstclub.membership.domain.Subscription;
import com.firstclub.membership.domain.SubscriptionStatus;
import com.firstclub.membership.domain.SubscriptionIdempotency;
import com.firstclub.membership.exception.ConflictException;
import com.firstclub.membership.exception.NotFoundException;
import com.firstclub.membership.repository.ActiveMembershipLockRepository;
import com.firstclub.membership.repository.SubscriptionRepository;
import com.firstclub.membership.repository.SubscriptionIdempotencyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Optional;

/**
 * User-initiated subscription lifecycle: subscribe, upgrade/downgrade tier, cancel, and status
 * lookup. This class is the RETRY/ORCHESTRATION policy layer; the actual transactional units of
 * work live in {@link SubscriptionMutationTransactions} - see that class's javadoc for why the
 * split exists. See {@link SubscriptionStateMachine} for transition rules and
 * {@link TierEvaluationService} for the SYSTEM side of tier changes.
 * <p>
 * Optimistic-lock retry is standardized across every mutation that touches an existing
 * {@code @Version}-controlled row (changeTier, cancel): one retry through a fresh transaction on
 * {@link ObjectOptimisticLockingFailureException}, then a 409 to the caller. Subscription
 * creation has a separate integrity-race recovery: if the database unique constraint is won by a
 * concurrent request using the same idempotency key, the failed transaction is discarded and the
 * committed subscription is replayed from a fresh repository transaction. If the constraint that
 * fired is instead {@code ActiveMembershipLock}'s uniqueness (the pessimistic user-row lock
 * should make this unreachable in practice, but "should" isn't "is"), that's translated to the
 * same 409 the lock-check path already returns, rather than left to surface as an unhandled
 * {@code DataIntegrityViolationException} - see {@link #subscribe}'s inline comments for exactly
 * which case gets which treatment, and which residual case is deliberately left unhandled.
 */
@Service
public class SubscriptionService {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionService.class);

    private final SubscriptionRepository subscriptionRepository;
    private final SubscriptionMutationTransactions mutations;
    private final Clock clock;
    private final SubscriptionIdempotencyRepository idempotencyRepository;
    private final ActiveMembershipLockRepository lockRepository;

    public SubscriptionService(SubscriptionRepository subscriptionRepository,
                                SubscriptionMutationTransactions mutations, Clock clock,
                                SubscriptionIdempotencyRepository idempotencyRepository,
                                ActiveMembershipLockRepository lockRepository) {
        this.subscriptionRepository = subscriptionRepository;
        this.mutations = mutations;
        this.clock = clock;
        this.idempotencyRepository = idempotencyRepository;
        this.lockRepository = lockRepository;
    }

    public Subscription subscribe(Long userId, Long planId, Long tierId, String idempotencyKey) {
        String key = idempotencyKey == null ? null : idempotencyKey.trim();
        try {
            return mutations.createSubscription(userId, planId, tierId, key);
        } catch (DataIntegrityViolationException constraintRace) {
            // A concurrent request can win the unique (user_id, idempotency_key) constraint
            // after both requests initially observe "no record". The failed transaction must
            // be discarded; replay is deliberately performed through a fresh transaction.
            if (key != null && !key.isBlank()) {
                Optional<SubscriptionIdempotency> existing = idempotencyRepository
                        .findByUserIdAndIdempotencyKey(userId, key);
                if (existing.isPresent()) {
                    SubscriptionIdempotency record = existing.get();
                    if (!record.getPlanId().equals(planId) || !record.getTierId().equals(tierId)) {
                        throw new ConflictException(
                                "Idempotency-Key was already used with different subscription parameters");
                    }
                    return record.getSubscription();
                }
            }
            // Not an idempotency-key race (no key was given, or the key didn't resolve to a
            // record). The remaining KNOWN cause of a unique-constraint failure inside
            // createSubscription is the ActiveMembershipLock invariant - the pessimistic
            // user-row lock should make that essentially unreachable in practice (see
            // createSubscription's javadoc), but "should be unreachable" is not the same
            // guarantee as "is unreachable": a lock-acquisition edge case, a future code path
            // that bypasses the lock, or a different isolation configuration could still land
            // here. GlobalExceptionHandler has no handler for DataIntegrityViolationException on
            // purpose - a raw constraint violation is not normally something that should get a
            // free translation to a business-logic response - so without this check, this
            // specific, well-understood case would surface as an unhandled 500 instead of the
            // same 409 the pessimistic-lock path already returns for the identical situation.
            if (lockRepository.findByUserId(userId).isPresent()) {
                throw new ConflictException(
                        "User " + userId + " already has an active subscription - cancel it before subscribing again");
            }
            // A genuinely unrecognized integrity violation - do not invent a business-logic
            // response for something this code doesn't understand; a loud 500 here is more
            // honest than guessing at a 409 that might be masking a real bug.
            throw constraintRace;
        }
    }

    public Subscription changeTier(Long subscriptionId, Long newTierId, Long callerUserId) {
        return withOptimisticRetry("change tier for subscription " + subscriptionId,
                () -> mutations.changeTier(subscriptionId, newTierId, callerUserId));
    }

    public Subscription cancel(Long subscriptionId, Long callerUserId) {
        return withOptimisticRetry("cancel subscription " + subscriptionId,
                () -> mutations.cancel(subscriptionId, callerUserId));
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

        if (subscription.getStatus() == SubscriptionStatus.ACTIVE && !subscription.isCurrentlyActive(clock.instant())) {
            subscription.setStatus(SubscriptionStatus.EXPIRED);
            subscriptionRepository.save(subscription);
        }
        return subscription;
    }
}
