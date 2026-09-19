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
 * A single configurable perk attached to a tier. This is data, not a class hierarchy -
 * the spec's own word for benefits is "configurable", which we read as "changeable without a
 * redeploy", not "polymorphic". Adding a brand-new benefit BEHAVIOUR needs a new BenefitType +
 * a branch in BenefitService; adding a new benefit VALUE (e.g. Gold's discount going from 5% to
 * 7%, or a new "10% off Electronics" row) needs zero code changes.
 */
@Entity
@Table(name = "tier_benefit")
public class TierBenefit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "tier_id", nullable = false)
    private Tier tier;

    @Enumerated(EnumType.STRING)
    private BenefitType benefitType;

    /** Meaning depends on benefitType: percentage for PERCENTAGE_DISCOUNT, unused for
     *  FREE_DELIVERY / EARLY_ACCESS / PRIORITY_SUPPORT (kept nullable-friendly via 0). */
    private BigDecimal paramValue = BigDecimal.ZERO;

    /** Category/item scope this benefit applies to, or "ALL" for cart-wide. Free-text and
     *  data-driven on purpose - new categories need no code change. */
    private String scope = "ALL";

    protected TierBenefit() {
        // JPA
    }

    public TierBenefit(BenefitType benefitType, BigDecimal paramValue, String scope) {
        this.benefitType = benefitType;
        this.paramValue = paramValue;
        this.scope = scope;
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

    public BenefitType getBenefitType() {
        return benefitType;
    }

    public BigDecimal getParamValue() {
        return paramValue;
    }

    public String getScope() {
        return scope;
    }
}
