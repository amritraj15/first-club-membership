package com.firstclub.membership;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;

/**
 * Entry point for the FirstClub Membership Program service.
 * <p>
 * Design summary (see README.md for the full write-up):
 * - Plan (Monthly/Quarterly/Yearly) and Tier (Silver/Gold/Platinum) are modeled as
 *   independent entities. A tier can be chosen by a user at subscribe time (USER_SELECTED)
 *   or reached automatically via order behaviour (SYSTEM_PROMOTED) - the spec asks for both,
 *   so {@link com.firstclub.membership.domain.Subscription} tracks which one applied via
 *   {@link com.firstclub.membership.domain.TierSource}.
 * - Tier promotion criteria are pluggable via the Strategy pattern
 *   ({@link com.firstclub.membership.strategy.TierCriteriaStrategy}).
 * - Benefits are data (rows in tier_benefit), not a class hierarchy, because the spec's own
 *   word is "configurable".
 * - Subscription lifecycle transitions are guarded by a simple allowed-transition map
 *   rather than a full State-pattern class hierarchy.
 */
@SpringBootApplication
@EnableCaching
public class MembershipApplication {
    public static void main(String[] args) {
        SpringApplication.run(MembershipApplication.class, args);
    }
}
