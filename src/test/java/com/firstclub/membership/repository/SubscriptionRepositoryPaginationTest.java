package com.firstclub.membership.repository;

import com.firstclub.membership.domain.CriteriaMatchMode;
import com.firstclub.membership.domain.Plan;
import com.firstclub.membership.domain.PlanType;
import com.firstclub.membership.domain.PlanVersion;
import com.firstclub.membership.domain.Subscription;
import com.firstclub.membership.domain.SubscriptionStatus;
import com.firstclub.membership.domain.Tier;
import com.firstclub.membership.domain.TierName;
import com.firstclub.membership.domain.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Targets the one thing {@link com.firstclub.membership.service.TierReconciliationScheduler}'s
 * batching fix actually depends on: {@link SubscriptionRepository#findDistinctUserIdsByStatus}
 * must visit every ACTIVE user id EXACTLY ONCE across a full walk of its pages - no duplicates,
 * no gaps. That query's own Javadoc explains why this isn't automatic: offset/limit pagination
 * over an unordered {@code DISTINCT} projection has no guaranteed stable row order across
 * separate page fetches, so this is exactly the class of bug that compiles fine, passes a
 * single-page smoke test, and only shows up once the active-user count actually exceeds one
 * page. Nothing else in this codebase exercises that path: the scheduler's {@code fixedDelay}
 * is an hour, which never fires during a several-second test run, and every existing
 * integration test creates far fewer active users than any page size would need to split.
 * <p>
 * {@code @DataJpaTest} loads only the JPA slice (entities + Spring Data repositories) against
 * this project's real H2 + Flyway setup - the same {@code V1__init_schema.sql} schema
 * production runs against, not a mock or a hand-rolled fake. {@link
 * com.firstclub.membership.config.DataSeeder} is a plain {@code @Component} implementing
 * {@code CommandLineRunner}, not an entity or a repository, so this test slice does not load it
 * - the database starts empty (post-migration, pre-seed), and each test method runs in its own
 * transaction that {@code @DataJpaTest} rolls back afterward, so the two tests below never see
 * each other's data regardless of execution order.
 */
@DataJpaTest
class SubscriptionRepositoryPaginationTest {

    @Autowired
    private SubscriptionRepository subscriptionRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PlanRepository planRepository;

    @Autowired
    private PlanVersionRepository planVersionRepository;

    @Autowired
    private TierRepository tierRepository;

    @Test
    void walkingAllPagesVisitsEveryActiveUserExactlyOnceAndSkipsCancelledUsers() {
        Plan plan = planRepository.save(new Plan(PlanType.MONTHLY, new BigDecimal("199.00"), "INR"));
        PlanVersion planVersion = planVersionRepository.save(
                new PlanVersion(plan, 1, new BigDecimal("199.00"), "INR"));
        Tier tier = tierRepository.save(new Tier(TierName.SILVER, 1, CriteriaMatchMode.ANY));
        Instant now = Instant.now();

        // Deliberately not a multiple of the page size below, so the last page is partial -
        // the off-by-one case where a fragile pagination implementation is most likely to
        // either drop the final row or loop forever thinking there's another page.
        int activeUserCount = 7;
        Set<Long> expectedActiveUserIds = new HashSet<>();
        for (int i = 0; i < activeUserCount; i++) {
            User user = userRepository.save(
                    new User("Active " + i, "active" + i + "-" + System.nanoTime() + "@example.com", null));
            subscriptionRepository.save(
                    new Subscription(user, plan, planVersion, tier, now, now.plus(30, ChronoUnit.DAYS)));
            expectedActiveUserIds.add(user.getId());
        }

        // A cancelled user must never surface from this query, at any page - proves the status
        // filter survives pagination rather than only working on a single unpaged call.
        User cancelledUser = userRepository.save(
                new User("Cancelled", "cancelled-" + System.nanoTime() + "@example.com", null));
        Subscription cancelledSubscription = new Subscription(
                cancelledUser, plan, planVersion, tier, now, now.plus(30, ChronoUnit.DAYS));
        cancelledSubscription.setStatus(SubscriptionStatus.CANCELLED);
        subscriptionRepository.save(cancelledSubscription);

        // 7 active users over pages of 3 -> pages of (3, 3, 1): exercises a full page, a second
        // full page, and a trailing partial page in the same walk.
        int pageSize = 3;
        Set<Long> visitedUserIds = new HashSet<>();
        int pageNumber = 0;
        int pagesFetched = 0;
        Page<Long> page;
        do {
            page = subscriptionRepository.findDistinctUserIdsByStatus(
                    SubscriptionStatus.ACTIVE, PageRequest.of(pageNumber, pageSize));
            for (Long userId : page.getContent()) {
                assertTrue(visitedUserIds.add(userId),
                        "user " + userId + " was returned by more than one page - pagination is not stable");
            }
            pageNumber++;
            pagesFetched++;
        } while (page.hasNext());

        assertEquals(3, pagesFetched,
                "7 active users at page size 3 should take exactly 3 pages (3, 3, 1)");
        assertEquals(expectedActiveUserIds, visitedUserIds,
                "every active user must be visited exactly once, and the cancelled user must never appear");
    }

    @Test
    void emptyActiveSetReturnsOnePageThatDoesNotClaimAnotherOneExists() {
        Page<Long> page = subscriptionRepository.findDistinctUserIdsByStatus(
                SubscriptionStatus.ACTIVE, PageRequest.of(0, 200));

        assertEquals(0, page.getTotalElements());
        assertTrue(page.getContent().isEmpty());
        // If this were true on an empty result, TierReconciliationScheduler's do/while sweep
        // would spin forever on a freshly migrated, unseeded database.
        assertFalse(page.hasNext(), "an empty result page must not claim to have a next page");
    }
}
