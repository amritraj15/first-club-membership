package com.firstclub.membership.web;

import com.firstclub.membership.domain.Subscription;
import com.firstclub.membership.dto.SubscriptionDtos.ChangeTierRequest;
import com.firstclub.membership.dto.SubscriptionDtos.MembershipStatusResponse;
import com.firstclub.membership.dto.SubscriptionDtos.SubscribeRequest;
import com.firstclub.membership.service.CallerIdentityGuard;
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

/**
 * FR3/FR4/FR5/FR6 - subscribe, upgrade/downgrade, cancel, track membership + expiry.
 * <p>
 * Every MUTATING endpoint here requires an {@code X-User-Id} header asserting the caller's
 * identity, verified by {@link CallerIdentityGuard} against the resource actually being
 * mutated - see that class's javadoc for exactly what this does and does not protect against.
 * {@code getMembership} (read-only) deliberately does NOT require it - see the guard's javadoc
 * for the scope boundary.
 */
@RestController
@RequestMapping("/api")
public class SubscriptionController {

    private final SubscriptionService subscriptionService;
    private final TierEvaluationService tierEvaluationService;
    private final CallerIdentityGuard callerIdentityGuard;
    private final Clock clock;

    public SubscriptionController(SubscriptionService subscriptionService,
                                   TierEvaluationService tierEvaluationService,
                                   CallerIdentityGuard callerIdentityGuard,
                                   Clock clock) {
        this.subscriptionService = subscriptionService;
        this.tierEvaluationService = tierEvaluationService;
        this.callerIdentityGuard = callerIdentityGuard;
        this.clock = clock;
    }

    @PostMapping("/subscriptions")
    public ResponseEntity<MembershipStatusResponse> subscribe(
            @RequestHeader(value = "X-User-Id", required = false) String callerUserIdHeader,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody SubscribeRequest request) {
        callerIdentityGuard.requireCallerOwns(callerUserIdHeader, request.userId());
        Subscription subscription = subscriptionService.subscribe(request.userId(), request.planId(), request.tierId(), idempotencyKey);
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(subscription));
    }

    @PatchMapping("/subscriptions/{id}/tier")
    public MembershipStatusResponse changeTier(@RequestHeader(value = "X-User-Id", required = false) String callerUserIdHeader,
                                                @PathVariable Long id, @Valid @RequestBody ChangeTierRequest request) {
        Long callerUserId = callerIdentityGuard.requireCaller(callerUserIdHeader);
        Subscription subscription = subscriptionService.changeTier(id, request.newTierId(), callerUserId);
        return toResponse(subscription);
    }

    @DeleteMapping("/subscriptions/{id}")
    public MembershipStatusResponse cancel(@RequestHeader(value = "X-User-Id", required = false) String callerUserIdHeader, @PathVariable Long id) {
        Long callerUserId = callerIdentityGuard.requireCaller(callerUserIdHeader);
        Subscription subscription = subscriptionService.cancel(id, callerUserId);
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
    public ResponseEntity<Map<String, Object>> reconcileTier(@RequestHeader(value = "X-User-Id", required = false) String callerUserIdHeader,
                                                               @PathVariable Long userId) {
        callerIdentityGuard.requireCallerOwns(callerUserIdHeader, userId);
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
