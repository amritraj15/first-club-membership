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
import java.time.Clock;
import java.time.Instant;
import java.util.Map;

/** FR3/FR4/FR5/FR6 - subscribe, upgrade/downgrade, cancel, track membership + expiry. */
@RestController
@RequestMapping("/api")
public class SubscriptionController {

    private final SubscriptionService subscriptionService;
    private final TierEvaluationService tierEvaluationService;
    private final Clock clock;

    public SubscriptionController(SubscriptionService subscriptionService,
                                   TierEvaluationService tierEvaluationService,
                                   Clock clock) {
        this.subscriptionService = subscriptionService;
        this.tierEvaluationService = tierEvaluationService;
        this.clock = clock;
    }

    @PostMapping("/subscriptions")
    public ResponseEntity<MembershipStatusResponse> subscribe(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody SubscribeRequest request) {
        Subscription subscription = subscriptionService.subscribe(request.userId(), request.planId(), request.tierId(), idempotencyKey);
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
     * Runs the same idempotent reconciliation used by the scheduled safety net immediately on
     * demand. It is useful after an operator changes data or wants a visible demo result.
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
        Instant now = clock.instant();
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
                daysRemaining,
                s.getPlanVersion().getVersionNumber(),
                s.getPlanVersion().getPrice(),
                s.getPlanVersion().getCurrency()
        );
    }
}
