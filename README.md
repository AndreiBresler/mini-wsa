# Mini WSA — Security Analytics Pipeline

A minimal version of Akamai's Web Security Analytics platform. Ingests security event records (DLRs) through Kafka, enriches them with attack classification and threat scoring, persists to Postgres, and exposes analytics via REST.

## Architecture

```
REST clients ─┐
              ├─► POST /v1/events/ingest ─► Kafka (security-events, keyed by clientIp)
generator ────┘                                    │
                                                   ▼
                                      Kafka consumer
                                      ├─ validate
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

REST receives events and publishes to Kafka, returning a 201 with `{accepted, message}`. A `@KafkaListener` consumer drives enrichment + persistence on each partition. Repeat-offender state is kept in Redis as a sorted set per `clientIp`, aged out by score = epoch ms. Kafka decouples ingestion from processing so the consumer can crash, restart, and replay without losing events; idempotency is guaranteed by a `UNIQUE(event_id)` constraint that converts redeliveries into harmless no-ops.

## Stack

- Java 21, Spring Boot 3.4
- Postgres 16 (storage)
- Redis 7 (sliding-window state)
- Kafka 3.9.1 in KRaft mode (no Zookeeper)
- Flyway (schema migrations)
- Spring Data JPA + Criteria API (queries — see [ORM-only persistence](#orm-only-persistence-no-sql-or-jpql-strings))
- Testcontainers (integration tests)
- Spring profiles for tier-based deployment — see [Deployment topology](#deployment-topology)

## Build & run

### Option A — full stack via Docker (recommended)

```bash
docker compose up --build
```

This brings up Postgres, Redis, Kafka, and the app. The API is exposed at `http://localhost:8080`. With no `SPRING_PROFILES_ACTIVE` set, the application runs every tier (ingest, consumer, query, dev) in a single JVM — convenient for the demo. See [Deployment topology](#deployment-topology) for the production split.

### Option B — run app locally, dependencies in Docker

```bash
docker compose up -d postgres redis kafka
./mvnw spring-boot:run
```

### Tests

```bash
./mvnw test
```

Tests include:
- Unit tests for pure logic (`ThreatScoreCalculator`, `EventGenerator`, `EnrichmentService`, both repository facades, both services, `ProfileWiringTest`)
- Integration tests against real Postgres, Kafka, Redis via Testcontainers (`IngestionFlowIntegrationTest`, `EventRepository*IntegrationTest`, `StatsRepositoryIntegrationTest`, `ScenarioLibraryTest`)

## API

### Ingest events

`POST /v1/events/ingest` accepts a single event object or an array of events.

```bash
curl -X POST http://localhost:8080/v1/events/ingest \
  -H 'Content-Type: application/json' \
  -d '{
    "eventId": "evt-00132",
    "timestamp": "2026-05-20T14:32:10Z",
    "configId": 14227,
    "policyId": "pol_web1",
    "clientIp": "203.0.113.42",
    "hostname": "www.example.com",
    "path": "/api/v1/login",
    "method": "POST",
    "statusCode": 403,
    "userAgent": "Mozilla/5.0",
    "rule": {
      "id": "950001",
      "name": "SQL_INJECTION",
      "message": "SQL Injection",
      "severity": "CRITICAL",
      "category": "INJECTION"
    },
    "action": "DENY",
    "geoLocation": {"country": "CN", "city": "Beijing"},
    "requestSize": 1024,
    "responseSize": 256
  }'
```

Returns `201 Created` with `{accepted: 1, message: "accepted"}`. Validation errors return `400` with field-level details.

### Stats summary

`GET /v1/stats/summary?configId={id}&from={iso8601}&to={iso8601}` — all parameters optional.

```bash
curl 'http://localhost:8080/v1/stats/summary?configId=14227&from=2026-05-20T00:00:00Z&to=2026-05-21T00:00:00Z'
```

### Samples

`GET /v1/events/samples?configId={}&from={}&to={}&category={}&action={}&limit={}&offset={}` — paginated, all filters optional. Default `limit=20`, max `100`. Sorted by timestamp descending.

```bash
curl 'http://localhost:8080/v1/events/samples?category=INJECTION&action=DENY&limit=20'
```

### Data generator (dev)

`GET /v1/dev/scenarios` — list the bundled scenarios.
`POST /v1/dev/generate?scenario={name}&seed={long}` — generate a scenario worth of events and POST them through the public ingest endpoint. Same-seed runs are byte-for-byte reproducible.

Available scenarios: `single-targeted-attack`, `multi-vector-incident`, `slow-burn`, `quiet-bot-day`.

## ORM-only persistence (no SQL or JPQL strings)

A deliberate rule across this codebase: **the application code does not contain any hand-written SQL or JPQL query strings.** Persistence is expressed exclusively through:

- **Spring Data JPA derived methods** (`save`, `findAll`, etc.) for trivial cases
- **JPA Specifications** for filtered list queries — see `EventSpecifications.samples(...)` powering `GET /v1/events/samples`
- **JPA Criteria API** for aggregations — see `StatsRepository` powering `GET /v1/stats/summary`

The reason: string-based queries (JPQL or native SQL) fail silently when a field is renamed, a typo slips in, or a refactor moves a column. The error surfaces only when that query path is exercised — usually in production. Specifications and Criteria construct the query graph programmatically through `CriteriaBuilder`, so the *shape* of the query (which projections, which predicates, which group-by columns) is expressed in Java method calls that the compiler validates at the API level.

Field names are still string literals in the current implementation (`root.get("ruleCategory")`) because the JPA static metamodel isn't generated. Adding the `hibernate-jpamodelgen` annotation processor would give compile-time-checked field references (`EventEntity_.ruleCategory`) — a one-line `pom.xml` addition planned for the next iteration. Even without it, integration tests run against real Postgres via Testcontainers and catch field-name drift before it ships.

This rule also keeps the code portable across SQL backends: the same repositories run unchanged on Postgres, Aurora, or any other JPA-compatible store. The Criteria queries are translated to vendor SQL by Hibernate at runtime.

## Deployment topology

The build produces a single Spring Boot JAR. For local dev and the assignment demo, `docker compose up` runs it as one process with every tier active — that's the `all` Spring profile, which is the default when `SPRING_PROFILES_ACTIVE` is unset.

In production these tiers have different scaling profiles and would be deployed as **separate services off the same JAR**, each with its own `SPRING_PROFILES_ACTIVE`:

| Profile     | Components active                                                  | Scaling signal      | Notes                                                  |
|-------------|--------------------------------------------------------------------|---------------------|--------------------------------------------------------|
| `ingest`    | `IngestionController`, `EventProducer`, `KafkaTopicConfig`         | request rate        | Stateless, CPU-light, network-bound. Many small pods.  |
| `consumer`  | `EventConsumer`, `EnrichmentService`, `RepeatOffenderTracker`, `EventRepository` | Kafka consumer lag | CPU + I/O bound. Capped by topic partition count.      |
| `query`     | `StatsController/Service`, `SamplesController/Service`, `EventRepository`, `StatsRepository` | read traffic       | Read-only. Bursty, cacheable.                          |
| `dev`       | `DevController`, `GeneratorService`, `ScenarioLibrary`             | n/a                 | Generator endpoints. Must NOT be active in prod.       |
| `all`       | Everything above                                                   | n/a                 | Default. Demo / dev convenience.                       |

`EventRepository` participates in both `consumer` (writes via `upsert`) and `query` (reads via `findSamples`). `StatsRepository` participates in `query`. Wiring is a single annotation per class — `@Profile({"query", "all"})` etc. — and is verified by `ProfileWiringTest` at unit-test time.

In Kubernetes:

```yaml
# Three deployments, one image, three SPRING_PROFILES_ACTIVE values.
# Each can scale independently. Number of consumer pods is capped at
# the partition count on the security-events topic.
```

The tiers share nothing except the Kafka topic contract and the database schema. Splitting required no architecture changes — the components were already decoupled; they only ever communicated through Kafka and Postgres, never via direct method calls.

## Storage choice

**Used for the assignment:** PostgreSQL 16 (vanilla, single node, Docker).

Picked because:

1. The workload is **SQL-shaped**: every analytics query is `WHERE time-range AND configId GROUP BY column ORDER BY COUNT(*) DESC`. JPA Criteria maps to that directly with no impedance mismatch.
2. Idempotent ingestion is guaranteed by a `UNIQUE(event_id)` constraint — a relational invariant that costs nothing in Postgres and would require application-level coordination in a non-relational store.
3. Local-dev story: one Docker image, one `psql` to inspect.

Schema is managed by Flyway; all queries are portable (JPA Criteria, no Postgres-specific SQL), so the code moves cleanly to any SQL backend.

### Production scaling path

The query pattern is **OLAP over append-only time-series data**. AWS scales it in tiers:

**Tier 1 — Up to ~100M events: Aurora Postgres.** Drop-in replacement, zero application code changes. Time-partition the `events` table (one partition per day or week) plus a composite index on `(timestamp, config_id)`. Aurora's parallel query helps the aggregations. Buys roughly two orders of magnitude over single-node Postgres before costs hurt.

**Tier 2 — Beyond ~100M events: Amazon Redshift.** Columnar storage is the natural fit for `GROUP BY clientIp COUNT(*) ORDER BY count DESC` over hundreds of millions of rows. Sort key on `(config_id, timestamp)`, dist key on `client_ip` for top-attackers locality. The Criteria queries port to Redshift SQL with minor dialect tweaks at the Hibernate level.

**Tier 3 — Archive / ad-hoc analyst queries: Athena + S3 (Parquet).** Past the hot window (e.g. > 90 days), events get exported to date-partitioned Parquet in S3 and queried via Athena. Effectively free storage; pay only for the bytes scanned per query. Right tool for compliance retention and SOC investigations that don't need second-latency.

### Why not other AWS options

- **DynamoDB** — excellent for `getEventById` (which we don't expose), poor for `GROUP BY` aggregations. Would force a secondary analytics store anyway.
- **Timestream** — designed for per-device IoT time series, awkward for `GROUP BY clientIp` or `GROUP BY path`.
- **OpenSearch** — could do these queries via aggregation DSL, but the workload is SQL-native and the rest of the analytics tooling (BI dashboards, ad-hoc SOC queries) speaks SQL. Saving the OpenSearch swap for full-text log exploration, which isn't in scope.

### Why the swap is cheap

The `EventRepository` + `StatsRepository` facades mean changing storage is two classes. The ingestion path (REST → Kafka → consumer), the enrichment logic, the API DTOs, and the threat-score math don't know or care what's underneath.

## Production-readiness gaps

This is an assignment, not a production deployment. Intentional simplifications, documented so the reviewer knows we know:

### Kafka

The Compose setup runs a **single-broker KRaft cluster with no replication, no authentication, no encryption**. In production:

- **Replication** — ≥ 3 brokers across AZs; topic replication factor ≥ 3; `min.insync.replicas = 2`; producer `acks=all` (already set).
- **Authentication / authorization** — SASL/SCRAM or mTLS; per-service ACLs so the ingest service can only `Write` `security-events`, and only the consumer group can `Read`.
- **Encryption** — TLS on every listener.
- **Operations** — dead-letter topic for poison messages, schema registry (Avro/Protobuf) instead of JSON, MirrorMaker for cross-region DR.

In AWS terms: **Amazon MSK**, three brokers across AZs, IAM auth, TLS in transit.

### Other gaps

- **No authentication** on any endpoint. Production would put OAuth/JWT at the API gateway and remove or `@Profile("dev")`-gate `/v1/dev/*`.
- **No rate limiting** on the public endpoints. One of the bonus challenges.
- **Single-node Redis** — production would use ElastiCache with cluster mode + replicas.
- **Single Postgres** — read replica for the query tier; analytics reads are bursty and don't need to compete with writes.
- **No metrics export** — Actuator is enabled (`/actuator/health`) but Micrometer → Prometheus → Grafana isn't wired.

## What I'd improve with more time

- Generate the JPA static metamodel (`hibernate-jpamodelgen`) for compile-checked field references in the Criteria queries
- Materialized views or rollup tables for `topAttackers` / `topTargetedPaths` (recomputing the full GROUP BY on every dashboard refresh is wasteful)
- Dead-letter topic + handler for permanently-failing Kafka messages
- Schema registry + Avro on Kafka (current JSON is fine for the assignment, not for prod-scale)
- `@Cacheable` on stats responses with a short TTL (current implementation recomputes on every request)
- Backpressure on the ingest endpoint (semaphore or bulkhead) so a producer flood can't OOM the JVM

## Challenges I worked through

- **Postgres + nullable JPQL params** — the original `findSamples` `@Query` used `(:p IS NULL OR e.col = :p)` for optional filters. Worked on H2, broke on Postgres because JDBC sends untyped nulls. Fixed by rewriting to a `Specification<EventEntity>` builder that simply omits absent predicates — no nullable parameters ever sent.
- **`EventGenerator` determinism** — the spec said "same seed → identical output" but the generator was calling `Instant.now()` internally, so timestamps differed across runs. Made `now` an explicit parameter of `generate()`; production passes `Instant.now()`, tests pass a fixed Instant.
- **Singleton-container test pattern** — class-scoped `@Container` was killing Postgres between test classes, but Spring's TestContext cache kept the JVM-wide app context pointing at the dead container. Fixed by starting the container in a `static {}` block on a `BaseIntegrationTest` superclass; Testcontainers' Ryuk reaper handles JVM-exit cleanup.
- **Idempotent ingestion under at-least-once delivery** — Kafka redelivers on consumer crash; we rely on a `UNIQUE(event_id)` constraint and catch `DataIntegrityViolationException` in the facade. No `existsBy` precheck, so no read-then-write race.

## Milestones

- `v0.1-ingestion` — Kafka producer + consumer, REST endpoint, validation, exception handler
- `v0.2-enrichment` — classification, threat scoring, Redis sliding window, persistence
- `v0.3-stats` — `/v1/stats/summary` with top attackers and paths
- `v0.4-samples` — `/v1/events/samples` with filters + pagination
- `v0.5-generator` — scenario-driven data generator behind `/v1/dev/*`
- `v0.6-criteria-profiles` — Criteria-only persistence + Spring-profile tier separation
