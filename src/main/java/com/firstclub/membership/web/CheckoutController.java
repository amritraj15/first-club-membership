package com.firstclub.membership.web;

import com.firstclub.membership.domain.Subscription;
import com.firstclub.membership.dto.CheckoutDtos.CheckoutRequest;
import com.firstclub.membership.dto.CheckoutDtos.CheckoutResponse;
import com.firstclub.membership.exception.ConflictException;
import com.firstclub.membership.service.BenefitService;
import com.firstclub.membership.service.CallerIdentityGuard;
import com.firstclub.membership.service.SubscriptionService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;

/** The checkout-integration path (FR9) - applies the user's current tier's benefits to a cart.
 *  This is the endpoint the <10ms-style latency discussion applies to; see BenefitService.
 *  Requires {@code X-User-Id} matching {@code userId} - see {@link CallerIdentityGuard}. */
@RestController
@RequestMapping("/api")
public class CheckoutController {

    private final SubscriptionService subscriptionService;
    private final BenefitService benefitService;
    private final CallerIdentityGuard callerIdentityGuard;
    private final Clock clock;

    public CheckoutController(SubscriptionService subscriptionService, BenefitService benefitService,
                               CallerIdentityGuard callerIdentityGuard, Clock clock) {
        this.subscriptionService = subscriptionService;
        this.benefitService = benefitService;
        this.callerIdentityGuard = callerIdentityGuard;
        this.clock = clock;
    }

    @PostMapping("/users/{userId}/checkout/benefits")
    public CheckoutResponse applyBenefits(@RequestHeader(value = "X-User-Id", required = false) String callerUserIdHeader,
                                           @PathVariable Long userId, @Valid @RequestBody CheckoutRequest request) {
        callerIdentityGuard.requireCallerOwns(callerUserIdHeader, userId);
        Subscription subscription = subscriptionService.getCurrentMembership(userId);
        // getCurrentMembership intentionally returns EXPIRED/CANCELLED subscriptions too (so
        // "track membership" can show them) - but a lapsed membership must not grant benefits
        // at checkout, so that check belongs here, not in the tracking read.
        if (!subscription.isCurrentlyActive(clock.instant())) {
            throw new ConflictException("User " + userId + " has no active membership - benefits do not apply");
        }
        return benefitService.applyBenefits(subscription, request.items());
    }
}
