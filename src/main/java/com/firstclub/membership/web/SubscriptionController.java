package com.firstclub.membership.web;

import com.firstclub.membership.domain.Subscription;
import com.firstclub.membership.domain.Tier;
import com.firstclub.membership.domain.TierChangeAudit;
import com.firstclub.membership.dto.SubscriptionDtos.ChangeTierRequest;
import com.firstclub.membership.dto.SubscriptionDtos.MembershipStatusResponse;
import com.firstclub.membership.dto.SubscriptionDtos.SubscribeRequest;
import com.firstclub.membership.dto.SubscriptionDtos.TierChangeHistoryEntry;
import com.firstclub.membership.repository.TierChangeAuditRepository;
import com.firstclub.membership.service.CallerIdentityGuard;
import com.firstclub.membership.service.PlanService;
import com.firstclub.membership.service.SubscriptionService;
import com.firstclub.membership.service.TierEvaluationService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * FR3/FR4/FR5/FR6 - subscribe, upgrade/downgrade, cancel, track membership + expiry.
 * <p>
 * Every endpoint here that returns or mutates a specific user's data requires an
 * {@code X-User-Id} header asserting the caller's identity, verified by {@link
 * CallerIdentityGuard} against the resource actually being accessed - see that class's javadoc
 * for exactly what this does and does not protect against, and why the two catalog endpoints
 * ({@code GET /plans}, {@code GET /tiers}, in {@link PlanController}) are the deliberate
 * exception.
 */
@RestController
@RequestMapping("/api")
public class SubscriptionController {

    private final SubscriptionService subscriptionService;
    private final TierEvaluationService tierEvaluationService;
    private final CallerIdentityGuard callerIdentityGuard;
    private final TierChangeAuditRepository tierChangeAuditRepository;
    private final PlanService planService;
    private final Clock clock;

    public SubscriptionController(SubscriptionService subscriptionService,
                                   TierEvaluationService tierEvaluationService,
                                   CallerIdentityGuard callerIdentityGuard,
                                   TierChangeAuditRepository tierChangeAuditRepository,
                                   PlanService planService,
                                   Clock clock) {
        this.subscriptionService = subscriptionService;
        this.tierEvaluationService = tierEvaluationService;
        this.callerIdentityGuard = callerIdentityGuard;
        this.tierChangeAuditRepository = tierChangeAuditRepository;
        this.planService = planService;
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
    public MembershipStatusResponse getMembership(@RequestHeader(value = "X-User-Id", required = false) String callerUserIdHeader,
                                                    @PathVariable Long userId) {
        callerIdentityGuard.requireCallerOwns(callerUserIdHeader, userId);
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

    /**
     * The append-only tier-change trail for this user, oldest first - see {@link TierChangeAudit}.
     */
    @GetMapping("/users/{userId}/tier-history")
    public List<TierChangeHistoryEntry> getTierHistory(@RequestHeader(value = "X-User-Id", required = false) String callerUserIdHeader,
                                                         @PathVariable Long userId) {
        callerIdentityGuard.requireCallerOwns(callerUserIdHeader, userId);
        return tierChangeAuditRepository.findByUserIdOrderByChangedAtAsc(userId).stream()
                .map(this::toHistoryEntry)
                .toList();
    }

    private TierChangeHistoryEntry toHistoryEntry(TierChangeAudit audit) {
        String previousTierName = audit.getPreviousTierId() == null
                ? null
                : planService.getTier(audit.getPreviousTierId()).getName().name();
        Tier newTier = planService.getTier(audit.getNewTierId());
        return new TierChangeHistoryEntry(
                audit.getSubscriptionId(),
                previousTierName,
                newTier.getName().name(),
                audit.getTierSource().name(),
                audit.getChangedAt().toString()
        );
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
