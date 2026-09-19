package com.firstclub.membership.domain;

/** Membership tier. Rank ordering (Silver < Gold < Platinum) lives on the {@link Tier} entity,
 *  not here, so new tiers can be added/reordered via data rather than an enum change. */
public enum TierName {
    SILVER,
    GOLD,
    PLATINUM
}
