package com.firstclub.membership.service;

import com.firstclub.membership.domain.SubscriptionStatus;
import com.firstclub.membership.exception.InvalidTransitionException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SubscriptionStateMachineTest {

    private final SubscriptionStateMachine machine = new SubscriptionStateMachine();

    @Test
    void activeCanTransitionToCancelledOrExpired() {
        assertDoesNotThrow(() -> machine.assertTransitionAllowed(SubscriptionStatus.ACTIVE, SubscriptionStatus.CANCELLED));
        assertDoesNotThrow(() -> machine.assertTransitionAllowed(SubscriptionStatus.ACTIVE, SubscriptionStatus.EXPIRED));
    }

    @Test
    void cancelledIsTerminal() {
        assertThrows(InvalidTransitionException.class,
                () -> machine.assertTransitionAllowed(SubscriptionStatus.CANCELLED, SubscriptionStatus.ACTIVE));
    }

    @Test
    void expiredIsTerminal() {
        assertThrows(InvalidTransitionException.class,
                () -> machine.assertTransitionAllowed(SubscriptionStatus.EXPIRED, SubscriptionStatus.ACTIVE));
    }

    @Test
    void tierChangeOnlyAllowedWhileActive() {
        assertDoesNotThrow(() -> machine.assertTierChangeAllowed(SubscriptionStatus.ACTIVE));
        assertThrows(InvalidTransitionException.class,
                () -> machine.assertTierChangeAllowed(SubscriptionStatus.CANCELLED));
        assertThrows(InvalidTransitionException.class,
                () -> machine.assertTierChangeAllowed(SubscriptionStatus.EXPIRED));
    }
}
