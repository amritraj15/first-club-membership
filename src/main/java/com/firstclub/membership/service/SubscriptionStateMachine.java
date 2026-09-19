package com.firstclub.membership.service;

import com.firstclub.membership.domain.SubscriptionStatus;
import com.firstclub.membership.exception.InvalidTransitionException;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Guards subscription lifecycle transitions with a plain allowed-transitions map instead of a
 * full GoF State pattern (one class per state). For 3 states with simple rules, the map is
 * exactly as correct, far more readable in review, and trivial to extend by adding a map entry -
 * see README "what we deliberately did not build" for the reasoning.
 */
@Component
public class SubscriptionStateMachine {

    private static final Map<SubscriptionStatus, Set<SubscriptionStatus>> ALLOWED = new EnumMap<>(SubscriptionStatus.class);

    static {
        ALLOWED.put(SubscriptionStatus.ACTIVE, EnumSet.of(SubscriptionStatus.CANCELLED, SubscriptionStatus.EXPIRED));
        ALLOWED.put(SubscriptionStatus.CANCELLED, EnumSet.noneOf(SubscriptionStatus.class));
        ALLOWED.put(SubscriptionStatus.EXPIRED, EnumSet.noneOf(SubscriptionStatus.class));
    }

    public void assertTransitionAllowed(SubscriptionStatus from, SubscriptionStatus to) {
        Set<SubscriptionStatus> allowedTargets = ALLOWED.getOrDefault(from, Set.of());
        if (!allowedTargets.contains(to)) {
            throw new InvalidTransitionException(
                    "Cannot transition subscription from " + from + " to " + to);
        }
    }

    /** Tier changes (upgrade/downgrade) are only meaningful on a subscription that is actually
     *  active right now - a cancelled or expired subscription's tier is frozen history. */
    public void assertTierChangeAllowed(SubscriptionStatus current) {
        if (current != SubscriptionStatus.ACTIVE) {
            throw new InvalidTransitionException(
                    "Cannot change tier on a subscription with status " + current);
        }
    }
}
