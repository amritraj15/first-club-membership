package com.firstclub.membership.domain;

/**
 * The period used to evaluate an order-based tier criterion. Calendar month is the default
 * because it matches the assignment wording; rolling days remains available for campaigns that
 * should not reset at a month boundary.
 */
public enum CriteriaWindowType {
    CALENDAR_MONTH,
    ROLLING_DAYS
}
