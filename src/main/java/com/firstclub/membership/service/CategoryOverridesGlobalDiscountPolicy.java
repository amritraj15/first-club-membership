package com.firstclub.membership.service;

import com.firstclub.membership.domain.TierBenefit;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

/**
 * The discount policy actually wired into this build: if a category-specific discount exists
 * for a cart item's category, it applies EXCLUSIVELY (the ALL-scope discount is ignored for
 * that item); only items with no category-specific match fall back to the ALL-scope rate.
 * <p>
 * This was chosen over the two alternatives the design review raised:
 * <ul>
 *   <li><b>Stackable</b> (sum every matching rate) - rejected: this is what produced the
 *       original bug (10% ALL + 15% Electronics silently becoming 25% on Electronics items,
 *       almost certainly not the intended semantics of a tier's benefit configuration).</li>
 *   <li><b>Highest-applicable-wins</b> - a reasonable alternative, but it means a curated,
 *       deliberately-smaller "15% off Electronics" promotion could be silently overridden by a
 *       later, larger blanket discount, which inverts the usual retail intent of a targeted
 *       promotion (targeted offers are usually meant to apply on top of / instead of the
 *       generic rate, not lose to whichever number happens to be bigger).</li>
 * </ul>
 * Category-overrides-global was chosen as the more predictable, common e-commerce convention:
 * a specific promotion always means what it says for the category it names.
 * <p>
 * If a tier ever needs more than one category-specific rate for the SAME category, the first
 * one found wins (tiers are not expected to be configured that way; this is a documented
 * fallback for malformed config, not a supported use case).
 */
@Component
public class CategoryOverridesGlobalDiscountPolicy implements DiscountPolicy {

    private static final String ALL_SCOPE = "ALL";

    @Override
    public BigDecimal resolveRate(String category, List<TierBenefit> percentageDiscountBenefits) {
        return percentageDiscountBenefits.stream()
                .filter(b -> b.getScope().equalsIgnoreCase(category))
                .map(TierBenefit::getParamValue)
                .findFirst()
                .orElseGet(() -> percentageDiscountBenefits.stream()
                        .filter(b -> b.getScope().equalsIgnoreCase(ALL_SCOPE))
                        .map(TierBenefit::getParamValue)
                        .findFirst()
                        .orElse(BigDecimal.ZERO));
    }
}
