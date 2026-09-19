package com.firstclub.membership.web;

import com.firstclub.membership.domain.Plan;
import com.firstclub.membership.domain.Tier;
import com.firstclub.membership.domain.TierBenefit;
import com.firstclub.membership.domain.TierCriterion;
import com.firstclub.membership.dto.PlanDtos.*;
import com.firstclub.membership.service.PlanService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** FR1/FR2 - "Get Membership Plans and Tier to be selected by the user". */
@RestController
@RequestMapping("/api")
public class PlanController {

    private final PlanService planService;

    public PlanController(PlanService planService) {
        this.planService = planService;
    }

    @GetMapping("/plans")
    public List<PlanResponse> listPlans() {
        return planService.listPlans().stream().map(this::toResponse).toList();
    }

    @GetMapping("/tiers")
    public List<TierResponse> listTiers() {
        return planService.listTiersAscending().stream().map(this::toResponse).toList();
    }

    private PlanResponse toResponse(Plan plan) {
        return new PlanResponse(plan.getId(), plan.getPlanType(), plan.getPrice(), plan.getCurrency());
    }

    private TierResponse toResponse(Tier tier) {
        List<BenefitResponse> benefits = tier.getBenefits().stream()
                .map(this::toResponse)
                .toList();
        List<CriterionResponse> criteria = tier.getCriteria().stream()
                .map(this::toResponse)
                .toList();
        return new TierResponse(tier.getId(), tier.getName().name(), tier.getRank(),
                tier.getCriteriaMatchMode().name(), benefits, criteria);
    }

    private BenefitResponse toResponse(TierBenefit b) {
        return new BenefitResponse(b.getBenefitType().name(), b.getParamValue(), b.getScope());
    }

    private CriterionResponse toResponse(TierCriterion c) {
        return new CriterionResponse(c.getCriteriaType().name(), c.getThreshold(), c.getCohortName(),
                c.getWindowType().name(), c.getRollingWindowDays());
    }
}
