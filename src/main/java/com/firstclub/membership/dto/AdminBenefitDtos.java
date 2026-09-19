package com.firstclub.membership.dto;

import com.firstclub.membership.domain.BenefitType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/** Request and response shapes for the intentionally small protected admin API. */
public final class AdminBenefitDtos {

    private AdminBenefitDtos() {
    }

    public record UpsertTierBenefitRequest(
            @NotNull BenefitType benefitType,
            @NotNull @DecimalMin(value = "0.0", inclusive = true) BigDecimal paramValue,
            @NotBlank String scope
    ) {
    }

    public record TierBenefitAdminResponse(
            Long id,
            Long tierId,
            String tierName,
            BenefitType benefitType,
            BigDecimal paramValue,
            String scope
    ) {
    }
}
