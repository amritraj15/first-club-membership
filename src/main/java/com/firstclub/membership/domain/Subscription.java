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
import jakarta.persistence.Version;

import java.time.Instant;

/**
 * The join of a User to a Plan + Tier, with lifecycle state.
 * <p>
 * Concurrency: {@code version} is a JPA optimistic-lock column. Two concurrent writers (e.g. a
 * user-initiated downgrade racing a system tier-promotion job) will have one succeed and the
 * other throw {@code OptimisticLockException}; the service layer catches this and retries once
 * (see SubscriptionService / TierEvaluationService), then logs and defers to the next
 * reconciliation pass rather than looping indefinitely.
 * <p>
 * Expiry is computed LAZILY on read via {@link #isCurrentlyActive(Instant)} rather than relying
 * solely on a background job to flip status - a missed cron run must never make an expired
 * subscription look active.
 */
@Entity
@Table(name = "subscription")
public class Subscription {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne
    @JoinColumn(name = "plan_id", nullable = false)
    private Plan plan;

    @ManyToOne
    @JoinColumn(name = "tier_id", nullable = false)
    private Tier tier;

    @ManyToOne(optional = false)
    @JoinColumn(name = "plan_version_id", nullable = false)
    private PlanVersion planVersion;

    @Enumerated(EnumType.STRING)
    private SubscriptionStatus status;

    @Enumerated(EnumType.STRING)
    private TierSource tierSource;

    private Instant startDate;

    private Instant endDate;

    /** Set by the user's most recent EXPLICIT tier change. When set, the automatic tier
     *  evaluator will not silently override it back down - see TierEvaluationService. */
    private boolean manualTierOverride = false;

    @Version
    private Long version;

    protected Subscription() {
        // JPA
    }

    public Subscription(User user, Plan plan, PlanVersion planVersion, Tier tier, Instant startDate, Instant endDate) {
        this.user = user;
        this.plan = plan;
        this.planVersion = planVersion;
        this.tier = tier;
        this.status = SubscriptionStatus.ACTIVE;
        this.tierSource = TierSource.USER_SELECTED;
        this.startDate = startDate;
        this.endDate = endDate;
    }

    public Long getId() {
        return id;
    }

    public User getUser() {
        return user;
    }

    public Plan getPlan() {
        return plan;
    }

    public Tier getTier() {
        return tier;
    }

    public PlanVersion getPlanVersion() {
        return planVersion;
    }

    public void setTier(Tier tier) {
        this.tier = tier;
    }

    public SubscriptionStatus getStatus() {
        return status;
    }

    public void setStatus(SubscriptionStatus status) {
        this.status = status;
    }

    public TierSource getTierSource() {
        return tierSource;
    }

    public void setTierSource(TierSource tierSource) {
        this.tierSource = tierSource;
    }

    public Instant getStartDate() {
        return startDate;
    }

    public Instant getEndDate() {
        return endDate;
    }

    public void setEndDate(Instant endDate) {
        this.endDate = endDate;
    }

    public boolean isManualTierOverride() {
        return manualTierOverride;
    }

    public void setManualTierOverride(boolean manualTierOverride) {
        this.manualTierOverride = manualTierOverride;
    }

    public Long getVersion() {
        return version;
    }

    /** Read-time truth about whether this subscription is actually active right now,
     *  independent of the persisted {@code status} - see class javadoc. */
    public boolean isCurrentlyActive(Instant now) {
        return status == SubscriptionStatus.ACTIVE && endDate.isAfter(now);
    }
}
