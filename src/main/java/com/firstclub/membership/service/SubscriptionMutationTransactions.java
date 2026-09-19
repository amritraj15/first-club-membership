package com.firstclub.membership.service;

import com.firstclub.membership.domain.ActiveMembershipLock;
import com.firstclub.membership.domain.Plan;
import com.firstclub.membership.domain.Subscription;
import com.firstclub.membership.domain.SubscriptionStatus;
import com.firstclub.membership.domain.Tier;
import com.firstclub.membership.domain.TierSource;
import com.firstclub.membership.domain.User;
import com.firstclub.membership.exception.ConflictException;
import com.firstclub.membership.exception.NotFoundException;
import com.firstclub.membership.repository.ActiveMembershipLockRepository;
import com.firstclub.membership.repository.SubscriptionRepository;
import com.firstclub.membership.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * The actual transactional units of work behind every subscription mutation (create, change
 * tier, cancel), kept in their own bean - SEPARATE from {@link SubscriptionService} - for the
 * same reason as the rest of this codebase's retry-sensitive operations: Spring's
 * {@code @Transactional} is an AOP proxy, and a same-class ("self-invocation") retry call
 * bypasses that proxy entirely, silently running with no transaction boundary and, worse,
 * reusing a persistence context that a failed flush has already poisoned. Every retry in
 * {@link SubscriptionService} goes through a call to THIS bean, so each attempt gets a
 * genuinely fresh transaction.
 */
@Service
public class SubscriptionMutationTransactions {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionMutationTransactions.class);

    private final SubscriptionRepository subscriptionRepository;
    private final UserRepository userRepository;
    private final PlanService planService;
    private final SubscriptionStateMachine stateMachine;
    private final ActiveMembershipLockRepository lockRepository;

    public SubscriptionMutationTransactions(SubscriptionRepository subscriptionRepository,
                                             UserRepository userRepository,
                                             PlanService planService,
                                             SubscriptionStateMachine stateMachine,
                                             ActiveMembershipLockRepository lockRepository) {
        this.subscriptionRepository = subscriptionRepository;
        this.userRepository = userRepository;
        this.planService = planService;
        this.stateMachine = stateMachine;
        this.lockRepository = lockRepository;
    }

    /**
     * Creates a subscription and its {@link ActiveMembershipLock} row in ONE transaction, so
     * they commit or roll back together. Defense in depth against duplicate active
     * subscriptions, deliberately layered:
     * <ol>
     *   <li>An app-level check (below) gives a fast, friendly 409 for the common, non-racy case,
     *       and opportunistically cleans up a stale lock row left behind by a subscription that
     *       expired by date but was never read (so its status was never lazily flipped).</li>
     *   <li>The DB-level unique constraint on {@code active_membership_lock.user_id} is the
     *       actual correctness guarantee for the genuine race: if two requests for the same new
     *       subscriber both pass step 1 simultaneously, both proceed to insert - the database
     *       allows only one, and the other's {@code save()} throws
     *       {@link DataIntegrityViolationException}, which is caught here and turned into a 409.
     *       Because both writes (subscription + lock) share this one transaction, the loser's
     *       partially-inserted subscription row rolls back too - no orphan record.</li>
     * </ol>
     * <p>
     * One deliberately-accepted gap: the opportunistic cleanup step below (flipping a
     * stale ACTIVE-status-but-time-expired row to EXPIRED) is a plain {@code save()}, not
     * wrapped in the same optimistic-lock retry-once pattern as {@link #changeTier} / {@link
     * #cancel}. In the extremely narrow case of two concurrent {@code createSubscription} calls
     * for the same user both racing to clean up the SAME stale row, one could get an
     * {@code ObjectOptimisticLockingFailureException} here uncaught, surfacing as a 500 rather
     * than a clean 409/retry. Not fixed for this exercise (it would mean either a third retry
     * wrapper or folding this into the generic retry helper for a genuinely rare race on top of
     * an already-rare race); flagged here so it isn't mistaken for an oversight.
     */
    @Transactional
    public Subscription createSubscription(Long userId, Long planId, Long tierId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException("User not found: " + userId));
        Plan plan = planService.getPlan(planId);
        Tier tier = planService.getTier(tierId);

        List<Subscription> activeStatusSubs = subscriptionRepository.findByUserIdAndStatus(userId, SubscriptionStatus.ACTIVE);
        Instant now = Instant.now();
        for (Subscription existing : activeStatusSubs) {
            if (existing.isCurrentlyActive(now)) {
                throw new ConflictException(
                        "User " + userId + " already has an active subscription - cancel it before subscribing again");
            }
            // Status says ACTIVE but the end date has passed and nobody has read it yet to
            // trigger the usual lazy-expiry correction - clean up here so this user isn't
            // permanently blocked from subscribing again by a stale lock row.
            existing.setStatus(SubscriptionStatus.EXPIRED);
            subscriptionRepository.save(existing);
            lockRepository.deleteByUserId(userId);
        }

        Subscription subscription = new Subscription(user, plan, tier, now, plan.computeEndDate(now));
        subscription = subscriptionRepository.save(subscription);

        try {
            lockRepository.save(new ActiveMembershipLock(userId, subscription.getId()));
        } catch (DataIntegrityViolationException raceLost) {
            // A concurrent request for this same user won the race between our check above and
            // this insert. The whole transaction rolls back (subscription row included) since
            // this exception propagates out of a @Transactional method.
            log.warn("Concurrent duplicate-subscribe detected for user {} at the DB constraint", userId);
            throw new ConflictException(
                    "User " + userId + " already has an active subscription (concurrent request) - please retry");
        }

        return subscription;
    }

    @Transactional
    public Subscription changeTier(Long subscriptionId, Long newTierId) {
        Subscription subscription = subscriptionRepository.findById(subscriptionId)
                .orElseThrow(() -> new NotFoundException("Subscription not found: " + subscriptionId));
        stateMachine.assertTierChangeAllowed(subscription.getStatus());
        Tier newTier = planService.getTier(newTierId);

        subscription.setTier(newTier);
        subscription.setTierSource(TierSource.USER_SELECTED);
        subscription.setManualTierOverride(true);
        return subscriptionRepository.save(subscription);
    }

    /**
     * Cancelling MUST also delete the {@link ActiveMembershipLock} row, in the same transaction
     * as the status flip - not lazily later. If it didn't, a cancelled user could never
     * subscribe again: {@code createSubscription}'s app-level check only looks at
     * status={@code ACTIVE} rows (a cancelled subscription correctly no longer matches), so it
     * would sail through to the lock insert and hit the still-present stale row's unique
     * constraint, producing a false-positive 409 for a user who has nothing active at all.
     */
    @Transactional
    public Subscription cancel(Long subscriptionId) {
        Subscription subscription = subscriptionRepository.findById(subscriptionId)
                .orElseThrow(() -> new NotFoundException("Subscription not found: " + subscriptionId));
        stateMachine.assertTransitionAllowed(subscription.getStatus(), SubscriptionStatus.CANCELLED);
        subscription.setStatus(SubscriptionStatus.CANCELLED);
        subscription.setEndDate(Instant.now());
        subscription = subscriptionRepository.save(subscription);
        lockRepository.deleteByUserId(subscription.getUser().getId());
        return subscription;
    }
}
