package com.firstclub.membership.service;

import com.firstclub.membership.domain.CriteriaMatchMode;
import com.firstclub.membership.domain.Plan;
import com.firstclub.membership.domain.PlanType;
import com.firstclub.membership.domain.PlanVersion;
import com.firstclub.membership.domain.Subscription;
import com.firstclub.membership.domain.SubscriptionStatus;
import com.firstclub.membership.domain.Tier;
import com.firstclub.membership.domain.TierName;
import com.firstclub.membership.domain.User;
import com.firstclub.membership.exception.ForbiddenException;
import com.firstclub.membership.exception.InvalidTransitionException;
import com.firstclub.membership.repository.ActiveMembershipLockRepository;
import com.firstclub.membership.repository.PlanVersionRepository;
import com.firstclub.membership.repository.SubscriptionIdempotencyRepository;
import com.firstclub.membership.repository.SubscriptionRepository;
import com.firstclub.membership.repository.TierChangeAuditRepository;
import com.firstclub.membership.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit test (Mockito for repository INTERFACES only, real domain objects for entities - see the
 * note on {@link #setId} below for why) for one specific, previously-untested fix: {@code
 * changeTier} must reject a STALE-ACTIVE subscription - {@code status} still reads ACTIVE in the
 * database, but the subscription is not actually live, because expiry is corrected lazily on
 * read rather than by a background job that runs before every write (see {@code
 * Subscription#isCurrentlyActive} and {@code SubscriptionMutationTransactions#changeTier}'s own
 * Javadoc). Before this guard existed, that state passed the state-machine check - which only
 * inspects the persisted enum - and let a caller "change tier" on a membership that was no
 * longer live. Nothing else in this test suite forces that state directly: the integration
 * suite only reaches {@code changeTier} through subscriptions it just created, which are never
 * expired, so a regression here would pass every existing test silently.
 * <p>
 * Real {@code User}/{@code Plan}/{@code PlanVersion}/{@code Tier}/{@code Subscription} objects
 * are used instead of Mockito mocks for these concrete JPA entities - not a style preference,
 * but a portability fix: Mockito's default "inline" mock maker instruments concrete classes via
 * a runtime-attached Java agent, which does not work on every JDK (confirmed failing here on a
 * JDK 27 build with "Could not modify all classes"). Repository dependencies stay mocked,
 * because they're plain interfaces - {@code Mockito.mock()} on an interface uses an ordinary JDK
 * dynamic proxy, not agent-based instrumentation, so it is unaffected by that limitation on any
 * JDK. {@code PlanService} is passed as {@code null}: both tests below are constructed so that
 * {@code changeTier} throws before it would ever be reached.
 */
class SubscriptionMutationTransactionsTest {

    private SubscriptionRepository subscriptionRepository;
    private TierChangeAuditRepository tierChangeAuditRepository;
    private SubscriptionMutationTransactions mutations;
    private Instant now;

    @BeforeEach
    void setUp() {
        subscriptionRepository = mock(SubscriptionRepository.class);
        UserRepository userRepository = mock(UserRepository.class);
        ActiveMembershipLockRepository lockRepository = mock(ActiveMembershipLockRepository.class);
        PlanVersionRepository planVersionRepository = mock(PlanVersionRepository.class);
        SubscriptionIdempotencyRepository idempotencyRepository = mock(SubscriptionIdempotencyRepository.class);
        tierChangeAuditRepository = mock(TierChangeAuditRepository.class);
        CallerIdentityGuard callerIdentityGuard = new CallerIdentityGuard();
        Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
        now = clock.instant();

        mutations = new SubscriptionMutationTransactions(subscriptionRepository, userRepository,
                /* planService, deliberately unused on both tested paths - see class javadoc */ null,
                new SubscriptionStateMachine(), lockRepository, planVersionRepository, idempotencyRepository,
                tierChangeAuditRepository, callerIdentityGuard, clock);
    }

    /** Sets a JPA-generated id on an otherwise-unpersisted real entity, via reflection rather
     *  than a real save() - these entities have no public id setter (Hibernate owns that field),
     *  and this test needs a concrete, known id for the ownership comparison in
     *  {@code CallerIdentityGuard} to behave meaningfully. Every affected entity has a protected
     *  no-arg constructor for the same reason (Hibernate's own proxying requirement), which is
     *  what makes constructing one here at all possible without a persistence context. */
    private static void setId(Object entity, Long id) {
        try {
            Field idField = entity.getClass().getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(entity, id);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private Subscription realSubscription(User owner, Instant startDate, Instant endDate) {
        Plan plan = new Plan(PlanType.MONTHLY, new BigDecimal("199.00"), "INR");
        PlanVersion planVersion = new PlanVersion(plan, 1, new BigDecimal("199.00"), "INR");
        Tier tier = new Tier(TierName.SILVER, 1, CriteriaMatchMode.ANY);
        return new Subscription(owner, plan, planVersion, tier, startDate, endDate);
    }

    @Test
    void changeTierRejectsAStaleActiveButActuallyExpiredSubscription() {
        Long ownerId = 42L;
        User owner = new User("Owner", "owner@example.com", null);
        setId(owner, ownerId);

        // Real dates, not a stubbed isCurrentlyActive() - endDate genuinely in the past, so the
        // real Subscription.isCurrentlyActive(now) computes false on its own. status stays
        // ACTIVE (the constructor's default) precisely because nothing here ever "expires" it -
        // that lazy correction is exactly the mechanism this guard exists to not depend on.
        Subscription staleActiveSubscription = realSubscription(
                owner, now.minus(60, ChronoUnit.DAYS), now.minus(1, ChronoUnit.DAYS));

        when(subscriptionRepository.findById(1L)).thenReturn(Optional.of(staleActiveSubscription));

        assertThrows(InvalidTransitionException.class,
                () -> mutations.changeTier(1L, 99L, ownerId));

        // The rejection must happen before any mutation - a regression that reordered the
        // checks could still throw for an unrelated reason and pass a looser assertion; this
        // confirms no write was ever attempted on the stale subscription.
        verify(subscriptionRepository, never()).save(any());
        verify(tierChangeAuditRepository, never()).save(any());
    }

    @Test
    void changeTierStillRejectsANonOwnerBeforeEvenCheckingLiveness() {
        // Companion boundary check: CallerIdentityGuard's ownership check runs before the
        // liveness/state-machine checks (see changeTier's Javadoc on why - a non-owner
        // shouldn't learn anything about a subscription's lifecycle state as a side effect of a
        // rejected request). To prove the ORDER, not just the outcome, this subscription is
        // built CANCELLED - a state that would throw InvalidTransitionException (422) if the
        // state-machine check were ever reached. Getting ForbiddenException (403) instead is
        // only possible if the ownership check ran first and stopped the request before that.
        Long ownerId = 42L;
        Long attackerId = 7L;
        User owner = new User("Owner", "owner@example.com", null);
        setId(owner, ownerId);

        Subscription cancelledSubscriptionOwnedBySomeoneElse =
                realSubscription(owner, now.minus(60, ChronoUnit.DAYS), now.plus(30, ChronoUnit.DAYS));
        cancelledSubscriptionOwnedBySomeoneElse.setStatus(SubscriptionStatus.CANCELLED);
        when(subscriptionRepository.findById(2L)).thenReturn(Optional.of(cancelledSubscriptionOwnedBySomeoneElse));

        assertThrows(ForbiddenException.class,
                () -> mutations.changeTier(2L, 99L, attackerId));
    }
}
