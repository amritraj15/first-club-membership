package com.firstclub.membership.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * The database-level guard against two concurrently-created active subscriptions for the same
 * user - a plain application-level "check then insert" (as in an earlier version of this
 * service) has a race: two concurrent requests can both pass the check before either commits.
 * A row here exists <=> that user currently holds an active subscription.
 * <p>
 * IMPORTANT IMPLEMENTATION DETAIL - why the id is auto-generated rather than {@code userId}
 * itself being the {@code @Id}: if {@code userId} were the primary key and we set it explicitly
 * before calling {@code save()}, Spring Data JPA's {@code isNew()} check would see a non-null id
 * and route the call through Hibernate's {@code merge()} (UPDATE-or-insert) rather than
 * {@code persist()} (INSERT) - which would silently overwrite an existing lock row instead of
 * failing on the very duplicate we're trying to catch. Using a separate {@code @GeneratedValue}
 * id with a {@code unique} constraint on {@code user_id} keeps every {@code save()} a genuine
 * INSERT (id is always null pre-save, so Hibernate always treats it as new), and a concurrent
 * duplicate correctly fails the unique constraint. It also happens that
 * {@code GenerationType.IDENTITY} disables JDBC insert batching and executes the INSERT
 * immediately (Hibernate needs the generated key back right away) - so the constraint violation
 * surfaces synchronously within the calling transaction, not deferred to a later flush.
 */
@Entity
@Table(name = "active_membership_lock", uniqueConstraints = @UniqueConstraint(columnNames = "user_id"))
public class ActiveMembershipLock {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, unique = true)
    private Long userId;

    private Long subscriptionId;

    protected ActiveMembershipLock() {
        // JPA
    }

    public ActiveMembershipLock(Long userId, Long subscriptionId) {
        this.userId = userId;
        this.subscriptionId = subscriptionId;
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
}
