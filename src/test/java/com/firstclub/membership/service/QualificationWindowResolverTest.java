package com.firstclub.membership.service;

import com.firstclub.membership.domain.CriteriaType;
import com.firstclub.membership.domain.CriteriaWindowType;
import com.firstclub.membership.domain.TierCriterion;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;

class QualificationWindowResolverTest {

    private final Instant now = Instant.parse("2026-09-19T12:00:00Z");
    private final QualificationWindowResolver resolver = new QualificationWindowResolver(
            Clock.fixed(now, ZoneOffset.UTC), "Asia/Kolkata");

    @Test
    void defaultsToCurrentCalendarMonthInConfiguredBusinessZone() {
        TierCriterion criterion = new TierCriterion(CriteriaType.MIN_ORDER_VALUE, new BigDecimal("5000"), null);

        QualificationWindowResolver.QualificationWindow window = resolver.resolve(criterion);

        // Midnight Sep 1 in Asia/Kolkata is Aug 31 18:30 UTC.
        assertEquals(Instant.parse("2026-08-31T18:30:00Z"), window.startInclusive());
        assertEquals(Instant.parse("2026-09-30T18:30:00Z"), window.endExclusive());
    }

    @Test
    void rollingWindowRemainsAvailableForIndividualCriteria() {
        TierCriterion criterion = new TierCriterion(CriteriaType.MIN_ORDER_VALUE, new BigDecimal("15000"), null,
                CriteriaWindowType.ROLLING_DAYS, 30);

        QualificationWindowResolver.QualificationWindow window = resolver.resolve(criterion);

        assertEquals(Instant.parse("2026-08-20T12:00:00Z"), window.startInclusive());
        assertEquals(now, window.endExclusive());
    }
}
