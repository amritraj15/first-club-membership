package com.firstclub.membership.domain;

/** Lifecycle states for a {@link Subscription}. Valid transitions are enforced by
 *  {@link com.firstclub.membership.service.SubscriptionStateMachine}, not by subclassing -
 *  a full GoF State pattern was considered and deliberately rejected as more machinery than
 *  4 states with simple transition rules justifies (see README, "what we did not build"). */
public enum SubscriptionStatus {
    ACTIVE,
    CANCELLED,
    EXPIRED
}
