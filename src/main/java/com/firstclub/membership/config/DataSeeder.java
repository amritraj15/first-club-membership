package com.firstclub.membership.config;

import com.firstclub.membership.domain.ActiveMembershipLock;
import com.firstclub.membership.domain.BenefitType;
import com.firstclub.membership.domain.CriteriaMatchMode;
import com.firstclub.membership.domain.CriteriaType;
import com.firstclub.membership.domain.CriteriaWindowType;
import com.firstclub.membership.domain.OrderRecord;
import com.firstclub.membership.domain.Plan;
import com.firstclub.membership.domain.PlanVersion;
import com.firstclub.membership.domain.PlanType;
import com.firstclub.membership.domain.Subscription;
import com.firstclub.membership.domain.Tier;
import com.firstclub.membership.domain.TierBenefit;
import com.firstclub.membership.domain.TierCriterion;
import com.firstclub.membership.domain.TierName;
import com.firstclub.membership.domain.User;
import com.firstclub.membership.repository.ActiveMembershipLockRepository;
import com.firstclub.membership.repository.OrderRecordRepository;
import com.firstclub.membership.repository.PlanRepository;
import com.firstclub.membership.repository.PlanVersionRepository;
import com.firstclub.membership.repository.SubscriptionRepository;
import com.firstclub.membership.repository.TierRepository;
import com.firstclub.membership.repository.UserRepository;
import com.firstclub.membership.service.TierEvaluationService;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Seeds demo-able data on startup: three plans, three tiers each with configurable benefits and
 * criteria, and named active-subscription scenarios. Those scenarios make the main evaluation
 * paths inspectable immediately after a fresh boot, without hand-creating data first.
 */
@Component
public class DataSeeder implements CommandLineRunner {

    private final PlanRepository planRepository;
    private final TierRepository tierRepository;
    private final UserRepository userRepository;
    private final OrderRecordRepository orderRecordRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final ActiveMembershipLockRepository activeMembershipLockRepository;
    private final PlanVersionRepository planVersionRepository;
    private final TierEvaluationService tierEvaluationService;
    private final Clock clock;

    public DataSeeder(PlanRepository planRepository, TierRepository tierRepository, UserRepository userRepository,
                      OrderRecordRepository orderRecordRepository, SubscriptionRepository subscriptionRepository,
                      ActiveMembershipLockRepository activeMembershipLockRepository,
                      PlanVersionRepository planVersionRepository,
                      TierEvaluationService tierEvaluationService, Clock clock) {
        this.planRepository = planRepository;
        this.tierRepository = tierRepository;
        this.userRepository = userRepository;
        this.orderRecordRepository = orderRecordRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.activeMembershipLockRepository = activeMembershipLockRepository;
        this.planVersionRepository = planVersionRepository;
        this.tierEvaluationService = tierEvaluationService;
        this.clock = clock;
    }

    @Override
    public void run(String... args) {
        if (planRepository.count() > 0) {
            return; // Already seeded (e.g. re-run without a fresh in-memory DB).
        }

        Plan monthly = planRepository.save(new Plan(PlanType.MONTHLY, new BigDecimal("199.00"), "INR"));
        Plan quarterly = planRepository.save(new Plan(PlanType.QUARTERLY, new BigDecimal("499.00"), "INR"));
        Plan yearly = planRepository.save(new Plan(PlanType.YEARLY, new BigDecimal("1499.00"), "INR"));
        planVersionRepository.save(new PlanVersion(monthly, 1, monthly.getPrice(), monthly.getCurrency()));
        planVersionRepository.save(new PlanVersion(quarterly, 1, quarterly.getPrice(), quarterly.getCurrency()));
        planVersionRepository.save(new PlanVersion(yearly, 1, yearly.getPrice(), yearly.getCurrency()));

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
        platinum.addCriterion(new TierCriterion(CriteriaType.MIN_ORDER_VALUE, new BigDecimal("15000"), null,
                CriteriaWindowType.ROLLING_DAYS, 30));
        platinum.addCriterion(new TierCriterion(CriteriaType.COHORT, BigDecimal.ZERO, "VIP"));
        platinum.addBenefit(new TierBenefit(BenefitType.FREE_DELIVERY, BigDecimal.ZERO, "ALL"));
        platinum.addBenefit(new TierBenefit(BenefitType.EXPEDITED_DELIVERY, new BigDecimal("1"), "DAYS"));
        platinum.addBenefit(new TierBenefit(BenefitType.PERCENTAGE_DISCOUNT, new BigDecimal("10"), "ALL"));
        platinum.addBenefit(new TierBenefit(BenefitType.PERCENTAGE_DISCOUNT, new BigDecimal("15"), "Electronics"));
        platinum.addBenefit(new TierBenefit(BenefitType.EXCLUSIVE_DEAL, new BigDecimal("20"), "Beauty"));
        platinum.addBenefit(new TierBenefit(BenefitType.EARLY_ACCESS, new BigDecimal("7"), "DAYS"));
        platinum.addBenefit(new TierBenefit(BenefitType.PRIORITY_SUPPORT, BigDecimal.ZERO, "ALL"));
        tierRepository.save(platinum);

        userRepository.save(new User("Amrit Raj", "amrit@example.com", null));
        userRepository.save(new User("Priya VIP", "priya@example.com", "VIP"));

        Instant now = clock.instant();
        ZoneId businessZone = ZoneId.of("Asia/Kolkata");
        ZonedDateTime monthStart = now.atZone(businessZone).withDayOfMonth(1)
                .toLocalDate().atStartOfDay(businessZone);

        // Each named scenario starts at Silver, then is evaluated from its real seed orders.
        // Expected result after startup: count/value -> Gold; VIP/rolling -> Platinum;
        // prior-month-only -> Silver (proves default calendar-month semantics).
        User countUser = userRepository.save(new User("Seed Gold Count", "seed.gold-count@example.com", null));
        User valueUser = userRepository.save(new User("Seed Gold Value", "seed.gold-value@example.com", null));
        User vipUser = userRepository.save(new User("Seed Platinum VIP", "seed.platinum-vip@example.com", "VIP"));
        User rollingUser = userRepository.save(new User("Seed Platinum Rolling", "seed.platinum-rolling@example.com", null));
        User previousMonthUser = userRepository.save(new User("Seed Calendar Boundary", "seed.calendar-boundary@example.com", null));

        createActiveSubscription(countUser, yearly, silver, now);
        createActiveSubscription(valueUser, yearly, silver, now);
        createActiveSubscription(vipUser, yearly, silver, now);
        createActiveSubscription(rollingUser, yearly, silver, now);
        createActiveSubscription(previousMonthUser, yearly, silver, now);

        List<OrderRecord> orders = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            orders.add(new OrderRecord(countUser, new BigDecimal("100"), now));
        }
        orders.add(new OrderRecord(valueUser, new BigDecimal("6000"), now));
        orders.add(new OrderRecord(rollingUser, new BigDecimal("16000"), now.minusSeconds(5L * 24 * 60 * 60)));
        for (int i = 0; i < 11; i++) {
            orders.add(new OrderRecord(previousMonthUser, new BigDecimal("100"),
                    monthStart.toInstant().minusSeconds(60L + i)));
        }
        orderRecordRepository.saveAll(orders);

        for (User user : List.of(countUser, valueUser, vipUser, rollingUser, previousMonthUser)) {
            tierEvaluationService.reevaluateSafely(user.getId());
        }
    }

    private void createActiveSubscription(User user, Plan plan, Tier initialTier, Instant start) {
        Subscription subscription = subscriptionRepository.save(
                new Subscription(user, plan, planVersionRepository.findTopByPlanIdOrderByVersionNumberDesc(plan.getId()).orElseThrow(), initialTier, start, plan.computeEndDate(start)));
        activeMembershipLockRepository.save(new ActiveMembershipLock(user.getId(), subscription.getId()));
    }
}
