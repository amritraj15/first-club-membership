package com.firstclub.membership.web;

import com.firstclub.membership.domain.Subscription;
import com.firstclub.membership.dto.ExclusiveDealDtos.ExclusiveDealResponse;
import com.firstclub.membership.exception.ConflictException;
import com.firstclub.membership.service.BenefitService;
import com.firstclub.membership.service.SubscriptionService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.util.List;

/** Returns the active member's category-scoped exclusive deals for discovery before checkout. */
@RestController
@RequestMapping("/api/users")
public class ExclusiveDealController {

    private final SubscriptionService subscriptionService;
    private final BenefitService benefitService;
    private final Clock clock;

    public ExclusiveDealController(SubscriptionService subscriptionService, BenefitService benefitService, Clock clock) {
        this.subscriptionService = subscriptionService;
        this.benefitService = benefitService;
        this.clock = clock;
    }

    @GetMapping("/{userId}/exclusive-deals")
    public List<ExclusiveDealResponse> list(@PathVariable Long userId) {
        Subscription subscription = subscriptionService.getCurrentMembership(userId);
        if (!subscription.isCurrentlyActive(clock.instant())) {
            throw new ConflictException("User " + userId + " has no active membership - deals do not apply");
        }
        return benefitService.exclusiveDeals(subscription);
    }
}
