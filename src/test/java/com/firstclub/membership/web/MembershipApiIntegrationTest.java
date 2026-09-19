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
import com.firstclub.membership.dto.PlanDtos.TierResponse;
import com.firstclub.membership.dto.SubscriptionDtos.ChangeTierRequest;
import com.firstclub.membership.dto.SubscriptionDtos.MembershipStatusResponse;
import com.firstclub.membership.dto.SubscriptionDtos.SubscribeRequest;
import com.firstclub.membership.dto.UserDtos.CreateUserRequest;
import com.firstclub.membership.dto.UserDtos.UserResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
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

    // ---- Happy path: subscribe, track, cancel ----

    @Test
    void subscribeThenTrackThenCancel() {
        Long userId = createUser("Amrit", "amrit+" + System.nanoTime() + "@example.com", null);

        ResponseEntity<MembershipStatusResponse> subscribeResponse = rest.postForEntity(
                url("/api/subscriptions"), new SubscribeRequest(userId, monthlyPlanId, silverTierId),
                MembershipStatusResponse.class);
        assertEquals(HttpStatus.CREATED, subscribeResponse.getStatusCode());
        assertEquals("ACTIVE", subscribeResponse.getBody().status());
        assertEquals("SILVER", subscribeResponse.getBody().tierName());
        Long subscriptionId = subscribeResponse.getBody().subscriptionId();

        ResponseEntity<MembershipStatusResponse> membership = rest.getForEntity(
                url("/api/users/" + userId + "/membership"), MembershipStatusResponse.class);
        assertEquals(HttpStatus.OK, membership.getStatusCode());
        assertEquals(subscriptionId, membership.getBody().subscriptionId());

        rest.delete(url("/api/subscriptions/" + subscriptionId));
        ResponseEntity<MembershipStatusResponse> afterCancel = rest.getForEntity(
                url("/api/users/" + userId + "/membership"), MembershipStatusResponse.class);
        assertEquals("CANCELLED", afterCancel.getBody().status());
    }

    // ---- Fix #1: app-level duplicate-subscribe rejection ----

    @Test
    void subscribingTwiceWhileActiveIsRejectedWith409() {
        Long userId = createUser("Priya", "priya+" + System.nanoTime() + "@example.com", null);

        rest.postForEntity(url("/api/subscriptions"),
                new SubscribeRequest(userId, monthlyPlanId, silverTierId), MembershipStatusResponse.class);

        ResponseEntity<Map> second = rest.postForEntity(url("/api/subscriptions"),
                new SubscribeRequest(userId, monthlyPlanId, goldTierId), Map.class);
        assertEquals(HttpStatus.CONFLICT, second.getStatusCode());
    }

    // ---- Fix #1: cancel frees the slot for re-subscribing ----

    @Test
    void afterCancelUserCanSubscribeAgain() {
        Long userId = createUser("Rahul", "rahul+" + System.nanoTime() + "@example.com", null);

        ResponseEntity<MembershipStatusResponse> first = rest.postForEntity(url("/api/subscriptions"),
                new SubscribeRequest(userId, monthlyPlanId, silverTierId), MembershipStatusResponse.class);
        rest.delete(url("/api/subscriptions/" + first.getBody().subscriptionId()));

        ResponseEntity<MembershipStatusResponse> second = rest.postForEntity(url("/api/subscriptions"),
                new SubscribeRequest(userId, monthlyPlanId, goldTierId), MembershipStatusResponse.class);
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
                            new SubscribeRequest(userId, monthlyPlanId, silverTierId), Map.class);
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
                new SubscribeRequest(userId, monthlyPlanId, silverTierId), MembershipStatusResponse.class);
        Long subscriptionId = sub.getBody().subscriptionId();

        // 11 orders crosses Gold's ">10 orders" criterion (seeded in DataSeeder).
        OrderPlacedResponse lastOrder = null;
        for (int i = 0; i < 11; i++) {
            ResponseEntity<OrderPlacedResponse> orderResponse = rest.postForEntity(
                    url("/api/users/" + userId + "/orders"), new PlaceOrderRequest(new BigDecimal("100")),
                    OrderPlacedResponse.class);
            lastOrder = orderResponse.getBody();
        }
        assertEquals("GOLD", lastOrder.currentTier());

        MembershipStatusResponse afterPromotion = rest.getForEntity(
                url("/api/users/" + userId + "/membership"), MembershipStatusResponse.class).getBody();
        assertEquals("GOLD", afterPromotion.tierName());
        assertEquals("SYSTEM_PROMOTED", afterPromotion.tierSource());

        // Manual downgrade back to Silver - must stick immediately.
        ResponseEntity<MembershipStatusResponse> downgraded = rest.exchange(
                url("/api/subscriptions/" + subscriptionId + "/tier"), org.springframework.http.HttpMethod.PATCH,
                new org.springframework.http.HttpEntity<>(new ChangeTierRequest(silverTierId)),
                MembershipStatusResponse.class);
        assertEquals("SILVER", downgraded.getBody().tierName());
        assertEquals("USER_SELECTED", downgraded.getBody().tierSource());

        // One more order should re-enter automatic evaluation and re-promote to Gold.
        ResponseEntity<OrderPlacedResponse> anotherOrder = rest.postForEntity(
                url("/api/users/" + userId + "/orders"), new PlaceOrderRequest(new BigDecimal("50")),
                OrderPlacedResponse.class);
        assertEquals("GOLD", anotherOrder.getBody().currentTier());
    }

    // ---- Fix #3: discount policy - category-specific must NOT stack with ALL ----

    @Test
    void categorySpecificDiscountOverridesGlobalDiscount_doesNotStack() {
        Long userId = createUser("Shopper", "shopper+" + System.nanoTime() + "@example.com", "VIP");
        rest.postForEntity(url("/api/subscriptions"),
                new SubscribeRequest(userId, monthlyPlanId, platinumTierId), MembershipStatusResponse.class);

        // Platinum (seeded): 10% off ALL, 15% off Electronics.
        CheckoutRequest request = new CheckoutRequest(List.of(
                new CartItem("Electronics", new BigDecimal("1000")),
                new CartItem("Groceries", new BigDecimal("500"))
        ));
        ResponseEntity<CheckoutResponse> response = rest.postForEntity(
                url("/api/users/" + userId + "/checkout/benefits"), request, CheckoutResponse.class);

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
                new SubscribeRequest(userId, monthlyPlanId, platinumTierId), MembershipStatusResponse.class);

        ResponseEntity<ExclusiveDealResponse[]> deals = rest.getForEntity(
                url("/api/users/" + userId + "/exclusive-deals"), ExclusiveDealResponse[].class);
        assertEquals(HttpStatus.OK, deals.getStatusCode());
        assertTrue(List.of(deals.getBody()).stream().anyMatch(deal -> deal.category().equals("Beauty")
                && deal.discountPercent().compareTo(new BigDecimal("20")) == 0));

        ResponseEntity<CheckoutResponse> response = rest.postForEntity(
                url("/api/users/" + userId + "/checkout/benefits"),
                new CheckoutRequest(List.of(new CartItem("Beauty", new BigDecimal("1000")))), CheckoutResponse.class);

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
                url("/api/admin/tiers/" + platinumTierId + "/benefits"), org.springframework.http.HttpMethod.POST,
                new HttpEntity<>(create, adminHeaders()), TierBenefitAdminResponse.class);
        assertEquals(HttpStatus.CREATED, created.getStatusCode());
        assertTrue(created.getBody().id() != null);

        UpsertTierBenefitRequest update = new UpsertTierBenefitRequest(
                com.firstclub.membership.domain.BenefitType.EXCLUSIVE_DEAL, new BigDecimal("30"), "Books");
        ResponseEntity<TierBenefitAdminResponse> changed = rest.exchange(
                url("/api/admin/benefits/" + created.getBody().id()), org.springframework.http.HttpMethod.PATCH,
                new HttpEntity<>(update, adminHeaders()), TierBenefitAdminResponse.class);
        assertEquals(HttpStatus.OK, changed.getStatusCode());
        assertEquals(0, changed.getBody().paramValue().compareTo(new BigDecimal("30")));

        Long userId = createUser("Admin Deal", "admin-deal+" + System.nanoTime() + "@example.com", "VIP");
        rest.postForEntity(url("/api/subscriptions"),
                new SubscribeRequest(userId, monthlyPlanId, platinumTierId), MembershipStatusResponse.class);
        ResponseEntity<CheckoutResponse> checkout = rest.postForEntity(
                url("/api/users/" + userId + "/checkout/benefits"),
                new CheckoutRequest(List.of(new CartItem("Books", new BigDecimal("1000")))), CheckoutResponse.class);
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
                new SubscribeRequest(userId, monthlyPlanId, silverTierId), MembershipStatusResponse.class);
        Long subscriptionId = sub.getBody().subscriptionId();

        rest.delete(url("/api/subscriptions/" + subscriptionId));

        ResponseEntity<Map> secondCancel = rest.exchange(
                url("/api/subscriptions/" + subscriptionId), org.springframework.http.HttpMethod.DELETE,
                null, Map.class);
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, secondCancel.getStatusCode());
    }

    // ---- Not found ----

    @Test
    void unknownUserMembershipReturns404() {
        ResponseEntity<Map> response = rest.getForEntity(url("/api/users/999999/membership"), Map.class);
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    // ---- Manual reconciliation endpoint ----

    @Test
    void reconciliationEndpointIsIdempotentWhenNothingChanged() {
        Long userId = createUser("Recon", "recon+" + System.nanoTime() + "@example.com", null);
        rest.postForEntity(url("/api/subscriptions"),
                new SubscribeRequest(userId, monthlyPlanId, silverTierId), MembershipStatusResponse.class);

        ResponseEntity<Map> response = rest.postForEntity(
                url("/api/users/" + userId + "/reconcile-tier"), null, Map.class);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(false, response.getBody().get("tierChanged"));
    }
}
