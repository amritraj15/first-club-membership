package com.firstclub.membership.service;

import com.firstclub.membership.domain.SubscriptionStatus;
import com.firstclub.membership.repository.SubscriptionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodic safety net for qualifications that change merely because time passes.
 * <p>
 * Walks active users in bounded pages ({@code membership.reconciliation.batch-size}, default
 * 200) rather than loading every active user id into one in-memory list. A single-list sweep is
 * fine at seed-data scale, but this job runs on an unbounded "how many users are active right
 * now" set, and correctness code elsewhere in this service (windowed order queries, per-request
 * caching) is deliberately bounded for the same reason - this sweep should not be the one place
 * memory footprint scales unbounded with membership growth. Each page still processes users one
 * at a time through {@link TierEvaluationService#reevaluateSafely}, which is unchanged: this is
 * a memory-footprint / query-shape fix, not a throughput or transaction-boundary change.
 */
@Component
public class TierReconciliationScheduler {

    private static final Logger log = LoggerFactory.getLogger(TierReconciliationScheduler.class);

    private final SubscriptionRepository subscriptionRepository;
    private final TierEvaluationService tierEvaluationService;
    private final int batchSize;

    public TierReconciliationScheduler(SubscriptionRepository subscriptionRepository,
                                       TierEvaluationService tierEvaluationService,
                                       @Value("${membership.reconciliation.batch-size:200}") int batchSize) {
        this.subscriptionRepository = subscriptionRepository;
        this.tierEvaluationService = tierEvaluationService;
        this.batchSize = batchSize;
    }

    @Scheduled(
            fixedDelayString = "${membership.reconciliation.fixed-delay-ms:3600000}",
            initialDelayString = "${membership.reconciliation.initial-delay-ms:60000}"
    )
    public void reconcileActiveSubscriptions() {
        int pageNumber = 0;
        int usersProcessed = 0;
        Page<Long> page;
        do {
            page = subscriptionRepository.findDistinctUserIdsByStatus(
                    SubscriptionStatus.ACTIVE, PageRequest.of(pageNumber, batchSize));
            page.getContent().forEach(tierEvaluationService::reevaluateSafely);
            usersProcessed += page.getNumberOfElements();
            pageNumber++;
        } while (page.hasNext());
        log.info("Tier reconciliation sweep processed {} active users across {} page(s)", usersProcessed, pageNumber);
    }
}
