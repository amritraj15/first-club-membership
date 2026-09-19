# FirstClub Membership Program

A tiered membership backend: Plans (Monthly/Quarterly/Yearly) + Tiers (Silver/Gold/Platinum),
configurable benefits, order-driven automatic tier promotion, and a checkout-integration
endpoint. Spring Boot 3 / Java 17, H2 in-memory database, no external services required.

## Verification status

Verified with JDK 17.0.20.1 and Maven 3.9.16. The complete `mvn clean test` suite passes:

```text
Tests run: 18, Failures: 0, Errors: 0, Skipped: 0
```

This includes fast unit tests and Spring Boot integration tests that start an embedded Tomcat
server, exercise the REST API over HTTP, and verify concurrent subscription creation is safely
limited to one active membership per user.

## How to build and run

Requires JDK 17+ and Maven (internet access needed for the first build, to fetch dependencies).

```bash
mvn clean install
mvn spring-boot:run
```

The app starts on `http://localhost:8080`, seeds demo data on boot (3 plans, 3 tiers with
benefits/criteria, 2 users), and exposes an H2 console at `/h2-console`
(JDBC URL `jdbc:h2:mem:membershipdb`, user `sa`, empty password) if you want to inspect the
schema directly.

Run the tests:

```bash
mvn test
```

This runs both kinds of test in the suite:
- **Unit tests** (`strategy/TierEvaluatorTest`, `service/SubscriptionStateMachineTest`) - plain
  JUnit 5, no Spring context, fast.
- **Integration tests** (`web/MembershipApiIntegrationTest`) - `@SpringBootTest` with a real
  embedded server and `TestRestTemplate`, exercising the actual REST API end-to-end, including a
  genuine concurrent-request test for the duplicate-subscription fix. These start a real Spring
  context and embedded Tomcat, so they're slower than the unit tests - expect the full `mvn test`
  run to take noticeably longer than just the unit tests would.

## Design summary

See the Javadoc on each class for the reasoning inline; this section is the short version.

- **Plan and Tier are independent entities**, joined by `Subscription`. The spec asks for tier
  to be both "selected by the user" at subscribe time AND driven by order-behaviour criteria -
  these are two different writers of the same field, so `Subscription.tierSource` +
  `manualTierOverride` track which one last wrote it, and the automatic evaluator skips a
  subscription with an active manual override until the user's next order (see
  `TierEvaluationService` and `OrderService` Javadoc for the exact policy).
- **Strategy pattern for tier-promotion criteria** (`TierCriteriaStrategy` + three
  implementations: order count, order value, cohort). This is the one place a full pattern is
  used, because the spec explicitly lists three heterogeneous, extensible criteria types. Adding
  a new one means one new class, zero changes to existing code - see
  `TierEvaluatorTest.cancelledOrdersMustBeExcludedByCallerBeforeEvaluation` and neighbours for
  proof this is independently testable.
- **Benefits are data, not a class hierarchy** (`TierBenefit` rows: type + param + scope). The
  spec's own word is "configurable" - read as "changeable without a redeploy," which points at
  data, not polymorphism.
- **Subscription lifecycle is an enum + an allowed-transitions map** (`SubscriptionStateMachine`),
  not a full GoF State pattern. For 3 states with simple rules, the map is exactly as correct and
  far more readable in a 20-minute code review.
- **Concurrency**: optimistic locking (`@Version` on `Subscription`) with a retry-once policy on
  conflict, then a 409 to the caller, standardized across every mutation that touches an existing
  row (`changeTier`, `cancel`). The retry is deliberately implemented via a **separate Spring
  bean per transactional unit of work** (`TierReevaluationTransaction`,
  `SubscriptionMutationTransactions`) rather than a same-class method, because Spring's
  `@Transactional` is implemented via an AOP proxy that a same-class ("self-invocation") call
  bypasses entirely - a same-class retry would silently run with no transaction boundary at all,
  and a same-class retry after a failed flush is invalid JPA usage regardless. This is a genuine,
  easy-to-miss correctness trap in Spring apps and is called out explicitly in the Javadoc of
  every affected class.
- **Duplicate active subscriptions are prevented at the DATABASE level**, not just the
  application level (`ActiveMembershipLock`, a row with a unique constraint on `user_id`, created
  atomically alongside the `Subscription` in one transaction). An app-level check alone
  ("query for an active subscription, then insert if none") has a race: two concurrent requests
  can both pass the check before either commits. See `SubscriptionMutationTransactions.createSubscription`
  Javadoc for the full layered defense (app-level fast-path + DB constraint), and
  `MembershipApiIntegrationTest.concurrentSubscribeAttempts_onlyOneSucceeds` for a test that
  actually fires 8 concurrent HTTP requests at it and asserts exactly one wins.
- **Discount stacking is policy-driven, not accumulated ad-hoc** (`DiscountPolicy` interface,
  `CategoryOverridesGlobalDiscountPolicy` implementation). A tier with both an ALL-scope and a
  category-scope `PERCENTAGE_DISCOUNT` benefit resolves to exactly ONE rate per cart item
  (category-specific wins if present, otherwise the ALL-scope rate) - not the sum of both. See
  that class's Javadoc for the alternatives considered (stacking, highest-wins) and why this one
  was chosen.
- **Tier evaluation runs synchronously but in its own transaction** (`REQUIRES_NEW`), triggered
  by order placement/cancellation - not via an event bus. A bug in evaluation can never roll back
  or block the order write; a failure there is logged and deferred to the next trigger rather
  than propagated. No message broker, no async infrastructure - deliberately, see "what we did
  not build" below.
- **Expiry is computed lazily on read** (`Subscription.isCurrentlyActive`), not solely by a
  background job - a missed cron run can never make an expired subscription look active, and
  `GET /users/{id}/membership` self-corrects the persisted status on read.

## Ambiguities resolved (and how)

1. **Is tier user-chosen or system-computed?** Both, per the spec's own conflicting wording -
   see `TierSource` / `manualTierOverride` above.
2. **Subscribing while already active** - rejected with 409 rather than silently replacing the
   existing subscription, since discarding a paid, still-valid subscription by default is a worse
   failure mode than asking the caller to cancel first.
3. **"More than X orders"** - read as strictly greater-than, not greater-or-equal, per the
   spec's literal wording. Covered by `TierEvaluatorTest.orderCountStrictlyGreaterThanThresholdPromotes`.
4. **Refunded/cancelled orders** - excluded from the rolling count/value window
   (`OrderRecordRepository.findByUserIdAndCancelledFalseAndPlacedAtAfter`), so a refund correctly
   un-counts itself.
5. **AND vs OR across a tier's criteria** - made configurable per tier
   (`Tier.criteriaMatchMode`) rather than hardcoded, since the spec's phrasing ("based on
   criteria like X, Y, or Z") reads as OR but a future tier might legitimately want AND.
6. **"Total order value in a month"** - implemented as a rolling 30-day window, not a calendar
   month. Both are defensible readings; rolling avoids a behavioural cliff at the 1st of the
   month and a timezone dependency the spec doesn't specify. See the constant-level Javadoc on
   `TierReevaluationTransaction.EVALUATION_WINDOW_DAYS` for the full tradeoff and exactly what
   would need to change to switch to calendar-month semantics.
7. **Can a tier demote automatically, or only promote?** Both promotion and automatic demotion
   are allowed - the evaluator always recomputes the highest CURRENTLY-qualifying tier, so a
   user whose order activity drops off can move back down, not just up. See
   `TierReevaluationTransaction`'s class Javadoc for the full reasoning and the important caveat
   (demotion only happens when evaluation actually runs - see the new reconciliation endpoint
   below).
8. **What does "configurable" mean for benefits/tiers?** Configurable at the DATA level (rows in
   `tier_benefit` / `tier_criterion`, changeable without a redeploy) via `DataSeeder` in this
   build - NOT configurable via a runtime admin API. No admin CRUD endpoints
   (`POST /api/admin/tiers`, etc.) were built; that would be a meaningful scope increase for a
   take-home evaluated on the core domain model, not the admin surface around it. Stated
   explicitly here rather than left ambiguous.
9. **Discount stacking** - see the design summary above and `CategoryOverridesGlobalDiscountPolicy`.

## What was deliberately NOT implemented (and why)

- **Full event/outbox pipeline for tier reconciliation** - order-triggered evaluation
  (synchronous, own transaction) plus a manually-triggerable
  `POST /users/{id}/reconcile-tier` endpoint covers the requirement without building a message
  broker, an outbox table, and a consumer worker. A production deployment would additionally
  want a scheduled job calling the same idempotent evaluation logic across all active
  subscriptions periodically - the manual endpoint is the demoable stand-in for that job.
- **Full GoF State pattern classes** for subscription lifecycle - an enum + transition-guard map
  is equally correct and easier to review.
- **Distributed/keyed locking infrastructure** - a single `@Version` column with retry-once
  covers "bonus for concurrency" without solving a scale problem this exercise doesn't have.
  (Duplicate-subscription prevention specifically DOES get a DB-level fix - `ActiveMembershipLock`
  - because that one is a real, demonstrated race, not a hypothetical scale concern.)
- **Event-sourced subscription history** - `Subscription` is a mutable row, not an append-only
  log. Worth doing at real scale for audit purposes; not worth the machinery here. (A lighter
  version of this - storing WHY a tier changed, e.g. "GOLD -> PLATINUM, reason: ORDER_VALUE,
  actual ₹85,000, required ₹75,000" - was also considered and deferred for the same reason.)
- **Price versioning / grandfathering** - if a plan's price changes, this system does not
  preserve the price an already-subscribed user is paying. Flagged here explicitly as a real
  gap rather than silently omitted - a production system needs this.
- **Full order/catalog subsystem** - `OrderRecord` is a minimal stand-in (just value + timestamp)
  purely as the input signal for tier evaluation, since a real order/catalog system is out of
  scope for this exercise.
- **Cache eviction on config update** - `PlanService` caches plans/tiers (`@Cacheable`, Spring's
  built-in in-memory cache, no extra dependency) since they sit on the checkout-latency path and
  rarely change, but there's no admin endpoint to mutate them in this build, so no eviction path
  was built either. A real admin-update endpoint would need to evict `"plans"`/`"tiers"`/
  `"tiersDesc"` on write.
- **Admin configuration API** (`POST /api/admin/tiers`, etc.) - see ambiguity #8 above.
- **Downstream enforcement of EARLY_ACCESS / PRIORITY_SUPPORT** - this service defines and
  returns these as entitlements (`checkout/benefits` reports whether they apply), but does not
  implement the systems that would actually consume them (a sales service gating early access,
  a support-routing system prioritizing tickets). Keeping the membership service's job as
  "define and expose the entitlement" rather than reaching into unrelated systems keeps it
  modular - the boundary is deliberate, not an oversight.
- **Idempotency keys for `POST /api/subscriptions`** - a real production concern (a client
  retrying after a network timeout could otherwise create a second subscription), but the
  DB-level `ActiveMembershipLock` constraint already prevents the specific bad outcome
  (two ACTIVE subscriptions for one user) even without an idempotency key; a key would mainly
  buy a cleaner error/replay experience for the retrying client, which is a real but lower-value
  improvement than the two Priority-1 fixes above.
- **Authorization boundaries / admin-vs-user API separation** - this build has no auth layer at
  all (matching the assignment's backend-functionality focus); every endpoint is open. Noted as
  a real gap for a production system, not addressed here.
- **Observability (metrics/structured audit logs)** - `TierReevaluationTransaction` and the
  mutation transactions log promotions, demotions, and conflicts via SLF4J, but there's no
  metrics emission (`tier_promotion_count`, etc.) or structured audit fields beyond what's in
  the log messages already. Reasonable for a take-home; a real gap for production.

## Suggestions reviewed (from the implementation-review pass)

A prior review pass flagged 20 numbered items across three priority tiers. Here's the
disposition of each - what got fixed, what got documented instead of built, and why.

| # | Suggestion | Disposition |
|---|---|---|
| 1 | DB-level duplicate-active-subscription protection | **Fixed.** `ActiveMembershipLock` + unique constraint; see design summary and `SubscriptionMutationTransactions`. Covered by a genuine concurrent-request integration test. |
| 2 | Clarify month vs rolling-30-days semantics | **Documented**, not changed - rolling window kept, tradeoff explained in `TierReevaluationTransaction` and ambiguity #6 above. |
| 3 | Discount stacking/precedence policy | **Fixed.** `DiscountPolicy` / `CategoryOverridesGlobalDiscountPolicy`; the double-counting bug is now structurally impossible (one rate resolved per cart item). |
| 4 | REST integration tests | **Added.** `MembershipApiIntegrationTest` - happy paths, 409/422/404 failure cases, and the concurrency test. |
| 5 | Admin config APIs vs. "configurable" clarification | **Documented, not built** - see ambiguity #8. A real admin CRUD surface is a scope increase this exercise doesn't need. |
| 6 | Standardize optimistic-lock retry across mutations | **Fixed.** `cancel` now goes through the same retry-once pattern as `changeTier`, via `SubscriptionService.withOptimisticRetry`. |
| 7 | Calendar-based duration (`plusMonths`/`plusYears`) | **Fixed.** `Plan.computeEndDate` replaces the fixed 30/90/360-day approximation. |
| 8 | Durable tier reconciliation (outbox/event pipeline) | **Partially addressed** - a manual `POST /users/{id}/reconcile-tier` endpoint, not a full outbox pipeline. See "deliberately not implemented" above. |
| 9 | Explicitly define demotion policy | **Documented.** Promotion + automatic demotion, both driven by evaluation triggers - see ambiguity #7 and `TierReevaluationTransaction` Javadoc. |
| 10 | Benefit definition vs. enforcement | **Documented** - see "deliberately not implemented" above; this service defines/exposes entitlements, doesn't enforce them downstream. |
| 11 | Separate `Subscription` from a `TierQualification` entity | **Not done.** Would help audit/debug at real scale; adds a second entity and a sync concern for a take-home. |
| 12 | Store tier-change reason (previous/new/criterion/actual/threshold) | **Not done** - same reasoning as #11; noted as a good production addition. |
| 13 | Composable AND/OR tier criteria | **Already implemented** before this review - `Tier.criteriaMatchMode` (`ANY`/`OR`). |
| 14 | Explicit tier rank instead of enum ordering | **Already implemented** before this review - `Tier.rank`, used by `TierEvaluator` via `Comparator.comparingInt(Tier::getRank)`. |
| 15 | Richer benefit definition (priority, stackable flag, JSON config blob) | **Not done** - the current `type + paramValue + scope` shape covers everything the spec asks for; a generic config blob would trade compile-time safety for flexibility this exercise doesn't need yet. |
| 16 | Idempotency keys for subscribe | **Not done** - see "deliberately not implemented" above; the DB constraint already prevents the dangerous outcome. |
| 17 | HTTP status code mapping (409/422/etc.) | **Fixed** the one real gap: `InvalidTransitionException` now returns 422, not 400. Cancel intentionally still returns 200 + body (not 204) - showing the resulting `CANCELLED` state is more useful for a demo than an empty response, a deliberate choice, not an oversight. |
| 18 | Authorization boundaries | **Not done** - out of scope for this exercise; noted as a real gap. |
| 19 | Pricing/benefit versioning & effective dates | **Not done** - same territory as the pre-existing "price versioning/grandfathering" gap already documented. |
| 20 | Observability (metrics, structured logs) | **Not done** beyond existing SLF4J logging - noted as a real gap. |



## API walkthrough (curl)

Assumes the app is running on `localhost:8080` with the seeded demo data (user id `1` = Amrit,
no cohort; user id `2` = Priya, cohort `VIP`; plan id `1/2/3` = Monthly/Quarterly/Yearly; tier id
`1/2/3` = Silver/Gold/Platinum - **confirm actual IDs via the `GET` calls below**, since H2
auto-increment order should match seed order but don't assume it blindly).

```bash
# 1. List plans and tiers (see actual generated IDs)
curl -s localhost:8080/api/plans | jq
curl -s localhost:8080/api/tiers | jq

# 2. Subscribe user 1 to the Monthly plan at Silver tier
curl -s -X POST localhost:8080/api/subscriptions \
  -H "Content-Type: application/json" \
  -d '{"userId": 1, "planId": 1, "tierId": 1}' | jq

# 3. Track current membership
curl -s localhost:8080/api/users/1/membership | jq

# 4. Place 11 orders of value 100 each - crosses Gold's ">10 orders" criterion
for i in $(seq 1 11); do
  curl -s -X POST localhost:8080/api/users/1/orders \
    -H "Content-Type: application/json" -d '{"value": 100}' > /dev/null
done
curl -s localhost:8080/api/users/1/membership | jq   # tierName should now be GOLD, tierSource SYSTEM_PROMOTED

# 5. User manually downgrades back to Silver - system must NOT immediately re-promote
curl -s -X PATCH localhost:8080/api/subscriptions/1/tier \
  -H "Content-Type: application/json" -d '{"newTierId": 1}' | jq
curl -s localhost:8080/api/users/1/membership | jq   # tierName SILVER, tierSource USER_SELECTED

# 6. One more order re-enters automatic evaluation - should promote back to GOLD
curl -s -X POST localhost:8080/api/users/1/orders \
  -H "Content-Type: application/json" -d '{"value": 100}' | jq
curl -s localhost:8080/api/users/1/membership | jq   # back to GOLD, SYSTEM_PROMOTED

# 7. Checkout benefits for a cart - user 1 is on GOLD at this point (free delivery + 5% off ALL)
curl -s -X POST localhost:8080/api/users/1/checkout/benefits \
  -H "Content-Type: application/json" \
  -d '{"items": [{"category": "Electronics", "price": 2000}, {"category": "Groceries", "price": 500}]}' | jq
# expect totalDiscount = 125.00 (5% of the 2500 cart total), freeDelivery = true

# 8. Illegal transition - cancel twice
curl -s -X DELETE localhost:8080/api/subscriptions/1 | jq
curl -s -X DELETE localhost:8080/api/subscriptions/1 -w "\nHTTP %{http_code}\n"   # expect 422

# 8b. Cancelling frees the "slot" - re-subscribing for the same user now succeeds
curl -s -X POST localhost:8080/api/subscriptions \
  -H "Content-Type: application/json" -d '{"userId": 1, "planId": 1, "tierId": 1}' | jq

# 9. Cohort-based promotion - user 2 (VIP) qualifies for Platinum with zero orders
curl -s -X POST localhost:8080/api/subscriptions \
  -H "Content-Type: application/json" -d '{"userId": 2, "planId": 1, "tierId": 3}' | jq
curl -s localhost:8080/api/users/2/membership | jq

# 10. Discount stacking policy (bug #3 from the review, now fixed) - user 2 is on PLATINUM,
#     which has BOTH "10% off ALL" and "15% off Electronics". The two must NOT stack: Electronics
#     gets 15% (not 25%), Groceries falls back to the 10% ALL rate.
curl -s -X POST localhost:8080/api/users/2/checkout/benefits \
  -H "Content-Type: application/json" \
  -d '{"items": [{"category": "Electronics", "price": 1000}, {"category": "Groceries", "price": 500}]}' | jq
# expect totalDiscount = 200.00 (150 Electronics @15% + 50 Groceries @10%), NOT 300.00

# 11. Manual reconciliation - re-run tier evaluation on demand without a new order
curl -s -X POST localhost:8080/api/users/2/reconcile-tier | jq

# 12. Concurrent duplicate-subscribe protection (bug #1 from the review) - fire two subscribe
#     requests for the SAME new user at once; exactly one should return 201, the other 409.
curl -s -X POST localhost:8080/api/users \
  -H "Content-Type: application/json" \
  -d '{"name": "Race Test", "email": "race@example.com"}' | jq
# note the returned id, then run both concurrently:
curl -s -X POST localhost:8080/api/subscriptions -H "Content-Type: application/json" \
  -d '{"userId": <id>, "planId": 1, "tierId": 1}' -w "\nHTTP %{http_code}\n" &
curl -s -X POST localhost:8080/api/subscriptions -H "Content-Type: application/json" \
  -d '{"userId": <id>, "planId": 1, "tierId": 2}' -w "\nHTTP %{http_code}\n" &
wait
# The automated version of this exact scenario, with 8 concurrent requests instead of 2, is
# MembershipApiIntegrationTest.concurrentSubscribeAttempts_onlyOneSucceeds.
```

## Performance notes

- Plan/tier listing and checkout-benefit application are cached / bounded in-memory lookups -
  no DB round trip per benefit at checkout, comfortably sub-10ms for realistic cart sizes.
- Tier evaluation (the O(orders-in-window) part) runs off the checkout path, only on order
  placement/cancellation - see `TierReevaluationTransaction`.
