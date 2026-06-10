# Event Generator — Low-Level Design

> Assignment Part 5: "Create a data generator (script or class) that creates events with realistic distributions and includes attack waves (bursts of events from the same IP targeting the same path)."

## 1. Purpose

A programmatic source of synthetic but realistic security events for demo, manual testing, and to make the analytics endpoints (`/v1/stats/summary`, `/v1/events/samples`) interesting to query. It is not a load tester and not a fuzz tester.

The generator publishes events by **making HTTP POSTs to `/v1/events/ingest`** — the same path real clients would use. It does not short-circuit to Kafka. This means generated events traverse validation, the exception handler, the Kafka producer, the consumer, enrichment, and persistence — exactly as REST-ingested traffic would. The generator is, from the system's point of view, just another client.

## 2. API surface

Two endpoints. Event shape is defined by **named scenarios** loaded from a YAML config (§3). A reviewer can run a pre-canned scenario and immediately see meaningful data.

### `GET /v1/dev/scenarios`

```json
[
  { "name": "quiet-bot-day",          "description": "Baseline traffic, no attack waves" },
  { "name": "single-targeted-attack", "description": "One sustained injection wave on /api/v1/login" },
  { "name": "multi-vector-incident",  "description": "Multiple coordinated waves across categories" },
  { "name": "slow-burn",              "description": "Low-rate wave that just exceeds the repeat-offender threshold" }
]
```

### `POST /v1/dev/generate`

```
POST /v1/dev/generate?scenario=multi-vector-incident&configId=14227&seed=42&batching=false&batchSize=1
```

| Parameter   | Type    | Default                | Description |
|-------------|---------|------------------------|---|
| `scenario`  | string  | (required)             | Name of a scenario from the loaded config. 400 if unknown. |
| `configId`  | int     | 14227                  | All events are emitted under this config id. |
| `seed`      | long    | `System.nanoTime()`    | RNG seed. Same seed + same scenario → identical events. |
| `batching`  | boolean | scenario value or `false` | When true, events are shipped as JSON arrays of up to `batchSize` per HTTP request. When false, one event per request. |
| `batchSize` | int     | scenario value or `1`  | Cap on events per HTTP request. Last chunk is whatever remains. Has no effect when `batching=false`. |

Returns synchronously once all events have been **POSTed to the ingest endpoint** (not once consumed and persisted):

```json
{
  "scenario": "multi-vector-incident",
  "generated": 1000,
  "waveCount": 3,
  "singletonCount": 960,
  "httpRequests": 1000,
  "batching": false,
  "batchSize": 1,
  "durationMs": 842,
  "seed": 42
}
```

### Batching precedence

`query param` > `scenario value` > `global default (false / 1)`.

A scenario can set `defaultBatching: true` and `defaultBatchSize: 100` so that "high-volume" scenarios ship efficiently by default while still letting an operator force `batching=false` per request.

### A note on realism

`batching=false` matches the most literal interpretation of "create events" — one event = one HTTP request. Real production WAF agents typically batch (they buffer events for a few seconds, then ship in one call) — so `batching=true` is also realistic, just a different fidelity. Both modes traverse the same code paths server-side; only the request payload shape differs.

**Known limitation:** even with `batching=false`, the generator ships events as fast as the HTTP loop runs (~1000+ req/sec on localhost). The event `timestamp` field has correct in-payload spacing per the scenario, but the wall-clock arrival rate at the ingest endpoint is much faster than the timestamps suggest. We do **not** sleep between requests — that would turn a 1000-event run into hours.

Both endpoints live under `/v1/dev/` to signal they are development utilities. Production-readiness gating is in §11.

## 3. Scenario configuration

Scenarios are declared in `src/main/resources/generator-scenarios.yml` and loaded once at startup by `ScenarioLibrary` (a `@Component`).

### Schema

```yaml
scenarios:
  - name: <unique-identifier>
    description: <human-readable summary>
    count: <total events, including all wave events>
    windowHours: <hours back from "now" the singleton events span; default 24>
    defaultBatching: <bool; default false>
    defaultBatchSize: <int; default 1>
    waves:                          # optional; omit or empty list for no waves
      - size: <events in this wave>
        durationSeconds: <time span this wave covers>
        category: <INJECTION | XSS | DATA_LEAKAGE | DOS | BOT | RATE_LIMIT | PROTOCOL_VIOLATION>
        targetPath: <optional fixed path, e.g. /api/v1/login; if omitted, a random sensitive path is picked>
        severityProfile: <ESCALATING | CRITICAL | HIGH | MIXED>   # default ESCALATING
```

`severityProfile` controls per-wave severity:

- `ESCALATING` — first half `HIGH`, second half `CRITICAL`
- `CRITICAL` — every event `CRITICAL`
- `HIGH` — every event `HIGH`
- `MIXED` — sampled from the global severity weights (§5)

### Bundled scenarios

```yaml
scenarios:
  - name: quiet-bot-day
    description: "Baseline traffic, no attack waves. Demonstrates the unmolested distribution."
    count: 500
    windowHours: 24
    waves: []

  - name: single-targeted-attack
    description: "One sustained INJECTION wave from a single bad actor on /api/v1/login. Repeat-offender bonus fires."
    count: 200
    windowHours: 6
    waves:
      - size: 15
        durationSeconds: 30
        category: INJECTION
        targetPath: /api/v1/login
        severityProfile: ESCALATING

  - name: multi-vector-incident
    description: "Three coordinated waves (INJECTION, XSS, DATA_LEAKAGE) plus background traffic."
    count: 1000
    windowHours: 12
    defaultBatching: true
    defaultBatchSize: 100
    waves:
      - size: 12
        durationSeconds: 45
        category: INJECTION
        targetPath: /api/v1/login
      - size: 8
        durationSeconds: 20
        category: XSS
        severityProfile: HIGH
      - size: 20
        durationSeconds: 60
        category: DATA_LEAKAGE
        targetPath: /api/admin/dump
        severityProfile: CRITICAL

  - name: slow-burn
    description: "Low-rate wave (20 events spread across 8 minutes). Edge case: size > threshold but rate is just inside the 10-minute window."
    count: 2000
    windowHours: 24
    defaultBatching: true
    defaultBatchSize: 100
    waves:
      - size: 20
        durationSeconds: 480
        category: INJECTION
        targetPath: /api/v1/login
        severityProfile: MIXED
```

### Validation at load time

`ScenarioLibrary` validates each scenario when the file is read; any failure fails application startup:

- Unique `name` across all scenarios
- `count > 0` and `count ≤ miniwsa.generator.max-count`
- `windowHours > 0`
- Sum of wave sizes `≤ count` (the remainder become singletons)
- Each wave: `size ≥ 2`, `durationSeconds > 0`, `category` is a valid enum

### Configuration

```yaml
miniwsa:
  generator:
    max-count: 10000
    scenarios-file: classpath:generator-scenarios.yml
    ingest-url: http://localhost:8080
```

`ingest-url` is where the generator POSTs to. In dev it's localhost; in production it'd point at a load balancer or sibling instance.

## 4. Event generation algorithm

`EventGenerator` is a pure static utility — no Spring annotations, no state beyond the local `Random`. Given a `Scenario`, `configId`, and `seed`, it returns a `List<SecurityEvent>` deterministically:

1. **Compute counts.** `singletonCount = scenario.count - sum(wave.size)`. Already validated at load time to be non-negative.
2. **Place waves on a timeline.** Each wave gets a random start time within `windowHours`, with the constraint that wave time spans do not overlap. If we can't fit waves without overlap after 100 attempts, log a warning and allow overlap.
3. **Emit wave events.** Per wave: same attacker IP (from the bad-actor sub-pool), same target path (configured or a random sensitive path), evenly-spaced timestamps across `durationSeconds`, severity from `severityProfile`, action derived from severity (§5).
4. **Emit singleton events.** Independent draws from the global distributions (§5), timestamps spread non-uniformly across the window (denser toward "now").
5. **Sort and return.** Combine wave + singleton events, sort by timestamp ascending.

`eventId` format: `gen-{seed}-{index}` where `index` is the event's position in the sorted list. Reproducible and visually distinct from REST-ingested IDs.

## 5. Default distributions (singleton events)

Wave events override category, path, and severity per the wave config; everything else still draws from these pools.

### Category weights

| Category | Weight | Severity weight | Weight |
|---|---|---|---|
| `BOT` | 30 | `LOW` | 40 |
| `INJECTION` | 20 | `MEDIUM` | 30 |
| `XSS` | 15 | `HIGH` | 20 |
| `RATE_LIMIT` | 12 | `CRITICAL` | 10 |
| `DOS` | 10 |
| `PROTOCOL_VIOLATION` | 8 |
| `DATA_LEAKAGE` | 5 |

### Action distribution (derived from severity)

| Severity | Distribution |
|---|---|
| `CRITICAL` | DENY 80% / ALERT 20% |
| `HIGH` | DENY 60% / ALERT 40% |
| `MEDIUM` | ALERT 70% / MONITOR 30% |
| `LOW` | MONITOR 80% / ALERT 20% |

Internally consistent — no `LOW+DENY` or `CRITICAL+MONITOR` pairs.

### Pools (in `GeneratorConstants`)

- ~50 client IPs (RFC 5737 doc ranges + realistic public IPs); a ~10-IP "bad-actor" sub-pool used for wave attackers
- ~30 paths, weighted toward `/login`, `/admin`, `/api/v1/*` over `/static/*`; small sensitive sub-pool for random wave targets
- ~15 user-agents (browsers + bots + attack tools like `sqlmap`)
- 5 countries weighted toward `RU`, `CN`, `US`, `NL`, `BR`

### Timestamp distribution for singletons

Events span `now - windowHours` to `now`, denser toward "now". Approximated by `now - (random()^2 * windowHours * 3600s)`.

Within an attack wave, timestamps are dense (e.g. 10 events in 30 seconds) regardless of the global distribution.

## 6. Determinism

Every random choice runs through a single `Random` constructed from the request's `seed`. Same seed + same scenario → identical event list, identical wave placement, identical IPs, identical timestamps.

If `seed` is omitted, the generator uses `System.nanoTime()` and includes the chosen seed in the response so the caller can reproduce.

## 7. Component layout

```
src/main/java/com/akamai/miniwsa/generator/
├── GeneratorConstants.java     // pools and weights — constants only, no logic
├── Scenario.java               // record: name, description, count, windowHours, batching defaults, List<Wave>
├── Wave.java                   // record: size, durationSeconds, category, targetPath?, severityProfile
├── SeverityProfile.java        // enum: ESCALATING, CRITICAL, HIGH, MIXED
├── ScenarioLibrary.java        // @Component; loads YAML at startup, validates, lookup by name
├── EventGenerator.java         // pure static; produces List<SecurityEvent> from (Scenario, configId, seed)
├── GeneratorService.java       // orchestrates: scenario lookup, generation, batching, HTTP shipping
├── DevController.java          // REST endpoints, parameter binding
└── dto/
    ├── GenerateResponse.java   // response shape
    └── ScenarioSummary.java    // listing shape
```

`EventGenerator` is a `final class` with a private constructor and one static `generate()` method. Unit tests call it directly without Spring or Kafka.

`GeneratorService` holds the `RestClient` and the configured ingest URL. It is the only component that knows about HTTP.

## 8. Test strategy

### `EventGeneratorTest` — pure unit (no Spring, no Kafka, no HTTP)

| Test | Asserts |
|---|---|
| `produces_requested_count_of_events` | result size equals `scenario.count` exactly |
| `same_seed_produces_identical_output` | two runs with same seed → identical event lists |
| `different_seeds_produce_different_output` | two runs with different seeds differ |
| `wave_events_share_ip_and_path` | for a scenario with one wave of size N, exactly N events share one IP+path |
| `wave_events_span_configured_duration` | wave event timestamps fit within `durationSeconds` |
| `wave_severity_profile_escalating_applied` | first half of an ESCALATING wave is HIGH, second half is CRITICAL |
| `no_waves_scenario_produces_only_singletons` | scenario with empty waves → no IP appears > 3× in any 30s window |
| `category_weights_roughly_honored_in_singletons` | with `count=10000`, each category is within ±5% of declared weight |
| `severity_action_pairs_are_consistent` | no `LOW+DENY`, no `CRITICAL+MONITOR` |
| `timestamps_within_window` | all timestamps fall between `now - windowHours` and `now` |
| `all_events_have_required_fields` | every event has non-null eventId, timestamp, configId, clientIp, rule, action |

### `ScenarioLibraryTest` — unit

| Test | Asserts |
|---|---|
| `loads_bundled_scenarios_at_startup` | 4 bundled scenarios available by name |
| `rejects_scenario_with_duplicate_name` | startup throws |
| `rejects_scenario_with_wave_sum_exceeding_count` | startup throws |
| `lookup_by_name_returns_scenario` | returned object matches YAML |
| `lookup_by_unknown_name_throws` | clear exception |

The assignment's required "at least one integration test for the API" is already satisfied by `IngestionFlowIntegrationTest`. We do not add an extra integration test for `DevController` — it's a thin parameter-binding layer. Manual smoke (§12) covers it.

## 9. Where it does NOT belong

- **Not in `api/`** — that package is for production endpoints
- **Not bypassing the HTTP ingest path** — see §1
- **Not async** — see §2
- **Not a fuzz tester** — produces valid events only

## 10. Configuration summary

```yaml
miniwsa:
  generator:
    max-count: 10000
    scenarios-file: classpath:generator-scenarios.yml
    ingest-url: http://localhost:8080
```

## 11. Production-readiness gaps (intentional, documented)

If this were going to a real environment:

1. **Gate behind a Spring profile** — `@Profile("dev")` on `DevController` so it doesn't autoconfigure in prod
2. **Authn/authz** — at minimum a shared-secret header; open generator = DoS vector
3. **Rate-limit at the controller** independent of producer throughput
4. **Surface back-pressure** — return 429 if the producer queue fills
5. **Respect timestamps** — sleep between requests to match in-payload spacing (omitted because demos would take hours)

These are README "What I'd do next" items.

## 12. Smoke test (manual)

```bash
# List available scenarios
curl -s 'http://localhost:8080/v1/dev/scenarios' | jq .

# Quiet baseline — one event per HTTP request (batching=false default)
curl -s -X POST 'http://localhost:8080/v1/dev/generate?scenario=quiet-bot-day&seed=1' | jq .
sleep 3
curl -s 'http://localhost:8080/v1/stats/summary' | jq '{totalEvents, topAttackers: .topAttackers[:3]}'

# Targeted attack — dominant attacker should be obvious
curl -s -X POST 'http://localhost:8080/v1/dev/generate?scenario=single-targeted-attack&seed=2' | jq .
sleep 3
curl -s 'http://localhost:8080/v1/stats/summary' | jq '{topAttackers: .topAttackers[:3], topTargetedPaths: .topTargetedPaths[:3]}'

# Multi-vector — scenario opts into batching=true&batchSize=100 by default
curl -s -X POST 'http://localhost:8080/v1/dev/generate?scenario=multi-vector-incident&seed=3' | jq '{httpRequests, batching, generated}'
sleep 5
curl -s 'http://localhost:8080/v1/stats/summary' | jq '.byCategory'

# Override batching back to false on a high-volume scenario
curl -s -X POST 'http://localhost:8080/v1/dev/generate?scenario=multi-vector-incident&seed=4&batching=false' | jq '{httpRequests, batching}'
# httpRequests now equals generated (1000)

# Determinism — same seed twice produces same eventIds; duplicates dedup at the DB layer
curl -s -X POST 'http://localhost:8080/v1/dev/generate?scenario=quiet-bot-day&seed=42' | jq '.generated'
curl -s -X POST 'http://localhost:8080/v1/dev/generate?scenario=quiet-bot-day&seed=42' | jq '.generated'

# Unknown scenario returns 400
curl -s -X POST 'http://localhost:8080/v1/dev/generate?scenario=nope' -w '\nHTTP %{http_code}\n'
```
