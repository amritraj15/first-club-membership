package com.firstclub.membership.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

/**
 * A billing plan. Price is deliberately BigDecimal, never float/double, to avoid the classic
 * rounding-error bug class in subscription billing. Price CHANGES are out of scope for this
 * exercise (see README "deliberately not implemented") - there is no versioning/grandfathering
 * of price for already-active subscriptions here.
 */
@Entity
@Table(name = "plan")
public class Plan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    private PlanType planType;

    private BigDecimal price;

    private String currency;

    protected Plan() {
        // JPA
    }

    public Plan(PlanType planType, BigDecimal price, String currency) {
        this.planType = planType;
        this.price = price;
        this.currency = currency;
    }

    public Long getId() {
        return id;
    }

    public PlanType getPlanType() {
        return planType;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public String getCurrency() {
        return currency;
    }

    /**
     * Calendar-based expiry, not a fixed day count. A fixed "months * 30 days" approximation
     * (the review's flagged issue) drifts against real calendar months (28/29/30/31 days) and
     * compounds over a Yearly plan. {@code Instant} itself has no calendar arithmetic (it's a
     * pure instant on the timeline), so we go through {@code ZonedDateTime} in UTC to get
     * {@code plusMonths} semantics, then back to {@code Instant}.
     */
    public Instant computeEndDate(Instant start) {
        ZonedDateTime startZoned = start.atZone(ZoneOffset.UTC);
        return startZoned.plusMonths(planType.getMonths()).toInstant();
    }
}
