package com.firstclub.membership.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.util.List;

public class CheckoutDtos {

    private CheckoutDtos() {
    }

    public record CartItem(
            @NotBlank String category,
            @NotNull @Positive BigDecimal price
    ) {
    }

    public record CheckoutRequest(
            @NotEmpty List<CartItem> items
    ) {
    }

    public record AppliedBenefit(String benefitType, String scope, BigDecimal discountAmount) {
    }

    public record CheckoutResponse(
            BigDecimal cartTotal,
            BigDecimal totalDiscount,
            boolean freeDelivery,
            boolean prioritySupport,
            BigDecimal finalTotal,
            List<AppliedBenefit> appliedBenefits
    ) {
    }
}
