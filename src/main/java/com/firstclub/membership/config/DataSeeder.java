package com.firstclub.membership.config;

import com.firstclub.membership.domain.BenefitType;
import com.firstclub.membership.domain.CriteriaMatchMode;
import com.firstclub.membership.domain.CriteriaType;
import com.firstclub.membership.domain.Plan;
import com.firstclub.membership.domain.PlanType;
import com.firstclub.membership.domain.Tier;
import com.firstclub.membership.domain.TierBenefit;
import com.firstclub.membership.domain.TierCriterion;
import com.firstclub.membership.domain.TierName;
import com.firstclub.membership.domain.User;
import com.firstclub.membership.repository.PlanRepository;
import com.firstclub.membership.repository.TierRepository;
import com.firstclub.membership.repository.UserRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Seeds demo-able data on startup: three plans, three tiers each with configurable benefits and
 * criteria, and one demo user - so every endpoint in README's curl walkthrough works immediately
 * against a fresh boot with no manual setup.
 */
@Component
public class DataSeeder implements CommandLineRunner {

    private final PlanRepository planRepository;
    private final TierRepository tierRepository;
    private final UserRepository userRepository;

    public DataSeeder(PlanRepository planRepository, TierRepository tierRepository, UserRepository userRepository) {
        this.planRepository = planRepository;
        this.tierRepository = tierRepository;
        this.userRepository = userRepository;
    }

    @Override
    public void run(String... args) {
        if (planRepository.count() > 0) {
            return; // Already seeded (e.g. re-run without a fresh in-memory DB).
        }

        planRepository.save(new Plan(PlanType.MONTHLY, new BigDecimal("199.00"), "INR"));
        planRepository.save(new Plan(PlanType.QUARTERLY, new BigDecimal("499.00"), "INR"));
        planRepository.save(new Plan(PlanType.YEARLY, new BigDecimal("1499.00"), "INR"));

        Tier silver = new Tier(TierName.SILVER, 1, CriteriaMatchMode.ANY);
        // Silver has NO criteria -> everyone qualifies; it is the base tier.
        silver.addBenefit(new TierBenefit(BenefitType.PERCENTAGE_DISCOUNT, new BigDecimal("2"), "ALL"));
        tierRepository.save(silver);

        Tier gold = new Tier(TierName.GOLD, 2, CriteriaMatchMode.ANY);
        gold.addCriterion(new TierCriterion(CriteriaType.MIN_ORDER_COUNT, new BigDecimal("10"), null));
        gold.addCriterion(new TierCriterion(CriteriaType.MIN_ORDER_VALUE, new BigDecimal("5000"), null));
        gold.addBenefit(new TierBenefit(BenefitType.FREE_DELIVERY, BigDecimal.ZERO, "ALL"));
        gold.addBenefit(new TierBenefit(BenefitType.PERCENTAGE_DISCOUNT, new BigDecimal("5"), "ALL"));
        tierRepository.save(gold);

        Tier platinum = new Tier(TierName.PLATINUM, 3, CriteriaMatchMode.ANY);
        platinum.addCriterion(new TierCriterion(CriteriaType.MIN_ORDER_COUNT, new BigDecimal("25"), null));
        platinum.addCriterion(new TierCriterion(CriteriaType.MIN_ORDER_VALUE, new BigDecimal("15000"), null));
        platinum.addCriterion(new TierCriterion(CriteriaType.COHORT, BigDecimal.ZERO, "VIP"));
        platinum.addBenefit(new TierBenefit(BenefitType.FREE_DELIVERY, BigDecimal.ZERO, "ALL"));
        platinum.addBenefit(new TierBenefit(BenefitType.PERCENTAGE_DISCOUNT, new BigDecimal("10"), "ALL"));
        platinum.addBenefit(new TierBenefit(BenefitType.PERCENTAGE_DISCOUNT, new BigDecimal("15"), "Electronics"));
        platinum.addBenefit(new TierBenefit(BenefitType.EARLY_ACCESS, BigDecimal.ZERO, "ALL"));
        platinum.addBenefit(new TierBenefit(BenefitType.PRIORITY_SUPPORT, BigDecimal.ZERO, "ALL"));
        tierRepository.save(platinum);

        userRepository.save(new User("Amrit Raj", "amrit@example.com", null));
        userRepository.save(new User("Priya VIP", "priya@example.com", "VIP"));
    }
}
