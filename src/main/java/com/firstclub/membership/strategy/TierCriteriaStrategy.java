package com.firstclub.membership.strategy;

import com.firstclub.membership.domain.CriteriaType;
import com.firstclub.membership.domain.OrderRecord;
import com.firstclub.membership.domain.TierCriterion;
import com.firstclub.membership.domain.User;

import java.util.List;

/**
 * One pluggable rule for whether a user satisfies a single {@link TierCriterion}.
 * <p>
 * This is the ONE piece of the design where a full pattern (Strategy) is deliberately used
 * rather than a simple enum switch, because the spec explicitly lists three heterogeneous,
 * independently-extensible criteria types and implies more may be added. Adding a new criterion
 * type (e.g. "referral count") means: add one enum value to {@link CriteriaType}, add one new
 * class implementing this interface, and register it - zero changes to
 * {@link TierEvaluator} or any existing strategy.
 */
public interface TierCriteriaStrategy {

    CriteriaType supportedType();

    /**
     * @param user          the user being evaluated
     * @param recentOrders  the user's non-cancelled orders in the evaluation window
     * @param criterion     the specific threshold/config to evaluate against
     */
    boolean isSatisfied(User user, List<OrderRecord> recentOrders, TierCriterion criterion);
}
