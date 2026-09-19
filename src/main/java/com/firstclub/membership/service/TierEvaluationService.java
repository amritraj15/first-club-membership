package com.firstclub.membership.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;

/**
 * Recomputes a user's system-driven tier from their recent order history.
 * <p>
 * Design decisions worth restating here (see README for the full discussion):
 * <ul>
 *   <li>Runs in its OWN transaction (REQUIRES_NEW), never the order-write's transaction, so a
 *       bug in tier evaluation can never roll back or block order placement.</li>
 *   <li>On failure, logs and returns rather than throwing back into the order flow - a missed
 *       promotion is recoverable on the next order or the next manual reconciliation call;
 *       a broken checkout is not.</li>
 *   <li>Recomputes tier from scratch from the order window every time (idempotent), rather than
 *       incrementing a counter - so a missed event can never cause permanent drift.</li>
 *   <li>Respects {@code manualTierOverride}: if the user's LAST tier change was an explicit
 *       user-initiated one, automatic promotion/demotion is skipped until they order again -
 *       this is the fix for the Plan-vs-Tier ambiguity flagged during design review (a user who
 *       manually downgrades should not be silently re-promoted on the same evaluation pass).
 *       In this implementation the override is cleared as soon as a NEW order arrives, so the
 *       user's next piece of order activity re-enters normal automatic evaluation - a
 *       deliberately simple policy; see README for alternatives considered.</li>
 *   <li>Optimistic-lock conflict -&gt; retry exactly once, then log and defer to the next
 *       evaluation trigger rather than looping.</li>
 * </ul>
 */
@Service
public class TierEvaluationService {

    private static final Logger log = LoggerFactory.getLogger(TierEvaluationService.class);

    private final TierReevaluationTransaction tierReevaluationTransaction;

    public TierEvaluationService(TierReevaluationTransaction tierReevaluationTransaction) {
        this.tierReevaluationTransaction = tierReevaluationTransaction;
    }

    /**
     * Re-evaluates and, if warranted, updates the given user's active subscription's tier.
     * The actual work runs in {@link TierReevaluationTransaction}, a SEPARATE Spring bean -
     * this matters, not just for style: {@code @Transactional(REQUIRES_NEW)} is implemented via
     * a proxy around the bean, so calling it through a distinct injected collaborator (as done
     * here) actually gets a new transaction; calling a same-class method annotated that way
     * would go through {@code this} directly and silently skip the proxy, making the annotation
     * a no-op. Returns true if the tier actually changed.
     */
    public boolean reevaluateSafely(Long userId) {
        try {
            return doReevaluateWithRetry(userId, 1);
        } catch (RuntimeException ex) {
            log.error("Tier re-evaluation failed for user {} - will be retried on next order or "
                    + "manual reconciliation call. Cause: {}", userId, ex.getMessage());
            return false;
        }
    }

    private boolean doReevaluateWithRetry(Long userId, int attemptsLeft) {
        try {
            return tierReevaluationTransaction.reevaluateInNewTransaction(userId);
        } catch (ObjectOptimisticLockingFailureException conflict) {
            if (attemptsLeft > 0) {
                log.warn("Optimistic lock conflict evaluating tier for user {} - retrying once", userId);
                return doReevaluateWithRetry(userId, attemptsLeft - 1);
            }
            log.error("Optimistic lock conflict persisted after retry for user {} - deferring to "
                    + "next evaluation trigger", userId);
            return false;
        }
    }
}
