# AGENT.md — Development Guidelines

Guidelines for AI

## Scope of allowed changes

✓ Java source, tests, configs, migrations, docs, docker-compose, Dockerfile  
✓ New endpoints, services, repositories within the established layout (`api/ ingestion/ enrichment/ storage/ stats/ generator/ domain/ config/`)  
✓ Refactoring within a module to improve clarity  
✓ Adding dependencies that are well-known, actively maintained, and Apache 2.0 or MIT licensed  
✓ SQL migration files — add new `V{n+1}__name.sql` only; never modify a committed migration  

## Do not

✗ Introduce frameworks not already in `pom.xml` without prior discussion  
✗ Add R2DBC, Reactor, or WebFlux — synchronous JDBC + JDK 21 virtual threads is the design  
✗ Add an OpenAPI/Swagger code generator — curl examples in the README are sufficient  
✗ Commit code that does not compile (`mvn compile` must pass before every commit)  
✗ Commit tests that don't assert behavior (`assertTrue(true)`, commented-out assertions)  
✗ Reformat existing code unless explicitly asked  
✗ Squash commit history before pushing  
✗ Inline magic strings — all route paths, topic names, and key prefixes go in `private static final` constants at the top of the relevant class  
✗ Catch and swallow exceptions silently — log at WARN minimum, wrap-and-rethrow when not the right handler  
✗ Add license headers in source files — `pom.xml` carries the license  

## Coding conventions

- **Java 21.** Use records for DTOs. Pattern matching where it improves readability over `instanceof` chains.
- **Virtual threads on by default.** `spring.threads.virtual.enabled=true`. Blocking I/O is fine; the JVM unmounts the virtual thread from its carrier.
- **One public class per file.** Package-private by default; promote to `public` only when crossing module boundaries.
- **Constants at the top of the file** for any string that appears in more than one place or has external meaning (route paths, topic names, Redis key prefixes, header names).
- **No `Optional` in domain records** as field types; use nullable fields. `Optional` belongs in return types of repository methods.
- **No `static` mutable state** anywhere outside `*Test` classes.
- **`@Transactional`** on service classes or persistence facades only — never on controllers, never on Spring Data interfaces.
- **JPA via Spring Data.** `@Entity` classes live under `storage/`. Analytical aggregations use `@Query` JPQL constructor expressions returning record projections. Repositories are wrapped in facade classes so services depend on domain records, not JPA types. Avoid database-specific SQL; isolate any vendor-specific snippet in one facade method with a comment.

## Test conventions

- **Naming:** `methodOrBehavior_underCondition_expectedResult` — e.g. `critical_deny_on_login_caps_at_100`.
- **Assert from `org.assertj.core.api.Assertions`** — readable, chainable, great failure messages.
- **One concept per test.** Multiple assertions are fine if they verify one behavior; unrelated behaviors → split into separate tests.
- **Integration tests use Testcontainers** for Postgres, Kafka, and Redis. No H2 stand-ins.
- **Assert observable behavior only** — not that a method called another method.

## Commit conventions

- **Small, logical commits.** One feature or fix per commit.
- **Imperative subject line, ≤ 72 chars.** "Add stats summary endpoint", not "Added" or "Adding".
- **Tag milestones:** `v0.X-name` (`v0.1-ingestion`, `v0.2-enrichment`, `v0.3-stats`, `v0.4-samples`, `v0.5-generator`, `v0.6-timeseries`).
- **Don't squash before pushing.**

## Decisions requiring discussion before implementation

- Architectural changes (storage engine, message broker, framework)
- New microservices or modules
- Major library upgrades (Spring Boot major version, Java major version)
- Deleting any test
- Changes to the public REST API contract (request/response shapes, status codes)
- Changes to the Kafka message contract (key, value schema)

## Build and run reference

```bash
# Compile
mvn compile

# Run all tests (unit + integration with Testcontainers)
mvn test

# Bring up full stack
docker compose up --build

# Bring up just dependencies, run app from Maven for fast iteration
docker compose up -d postgres redis kafka
mvn spring-boot:run

# Run AKHQ for Kafka UI (optional, dev profile only)
docker compose --profile dev up -d akhq
# then visit http://localhost:8090
```

## Security

- **No secrets in source or git history.** Use environment variables only. Default values in `application.yml` are for local development.
- **No real PII in sample data or tests.** The generator uses RFC 5737 documentation IP ranges (203.0.113.0/24, 198.51.100.0/24, 192.0.2.0/24).
- **No production credentials** anywhere — this is a demo system.

## Definition of done

A feature is complete when:
1. `mvn test` passes locally.
2. `docker compose up --build` brings the full stack up cleanly.
3. The relevant curl example in the README works end-to-end.
4. Changes are committed with a clear message and tagged if it's a milestone.