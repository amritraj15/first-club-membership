package com.firstclub.membership.service;

import com.firstclub.membership.domain.BenefitType;
import com.firstclub.membership.domain.Tier;
import com.firstclub.membership.domain.TierBenefit;
import com.firstclub.membership.dto.AdminBenefitDtos.TierBenefitAdminResponse;
import com.firstclub.membership.dto.AdminBenefitDtos.UpsertTierBenefitRequest;
import com.firstclub.membership.exception.NotFoundException;
import com.firstclub.membership.repository.TierBenefitRepository;
import com.firstclub.membership.repository.TierRepository;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

/**
 * Runtime management for benefit rows only. Qualification criteria and tier topology are
 * intentionally excluded because they are policy changes needing a stronger workflow.
 */
@Service
public class AdminBenefitService {

    private final TierRepository tierRepository;
    private final TierBenefitRepository tierBenefitRepository;

    public AdminBenefitService(TierRepository tierRepository, TierBenefitRepository tierBenefitRepository) {
        this.tierRepository = tierRepository;
        this.tierBenefitRepository = tierBenefitRepository;
    }

    @Transactional(readOnly = true)
    public List<TierBenefitAdminResponse> list(Long tierId) {
        Tier tier = findTier(tierId);
        return tier.getBenefits().stream().map(this::toResponse).toList();
    }

    @Transactional
    @CacheEvict(cacheNames = {"tiers", "tiersDesc"}, allEntries = true)
    public TierBenefitAdminResponse add(Long tierId, UpsertTierBenefitRequest request) {
        Tier tier = findTier(tierId);
        validate(request);
        TierBenefit benefit = new TierBenefit(request.benefitType(), request.paramValue(), request.scope().trim());
        tier.addBenefit(benefit);
        // Save the child explicitly so the generated identifier used in the 201 response is
        // the managed instance's identifier, not a pre-merge transient aggregate child.
        TierBenefit saved = tierBenefitRepository.saveAndFlush(benefit);
        return toResponse(saved);
    }

    @Transactional
    @CacheEvict(cacheNames = {"tiers", "tiersDesc"}, allEntries = true)
    public TierBenefitAdminResponse update(Long benefitId, UpsertTierBenefitRequest request) {
        validate(request);
        TierBenefit benefit = tierBenefitRepository.findById(benefitId)
                .orElseThrow(() -> new NotFoundException("Tier benefit not found: " + benefitId));
        benefit.update(request.benefitType(), request.paramValue(), request.scope().trim());
        return toResponse(benefit);
    }

    private Tier findTier(Long tierId) {
        return tierRepository.findById(tierId)
                .orElseThrow(() -> new NotFoundException("Tier not found: " + tierId));
    }

    private void validate(UpsertTierBenefitRequest request) {
        BigDecimal value = request.paramValue();
        if (request.benefitType() == BenefitType.PERCENTAGE_DISCOUNT
                || request.benefitType() == BenefitType.EXCLUSIVE_DEAL) {
            if (value.compareTo(BigDecimal.ZERO) <= 0 || value.compareTo(BigDecimal.valueOf(100)) > 0) {
                throw new IllegalArgumentException("Discount percentage must be greater than 0 and at most 100");
            }
        }
        if (request.benefitType() == BenefitType.EARLY_ACCESS && value.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Early-access days must be greater than 0");
        }
        if (request.benefitType() == BenefitType.EXPEDITED_DELIVERY && value.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Expedited-delivery days must be greater than 0");
        }
    }

    private TierBenefitAdminResponse toResponse(TierBenefit benefit) {
        Tier tier = benefit.getTier();
        return new TierBenefitAdminResponse(benefit.getId(), tier.getId(), tier.getName().name(),
                benefit.getBenefitType(), benefit.getParamValue(), benefit.getScope());
    }
}
