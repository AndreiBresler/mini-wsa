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
                                      └─ batch insert
                                                   │
              ┌────────────────────────────────────┴────────────────┐
              ▼                                                     ▼
          Postgres                                                Redis
       (events table)                                  (sliding window per IP)
              ▲
              │
   GET /v1/stats/summary
   GET /v1/events/samples
   GET /v1/stats/timeseries
```

REST receives events and produces to Kafka, returning 202-style "accepted". A `@KafkaListener` consumer drives enrichment + persistence. Repeat-offender state is kept in Redis as a sorted set per `clientIp`, aged out by score = epoch ms. Kafka decouples ingestion from processing so the consumer can crash, restart, and replay without losing events.

## Stack

- Java 21, Spring Boot 3.4
- Postgres 16 (storage)
- Redis 7 (sliding-window state)
- Kafka 3.8 in KRaft mode (no Zookeeper)
- Flyway (migrations)
- Testcontainers (integration tests)

## Build & run

### Option A — full stack via Docker (recommended)

```bash
docker compose up --build
```

This brings up Postgres, Redis, Kafka, and the app. The API is exposed at `http://localhost:8080`.

### Option B — run app locally, dependencies in Docker

```bash
docker compose up -d postgres redis kafka
./mvnw spring-boot:run
```

### Tests

```bash
./mvnw test
```

## API

### Ingest a single event

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
      "message": "SQL Injection Attack Detected",
      "severity": "CRITICAL",
      "category": "INJECTION"
    },
    "action": "DENY",
    "geoLocation": { "country": "CN", "city": "Beijing" },
    "requestSize": 1024,
    "responseSize": 256
  }'
```

### Ingest a batch

Same endpoint, body is a JSON array of events.

### Stats summary (coming in v0.3)

```bash
curl 'http://localhost:8080/v1/stats/summary?configId=14227&from=2026-05-20T00:00:00Z&to=2026-05-21T00:00:00Z'
```

### Samples (coming in v0.4)

```bash
curl 'http://localhost:8080/v1/events/samples?category=INJECTION&action=DENY&limit=20'
```

## Storage choice

Postgres for the assignment. At production scale this workload belongs on a columnar store like ClickHouse — DLR ingest is append-only, queries are aggregations over time ranges, and ClickHouse's MergeTree handles both trivially. Postgres was chosen here because:

- One ops dependency, single-command bring-up
- Spring Data + JdbcTemplate ergonomics
- Aggregations on a few million rows are fine with the right indexes: `(config_id, timestamp DESC)` for the time-range scan, `(client_ip, timestamp)` for top-attackers, `(rule_category)` and `(action)` for filtered counts

If this ran in production: ClickHouse for hot analytical queries, S3 for cold storage, the OLTP Postgres only for things like alert rule definitions.

## What I'd improve with more time

- Dead-letter topic for permanently-failing messages
- Schema registry + Avro instead of JSON over Kafka
- ClickHouse for the analytics path, leaving Postgres for control-plane data only
- Materialized views or rollups for the top-paths / top-attackers queries
- Backpressure on the ingestion endpoint (semaphore or bulkhead) so a producer flood can't OOM the JVM

## Milestones

- `v0.1-ingestion` — Kafka producer + consumer, REST endpoint, validation, exception handler
- `v0.2-enrichment` — classification, threat scoring, Redis sliding window, persistence
- `v0.3-stats` — `/v1/stats/summary` with top attackers and paths
- `v0.4-samples` — `/v1/events/samples` with filters + pagination
- `v0.5-timeseries` — `/v1/stats/timeseries` bucketed by interval
