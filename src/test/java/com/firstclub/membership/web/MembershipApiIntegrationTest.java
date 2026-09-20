package com.firstclub.membership.web;

import com.firstclub.membership.dto.CheckoutDtos.CartItem;
import com.firstclub.membership.dto.CheckoutDtos.CheckoutRequest;
import com.firstclub.membership.dto.CheckoutDtos.CheckoutResponse;
import com.firstclub.membership.dto.AdminBenefitDtos.TierBenefitAdminResponse;
import com.firstclub.membership.dto.AdminBenefitDtos.UpsertTierBenefitRequest;
import com.firstclub.membership.dto.ExclusiveDealDtos.ExclusiveDealResponse;
import com.firstclub.membership.dto.OrderDtos.OrderPlacedResponse;
import com.firstclub.membership.dto.OrderDtos.PlaceOrderRequest;
import com.firstclub.membership.dto.PlanDtos.PlanResponse;
import com.firstclub.membership.dto.AdminPlanDtos.PlanPriceAdminResponse;
import com.firstclub.membership.dto.AdminPlanDtos.UpdatePlanPriceRequest;
import com.firstclub.membership.dto.PlanDtos.TierResponse;
import com.firstclub.membership.dto.SubscriptionDtos.ChangeTierRequest;
import com.firstclub.membership.dto.SubscriptionDtos.MembershipStatusResponse;
import com.firstclub.membership.dto.SubscriptionDtos.SubscribeRequest;
import com.firstclub.membership.dto.SubscriptionDtos.TierChangeHistoryEntry;
import com.firstclub.membership.dto.UserDtos.CreateUserRequest;
import com.firstclub.membership.dto.UserDtos.UserResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end API tests against a real embedded server (RANDOM_PORT + TestRestTemplate, not
 * MockMvc) - deliberately real HTTP over real threads, because {@link #concurrentSubscribeAttempts_onlyOneSucceeds()}
 * needs genuine concurrent requests to actually exercise the race the DB-level unique
 * constraint (ActiveMembershipLock) is there to close; an in-process mock dispatcher would not
 * prove the same thing. Each test creates its own user/plan/tier lookups rather than assuming
 * fixed seeded IDs, since DataSeeder's auto-increment IDs are an implementation detail, not a
 * contract.
 * <p>
 * Every call that touches a specific user's data - mutating OR reading - now goes through
 * {@link #asUser} / {@link #getAsUser} / {@link #userHeaders}, supplying the {@code X-User-Id}
 * header {@link com.firstclub.membership.service.CallerIdentityGuard} requires. The two catalog
 * reads ({@code GET /plans}, {@code GET /tiers}) take no user id and stay unauthenticated - see
 * that class's javadoc for why. See {@link #crossUserSubscriptionMutationIsRejectedWith403()},
 * {@link #crossUserReadIsRejectedWith403()}, and {@link #missingCallerHeaderIsRejectedWith403()}
 * for tests of the guard itself.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "membership.admin.api-key=integration-test-admin-key")
class MembershipApiIntegrationTest {

    // @LocalServerPort's package has moved between Spring Boot versions (an easy thing to get
    // wrong without a compiler to check it); the "local.server.port" property, by contrast,
    // has been stable across every Spring Boot version, so @Value is the safer choice here.
    @Value("${local.server.port}")
    private int port;

    @Autowired
    private TestRestTemplate rest;

    private Long monthlyPlanId;
    private Long silverTierId;
    private Long goldTierId;
    private Long platinumTierId;

    @BeforeEach
    void loadSeededConfig() {
        ResponseEntity<PlanResponse[]> plans = rest.getForEntity(url("/api/plans"), PlanResponse[].class);
        monthlyPlanId = List.of(plans.getBody()).stream()
                .filter(p -> p.planType().name().equals("MONTHLY"))
                .findFirst().orElseThrow().id();

        ResponseEntity<TierResponse[]> tiers = rest.getForEntity(url("/api/tiers"), TierResponse[].class);
        for (TierResponse t : tiers.getBody()) {
            switch (t.name()) {
                case "SILVER" -> silverTierId = t.id();
                case "GOLD" -> goldTierId = t.id();
                case "PLATINUM" -> platinumTierId = t.id();
                default -> { }
            }
        }
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private Long createUser(String name, String email, String cohort) {
        ResponseEntity<UserResponse> response = rest.postForEntity(
                url("/api/users"), new CreateUserRequest(name, email, cohort), UserResponse.class);
        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        return response.getBody().id();
    }

    private HttpHeaders adminHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Admin-Api-Key", "integration-test-admin-key");
        return headers;
    }

    /** The caller-identity header every user-scoped endpoint now requires - see
     *  {@link com.firstclub.membership.service.CallerIdentityGuard}. */
    private HttpHeaders userHeaders(Long userId) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-User-Id", String.valueOf(userId));
        return headers;
    }

    /** Wraps a request body with the caller-identity header for the given user. */
    private <T> HttpEntity<T> asUser(T body, Long userId) {
        return new HttpEntity<>(body, userHeaders(userId));
    }

    /** For calls that carry no body (DELETE, the reconcile-tier POST, every GET below) but
     *  still need the caller-identity header. */
    private HttpEntity<Void> asUser(Long userId) {
        return new HttpEntity<>(userHeaders(userId));
    }

    /** GETs a user-scoped endpoint with the caller-identity header - TestRestTemplate's
     *  getForEntity has no overload that accepts headers, so every now-guarded read goes
     *  through exchange() instead. */
    private <T> ResponseEntity<T> getAsUser(String path, Long userId, Class<T> responseType) {
        return rest.exchange(url(path), HttpMethod.GET, asUser(userId), responseType);
    }

    // ---- Happy path: subscribe, track, cancel ----

    @Test
    void subscribeThenTrackThenCancel() {
        Long userId = createUser("Amrit", "amrit+" + System.nanoTime() + "@example.com", null);

        ResponseEntity<MembershipStatusResponse> subscribeResponse = rest.postForEntity(
                url("/api/subscriptions"), asUser(new SubscribeRequest(userId, monthlyPlanId, silverTierId), userId),
                MembershipStatusResponse.class);
        assertEquals(HttpStatus.CREATED, subscribeResponse.getStatusCode());
        assertEquals("ACTIVE", subscribeResponse.getBody().status());
        assertEquals("SILVER", subscribeResponse.getBody().tierName());
        Long subscriptionId = subscribeResponse.getBody().subscriptionId();

        ResponseEntity<MembershipStatusResponse> membership =
                getAsUser("/api/users/" + userId + "/membership", userId, MembershipStatusResponse.class);
        assertEquals(HttpStatus.OK, membership.getStatusCode());
        assertEquals(subscriptionId, membership.getBody().subscriptionId());

        rest.exchange(url("/api/subscriptions/" + subscriptionId), HttpMethod.DELETE,
                asUser(userId), Void.class);
        ResponseEntity<MembershipStatusResponse> afterCancel =
                getAsUser("/api/users/" + userId + "/membership", userId, MembershipStatusResponse.class);
        assertEquals("CANCELLED", afterCancel.getBody().status());
    }

    // ---- Fix #1: app-level duplicate-subscribe rejection ----

    @Test
    void subscribingTwiceWhileActiveIsRejectedWith409() {
        Long userId = createUser("Priya", "priya+" + System.nanoTime() + "@example.com", null);

        rest.postForEntity(url("/api/subscriptions"),
                asUser(new SubscribeRequest(userId, monthlyPlanId, silverTierId), userId), MembershipStatusResponse.class);

        ResponseEntity<Map> second = rest.postForEntity(url("/api/subscriptions"),
                asUser(new SubscribeRequest(userId, monthlyPlanId, goldTierId), userId), Map.class);
        assertEquals(HttpStatus.CONFLICT, second.getStatusCode());
    }

    // ---- Fix #1: cancel frees the slot for re-subscribing ----

    @Test
    void afterCancelUserCanSubscribeAgain() {
        Long userId = createUser("Rahul", "rahul+" + System.nanoTime() + "@example.com", null);

        ResponseEntity<MembershipStatusResponse> first = rest.postForEntity(url("/api/subscriptions"),
                asUser(new SubscribeRequest(userId, monthlyPlanId, silverTierId), userId), MembershipStatusResponse.class);
        rest.exchange(url("/api/subscriptions/" + first.getBody().subscriptionId()),
                HttpMethod.DELETE, asUser(userId), Void.class);

        ResponseEntity<MembershipStatusResponse> second = rest.postForEntity(url("/api/subscriptions"),
                asUser(new SubscribeRequest(userId, monthlyPlanId, goldTierId), userId), MembershipStatusResponse.class);
        assertEquals(HttpStatus.CREATED, second.getStatusCode());
    }

    // ---- Fix #1 (the actual race): concurrent subscribe attempts for a brand-new user ----

    @Test
    void concurrentSubscribeAttempts_onlyOneSucceeds() throws InterruptedException {
        Long userId = createUser("Concurrent", "concurrent+" + System.nanoTime() + "@example.com", null);

        int attempts = 8;
        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        CountDownLatch startGate = new CountDownLatch(1);
        AtomicInteger created = new AtomicInteger();
        AtomicInteger conflicted = new AtomicInteger();

        List<Future<HttpStatusCode>> futures = IntStream.range(0, attempts)
                .mapToObj(i -> pool.submit(() -> {
                    startGate.await();
                    ResponseEntity<Map> response = rest.postForEntity(url("/api/subscriptions"),
                            asUser(new SubscribeRequest(userId, monthlyPlanId, silverTierId), userId), Map.class);
                    return response.getStatusCode();
                }))
                .toList();

        startGate.countDown(); // release all threads at once
        for (Future<HttpStatusCode> f : futures) {
            try {
                HttpStatusCode status = f.get(10, TimeUnit.SECONDS);
                if (status.isSameCodeAs(HttpStatus.CREATED)) {
                    created.incrementAndGet();
                } else if (status.isSameCodeAs(HttpStatus.CONFLICT)) {
                    conflicted.incrementAndGet();
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        pool.shutdown();

        // The whole point of the DB-level unique constraint: no matter how many requests race,
        // EXACTLY one creates a subscription and every other one is rejected as a conflict -
        // never two active subscriptions for the same user.
        assertEquals(1, created.get(), "exactly one concurrent subscribe attempt should succeed");
        assertEquals(attempts - 1, conflicted.get(), "every other attempt should be rejected as a conflict");
    }

    // ---- Tier promotion via order activity, manual override, re-promotion ----

    @Test
    void orderActivityPromotesTier_manualOverrideIsRespected_thenClearedByNextOrder() {
        Long userId = createUser("Tiers", "tiers+" + System.nanoTime() + "@example.com", null);
        ResponseEntity<MembershipStatusResponse> sub = rest.postForEntity(url("/api/subscriptions"),
                asUser(new SubscribeRequest(userId, monthlyPlanId, silverTierId), userId), MembershipStatusResponse.class);
        Long subscriptionId = sub.getBody().subscriptionId();

        // 11 orders crosses Gold's ">10 orders" criterion (seeded in DataSeeder).
        OrderPlacedResponse lastOrder = null;
        for (int i = 0; i < 11; i++) {
            ResponseEntity<OrderPlacedResponse> orderResponse = rest.postForEntity(
                    url("/api/users/" + userId + "/orders"), asUser(new PlaceOrderRequest(new BigDecimal("100")), userId),
                    OrderPlacedResponse.class);
            lastOrder = orderResponse.getBody();
        }
        assertEquals("GOLD", lastOrder.currentTier());

        MembershipStatusResponse afterPromotion =
                getAsUser("/api/users/" + userId + "/membership", userId, MembershipStatusResponse.class).getBody();
        assertEquals("GOLD", afterPromotion.tierName());
        assertEquals("SYSTEM_PROMOTED", afterPromotion.tierSource());

        // Manual downgrade back to Silver - must stick immediately.
        ResponseEntity<MembershipStatusResponse> downgraded = rest.exchange(
                url("/api/subscriptions/" + subscriptionId + "/tier"), HttpMethod.PATCH,
                asUser(new ChangeTierRequest(silverTierId), userId),
                MembershipStatusResponse.class);
        assertEquals("SILVER", downgraded.getBody().tierName());
        assertEquals("USER_SELECTED", downgraded.getBody().tierSource());

        // One more order should re-enter automatic evaluation and re-promote to Gold.
        ResponseEntity<OrderPlacedResponse> anotherOrder = rest.postForEntity(
                url("/api/users/" + userId + "/orders"), asUser(new PlaceOrderRequest(new BigDecimal("50")), userId),
                OrderPlacedResponse.class);
        assertEquals("GOLD", anotherOrder.getBody().currentTier());

        // Fix #5: the audit trail (TierChangeAudit) should have recorded all four transitions,
        // in order, with the right previous/new tier and source on each - not just the current
        // state, which is all `/membership` can show.
        ResponseEntity<TierChangeHistoryEntry[]> history =
                getAsUser("/api/users/" + userId + "/tier-history", userId, TierChangeHistoryEntry[].class);
        assertEquals(HttpStatus.OK, history.getStatusCode());
        List<TierChangeHistoryEntry> entries = List.of(history.getBody());
        assertEquals(4, entries.size(), "initial assignment, auto-promotion, manual override, re-promotion");

        assertEquals(null, entries.get(0).previousTierName(), "no previous tier for the initial assignment");
        assertEquals("SILVER", entries.get(0).newTierName());
        assertEquals("USER_SELECTED", entries.get(0).tierSource());

        assertEquals("SILVER", entries.get(1).previousTierName());
        assertEquals("GOLD", entries.get(1).newTierName());
        assertEquals("SYSTEM_PROMOTED", entries.get(1).tierSource());

        assertEquals("GOLD", entries.get(2).previousTierName());
        assertEquals("SILVER", entries.get(2).newTierName());
        assertEquals("USER_SELECTED", entries.get(2).tierSource());

        assertEquals("SILVER", entries.get(3).previousTierName());
        assertEquals("GOLD", entries.get(3).newTierName());
        assertEquals("SYSTEM_PROMOTED", entries.get(3).tierSource());
    }

    // ---- Fix #3: discount policy - category-specific must NOT stack with ALL ----

    @Test
    void categorySpecificDiscountOverridesGlobalDiscount_doesNotStack() {
        Long userId = createUser("Shopper", "shopper+" + System.nanoTime() + "@example.com", "VIP");
        rest.postForEntity(url("/api/subscriptions"),
                asUser(new SubscribeRequest(userId, monthlyPlanId, platinumTierId), userId), MembershipStatusResponse.class);

        // Platinum (seeded): 10% off ALL, 15% off Electronics.
        CheckoutRequest request = new CheckoutRequest(List.of(
                new CartItem("Electronics", new BigDecimal("1000")),
                new CartItem("Groceries", new BigDecimal("500"))
        ));
        ResponseEntity<CheckoutResponse> response = rest.postForEntity(
                url("/api/users/" + userId + "/checkout/benefits"), asUser(request, userId), CheckoutResponse.class);

        CheckoutResponse body = response.getBody();
        // Electronics: 15% of 1000 = 150 (NOT 25% / 250 - that would mean the two rates stacked).
        // Groceries: falls back to the 10% ALL rate = 50.
        // Total discount must be exactly 200, not 300.
        assertEquals(0, new BigDecimal("200.00").compareTo(body.totalDiscount()),
                "category-specific and ALL discounts must not stack");
        assertTrue(body.freeDelivery());
    }

    // ---- Exclusive deals and non-zero early access ----

    @Test
    void exclusiveDealOverridesGeneralDiscount_andEarlyAccessShowsItsConfiguredDays() {
        Long userId = createUser("Deals", "deals+" + System.nanoTime() + "@example.com", "VIP");
        rest.postForEntity(url("/api/subscriptions"),
                asUser(new SubscribeRequest(userId, monthlyPlanId, platinumTierId), userId), MembershipStatusResponse.class);

        ResponseEntity<ExclusiveDealResponse[]> deals =
                getAsUser("/api/users/" + userId + "/exclusive-deals", userId, ExclusiveDealResponse[].class);
        assertEquals(HttpStatus.OK, deals.getStatusCode());
        assertTrue(List.of(deals.getBody()).stream().anyMatch(deal -> deal.category().equals("Beauty")
                && deal.discountPercent().compareTo(new BigDecimal("20")) == 0));

        ResponseEntity<CheckoutResponse> response = rest.postForEntity(
                url("/api/users/" + userId + "/checkout/benefits"),
                asUser(new CheckoutRequest(List.of(new CartItem("Beauty", new BigDecimal("1000")))), userId),
                CheckoutResponse.class);

        CheckoutResponse body = response.getBody();
        assertEquals(0, body.totalDiscount().compareTo(new BigDecimal("200.00")));
        assertTrue(body.appliedBenefits().stream().anyMatch(benefit -> benefit.benefitType().equals("EXCLUSIVE_DEAL")
                && benefit.configuredValue().compareTo(new BigDecimal("20")) == 0));
        assertTrue(body.appliedBenefits().stream().anyMatch(benefit -> benefit.benefitType().equals("EARLY_ACCESS")
                && benefit.scope().equals("DAYS") && benefit.configuredValue().compareTo(new BigDecimal("7")) == 0));
    }

    // ---- Protected runtime benefit administration ----

    @Test
    void protectedAdminApiUpdatesBenefitAndCheckoutUsesNewValueImmediately() {
        ResponseEntity<Map> denied = rest.getForEntity(
                url("/api/admin/tiers/" + platinumTierId + "/benefits"), Map.class);
        assertEquals(HttpStatus.FORBIDDEN, denied.getStatusCode());

        UpsertTierBenefitRequest create = new UpsertTierBenefitRequest(
                com.firstclub.membership.domain.BenefitType.EXCLUSIVE_DEAL, new BigDecimal("25"), "Books");
        ResponseEntity<TierBenefitAdminResponse> created = rest.exchange(
                url("/api/admin/tiers/" + platinumTierId + "/benefits"), HttpMethod.POST,
                new HttpEntity<>(create, adminHeaders()), TierBenefitAdminResponse.class);
        assertEquals(HttpStatus.CREATED, created.getStatusCode());
        assertTrue(created.getBody().id() != null);

        UpsertTierBenefitRequest update = new UpsertTierBenefitRequest(
                com.firstclub.membership.domain.BenefitType.EXCLUSIVE_DEAL, new BigDecimal("30"), "Books");
        ResponseEntity<TierBenefitAdminResponse> changed = rest.exchange(
                url("/api/admin/benefits/" + created.getBody().id()), HttpMethod.PATCH,
                new HttpEntity<>(update, adminHeaders()), TierBenefitAdminResponse.class);
        assertEquals(HttpStatus.OK, changed.getStatusCode());
        assertEquals(0, changed.getBody().paramValue().compareTo(new BigDecimal("30")));

        Long userId = createUser("Admin Deal", "admin-deal+" + System.nanoTime() + "@example.com", "VIP");
        rest.postForEntity(url("/api/subscriptions"),
                asUser(new SubscribeRequest(userId, monthlyPlanId, platinumTierId), userId), MembershipStatusResponse.class);
        ResponseEntity<CheckoutResponse> checkout = rest.postForEntity(
                url("/api/users/" + userId + "/checkout/benefits"),
                asUser(new CheckoutRequest(List.of(new CartItem("Books", new BigDecimal("1000")))), userId),
                CheckoutResponse.class);
        assertEquals(0, checkout.getBody().totalDiscount().compareTo(new BigDecimal("300.00")));
    }

    @Test
    void tierApiShowsCalendarMonthDefaultAndTheOptInRollingCriterion() {
        ResponseEntity<TierResponse[]> response = rest.getForEntity(url("/api/tiers"), TierResponse[].class);
        TierResponse gold = List.of(response.getBody()).stream()
                .filter(tier -> tier.name().equals("GOLD")).findFirst().orElseThrow();
        TierResponse platinum = List.of(response.getBody()).stream()
                .filter(tier -> tier.name().equals("PLATINUM")).findFirst().orElseThrow();

        assertTrue(gold.criteria().stream().allMatch(criterion -> criterion.windowType().equals("CALENDAR_MONTH")));
        assertTrue(platinum.criteria().stream().anyMatch(criterion -> criterion.windowType().equals("ROLLING_DAYS")
                && criterion.rollingWindowDays() == 30));
    }

    // ---- Illegal transition -> 422, not 400 ----

    @Test
    void cancellingTwiceIsRejectedAsUnprocessable() {
        Long userId = createUser("Twice", "twice+" + System.nanoTime() + "@example.com", null);
        ResponseEntity<MembershipStatusResponse> sub = rest.postForEntity(url("/api/subscriptions"),
                asUser(new SubscribeRequest(userId, monthlyPlanId, silverTierId), userId), MembershipStatusResponse.class);
        Long subscriptionId = sub.getBody().subscriptionId();

        rest.exchange(url("/api/subscriptions/" + subscriptionId), HttpMethod.DELETE,
                asUser(userId), Void.class);

        ResponseEntity<Map> secondCancel = rest.exchange(
                url("/api/subscriptions/" + subscriptionId), HttpMethod.DELETE,
                asUser(userId), Map.class);
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, secondCancel.getStatusCode());
    }

    // ---- Not found ----

    @Test
    void unknownUserMembershipReturns404() {
        // Header must match the (nonexistent) path user id so CallerIdentityGuard's ownership
        // check passes and the request actually reaches the "does this user exist" check this
        // test is for - a mismatched or missing header would produce 403 instead, which is a
        // real but different failure mode, covered separately by the guard-specific tests below.
        ResponseEntity<Map> response = getAsUser("/api/users/999999/membership", 999999L, Map.class);
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    // ---- Manual reconciliation endpoint ----

    @Test
    void reconciliationEndpointIsIdempotentWhenNothingChanged() {
        Long userId = createUser("Recon", "recon+" + System.nanoTime() + "@example.com", null);
        rest.postForEntity(url("/api/subscriptions"),
                asUser(new SubscribeRequest(userId, monthlyPlanId, silverTierId), userId), MembershipStatusResponse.class);

        ResponseEntity<Map> response = rest.exchange(
                url("/api/users/" + userId + "/reconcile-tier"), HttpMethod.POST,
                asUser(userId), Map.class);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(false, response.getBody().get("tierChanged"));
    }

    @Test
    void sameIdempotencyKeyReplaysOriginalSubscription() {
        Long userId = createUser("Idempotent", "idempotent+" + System.nanoTime() + "@example.com", null);
        HttpHeaders headers = new HttpHeaders();
        headers.set("Idempotency-Key", "subscribe-" + System.nanoTime());
        headers.set("X-User-Id", String.valueOf(userId));
        HttpEntity<SubscribeRequest> request = new HttpEntity<>(
                new SubscribeRequest(userId, monthlyPlanId, silverTierId), headers);

        ResponseEntity<MembershipStatusResponse> first = rest.postForEntity(
                url("/api/subscriptions"), request, MembershipStatusResponse.class);
        ResponseEntity<MembershipStatusResponse> replay = rest.postForEntity(
                url("/api/subscriptions"), request, MembershipStatusResponse.class);

        assertEquals(HttpStatus.CREATED, first.getStatusCode());
        assertEquals(HttpStatus.CREATED, replay.getStatusCode());
        assertEquals(first.getBody().subscriptionId(), replay.getBody().subscriptionId());
    }

    @Test
    void sameIdempotencyKeyWithDifferentParametersIsRejected() {
        Long userId = createUser("IdempotencyConflict", "idempotency-conflict+" + System.nanoTime() + "@example.com", null);
        String key = "same-key-" + System.nanoTime();
        HttpHeaders headers = new HttpHeaders();
        headers.set("Idempotency-Key", key);
        headers.set("X-User-Id", String.valueOf(userId));

        ResponseEntity<MembershipStatusResponse> first = rest.postForEntity(
                url("/api/subscriptions"),
                new HttpEntity<>(new SubscribeRequest(userId, monthlyPlanId, silverTierId), headers),
                MembershipStatusResponse.class);
        assertEquals(HttpStatus.CREATED, first.getStatusCode());

        ResponseEntity<Map> conflict = rest.postForEntity(
                url("/api/subscriptions"),
                new HttpEntity<>(new SubscribeRequest(userId, monthlyPlanId, goldTierId), headers),
                Map.class);
        assertEquals(HttpStatus.CONFLICT, conflict.getStatusCode());
    }

    @Test
    void planPriceChangeCreatesNewVersionAndExistingSubscriptionIsGrandfathered() {
        Long userId = createUser("Grandfather", "grandfather+" + System.nanoTime() + "@example.com", null);
        ResponseEntity<MembershipStatusResponse> existing = rest.postForEntity(
                url("/api/subscriptions"),
                asUser(new SubscribeRequest(userId, monthlyPlanId, silverTierId), userId),
                MembershipStatusResponse.class);
        assertEquals(HttpStatus.CREATED, existing.getStatusCode());
        int oldVersion = existing.getBody().planPriceVersion();
        BigDecimal oldPrice = existing.getBody().price();

        ResponseEntity<PlanPriceAdminResponse> updated = rest.exchange(
                url("/api/admin/plans/" + monthlyPlanId + "/price"),
                HttpMethod.PATCH,
                new HttpEntity<>(new UpdatePlanPriceRequest(new BigDecimal("249.00"), "INR"), adminHeaders()),
                PlanPriceAdminResponse.class);

        assertEquals(HttpStatus.OK, updated.getStatusCode());
        assertTrue(updated.getBody().version() > oldVersion);
        assertEquals(0, new BigDecimal("249.00").compareTo(updated.getBody().price()));

        ResponseEntity<Map> oldMembership =
                getAsUser("/api/users/" + userId + "/membership", userId, Map.class);
        assertEquals(oldVersion, ((Number) oldMembership.getBody().get("planPriceVersion")).intValue());
        assertEquals(0, oldPrice.compareTo(new BigDecimal(oldMembership.getBody().get("price").toString())));

        Long newUserId = createUser("NewPrice", "new-price+" + System.nanoTime() + "@example.com", null);
        ResponseEntity<MembershipStatusResponse> newer = rest.postForEntity(
                url("/api/subscriptions"),
                asUser(new SubscribeRequest(newUserId, monthlyPlanId, silverTierId), newUserId),
                MembershipStatusResponse.class);
        assertEquals(HttpStatus.CREATED, newer.getStatusCode());
        assertEquals(updated.getBody().version(), newer.getBody().planPriceVersion());
        assertEquals(0, new BigDecimal("249.00").compareTo(newer.getBody().price()));
    }

    @Test
    void concurrentSameIdempotencyKeyReturnsOneSubscription() throws Exception {
        Long userId = createUser("ConcurrentIdempotency", "concurrent-idempotency+" + System.nanoTime() + "@example.com", null);
        String key = "concurrent-key-" + System.nanoTime();
        int attempts = 6;
        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        CountDownLatch gate = new CountDownLatch(1);
        List<Future<Long>> futures = IntStream.range(0, attempts).mapToObj(i -> pool.submit(() -> {
            gate.await();
            HttpHeaders headers = new HttpHeaders();
            headers.set("Idempotency-Key", key);
            headers.set("X-User-Id", String.valueOf(userId));
            ResponseEntity<MembershipStatusResponse> response = rest.postForEntity(
                    url("/api/subscriptions"),
                    new HttpEntity<>(new SubscribeRequest(userId, monthlyPlanId, silverTierId), headers),
                    MembershipStatusResponse.class);
            return response.getBody().subscriptionId();
        })).toList();
        gate.countDown();

        List<Long> ids = futures.stream().map(f -> {
            try { return f.get(10, TimeUnit.SECONDS); }
            catch (Exception e) { throw new RuntimeException(e); }
        }).toList();
        pool.shutdown();

        assertEquals(1, ids.stream().distinct().count());
    }

    // ---- CallerIdentityGuard: the actual point of this change ----

    @Test
    void crossUserSubscriptionMutationIsRejectedWith403() {
        Long owner = createUser("Owner", "owner+" + System.nanoTime() + "@example.com", null);
        Long attacker = createUser("Attacker", "attacker+" + System.nanoTime() + "@example.com", null);

        ResponseEntity<MembershipStatusResponse> sub = rest.postForEntity(url("/api/subscriptions"),
                asUser(new SubscribeRequest(owner, monthlyPlanId, silverTierId), owner), MembershipStatusResponse.class);
        Long subscriptionId = sub.getBody().subscriptionId();

        // Attacker asserts their OWN identity but targets the owner's subscription id - must be
        // rejected before any lifecycle/state-machine check runs, not silently succeed.
        ResponseEntity<Map> forgedCancel = rest.exchange(
                url("/api/subscriptions/" + subscriptionId), HttpMethod.DELETE,
                asUser(attacker), Map.class);
        assertEquals(HttpStatus.FORBIDDEN, forgedCancel.getStatusCode());

        ResponseEntity<Map> forgedTierChange = rest.exchange(
                url("/api/subscriptions/" + subscriptionId + "/tier"), HttpMethod.PATCH,
                asUser(new ChangeTierRequest(goldTierId), attacker), Map.class);
        assertEquals(HttpStatus.FORBIDDEN, forgedTierChange.getStatusCode());

        ResponseEntity<Map> forgedOrder = rest.postForEntity(
                url("/api/users/" + owner + "/orders"),
                asUser(new PlaceOrderRequest(new BigDecimal("100")), attacker), Map.class);
        assertEquals(HttpStatus.FORBIDDEN, forgedOrder.getStatusCode());

        // The subscription must be untouched - still active, still owned by the real user.
        MembershipStatusResponse stillActive =
                getAsUser("/api/users/" + owner + "/membership", owner, MembershipStatusResponse.class).getBody();
        assertEquals("ACTIVE", stillActive.status());
        assertEquals("SILVER", stillActive.tierName());
    }

    /** Companion to {@link #crossUserSubscriptionMutationIsRejectedWith403()}: the same guard,
     *  now applied to reads (membership, tier-history, exclusive-deals, checkout benefits) as
     *  well as mutations - an attacker asserting their own real identity must not be able to
     *  read another user's membership, history, deals, or run a checkout calculation against
     *  another user's tier. */
    @Test
    void crossUserReadIsRejectedWith403() {
        Long owner = createUser("ReadOwner", "read-owner+" + System.nanoTime() + "@example.com", "VIP");
        Long attacker = createUser("ReadAttacker", "read-attacker+" + System.nanoTime() + "@example.com", null);
        rest.postForEntity(url("/api/subscriptions"),
                asUser(new SubscribeRequest(owner, monthlyPlanId, platinumTierId), owner), MembershipStatusResponse.class);

        ResponseEntity<Map> forgedMembership =
                getAsUser("/api/users/" + owner + "/membership", attacker, Map.class);
        assertEquals(HttpStatus.FORBIDDEN, forgedMembership.getStatusCode());

        ResponseEntity<Map> forgedHistory =
                getAsUser("/api/users/" + owner + "/tier-history", attacker, Map.class);
        assertEquals(HttpStatus.FORBIDDEN, forgedHistory.getStatusCode());

        ResponseEntity<Map> forgedDeals =
                getAsUser("/api/users/" + owner + "/exclusive-deals", attacker, Map.class);
        assertEquals(HttpStatus.FORBIDDEN, forgedDeals.getStatusCode());

        ResponseEntity<Map> forgedCheckout = rest.postForEntity(
                url("/api/users/" + owner + "/checkout/benefits"),
                asUser(new CheckoutRequest(List.of(new CartItem("Beauty", new BigDecimal("1000")))), attacker),
                Map.class);
        assertEquals(HttpStatus.FORBIDDEN, forgedCheckout.getStatusCode());

        // The two catalog endpoints take no user id at all - they stay open, on purpose (see
        // CallerIdentityGuard's javadoc), so no header is required or checked here.
        ResponseEntity<TierResponse[]> tiers = rest.getForEntity(url("/api/tiers"), TierResponse[].class);
        assertEquals(HttpStatus.OK, tiers.getStatusCode());
    }

    @Test
    void missingCallerHeaderIsRejectedWith403() {
        Long userId = createUser("NoHeader", "no-header+" + System.nanoTime() + "@example.com", null);
        ResponseEntity<Map> response = rest.postForEntity(
                url("/api/subscriptions"), new SubscribeRequest(userId, monthlyPlanId, silverTierId), Map.class);
        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
    }

}
