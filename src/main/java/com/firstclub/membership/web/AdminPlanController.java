package com.firstclub.membership.web;

import com.firstclub.membership.dto.AdminPlanDtos.PlanPriceAdminResponse;
import com.firstclub.membership.dto.AdminPlanDtos.UpdatePlanPriceRequest;
import com.firstclub.membership.service.AdminPlanService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Protected plan-price administration. Every price change creates an immutable PlanVersion. */
@RestController
@RequestMapping("/api/admin/plans")
public class AdminPlanController {
    private final AdminPlanService adminPlanService;

    public AdminPlanController(AdminPlanService adminPlanService) {
        this.adminPlanService = adminPlanService;
    }

    @PatchMapping("/{planId}/price")
    public PlanPriceAdminResponse updatePrice(@PathVariable Long planId,
                                              @Valid @RequestBody UpdatePlanPriceRequest request) {
        return adminPlanService.updatePrice(planId, request);
    }
}
