package com.firstclub.membership.strategy;

import com.firstclub.membership.domain.CriteriaMatchMode;
import com.firstclub.membership.domain.OrderRecord;
import com.firstclub.membership.domain.Tier;
import com.firstclub.membership.domain.TierCriterion;
import com.firstclub.membership.domain.User;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Given a user's recent order history and the full set of tiers, finds the HIGHEST-ranked tier
 * whose criteria the user satisfies. A tier with no criteria at all (e.g. Silver, the default
 * entry tier) is treated as always-satisfied, so every user qualifies for at least the base
 * tier.
 */
@Component
public class TierEvaluator {

    private final TierCriteriaStrategyResolver resolver;

    public TierEvaluator(TierCriteriaStrategyResolver resolver) {
        this.resolver = resolver;
    }

    /** @return the highest-ranked tier the user currently qualifies for, out of {@code tiers}. */
    public Tier evaluate(User user, List<OrderRecord> recentOrders, List<Tier> tiers) {
        return tiers.stream()
                .filter(tier -> qualifies(user, recentOrders, tier))
                .max(Comparator.comparingInt(Tier::getRank))
                .orElseThrow(() -> new IllegalStateException(
                        "No tier qualifies - every tier deck must include a criteria-free base tier"));
    }

    private boolean qualifies(User user, List<OrderRecord> recentOrders, Tier tier) {
        List<TierCriterion> criteria = tier.getCriteria();
        if (criteria.isEmpty()) {
            return true;
        }
        if (tier.getCriteriaMatchMode() == CriteriaMatchMode.ALL) {
            return criteria.stream().allMatch(c -> matches(user, recentOrders, c));
        }
        return criteria.stream().anyMatch(c -> matches(user, recentOrders, c));
    }

    private boolean matches(User user, List<OrderRecord> recentOrders, TierCriterion criterion) {
        return resolver.resolve(criterion.getCriteriaType()).isSatisfied(user, recentOrders, criterion);
    }
}
