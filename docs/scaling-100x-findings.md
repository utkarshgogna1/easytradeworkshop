# 100x Peak Traffic — Breakage Analysis & Remediation

Status: in progress
Grounded in: Bluebox production telemetry, 2026-09-29 (24h window).

## Question

What would break first if peak traffic increased 100x, application-wide?

## What breaks first (ranked)

Current combined DB call volume is ~5.4 calls/s (24h avg); 100x ≈ 540 calls/s.

1. **Shared SQL Server (`ostroski-easytrade-db:1433`) — connection-pool exhaustion (highest risk).**
   A single shared instance serves at least 5 services. 24h call volume:

   | Service | Calls to DB / 24h | Avg latency |
   |---|---|---|
   | manager | 307,136 | 2.3 ms |
   | broker-service | 132,949 | 14.0 ms (slowest) |
   | loginservice | 17,765 | 1.7 ms |
   | credit-card-order-service | 8,183 | 1.7 ms |
   | contentcreator | 1,446 | 37.6 ms |

   At ~540 calls/s, app-side connection pools saturate before the DB becomes CPU- or
   lock-bound. Because the instance is shared, one service exhausting connections starves
   the others — a cross-service cascade. **Inferred, not measured:** there is no DB-tier
   telemetry in the environment (no connection/CPU/lock-wait metrics), so this is a
   monitoring blind spot as well as a bottleneck.

2. **feature-flag-service — CPU saturation.** Peak ~135 req/min; CPU ~4.3% avg / 5.8% peak
   of one core. A linear 100x pushes required CPU past a full core (400–600%+) long before
   heap (~17 MB of 50.7 MB) becomes a concern. CPU is the first wall.

3. **manager (.NET) — thread pool + DB pool.** Peak ~220 req/min (~3.7/s), ~100% of its
   downstream traffic to the shared DB, thin thread pool (~6 threads). At ~370/s the thread
   pool and DB connection pool fail first. No CPU/memory-limit telemetry exists for the .NET
   services (a gap).

## Remediation

### Code-fixable — done in this change

**credit-card-order-service DB connection pooling.** `DatabaseHelper` previously opened a
fresh `DriverManager.getConnection()` per operation and closed it — unbounded connection
churn, and a direct contributor to failure #1. Replaced with a bounded, reused **HikariCP**
pool:

- Single lazily-initialized `HikariDataSource`; `getConnection()` now draws from the pool.
- Connection string unchanged (`MSSQL_CONNECTIONSTRING`).
- Pool size/timeouts tunable via env vars (no code change to re-tune):
  `DB_POOL_MAX_SIZE` (default 10), `DB_POOL_MIN_IDLE` (2),
  `DB_POOL_CONNECTION_TIMEOUT_MS` (30000), `DB_POOL_IDLE_TIMEOUT_MS` (600000),
  `DB_POOL_MAX_LIFETIME_MS` (1800000).
- All existing `try (Connection conn = ...)` callers are unchanged; `close()` now returns
  the connection to the pool instead of tearing down a physical connection.

### Infrastructure / config changes required — FLAGGED, not code

These cannot be solved in this service's code and need platform/ops work:

1. **DB-tier observability.** Instrument SQL Server (DMVs / host metrics) for active
   connections, CPU, and lock waits. Today the database's own resource state is invisible —
   pool-exhaustion-vs-lock-contention above is inferred, not measured.
2. **Scale the shared database.** It is the shared choke point for ≥5 services. Options:
   larger instance, read replicas for read-heavy callers, or splitting per-service databases.
3. **feature-flag-service capacity.** CPU-bound at scale — needs horizontal autoscaling (HPA)
   and/or more CPU. Config/infra, not code.
4. **manager (.NET) tuning.** Raise thread-pool minimums and connection-pool sizing; add
   process/container CPU and memory metrics so its ceiling is measurable.
5. **Apply the same pooling pattern to the other JDBC services** (broker-service — already the
   slowest DB caller — loginservice, accountservice, contentcreator). This change pools only
   credit-card-order-service; the others still open per-call connections.
6. **Define SLOs.** No SLOs exist for any service, so there is no automated guardrail for a
   scale event.

## Note

Releasing new features remains gated on the active credit-card-order-service outage
(divide-by-zero behind `credit_card_meltdown`) — see the separate fix PR.
