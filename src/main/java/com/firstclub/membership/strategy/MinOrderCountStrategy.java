package com.firstclub.membership.strategy;

import com.firstclub.membership.domain.CriteriaType;
import com.firstclub.membership.domain.OrderRecord;
import com.firstclub.membership.domain.TierCriterion;
import com.firstclub.membership.domain.User;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * "Number of orders more than X" - the spec's literal wording is "more than", i.e. STRICTLY
 * greater than the threshold, not greater-or-equal. This is a common off-by-one trap in this
 * kind of exercise, so it is called out explicitly here rather than left implicit.
 */
@Component
public class MinOrderCountStrategy implements TierCriteriaStrategy {

    @Override
    public CriteriaType supportedType() {
        return CriteriaType.MIN_ORDER_COUNT;
    }

    @Override
    public boolean isSatisfied(User user, List<OrderRecord> recentOrders, TierCriterion criterion) {
        long count = recentOrders.size();
        return count > criterion.getThreshold().longValue();
    }
}
