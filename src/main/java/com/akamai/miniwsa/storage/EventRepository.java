package com.akamai.miniwsa.storage;

import com.akamai.miniwsa.domain.EnrichedEvent;
import com.akamai.miniwsa.domain.GeoLocation;
import com.akamai.miniwsa.domain.Rule;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;

/**
 * Facade over {@link EventJpaRepository}. Maps the immutable
 * {@link EnrichedEvent} domain record to the mutable {@link EventEntity}
 * JPA bean. Services depend on this facade, never on JPA types directly.
 *
 * <h2>Idempotency model</h2>
 *
 * <p>The authoritative idempotency guarantee is the {@code UNIQUE(event_id)}
 * constraint in the database. Three layers reinforce it:
 *
 * <ol>
 *   <li><b>Kafka partitioning by {@code clientIp}.</b> Same client (and
 *       therefore same {@code eventId}) routes to the same partition,
 *       which is serviced by a single consumer thread. Redelivery after
 *       a consumer crash is sequential, not concurrent.
 *   <li><b>Manual ack only after a successful save.</b> If the save throws,
 *       the offset is not committed and Kafka redelivers; the redelivered
 *       attempt sees the unique-constraint violation and returns false.
 *   <li><b>{@code DataIntegrityViolationException} catch.</b> Cross-partition
 *       eventId collisions (operator error, not normal operation) are
 *       handled here without raising a noisy stack trace upstream.
 * </ol>
 *
 * <p>No {@code @Transactional} on this method: Spring Data's
 * {@code JpaRepository.save} runs in its own transaction, so a constraint
 * violation rolls back that transaction without leaving a rollback-only
 * marker on a wider scope.
 */
@Repository
public class EventRepository {

    private final EventJpaRepository jpa;

    public EventRepository(EventJpaRepository jpa) {
        this.jpa = jpa;
    }

    /**
     * Persists the enriched event. Returns {@code true} if inserted,
     * {@code false} if an event with this {@code eventId} already exists.
     */
    public boolean upsert(EnrichedEvent event) {
        try {
            jpa.save(toEntity(event));
            return true;
        } catch (DataIntegrityViolationException duplicate) {
            return false;
        }
    }

    private static EventEntity toEntity(EnrichedEvent e) {
        Rule rule = e.rule();
        GeoLocation geo = e.geoLocation();

        EventEntity entity = new EventEntity();
        entity.setEventId(e.eventId());
        entity.setTimestamp(e.timestamp());
        entity.setReceivedAt(e.receivedAt());
        entity.setConfigId(e.configId());
        entity.setPolicyId(e.policyId());
        entity.setClientIp(e.clientIp());
        entity.setHostname(e.hostname());
        entity.setPath(e.path());
        entity.setMethod(e.method());
        entity.setStatusCode(e.statusCode());
        entity.setUserAgent(e.userAgent());
        entity.setRuleId(rule.id());
        entity.setRuleName(rule.name());
        entity.setRuleMessage(rule.message());
        entity.setRuleSeverity(rule.severity());
        entity.setRuleCategory(rule.category());
        entity.setAction(e.action());
        entity.setGeoCountry(geo == null ? null : geo.country());
        entity.setGeoCity(geo == null ? null : geo.city());
        entity.setRequestSize(e.requestSize());
        entity.setResponseSize(e.responseSize());
        entity.setAttackType(e.attackType());
        entity.setThreatScore(e.threatScore());
        return entity;
    }
}
