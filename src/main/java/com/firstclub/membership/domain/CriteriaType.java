package com.firstclub.membership.domain;

/** Kinds of signal that can promote a user into a tier. Each maps to exactly one
 *  {@link com.firstclub.membership.strategy.TierCriteriaStrategy} implementation. */
public enum CriteriaType {
    MIN_ORDER_COUNT,
    MIN_ORDER_VALUE,
    COHORT
}
