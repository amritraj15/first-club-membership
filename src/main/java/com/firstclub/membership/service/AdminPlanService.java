package com.firstclub.membership.service;

import com.firstclub.membership.domain.Plan;
import com.firstclub.membership.domain.PlanVersion;
import com.firstclub.membership.dto.AdminPlanDtos.PlanPriceAdminResponse;
import com.firstclub.membership.dto.AdminPlanDtos.UpdatePlanPriceRequest;
import com.firstclub.membership.exception.NotFoundException;
import com.firstclub.membership.repository.PlanRepository;
import com.firstclub.membership.repository.PlanVersionRepository;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Runtime price changes create a new immutable version; existing subscriptions keep their old version. */
@Service
public class AdminPlanService {
    private final PlanRepository planRepository;
    private final PlanVersionRepository planVersionRepository;

    public AdminPlanService(PlanRepository planRepository, PlanVersionRepository planVersionRepository) {
        this.planRepository = planRepository;
        this.planVersionRepository = planVersionRepository;
    }

    @Transactional
    @CacheEvict(cacheNames = "plans", allEntries = true)
    public PlanPriceAdminResponse updatePrice(Long planId, UpdatePlanPriceRequest request) {
        Plan plan = planRepository.findByIdForUpdate(planId)
                .orElseThrow(() -> new NotFoundException("Plan not found: " + planId));
        PlanVersion latest = planVersionRepository.findTopByPlanIdOrderByVersionNumberDesc(planId)
                .orElseThrow(() -> new NotFoundException("No price version configured for plan: " + planId));
        int nextVersion = latest.getVersionNumber() + 1;
        String currency = request.currency().trim().toUpperCase();
        PlanVersion version = planVersionRepository.save(
                new PlanVersion(plan, nextVersion, request.price(), currency));
        plan.updateCurrentPrice(request.price(), currency);
        planRepository.save(plan);
        return new PlanPriceAdminResponse(planId, version.getVersionNumber(), version.getPrice(), version.getCurrency());
    }
}
