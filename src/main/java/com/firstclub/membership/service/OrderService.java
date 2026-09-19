package com.firstclub.membership.service;

import com.firstclub.membership.domain.OrderRecord;
import com.firstclub.membership.domain.Subscription;
import com.firstclub.membership.domain.SubscriptionStatus;
import com.firstclub.membership.domain.User;
import com.firstclub.membership.exception.NotFoundException;
import com.firstclub.membership.repository.OrderRecordRepository;
import com.firstclub.membership.repository.SubscriptionRepository;
import com.firstclub.membership.repository.UserRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;

/**
 * Records order activity and triggers tier re-evaluation. Placing/cancelling an order is the
 * one write path that also clears {@code manualTierOverride}: new order activity is the signal
 * that the user's eligibility may have genuinely changed, so it re-enters normal automatic
 * evaluation rather than staying pinned to a manual choice made before this new activity
 * happened. See TierEvaluationService javadoc for the full policy discussion.
 * <p>
 * Deliberately NOT event-driven (no message bus / listener): {@link TierEvaluationService}
 * is called synchronously but in its OWN transaction (REQUIRES_NEW), so the order write itself
 * can never be rolled back or blocked by a tier-evaluation bug - see that class's javadoc.
 */
@Service
public class OrderService {

    private final OrderRecordRepository orderRecordRepository;
    private final UserRepository userRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final TierEvaluationService tierEvaluationService;
    private final Clock clock;

    public OrderService(OrderRecordRepository orderRecordRepository,
                         UserRepository userRepository,
                         SubscriptionRepository subscriptionRepository,
                         TierEvaluationService tierEvaluationService,
                         Clock clock) {
        this.orderRecordRepository = orderRecordRepository;
        this.userRepository = userRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.tierEvaluationService = tierEvaluationService;
        this.clock = clock;
    }

    /**
     * NOTE ON TRANSACTION BOUNDARIES: deliberately NOT wrapped in one outer @Transactional
     * spanning both the order write and the tier evaluation. Each Spring Data JPA repository
     * call (save/findById) is already transactional on its own by default, so
     * {@link #recordOrderAndClearOverride} commits the order insert and the override-clear
     * (as two small, independently-committed writes) before this method returns control here.
     * Only THEN does {@link TierEvaluationService#reevaluateSafely} start its own
     * REQUIRES_NEW transaction. Had we instead wrapped everything in one outer @Transactional,
     * the override-clear would still be uncommitted when evaluation's separate-connection
     * transaction reads it - it would see the OLD value and wrongly skip evaluation on the very
     * order that should have re-enabled it. The tradeoff accepted here is a small window of
     * non-atomicity between "order recorded" and "override cleared" (both are minor,
     * self-correcting bookkeeping - not worth a distributed-transaction fix) in exchange for
     * avoiding that correctness bug.
     */
    public PlaceOrderResult placeOrder(Long userId, BigDecimal value) {
        OrderRecord order = recordOrderAndClearOverride(userId, value);
        boolean tierChanged = tierEvaluationService.reevaluateSafely(userId);
        return new PlaceOrderResult(order, tierChanged);
    }

    public OrderRecord cancelOrder(Long orderId) {
        OrderRecord order = markCancelledAndClearOverride(orderId);
        tierEvaluationService.reevaluateSafely(order.getUser().getId());
        return order;
    }

    /** Package-visible (not private) only so it reads clearly as its own unit of work; called
     *  from within this class, so Spring's @Transactional proxy would NOT apply here even if
     *  annotated - each repository call inside is already individually transactional, which is
     *  sufficient for the guarantee this method actually needs (see javadoc above). */
    OrderRecord recordOrderAndClearOverride(Long userId, BigDecimal value) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException("User not found: " + userId));
        OrderRecord order = orderRecordRepository.save(new OrderRecord(user, value, clock.instant()));
        clearManualOverrideIfPresent(userId);
        return order;
    }

    OrderRecord markCancelledAndClearOverride(Long orderId) {
        OrderRecord order = orderRecordRepository.findById(orderId)
                .orElseThrow(() -> new NotFoundException("Order not found: " + orderId));
        order.setCancelled(true);
        order = orderRecordRepository.save(order);
        clearManualOverrideIfPresent(order.getUser().getId());
        return order;
    }

    private void clearManualOverrideIfPresent(Long userId) {
        subscriptionRepository.findFirstByUserIdAndStatusOrderByStartDateDesc(userId, SubscriptionStatus.ACTIVE)
                .filter(Subscription::isManualTierOverride)
                .ifPresent(sub -> {
                    sub.setManualTierOverride(false);
                    subscriptionRepository.save(sub);
                });
    }

    public record PlaceOrderResult(OrderRecord order, boolean tierChanged) {
    }
}
