package com.firstclub.membership.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A placed order, used purely as the input signal for tier evaluation in this exercise (full
 * order/checkout/catalog is out of scope - see README). {@code cancelled} orders are excluded
 * from order-count / order-value qualification windows, so a refund correctly un-counts itself
 * rather than permanently inflating a user's tier eligibility.
 */
@Entity
@Table(name = "order_record")
public class OrderRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "order_value", nullable = false)
    private BigDecimal value;

    private Instant placedAt;

    private boolean cancelled = false;

    protected OrderRecord() {
        // JPA
    }

    public OrderRecord(User user, BigDecimal value, Instant placedAt) {
        this.user = user;
        this.value = value;
        this.placedAt = placedAt;
    }

    public Long getId() {
        return id;
    }

    public User getUser() {
        return user;
    }

    public BigDecimal getValue() {
        return value;
    }

    public Instant getPlacedAt() {
        return placedAt;
    }

    public boolean isCancelled() {
        return cancelled;
    }

    public void setCancelled(boolean cancelled) {
        this.cancelled = cancelled;
    }
}
