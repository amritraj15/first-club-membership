package com.firstclub.membership.domain;

/** Kinds of perk a tier can grant. Adding a new type here is the ONE place a genuinely new
 *  benefit *behaviour* requires a code change - everything else about a benefit (which tiers
 *  have it, at what value, over what scope) is data in {@link TierBenefit}. */
public enum BenefitType {
    FREE_DELIVERY,
    PERCENTAGE_DISCOUNT,
    EARLY_ACCESS,
    PRIORITY_SUPPORT
}
