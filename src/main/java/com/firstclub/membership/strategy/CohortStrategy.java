package com.firstclub.membership.strategy;

import com.firstclub.membership.domain.CriteriaType;
import com.firstclub.membership.domain.OrderRecord;
import com.firstclub.membership.domain.TierCriterion;
import com.firstclub.membership.domain.User;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

/** "User belonging to a certain cohort". A null cohort on the user, or a null cohortName on the
 *  criterion, must resolve to "not satisfied", never throw - a criterion is misconfigured data,
 *  not a caller error. */
@Component
public class CohortStrategy implements TierCriteriaStrategy {

    @Override
    public CriteriaType supportedType() {
        return CriteriaType.COHORT;
    }

    @Override
    public boolean isSatisfied(User user, List<OrderRecord> recentOrders, TierCriterion criterion) {
        if (user.getCohort() == null || criterion.getCohortName() == null) {
            return false;
        }
        return Objects.equals(user.getCohort(), criterion.getCohortName());
    }
}
