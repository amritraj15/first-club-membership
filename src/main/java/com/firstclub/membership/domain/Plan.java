package com.firstclub.membership.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.OneToMany;
import jakarta.persistence.CascadeType;
import java.util.ArrayList;
import java.util.List;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

/**
 * A billing plan. Price is deliberately BigDecimal, never float/double, to avoid the classic
 * rounding-error bug class in subscription billing. The current price/currency represent the
 * latest catalog version; existing subscriptions reference an immutable PlanVersion so they
 * retain their grandfathered commercial terms.
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

    @OneToMany(mappedBy = "plan", cascade = CascadeType.ALL, orphanRemoval = false)
    private List<PlanVersion> versions = new ArrayList<>();

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

    public void updateCurrentPrice(BigDecimal price, String currency) {
        this.price = price;
        this.currency = currency;
    }

    public void addVersion(PlanVersion version) {
        versions.add(version);
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
