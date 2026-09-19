package com.firstclub.membership.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public class OrderDtos {

    private OrderDtos() {
    }

    public record PlaceOrderRequest(
            @NotNull @DecimalMin(value = "0.0", inclusive = true) BigDecimal value
    ) {
    }

    public record OrderResponse(Long orderId, Long userId, BigDecimal value, boolean cancelled, String placedAt) {
    }

    /** Returned after placing an order, so the caller can see immediately whether it triggered
     *  a tier change - useful for demoing the promotion flow end-to-end in one call. */
    public record OrderPlacedResponse(OrderResponse order, boolean tierChanged, String currentTier) {
    }
}
