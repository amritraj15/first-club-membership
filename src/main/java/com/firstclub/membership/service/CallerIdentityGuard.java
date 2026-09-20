package com.firstclub.membership.service;

import com.firstclub.membership.exception.ForbiddenException;
import org.springframework.stereotype.Component;

/**
 * A minimal "who is calling" boundary for every endpoint that mutates a specific user's data -
 * subscribe, change tier, cancel, place/cancel an order, and trigger reconciliation. Before this
 * guard existed, every {@code /api/users/{userId}/...} and {@code /api/subscriptions/{id}}
 * mutation trusted the path/body id outright: any caller could cancel, retier, or place orders
 * against ANY user id just by supplying it, with nothing checking that the caller was that user.
 * <p>
 * This is deliberately NOT authentication. {@code X-User-Id} is self-asserted, not verified
 * against a password, token, or session - there is no login flow in this exercise (see README's
 * "full authorization boundaries... out of scope" note, which still stands: this guard doesn't
 * replace an identity provider, it just makes "who is allowed to act on whose data" an explicit,
 * enforced rule instead of an unchecked assumption). A production deployment replaces the header
 * check in {@link #requireCaller} with whatever the platform's real identity/session layer
 * resolves the caller to; every call site that uses this guard only needs that one method's
 * return value to change, not its own logic.
 * <p>
 * Read-only endpoints ({@code GET /users/{userId}/membership}, {@code GET /plans}, checkout
 * benefit calculation, etc.) are NOT covered by this guard - it is scoped to state-mutating
 * actions, which is where an unauthenticated caller can actually do harm (cancel someone else's
 * paid membership, force order-driven tier changes on someone else's account), not to every
 * place a user id appears in a URL. Broadening that scope is a reasonable follow-up, not done
 * here to keep this change reviewable as one clear boundary rather than a rewrite of every route.
 * <p>
 * As of this change, every endpoint that returns or acts on a SPECIFIC user's data requires
 * this header - {@code GET /membership}, {@code GET /tier-history}, {@code GET
 * /exclusive-deals}, and {@code POST /checkout/benefits}, in addition to every mutation. The
 * two genuinely public catalog endpoints, {@code GET /plans} and {@code GET /tiers}, deliberately
 * remain open: they take no user id at all, so there is no owner to check a caller against -
 * requiring a header there would mean "any value at all passes," which is friction with zero
 * access-control benefit, not protection. Applying the guard where it can't mean anything would
 * be worse than not applying it, since it would misleadingly suggest those routes are protected.
 */
@Component
public class CallerIdentityGuard {

    /** @return the caller's asserted user id, or throws 403 if the header is missing/invalid. */
    public Long requireCaller(String userIdHeader) {
        if (userIdHeader == null || userIdHeader.isBlank()) {
            throw new ForbiddenException("X-User-Id header is required to perform this action");
        }
        try {
            return Long.parseLong(userIdHeader.trim());
        } catch (NumberFormatException ex) {
            throw new ForbiddenException("X-User-Id header must be a numeric user id");
        }
    }

    /** Convenience for the common case: resolve the caller from the raw header, then
     *  immediately assert they own the resource identified by {@code resourceOwnerUserId}. */
    public Long requireCallerOwns(String userIdHeader, Long resourceOwnerUserId) {
        Long caller = requireCaller(userIdHeader);
        requireOwnership(caller, resourceOwnerUserId);
        return caller;
    }

    /** For call sites that already resolved the caller id (e.g. a controller already called
     *  {@link #requireCaller} once) and just need the ownership assertion against a resource
     *  loaded later, in the service layer - avoids re-stringifying and re-parsing an id that is
     *  already a {@code Long}. */
    public void requireOwnership(Long callerUserId, Long resourceOwnerUserId) {
        if (!callerUserId.equals(resourceOwnerUserId)) {
            throw new ForbiddenException(
                    "X-User-Id " + callerUserId + " is not authorized to act on user " + resourceOwnerUserId);
        }
    }
}
