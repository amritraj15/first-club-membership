package com.firstclub.membership.domain;

/**
 * Records WHY a subscription is at its current tier. The spec asks for tier to be both
 * "selected by the user" at subscribe time AND driven by order-behaviour criteria - these are
 * two different writers of the same field, so we tag which one wrote it last. This lets the
 * tier-evaluation job avoid silently overwriting a tier a user explicitly (down)selected on the
 * very next scheduled run, and lets a human read a subscription's history and understand why the
 * tier is what it is.
 */
public enum TierSource {
    USER_SELECTED,
    SYSTEM_PROMOTED
}
