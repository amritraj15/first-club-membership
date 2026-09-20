package com.firstclub.membership.dto;

import jakarta.validation.constraints.NotNull;

public class SubscriptionDtos {

    private SubscriptionDtos() {
    }

    public record SubscribeRequest(
            @NotNull Long userId,
            @NotNull Long planId,
            @NotNull Long tierId
    ) {
    }

    public record ChangeTierRequest(
            @NotNull Long newTierId
    ) {
    }

    public record MembershipStatusResponse(
            Long subscriptionId,
            Long userId,
            String planType,
            String tierName,
            String status,
            String tierSource,
            String startDate,
            String endDate,
            long daysRemaining,
            int planPriceVersion,
            java.math.BigDecimal price,
            String currency
    ) {
    }

    /** One row of {@code GET /users/{userId}/tier-history} - see {@link
     *  com.firstclub.membership.domain.TierChangeAudit}. {@code previousTierName} is null only
     *  for a subscription's very first entry (the initial tier chosen at subscribe time). */
    public record TierChangeHistoryEntry(
            Long subscriptionId,
            String previousTierName,
            String newTierName,
            String tierSource,
            String changedAt
    ) {
    }
}
