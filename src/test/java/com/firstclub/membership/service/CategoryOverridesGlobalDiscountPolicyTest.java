package com.firstclub.membership.service;

import com.firstclub.membership.domain.BenefitType;
import com.firstclub.membership.domain.TierBenefit;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Isolated unit test for the exact arithmetic {@link CategoryOverridesGlobalDiscountPolicy}'s
 * own Javadoc says was the source of a real bug (10% ALL + 15% Electronics silently becoming
 * 25%). Before this test, that behaviour was only verified through
 * {@code MembershipApiIntegrationTest.categorySpecificDiscountOverridesGlobalDiscount_doesNotStack},
 * a full HTTP round-trip through subscription creation, tier resolution, and checkout - correct,
 * but slow to run and one indirection removed from the policy class itself. This test exercises
 * {@link CategoryOverridesGlobalDiscountPolicy#resolveRate} directly: no Spring context, no
 * database, no HTTP - a pure function against a plain {@link TierBenefit} list, the same
 * "plain JUnit 5, no Spring context, fast" category as {@code TierEvaluatorTest} and
 * {@code SubscriptionStateMachineTest}.
 */
class CategoryOverridesGlobalDiscountPolicyTest {

    private final CategoryOverridesGlobalDiscountPolicy policy = new CategoryOverridesGlobalDiscountPolicy();

    @Test
    void categorySpecificRateAppliesInsteadOfAllScopeRate_notOnTopOfIt() {
        List<TierBenefit> benefits = List.of(
                new TierBenefit(BenefitType.PERCENTAGE_DISCOUNT, new BigDecimal("10"), "ALL"),
                new TierBenefit(BenefitType.PERCENTAGE_DISCOUNT, new BigDecimal("15"), "Electronics")
        );

        BigDecimal rate = policy.resolveRate("Electronics", benefits);

        // The regression this policy exists to prevent: 10 + 15 = 25 would mean the two rates
        // stacked. The correct answer is exactly 15 - the category-specific rate, alone.
        assertEquals(0, new BigDecimal("15").compareTo(rate),
                "category-specific and ALL-scope discounts must not stack");
    }

    @Test
    void nonMatchingCategoryFallsBackToAllScopeRate() {
        List<TierBenefit> benefits = List.of(
                new TierBenefit(BenefitType.PERCENTAGE_DISCOUNT, new BigDecimal("10"), "ALL"),
                new TierBenefit(BenefitType.PERCENTAGE_DISCOUNT, new BigDecimal("15"), "Electronics")
        );

        BigDecimal rate = policy.resolveRate("Groceries", benefits);

        assertEquals(0, new BigDecimal("10").compareTo(rate));
    }

    @Test
    void categoryMatchingIsCaseInsensitive() {
        List<TierBenefit> benefits = List.of(
                new TierBenefit(BenefitType.PERCENTAGE_DISCOUNT, new BigDecimal("15"), "electronics")
        );

        BigDecimal rate = policy.resolveRate("Electronics", benefits);

        assertEquals(0, new BigDecimal("15").compareTo(rate));
    }

    @Test
    void noMatchingBenefitAtAllResolvesToZero() {
        List<TierBenefit> benefits = List.of(
                new TierBenefit(BenefitType.PERCENTAGE_DISCOUNT, new BigDecimal("15"), "Electronics")
        );

        BigDecimal rate = policy.resolveRate("Groceries", benefits);

        assertEquals(0, BigDecimal.ZERO.compareTo(rate));
    }

    @Test
    void emptyBenefitListResolvesToZero() {
        BigDecimal rate = policy.resolveRate("Electronics", List.of());

        assertEquals(0, BigDecimal.ZERO.compareTo(rate));
    }

    @Test
    void duplicateCategoryScopedBenefitsOnSameCategory_firstOneWins() {
        // Documented fallback for malformed config (see the class's own Javadoc) - not a
        // supported configuration, but the behaviour should still be deterministic rather than
        // whatever order a Set or a differently-ordered query happens to return.
        List<TierBenefit> benefits = List.of(
                new TierBenefit(BenefitType.PERCENTAGE_DISCOUNT, new BigDecimal("15"), "Electronics"),
                new TierBenefit(BenefitType.PERCENTAGE_DISCOUNT, new BigDecimal("20"), "Electronics")
        );

        BigDecimal rate = policy.resolveRate("Electronics", benefits);

        assertEquals(0, new BigDecimal("15").compareTo(rate));
    }
}
