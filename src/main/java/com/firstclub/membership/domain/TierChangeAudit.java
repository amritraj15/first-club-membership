package com.firstclub.membership.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * An append-only record of every tier a subscription has ever moved through - written in the
 * same transaction as the mutation it describes, so a row can never exist without the change it
 * records, or vice versa. This is deliberately NOT full event sourcing: {@link Subscription}'s
 * current state is still the row Hibernate manages directly, not something derived by replaying
 * these events - see the "deliberately NOT implemented" note in the README for why that larger
 * change wasn't undertaken. What this closes is narrower and more concrete: "why is this user
 * on Gold, and when did that happen" is answerable from a query, not a guess from logs.
 * <p>
 * IDs are stored as plain {@code Long}s rather than {@code @ManyToOne} relations, matching the
 * same pattern {@link ActiveMembershipLock} and {@link SubscriptionIdempotency} already use for
 * their reference columns - an audit row is written once and read back in bulk, not navigated
 * from; a plain id avoids pulling in an object graph that reading history has no use for.
 */
@Entity
@Table(name = "tier_change_audit")
public class TierChangeAudit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "subscription_id", nullable = false)
    private Long subscriptionId;

    /** Null only for a subscription's very first tier assignment at subscribe time - there is
     *  no "previous" tier for a brand-new subscription. */
    @Column(name = "previous_tier_id")
    private Long previousTierId;

    @Column(name = "new_tier_id", nullable = false)
    private Long newTierId;

    @Enumerated(EnumType.STRING)
    @Column(name = "tier_source", nullable = false)
    private TierSource tierSource;

    @Column(name = "changed_at", nullable = false)
    private Instant changedAt;

    protected TierChangeAudit() {
        // JPA
    }

    public TierChangeAudit(Long userId, Long subscriptionId, Long previousTierId, Long newTierId,
                            TierSource tierSource, Instant changedAt) {
        this.userId = userId;
        this.subscriptionId = subscriptionId;
        this.previousTierId = previousTierId;
        this.newTierId = newTierId;
        this.tierSource = tierSource;
        this.changedAt = changedAt;
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public Long getSubscriptionId() {
        return subscriptionId;
    }

    public Long getPreviousTierId() {
        return previousTierId;
    }

    public Long getNewTierId() {
        return newTierId;
    }

    public TierSource getTierSource() {
        return tierSource;
    }

    public Instant getChangedAt() {
        return changedAt;
    }
}
