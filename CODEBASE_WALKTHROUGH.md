# Codebase Walkthrough — Every File, Every Method, How It Works

Third companion to `SYSTEM_DESIGN.md` (architecture/patterns) and `JAVA_CONCEPTS.md` (language
features). This one is scoped narrowly: **what each file in `src/main` is for, what each of its
methods does, and mechanically how.** Organized by package, in dependency order (domain first,
since everything else depends on it; tests are summarized at the end rather than repeated from
`SYSTEM_DESIGN.md`).

---

## `domain/` — entities and enums

### `Subscription.java`
The central row: one active-or-historical membership per user per period.

- **`isCurrentlyActive(Instant now)`** — `return status == ACTIVE && endDate.isAfter(now)`. Pure,
  no I/O. This is the *lazy expiry* mechanism: nothing flips `status` to `EXPIRED` on a timer;
  every read path calls this instead, so a missed background job can never make an expired row
  look active.
- **`setTier` / `setTierSource` / `setStatus` / `setEndDate` / `setManualTierOverride`** — the
  *only* mutators the class exposes. No `setId`, no `setUser`, no `setPlan` — those are set once
  at construction and never again, by omission rather than a runtime guard.
- **`@Version private Long version`** — Hibernate-managed optimistic-lock token, incremented on
  every `UPDATE`; the mechanism behind Section 8.3 of `SYSTEM_DESIGN.md`.
- Constructor sets `status = ACTIVE`, `tierSource = USER_SELECTED` unconditionally — every
  subscription starts life as an explicit user choice, even one that gets promoted a second later.

### `User.java`
Name, email (unique), optional cohort string (`"VIP"` or `null`). Two constructors: `protected
User()` (Hibernate's reflection-based instantiation) and the real public one. No behavior beyond
getters — this entity is intentionally inert; all the interesting logic that *uses* a user's data
lives in the strategy classes and services that read it.

### `Plan.java`
- **`updateCurrentPrice(price, currency)`** — mutates the plan's *catalog* price. Called only from
  `AdminPlanService.updatePrice`, always immediately after a new `PlanVersion` is created — the
  plan's current price and the latest version are kept in lockstep by convention (one call site).
- **`computeEndDate(Instant start)`** — `start.atZone(UTC).plusMonths(planType.getMonths())
  .toInstant()`. **How, precisely:** `Instant` alone has no calendar arithmetic (it's a raw point
  on the timeline, no concept of "a month"); going through `ZonedDateTime` first is what makes
  `plusMonths` available, and using it instead of `plus(30, DAYS)` per month is what makes a
  Yearly plan expire exactly 12 calendar months later rather than 360 days later (a real drift
  bug this specifically fixes).
- **`addVersion` / `@OneToMany versions`** — bookkeeping link back to every `PlanVersion` ever
  issued for this plan; not read anywhere in the hot path (checkout/subscribe never traverses it),
  kept for completeness/inspection.

### `PlanVersion.java`
An immutable price/currency snapshot tied to `(plan, versionNumber)`. No setters at all — a price
change is always a **new row** (`AdminPlanService.updatePrice`), never an `UPDATE` on an existing
one. This is what makes grandfathering work: a `Subscription.planVersion` foreign key, once set,
points at a row that will never change underneath it.

### `Tier.java`
- **`rank`** (plain `int`) — used by `TierEvaluator`'s `Comparator.comparingInt(Tier::getRank)` to
  find "the highest qualifying tier." An explicit rank field rather than relying on enum ordinal
  ordering, so tier ordering is a data decision, not an accident of declaration order in
  `TierName`.
- **`criteriaMatchMode`** (`ANY`/`ALL` via `CriteriaMatchMode`) — whether the tier's criteria are
  OR'd or AND'd together; read by `TierEvaluator.qualifies`.
- **`addBenefit` / `getBenefits`**, **`addCriterion` / `getCriteria`** — the two `@OneToMany`
  collections a tier owns; both cascade on save so seeding/admin-adding a benefit persists via the
  parent.

### `TierCriterion.java`
One qualification rule. Two constructors: a 3-arg one (type, threshold, cohortName) that
**defaults `windowType` to `CALENDAR_MONTH`**, and a 5-arg one that explicitly sets
`windowType`/`rollingWindowDays` (used only for Platinum's rolling-30-day value rule in the
seeder). This asymmetry — most criteria use the short constructor, one uses the long one — is
*why* calendar-month is correctly described as "the default" rather than "the only option."

### `TierBenefit.java`
`type` + `paramValue` + `scope`, plus an **`update(...)` method** that overwrites all three
fields in place — used by `AdminBenefitService.update` to modify a benefit row without
constructing a new entity (the row's identity/id stays the same across the edit, unlike
`PlanVersion`'s deliberate immutability — a benefit is genuinely mutable configuration, a price
history is genuinely not).

### `TierChangeAudit.java`
Append-only: `userId`, `subscriptionId`, `previousTierId` (nullable — null only for the very first
assignment), `newTierId`, `tierSource`, `changedAt`. **No `@ManyToOne` relations** — every
reference is a plain `Long`, matching the "written once, read back in bulk, never navigated from"
usage pattern. Written from exactly three call sites: initial subscribe, manual `changeTier`, and
automatic promotion/demotion — always in the same transaction as the change it records.

### `ActiveMembershipLock.java`
Two `Long` fields (`userId`, `subscriptionId`), no relations. Exists *purely* to hold a
`UNIQUE(user_id)` database constraint — see Section 8.2 of `SYSTEM_DESIGN.md`. Created in the same
transaction as a new `Subscription`; deleted (via `ActiveMembershipLockRepository.deleteByUserId`)
whenever a subscription is cancelled or found stale-expired, freeing the slot.

### `SubscriptionIdempotency.java`
`userId`, `idempotencyKey`, `planId`, `tierId`, and a `@OneToOne subscription` reference. One row
per successfully-processed `Idempotency-Key`; a retry with the same key looks this row up and
returns `getSubscription()` directly rather than creating anything.

### `OrderRecord.java`
Deliberately minimal: `userId`, `value` (column named `order_value` to dodge SQL's reserved
`VALUE` keyword), `placedAt`, `cancelled` (boolean, flipped by `OrderService.cancelOrder`, never
deleted — a cancelled order stays queryable, it's just excluded from qualification windows by the
repository query's `CancelledFalse` clause).

### Enums (`PlanType`, `TierName`, `TierSource`, `SubscriptionStatus`, `BenefitType`,
`CriteriaType`, `CriteriaWindowType`, `CriteriaMatchMode`)
Each is a closed, small domain with **no behavior beyond `PlanType.getMonths()`** (returns 1/3/12
— read by `Plan.computeEndDate`). Every one of these appears in an exhaustive `switch` or an
`EnumMap` somewhere in the service layer — see `JAVA_CONCEPTS.md` Sections 2–3 for exactly how
that's exploited for compile-time safety.

---

## `repository/` — Spring Data interfaces (no implementation bodies — Spring generates them)

| Interface | Notable method | How it works |
|---|---|---|
| `UserRepository` | `findByIdForUpdate(id)` | `@Lock(PESSIMISTIC_WRITE)` + `@Query` — issues `SELECT ... FOR UPDATE`, held for the whole enclosing transaction |
| `PlanRepository` | `findByIdForUpdate(id)` | Same pessimistic-lock pattern, applied to `Plan` — serializes concurrent admin price changes on the *same* plan |
| `SubscriptionRepository` | `findDistinctUserIdsByStatus(status, Pageable)` | `@Query` with explicit `order by s.user.id` and a separate `countQuery` — the ordering is load-bearing (Section 8.5/`JAVA_CONCEPTS.md`); Spring can't auto-derive a count query from a `DISTINCT` projection, hence the explicit one |
| `ActiveMembershipLockRepository` | `findByUserId`, `deleteByUserId` | Method-name-derived queries — Spring Data parses the method name into JPQL |
| `SubscriptionIdempotencyRepository` | `findByUserIdAndIdempotencyKey` | Same derivation mechanism, compound key |
| `OrderRecordRepository` | `findByUserIdAndCancelledFalseAndPlacedAtGreaterThanEqualAndPlacedAtLessThan` | One long method name *is* the entire windowed-query implementation — Spring Data builds the `WHERE` clause purely from the method signature |
| `TierChangeAuditRepository` | `findByUserIdOrderByChangedAtAsc` | Same derivation, ascending order for chronological display |
| `PlanVersionRepository` | `findTopByPlanIdOrderByVersionNumberDesc` | `findTop...` = `LIMIT 1` — "give me the current version" |
| `TierRepository` | `findAllByOrderByRankAsc/Desc` | Two pre-sorted full-table reads — small table, cached one layer up in `PlanService` |
| `TierBenefitRepository`, `PlanRepository` (base CRUD) | — | Plain `JpaRepository<T, Long>`, no custom methods needed |

**How Spring Data works here, mechanically:** none of these interfaces have a body. At startup,
Spring generates a dynamic proxy implementing each interface — for method-name-derived queries, it
parses the method name (`findByUserIdAndCancelledFalseAnd...`) into a JPQL `WHERE` clause at
proxy-creation time; for `@Query`-annotated methods, it uses the JPQL string verbatim, substituting
`@Param`-bound arguments.

---

## `strategy/` — the pluggable tier-criteria evaluation

### `TierCriteriaStrategy.java`
One-method interface: `boolean isSatisfied(User user, List<OrderRecord> recentOrders,
TierCriterion criterion)`. The entire extension point for "a new way to qualify for a tier."

### `MinOrderCountStrategy.java`
`return recentOrders.size() > criterion.getThreshold().intValue();` — strictly greater-than, per
the spec's literal wording ("more than X orders").

### `MinOrderValueStrategy.java`
Sums `recentOrders.stream().map(OrderRecord::getValue).reduce(BigDecimal.ZERO, BigDecimal::add)`,
compares against the threshold with `.compareTo(...) > 0` — never `==` on `BigDecimal`, since two
mathematically-equal `BigDecimal`s can differ in scale (`5000` vs `5000.00`) and `.equals()` would
wrongly say they're different; `.compareTo()` is scale-independent.

### `CohortStrategy.java`
`return criterion.getCohortName().equalsIgnoreCase(user.getCohort());` — the only strategy that
ignores `recentOrders` entirely; a user can qualify with zero order history, which is exactly the
seeded Platinum-VIP scenario.

### `TierCriteriaStrategyResolver.java`
`Map<CriteriaType, TierCriteriaStrategy>` built once at construction by iterating a
Spring-injected `List<TierCriteriaStrategy>` and keying each by its `getSupportedType()` (or
equivalent) — **how Spring wires this:** every `@Component`-annotated strategy implementation gets
auto-collected into that injected list purely by matching the interface type; the resolver never
names a concrete strategy class anywhere.

### `TierEvaluator.java`
- **`qualifies(User, Function<TierCriterion,List<OrderRecord>>, Tier)`** — resolves each of the
  tier's criteria to its strategy via the resolver, evaluates each, then combines results with
  either `.allMatch(...)` or `.anyMatch(...)` depending on `tier.getCriteriaMatchMode()`.
- **`evaluate(User, Function<...>, List<Tier>)`** — `tiers.stream().filter(this::qualifies)
  .max(Comparator.comparingInt(Tier::getRank))` — the single line that embodies "always pick the
  highest currently-qualifying tier," which is *why* a VIP-cohort user with one order jumps
  straight to Platinum rather than stepping through Gold.

---

## `service/` — business logic

### `SubscriptionMutationTransactions.java` — the only writer of `Subscription`/`ActiveMembershipLock`

- **`createSubscription(userId, planId, tierId, idempotencyKey)`**: locks the user row
  (`findByIdForUpdate`) → lazily expires any stale-ACTIVE existing subscription found → if none
  genuinely active, checks the idempotency key (replay if matched) → constructs `Subscription` +
  `ActiveMembershipLock` → saves both → writes the initial `TierChangeAudit` row — all inside one
  `@Transactional` method, so the lock, the two inserts, and the audit row commit or roll back
  together.
- **`changeTier(subscriptionId, newTierId, callerUserId)`**: load → ownership check → lifecycle
  check → **liveness check** (`isCurrentlyActive`) → mutate → save → audit write. The exact order
  (ownership *before* liveness) is deliberate — see `SYSTEM_DESIGN.md` Section 2.3.
- **`cancel(subscriptionId, callerUserId)`**: same guard shape as `changeTier`, transitions status
  to `CANCELLED`, and — the detail worth stating out loud — **deletes the `ActiveMembershipLock`
  row**, which is what actually frees the user to subscribe again (the unique constraint is on
  that table, not on `Subscription` itself).

### `SubscriptionService.java` — the retry/translation façade in front of the above

- **`subscribe` / `changeTier` / `cancel`**: each wraps the matching
  `SubscriptionMutationTransactions` call in `withOptimisticRetry` — catch
  `ObjectOptimisticLockingFailureException`, retry exactly once through the *same injected proxy*
  (never `this`), then a 409 on a second failure.
- **`subscribe`'s catch block additionally handles `DataIntegrityViolationException`** — the
  three-way branch (idempotency replay / known `ActiveMembershipLock` race → 409 / unknown → 
  re-throw raw) detailed in `SYSTEM_DESIGN.md` Section 7.3.
- **`getCurrentMembership(userId)`**: `findFirstByUserIdOrderByStartDateDesc` (no status filter,
  deliberately — an already-expired subscription should still be *shown*, just correctly labeled)
  → `orElseThrow(NotFoundException)`.

### `TierEvaluationService.java` — the public entry point for "recompute this user's tier"

- **`reevaluateSafely(userId)`**: calls the retry helper, catches *any* `RuntimeException` at the
  outer boundary and logs+returns `false` rather than propagating — this is what makes a bug in
  evaluation logically incapable of taking down an order-placement request that triggered it.
- **`doReevaluateWithRetry(userId, attemptsLeft)`**: private recursive helper — calls
  `TierReevaluationTransaction.reevaluateInNewTransaction` (a **different bean**, so the
  `REQUIRES_NEW` on that method actually takes effect), catches the optimistic-lock exception,
  recurses once with `attemptsLeft - 1`, then gives up quietly.

### `TierReevaluationTransaction.java` — the only writer of automatic tier changes

- **`reevaluateInNewTransaction(userId)`** (`@Transactional(REQUIRES_NEW)`): loads the active
  subscription → bails early (`return false`) if absent, not currently active, or
  `manualTierOverride == true` → resolves each criterion's order window via
  `QualificationWindowResolver` (caching by resolved window so criteria sharing a window run one
  query, not one each) → calls `TierEvaluator.evaluate` → if the result differs from the current
  tier (**either direction**, promotion or demotion), updates it, sets `tierSource =
  SYSTEM_PROMOTED`, and writes a `TierChangeAudit` row.

### `TierReconciliationScheduler.java` — the hourly safety net

- **`reconcileActiveSubscriptions()`** (`@Scheduled`): `do { page =
  subscriptionRepository.findDistinctUserIdsByStatus(ACTIVE, PageRequest.of(pageNumber,
  batchSize)); page.forEach(tierEvaluationService::reevaluateSafely); pageNumber++; }
  while (page.hasNext());` — walks the active-user base in bounded pages rather than loading it
  all into one `List`, calling the exact same `reevaluateSafely` the order-placement path calls
  (one evaluation implementation, two triggers).

### `BenefitService.java` — checkout-time perk resolution

- **`applyBenefits(Subscription, List<CartItem>)`**: for each cart item, resolves one discount
  rate via `DiscountPolicy.resolveRate` and multiplies it against the item price
  (`RoundingMode.HALF_UP`, scale 2); separately, a `switch` over every `TierBenefit` not tied to a
  specific cart item (`FREE_DELIVERY`, `EXPEDITED_DELIVERY`, `PRIORITY_SUPPORT`, `EARLY_ACCESS`)
  populates the non-discount fields of the response.
- **`exclusiveDeals(Subscription)`**: filters the tier's benefits down to `EXCLUSIVE_DEAL` type,
  maps each to a response DTO — the data behind `GET /exclusive-deals`.

### `DiscountPolicy.java` (interface) / `CategoryOverridesGlobalDiscountPolicy.java` (impl)
- **`resolveRate(category, List<TierBenefit>)`**: filters to `PERCENTAGE_DISCOUNT`-type benefits,
  looks for one whose `scope` case-insensitively matches `category` — if found, returns its rate
  and stops; otherwise falls back to the `ALL`-scope benefit's rate; otherwise `BigDecimal.ZERO`.
  **How the no-stacking guarantee is enforced mechanically:** the method returns after finding the
  *first* matching rate — there's no code path that sums two rates, because there's only ever one
  `return` statement reached per call.

### `PlanService.java` — cached catalog reads
- **`listPlans` / `listTiersAscending` / `listTiersDescending`**: each `@Cacheable` under a
  distinct cache name — Spring wraps these methods so a call with the same (no) arguments returns
  a cached result after the first invocation, until evicted.
- **`getPlan(id)` / `getTier(id)`**: plain, uncached single-row lookups (id-keyed lookups
  aren't worth caching the way "the whole catalog" is).

### `AdminPlanService.java`
- **`updatePrice(planId, request)`**: locks the plan row (`findByIdForUpdate`) → finds the current
  highest version number → creates version `N+1` → calls `plan.updateCurrentPrice(...)` → saves
  both → `@CacheEvict(cacheNames="plans")` clears the cached catalog so the next `GET /plans` call
  reflects the change instead of serving a stale cached price.

### `AdminBenefitService.java`
- **`list(tierId)`**: read-only, maps every existing `TierBenefit` on the tier to a response DTO.
- **`add(tierId, request)`**: validates the request (percentage benefits must be `0 < x ≤ 100`;
  `EARLY_ACCESS`/`EXPEDITED_DELIVERY` days must be `> 0`) → constructs and attaches a new
  `TierBenefit` → **`saveAndFlush`**, specifically, not `save` — so the response DTO's `id` field
  reflects the actual persisted identifier immediately, not a pending/transient one.
- **`update(benefitId, request)`**: loads by id, calls the entity's own `update(...)` method
  (Section `TierBenefit.java` above) rather than reconstructing the row.
- Both mutating methods carry `@CacheEvict(cacheNames = {"tiers","tiersDesc"})` — a benefit change
  must invalidate the cached tier list, since `GET /tiers` embeds each tier's benefits inline.

### `OrderService.java`
- **`placeOrder(userId, value)`**: saves an `OrderRecord`, then calls
  `tierEvaluationService.reevaluateSafely(userId)` — this single call is the entire "order
  activity can promote or demote a tier" mechanism; nothing else triggers it besides this and
  order cancellation.
- **`cancelOrder(orderId, callerUserId)`** → **`markCancelledAndClearOverride`**: loads the order,
  checks ownership, sets `cancelled = true`, saves, then — if the user's subscription currently
  has `manualTierOverride = true` — clears it, since a cancelled order changing the qualifying
  picture is exactly the kind of event that should let automatic evaluation resume; finally calls
  `reevaluateSafely` again, so a cancellation can demote a tier the same way a new order can
  promote one.

### `CallerIdentityGuard.java`
- **`requireCaller(headerValue)`**: null/blank → `ForbiddenException`; otherwise
  `Long.parseLong(headerValue.trim())`, catching `NumberFormatException` into the same exception
  type — a non-numeric header is treated identically to a missing one.
- **`requireOwnership(Long caller, Long resourceOwnerId)`**: a single `.equals()` comparison —
  no DB access, used when the caller and owner id are both already in hand (e.g., after loading a
  subscription).
- **`requireCallerOwns(headerValue, resourceOwnerId)`**: composes the two above — the one method
  most controllers actually call.

### `AdminApiKeyGuard.java`
- **`verify(providedKey)`**: reads the configured key (`${membership.admin.api-key:}`, default
  empty) — if the configured key is empty, **every** request is rejected (closed by default, not
  "no key configured means no check"); otherwise a plain string comparison against the provided
  header value.

### `SubscriptionStateMachine.java`
- **`assertTransitionAllowed(current, target)`**: `if (!ALLOWED.getOrDefault(current,
  Set.of()).contains(target)) throw new InvalidTransitionException(...)`.
- **`assertTierChangeAllowed(current)`**: narrower helper — a tier can only change while
  `current == ACTIVE`, expressed as its own check rather than reusing the transition map (a tier
  change isn't a status *transition* at all, so it doesn't belong in the same map).

### `QualificationWindowResolver.java`
- **`resolve(criterion)`** / **`resolve(criterion, Instant now)`**: `ROLLING_DAYS` →
  `[now - N days, now]`; otherwise (the `CALENDAR_MONTH` default) → the current month's
  `[first-of-month 00:00 in the configured zone, first-of-next-month 00:00)`, computed by going
  through `ZonedDateTime` for the same reason `Plan.computeEndDate` does (calendar arithmetic
  requires zone/date awareness that raw `Instant` doesn't have).

---

## `web/` — controllers (thin; validation + guard calls + delegate to a service)

| Controller | Routes | What it adds beyond "call the service" |
|---|---|---|
| `SubscriptionController` | subscribe, changeTier, cancel, membership, tier-history, reconcile-tier | Resolves `X-User-Id` and calls the right `CallerIdentityGuard` method per route; `getTierHistory` additionally maps each `TierChangeAudit` row's tier ids to names via `PlanService.getTier` |
| `OrderController` | place/cancel order | Resolves the caller header; `placeOrder`'s response also looks up the user's *current* tier name via `SubscriptionRepository.findFirstByUserIdOrderByStartDateDesc` so the client doesn't need a second round trip |
| `CheckoutController` | checkout/benefits | Loads the membership, explicitly re-checks `isCurrentlyActive` (checkout must not honor a lapsed membership even though the tracking read shows it), then delegates to `BenefitService` |
| `ExclusiveDealController` | exclusive-deals | Same liveness re-check as `CheckoutController`, for the same reason |
| `PlanController` | plans, tiers | The two **unauthenticated** reads — no `CallerIdentityGuard` call at all, by design |
| `AdminPlanController` / `AdminBenefitController` | admin routes | No explicit guard call — coverage comes from `AdminApiKeyInterceptor` matching `/api/admin/**` |
| `UserController` | create/get user | Plain CRUD, no guard — user creation is the one operation that must be reachable by someone with no identity yet |
| `WebMvcConfiguration` | — (not a controller) | Registers `AdminApiKeyInterceptor` against `/api/admin/**` at startup — the mechanism that makes the admin boundary uniform without a guard call in every admin controller method |
| `AdminApiKeyInterceptor` | — (not a controller) | `preHandle` calls `AdminApiKeyGuard.verify` before any admin controller method runs; returning `true` lets the request continue only if `verify` didn't throw |

---

## `dto/` — request/response records (see `JAVA_CONCEPTS.md` Section 1 for *why* records)

Eight files, one per resource area (`SubscriptionDtos`, `OrderDtos`, `PlanDtos`, `CheckoutDtos`,
`ExclusiveDealDtos`, `UserDtos`, `AdminPlanDtos`, `AdminBenefitDtos`) — each a non-instantiable
holder class (private constructor) containing the request/response records for that resource.
None contain logic; `@NotNull`/bean-validation annotations on record components are the only
"behavior," enforced automatically by Spring's `@Valid` before a controller method body runs.

---

## `exception/` — the `ApiException` hierarchy

`ApiException` (abstract, `getStatus()` abstract) → `NotFoundException` (404),
`ConflictException` (409), `InvalidTransitionException` (422), `ForbiddenException` (403).
**`GlobalExceptionHandler`** has one `@ExceptionHandler(ApiException.class)` method that calls
`ex.getStatus()` polymorphically to build the response — plus separate handlers for
`MethodArgumentNotValidException` (bean-validation failures → 400) and `IllegalArgumentException`
(defensive catch-all for validation logic that throws the plain JDK type, e.g.
`AdminBenefitService.validate`).

---

## `config/`

### `TimeConfig.java`
One `@Bean Clock clock() { return Clock.systemUTC(); }` — the single place production `Instant.
now()`-equivalent behavior originates; every other class receives `Clock` through constructor
injection rather than calling a static method.

### `DataSeeder.java`
`implements CommandLineRunner` — runs once at startup, only if `planRepository.count() == 0`
(so restarting against a populated DB is a no-op, not a duplicate-seed error). Creates the 3
plans, 3 tiers with their criteria/benefits, 7 users (2 plain, 5 pre-positioned tier-strategy
scenarios), and — for the 5 scenario users — calls `tierEvaluationService.reevaluateSafely`
directly after constructing their orders, so they land on the correct tier *before* the app
finishes starting, with no client request required.

---

## `MembershipApplication.java`
`@SpringBootApplication @EnableCaching @EnableScheduling` — the three annotations that,
respectively, bootstrap component scanning/auto-configuration, activate `@Cacheable`/`@CacheEvict`
processing (`PlanService`, `AdminPlanService`, `AdminBenefitService`), and activate `@Scheduled`
processing (`TierReconciliationScheduler`). Without `@EnableCaching`/`@EnableScheduling` here,
those annotations elsewhere in the codebase would be inert — Spring only wires them up if the
application class explicitly opts in.

---

## `resources/` — schema and configuration

- **`db/migration/V1__init_schema.sql`** — the full baseline schema, hand-derived column-by-column
  from every entity above (see `SYSTEM_DESIGN.md` Section 6 for the constraint-by-constraint
  reasoning).
- **`db/migration/V2__tier_change_audit.sql`** — additive migration for the `tier_change_audit`
  table, added after `V1` was already applied — Flyway migrations are never edited post-hoc.
- **`application.yml`** — default (H2) profile: datasource, `ddl-auto: none`, `flyway.enabled:
  true`, cache/scheduling config, `membership.admin.api-key` (default empty → closed).
- **`application-postgres.yml`** — same shape, Postgres datasource, activated via
  `spring.profiles.active=postgres`.

---

## `test/` — one-line-each summary (full detail already in `SYSTEM_DESIGN.md`)

| File | What it proves |
|---|---|
| `TierEvaluatorTest` | Strategy resolution + highest-tier selection, in isolation |
| `SubscriptionStateMachineTest` | Every legal/illegal transition |
| `QualificationWindowResolverTest` | Calendar-month vs rolling-days window math |
| `CategoryOverridesGlobalDiscountPolicyTest` | No-stacking discount resolution, in isolation |
| `SubscriptionMutationTransactionsTest` | The stale-ACTIVE `changeTier` guard, and ownership-before-liveness ordering |
| `SubscriptionServiceDataIntegrityRaceTest` | The three-way `DataIntegrityViolationException` translation |
| `SubscriptionRepositoryPaginationTest` | The reconciliation sweep's pagination visits every user exactly once |
| `MembershipApiIntegrationTest` | Everything else, end-to-end over real HTTP, including genuine concurrent-request races |
