package com.firstclub.membership.service;

import com.firstclub.membership.domain.ActiveMembershipLock;
import com.firstclub.membership.domain.Plan;
import com.firstclub.membership.domain.PlanVersion;
import com.firstclub.membership.domain.SubscriptionIdempotency;
import com.firstclub.membership.domain.Subscription;
import com.firstclub.membership.domain.SubscriptionStatus;
import com.firstclub.membership.domain.Tier;
import com.firstclub.membership.domain.TierSource;
import com.firstclub.membership.domain.User;
import com.firstclub.membership.exception.ConflictException;
import com.firstclub.membership.exception.InvalidTransitionException;
import com.firstclub.membership.exception.NotFoundException;
import com.firstclub.membership.repository.ActiveMembershipLockRepository;
import com.firstclub.membership.repository.SubscriptionRepository;
import com.firstclub.membership.repository.PlanVersionRepository;
import com.firstclub.membership.repository.SubscriptionIdempotencyRepository;
import com.firstclub.membership.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.Clock;
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

    private final SubscriptionRepository subscriptionRepository;
    private final UserRepository userRepository;
    private final PlanService planService;
    private final SubscriptionStateMachine stateMachine;
    private final ActiveMembershipLockRepository lockRepository;
    private final PlanVersionRepository planVersionRepository;
    private final SubscriptionIdempotencyRepository idempotencyRepository;
    private final CallerIdentityGuard callerIdentityGuard;
    private final Clock clock;

    public SubscriptionMutationTransactions(SubscriptionRepository subscriptionRepository,
                                             UserRepository userRepository,
                                             PlanService planService,
                                             SubscriptionStateMachine stateMachine,
                                             ActiveMembershipLockRepository lockRepository,
                                             PlanVersionRepository planVersionRepository,
                                             SubscriptionIdempotencyRepository idempotencyRepository,
                                             CallerIdentityGuard callerIdentityGuard,
                                             Clock clock) {
        this.subscriptionRepository = subscriptionRepository;
        this.userRepository = userRepository;
        this.planService = planService;
        this.stateMachine = stateMachine;
        this.lockRepository = lockRepository;
        this.planVersionRepository = planVersionRepository;
        this.idempotencyRepository = idempotencyRepository;
        this.callerIdentityGuard = callerIdentityGuard;
        this.clock = clock;
    }

    /**
     * Creates a subscription using two complementary database guarantees:
     * <ol>
     *   <li>A {@code PESSIMISTIC_WRITE} lock on the user row serializes subscription creation
     *       for the same user across all application servers sharing the database.</li>
     *   <li>The unique {@link ActiveMembershipLock} row remains a database invariant: at most
     *       one active membership can exist for a user even if another code path violates the
     *       application-level assumption.</li>
     * </ol>
     * The idempotency record is written in the same transaction, so a retry against any server
     * returns the original subscription rather than creating another one.
     */
    @Transactional
    public Subscription createSubscription(Long userId, Long planId, Long tierId, String idempotencyKey) {
        // Pessimistic user-row lock is database-backed and therefore coordinates requests
        // across every application server connected to the same database.
        User user = userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new NotFoundException("User not found: " + userId));

        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            String key = idempotencyKey.trim();
            if (key.length() > 200) {
                throw new IllegalArgumentException("Idempotency-Key must be at most 200 characters");
            }
            var previous = idempotencyRepository.findByUserIdAndIdempotencyKey(userId, key);
            if (previous.isPresent()) {
                SubscriptionIdempotency record = previous.get();
                if (!record.getPlanId().equals(planId) || !record.getTierId().equals(tierId)) {
                    throw new ConflictException("Idempotency-Key was already used with different subscription parameters");
                }
                return record.getSubscription();
            }
            idempotencyKey = key;
        }

        Plan plan = planService.getPlan(planId);
        Tier tier = planService.getTier(tierId);
        PlanVersion planVersion = planVersionRepository.findTopByPlanIdOrderByVersionNumberDesc(planId)
                .orElseThrow(() -> new NotFoundException("No price version configured for plan: " + planId));

        List<Subscription> activeStatusSubs = subscriptionRepository.findByUserIdAndStatus(userId, SubscriptionStatus.ACTIVE);
        Instant now = clock.instant();
        for (Subscription existing : activeStatusSubs) {
            if (existing.isCurrentlyActive(now)) {
                throw new ConflictException(
                        "User " + userId + " already has an active subscription - cancel it before subscribing again");
            }
            existing.setStatus(SubscriptionStatus.EXPIRED);
            subscriptionRepository.save(existing);
            lockRepository.deleteByUserId(userId);
        }

        Subscription subscription = new Subscription(user, plan, planVersion, tier, now, plan.computeEndDate(now));
        subscription = subscriptionRepository.save(subscription);
        lockRepository.save(new ActiveMembershipLock(userId, subscription.getId()));

        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            idempotencyRepository.save(new SubscriptionIdempotency(
                    userId, idempotencyKey, planId, tierId, subscription));
        }

        return subscription;
    }

    /**
     * Guards against a STALE-ACTIVE subscription: {@code status} can still read ACTIVE in the
     * database even after {@code endDate} has passed, because expiry is corrected lazily on
     * read (see {@link Subscription#isCurrentlyActive}), not by a background job that runs
     * before every write. Without this check, a subscription that has expired but hasn't been
     * touched by {@code getCurrentMembership} yet would pass {@code assertTierChangeAllowed}
     * (which only inspects the persisted enum) and let a caller "change tier" on a membership
     * that is no longer live - purely a data-consistency problem, since
     * {@link com.firstclub.membership.web.CheckoutController} independently re-checks liveness
     * before granting any benefit, but a real gap for GET /membership to then show a
     * just-changed tier on a row it immediately re-flips to EXPIRED. This is the same liveness
     * check CheckoutController already applies for the same reason - see that class's comment.
     */
    @Transactional
    public Subscription changeTier(Long subscriptionId, Long newTierId, Long callerUserId) {
        Subscription subscription = subscriptionRepository.findById(subscriptionId)
                .orElseThrow(() -> new NotFoundException("Subscription not found: " + subscriptionId));
        callerIdentityGuard.requireOwnership(callerUserId, subscription.getUser().getId());
        stateMachine.assertTierChangeAllowed(subscription.getStatus());
        if (!subscription.isCurrentlyActive(clock.instant())) {
            throw new InvalidTransitionException(
                    "Cannot change tier on subscription " + subscriptionId + " - it has expired");
        }
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
    public Subscription cancel(Long subscriptionId, Long callerUserId) {
        Subscription subscription = subscriptionRepository.findById(subscriptionId)
                .orElseThrow(() -> new NotFoundException("Subscription not found: " + subscriptionId));
        callerIdentityGuard.requireOwnership(callerUserId, subscription.getUser().getId());
        stateMachine.assertTransitionAllowed(subscription.getStatus(), SubscriptionStatus.CANCELLED);
        subscription.setStatus(SubscriptionStatus.CANCELLED);
        subscription.setEndDate(clock.instant());
        subscription = subscriptionRepository.save(subscription);
        lockRepository.deleteByUserId(subscription.getUser().getId());
        return subscription;
    }
}
