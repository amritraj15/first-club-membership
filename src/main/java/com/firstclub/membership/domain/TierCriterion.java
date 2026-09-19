package com.firstclub.membership.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.math.BigDecimal;

/**
 * One promotion rule attached to a tier, e.g. "MIN_ORDER_COUNT >= 10" or "COHORT = VIP".
 * A tier can have several; {@link Tier#getCriteriaMatchMode()} decides ANY vs ALL.
 * Evaluated by the matching {@link com.firstclub.membership.strategy.TierCriteriaStrategy}.
 */
@Entity
@Table(name = "tier_criterion")
public class TierCriterion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "tier_id", nullable = false)
    private Tier tier;

    @Enumerated(EnumType.STRING)
    private CriteriaType criteriaType;

    /** Defaults to calendar-month semantics for every order-based criterion. */
    @Enumerated(EnumType.STRING)
    private CriteriaWindowType windowType = CriteriaWindowType.CALENDAR_MONTH;

    /** Used only when {@link #windowType} is {@link CriteriaWindowType#ROLLING_DAYS}. */
    private int rollingWindowDays = 30;

    /** Threshold for MIN_ORDER_COUNT (integer orders, "more than X" -> stored as X, compared
     *  strictly greater than) and MIN_ORDER_VALUE (currency amount). Unused for COHORT. */
    private BigDecimal threshold = BigDecimal.ZERO;

    /** Only used for COHORT criteria. */
    private String cohortName;

    protected TierCriterion() {
        // JPA
    }

    public TierCriterion(CriteriaType criteriaType, BigDecimal threshold, String cohortName) {
        this(criteriaType, threshold, cohortName, CriteriaWindowType.CALENDAR_MONTH, 30);
    }

    public TierCriterion(CriteriaType criteriaType, BigDecimal threshold, String cohortName,
                         CriteriaWindowType windowType, int rollingWindowDays) {
        this.criteriaType = criteriaType;
        this.threshold = threshold;
        this.cohortName = cohortName;
        this.windowType = windowType;
        this.rollingWindowDays = rollingWindowDays;
    }

    public Long getId() {
        return id;
    }

    public Tier getTier() {
        return tier;
    }

    public void setTier(Tier tier) {
        this.tier = tier;
    }

    public CriteriaType getCriteriaType() {
        return criteriaType;
    }

    public CriteriaWindowType getWindowType() {
        return windowType;
    }

    public int getRollingWindowDays() {
        return rollingWindowDays;
    }

    public BigDecimal getThreshold() {
        return threshold;
    }

    public String getCohortName() {
        return cohortName;
    }
}
