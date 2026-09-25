# FirstClub Membership — System Design Deep-Dive

**Prepared for interview explanation.** Companion to the codebase at
`github.com/amritraj15/first-club-membership` — push the latest working tree before using this
document; see the note at the top of the conversation this was generated from if you're unsure
which state is current. Every class/table/endpoint named below is real, not illustrative.

---

## Table of contents

1. High-Level Design (HLD)
2. Low-Level Design (LLD)
3. Architecture
4. Design Strategy & Patterns
5. Trade-offs (explicit)
6. Databases
7. Idempotency (deep dive)
8. Concurrency (deep dive)
9. Multithreading (deep dive — and where the term does *not* apply, precisely)
10. Rapid-fire interview Q&A

---

# 1. High-Level Design (HLD)

## 1.1 Problem statement

A backend for a tiered membership program: users buy a **Plan** (Monthly/Quarterly/Yearly,
priced), which grants a **Tier** (Silver/Gold/Platinum, each with configurable perks). Tier
membership is driven by two independent forces — the user's explicit choice at subscribe time,
*and* the user's ongoing order behavior (count, value, cohort) — and both are legitimate, so the
system has to track which one last decided the current tier and reconcile between them.

## 1.2 System context

```
                         ┌─────────────────────────────┐
   End User / Client ───▶│                              │
                         │   FirstClub Membership API   │
   Admin / Ops Tooling ─▶│   (Spring Boot monolith)     │
                         │                              │
   Order-placing system ─▶ (simulated here by           │
   (external in reality)  │  POST /users/{id}/orders)    │
                         └──────────────┬───────────────┘
                                        │
                              ┌─────────▼─────────┐
                              │  PostgreSQL (prod) │
                              │  H2 (dev/test)     │
                              └────────────────────┘

   Entitlements this service DEFINES but does not itself enforce downstream:
   EARLY_ACCESS, PRIORITY_SUPPORT → would be consumed by a sales/support system
   that does not exist in this system's boundary (deliberately out of scope).
```

The system does **not** own catalog/checkout/order fulfillment — `OrderRecord` is a deliberately
minimal stand-in (value + timestamp + cancelled flag), the input *signal* tier evaluation reacts
to, not a real order subsystem.

## 1.3 High-level component view

```
┌───────────────────────────────────────────────────────────────────────┐
│  Web layer (Spring MVC controllers)                                    │
│  SubscriptionController · OrderController · PlanController ·           │
│  CheckoutController · ExclusiveDealController ·                        │
│  AdminPlanController · AdminBenefitController                          │
├───────────────────────────────────────────────────────────────────────┤
│  Cross-cutting                                                         │
│  CallerIdentityGuard (user-scoped auth boundary) ·                     │
│  AdminApiKeyInterceptor (admin boundary) ·                             │
│  GlobalExceptionHandler (ApiException → HTTP status) · Clock (DI'd)    │
├───────────────────────────────────────────────────────────────────────┤
│  Service / domain logic                                                │
│  SubscriptionService → SubscriptionMutationTransactions (mutations)    │
│  TierEvaluationService → TierReevaluationTransaction (evaluation)      │
│  TierEvaluator + TierCriteriaStrategy impls (count/value/cohort)       │
│  BenefitService + DiscountPolicy (checkout-time perk resolution)       │
│  PlanService (cached catalog reads) · OrderService · AdminBenefitService│
│  AdminPlanService · TierReconciliationScheduler (hourly safety net)    │
├───────────────────────────────────────────────────────────────────────┤
│  Persistence (Spring Data JPA repositories, one per aggregate)         │
├───────────────────────────────────────────────────────────────────────┤
│  PostgreSQL / H2, schema owned by Flyway (V1__init_schema.sql,         │
│  V2__tier_change_audit.sql)                                            │
└───────────────────────────────────────────────────────────────────────┘
```

## 1.4 Key use-case flows (narrative)

**Subscribe** — `POST /api/subscriptions`
`X-User-Id` verified → `PESSIMISTIC_WRITE` lock acquired on the user row → check for an existing
active subscription (expire it lazily if stale) → check idempotency key if given → create
`Subscription` + `ActiveMembershipLock` in the same transaction → release lock on commit.

**Order placement → tier reevaluation** — `POST /api/users/{id}/orders`
Record the order → **synchronously**, in a **new, separate transaction**
(`REQUIRES_NEW`), reevaluate the user's qualifying tier against all criteria → if the highest
currently-qualifying tier differs from the current one (either direction), update it and write a
`TierChangeAudit` row. A bug in evaluation cannot roll back or block the order write — it's a
separate transaction, failures are logged and picked up by the next trigger.

**Checkout benefit resolution** — `POST /api/users/{id}/checkout/benefits`
Load the user's current, currently-*active* (lazily-expiry-checked) subscription → resolve one
discount rate per cart item via `DiscountPolicy` (category-specific overrides ALL-scope, never
stacks) → collect non-discount benefits (`FREE_DELIVERY`, `EXPEDITED_DELIVERY`, `EARLY_ACCESS`,
`PRIORITY_SUPPORT`) → return a computed response. No DB write at all — pure read + compute.

**Admin plan-price change** — `PATCH /api/admin/plans/{id}/price`
Verified by API key → creates a **new** `PlanVersion` row (never mutates an existing one) →
existing subscribers keep referencing their originally-purchased version (grandfathered); new
subscribers get the new version.

## 1.5 Non-functional requirements and how they're met

| NFR | How |
|---|---|
| Consistency under concurrent writes | DB transactions + row locks + unique constraints (Section 8) |
| Safe retries | Persisted, DB-backed idempotency (Section 7) |
| Horizontal scalability of the app tier | App holds no in-memory mutable state; every coordination point is a DB row — see 8.4 |
| Extensibility (new tier criteria, new benefit types) | Strategy pattern + data-driven benefits — Section 4 |
| Auditability | `TierChangeAudit` (append-only, per change) |
| A defensible, minimal security boundary | `CallerIdentityGuard` + `AdminApiKeyInterceptor`, both deliberately scoped, both documented as *not* full authn |

---

# 2. Low-Level Design (LLD)

## 2.1 Domain model

```
User ──< Subscription >── Plan ──< PlanVersion
          │      │
          │      └── Tier ──< TierCriterion
          │            └──< TierBenefit
          │
          ├── ActiveMembershipLock   (1:1 via unique(user_id), plain FK-less Long columns)
          ├── SubscriptionIdempotency (1:1, unique(subscription_id), unique(user_id, idempotency_key))
          └── TierChangeAudit         (1:many, append-only)

User ──< OrderRecord
```

Key columns and why they exist:

| Table | Notable column(s) | Purpose |
|---|---|---|
| `subscription` | `version` (`@Version`) | Optimistic-lock token — Section 8.3 |
| `subscription` | `tier_source`, `manual_tier_override` | Distinguishes *who* last set the tier — user or evaluator |
| `subscription` | `plan_version_id` | Grandfathering — this row's purchased price, immutable |
| `active_membership_lock` | `UNIQUE(user_id)` | The actual, DB-enforced "one active subscription per user" invariant |
| `subscription_idempotency` | `UNIQUE(user_id, idempotency_key)` | Idempotent-replay invariant |
| `tier_change_audit` | no FKs (plain Longs) | Append-only log; matches the entity's own mapping — see 6.3 |

## 2.2 Class responsibilities (selected)

| Class | Responsibility |
|---|---|
| `SubscriptionMutationTransactions` | The *only* place that writes `Subscription`/`ActiveMembershipLock` rows for create/changeTier/cancel. Holds the pessimistic lock, the ownership guard, the expiry guard, the audit write. |
| `SubscriptionService` | Thin façade: optimistic-retry wrapper + `DataIntegrityViolationException` translation around the mutation calls. |
| `TierReevaluationTransaction` | The *only* place that changes a tier automatically. Runs in `REQUIRES_NEW`. Respects `manualTierOverride`. |
| `TierEvaluator` | Pure function: given a user's data + a tier list, returns the highest currently-qualifying tier. No I/O. |
| `TierCriteriaStrategy` (+ 3 impls) | One class per criterion type (count / value / cohort). New criterion = new class, zero changes elsewhere. |
| `DiscountPolicy` / `CategoryOverridesGlobalDiscountPolicy` | Pure function: category + benefit list → one rate. No stacking, ever. |
| `CallerIdentityGuard` | Header-vs-owner comparison. No DB I/O for the pure header-vs-path check; a DB load for the load-then-compare cases (`changeTier`/`cancel`/order-cancel). |
| `SubscriptionStateMachine` | `Map<SubscriptionStatus, Set<SubscriptionStatus>>` — the entire lifecycle contract in one data structure. |
| `TierReconciliationScheduler` | Hourly `@Scheduled` sweep, paginated, calls the same `TierEvaluationService.reevaluateSafely` the order-placement path calls. |
| `GlobalExceptionHandler` | `ApiException` (abstract, one `getStatus()` per subclass) → HTTP status. One `@ExceptionHandler` per exception family, not per endpoint. |

## 2.3 Sequence: `changeTier` (the most guarded single method in the codebase)

```
Controller: resolve X-User-Id header → CallerIdentityGuard.requireCaller() [403 if missing/invalid]
  ↓
SubscriptionService.changeTier(id, newTierId, callerUserId)
  ↓ (delegates through the retry wrapper)
SubscriptionMutationTransactions.changeTier   [@Transactional]
  1. load Subscription by id                         → 404 if absent
  2. CallerIdentityGuard.requireOwnership(caller, sub.user.id)
                                                       → 403 BEFORE any lifecycle info leaks
  3. StateMachine.assertTierChangeAllowed(sub.status)  → 422 if not ACTIVE
  4. sub.isCurrentlyActive(clock.instant())            → 422 if stale-ACTIVE-but-expired
  5. planService.getTier(newTierId)                    (only reached if 1-4 all pass)
  6. sub.setTier / setTierSource(USER_SELECTED) / setManualTierOverride(true)
  7. save(sub)
  8. TierChangeAuditRepository.save(new TierChangeAudit(...))
  ↓
On ObjectOptimisticLockingFailureException anywhere in 1-8: caught ONE level up, in
SubscriptionService, which retries the WHOLE transaction exactly once through the SAME bean
(a fresh transaction), then gives up with 409.
```

The ordering of steps 2-4 is deliberate and interview-relevant: **ownership before lifecycle
state**, so a non-owner learns nothing about a subscription's state as a side effect of a
rejected request.

## 2.4 API surface (grouped)

| Group | Endpoints |
|---|---|
| Catalog (public, no auth) | `GET /plans`, `GET /tiers` |
| Subscription lifecycle (guarded) | `POST /subscriptions`, `PATCH /subscriptions/{id}/tier`, `DELETE /subscriptions/{id}` |
| Tracking (guarded) | `GET /users/{id}/membership`, `GET /users/{id}/tier-history` |
| Orders (guarded) | `POST /users/{id}/orders`, `POST /orders/{id}/cancel` |
| Benefits at checkout (guarded) | `POST /users/{id}/checkout/benefits`, `GET /users/{id}/exclusive-deals` |
| Reconciliation (guarded) | `POST /users/{id}/reconcile-tier` |
| Admin (separate API-key boundary) | `PATCH /admin/plans/{id}/price`, `GET/POST /admin/tiers/{id}/benefits`, `PATCH /admin/benefits/{id}` |

## 2.5 Exception → HTTP mapping

| Exception | Status | When |
|---|---|---|
| `NotFoundException` | 404 | Unknown user/subscription/order |
| `ConflictException` | 409 | Already-active subscription, idempotency-key param mismatch, translated `DataIntegrityViolationException` |
| `InvalidTransitionException` | 422 | Illegal lifecycle transition, or stale-ACTIVE-but-expired on `changeTier` |
| `ForbiddenException` | 403 | Missing/invalid caller header, cross-user access, bad admin key |
| `MethodArgumentNotValidException` | 400 | Bean-validation failure on request body |

422 vs 400 is a deliberate distinction: **400 means the request itself is malformed; 422 means a
well-formed request violates a business rule.** Cancel-twice is well-formed and wrong, not
malformed — 422.

---

# 3. Architecture

## 3.1 Layered, not hexagonal/onion

Controller → Service → Repository → DB, straight through. No ports-and-adapters ceremony, because
there is exactly one delivery mechanism (REST) and one persistence mechanism (JPA/SQL) — an
abstraction with a single implementation is a cost with no payoff. This is a stated trade-off, not
an oversight (Section 5).

## 3.2 Why a monolith, not microservices

A membership/tier/benefit domain is one consistency boundary: a subscription, its lock row, and
its idempotency row are written in **one transaction**. Splitting this into services would mean
either distributed transactions (2PC, avoided industry-wide for good reason) or an eventual-
consistency saga — solving a problem this domain doesn't have, at a cost (network hops, partial-
failure handling, operational surface) this domain doesn't need to pay. A monolith with a shared
Postgres is the correct scope for "tiered membership backend," and stays correct up to a
multi-instance, single-database deployment (Section 8.4) — which is exactly what's built.

## 3.3 Tech stack and why

| Choice | Why |
|---|---|
| Spring Boot 3 / Java 17 | Mainstream, batteries-included DI + transaction management + MVC |
| Spring Data JPA / Hibernate | Repository boilerplate elimination; `@Version` optimistic locking and `@Lock(PESSIMISTIC_WRITE)` are first-class, not hand-rolled SQL |
| H2 (dev/test), PostgreSQL (prod) | Same schema (Flyway-owned, portable SQL), fast local iteration without sacrificing the real target DB's semantics |
| Flyway | Versioned, auditable schema evolution — replaces Hibernate `ddl-auto` (Section 6.2) |
| No message broker / outbox | Tier reevaluation is synchronous and cheap enough (one bounded query per criterion window) that async infrastructure would be solving a scale problem this system doesn't have yet |

## 3.4 Deployment view

```
        ┌── App instance A ──┐
Clients │                    ├──▶ Shared PostgreSQL  (single source of coordination truth)
        └── App instance B ──┘
```

The app tier is **stateless** — no in-memory session, no local cache of mutable data (the plan/
tier catalog cache is read-only and admin-evicted, Section 4.4). Adding instance C changes
nothing about correctness, because every invariant that matters (one active subscription, one
idempotency replay, one price version) is enforced by the database, not by any one JVM's memory.

## 3.5 Cross-cutting concerns

- **`CallerIdentityGuard`** — a `@Component`, not a filter/interceptor, invoked explicitly per
  controller method. Deliberately not a blanket interceptor (unlike the admin guard) because the
  two public catalog reads must stay uncovered, and an interceptor pattern-matching URL paths to
  exclude them is more fragile than an explicit call in every method that needs it.
- **`AdminApiKeyInterceptor`** — the opposite choice, correctly: every `/admin/**` route should be
  covered uniformly, so a `HandlerInterceptor` is the right tool — new admin endpoints inherit the
  boundary automatically, by construction.
- **`Clock`** injected everywhere time is read — enables deterministic unit tests (`Clock.fixed`)
  and is the one place a real deployment would ever need to reason about timezone (`Asia/Kolkata`
  default for qualification windows, configurable).

---

# 4. Design Strategy & Patterns

## 4.1 Strategy — tier-promotion criteria

`TierCriteriaStrategy` interface, three implementations (`MinOrderCountStrategy`,
`MinOrderValueStrategy`, `CohortStrategy`), resolved by `TierCriteriaStrategyResolver` keyed on
`CriteriaType`. This is the **one** place a textbook GoF pattern is used, deliberately, because
the spec explicitly names three heterogeneous criteria and says "based on criteria **like**" —
strongly implying more will come. Adding a fourth criterion is one new class + one enum value; the
evaluator, the tiers, the admin API, and every existing test are untouched.

## 4.2 Policy — discount resolution

`DiscountPolicy` interface, one implementation (`CategoryOverridesGlobalDiscountPolicy`). Resolves
to **exactly one rate per cart item**: a category-specific `PERCENTAGE_DISCOUNT` wins if present,
otherwise the `ALL`-scope rate applies, never both. This is the fix for a real bug class (10% +
15% silently becoming 25%) turned into a structural impossibility rather than a runtime check.

## 4.3 State-machine-via-map, not full GoF State

`SubscriptionStateMachine` is a `Map<SubscriptionStatus, Set<SubscriptionStatus>>` plus two
assertion methods. Three states, simple transition rules — a full State-pattern class hierarchy
(one class per state, polymorphic `next()`) would be more ceremony for the same correctness, and
harder to review in one sitting. **This is a deliberate under-engineering choice**, stated as such.

## 4.4 Benefits as data, not a class hierarchy

`TierBenefit` rows (`type` + `paramValue` + `scope`), not one Java class per perk. A protected
admin API can add/update rows and evict the read cache — "configurable" means data changes, not
deployments. `BenefitType` is still a closed enum (not a free-text string), so `BenefitService`'s
checkout-time `switch` stays **exhaustive** — adding `EXPEDITED_DELIVERY` required one new `case`,
and the compiler enforced that no existing case could be silently skipped.

## 4.5 Versioned immutable snapshots — `PlanVersion`

A price change creates a new `PlanVersion` row; existing subscriptions keep referencing the
version they purchased. This is the append-only pattern applied narrowly to solve one concrete
problem (grandfathering), not adopted as the storage model for the whole domain (contrast with
4.6 and the explicit non-adoption of full event sourcing in Section 5).

## 4.6 Append-only audit log — `TierChangeAudit`

Every tier change (initial assignment, manual override, automatic promotion/demotion) writes one
row: previous tier, new tier, source, timestamp — in the **same transaction** as the change
itself, so a row can never exist without the change it describes or vice versa. Explicitly **not**
full event sourcing: `Subscription`'s current state is still a normal mutable row Hibernate
manages directly, not something replayed from these events. This is the calibrated middle ground
between "no history at all" and "rearchitect persistence around an event log."

## 4.7 Guard pattern for authorization boundaries

Two guards, two different mechanisms, deliberately:
- `CallerIdentityGuard` — explicit per-method call, because coverage needs to be *selective*
  (catalog reads excluded).
- `AdminApiKeyInterceptor` — blanket interceptor, because coverage needs to be *uniform*.

Using the wrong mechanism for either would either leak the catalog behind auth it doesn't need, or
create a real risk of a new admin endpoint forgetting to add the guard call.

## 4.8 Dependency-injected `Clock`

Every time-dependent decision — order placement, expiry checks, qualification windows, audit
timestamps, seed data — reads from one injected `java.time.Clock` bean, never `Instant.now()`
directly. This is what makes `Clock.fixed(...)` unit tests (Section 9) possible at all, and is the
single business-time source of truth in production.

---

# 5. Trade-offs (explicit)

| Decision | Alternative considered | Why this one |
|---|---|---|
| Enum + transition map for lifecycle | Full GoF State pattern | 3 states, simple rules — the map is equally correct and far more reviewable |
| DB-backed idempotency table | In-memory/Redis idempotency cache | Survives restarts; correct across multiple app instances sharing one DB without a second coordination system |
| Pessimistic row lock + unique constraint (layered) | Unique constraint alone | A constraint alone still lets two transactions both pass an app-level "already active?" check before either commits — the lock closes that window; the constraint remains the backstop if the lock is ever bypassed |
| Synchronous, same-request tier reevaluation (own transaction) | Async via message queue/outbox | Evaluation is cheap (bounded query per window); a broker solves a scale problem not yet present, at real operational cost |
| Lightweight `TierChangeAudit` | Full event sourcing | Answers "why is this user on this tier" without rearchitecting `Subscription`'s persistence model or every read path |
| `CallerIdentityGuard` (self-asserted header) | Full OAuth/session/identity provider | Closes the accidental/casual cross-user hole for an exercise with no login flow in scope; explicitly documented as *not* stopping a determined attacker |
| Flyway `ddl-auto: none` | `ddl-auto: validate` | `validate` is the more correct pairing, but requires confirming an exact column-by-column match against a live schema export, which wasn't possible to verify in the authoring environment — chose the option whose failure mode is "silently permissive" over one whose failure mode is "app won't start on a hidden mismatch" |
| Narrow admin API (benefit rows + plan price only) | Broad configuration CRUD for tiers/criteria | Tier topology and qualification rules are material policy decisions, not operational tuning — deliberately harder to change than a benefit's discount percentage |
| `OrderRecord` as a minimal stand-in | A real order/catalog subsystem | Out of scope — this service only needs orders as a *signal* for tier evaluation |

---

# 6. Databases

## 6.1 H2 for dev/test, PostgreSQL for shared/multi-server production

Same schema, same Flyway migrations, portable SQL (no vendor-specific syntax) — the only
difference is the `spring.profiles.active=postgres` datasource config. This means "it worked in
H2" and "it works in Postgres" are the same claim here, not two separate ones to verify.

## 6.2 Schema ownership: Flyway, not Hibernate `ddl-auto`

`V1__init_schema.sql` (baseline) + `V2__tier_change_audit.sql` (additive — **migrations already
applied are never edited**, a new one is always added; editing `V1` after it's run breaks its
stored checksum for anyone who already migrated). `ddl-auto: none` — Hibernate never touches
schema at runtime. This closes a real class of bug: multiple app-server instances doing
auto-DDL against a shared database on startup is itself an uncoordinated-write race, exactly the
kind of bug the rest of this design (Section 8) is careful about everywhere else.

## 6.3 Table-by-table (why each constraint exists, not just what it is)

| Table | Key constraint | Why |
|---|---|---|
| `app_user` | `UNIQUE(email)` | One account per email |
| `plan_version` | `UNIQUE(plan_id, version_number)` | Each plan's version sequence is independent and gapless |
| `subscription` | FKs to `app_user`, `plan`, `tier`, `plan_version` | Referential integrity for the one row that ties everything together |
| `active_membership_lock` | `UNIQUE(user_id)` | **The** invariant — see Section 8.2 |
| `subscription_idempotency` | `UNIQUE(user_id, idempotency_key)`, `UNIQUE(subscription_id)` | Idempotent replay + one idempotency record per subscription |
| `tier_change_audit` | no FKs | Deliberately matches the entity's own JPA mapping (plain `Long` fields, not `@ManyToOne` relations) — an audit log is written once and read back in bulk, not navigated from; adding FKs here would be a constraint the entity doesn't actually declare |

## 6.4 Why relational, not a document/NoSQL store

The domain's hard requirements are exactly what an RDBMS is for: **strict uniqueness invariants**
(one active subscription per user, one idempotency record per key), **multi-row atomicity**
(subscription + lock + idempotency in one transaction), and **row-level locking primitives**
(`PESSIMISTIC_WRITE`). A document store would require reimplementing all three at the application
level with weaker guarantees.

---

# 7. Idempotency — deep dive

## 7.1 The problem

A client's `POST /api/subscriptions` can time out or get retried by a proxy/load balancer without
the client knowing whether the first attempt succeeded. Without protection, a retry creates a
second subscription (or fails confusingly against the "already active" check).

## 7.2 Design

`Idempotency-Key` header (optional) → persisted in `subscription_idempotency`, tied to
`(user_id, idempotency_key)` uniquely, alongside the resulting `subscription_id`. **Not** an
in-memory cache (Section 5) — a real table, in the same transaction as subscription creation.

## 7.3 The four paths through `SubscriptionService.subscribe`'s catch block

```
createSubscription() throws DataIntegrityViolationException
        │
        ├─ key present, resolves to an existing SubscriptionIdempotency record
        │     └─ same plan/tier params?  → replay: return the original Subscription
        │     └─ different params?       → 409 (key reused for a different logical request)
        │
        ├─ key absent/blank, OR present but resolves to no record
        │     └─ ActiveMembershipLock row exists for this user?
        │            → 409 (the KNOWN cause: the pessimistic lock should make this
        │              unreachable in practice, but "should" isn't "is" — translated to
        │              the same 409 the lock-check path already returns, rather than an
        │              unhandled 500; GlobalExceptionHandler has no handler for this
        │              exception type on purpose)
        │
        └─ neither of the above
              └─ re-thrown raw — a genuinely unrecognized violation should surface loudly,
                 not be guessed at as a business-logic response
```

This is the kind of logic that's easy to get subtly wrong under pressure in an interview
whiteboard — the key insight to state out loud: **idempotency-key matching and the
active-subscription invariant are two different constraints that can both throw the same
exception type, and the recovery code has to disambiguate which one actually fired.**

## 7.4 Why DB-backed, not Redis/cache

A cache introduces a second system that can disagree with the database (write to DB succeeds,
write to cache fails or races) and a second thing to keep consistent across app instances. A row
in the same transaction as the subscription it protects has neither problem — it commits or rolls
back atomically with the thing it's protecting.

---

# 8. Concurrency — deep dive

## 8.1 The core race

Two concurrent `POST /api/subscriptions` for the same user: an app-level check-then-insert
("query for an active subscription, insert if none") has a race window — both requests can pass
the check before either commits.

## 8.2 The layered defense (defense in depth, not just one mechanism)

```
1. Application fast-path check   (cheap, catches the common case, NOT sufficient alone)
2. PESSIMISTIC_WRITE lock on the user row, held for the whole transaction
      → a second concurrent transaction for the SAME user BLOCKS at the lock, not
        at the check — it only proceeds after the first commits or rolls back
3. UNIQUE(user_id) on active_membership_lock
      → the backstop: even if 1 and 2 were somehow bypassed, the database itself
        refuses a second row
```

Why not just #3 alone? Without the lock, two transactions can both pass step 1, both attempt
step 3's insert, and one gets a constraint-violation exception at commit time — correct, but as a
500-shaped surprise rather than a clean 409 (this is exactly the `DataIntegrityViolationException`
translation logic in Section 7.3). The lock turns a *reactive* exception-recovery path into a
*proactive* serialization — the second request simply waits its turn and sees accurate data.

Proven by `MembershipApiIntegrationTest.concurrentSubscribeAttempts_onlyOneSucceeds`: 8 real
concurrent HTTP requests, exactly one `201`, seven `409`s, every run.

## 8.3 Optimistic locking for `changeTier`/`cancel`, and the self-invocation trap

`Subscription.version` (`@Version`) — Hibernate increments it on every update and rejects a write
based on a stale read (`ObjectOptimisticLockingFailureException`). `SubscriptionService` catches
that, **retries once through a fresh transaction**, then gives up with 409.

**The trap, and why it's architecturally significant:** Spring's `@Transactional` is implemented
as an **AOP proxy** wrapping the bean. A method calling another method **on `this`** (the same
Java object) bypasses the proxy entirely — the call never goes through Spring's transaction
interceptor, so it silently runs with no transaction boundary at all. A naive retry
implementation (`this.changeTier(...)` called again after catching the optimistic-lock failure,
inside the same class) would compile fine, look correct in a code review, and **silently not
retry in a new transaction** — it would just continue in the same (already-failed) transactional
context, or run with no transactional semantics whatsoever.

The fix: the retry target lives in a **separate Spring bean** (`SubscriptionMutationTransactions`,
called *through* the injected proxy from `SubscriptionService`), so the retry genuinely is a new
proxy-mediated call, genuinely gets a new transaction. This is called out explicitly in the
Javadoc of every affected class — it's the single most easy-to-miss correctness trap in Spring
transaction management, and a strong signal to raise unprompted in an interview.

## 8.4 Why this works across multiple app-server instances, not just multiple threads in one JVM

The pessimistic lock is a **database** row lock (`SELECT ... FOR UPDATE`), not a JVM-level
`synchronized` block or in-memory mutex. Two application instances — different JVMs, different
machines — contending for the same user both go through the same physical database row, so the
same serialization applies regardless of which instance either request lands on. This is *why* the
design explicitly does **not** need Redis/ZooKeeper-style distributed locks (Section 5): the
database already **is** the single shared coordination point every instance talks to.

## 8.5 The reconciliation scheduler's concurrency story (and its stated limit)

`TierReconciliationScheduler` runs hourly, paginated (`membership.reconciliation.batch-size`,
default 200) rather than loading every active user into memory at once —
`SubscriptionRepositoryPaginationTest` proves the paged query visits every active user exactly
once with a stable `ORDER BY`, since unordered `DISTINCT` + offset/limit pagination has no
guaranteed row order across separate page fetches otherwise. **Stated limit, not hidden:** this
scheduler is not distributed-coordinated across multiple app instances — with N instances each
running their own hourly sweep, the same users get redundantly (but harmlessly, since evaluation
is idempotent) re-evaluated N times. Fixing that needs a distributed lock or leader election,
explicitly called out as unnecessary scope for this exercise, not an oversight.

---

# 9. Multithreading — deep dive (precision matters here)

This is the section most likely to get overclaimed in an interview, so the calibrated answer:

## 9.1 Where concurrency actually originates in this system

**Not** from application code spawning threads. Every request is handled by Tomcat's embedded
request-handling thread pool — one thread per in-flight HTTP request, managed entirely by the
servlet container. The "concurrency" this system defends against (Section 8) is multiple such
request threads — possibly across multiple JVMs — racing on shared database rows, not anything
the application explicitly threads itself.

## 9.2 Why the Spring service beans are thread-safe by construction

Every `@Service`/`@Component` here is a Spring **singleton** with **no mutable instance state** —
no instance fields written after construction, only injected, effectively-final collaborators
(other beans, the `Clock`). All request-specific data lives in **method-local variables** and the
**database row** being operated on. This means thread-safety isn't achieved through
`synchronized` blocks or manual locking in Java code at all — it's achieved by having nothing
Java-level to protect; the only mutable shared state is in the database, which is why Section 8's
locks live *there*, not in application memory.

## 9.3 The one place `java.util.concurrent` actually appears — and it's test code, not production

`MembershipApiIntegrationTest` uses `ExecutorService` + `CountDownLatch` +
`AtomicInteger`/`AtomicReference` to fire genuinely concurrent HTTP requests against the running
embedded server, specifically to exercise the race in Section 8.2 for real, not simulate it. This
is **test infrastructure**, deliberately kept out of production code — if asked "does this system
use multithreading," the precise answer is: *the application doesn't create threads itself; it
correctly handles concurrent access from the container's thread pool via database-level
coordination, and the test suite uses explicit `ExecutorService`s to prove that under real
concurrent load, not simulated sequential calls.*

## 9.4 The `@Scheduled` task's threading model

Spring's default `TaskScheduler` for `@Scheduled` methods is a **single-threaded** pool unless
explicitly reconfigured. `TierReconciliationScheduler`'s hourly sweep therefore runs on one thread;
its pagination (8.5) bounds memory, not wall-clock time — a very large active-user base would make
one sweep take proportionally longer, sequentially, on that one thread. This is a known,
documented scaling edge, not a hidden one.

## 9.5 What would actually change under real production load

- Tomcat's connection/thread pool sizing tuned against the database connection pool size (HikariCP
  default) — the two must be balanced, or the app-tier thread pool can out-race the DB pool and
  queue silently.
- The reconciliation sweep could move to a dedicated `Executor` with a bounded thread count if
  hourly wall-clock time became a real constraint — not needed yet given the pagination bound.
- Multi-instance scheduling coordination (8.5) would need a real answer — a DB-based leader
  election row, or moving the sweep to a single designated instance/cron job outside the app
  tier entirely.

---

# 10. Rapid-fire interview Q&A

**Q: Why not just use the unique constraint and skip the pessimistic lock?**
A constraint alone still lets two transactions both pass an app-level check before either commits
— you'd get a correct-but-500-shaped failure instead of a clean 409, and you'd be relying on
exception-recovery logic to distinguish which invariant actually fired (see Section 7.3). The
lock makes the common case never reach that recovery path at all.

**Q: What happens if two requests for *different* users hit `changeTier` at the same time?**
Nothing — they lock different rows, proceed independently, no contention. The pessimistic lock in
`createSubscription` is scoped to one user's row specifically for this reason.

**Q: Why does the retry for optimistic-lock conflicts live in a separate bean?**
Because Spring's `@Transactional` is an AOP proxy, and same-class ("self-invocation") calls bypass
the proxy — a same-class retry would silently run outside any new transaction boundary. See
Section 8.3 in full; this is the single best "I understand Spring internals, not just the
annotations" answer available in this codebase.

**Q: How would this scale to a much larger active-user base?**
The per-request paths (subscribe, checkout, changeTier) are already O(1) DB work regardless of
total user count. The one path that scales with active-user count is the reconciliation sweep —
already paginated and bounded in memory; the next step if wall-clock time became an issue would be
parallelizing pages across a small worker pool, or moving to aggregate/precomputed qualification
snapshots instead of per-user re-evaluation.

**Q: Why Flyway instead of `ddl-auto: update`?**
Auto-DDL from multiple app-server instances against a shared database on startup is itself an
uncoordinated-write race — exactly the class of bug the rest of this design is careful about
everywhere else. Flyway makes schema changes versioned, reviewable, and applied exactly once.

**Q: Why is `ddl-auto` `none` and not the stricter `validate`?**
`validate` is the more correct pairing in principle, but it requires confirming Hibernate's
expected schema matches Flyway's output column-by-column via a live export — a check this
particular build environment couldn't perform. `none` was the honest choice given that
constraint, with every functional invariant (PK/FK/unique) still enforced at the DB level and
exercised by the full integration suite.

**Q: Is `CallerIdentityGuard` real authentication?**
No, explicitly — it's a self-asserted header, not verified against a password/token/session. It
stops accidental or casual cross-user calls, not a determined attacker who sets an arbitrary
header. Documented as a stated scope boundary, not a security guarantee, with a clear statement
of what a production identity layer would replace.

**Q: Why does `TierChangeAudit` exist but not full event sourcing?**
Event sourcing means `Subscription`'s current state is *derived* from replaying events — a
materially different persistence architecture with real migration and read-path cost.
`TierChangeAudit` is additive: a log written alongside the normal mutable row, answering "why is
this user on this tier" without touching how the row itself is stored. Calibrated to the actual
requirement (a change history), not the largest architecture that could address it.
