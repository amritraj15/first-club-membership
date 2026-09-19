package com.firstclub.membership.service;

import com.firstclub.membership.domain.SubscriptionStatus;
import com.firstclub.membership.repository.SubscriptionRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Periodic safety net for qualifications that change merely because time passes. */
@Component
public class TierReconciliationScheduler {

    private final SubscriptionRepository subscriptionRepository;
    private final TierEvaluationService tierEvaluationService;

    public TierReconciliationScheduler(SubscriptionRepository subscriptionRepository,
                                       TierEvaluationService tierEvaluationService) {
        this.subscriptionRepository = subscriptionRepository;
        this.tierEvaluationService = tierEvaluationService;
    }

    @Scheduled(
            fixedDelayString = "${membership.reconciliation.fixed-delay-ms:3600000}",
            initialDelayString = "${membership.reconciliation.initial-delay-ms:60000}"
    )
    public void reconcileActiveSubscriptions() {
        subscriptionRepository.findDistinctUserIdsByStatus(SubscriptionStatus.ACTIVE)
                .forEach(tierEvaluationService::reevaluateSafely);
    }
}
