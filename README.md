# Mini WSA — Security Analytics Pipeline

A minimal Akamai-style Web Security Analytics platform. Ingests security event records over REST, fans them through Kafka, enriches them (classification + threat score + repeat-offender check), persists to Postgres, and exposes analytics via REST.

## Architecture

```
REST clients ─┐
              ├─► POST /v1/events/ingest ─► Kafka (security-events, keyed by clientIp)
generator ────┘                                    │
                                                   ▼
                                      Kafka consumer
                                      ├─ classify (category → attackType)
                                      ├─ score (severity + action + path + repeat-offender)
                                      └─ persist (idempotent on event_id)
                                                   │
              ┌────────────────────────────────────┴────────────────┐
              ▼                                                     ▼
          Postgres                                                Redis
       (events table)                                  (sliding window per IP)
              ▲
              │
   GET /v1/stats/summary
   GET /v1/events/samples
```

REST publishes to Kafka and returns 201; a `@KafkaListener` drives enrichment and persistence on each partition. Kafka decouples ingest from processing so the consumer can crash, restart, and replay without losing events. Idempotency comes from a `UNIQUE(event_id)` constraint — redeliveries become harmless no-ops.

## Stack

Java 21 · Spring Boot 3.4 · Postgres 16 · Redis 7 · Kafka 3.9.1 (KRaft) · Flyway · Spring Data JPA (Criteria API) · Testcontainers

## Build & run

```bash
docker compose up --build       # full stack on http://localhost:8080
./mvnw test                     # 77 unit + integration tests
./e2e.sh                        # 27 end-to-end checks against the running stack
```

## API

| Method & path                  | Purpose                                                                 |
|--------------------------------|-------------------------------------------------------------------------|
| `POST /v1/events/ingest`       | Single event or array. Returns `201 {accepted, message}`.               |
| `GET  /v1/stats/summary`       | Counts, averages, top attackers, top paths. All filters optional.       |
| `GET  /v1/events/samples`      | Paginated event samples. `?category`, `?action`, `?limit`, `?offset`.   |
| `GET  /v1/dev/scenarios`       | List bundled traffic scenarios.                                         |
| `POST /v1/dev/generate`        | Run a scenario through the public ingest endpoint. Seed-reproducible.   |
| `GET  /actuator/health`        | Liveness.                                                               |

## Using the data generator

The generator lives under `/v1/dev` and is active only when the `dev` or `all` profile is on — so `docker compose up` exposes it for demos, the production `query`/`ingest`/`consumer` deployments do not.

### List available scenarios

```bash
curl http://localhost:8080/v1/dev/scenarios
```

### Generate events

```bash
# Minimal — pick a scenario, defaults for everything else
curl -X POST 'http://localhost:8080/v1/dev/generate?scenario=single-targeted-attack'

# Reproducible — same seed always produces identical events
curl -X POST 'http://localhost:8080/v1/dev/generate?scenario=multi-vector-incident&seed=42'

# Override the configId tagged onto every event (default 14227)
curl -X POST 'http://localhost:8080/v1/dev/generate?scenario=slow-burn&configId=99999&seed=1'

# Send as JSON-array batches via the batch ingest path
curl -X POST 'http://localhost:8080/v1/dev/generate?scenario=quiet-bot-day&batching=true&batchSize=20'
```

| Param | Default | Description |
|-------|---------|-------------|
| `scenario` | required | One of the names listed by `/v1/dev/scenarios` |
| `configId` | `14227` | Tagged onto every generated event |
| `seed` | random | Fix it for byte-identical replays |
| `batching` | `false` | `true` = POST events as JSON arrays |
| `batchSize` | `50` | Batch size when `batching=true` |

The generator does **not** insert into the database directly — it POSTs events to the public `/v1/events/ingest` endpoint, so every generated event takes the same path as real traffic (validation → Kafka → consumer → enrichment → Postgres → Redis). That's also what makes the generator useful as an end-to-end smoke test: a successful `generate` proves the whole pipeline works.

### Typical demo loop

```bash
curl -X POST 'http://localhost:8080/v1/dev/generate?scenario=multi-vector-incident&seed=42'
sleep 5   # let the Kafka consumer drain
curl 'http://localhost:8080/v1/stats/summary' | jq
curl 'http://localhost:8080/v1/events/samples?limit=10' | jq
```

### Editing or adding scenarios

Scenarios are defined in **`src/main/resources/generator-scenarios.yml`**. Schema:

```yaml
scenarios:
  - name: my-scenario                # required, unique
    description: "what this proves"  # required, shown in /v1/dev/scenarios
    count: 200                       # total events to generate
    windowHours: 6                   # spread events across the last N hours
    defaultBatching: false           # optional, default false
    defaultBatchSize: 50             # optional, used when defaultBatching=true
    waves: []                        # zero or more attack waves; see below
```

A **wave** injects clustered attack traffic on top of the baseline:

```yaml
waves:
  - size: 15                         # number of events in the wave
    durationSeconds: 30              # spread the wave across N seconds
    category: INJECTION              # INJECTION | XSS | DATA_LEAKAGE | BOT | DOS | RATE_LIMIT | PROTOCOL_VIOLATION
    targetPath: /api/v1/login        # optional — pins the wave to one path so topTargetedPaths picks it up
    severityProfile: ESCALATING      # LOW | MEDIUM | HIGH | CRITICAL | MIXED | ESCALATING
```

After editing, restart the app (`docker compose restart app`) — scenarios are loaded once at startup. The four bundled scenarios (`quiet-bot-day`, `single-targeted-attack`, `multi-vector-incident`, `slow-burn`) cover the spec's stated invariants and the edge cases (repeat-offender threshold, slow-rate-just-inside-window).

## Design decisions

### ORM-only persistence — no SQL or JPQL strings
Every query is either a Spring Data derived method, a JPA `Specification` (filtered lists), or built via the Criteria API (aggregations). **Why:** string queries fail silently when a field is renamed; Criteria expresses the query graph in compiler-checked Java. Field names are still string literals (`root.get("ruleCategory")`) because the JPA static metamodel isn't generated — `hibernate-jpamodelgen` would close that gap.

### Idempotent ingest under at-least-once delivery
Kafka redelivers on consumer crash. We rely on `UNIQUE(event_id)` in Postgres and catch `DataIntegrityViolationException` in the repository facade — no read-then-write race, no application-level coordination.

### Repeat-offender detection via Redis sorted set
Per-IP ZSET, members keyed by event UUID, score is event-time epoch ms. `ZREMRANGEBYSCORE` evicts the window edge on each write; `ZCARD > threshold` is the check. TTL on the key is a janitor for quiet IPs. Degrades open if Redis is down — no bonus is applied, event still proceeds.

### Tier-based deployment via Spring profiles
The same JAR runs as four different services depending on `SPRING_PROFILES_ACTIVE`:

| Profile   | Components                                                            | Scaling signal     |
|-----------|-----------------------------------------------------------------------|--------------------|
| `ingest`  | `IngestionController`, `EventProducer`, `KafkaTopicConfig`            | request rate       |
| `consumer`| `EventConsumer`, `EnrichmentService`, `RepeatOffenderTracker`         | Kafka consumer lag |
| `query`   | `StatsController/Service`, `SamplesController/Service`, repositories  | read traffic       |
| `dev`     | `DevController`, `GeneratorService`, `ScenarioLibrary`                | (off in prod)      |
| `all`     | Everything — single JVM. Default for `docker compose up`.             | n/a                |

The tiers share nothing except the Kafka topic contract and the database schema. Wiring is verified by `ProfileWiringTest` at unit-test time.

### Storage choice
Postgres for the assignment because the workload is SQL-shaped (`WHERE time-range AND configId GROUP BY ... ORDER BY COUNT DESC`) and idempotency is a relational invariant. Migrations via Flyway, queries are portable JPA — no Postgres-specific SQL anywhere.

### Index strategy

Every query always predicates on `config_id` (equality) and `timestamp` (range). The GROUP BY column (`rule_category`, `action`, `client_ip`) comes after those two in the predicate list, so the useful index shape is `(config_id, <group-by-column>, timestamp DESC)` — not a standalone column index.

| Index | Columns | Serves |
|---|---|---|
| `idx_events_config_ts` | `(config_id, timestamp DESC)` | `countTotal`, `topAttackers`, `topPaths`, `findSamples` with no category/action filter |
| `idx_events_ts` | `(timestamp DESC)` | Same queries when `config_id` is null (global view) |
| `idx_events_config_category_ts` | `(config_id, rule_category, timestamp DESC)` | `byCategory` aggregation; `findSamples` filtered by category |
| `idx_events_config_action_ts` | `(config_id, action, timestamp DESC)` | `byAction` aggregation; `findSamples` filtered by action |
| `idx_events_client_ip_ts` | `(client_ip, timestamp DESC)` | `topAttackers` when `config_id` is null |
| unique on `event_id` | `(event_id)` | Idempotent upsert deduplication |

V1 created single-column `idx_events_category` and `idx_events_action` indexes. These were dropped in V2 — Postgres would never pick them because the planner always finds `idx_events_config_ts` cheaper when `config_id` and `timestamp` are already in the predicate. The V2 composites supersede them.

## Testing

| Layer | What it covers | Tooling |
|---|---|---|
| **Unit tests** | Pure logic — threat-score calculator, enrichment service, event generator, scenario library, Criteria query construction (mocked `EntityManager`), `@Profile` wiring (reflection) | JUnit + Mockito |
| **Component tests** | Each storage facade and aggregator in isolation against real Postgres via Testcontainers — `EventRepository` upsert/idempotency, `EventRepository.findSamples` filter+pagination, `StatsRepository` Criteria aggregations | `@DataJpaTest` + Testcontainers |
| **Full-pipeline integration test** | REST → Kafka → consumer → enrichment → Redis → Postgres → query, driven by MockMvc against the actual Spring context with real Postgres, Kafka, and Redis containers | `@SpringBootTest` + MockMvc + Testcontainers |
| **End-to-end (e2e)** | 27 curl-driven checks against the full stack from outside: status codes, JSON shape, idempotency, validation, pagination, filter behavior | `e2e.sh` + `docker compose` |

77 unit + component + integration tests inside `mvn test`; 27 e2e checks outside.

## What would change for production

| Area | Production | Why |
|------|-----------|-----|
| **Kafka** | Managed cluster across multiple availability zones, replication factor 3, authentication and TLS in transit, dead-letter topic | Single broker + plaintext is fine for a demo, not for SOC-grade durability |
| **Storage** | Managed Postgres (read replicas, time-partitioned `events` table, composite index on `(timestamp, config_id)`) up to the point row-store aggregations get expensive; columnar warehouse past that | Single-node Postgres tops out a few orders of magnitude below WSA volumes; a column-store is the natural fit for the `GROUP BY` workload |
| **Redis** | Managed cluster with replicas per shard | Single node has no failover and no horizontal capacity |
| **Auth** | Authentication at the API gateway; gate `/v1/dev/*` behind the `dev` profile so it's never exposed in prod | No endpoint is authenticated today |
| **Rate limiting** | Per-IP rate limit at the gateway or in front of the ingest controller | Public endpoints are unprotected |
| **Observability** | Metrics export, distributed tracing, structured logs shipped to a central platform | Actuator is enabled but metrics aren't exported |
| **Deployment** | Three deployments off the same image, each with its own `SPRING_PROFILES_ACTIVE` (ingest/consumer/query); consumer scaled on Kafka consumer lag, others on request rate | Three tiers have three different scaling curves |

The migration is cheap because the components were already decoupled — they only ever communicated through Kafka and the database. Splitting was a configuration change, not an architecture change.

## Challenges worked through

- **Nullable JPQL parameters on Postgres** — `(:p IS NULL OR e.col = :p)` worked on H2, broke on Postgres because JDBC sends untyped nulls. Rewrote to a `Specification` builder that simply omits absent predicates.
- **Generator non-determinism** — the spec required "same seed → same output", but the generator was calling `Instant.now()` internally. Made `now` an explicit parameter; production passes `Instant.now()`, tests pass a fixed Instant.
- **Singleton-container test pattern** — class-scoped `@Container` was killing Postgres between test classes while Spring's TestContext cache kept the app context alive. Fixed by starting the container in a `static {}` block on a `BaseIntegrationTest` superclass.
- **Idempotent ingestion** — rely on `UNIQUE(event_id)` + catch `DataIntegrityViolationException`; no `existsBy` precheck, no read-then-write race.