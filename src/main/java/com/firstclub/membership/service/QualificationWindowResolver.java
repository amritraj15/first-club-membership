package com.firstclub.membership.service;

import com.firstclub.membership.domain.CriteriaWindowType;
import com.firstclub.membership.domain.TierCriterion;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/** Resolves the inclusive/exclusive instants used for order-based tier qualification. */
@Component
public class QualificationWindowResolver {

    private final Clock clock;
    private final ZoneId businessZone;

    public QualificationWindowResolver(Clock clock,
                                       @Value("${membership.qualification.zone-id:Asia/Kolkata}") String zoneId) {
        this.clock = clock;
        this.businessZone = ZoneId.of(zoneId);
    }

    public QualificationWindow resolve(TierCriterion criterion) {
        return resolve(criterion, clock.instant());
    }

    /** Resolves against a caller-supplied instant when several criteria form one evaluation. */
    public QualificationWindow resolve(TierCriterion criterion, Instant now) {
        if (criterion.getWindowType() == CriteriaWindowType.ROLLING_DAYS) {
            return new QualificationWindow(
                    now.minusSeconds(criterion.getRollingWindowDays() * 24L * 60L * 60L), now);
        }

        ZonedDateTime zonedNow = now.atZone(businessZone);
        ZonedDateTime monthStart = zonedNow.withDayOfMonth(1).toLocalDate().atStartOfDay(businessZone);
        return new QualificationWindow(monthStart.toInstant(), monthStart.plusMonths(1).toInstant());
    }

    public record QualificationWindow(Instant startInclusive, Instant endExclusive) {
    }
}
