package com.firstclub.membership.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public final class AdminPlanDtos {
    private AdminPlanDtos() {}

    public record UpdatePlanPriceRequest(
            @NotNull @DecimalMin(value = "0.01") BigDecimal price,
            @NotBlank String currency
    ) {}

    public record PlanPriceAdminResponse(
            Long planId,
            int version,
            BigDecimal price,
            String currency
    ) {}
}
