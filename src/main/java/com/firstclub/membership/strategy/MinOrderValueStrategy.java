package com.firstclub.membership.strategy;

import com.firstclub.membership.domain.CriteriaType;
import com.firstclub.membership.domain.OrderRecord;
import com.firstclub.membership.domain.TierCriterion;
import com.firstclub.membership.domain.User;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

/** "Total order value in a month" - strictly greater than the configured threshold, for
 *  consistency with {@link MinOrderCountStrategy}'s reading of "more than". */
@Component
public class MinOrderValueStrategy implements TierCriteriaStrategy {

    @Override
    public CriteriaType supportedType() {
        return CriteriaType.MIN_ORDER_VALUE;
    }

    @Override
    public boolean isSatisfied(User user, List<OrderRecord> recentOrders, TierCriterion criterion) {
        BigDecimal total = recentOrders.stream()
                .map(OrderRecord::getValue)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return total.compareTo(criterion.getThreshold()) > 0;
    }
}
