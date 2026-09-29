# Bitcoin Payment Feature — Design & Scaling Analysis

Status: proposed
Owner: platform
Grounded in: Bluebox production telemetry captured 2026-09-29 (see PR description for evidence)

## 1. Context

EasyTrade wants to accept Bitcoin as a payment method. The natural home is
`credit-card-order-service`, which already owns the payment/order lifecycle
(order creation → status polling → third-party fulfilment). This document covers
the production stress analysis, a target architecture that survives 100x current
traffic, the existing issues that must be fixed alongside the feature, and the
scope of the first implementation increment.

## 2. Current production reality (from Bluebox)

| Signal | Value (24h, healthy period) |
|---|---|
| Throughput | ~380 req/hr (~0.1 req/s), 97% on the status-poll read |
| Latency | p50 ~30 ms, p95/p99 ~67 ms |
| CPU | ~2% of limit |
| Memory | ~27 MB of a 213 MB limit (~13% — the tightest resource) |
| SLOs | none defined |
| Infra telemetry on the SQL DB | none (blind spot) |

Dependencies of `credit-card-order-service`:
- **feature-flag-service** — called ~once per request, ~1 ms, ample headroom.
- **third-party-service** (`/v1/manufacturer`) — low volume, fulfilment callback.
- **SQL Server** (`CreditCardOrders`, `CreditCardOrderStatus`, `CreditCards`) —
  ~8.3k calls/24h, accessed via raw JDBC `DriverManager` with **no connection pool**.
  No CPU/memory/connection telemetry exists.

## 3. Components under the most stress (task 1)

Ranked by where a Bitcoin payment feature adds load, and where 100x breaks first:

1. **SQL Server + the JDBC access pattern (highest risk).** `DatabaseHelper` opens a
   fresh `DriverManager.getConnection()` per operation — no pooling. At 100x this is
   the first hard wall: connection churn, pool/handle exhaustion, and lock contention
   on the order/status tables. The tier also has zero infra monitoring, so today we are
   blind to its real ceiling. A payment flow adds write-heavy rows (payment intents,
   confirmations) and, for Bitcoin, repeated status reads while awaiting on-chain
   confirmations — amplifying both reads and writes.
2. **The JVM heap of `credit-card-order-service`.** 213 MB is small and already ~13%
   used at trivial load. Payment payloads, polling, and any in-process retry/cache
   push memory/GC first — before CPU (~2%).
3. **A new outbound dependency: the Bitcoin gateway / blockchain confirmation source.**
   Bitcoin confirmations are slow (minutes) and bursty. If handled synchronously in the
   request path, threads block and the service falls over long before 100x. This must
   be asynchronous.
4. **feature-flag-service** — lowest risk; comfortable headroom and already on the
   per-request path.

## 4. Target architecture for 100x traffic (task 2)

Design principle: **the request path never blocks on Bitcoin.** A payment is *recorded*
synchronously (fast, small DB write) and *confirmed* asynchronously.

```
                                    ┌──────────────────────────┐
  client ──POST /v1/payments/bitcoin──▶  credit-card-order-svc  │
                                    │  (validate + persist      │
                                    │   PENDING intent, return  │
                                    │   202 + paymentId)        │
                                    └────────────┬─────────────┘
                                                 │ enqueue confirmation job
                                                 ▼
                                    ┌──────────────────────────┐
                                    │   message queue (rabbitmq │
                                    │   already in the stack)   │
                                    └────────────┬─────────────┘
                                                 ▼
                                    ┌──────────────────────────┐
                                    │  bitcoin-confirmation     │
                                    │  worker (scales           │
                                    │  independently)           │
                                    │  ← polls/receives webhook │
                                    │    from BTC gateway       │
                                    └────────────┬─────────────┘
                                                 ▼
                                    updates payment intent → CONFIRMED / FAILED
  client ──GET /v1/payments/{id}──▶ reads current intent state (fast)
```

Scaling levers, mapped to the stress points above:

| Stress point | 100x mitigation |
|---|---|
| DB connection churn | Introduce a pooled `DataSource` (HikariCP) sized to the service; stop opening a raw connection per call. Add read replicas for the status-poll read path if reads dominate. |
| JVM heap | Raise heap/limits; keep payment objects small and short-lived; no in-request blocking work. Horizontal scale (HPA) on the stateless API. |
| Bitcoin confirmation latency | Move confirmation off the request path onto a queue + dedicated worker that scales on queue depth. API returns `202 Accepted` immediately. |
| Status polling amplification | Cache the latest payment state; prefer webhook/event push over client polling; add short-TTL caching for `GET /v1/payments/{id}`. |
| DB observability blind spot | Add infra + connection-pool telemetry before load testing (prerequisite, not optional). |

Rollout: gate the whole feature behind a `bitcoin_payment` feature flag (matches how
this codebase already gates behaviour via feature-flag-service), default **off**, and
enable progressively. Load-test against the real path — do **not** extrapolate linearly
from 0.1 req/s.

## 5. Existing issues to fix alongside this feature (task 3)

These are blockers/risks surfaced by production telemetry and code review:

1. **BLOCKER — active outage.** `GET /v1/orders/{accountId}/status/latest` is failing
   100% in prod (divide-by-zero behind the `credit_card_meltdown` flag). Fixed in code
   in PR #1 but not merged/deployed; flag still on. Ship that fix and restore the 0%
   baseline **before** enabling any new feature.
2. **No DB connection pooling.** Raw `DriverManager.getConnection()` per operation will
   not survive 100x. Migrate to a pooled `DataSource`.
3. **No SLOs and a monitoring blind spot on the DB tier.** A 100% failure ran for ~3h
   with no Davis problem raised. Define availability/latency SLOs for the service and
   add infra/connection telemetry for SQL Server before load testing.
4. **Trace-propagation gap.** The dominant endpoint arrives with no parent span, so the
   caller is unattributed. Fix header propagation so the payment path is traceable
   end to end.

## 6. Scope of this PR (first increment)

Because production is in an active outage and the full async/worker/pooling build is a
multi-service effort, this PR delivers a **safe, flag-gated first increment** inside
`credit-card-order-service`, plus this design doc:

- `POST /v1/payments/bitcoin` — validates the request, records a **PENDING** payment
  intent, returns `202 Accepted` with a `paymentId`. Never blocks on the chain.
- `GET /v1/payments/{paymentId}` — returns the current intent state.
- Gated behind the `bitcoin_payment` feature flag, **default off**, using the existing
  OpenFeature client — so it is dark until deliberately enabled.
- Follows existing patterns (`@RestController`, `StandardResponse`, records, JDBC helper).

Explicitly **out of scope** here (tracked as follow-ups, see §4/§5): the queue + worker,
HikariCP pooling migration, HPA/infra changes, SLOs, and the real BTC gateway integration.
Confirmation state transitions are stubbed behind the same intent model so the async
worker can drive them later without an API change.
