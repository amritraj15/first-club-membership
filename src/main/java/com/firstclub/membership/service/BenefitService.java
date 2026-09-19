package com.firstclub.membership.service;

import com.firstclub.membership.domain.BenefitType;
import com.firstclub.membership.domain.Subscription;
import com.firstclub.membership.domain.Tier;
import com.firstclub.membership.domain.TierBenefit;
import com.firstclub.membership.dto.CheckoutDtos.AppliedBenefit;
import com.firstclub.membership.dto.CheckoutDtos.CartItem;
import com.firstclub.membership.dto.CheckoutDtos.CheckoutResponse;
import com.firstclub.membership.dto.ExclusiveDealDtos.ExclusiveDealResponse;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Applies a user's tier benefits to a cart at checkout. This is the latency-sensitive path
 * referenced in the design write-up: the tier's benefit list is small, bounded, and comes from
 * {@link PlanService}'s cached tier lookup (no per-benefit DB round trip), so this method is
 * pure in-memory computation - O(items x benefits), both tiny and bounded - comfortably inside
 * a sub-10ms budget. The expensive part of this whole system (scanning order history to
 * evaluate tier) deliberately never runs on this path; see TierEvaluationService.
 * <p>
 * PERCENTAGE_DISCOUNT is resolved PER CART ITEM via {@link DiscountPolicy}, not by summing a
 * scope-total across the whole cart. An exact category {@code EXCLUSIVE_DEAL} has precedence
 * over that normal rate, and also never stacks. An earlier version of this method computed each
 * PERCENTAGE_DISCOUNT benefit's total independently over its matching items and added them all
 * up - which meant a tier with both "10% off ALL" and "15% off Electronics" applied BOTH to an
 * Electronics item, silently producing a 25% effective discount. Resolving one rate per item
 * through the policy makes that bug structurally impossible: each item contributes exactly one
 * discount amount, decided by exactly one rate.
 */
@Service
public class BenefitService {

    private final DiscountPolicy discountPolicy;

    public BenefitService(DiscountPolicy discountPolicy) {
        this.discountPolicy = discountPolicy;
    }

    public CheckoutResponse applyBenefits(Subscription subscription, List<CartItem> items) {
        Tier tier = subscription.getTier();
        BigDecimal cartTotal = items.stream()
                .map(CartItem::price)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        boolean freeDelivery = false;
        boolean prioritySupport = false;
        List<AppliedBenefit> nonDiscountBenefits = new ArrayList<>();

        List<TierBenefit> percentageDiscountBenefits = new ArrayList<>();
        List<TierBenefit> exclusiveDeals = new ArrayList<>();
        for (TierBenefit benefit : tier.getBenefits()) {
            switch (benefit.getBenefitType()) {
                case FREE_DELIVERY -> {
                    freeDelivery = true;
                    nonDiscountBenefits.add(new AppliedBenefit(BenefitType.FREE_DELIVERY.name(), benefit.getScope(),
                            BigDecimal.ZERO, benefit.getParamValue()));
                }
                case PRIORITY_SUPPORT -> {
                    prioritySupport = true;
                    nonDiscountBenefits.add(new AppliedBenefit(BenefitType.PRIORITY_SUPPORT.name(), benefit.getScope(),
                            BigDecimal.ZERO, benefit.getParamValue()));
                }
                case EARLY_ACCESS -> nonDiscountBenefits.add(
                        new AppliedBenefit(BenefitType.EARLY_ACCESS.name(), benefit.getScope(),
                                BigDecimal.ZERO, benefit.getParamValue()));
                case PERCENTAGE_DISCOUNT -> percentageDiscountBenefits.add(benefit);
                case EXCLUSIVE_DEAL -> exclusiveDeals.add(benefit);
            }
        }

        // A category-specific exclusive deal wins for that item; otherwise the normal one-rate
        // discount policy applies. Neither path stacks rates.
        BigDecimal totalDiscount = BigDecimal.ZERO;
        Map<String, AppliedDiscount> discountByResolvedScope = new LinkedHashMap<>();
        for (CartItem item : items) {
            TierBenefit exclusiveDeal = findExclusiveDeal(item.category(), exclusiveDeals);
            BigDecimal rate = exclusiveDeal == null
                    ? discountPolicy.resolveRate(item.category(), percentageDiscountBenefits)
                    : exclusiveDeal.getParamValue();
            if (rate.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            BigDecimal itemDiscount = item.price()
                    .multiply(rate)
                    .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
            totalDiscount = totalDiscount.add(itemDiscount);
            String benefitType = exclusiveDeal == null
                    ? BenefitType.PERCENTAGE_DISCOUNT.name() : BenefitType.EXCLUSIVE_DEAL.name();
            String resolvedLabel = exclusiveDeal != null ? exclusiveDeal.getScope()
                    : matchesCategory(item.category(), percentageDiscountBenefits) ? item.category() : "ALL";
            String key = benefitType + "|" + resolvedLabel + "|" + rate;
            discountByResolvedScope.merge(key,
                    new AppliedDiscount(benefitType, resolvedLabel, itemDiscount, rate),
                    (left, right) -> new AppliedDiscount(left.benefitType(), left.scope(),
                            left.discountAmount().add(right.discountAmount()), left.configuredValue()));
        }

        List<AppliedBenefit> appliedDiscounts = discountByResolvedScope.values().stream()
                .map(e -> new AppliedBenefit(e.benefitType(), e.scope(), e.discountAmount(), e.configuredValue()))
                .toList();

        List<AppliedBenefit> applied = new ArrayList<>(nonDiscountBenefits);
        applied.addAll(appliedDiscounts);

        BigDecimal finalTotal = cartTotal.subtract(totalDiscount).max(BigDecimal.ZERO);
        return new CheckoutResponse(cartTotal, totalDiscount, freeDelivery, prioritySupport, finalTotal, applied);
    }

    public List<ExclusiveDealResponse> exclusiveDeals(Subscription subscription) {
        return subscription.getTier().getBenefits().stream()
                .filter(benefit -> benefit.getBenefitType() == BenefitType.EXCLUSIVE_DEAL)
                .map(benefit -> new ExclusiveDealResponse(benefit.getScope(), benefit.getParamValue()))
                .toList();
    }

    private TierBenefit findExclusiveDeal(String category, List<TierBenefit> exclusiveDeals) {
        return exclusiveDeals.stream()
                .filter(deal -> deal.getScope().equalsIgnoreCase(category))
                .findFirst()
                .orElse(null);
    }

    private boolean matchesCategory(String category, List<TierBenefit> percentageDiscountBenefits) {
        return percentageDiscountBenefits.stream()
                .anyMatch(b -> b.getScope().equalsIgnoreCase(category));
    }

    private record AppliedDiscount(String benefitType, String scope, BigDecimal discountAmount,
                                   BigDecimal configuredValue) {
    }
}
