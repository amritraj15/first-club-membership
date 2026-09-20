# FirstClub Membership Program

A tiered membership backend: Plans (Monthly/Quarterly/Yearly) + Tiers (Silver/Gold/Platinum),
configurable benefits, order-driven plus scheduled tier reconciliation, exclusive deals, and a
checkout-integration endpoint. Spring Boot 3 / Java 17, H2 for tests/local development, PostgreSQL
support for shared multi-server deployments, no external services required.

## Verification status

Verified with JDK 17.0.20.1 and Maven 3.9.16. The complete `mvn clean install` lifecycle passes:

```text
Tests run: 27, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

This includes fast unit tests and Spring Boot integration tests that start an embedded Tomcat
server, exercise the REST API over HTTP, and verify concurrent subscription creation, idempotency,
and price-versioning behaviour.

The successful Maven lifecycle also compiles the application, packages the Spring Boot JAR, and
installs the artifact into the local Maven repository.

## How to build and run

Requires JDK 17+ and Maven (internet access needed for the first build, to fetch dependencies).

```bash
mvn clean install
mvn spring-boot:run
```

The app starts on `http://localhost:8080`, seeds 3 plans, 3 tiers, 2 basic users, and 5 named
active-membership scenarios, and exposes an H2 console at `/h2-console`
(JDBC URL `jdbc:h2:mem:membershipdb`, user `sa`, empty password) if you want to inspect the
schema directly.

For a shared multi-server setup, PostgreSQL configuration is also provided through
`application-postgres.yml` and `docker-compose.yml`.

Run the tests:

```bash
mvn test
```

This runs both kinds of test in the suite:
- **Unit tests** (`strategy/TierEvaluatorTest`, `service/SubscriptionStateMachineTest`,
  `service/QualificationWindowResolverTest`) - plain JUnit 5, no Spring context, fast.
- **Integration tests** (`web/MembershipApiIntegrationTest`) - `@SpringBootTest` with a real
  embedded server and `TestRestTemplate`, exercising the actual REST API end-to-end, including
  concurrent subscription creation, same-key idempotency, and plan-price versioning.

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
- **Benefits are data, not a class hierarchy** (`TierBenefit` rows: type + value + scope). The
  protected, limited runtime API can add or update only these rows and evicts the tier caches.
  It supports normal percentage discounts, category-scoped `EXCLUSIVE_DEAL`s, and entitlements
  such as `EARLY_ACCESS = 7 DAYS`; new tiers/criteria remain controlled policy changes.
- **Subscription lifecycle is an enum + an allowed-transitions map** (`SubscriptionStateMachine`),
  not a full GoF State pattern. For 3 states with simple rules, the map is exactly as correct and
  far more readable in a 20-minute code review.
- **Concurrency**: optimistic locking (`@Version` on `Subscription`) with a retry-once policy on
  conflict, then a 409 to the caller, standardized across every mutation that touches an existing
  row (`changeTier`, `cancel`). The retry is deliberately implemented via a **separate Spring bean per
  transactional unit of work** (`TierReevaluationTransaction`,
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
  Javadoc for the full layered defense: user-row `PESSIMISTIC_WRITE` locking during subscription
  creation, an application-level fast path, and the database uniqueness invariant. The
  `MembershipApiIntegrationTest.concurrentSubscribeAttempts_onlyOneSucceeds` test fires 8
  concurrent HTTP requests and asserts exactly one active membership is created.
- **Idempotency is persisted and database-backed**, not an in-memory request cache. `POST
  /api/subscriptions` accepts `Idempotency-Key`; the key is associated with the user/request
  parameters and resulting subscription so a retry returns the original subscription. Reusing
  the same key with different request parameters returns 409. The concurrent same-key integration
  test verifies that concurrent identical requests produce one subscription and deterministic
  replay rather than duplicate membership creation.
- **Discount stacking is policy-driven, not accumulated ad-hoc** (`DiscountPolicy` interface,
  `CategoryOverridesGlobalDiscountPolicy` implementation). A tier with both an ALL-scope and a
  category-scope `PERCENTAGE_DISCOUNT` benefit resolves to exactly ONE rate per cart item
  (category-specific wins if present, otherwise the ALL-scope rate) - not the sum of both. See
  that class's Javadoc for the alternatives considered (stacking, highest-wins) and why this one
  was chosen.
- **Qualification windows are criterion-level data.** Calendar month is the default, resolved in
  the configured `membership.qualification.zone-id` (`Asia/Kolkata` by default); individual
  criteria can opt into rolling days. The Platinum order-value criterion demonstrates the latter.
  One evaluation uses a single clock instant and caches order lists by resolved window, so common
  count/value rules sharing a window result in one order query rather than one per criterion.
- **Tier evaluation runs synchronously but in its own transaction** (`REQUIRES_NEW`), triggered
  immediately by order placement/cancellation and periodically by `TierReconciliationScheduler`
  (hourly by default). A bug in evaluation can never roll back or block the order write; failures
  are logged and deferred to the next trigger. No message broker or outbox is required for this
  assignment.
- **Time is supplied by one injected `Clock`**, including order placement, lifecycle/expiry
  checks, response timestamps, seed data, and qualification. This makes time-dependent behaviour
  deterministic in unit tests and keeps a single business-time source in production.
- **Expiry is computed lazily on read** (`Subscription.isCurrentlyActive`), not solely by a
  background job - a missed cron run can never make an expired subscription look active, and
  `GET /users/{id}/membership` self-corrects the persisted status on read.
- **Plan pricing is immutable at the subscription level** through `PlanVersion`. The current
  `Plan` price represents the latest catalog version; a subscription stores the exact version
  purchased. Admin price changes create a new version rather than mutating a historical version,
  so existing subscriptions are grandfathered at their purchased price.

## Ambiguities resolved (and how)

1. **Is tier user-chosen or system-computed?** Both, per the spec's own conflicting wording -
   see `TierSource` / `manualTierOverride` above.
2. **Subscribing while already active** - rejected with 409 rather than silently replacing the
   existing subscription, since discarding a paid, still-valid subscription by default is a worse
   failure mode than asking the caller to cancel first.
3. **"More than X orders"** - read as strictly greater-than, not greater-or-equal, per the
   spec's literal wording. Covered by `TierEvaluatorTest.orderCountStrictlyGreaterThanThresholdPromotes`.
4. **Refunded/cancelled orders** - excluded from every count/value window by the repository
   query, so a refund correctly un-counts itself.
5. **AND vs OR across a tier's criteria** - made configurable per tier
   (`Tier.criteriaMatchMode`) rather than hardcoded, since the spec's phrasing ("based on
   criteria like X, Y, or Z") reads as OR but a future tier might legitimately want AND.
6. **"Total order value in a month"** - calendar month is the default and is now explicit:
   `[first day 00:00, first day next month 00:00)` in `Asia/Kolkata` by default. A criterion can
   instead set `ROLLING_DAYS` with its own duration; Platinum's 15,000 order-value rule uses
   rolling 30 days. `QualificationWindowResolverTest` fixes both interpretations in tests.
7. **Can a tier demote automatically, or only promote?** Both promotion and automatic demotion
   are allowed - the evaluator always recomputes the highest CURRENTLY-qualifying tier, so a
   user whose order activity drops off can move back down, not just up. See
   `TierReevaluationTransaction`'s class Javadoc for the full reasoning and the important caveat
   (a scheduler now handles time passing even when no order arrives; manual reconciliation is
   still available for immediate operation).
8. **What does "configurable" mean for benefits/tiers?** Tier-benefit rows can be changed at
   runtime through a small, API-key-protected admin surface. Tier topology and qualification
   criteria remain code/seed managed, because changing them is a material policy decision.
9. **Discount stacking** - see the design summary above and `CategoryOverridesGlobalDiscountPolicy`.
10. **What happens when the same idempotency key is reused?** The same key with the same
    subscription request is treated as a retry and returns the original subscription. The same
    key with different user/plan/tier parameters returns 409 to prevent accidental reuse.
11. **What happens when plan pricing changes?** The current catalog price changes by creating a
    new immutable `PlanVersion`; existing subscriptions retain their original purchased version.

## What was deliberately NOT implemented (and why)

- **Full event/outbox pipeline for tier reconciliation** - this build has immediate order
  triggers, a manual reconciliation endpoint, and an hourly in-process scheduled safety net.
  It intentionally does not introduce a message broker, durable outbox, or distributed job
  coordinator. For a multi-instance production deployment, the scheduler should be replaced or
  coordinated through a durable/distributed mechanism.
- **Full GoF State pattern classes** for subscription lifecycle - an enum + transition-guard map
  is equally correct and easier to review for the three-state lifecycle used here.
- **Distributed/keyed application locks** - the implementation now uses a database-backed
  `PESSIMISTIC_WRITE` lock on the user row during subscription creation, so concurrent requests
  hitting different application servers but sharing the same database are serialized for that
  user. The `ActiveMembershipLock` unique constraint remains a second database invariant.
  Redis/ZooKeeper-style distributed locks are still unnecessary for this exercise.
- **Event-sourced subscription history** - `Subscription` remains a mutable current-state row,
  not an append-only event log. Full event sourcing and a detailed tier-change audit trail are
  production extensions, not required for the exercise.
- **Price versioning is NOT omitted anymore** - plan prices are now immutable `PlanVersion`
  records. A subscription stores the exact purchased price version, so existing subscriptions
  are grandfathered when an admin changes the current plan price. `PATCH /api/admin/plans/{id}/price`
  creates a new version; it never mutates an existing version.
- **Full order/catalog subsystem** - `OrderRecord` remains a minimal stand-in (value + timestamp)
  purely as the input signal for tier evaluation, since a real order/catalog system is out of
  scope for this exercise.
- **Broad configuration API** - runtime administration is intentionally limited to tier benefits
  and plan pricing. Tier topology and qualification criteria remain controlled policy/seed data.
- **Downstream enforcement of EARLY_ACCESS / PRIORITY_SUPPORT** - this service defines and exposes
  entitlements; it does not reach into a sales system or support-routing system to enforce them.
  Those downstream systems should consume the entitlement through their own service boundaries.
- **Full authorization boundaries / admin-vs-user API separation** - every mutating user-scoped
  endpoint (subscribe, change tier, cancel, place/cancel an order, reconcile tier) now requires
  an `X-User-Id` header, verified against the resource's actual owner by `CallerIdentityGuard`
  before any mutation runs. This is explicitly NOT authentication - the header is self-asserted,
  not checked against a password/token/session - so it stops accidental or casual cross-user
  calls, not a determined attacker who can set an arbitrary header. Read-only endpoints (`GET
  /membership`, `GET /plans`, `GET /tiers`, checkout benefit calculation, exclusive deals) are
  NOT covered - the guard is scoped to state-mutating actions, where an unauthenticated caller
  could do real harm, not to every place a user id appears in a URL. Admin mutation routes keep
  their separate `X-Admin-Api-Key` guard. Production should replace `CallerIdentityGuard`'s
  header check with the platform's real identity/session layer; every call site only needs that
  one method's return value to change.
- **Full production observability** - promotions, demotions, conflicts, and reconciliation events
  are logged through SLF4J, but the service does not add a metrics/trace/audit platform.
- **Schema migration tooling: now Flyway, not further scoped down** - `V1__init_schema.sql`
  replaces Hibernate's `ddl-auto: update` for both H2 and Postgres. `ddl-auto` is `none` rather
  than the stricter `validate`: validate would be the more correct pairing (Hibernate checking
  its expected schema against what Flyway created), but confirming an exact column-by-column
  match against a live schema export wasn't possible in the environment this migration was
  written in. Every functional constraint the app actually depends on - PKs, FKs, uniqueness on
  `ActiveMembershipLock.userId` and the idempotency-key pair - is still enforced at the DB level;
  run the full test suite after pulling this change, since `MembershipApiIntegrationTest`
  exercises nearly every column and constraint here and will surface a real mismatch as a test
  failure rather than a silent drift.

## Suggestions reviewed (from the implementation-review pass)

A prior review pass flagged 20 numbered items across three priority tiers. Here's the
disposition of each - what got fixed, what got documented instead of built, and why.

| # | Suggestion | Disposition |
|---|---|---|
| 1 | DB-level duplicate-active-subscription protection | **Fixed.** `ActiveMembershipLock` + unique constraint plus database user-row locking; covered by concurrent-request integration tests. |
| 2 | Clarify month vs rolling-30-days semantics | **Fixed.** Calendar month is the default and rolling days is an explicit per-criterion opt-in. |
| 3 | Discount stacking/precedence policy | **Fixed.** `DiscountPolicy` / `CategoryOverridesGlobalDiscountPolicy`; the double-counting bug is now structurally impossible (one rate resolved per cart item). |
| 4 | REST integration tests | **Added.** `MembershipApiIntegrationTest` - happy paths, 409/422/404 failure cases, and concurrency/idempotency tests. |
| 5 | Admin config APIs vs. "configurable" clarification | **Implemented narrowly.** Protected runtime CRUD for benefit rows only; no broad tier/criteria CRUD. |
| 6 | Standardize optimistic-lock retry across mutations | **Fixed.** `cancel` now goes through the same retry-once pattern as `changeTier`, via `SubscriptionService.withOptimisticRetry`. |
| 7 | Calendar-based duration (`plusMonths`/`plusYears`) | **Fixed.** `Plan.computeEndDate` replaces the fixed 30/90/360-day approximation. |
| 8 | Durable tier reconciliation (outbox/event pipeline) | **Partially addressed.** Immediate order triggers + manual reconciliation + hourly in-process sweep. Durable outbox/distributed scheduling remains production scope. |
| 9 | Explicitly define demotion policy | **Documented.** Promotion + automatic demotion, both driven by evaluation triggers - see ambiguity #7 and `TierReevaluationTransaction` Javadoc. |
| 10 | Benefit definition vs. enforcement | **Documented** - see "deliberately not implemented" above; this service defines/exposes entitlements, doesn't enforce them downstream. |
| 11 | Separate `Subscription` from a `TierQualification` entity | **Not done.** Would help audit/debug at real scale; adds a second entity and a sync concern for a take-home. |
| 12 | Store tier-change reason (previous/new/criterion/actual/threshold) | **Not done** - same reasoning as #11; noted as a good production addition. |
| 13 | Composable AND/OR tier criteria | **Already implemented** before this review - `Tier.criteriaMatchMode` (`ANY`/`OR`). |
| 14 | Explicit tier rank instead of enum ordering | **Already implemented** before this review - `Tier.rank`, used by `TierEvaluator` via `Comparator.comparingInt(Tier::getRank)`. |
| 15 | Richer benefit definition (priority, stackable flag, JSON config blob) | **Not done** - the current `type + paramValue + scope` shape covers everything the spec asks for; a generic config blob would trade compile-time safety for flexibility this exercise doesn't need yet. |
| 16 | Idempotency keys for subscribe | **Fixed.** `Idempotency-Key` is persisted per user and replayed safely; a reused key with different parameters returns 409. User-row locking makes the operation safe across application servers sharing the same database. |
| 17 | HTTP status code mapping (409/422/etc.) | **Fixed** the one real gap: `InvalidTransitionException` now returns 422, not 400. Cancel intentionally still returns 200 + body (not 204) - showing the resulting `CANCELLED` state is more useful for a demo than an empty response, a deliberate choice, not an oversight. |
| 18 | Authorization boundaries | **Fixed for mutations.** `CallerIdentityGuard` requires and verifies `X-User-Id` on every mutating user-scoped endpoint; admin routes keep `X-Admin-Api-Key`. Read endpoints and a real identity/session layer remain out of scope - see "deliberately not implemented" above. |
| 19 | Pricing/benefit versioning & effective dates | **Fixed for plan pricing.** Immutable `PlanVersion` is captured by each subscription; admin price changes create a new version and existing subscriptions retain the old price. |
| 20 | Observability (metrics, structured logs) | **Not done** beyond existing SLF4J logging - noted as a real gap. |

## Concurrency, idempotency, and price-version guarantees

### Multi-server concurrency

`POST /api/subscriptions` locks the target user row with `PESSIMISTIC_WRITE` inside the
subscription transaction. Because the lock is held by the shared database, two application
servers cannot simultaneously create memberships for the same user. The `ActiveMembershipLock`
unique constraint remains the database-level invariant that guarantees at most one active
membership.

```text
App Server A ----\
                 +--> Shared PostgreSQL --> PESSIMISTIC_WRITE user lock
App Server B ----/                         + ActiveMembershipLock unique(user_id)
```

### Idempotency

`POST /api/subscriptions` accepts an optional `Idempotency-Key` header. The key is persisted
with the user, plan, tier, and resulting subscription in the same transaction. Retrying the same
logical request returns the original subscription; reusing the key with different plan/tier/user
parameters returns `409 Conflict`.

Example:

```bash
curl -s -X POST localhost:8080/api/subscriptions \
  -H "Content-Type: application/json" \
  -H "X-User-Id: 42" \
  -H "Idempotency-Key: subscribe-user-42-001" \
  -d '{"userId":42,"planId":1,"tierId":1}' | jq
```

### Price versioning / grandfathering

Every plan starts with version 1. A price change creates version 2, version 3, etc. A
subscription stores the exact `PlanVersion` it purchased, so an existing subscriber keeps the
old price while new subscribers use the latest version.

The protected admin API is:

```text
PATCH /api/admin/plans/{planId}/price
X-Admin-Api-Key: <configured admin key>
```

Example:

```bash
curl -s -X PATCH localhost:8080/api/admin/plans/1/price \
  -H "X-Admin-Api-Key: $MEMBERSHIP_ADMIN_API_KEY" \
  -H "Content-Type: application/json" \
  -d '{"price":249.00,"currency":"INR"}' | jq
```

The response contains the newly-created price version. Existing subscriptions continue to
reference their previous version.

## API walkthrough (curl)

Assumes the app is running on `localhost:8080`. It seeds Amrit (no cohort), Priya (VIP), and
five active scenarios: `Seed Gold Count`, `Seed Gold Value`, `Seed Platinum VIP`, `Seed Platinum
Rolling`, and `Seed Calendar Boundary`. Their expected tiers are respectively Gold, Gold,
Platinum, Platinum, and Silver. Confirm generated IDs via the `GET` calls below rather than
assuming them.

```bash
# 1. List plans and tiers (see actual generated IDs)
curl -s localhost:8080/api/plans | jq
curl -s localhost:8080/api/tiers | jq

# 2. Subscribe user 1 to the Monthly plan at Silver tier, retaining the returned subscription id
SUB_ID=$(curl -s -X POST localhost:8080/api/subscriptions \
  -H "Content-Type: application/json" -H "X-User-Id: 1" \
  -d '{"userId": 1, "planId": 1, "tierId": 1}' | tee /dev/stderr | jq -r .subscriptionId)

# 3. Track current membership
curl -s localhost:8080/api/users/1/membership | jq

# 4. Place 11 orders of value 100 each - crosses Gold's ">10 orders" criterion
for i in $(seq 1 11); do
  curl -s -X POST localhost:8080/api/users/1/orders \
    -H "Content-Type: application/json" -H "X-User-Id: 1" -d '{"value": 100}' > /dev/null
done
curl -s localhost:8080/api/users/1/membership | jq   # tierName should now be GOLD, tierSource SYSTEM_PROMOTED

# 5. User manually downgrades back to Silver - system must NOT immediately re-promote
curl -s -X PATCH localhost:8080/api/subscriptions/$SUB_ID/tier \
  -H "Content-Type: application/json" -H "X-User-Id: 1" -d '{"newTierId": 1}' | jq
curl -s localhost:8080/api/users/1/membership | jq   # tierName SILVER, tierSource USER_SELECTED

# 6. One more order re-enters automatic evaluation - should promote back to GOLD
curl -s -X POST localhost:8080/api/users/1/orders \
  -H "Content-Type: application/json" -H "X-User-Id: 1" -d '{"value": 100}' | jq
curl -s localhost:8080/api/users/1/membership | jq   # back to GOLD, SYSTEM_PROMOTED

# 7. Checkout benefits for a cart - user 1 is on GOLD at this point (free delivery + 5% off ALL)
curl -s -X POST localhost:8080/api/users/1/checkout/benefits \
  -H "Content-Type: application/json" \
  -d '{"items": [{"category": "Electronics", "price": 2000}, {"category": "Groceries", "price": 500}]}' | jq
# expect totalDiscount = 125.00 (5% of the 2500 cart total), freeDelivery = true

# 8. Illegal transition - cancel twice
curl -s -X DELETE localhost:8080/api/subscriptions/$SUB_ID -H "X-User-Id: 1" | jq
curl -s -X DELETE localhost:8080/api/subscriptions/$SUB_ID -H "X-User-Id: 1" -w "\nHTTP %{http_code}\n"   # expect 422

# 8b. Cancelling frees the "slot" - re-subscribing for the same user now succeeds
curl -s -X POST localhost:8080/api/subscriptions \
  -H "Content-Type: application/json" -H "X-User-Id: 1" -d '{"userId": 1, "planId": 1, "tierId": 1}' | jq

# 9. Cohort-based promotion - user 2 (VIP) qualifies for Platinum with zero orders
curl -s -X POST localhost:8080/api/subscriptions \
  -H "Content-Type: application/json" -H "X-User-Id: 2" -d '{"userId": 2, "planId": 1, "tierId": 3}' | jq
curl -s localhost:8080/api/users/2/membership | jq

# 10. Discount stacking policy - user 2 is on PLATINUM,
#     which has BOTH "10% off ALL" and "15% off Electronics". The two must NOT stack:
#     Electronics gets 15%, Groceries falls back to the 10% ALL rate.
curl -s -X POST localhost:8080/api/users/2/checkout/benefits \
  -H "Content-Type: application/json" \
  -d '{"items": [{"category": "Electronics", "price": 1000}, {"category": "Groceries", "price": 500}]}' | jq
# expect totalDiscount = 200.00 (150 Electronics @15% + 50 Groceries @10%), NOT 300.00

# 11. Manual reconciliation - re-run tier evaluation on demand without a new order
curl -s -X POST localhost:8080/api/users/2/reconcile-tier -H "X-User-Id: 2" | jq

# 11b. Platinum exclusive deal + entitlement. Beauty gets its exclusive 20%, not the global 10%;
#      EARLY_ACCESS reports configuredValue 7 and scope DAYS.
curl -s localhost:8080/api/users/2/exclusive-deals | jq
curl -s -X POST localhost:8080/api/users/2/checkout/benefits \
  -H "Content-Type: application/json" \
  -d '{"items": [{"category": "Beauty", "price": 1000}]}' | jq
# expect totalDiscount = 200.00

# 12. Concurrent duplicate-subscribe protection - fire two subscribe requests for the SAME
#     new user at once; exactly one should return 201, the other 409.
curl -s -X POST localhost:8080/api/users \
  -H "Content-Type: application/json" \
  -d '{"name": "Race Test", "email": "race@example.com"}' | jq
# note the returned id, then run both concurrently:
curl -s -X POST localhost:8080/api/subscriptions -H "Content-Type: application/json" -H "X-User-Id: <id>" \
  -d '{"userId": <id>, "planId": 1, "tierId": 1}' -w "\nHTTP %{http_code}\n" &
curl -s -X POST localhost:8080/api/subscriptions -H "Content-Type: application/json" -H "X-User-Id: <id>" \
  -d '{"userId": <id>, "planId": 1, "tierId": 2}' -w "\nHTTP %{http_code}\n" &
wait
# The automated version of this exact scenario, with 8 concurrent requests instead of 2, is
# MembershipApiIntegrationTest.concurrentSubscribeAttempts_onlyOneSucceeds.

# 13. Same-key idempotency - retry the exact same subscription request with the same key.
curl -s -X POST localhost:8080/api/subscriptions \
  -H "Content-Type: application/json" -H "X-User-Id: 42" \
  -H "Idempotency-Key: subscribe-user-42-002" \
  -d '{"userId":42, "planId":1, "tierId":1}' | jq
curl -s -X POST localhost:8080/api/subscriptions \
  -H "Content-Type: application/json" -H "X-User-Id: 42" \
  -H "Idempotency-Key: subscribe-user-42-002" \
  -d '{"userId":42, "planId":1, "tierId":1}' | jq
# The second request replays the original subscription instead of creating another one.

# 14. Plan price versioning - update the current catalog price through the protected admin API.
curl -s -X PATCH localhost:8080/api/admin/plans/1/price \
  -H "X-Admin-Api-Key: $MEMBERSHIP_ADMIN_API_KEY" \
  -H "Content-Type: application/json" \
  -d '{"price":249.00,"currency":"INR"}' | jq
# Existing subscriptions retain their purchased PlanVersion; new subscriptions use the new version.
```

### Runtime benefit administration

These routes are closed by default. A shared Spring MVC interceptor protects every
`/api/admin/**` route, so newly added admin endpoints inherit the boundary automatically. Start
the app with a non-empty key, then supply the same key as `X-Admin-Api-Key`. This guard is
deliberately minimal; use your normal identity provider and administrator role in production.

```bash
MEMBERSHIP_ADMIN_API_KEY='replace-with-a-secret' mvn spring-boot:run

# List a tier's perk rows, then add a new category deal.
curl -s localhost:8080/api/admin/tiers/3/benefits \
  -H "X-Admin-Api-Key: replace-with-a-secret" | jq
curl -s -X POST localhost:8080/api/admin/tiers/3/benefits \
  -H "X-Admin-Api-Key: replace-with-a-secret" -H "Content-Type: application/json" \
  -d '{"benefitType":"EXCLUSIVE_DEAL","paramValue":25,"scope":"Books"}' | jq

# PATCH /api/admin/benefits/{benefitId} accepts the same request body.
```

## Performance notes

- Plan/tier listing and checkout-benefit application are cached / bounded in-memory lookups -
  no DB round trip per benefit at checkout, comfortably sub-10ms for realistic cart sizes.
- Tier evaluation (the O(orders-in-window) part) runs off the checkout path, on order
  placement/cancellation and the configured hourly reconciliation sweep - see
  `TierReevaluationTransaction` and `TierReconciliationScheduler`. It performs at most one
  repository query per distinct requested window in an evaluation; if window/cardinality grows
  materially, aggregate queries or precomputed qualification snapshots are the next step.
- For multi-server deployment, PostgreSQL is the shared consistency boundary for user-row locks,
  active-membership uniqueness, idempotency, and plan-version creation.
