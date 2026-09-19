package com.firstclub.membership.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;

import java.util.ArrayList;
import java.util.List;

/**
 * A membership tier. Independent of {@link Plan} - see README for why Plan and Tier are
 * modeled as two separate axes rather than tier being "part of" a plan.
 * <p>
 * rank is used to order tiers for upgrade/downgrade comparisons and to pick the HIGHEST
 * qualifying tier when several tiers' criteria are simultaneously satisfied.
 */
@Entity
@Table(name = "tier")
public class Tier {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    private TierName name;

    /** Higher rank = higher tier. Silver=1, Gold=2, Platinum=3 in the seed data. */
    private int rank;

    @Enumerated(EnumType.STRING)
    private CriteriaMatchMode criteriaMatchMode = CriteriaMatchMode.ANY;

    @OneToMany(mappedBy = "tier", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @OrderBy("id asc")
    private List<TierBenefit> benefits = new ArrayList<>();

    @OneToMany(mappedBy = "tier", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @OrderBy("id asc")
    private List<TierCriterion> criteria = new ArrayList<>();

    protected Tier() {
        // JPA
    }

    public Tier(TierName name, int rank, CriteriaMatchMode criteriaMatchMode) {
        this.name = name;
        this.rank = rank;
        this.criteriaMatchMode = criteriaMatchMode;
    }

    public Long getId() {
        return id;
    }

    public TierName getName() {
        return name;
    }

    public int getRank() {
        return rank;
    }

    public CriteriaMatchMode getCriteriaMatchMode() {
        return criteriaMatchMode;
    }

    public List<TierBenefit> getBenefits() {
        return benefits;
    }

    public List<TierCriterion> getCriteria() {
        return criteria;
    }

    public void addBenefit(TierBenefit benefit) {
        benefit.setTier(this);
        this.benefits.add(benefit);
    }

    public void addCriterion(TierCriterion criterion) {
        criterion.setTier(this);
        this.criteria.add(criterion);
    }
}
