package com.firstclub.membership.domain;

import jakarta.persistence.*;

/**
 * Durable idempotency record for subscription creation. The key is scoped to the user so a
 * client can safely retry the same logical operation against any application server.
 */
@Entity
@Table(name = "subscription_idempotency",
        uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "idempotency_key"}))
public class SubscriptionIdempotency {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "idempotency_key", nullable = false, length = 200)
    private String idempotencyKey;

    @Column(name = "plan_id", nullable = false)
    private Long planId;

    @Column(name = "tier_id", nullable = false)
    private Long tierId;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "subscription_id", nullable = false, unique = true)
    private Subscription subscription;

    protected SubscriptionIdempotency() {}

    public SubscriptionIdempotency(Long userId, String idempotencyKey, Long planId, Long tierId,
                                   Subscription subscription) {
        this.userId = userId;
        this.idempotencyKey = idempotencyKey;
        this.planId = planId;
        this.tierId = tierId;
        this.subscription = subscription;
    }

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public Long getPlanId() { return planId; }
    public Long getTierId() { return tierId; }
    public Subscription getSubscription() { return subscription; }
}
