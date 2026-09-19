package com.firstclub.membership.service;

import com.firstclub.membership.domain.OrderRecord;
import com.firstclub.membership.domain.Subscription;
import com.firstclub.membership.domain.SubscriptionStatus;
import com.firstclub.membership.domain.Tier;
import com.firstclub.membership.domain.TierSource;
import com.firstclub.membership.domain.User;
import com.firstclub.membership.repository.OrderRecordRepository;
import com.firstclub.membership.repository.SubscriptionRepository;
import com.firstclub.membership.strategy.TierEvaluator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

/**
 * The actual tier-recomputation unit of work, deliberately kept in its OWN bean (not a method
 * on {@link TierEvaluationService}) so that {@code @Transactional(REQUIRES_NEW)} is applied by
 * a real Spring AOP proxy - see {@link TierEvaluationService} javadoc for why a same-class
 * self-invocation would silently skip the proxy and the new-transaction guarantee.
 * <p>
 * Idempotent by construction: recomputes the qualifying tier from the order window from
 * scratch every call, so a missed or duplicate trigger can never cause permanent drift.
 * <p>
 * <b>Explicit demotion policy</b> (the spec does not say whether tiers can move down
 * automatically, so this is a documented business-rule decision, not an accident):
 * <b>promotion AND automatic demotion are both allowed</b> - this method always recomputes the
 * highest tier the CURRENT order window supports and moves the subscription to exactly that
 * tier, up or down, rather than being "sticky" (promotion-only) or tier-locked for the
 * membership period. The alternative (promotion-only) was considered and rejected: it would
 * mean a user who briefly qualified for Gold keeps Gold's benefits forever even after their
 * order activity drops off, which both under-serves the business (perpetual benefit cost with
 * no ongoing qualifying behaviour) and contradicts "users move through tiers... based on
 * criteria" reading as a live, not one-way, relationship between behaviour and tier.
 * <p>
 * The caveat: demotion (like promotion) only happens when THIS method actually runs - i.e. on
 * an order placement/cancellation, or a manual call to
 * {@code POST /users/{id}/reconcile-tier} (see SubscriptionController). Pure time passing with
 * no new order and no reconciliation call will not, by itself, trigger a demotion - there is no
 * scheduled background sweep in this build (see README "deliberately not implemented" on the
 * outbox/event-pipeline suggestion). This is called out explicitly rather than left implicit,
 * since it's exactly the kind of gap a production deployment would need to close with a
 * scheduled job calling this same method for every active subscription.
 */
@Service
public class TierReevaluationTransaction {

    private static final Logger log = LoggerFactory.getLogger(TierReevaluationTransaction.class);

    /**
     * "Total order value in a month" / order-count window - EXPLICITLY a rolling 30-day window
     * (last 30*24 hours from now), NOT a calendar month (1st-to-30th/31st). This is a
     * deliberate, documented choice between the two readings the spec's wording allows:
     * <ul>
     *   <li>Rolling 30 days (chosen): a user's eligibility is evaluated the same way regardless
     *       of what day of the month it currently is - no "reset to zero on the 1st" cliff where
     *       someone with 24 orders on the 28th loses all of them on the 1st. Simpler to reason
     *       about and implement correctly (no timezone-bound month-boundary arithmetic).</li>
     *   <li>Calendar month (rejected here, but noted as the more literal reading of "in a
     *       month"): would need an explicit timezone for "when does the month roll over" (a
     *       requirement the spec doesn't specify), and creates a real behavioural cliff at
     *       midnight on the 1st that a rolling window avoids.</li>
     * </ul>
     * If the actual intended semantics are calendar-month, this constant plus the query in
     * {@link com.firstclub.membership.repository.OrderRecordRepository} are the only two things
     * that need to change - the strategies, evaluator, and everything downstream are
     * window-agnostic.
     */
    private static final int EVALUATION_WINDOW_DAYS = 30;

    private final SubscriptionRepository subscriptionRepository;
    private final OrderRecordRepository orderRecordRepository;
    private final PlanService planService;
    private final TierEvaluator tierEvaluator;

    public TierReevaluationTransaction(SubscriptionRepository subscriptionRepository,
                                        OrderRecordRepository orderRecordRepository,
                                        PlanService planService,
                                        TierEvaluator tierEvaluator) {
        this.subscriptionRepository = subscriptionRepository;
        this.orderRecordRepository = orderRecordRepository;
        this.planService = planService;
        this.tierEvaluator = tierEvaluator;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean reevaluateInNewTransaction(Long userId) {
        Optional<Subscription> maybeSub =
                subscriptionRepository.findFirstByUserIdAndStatusOrderByStartDateDesc(userId, SubscriptionStatus.ACTIVE);
        if (maybeSub.isEmpty()) {
            return false; // No active subscription - nothing to promote/demote.
        }
        Subscription subscription = maybeSub.get();
        if (!subscription.isCurrentlyActive(Instant.now())) {
            return false; // Lazily-detected expiry - a background job will flip status separately.
        }

        User user = subscription.getUser();
        Instant windowStart = Instant.now().minus(EVALUATION_WINDOW_DAYS, ChronoUnit.DAYS);
        List<OrderRecord> recentOrders =
                orderRecordRepository.findByUserIdAndCancelledFalseAndPlacedAtAfter(userId, windowStart);

        List<Tier> allTiers = planService.listTiersAscending();
        Tier qualifyingTier = tierEvaluator.evaluate(user, recentOrders, allTiers);

        if (subscription.isManualTierOverride()) {
            // Respect the user's explicit choice for now; it gets cleared the next time they
            // place a NEW order (see OrderService), at which point normal evaluation resumes.
            log.info("Skipping auto tier-evaluation for user {} - manual override in effect", userId);
            return false;
        }

        if (qualifyingTier.getId().equals(subscription.getTier().getId())) {
            return false;
        }

        log.info("Promoting/adjusting user {} from tier {} to {} based on order activity",
                userId, subscription.getTier().getName(), qualifyingTier.getName());
        subscription.setTier(qualifyingTier);
        subscription.setTierSource(TierSource.SYSTEM_PROMOTED);
        subscriptionRepository.save(subscription);
        return true;
    }
}
