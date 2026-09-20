package com.firstclub.membership.domain;

/** Kinds of perk a tier can grant. Adding a new type here is the ONE place a genuinely new
 *  benefit *behaviour* requires a code change - everything else about a benefit (which tiers
 *  have it, at what value, over what scope) is data in {@link TierBenefit}. */
public enum BenefitType {
    FREE_DELIVERY,
    /** Graduated delivery-speed perk, distinct from the binary FREE_DELIVERY flag - paramValue
     *  is the guaranteed delivery window in days (e.g. 1 for next-day). The spec's own example
     *  list ("higher discounts, faster delivery, exclusive coupons") names delivery speed
     *  specifically, which FREE_DELIVERY alone (free vs. not) doesn't model. */
    EXPEDITED_DELIVERY,
    PERCENTAGE_DISCOUNT,
    /** A category-scoped member-only price reduction, distinct from the general discount. */
    EXCLUSIVE_DEAL,
    EARLY_ACCESS,
    PRIORITY_SUPPORT
}
