package com.firstclub.membership.service;

import com.firstclub.membership.domain.Plan;
import com.firstclub.membership.domain.Tier;
import com.firstclub.membership.exception.NotFoundException;
import com.firstclub.membership.repository.PlanRepository;
import com.firstclub.membership.repository.TierRepository;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Read access to Plan and Tier configuration. Both are cached (Spring's built-in
 * ConcurrentMapCache, no extra dependency needed) because they change rarely and sit on the
 * checkout-latency-sensitive path via BenefitService - see README performance section for why
 * this keeps benefit lookup well under the target latency.
 */
@Service
public class PlanService {

    private final PlanRepository planRepository;
    private final TierRepository tierRepository;

    public PlanService(PlanRepository planRepository, TierRepository tierRepository) {
        this.planRepository = planRepository;
        this.tierRepository = tierRepository;
    }

    @Cacheable("plans")
    public List<Plan> listPlans() {
        return planRepository.findAll();
    }

    @Cacheable("tiers")
    public List<Tier> listTiersAscending() {
        return tierRepository.findAllByOrderByRankAsc();
    }

    @Cacheable("tiersDesc")
    public List<Tier> listTiersDescending() {
        return tierRepository.findAllByOrderByRankDesc();
    }

    public Plan getPlan(Long id) {
        return planRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Plan not found: " + id));
    }

    public Tier getTier(Long id) {
        return tierRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Tier not found: " + id));
    }
}
