# FirstClub Membership — Demo Script

A step-by-step walkthrough, mapped directly to the assignment's four numbered requirements. Every
curl below uses concrete IDs from a **fresh** in-memory H2 boot (`DataSeeder` only seeds once, on
an empty database). If you've restarted the app more than once against the same DB file, or IDs
otherwise look different, re-run the two "confirm IDs" calls in Part 0 and substitute — nothing
else in this script depends on IDs being exactly these numbers, only on being *consistent* with
what your instance actually returns.

Every command assumes the app is running on `localhost:8080`. Install [`jq`](https://jqlang.github.io/jq/)
for readable output, or drop ` | jq` from any command to see raw JSON.

---

## Part 0 — Start the app, confirm seeded IDs

```bash
# Terminal 1: start with an admin key set (needed for Part 5's admin calls)
MEMBERSHIP_ADMIN_API_KEY='demo-secret' mvn spring-boot:run
```

```bash
# Terminal 2, once it's up:
curl -s localhost:8080/api/plans | jq
curl -s localhost:8080/api/tiers | jq
```

**Expect:** 3 plans (Monthly ₹199, Quarterly ₹499, Yearly ₹1499) and 3 tiers (Silver, Gold,
Platinum), each `id` a small sequential integer. On a fresh boot these are:

| Plans | id | | Tiers | id |
|---|---|---|---|---|
| Monthly | 1 | | Silver | 1 |
| Quarterly | 2 | | Gold | 2 |
| Yearly | 3 | | Platinum | 3 |

This script uses `monthlyPlanId=1`, `silverTierId=1`, `goldTierId=2`, `platinumTierId=3`
throughout — swap in whatever your two commands above actually returned if different.

**Seeded users** (also created once, at these ids on a fresh boot):

| id | name | cohort | Has a subscription already? |
|---|---|---|---|
| 1 | Amrit Raj | — | No — used fresh in Parts 2 & 3 |
| 2 | Priya VIP | VIP | No — used fresh in Parts 2 & 4 |
| 3 | Seed Gold Count | — | Yes, pre-evaluated to **GOLD** via order *count* |
| 4 | Seed Gold Value | — | Yes, pre-evaluated to **GOLD** via order *value* |
| 5 | Seed Platinum VIP | VIP | Yes, pre-evaluated to **PLATINUM** via *cohort* |
| 6 | Seed Platinum Rolling | — | Yes, pre-evaluated to **PLATINUM** via *rolling-30-day* value |
| 7 | Seed Calendar Boundary | — | Yes, stays **SILVER** (proves calendar-month default) |

Users 3–7 exist specifically so Part 4 (tier strategy) is inspectable with zero setup, immediately
after boot — you don't have to place 11 orders live to prove the strategy pattern works; that's
what the live demo in Part 4b is *for*, on top of the already-seeded proof, not instead of it.

---

## Part 1 — Requirement 1: Membership Plans

> *"Users can choose from Monthly, Quarterly, and Yearly membership plans. Each plan comes with
> specific pricing."*

```bash
curl -s localhost:8080/api/plans | jq
```

**Check:** three entries, `planType` one of `MONTHLY`/`QUARTERLY`/`YEARLY`, each with its own
`price` and `currency`. This is the full "get membership plans" list — nothing else to combine it
with.

Plan pricing is also **versioned**, not just static — that's demonstrated together with the admin
API in **Part 5a**, since it needs an existing subscriber to show grandfathering against.

---

## Part 2 — Requirement 2: Membership Benefits (configurable)

> *"Free delivery, extra X% discount, exclusive deals/early access, priority support... each tier
> unlocks additional perks — should be configurable."*

### 2a. See what each tier currently grants

```bash
curl -s localhost:8080/api/tiers | jq
```

**Check:** each tier's `benefits` array. On a fresh boot:
- **Silver**: 2% off `ALL`
- **Gold**: `FREE_DELIVERY` + 5% off `ALL`
- **Platinum**: `FREE_DELIVERY`, `EXPEDITED_DELIVERY` (1 day), 10% off `ALL`, 15% off `Electronics`,
  a 20% `EXCLUSIVE_DEAL` on `Beauty`, `EARLY_ACCESS` (7 days), and `PRIORITY_SUPPORT` — the full
  perk stack the spec's example list names (higher discounts, faster delivery, exclusive coupons),
  plus the two entitlement types the spec calls out as optional.

### 2b. Subscribe a user to Platinum and see the benefits actually apply

```bash
RESPONSE=$(curl -s -X POST localhost:8080/api/subscriptions \
  -H "Content-Type: application/json" -H "X-User-Id: 1" \
  -d '{"userId": 1, "planId": 1, "tierId": 3}')
echo "$RESPONSE" | jq
SUB=$(echo "$RESPONSE" | jq -r '.subscriptionId')
```

**Check:** `status: "ACTIVE"`, `tierName: "PLATINUM"`. `$SUB` now holds the subscription id used
throughout the rest of Part 2/3 — captured automatically, not something you need to retype.

```bash
# Discount-stacking proof: Electronics has its OWN rate (15%) that must NOT stack with the
# 10% ALL rate. Groceries has no specific rate, so it falls back to the 10% ALL rate.
curl -s -X POST localhost:8080/api/users/1/checkout/benefits \
  -H "Content-Type: application/json" -H "X-User-Id: 1" \
  -d '{"items": [{"category": "Electronics", "price": 1000}, {"category": "Groceries", "price": 500}]}' | jq
```

**Check:** `totalDiscount` is exactly **200.00** (150 from Electronics @15% + 50 from Groceries
@10%) — **not 300.00**, which is what it would be if the two rates stacked. This is the
`CategoryOverridesGlobalDiscountPolicy` behaviour. `freeDelivery: true` and `prioritySupport: true`
should both be set; `appliedBenefits` will also list `EXPEDITED_DELIVERY` and `EARLY_ACCESS` with
their `configuredValue`s (1 and 7 respectively).

```bash
curl -s localhost:8080/api/users/1/exclusive-deals -H "X-User-Id: 1" | jq
```

**Check:** one entry, `category: "Beauty"`, `discountPercent: 20`.

### 2c. Prove it's *configurable*, not hardcoded — add a new perk at runtime

```bash
# Denied without the admin key - closed by default.
curl -s localhost:8080/api/admin/tiers/3/benefits | jq
```
**Check:** `403`.

```bash
RESPONSE=$(curl -s -X POST localhost:8080/api/admin/tiers/3/benefits \
  -H "X-Admin-Api-Key: demo-secret" -H "Content-Type: application/json" \
  -d '{"benefitType":"EXCLUSIVE_DEAL","paramValue":25,"scope":"Books"}')
echo "$RESPONSE" | jq
BENEFIT_ID=$(echo "$RESPONSE" | jq -r '.id')
```
**Check:** a new benefit row. `$BENEFIT_ID` now holds its id.

```bash
# Same user, same subscription, NEW category with NO code deploy in between.
curl -s -X POST localhost:8080/api/users/1/checkout/benefits \
  -H "Content-Type: application/json" -H "X-User-Id: 1" \
  -d '{"items": [{"category": "Books", "price": 1000}]}' | jq
```
**Check:** `totalDiscount: 250.00` (25% of 1000) — the new perk applied immediately, no restart.

```bash
# Update it in place — still no restart.
curl -s -X PATCH localhost:8080/api/admin/benefits/$BENEFIT_ID \
  -H "X-Admin-Api-Key: demo-secret" -H "Content-Type: application/json" \
  -d '{"benefitType":"EXCLUSIVE_DEAL","paramValue":30,"scope":"Books"}' | jq
```
**Check:** `paramValue: 30`. Re-run the Books checkout call above — `totalDiscount` is now
`300.00`.

---

## Part 3 — Requirement 3: User Actions

> *"Get plans/tiers; subscribe to a plan+tier; upgrade/downgrade/cancel; track membership and
> expiry."*

"Get plans and tiers" was Parts 1 and 2a. The rest, using Amrit (user 1) and `$SUB` from Part 2b:

### 3a. Track current membership

```bash
curl -s localhost:8080/api/users/1/membership -H "X-User-Id: 1" | jq
```
**Check:** `subscriptionId` matches `$SUB`, `status: "ACTIVE"`, `tierName: "PLATINUM"`,
`daysRemaining` a positive number, `endDate` roughly a month out (Monthly plan).

### 3b. Upgrade/downgrade — manual tier change

```bash
curl -s -X PATCH localhost:8080/api/subscriptions/$SUB/tier \
  -H "Content-Type: application/json" -H "X-User-Id: 1" -d '{"newTierId": 1}' | jq
```
**Check:** `tierName: "SILVER"`, `tierSource: "USER_SELECTED"` — an explicit downgrade, tracked as
user-driven, not confused with an automatic one (see Part 4's `tierSource` distinction).

### 3c. Cancel, and the illegal-transition guard

```bash
curl -s -X DELETE localhost:8080/api/subscriptions/$SUB -H "X-User-Id: 1" | jq
```
**Check:** `status: "CANCELLED"`.

```bash
# Cancelling an already-cancelled subscription is a real business-rule violation, not a
# malformed request - 422, not 400 or a generic 500.
curl -s -o /dev/null -w "%{http_code}\n" -X DELETE localhost:8080/api/subscriptions/$SUB \
  -H "X-User-Id: 1"
```
**Check:** `422`.

### 3d. Cancelling frees the slot — re-subscribe

```bash
curl -s -X POST localhost:8080/api/subscriptions \
  -H "Content-Type: application/json" -H "X-User-Id: 1" \
  -d '{"userId": 1, "planId": 1, "tierId": 1}' | jq
```
**Check:** `201`, a **new** `subscriptionId` (different from `$SUB`).

### 3e. Error paths worth showing deliberately, not by accident

```bash
# Can't hold two active subscriptions at once.
curl -s -o /dev/null -w "%{http_code}\n" -X POST localhost:8080/api/subscriptions \
  -H "Content-Type: application/json" -H "X-User-Id: 1" \
  -d '{"userId": 1, "planId": 1, "tierId": 2}'
```
**Check:** `409`.

```bash
# Unknown user - 404, not a 500 or an empty 200.
curl -s -o /dev/null -w "%{http_code}\n" localhost:8080/api/users/999999/membership \
  -H "X-User-Id: 999999"
```
**Check:** `404`.

---

## Part 4 — Requirement 4: Membership Tiers (the strategy pattern)

> *"Users move through tiers based on: number of orders > X, total order value in a month, or
> cohort membership."*

### 4a. The three strategies, already proven at boot (users 3–7)

```bash
curl -s localhost:8080/api/users/3/membership -H "X-User-Id: 3" | jq '.tierName, .tierSource'
```
**Check:** `"GOLD"`, `"SYSTEM_PROMOTED"` — 11 orders of ₹100 crossed Gold's `MIN_ORDER_COUNT > 10`.

```bash
curl -s localhost:8080/api/users/4/membership -H "X-User-Id: 4" | jq '.tierName, .tierSource'
```
**Check:** `"GOLD"` — one ₹6,000 order crossed Gold's `MIN_ORDER_VALUE > 5000` instead (count was
only 1; this user qualifies via the *other* strategy, proving they're independent, OR-combined
criteria — see `Tier.criteriaMatchMode`).

```bash
curl -s localhost:8080/api/users/5/membership -H "X-User-Id: 5" | jq '.tierName'
```
**Check:** `"PLATINUM"` — **zero orders**. User 5's cohort is `VIP`, and Platinum's `COHORT`
criterion alone is enough (`ANY` match mode). This is the third strategy.

```bash
curl -s localhost:8080/api/users/6/membership -H "X-User-Id: 6" | jq '.tierName'
```
**Check:** `"PLATINUM"` — one ₹16,000 order placed 5 days ago, inside Platinum's *rolling 30-day*
value window (`MIN_ORDER_VALUE > 15000`), not the calendar-month window Gold uses. Proves windowed
qualification is per-criterion configuration, not a single global rule.

```bash
curl -s localhost:8080/api/users/7/membership -H "X-User-Id: 7" | jq '.tierName'
```
**Check:** `"SILVER"` — 11 orders, same count as user 3, but placed in the *previous* calendar
month. Gold's default window is the current calendar month, so they don't count. This is the
negative case: proves the window boundary is real, not just present in name.

```bash
curl -s localhost:8080/api/tiers | jq '.[] | {name, criteria}'
```
**Check:** Gold's criteria show `windowType: "CALENDAR_MONTH"`; Platinum's value criterion shows
`windowType: "ROLLING_DAYS", rollingWindowDays: 30`, and its cohort criterion shows
`cohortName: "VIP"`.

### 4b. Live promotion — watch it happen, not just inspect the result

A fresh, **non-cohort** user is used here on purpose — Priya (user 2) is `VIP`-cohort, and
Platinum's `COHORT` criterion is satisfied for her with **zero orders**. Since tier evaluation
always resolves to the single *highest currently-qualifying* tier, not a step-by-step climb, her
very first order would jump her straight to Platinum, skipping Gold entirely, no matter how many
`₹100` orders follow. That's not a bug — it's the correct behaviour of an `ANY`-mode, OR-combined
criteria set — but it means she's the wrong subject for a "watch count-based Gold promotion
happen" demo. This section needs a user for whom Gold is genuinely the highest tier order activity
alone can reach; Part 4b-bonus below uses Priya deliberately, for a different, equally real point.

```bash
RESPONSE=$(curl -s -X POST localhost:8080/api/users \
  -H "Content-Type: application/json" \
  -d '{"name": "Live Promotion Demo", "email": "live-promo@example.com"}')
echo "$RESPONSE" | jq
LIVEUSER=$(echo "$RESPONSE" | jq -r '.id')  # no cohort
```

```bash
RESPONSE=$(curl -s -X POST localhost:8080/api/subscriptions \
  -H "Content-Type: application/json" -H "X-User-Id: $LIVEUSER" \
  -d "{\"userId\": $LIVEUSER, \"planId\": 1, \"tierId\": 1}")
echo "$RESPONSE" | jq
SUB2=$(echo "$RESPONSE" | jq -r '.subscriptionId')
```
**Check:** `tierName: "SILVER"`.

```bash
for i in $(seq 1 11); do
  curl -s -X POST localhost:8080/api/users/$LIVEUSER/orders \
    -H "Content-Type: application/json" -H "X-User-Id: $LIVEUSER" -d '{"value": 100}' > /dev/null
done
curl -s localhost:8080/api/users/$LIVEUSER/membership -H "X-User-Id: $LIVEUSER" | jq '.tierName, .tierSource'
```
**Check:** `"GOLD"`, `"SYSTEM_PROMOTED"` — promoted live, mid-demo, by the same evaluator that
already ran once at boot for users 3–7. This user has no cohort and order value (₹1,100 total) is
nowhere near Platinum's ₹15,000 threshold, so Gold, and only Gold, is reachable here — the clean
count-based case Priya couldn't demonstrate.

```bash
# Manual override sticks immediately and is NOT clobbered by the evaluator...
curl -s -X PATCH localhost:8080/api/subscriptions/$SUB2/tier \
  -H "Content-Type: application/json" -H "X-User-Id: $LIVEUSER" -d '{"newTierId": 1}' | jq '.tierName, .tierSource'
```
**Check:** `"SILVER"`, `"USER_SELECTED"`.

```bash
# ...until the NEXT order re-enters automatic evaluation.
curl -s -X POST localhost:8080/api/users/$LIVEUSER/orders \
  -H "Content-Type: application/json" -H "X-User-Id: $LIVEUSER" -d '{"value": 50}' | jq '.currentTier, .tierChanged'
```
**Check:** `"GOLD"`, `true`.

### 4b-bonus. Cohort short-circuits the staircase (Priya, user 2)

This is the actual, confirmed behaviour if you run it — worth showing deliberately, not as a
correction. It demonstrates something 4b can't: two independent criteria (order count, cohort)
interacting correctly through `ANY`/OR match mode.

```bash
RESPONSE=$(curl -s -X POST localhost:8080/api/subscriptions \
  -H "Content-Type: application/json" -H "X-User-Id: 2" \
  -d '{"userId": 2, "planId": 1, "tierId": 1}')
echo "$RESPONSE" | jq
SUB_PRIYA=$(echo "$RESPONSE" | jq -r '.subscriptionId')
```
**Check:** `tierName: "SILVER"` — subscribing doesn't itself trigger evaluation; only an order or
a reconciliation sweep does.

```bash
curl -s -X POST localhost:8080/api/users/2/orders \
  -H "Content-Type: application/json" -H "X-User-Id: 2" -d '{"value": 100}' | jq '.currentTier'
```
**Check:** `"PLATINUM"` — **on the first order**, not the eleventh. Her cohort alone already
satisfied Platinum's criterion; Gold is never touched, because the evaluator picks the single
highest qualifying tier, not the first one it finds.

```bash
curl -s -X PATCH localhost:8080/api/subscriptions/$SUB_PRIYA/tier \
  -H "Content-Type: application/json" -H "X-User-Id: 2" -d '{"newTierId": 1}' | jq '.tierName'
curl -s -X POST localhost:8080/api/users/2/orders \
  -H "Content-Type: application/json" -H "X-User-Id: 2" -d '{"value": 50}' | jq '.currentTier'
```
**Check:** `"SILVER"`, then `"PLATINUM"` again — the override/reassert cycle from 4b holds at
whatever tier is actually reachable, not just Gold specifically.

### 4c. The audit trail — every change just made, in order

```bash
curl -s localhost:8080/api/users/$LIVEUSER/tier-history -H "X-User-Id: $LIVEUSER" | jq
```
**Check:** 4 entries in order: initial `SILVER` (`USER_SELECTED`, `previousTierName: null`) →
`GOLD` (`SYSTEM_PROMOTED`) → `SILVER` (`USER_SELECTED`) → `GOLD` (`SYSTEM_PROMOTED`).

```bash
curl -s localhost:8080/api/users/2/tier-history -H "X-User-Id: 2" | jq
```
**Check:** the same 4-event shape, but `PLATINUM` in place of every `GOLD` above — same mechanism,
different reachable ceiling. This is `TierChangeAudit`: a full "why is this user on this tier, and
when did that happen" answer straight from a query, for either user.

### 4d. Manual reconciliation on demand

```bash
curl -s -X POST localhost:8080/api/users/$LIVEUSER/reconcile-tier -H "X-User-Id: $LIVEUSER" | jq
```
**Check:** `tierChanged: false` — nothing to change right now; this is the same idempotent sweep
`TierReconciliationScheduler` runs hourly, invoked on demand instead of waiting an hour to show it
in a live setting.

---

## Part 5 — Admin-driven changes (plans, tiers, subscriptions)

You already did tier/benefit admin changes in **Part 2c**. This section covers **plan pricing**
specifically, since it needs an existing subscriber to demonstrate grandfathering against.

### 5a. Plan price change — versioned, not mutated in place

```bash
# A brand-new subscriber, to compare against later.
RESPONSE=$(curl -s -X POST localhost:8080/api/users \
  -H "Content-Type: application/json" \
  -d '{"name": "Price Witness", "email": "price-witness@example.com"}')
echo "$RESPONSE" | jq
WITNESS=$(echo "$RESPONSE" | jq -r '.id')
```

```bash
curl -s -X POST localhost:8080/api/subscriptions \
  -H "Content-Type: application/json" -H "X-User-Id: $WITNESS" \
  -d "{\"userId\": $WITNESS, \"planId\": 1, \"tierId\": 1}" | jq '.planPriceVersion, .price'
```
**Check:** `1`, `199.00` — bought at the original price/version.

```bash
curl -s -X PATCH localhost:8080/api/admin/plans/1/price \
  -H "X-Admin-Api-Key: demo-secret" -H "Content-Type: application/json" \
  -d '{"price":249.00,"currency":"INR"}' | jq
```
**Check:** `version: 2`, `price: 249.00`.

```bash
# The EXISTING subscriber is grandfathered - still shows the OLD price/version.
curl -s localhost:8080/api/users/$WITNESS/membership -H "X-User-Id: $WITNESS" | jq '.planPriceVersion, .price'
```
**Check:** still `1`, `199.00`.

```bash
# A NEW subscriber gets the NEW price.
RESPONSE=$(curl -s -X POST localhost:8080/api/users \
  -H "Content-Type: application/json" \
  -d '{"name": "New Price Subscriber", "email": "new-price@example.com"}')
echo "$RESPONSE" | jq
NEWSUB=$(echo "$RESPONSE" | jq -r '.id')
curl -s -X POST localhost:8080/api/subscriptions \
  -H "Content-Type: application/json" -H "X-User-Id: $NEWSUB" \
  -d "{\"userId\": $NEWSUB, \"planId\": 1, \"tierId\": 1}" | jq '.planPriceVersion, .price'
```
**Check:** `2`, `249.00`.

### 5b. Admin changes to a subscription indirectly — via order cancellation

There's no "admin edits a subscription directly" endpoint by design (see the README's
"deliberately NOT implemented" section on configuration scope) — but order cancellation is a real,
admin/ops-triggered mutation that reaches into tier evaluation. This needs its own user, not
`$LIVEUSER` from Part 4b: that user ends the demo with 12 total orders, and cancelling just one of
those still leaves 11 — still `>10`, still Gold, no visible change. The threshold is a strict
count, so the cancellation has to be the thing that crosses it, deliberately:

```bash
RESPONSE=$(curl -s -X POST localhost:8080/api/users \
  -H "Content-Type: application/json" \
  -d '{"name": "Demotion Demo", "email": "demotion-demo@example.com"}')
echo "$RESPONSE" | jq
DEMOTEUSER=$(echo "$RESPONSE" | jq -r '.id')
curl -s -X POST localhost:8080/api/subscriptions \
  -H "Content-Type: application/json" -H "X-User-Id: $DEMOTEUSER" \
  -d "{\"userId\": $DEMOTEUSER, \"planId\": 1, \"tierId\": 1}" | jq
```

```bash
# Exactly 11 orders - the minimum that crosses ">10" - capturing the LAST order's id.
ORDER=""
for i in $(seq 1 11); do
  ORDER=$(curl -s -X POST localhost:8080/api/users/$DEMOTEUSER/orders \
    -H "Content-Type: application/json" -H "X-User-Id: $DEMOTEUSER" -d '{"value": 100}' \
    | jq -r '.order.orderId')
done
curl -s localhost:8080/api/users/$DEMOTEUSER/membership -H "X-User-Id: $DEMOTEUSER" | jq '.tierName'
```
**Check:** `"GOLD"`. `$ORDER` now holds the 11th order's id.

```bash
curl -s -X POST localhost:8080/api/orders/$ORDER/cancel -H "X-User-Id: $DEMOTEUSER" | jq
curl -s localhost:8080/api/users/$DEMOTEUSER/membership -H "X-User-Id: $DEMOTEUSER" | jq '.tierName'
```
**Check:** `"SILVER"` — cancelling the one order that was load-bearing for the `>10` threshold
(11 → 10, and 10 is not `>10`) demotes automatically. Cancelled orders are excluded from every
count/value window, not just newly-placed ones — this is the same exclusion rule, run in reverse.

---

## Part 6 — Bonus: concurrency (the assignment's stated bonus)

```bash
RESPONSE=$(curl -s -X POST localhost:8080/api/users \
  -H "Content-Type: application/json" \
  -d '{"name": "Race Test", "email": "race@example.com"}')
echo "$RESPONSE" | jq
RACE=$(echo "$RESPONSE" | jq -r '.id')
curl -s -X POST localhost:8080/api/subscriptions -H "Content-Type: application/json" -H "X-User-Id: $RACE" \
  -d "{\"userId\": $RACE, \"planId\": 1, \"tierId\": 1}" -w "\nHTTP %{http_code}\n" &
curl -s -X POST localhost:8080/api/subscriptions -H "Content-Type: application/json" -H "X-User-Id: $RACE" \
  -d "{\"userId\": $RACE, \"planId\": 1, \"tierId\": 2}" -w "\nHTTP %{http_code}\n" &
wait
```
**Check:** exactly one `201`, exactly one `409` — never two `201`s, regardless of which one wins.
The automated version of this exact race (8 concurrent requests, not 2) is
`MembershipApiIntegrationTest.concurrentSubscribeAttempts_onlyOneSucceeds`.

```bash
# Idempotency: identical request, identical key, twice. A FRESH user, deliberately - by this
# point in the script user 1 already has an active subscription (from Part 3d), and a new
# Idempotency-Key doesn't bypass the "already has an active subscription" rule for a user who
# genuinely already has one - idempotency only replays a request that used the SAME key before.
# Reusing user 1 here would 409 on the very first call, not demonstrate a replay.
RESPONSE=$(curl -s -X POST localhost:8080/api/users \
  -H "Content-Type: application/json" \
  -d '{"name": "Idempotency Demo", "email": "idempotency-demo@example.com"}')
echo "$RESPONSE" | jq
IDUSER=$(echo "$RESPONSE" | jq -r '.id')
curl -s -X POST localhost:8080/api/subscriptions \
  -H "Content-Type: application/json" -H "X-User-Id: $IDUSER" \
  -H "Idempotency-Key: demo-key-001" \
  -d "{\"userId\":$IDUSER, \"planId\":1, \"tierId\":1}" | jq '.subscriptionId'
curl -s -X POST localhost:8080/api/subscriptions \
  -H "Content-Type: application/json" -H "X-User-Id: $IDUSER" \
  -H "Idempotency-Key: demo-key-001" \
  -d "{\"userId\":$IDUSER, \"planId\":1, \"tierId\":1}" | jq '.subscriptionId'
```
**Check:** the same `subscriptionId` both times — a retry, not a duplicate.

---

## Part 7 — Bonus: the authorization boundary

```bash
# No header at all.
curl -s -o /dev/null -w "%{http_code}\n" -X POST localhost:8080/api/subscriptions \
  -H "Content-Type: application/json" -d '{"userId": 1, "planId": 1, "tierId": 1}'
```
**Check:** `403`.

```bash
# Right shape, wrong caller - asserting an identity that isn't the resource's owner.
curl -s -o /dev/null -w "%{http_code}\n" localhost:8080/api/users/1/membership -H "X-User-Id: 2"
```
**Check:** `403` — this is a plain header-vs-path-variable comparison in `getMembership` itself,
not a lookup against the subscription's actual owner, so it fires regardless of whether user 1 or
2 have subscriptions at all at this point in the script.

```bash
# The two catalog endpoints deliberately stay open - no user id to check ownership against.
curl -s -o /dev/null -w "%{http_code}\n" localhost:8080/api/plans
curl -s -o /dev/null -w "%{http_code}\n" localhost:8080/api/tiers
```
**Check:** `200`, `200` — no header needed, on purpose (see `CallerIdentityGuard`'s Javadoc).

---

## Part 8 — Inspecting data directly, not just through the API

```
http://localhost:8080/h2-console
JDBC URL: jdbc:h2:mem:membershipdb
User:     sa
Password: (empty)
```

Useful queries once connected:

```sql
-- Every subscription, current tier, and status at a glance
SELECT s.id, u.name, t.name AS tier, s.status, s.tier_source, s.end_date
FROM subscription s JOIN app_user u ON u.id = s.user_id JOIN tier t ON t.id = s.tier_id;

-- The full tier-change audit trail across every user, not just one at a time via the API
SELECT * FROM tier_change_audit ORDER BY user_id, changed_at;

-- Confirm the DB-level invariant Part 6 exercises: this must never return more than one row
-- per user_id, by construction (unique constraint), not by application discipline alone
SELECT user_id, COUNT(*) FROM active_membership_lock GROUP BY user_id HAVING COUNT(*) > 1;
```

The last query returning zero rows, always, regardless of what's been thrown at the app in Parts
3–6, is the actual proof of the concurrency design — not just the one race in Part 6, but every
race that could have happened during this entire script.

---

## Requirement coverage checklist

| # | Requirement | Demonstrated in |
|---|---|---|
| 1 | Monthly/Quarterly/Yearly plans, specific pricing | Part 1, Part 5a (versioning) |
| 2 | Free delivery, %-discount, exclusive deals/early access, priority support, configurable per tier | Part 2 |
| 3 | Get plans/tiers, subscribe, upgrade/downgrade/cancel, track membership + expiry | Parts 1, 2a, 3 |
| 4 | Tier movement via order count, order value (calendar + rolling), and cohort | Part 4 |
| bonus | Concurrency | Part 6, `MembershipApiIntegrationTest` |
