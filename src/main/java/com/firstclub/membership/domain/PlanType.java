package com.firstclub.membership.domain;

/** The billing duration a user subscribes for. Orthogonal to {@link TierName}. */
public enum PlanType {
    MONTHLY(1),
    QUARTERLY(3),
    YEARLY(12);

    private final int months;

    PlanType(int months) {
        this.months = months;
    }

    public int getMonths() {
        return months;
    }
}
