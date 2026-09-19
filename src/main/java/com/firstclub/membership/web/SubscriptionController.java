package com.firstclub.membership.web;

import com.firstclub.membership.domain.Subscription;
import com.firstclub.membership.dto.SubscriptionDtos.ChangeTierRequest;
import com.firstclub.membership.dto.SubscriptionDtos.MembershipStatusResponse;
import com.firstclub.membership.dto.SubscriptionDtos.SubscribeRequest;
import com.firstclub.membership.service.SubscriptionService;
import com.firstclub.membership.service.TierEvaluationService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/** FR3/FR4/FR5/FR6 - subscribe, upgrade/downgrade, cancel, track membership + expiry. */
@RestController
@RequestMapping("/api")
public class SubscriptionController {

    private final SubscriptionService subscriptionService;
    private final TierEvaluationService tierEvaluationService;

    public SubscriptionController(SubscriptionService subscriptionService,
                                   TierEvaluationService tierEvaluationService) {
        this.subscriptionService = subscriptionService;
        this.tierEvaluationService = tierEvaluationService;
    }

    @PostMapping("/subscriptions")
    public ResponseEntity<MembershipStatusResponse> subscribe(@Valid @RequestBody SubscribeRequest request) {
        Subscription subscription = subscriptionService.subscribe(request.userId(), request.planId(), request.tierId());
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(subscription));
    }

    @PatchMapping("/subscriptions/{id}/tier")
    public MembershipStatusResponse changeTier(@PathVariable Long id, @Valid @RequestBody ChangeTierRequest request) {
        Subscription subscription = subscriptionService.changeTier(id, request.newTierId());
        return toResponse(subscription);
    }

    @DeleteMapping("/subscriptions/{id}")
    public MembershipStatusResponse cancel(@PathVariable Long id) {
        Subscription subscription = subscriptionService.cancel(id);
        return toResponse(subscription);
    }

    @GetMapping("/users/{userId}/membership")
    public MembershipStatusResponse getMembership(@PathVariable Long userId) {
        Subscription subscription = subscriptionService.getCurrentMembership(userId);
        return toResponse(subscription);
    }

    /**
     * Manual tier reconciliation - the lightweight safety net for the gap that a full
     * event/outbox pipeline would otherwise close: tier promotion/demotion is normally
     * triggered by order placement/cancellation only (see OrderService), so a user whose
     * qualifying activity has fallen (e.g. their order-window activity aged out with no new
     * order to re-trigger evaluation) can sit on a stale tier indefinitely with nothing to
     * prompt a recheck. A full outbox-pattern event pipeline was deliberately NOT built for
     * this exercise (see README "deliberately not implemented") - this endpoint is the
     * documented, demoable stand-in: it runs the exact same idempotent evaluation logic
     * on demand, and in production would also be wrapped in a scheduled job hitting every
     * active subscription periodically.
     */
    @PostMapping("/users/{userId}/reconcile-tier")
    public ResponseEntity<Map<String, Object>> reconcileTier(@PathVariable Long userId) {
        boolean changed = tierEvaluationService.reevaluateSafely(userId);
        Subscription subscription = subscriptionService.getCurrentMembership(userId);
        return ResponseEntity.ok(Map.of(
                "tierChanged", changed,
                "membership", toResponse(subscription)
        ));
    }

    private MembershipStatusResponse toResponse(Subscription s) {
        Instant now = Instant.now();
        long daysRemaining = Math.max(0, Duration.between(now, s.getEndDate()).toDays());
        return new MembershipStatusResponse(
                s.getId(),
                s.getUser().getId(),
                s.getPlan().getPlanType().name(),
                s.getTier().getName().name(),
                s.getStatus().name(),
                s.getTierSource().name(),
                s.getStartDate().toString(),
                s.getEndDate().toString(),
                daysRemaining
        );
    }
}
