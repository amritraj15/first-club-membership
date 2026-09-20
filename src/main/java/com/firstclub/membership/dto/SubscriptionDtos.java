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
}
