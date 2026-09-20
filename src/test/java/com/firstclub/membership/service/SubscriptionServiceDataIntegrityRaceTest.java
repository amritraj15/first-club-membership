package com.firstclub.membership.service;

import com.firstclub.membership.domain.ActiveMembershipLock;
import com.firstclub.membership.domain.Subscription;
import com.firstclub.membership.domain.SubscriptionIdempotency;
import com.firstclub.membership.exception.ConflictException;
import com.firstclub.membership.repository.ActiveMembershipLockRepository;
import com.firstclub.membership.repository.SubscriptionIdempotencyRepository;
import com.firstclub.membership.repository.SubscriptionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.lang.reflect.Constructor;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit test for a fix to a real regression: {@code
 * SubscriptionMutationTransactions#createSubscription} can throw a raw {@code
 * DataIntegrityViolationException} when a unique-constraint race fires - the {@code
 * ActiveMembershipLock} invariant, or the idempotency-key pair. {@code GlobalExceptionHandler}
 * has no handler for that exception type on purpose (a raw constraint violation shouldn't get a
 * free translation in general), so before this fix, the two known, understood cases that
 * previously fell through to an unhandled re-throw would have surfaced to the caller as an
 * unhandled 500, not the clean 409 the equivalent non-race code paths already return for the
 * identical business situation. See {@code SubscriptionService#subscribe}'s inline comments for
 * exactly which case gets which treatment.
 * <p>
 * {@link SubscriptionMutationTransactions} is exercised through a plain subclass that overrides
 * only {@code createSubscription} to throw on demand (see {@link #throwingMutations}), not
 * through a Mockito mock of the concrete class - Mockito's default "inline" mock maker
 * instruments concrete classes via a runtime-attached Java agent, which does not work on every
 * JDK (confirmed failing here on a JDK 27 build with "Could not modify all classes"). A subclass
 * override is ordinary {@code javac}-compiled Java with no bytecode-instrumentation step, so it
 * is unaffected by that limitation on any JDK; every constructor dependency it doesn't use is
 * passed as {@code null} rather than a mock, since the override never touches them. The same
 * reasoning applies to {@code ActiveMembershipLock}, {@code Subscription}, and {@code
 * SubscriptionIdempotency} below - real constructed instances instead of mocks, both to sidestep
 * the same limitation and because real objects need no stubbing to answer their own getters
 * correctly.
 */
class SubscriptionServiceDataIntegrityRaceTest {

    private ActiveMembershipLockRepository lockRepository;
    private SubscriptionIdempotencyRepository idempotencyRepository;
    private SubscriptionRepository subscriptionRepository;
    private Clock clock;

    @BeforeEach
    void setUp() {
        // All four are plain interfaces (Spring Data repositories) - Mockito mocks them via an
        // ordinary JDK dynamic proxy, not agent-based instrumentation, so they are unaffected by
        // the concrete-class limitation described in the class Javadoc.
        lockRepository = mock(ActiveMembershipLockRepository.class);
        idempotencyRepository = mock(SubscriptionIdempotencyRepository.class);
        subscriptionRepository = mock(SubscriptionRepository.class);
        clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    }

    /** A {@link SubscriptionMutationTransactions} whose {@code createSubscription} unconditionally
     *  throws {@code toThrow}, regardless of arguments - see the class Javadoc for why this is a
     *  subclass override rather than a Mockito mock. Every other constructor dependency is
     *  {@code null}: the override never calls the real method body, so they're never touched. */
    private SubscriptionMutationTransactions throwingMutations(RuntimeException toThrow) {
        return new SubscriptionMutationTransactions(null, null, null, null, null, null, null, null, null, null) {
            @Override
            public Subscription createSubscription(Long userId, Long planId, Long tierId, String idempotencyKey) {
                throw toThrow;
            }
        };
    }

    /** A bare, unpersisted {@code Subscription} via its protected no-arg constructor (reflection) -
     *  used purely as a distinct object reference for {@code assertSame}; none of its fields
     *  matter to these tests, so there's no reason to build a full real entity graph for it. */
    private static Subscription blankSubscription() {
        try {
            Constructor<Subscription> ctor = Subscription.class.getDeclaredConstructor();
            ctor.setAccessible(true);
            return ctor.newInstance();
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void activeMembershipLockRaceWithNoIdempotencyKeyIsTranslatedToAClean409() {
        DataIntegrityViolationException constraintRace = new DataIntegrityViolationException("unique constraint violated");
        SubscriptionService subscriptionService = new SubscriptionService(
                subscriptionRepository, throwingMutations(constraintRace), clock, idempotencyRepository, lockRepository);
        // The ActiveMembershipLock row exists - the known, understood cause of this constraint
        // failure - so this must become a 409, not the raw exception.
        when(lockRepository.findByUserId(1L)).thenReturn(Optional.of(new ActiveMembershipLock(1L, 100L)));

        ConflictException thrown = assertThrows(ConflictException.class,
                () -> subscriptionService.subscribe(1L, 10L, 20L, null));
        assertEquals(org.springframework.http.HttpStatus.CONFLICT, thrown.getStatus());
    }

    @Test
    void activeMembershipLockRaceWithABlankIdempotencyKeyIsAlsoTranslated() {
        // A blank (not null) key must be treated the same as no key - this is the exact branch
        // that used to short-circuit straight to `throw constraintRace` before the fix.
        DataIntegrityViolationException constraintRace = new DataIntegrityViolationException("unique constraint violated");
        SubscriptionService subscriptionService = new SubscriptionService(
                subscriptionRepository, throwingMutations(constraintRace), clock, idempotencyRepository, lockRepository);
        when(lockRepository.findByUserId(1L)).thenReturn(Optional.of(new ActiveMembershipLock(1L, 100L)));

        assertThrows(ConflictException.class, () -> subscriptionService.subscribe(1L, 10L, 20L, "   "));
    }

    @Test
    void idempotencyKeyRaceStillReplaysTheWinningSubscription() {
        // The original, already-correct behaviour must survive the fix untouched: a concurrent
        // request that wins the (user_id, idempotency_key) constraint should have its result
        // replayed, not converted into the ActiveMembershipLock 409 path.
        DataIntegrityViolationException constraintRace = new DataIntegrityViolationException("unique constraint violated");
        SubscriptionService subscriptionService = new SubscriptionService(
                subscriptionRepository, throwingMutations(constraintRace), clock, idempotencyRepository, lockRepository);

        Subscription winningSubscription = blankSubscription();
        SubscriptionIdempotency winningRecord =
                new SubscriptionIdempotency(1L, "retry-key", 10L, 20L, winningSubscription);
        when(idempotencyRepository.findByUserIdAndIdempotencyKey(1L, "retry-key"))
                .thenReturn(Optional.of(winningRecord));

        Subscription result = subscriptionService.subscribe(1L, 10L, 20L, "retry-key");

        assertSame(winningSubscription, result);
    }

    @Test
    void genuinelyUnrecognizedIntegrityViolationIsNotMaskedAsA409() {
        // Neither an idempotency-key match nor an ActiveMembershipLock row explains this
        // failure - the fix must not invent a business-logic response for something it doesn't
        // understand. The raw exception should still surface.
        DataIntegrityViolationException constraintRace = new DataIntegrityViolationException("unrecognized constraint violated");
        SubscriptionService subscriptionService = new SubscriptionService(
                subscriptionRepository, throwingMutations(constraintRace), clock, idempotencyRepository, lockRepository);
        when(lockRepository.findByUserId(1L)).thenReturn(Optional.empty());

        DataIntegrityViolationException thrown = assertThrows(DataIntegrityViolationException.class,
                () -> subscriptionService.subscribe(1L, 10L, 20L, null));
        assertSame(constraintRace, thrown);
    }
}
