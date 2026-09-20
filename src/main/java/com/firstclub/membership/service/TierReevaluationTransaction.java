package com.firstclub.membership.service;

import com.firstclub.membership.domain.CriteriaType;
import com.firstclub.membership.domain.OrderRecord;
import com.firstclub.membership.domain.Subscription;
import com.firstclub.membership.domain.SubscriptionStatus;
import com.firstclub.membership.domain.Tier;
import com.firstclub.membership.domain.TierChangeAudit;
import com.firstclub.membership.domain.TierCriterion;
import com.firstclub.membership.domain.TierSource;
import com.firstclub.membership.domain.User;
import com.firstclub.membership.repository.OrderRecordRepository;
import com.firstclub.membership.repository.SubscriptionRepository;
import com.firstclub.membership.repository.TierChangeAuditRepository;
import com.firstclub.membership.strategy.TierEvaluator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
 * A scheduled reconciliation sweep is also provided for time passing with no new order. It
 * calls this same idempotent method for each active user; order placement/cancellation remains
 * the immediate trigger.
 */
@Service
public class TierReevaluationTransaction {

    private static final Logger log = LoggerFactory.getLogger(TierReevaluationTransaction.class);

    private final SubscriptionRepository subscriptionRepository;
    private final OrderRecordRepository orderRecordRepository;
    private final PlanService planService;
    private final TierEvaluator tierEvaluator;
    private final QualificationWindowResolver qualificationWindowResolver;
    private final TierChangeAuditRepository tierChangeAuditRepository;
    private final Clock clock;

    public TierReevaluationTransaction(SubscriptionRepository subscriptionRepository,
                                        OrderRecordRepository orderRecordRepository,
                                        PlanService planService,
                                        TierEvaluator tierEvaluator,
                                        QualificationWindowResolver qualificationWindowResolver,
                                        TierChangeAuditRepository tierChangeAuditRepository,
                                        Clock clock) {
        this.subscriptionRepository = subscriptionRepository;
        this.orderRecordRepository = orderRecordRepository;
        this.planService = planService;
        this.tierEvaluator = tierEvaluator;
        this.qualificationWindowResolver = qualificationWindowResolver;
        this.tierChangeAuditRepository = tierChangeAuditRepository;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean reevaluateInNewTransaction(Long userId) {
        Optional<Subscription> maybeSub =
                subscriptionRepository.findFirstByUserIdAndStatusOrderByStartDateDesc(userId, SubscriptionStatus.ACTIVE);
        if (maybeSub.isEmpty()) {
            return false; // No active subscription - nothing to promote/demote.
        }
        Subscription subscription = maybeSub.get();
        Instant evaluationTime = clock.instant();
        if (!subscription.isCurrentlyActive(evaluationTime)) {
            return false; // Lazily-detected expiry - a background job will flip status separately.
        }

        User user = subscription.getUser();
        List<Tier> allTiers = planService.listTiersAscending();
        Map<QualificationWindowResolver.QualificationWindow, List<OrderRecord>> ordersByWindow = new HashMap<>();
        Tier qualifyingTier = tierEvaluator.evaluate(
                user, criterion -> ordersForCriterion(userId, criterion, evaluationTime, ordersByWindow), allTiers);

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
        Long previousTierId = subscription.getTier().getId();
        subscription.setTier(qualifyingTier);
        subscription.setTierSource(TierSource.SYSTEM_PROMOTED);
        subscription = subscriptionRepository.save(subscription);
        tierChangeAuditRepository.save(new TierChangeAudit(
                userId, subscription.getId(), previousTierId, qualifyingTier.getId(),
                TierSource.SYSTEM_PROMOTED, evaluationTime));
        return true;
    }

    /**
     * A tier can have several criteria but often shares a window (for example, order-count and
     * order-value in the current month). Cache per resolved window for this evaluation so the
     * repository is queried once per distinct window, while TierEvaluator still short-circuits.
     */
    private List<OrderRecord> ordersForCriterion(Long userId, TierCriterion criterion, Instant evaluationTime,
                                                 Map<QualificationWindowResolver.QualificationWindow,
                                                         List<OrderRecord>> ordersByWindow) {
        if (criterion.getCriteriaType() == CriteriaType.COHORT) {
            return List.of();
        }
        QualificationWindowResolver.QualificationWindow window =
                qualificationWindowResolver.resolve(criterion, evaluationTime);
        return ordersByWindow.computeIfAbsent(window, resolvedWindow ->
                orderRecordRepository.findByUserIdAndCancelledFalseAndPlacedAtGreaterThanEqualAndPlacedAtLessThan(
                        userId, resolvedWindow.startInclusive(), resolvedWindow.endExclusive()));
    }
}
