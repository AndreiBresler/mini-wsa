# Low-Level Design — Mini WSA

This document describes the internal design of the Mini WSA service: layering, request flow, threat scoring, sliding-window state, persistence, concurrency, and error handling. It complements the high-level architecture in the README.

## 1. Layering

```
api/              controllers, DTOs, exception handler — no business logic
domain/           records and enums — no Spring annotations except validation
ingestion/        Kafka producer (REST → topic) + consumer (topic → enrichment)
enrichment/       pure scoring + Redis-backed repeat-offender tracker + orchestrator
storage/          NamedParameterJdbcTemplate-based repository
stats/            aggregation queries and DTOs
config/           Kafka topic bean, ObjectMapper config, virtual-thread tuning
generator/        synthetic event generator (publishes to REST or Kafka)
```

**Dependency direction:** `api → ingestion + stats → enrichment + storage → domain`. The domain package has no inbound dependencies on any other package. This keeps domain objects portable and testable in isolation.

## 2. Request flow

### Ingestion path

```
HTTP POST /v1/events/ingest
   │
   ▼
IngestionController
   ├─ @Valid triggers SecurityEventBatchDeserializer (single-or-array)
   ├─ Bean Validation cascades into each event, into nested Rule
   └─ For each event: EventProducer.publish(event)
       │  KafkaTemplate.send(topic, clientIp, event)
       │  Returns immediately; producer flushes asynchronously
       ▼
Topic: security-events
   ├─ 3 partitions
   ├─ Keyed by clientIp → repeat-offender state for one IP lands on one partition
   └─ acks=all, enable.idempotence=true
       │
       ▼
EventConsumer (@KafkaListener, manual ack, concurrency=3)
   │  receivedAt = Instant.now()
   ├─ EnrichmentService.enrich(event, receivedAt)
   │   ├─ RepeatOffenderTracker.recordAndCheck(clientIp, eventTimestamp) → boolean
   │   ├─ ThreatScoreCalculator.calculate(event, isRepeatOffender) → int
   │   └─ returns EnrichedEvent
   ├─ EventRepository.upsert(enrichedEvent)   // INSERT … ON CONFLICT DO NOTHING
   └─ ack.acknowledge()                       // commit Kafka offset
```

### Query path

```
HTTP GET /v1/stats/summary           HTTP GET /v1/events/samples
        │                                    │
        ▼                                    ▼
StatsController                      SamplesController
        │                                    │
        ▼                                    ▼
StatsService                         SampleService
        │                                    │
        ▼                                    ▼
StatsRepository (aggregations)       EventRepository (paginated filter)
        │                                    │
        └────────────┬───────────────────────┘
                     ▼
                Postgres (events table)
```

## 3. Domain model

All domain types are immutable Java records:

| Type | Role |
|---|---|
| `SecurityEvent` | Raw DLR received over REST or from Kafka |
| `EnrichedEvent` | `SecurityEvent` + `receivedAt` + `attackType` + `threatScore` — what we persist |
| `Rule` | Nested in `SecurityEvent`: id, name, message, severity, category |
| `GeoLocation` | Nested: country, city |
| `Severity` | Enum with point values (CRITICAL=40, HIGH=30, MEDIUM=20, LOW=10) |
| `Action` | Enum with point values (DENY=20, ALERT=10, MONITOR=0) |
| `Category` | Enum with `attackType()` string (INJECTION → "SQL/Command Injection", etc) |

**Why records:** immutability for free, value-based equality, exhaustive deconstruction in pattern matching, no Lombok dependency.

**Why enums carry point values:** the scoring rule "CRITICAL = 40" is fundamentally a property of CRITICAL, not of a separate scoring service. Putting it on the enum keeps the data model self-describing and makes the calculator trivial.

## 4. Threat score calculation

```
score = severity.points()
      + action.points()
      + (path contains "/admin" OR "/login" ? 15 : 0)
      + (isRepeatOffender ? 15 : 0)

clamp to [0, 100]
```

**Maximum achievable: 90.** (CRITICAL=40 + DENY=20 + sensitive path=15 + repeat=15.) The 100 cap is defensive — if scoring rules ever expand, the cap remains correct without touching call sites.

**Repeat-offender threshold: > 5 events from the same `clientIp` in the last 10 minutes.** Configurable via `miniwsa.enrichment.repeat-offender-threshold` and `miniwsa.enrichment.repeat-offender-window-seconds`.

`ThreatScoreCalculator` is a pure static method — no Spring dependencies, no I/O. The repeat-offender boolean is injected so the calculator stays unit-testable without mocking Redis. Side effects live in `RepeatOffenderTracker`; orchestration lives in `EnrichmentService`.

## 5. Repeat-offender mechanism (Redis sliding window)

**Key:** `wsa:ip:{clientIp}` — one sorted set per IP.

**ZSET layout:**
- Member: a fresh UUID per event (we never need to look up individual members)
- Score: event timestamp in epoch milliseconds

**On each event:**

```
1. ZREMRANGEBYSCORE wsa:ip:{ip} -inf (now_ms - window_ms)   # age out old
2. ZADD               wsa:ip:{ip} now_ms <uuid>             # record this event
3. EXPIRE             wsa:ip:{ip} (window_seconds + GRACE)  # janitor
4. ZCARD              wsa:ip:{ip}                            # current count
```

Repeat offender ⟺ ZCARD > threshold.

**Why sorted set instead of a counter or list:**
- A counter (INCR) can't slide — it would accumulate forever or need an explicit reset.
- A list (LPUSH + LTRIM) trims by position, not by timestamp; bursty IPs would be pruned too aggressively.
- A ZSET supports O(log N + M) range delete by score, which is exactly the sliding-window semantic.

**Why a UUID member:** ZSET members must be unique. If two events arrive in the same millisecond, identical members would collide and ZADD would treat the second as an update, undercounting.

**Why TTL = window + GRACE (60s):** The sliding-window logic comes from `ZREMRANGEBYSCORE`, not the TTL — even with TTL = 0 (no expire), the math would still work because every new event prunes the old. The TTL exists only so that quiet IPs don't leak keys. The +60s grace ensures the key doesn't expire mid-pipeline at the exact moment a slow event reaches the call.

**Failure mode:** the tracker wraps Redis ops in try/catch. On failure: log a warning, return `false`. The event still gets enriched and persisted; only the repeat-offender bonus is lost. The system degrades gracefully — Redis is an optimization, not a hard dependency.

## 6. Persistence

### Schema (events table)

Single flat table managed by Flyway (`V1__create_events_table.sql`). Nested JSON (`rule`, `geoLocation`) is flattened into prefixed columns for index-friendliness.

### ORM layer

JPA via Spring Data, Hibernate underneath. The boundary is deliberate:

- `EventEntity` — JPA `@Entity`, mutable, getters/setters explicit (no Lombok per AGENT.md)
- `EventJpaRepository extends JpaRepository<EventEntity, Long>` — CRUD + `existsByEventId`
- `EventRepository` (facade) — translates between `EnrichedEvent` (immutable domain record) and `EventEntity`; carries `@Transactional`
- `StatsJpaRepository` — JPQL `@Query` aggregations returning record projections via constructor expressions
- `StatsRepository` (facade) — maps projections to response DTOs; rounds avg threat score to 2 decimals in Java (kept out of the query so JPQL stays portable)

Hibernate runs in **`ddl-auto: validate` mode** — Flyway owns schema changes; Hibernate only checks that the entity maps to the migration. This keeps the source of truth (migrations) explicit.

### Index strategy

| Index | Serves |
|---|---|
| `PRIMARY KEY (id)` | row identity |
| `UNIQUE (event_id)` | idempotency on consumer redelivery |
| `(config_id, timestamp DESC)` | stats time-range queries scoped to a config |
| `(timestamp DESC)` | stats queries with `configId` omitted |
| `(client_ip, timestamp DESC)` | top-attackers aggregation |
| `(rule_category)` | `byCategory` aggregation |
| `(action)` | `byAction` aggregation |

### Idempotent upsert

The authoritative idempotency guarantee is the `UNIQUE(event_id)` constraint in `events`. Code in `EventRepository.upsert`:

```
try {
    jpa.save(toEntity(event));
    return true;
} catch (DataIntegrityViolationException duplicate) {
    return false;
}
```

Three layers reinforce the guarantee:

1. **Kafka partitioning by `clientIp`.** Same client → same partition → same consumer thread → sequential processing. The realistic redelivery case (consumer crashes between save and ack) is therefore not a race.
2. **Manual ack only after `save()` returns.** Throw before ack → Kafka redelivers → second attempt hits the unique constraint → returns `false`.
3. **`DataIntegrityViolationException` catch.** Handles the (rare, operator-error) case where the same `eventId` is published with two different `clientIp`s and lands on two partitions concurrently. The DB catches it; this catch keeps it from logging as an error.

No `@Transactional` on `upsert`: Spring Data's `JpaRepository.save` opens its own transaction, so a unique-violation rollback is local to that call and doesn't poison a wider transactional scope (which would otherwise trigger `UnexpectedRollbackException` at commit).

### Batch insert

The consumer can be configured for batch listening (`List<SecurityEvent>` per poll). Batch inserts via `JdbcTemplate.batchUpdate` are 5-10× faster at high volume.

v0.2 starts with single-record listening for simpler control flow and clearer error handling. Will revisit if generator-driven load testing shows the consumer falling behind.

## 7. Concurrency model

### HTTP side

`spring.threads.virtual.enabled=true` enables JDK 21 virtual threads for Tomcat workers and Spring's task executor. Each request runs on a virtual thread; blocking on `KafkaTemplate.send(...).get()` does not pin an OS thread, so we can handle very high concurrent ingestion without bloating the carrier-thread pool.

### Kafka consumer side

```yaml
spring.kafka.listener.concurrency: 3
```

3 consumer threads, one per partition. Because we key by `clientIp`, all state for one IP (the Redis sorted set) is touched by exactly one consumer thread, so there is no cross-thread contention on the hot path even without locking.

### Why not @Async on JDBC / Redis calls

JDBC is a synchronous protocol. Wrapping `jdbcTemplate.update(...)` in `@Async` shifts the block from the consumer thread to a thread-pool thread — same number of total threads blocked, plus the overhead of pool scheduling and context loss (MDC, security, tracing).

With virtual threads on, blocking I/O is effectively free at the OS-thread level. Going to genuinely non-blocking I/O (R2DBC + Lettuce reactive + WebFlux) is a different architecture and not warranted for this workload — at OLTP scale, Hikari + JDBC outperforms R2DBC because connection pooling beats per-call connection acquisition.

## 8. Idempotency end-to-end

Three independent layers:

| Layer | Mechanism |
|---|---|
| Producer | `enable.idempotence=true` — no duplicate sends within a session |
| Consumer | `ack-mode: manual` — commit offset only after DB write succeeds |
| Database | `ON CONFLICT (event_id) DO NOTHING` — survives consumer crash mid-batch |

This combination tolerates a consumer crash between DB write and offset commit: on restart, Kafka redelivers, DB upsert is a no-op, the redelivered offset commits cleanly.

## 9. Error handling

### REST layer (`GlobalExceptionHandler`)

| Exception | HTTP status | Body |
|---|---|---|
| `MethodArgumentNotValidException` | 400 | `{error, details: [{field, message}, ...]}` |
| `HttpMessageNotReadableException` (caused by `InvalidFormatException` — unknown enum) | 400 | `{error, details: [{field, message}]}` |
| Any other unhandled | 500 | default Spring error response |

### Producer

Kafka send failures bubble up to the controller and produce 500. v0.3 will wrap with a timeout and translate to 503 with `Retry-After` so clients know it's transient.

### Consumer

| Failure | Behavior |
|---|---|
| Enrichment exception | log + skip + ack (don't block partition); future: DLQ topic |
| Redis exception | swallowed inside `RepeatOffenderTracker`, treated as non-repeat |
| DB exception | do NOT ack, redeliver on next poll; bounded retries via `DefaultErrorHandler` |
| Validation exception on consume | shouldn't happen — already validated at REST; if it does, ack-and-skip |

## 10. Stats endpoint internals (v0.3)

### `GET /v1/stats/summary`

Four indexed aggregations, run as separate queries for clarity and per-query tunability:

```sql
-- totalEvents
SELECT count(*) FROM events
 WHERE timestamp BETWEEN :from AND :to
   AND (:configId IS NULL OR config_id = :configId);

-- byCategory: { category: { count, avgThreatScore } }
SELECT rule_category, count(*), avg(threat_score)
  FROM events
 WHERE timestamp BETWEEN :from AND :to
   AND (:configId IS NULL OR config_id = :configId)
 GROUP BY rule_category;

-- byAction: { action: count }
SELECT action, count(*)
  FROM events
 WHERE timestamp BETWEEN :from AND :to
   AND (:configId IS NULL OR config_id = :configId)
 GROUP BY action;

-- topAttackers: top 10 by event count
SELECT client_ip, count(*), avg(threat_score)
  FROM events
 WHERE timestamp BETWEEN :from AND :to
   AND (:configId IS NULL OR config_id = :configId)
 GROUP BY client_ip
 ORDER BY count(*) DESC
 LIMIT 10;

-- topTargetedPaths: top 10 by event count
SELECT path, count(*)
  FROM events
 WHERE timestamp BETWEEN :from AND :to
   AND (:configId IS NULL OR config_id = :configId)
   AND path IS NOT NULL
 GROUP BY path
 ORDER BY count(*) DESC
 LIMIT 10;
```

**Alternative considered:** a single CTE with all aggregations in one round-trip. Rejected because (a) the separate queries are independently tunable and EXPLAIN-able, (b) the slowest query dominates anyway, and (c) network round-trips inside the same JVM/DB host are sub-millisecond.

## 11. Time-series endpoint (v0.6, bonus)

`GET /v1/stats/timeseries?configId=&from=&to=&interval=1m|5m|1h`

```sql
SELECT date_trunc(:bucket_unit, timestamp) AS bucket, count(*) AS event_count
  FROM events
 WHERE timestamp BETWEEN :from AND :to
   AND (:configId IS NULL OR config_id = :configId)
 GROUP BY bucket
 ORDER BY bucket;
```

Empty buckets are filled in via a `LEFT JOIN` against `generate_series(:from, :to, :interval)`. The `:bucket_unit` is whitelisted in the service layer (`minute`, `hour`) — never interpolated from user input.

## 12. Configuration surface

| Property | Default | Purpose |
|---|---|---|
| `miniwsa.kafka.topic` | `security-events` | Topic name |
| `miniwsa.enrichment.repeat-offender-window-seconds` | 600 | Sliding window |
| `miniwsa.enrichment.repeat-offender-threshold` | 5 | Triggers the +15 bonus when exceeded |
| `spring.threads.virtual.enabled` | true | JDK 21 virtual threads for Tomcat |
| `spring.kafka.listener.concurrency` | 3 | Consumer threads per listener container |

All overridable via environment variables (Spring's relaxed binding).
