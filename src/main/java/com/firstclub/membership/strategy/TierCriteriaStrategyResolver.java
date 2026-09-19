package com.firstclub.membership.strategy;

import com.firstclub.membership.domain.CriteriaType;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Wires up every {@link TierCriteriaStrategy} bean by its {@link CriteriaType} once at startup.
 *  Spring injects all beans implementing the interface here - a new @Component strategy is
 *  picked up automatically, no change needed in this class. */
@Component
public class TierCriteriaStrategyResolver {

    private final Map<CriteriaType, TierCriteriaStrategy> strategiesByType = new EnumMap<>(CriteriaType.class);

    public TierCriteriaStrategyResolver(List<TierCriteriaStrategy> strategies) {
        for (TierCriteriaStrategy strategy : strategies) {
            strategiesByType.put(strategy.supportedType(), strategy);
        }
    }

    public TierCriteriaStrategy resolve(CriteriaType type) {
        TierCriteriaStrategy strategy = strategiesByType.get(type);
        if (strategy == null) {
            throw new IllegalStateException("No TierCriteriaStrategy registered for " + type);
        }
        return strategy;
    }
}
