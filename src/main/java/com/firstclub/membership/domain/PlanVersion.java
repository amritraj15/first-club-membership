package com.firstclub.membership.domain;

import jakarta.persistence.*;

import java.math.BigDecimal;

/** Immutable commercial version of a plan. Existing subscriptions keep their version forever. */
@Entity
@Table(name = "plan_version", uniqueConstraints = @UniqueConstraint(columnNames = {"plan_id", "version_number"}))
public class PlanVersion {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "plan_id", nullable = false)
    private Plan plan;

    @Column(name = "version_number", nullable = false)
    private int versionNumber;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal price;

    @Column(nullable = false, length = 3)
    private String currency;

    protected PlanVersion() {}

    public PlanVersion(Plan plan, int versionNumber, BigDecimal price, String currency) {
        this.plan = plan;
        this.versionNumber = versionNumber;
        this.price = price;
        this.currency = currency;
    }

    public Long getId() { return id; }
    public Plan getPlan() { return plan; }
    public int getVersionNumber() { return versionNumber; }
    public BigDecimal getPrice() { return price; }
    public String getCurrency() { return currency; }
}
