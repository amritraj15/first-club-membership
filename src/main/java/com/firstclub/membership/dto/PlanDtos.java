package com.firstclub.membership.dto;

import com.firstclub.membership.domain.PlanType;

import java.math.BigDecimal;
import java.util.List;

public class PlanDtos {

    private PlanDtos() {
    }

    public record PlanResponse(Long id, PlanType planType, BigDecimal price, String currency) {
    }

    public record BenefitResponse(String benefitType, BigDecimal paramValue, String scope) {
    }

    public record CriterionResponse(String criteriaType, BigDecimal threshold, String cohortName) {
    }

    public record TierResponse(
            Long id,
            String name,
            int rank,
            String criteriaMatchMode,
            List<BenefitResponse> benefits,
            List<CriterionResponse> criteria
    ) {
    }
}
