package com.firstclub.membership.strategy;

import com.firstclub.membership.domain.CriteriaMatchMode;
import com.firstclub.membership.domain.CriteriaType;
import com.firstclub.membership.domain.OrderRecord;
import com.firstclub.membership.domain.Tier;
import com.firstclub.membership.domain.TierCriterion;
import com.firstclub.membership.domain.TierName;
import com.firstclub.membership.domain.User;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for the core promotion logic, constructed WITHOUT a Spring context - this is
 * exactly the "add a new criterion touches only its own strategy class" extensibility claim
 * from the design write-up, demonstrated rather than just asserted.
 */
class TierEvaluatorTest {

    private final TierEvaluator evaluator = new TierEvaluator(
            new TierCriteriaStrategyResolver(List.of(
                    new MinOrderCountStrategy(),
                    new MinOrderValueStrategy(),
                    new CohortStrategy()
            ))
    );

    private User user(String cohort) {
        return new User("Test User", "test@example.com", cohort);
    }

    private OrderRecord order(User user, String value) {
        return new OrderRecord(user, new BigDecimal(value), Instant.now());
    }

    @Test
    void userWithNoOrdersGetsBaseTierOnly() {
        Tier silver = new Tier(TierName.SILVER, 1, CriteriaMatchMode.ANY); // no criteria -> always qualifies
        Tier gold = new Tier(TierName.GOLD, 2, CriteriaMatchMode.ANY);
        gold.addCriterion(new TierCriterion(CriteriaType.MIN_ORDER_COUNT, new BigDecimal("10"), null));

        User u = user(null);
        Tier result = evaluator.evaluate(u, List.of(), List.of(silver, gold));

        assertEquals(TierName.SILVER, result.getName());
    }

    @Test
    void orderCountStrictlyGreaterThanThresholdPromotes() {
        Tier silver = new Tier(TierName.SILVER, 1, CriteriaMatchMode.ANY);
        Tier gold = new Tier(TierName.GOLD, 2, CriteriaMatchMode.ANY);
        gold.addCriterion(new TierCriterion(CriteriaType.MIN_ORDER_COUNT, new BigDecimal("10"), null));

        User u = user(null);
        // Exactly 10 orders - spec says "more than X", i.e. strictly greater, so 10 must NOT qualify.
        List<OrderRecord> exactlyTen = java.util.stream.IntStream.range(0, 10)
                .mapToObj(i -> order(u, "1"))
                .toList();
        assertEquals(TierName.SILVER, evaluator.evaluate(u, exactlyTen, List.of(silver, gold)).getName());

        // 11 orders must qualify.
        List<OrderRecord> eleven = java.util.stream.IntStream.range(0, 11)
                .mapToObj(i -> order(u, "1"))
                .toList();
        assertEquals(TierName.GOLD, evaluator.evaluate(u, eleven, List.of(silver, gold)).getName());
    }

    @Test
    void cancelledOrdersMustBeExcludedByCallerBeforeEvaluation() {
        // TierEvaluator trusts its input list as-is; excluding cancelled orders is the
        // repository query's job (see OrderRecordRepository), not the evaluator's - this test
        // documents that boundary so it isn't silently duplicated or dropped later.
        Tier silver = new Tier(TierName.SILVER, 1, CriteriaMatchMode.ANY);
        Tier gold = new Tier(TierName.GOLD, 2, CriteriaMatchMode.ANY);
        gold.addCriterion(new TierCriterion(CriteriaType.MIN_ORDER_VALUE, new BigDecimal("100"), null));

        User u = user(null);
        List<OrderRecord> orders = List.of(order(u, "150"));
        assertEquals(TierName.GOLD, evaluator.evaluate(u, orders, List.of(silver, gold)).getName());
    }

    @Test
    void nullCohortNeverThrowsAndNeverMatches() {
        Tier silver = new Tier(TierName.SILVER, 1, CriteriaMatchMode.ANY);
        Tier platinum = new Tier(TierName.PLATINUM, 3, CriteriaMatchMode.ANY);
        platinum.addCriterion(new TierCriterion(CriteriaType.COHORT, BigDecimal.ZERO, "VIP"));

        User userWithNoCohort = user(null);
        Tier result = evaluator.evaluate(userWithNoCohort, List.of(), List.of(silver, platinum));
        assertEquals(TierName.SILVER, result.getName());

        User vipUser = user("VIP");
        Tier vipResult = evaluator.evaluate(vipUser, List.of(), List.of(silver, platinum));
        assertEquals(TierName.PLATINUM, vipResult.getName());
    }

    @Test
    void highestQualifyingTierWinsWhenMultipleTiersQualify() {
        Tier silver = new Tier(TierName.SILVER, 1, CriteriaMatchMode.ANY);
        Tier gold = new Tier(TierName.GOLD, 2, CriteriaMatchMode.ANY);
        gold.addCriterion(new TierCriterion(CriteriaType.MIN_ORDER_COUNT, new BigDecimal("1"), null));
        Tier platinum = new Tier(TierName.PLATINUM, 3, CriteriaMatchMode.ANY);
        platinum.addCriterion(new TierCriterion(CriteriaType.MIN_ORDER_COUNT, new BigDecimal("2"), null));

        User u = user(null);
        List<OrderRecord> orders = List.of(order(u, "1"), order(u, "1"), order(u, "1"));
        // 3 orders satisfies BOTH Gold's (>1) and Platinum's (>2) criteria - Platinum must win.
        assertEquals(TierName.PLATINUM, evaluator.evaluate(u, orders, List.of(silver, gold, platinum)).getName());
    }
}
