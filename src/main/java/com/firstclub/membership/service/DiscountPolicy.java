package com.firstclub.membership.service;

import com.firstclub.membership.domain.TierBenefit;

import java.math.BigDecimal;
import java.util.List;

/**
 * Decides which single discount rate applies to a cart item when a tier has MORE THAN ONE
 * PERCENTAGE_DISCOUNT benefit that could match it (e.g. "10% off ALL" and "15% off Electronics"
 * both existing on the same tier). Deliberately pulled out as its own abstraction rather than
 * left as ad-hoc accumulation logic inside BenefitService - see
 * {@link CategoryOverridesGlobalDiscountPolicy} for the policy actually wired in, and its
 * Javadoc for why this one was chosen over the alternatives (stacking, highest-wins).
 * Swapping the bound implementation is the entire cost of changing the policy - no change
 * needed in BenefitService.
 */
public interface DiscountPolicy {

    /**
     * @param category                the cart item's category
     * @param percentageDiscountBenefits all PERCENTAGE_DISCOUNT benefits on the user's tier
     * @return the single rate (0-100) to apply to this item; BigDecimal.ZERO if none apply
     */
    BigDecimal resolveRate(String category, List<TierBenefit> percentageDiscountBenefits);
}
