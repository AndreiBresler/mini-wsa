package com.akamai.miniwsa.storage;

import com.akamai.miniwsa.domain.Action;
import com.akamai.miniwsa.domain.Category;
import com.akamai.miniwsa.domain.EnrichedEvent;
import com.akamai.miniwsa.domain.GeoLocation;
import com.akamai.miniwsa.domain.Rule;
import jakarta.persistence.criteria.Predicate;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Facade over {@link EventJpaRepository}. Maps between the immutable
 * {@link EnrichedEvent} domain record and the mutable {@link EventEntity}
 * JPA bean. Services depend on this facade, never on JPA types directly.
 *
 * <p>Dynamic filtering for {@code findSamples} is built with a JPA
 * {@link Specification} so the WHERE clause contains only the predicates
 * the caller supplied. The earlier {@code :param IS NULL OR e.field = :param}
 * pattern fails on Postgres for several JDBC types because Postgres cannot
 * infer the data type of a parameter that appears only inside {@code IS NULL}.
 *
 * <p>See LLD for the idempotency model around {@link #upsert}.
 */
@Repository
public class EventRepository {

    private static final String FIELD_CONFIG_ID = "configId";
    private static final String FIELD_TIMESTAMP = "timestamp";
    private static final String FIELD_RULE_CATEGORY = "ruleCategory";
    private static final String FIELD_ACTION = "action";

    private final EventJpaRepository jpa;

    public EventRepository(EventJpaRepository jpa) {
        this.jpa = jpa;
    }

    public boolean upsert(EnrichedEvent event) {
        try {
            jpa.save(toEntity(event));
            return true;
        } catch (DataIntegrityViolationException duplicate) {
            return false;
        }
    }

    public SamplesResult findSamples(Integer configId, Instant from, Instant to,
                                     Category category, Action action, Pageable pageable) {
        Specification<EventEntity> spec = buildSamplesSpecification(configId, from, to, category, action);
        Page<EventEntity> page = jpa.findAll(spec, pageable);
        List<EnrichedEvent> samples = page.getContent().stream()
                .map(EventRepository::toDomain)
                .toList();
        return new SamplesResult(page.getTotalElements(), samples);
    }

    public record SamplesResult(long totalCount, List<EnrichedEvent> samples) {
    }

    private static Specification<EventEntity> buildSamplesSpecification(
            Integer configId, Instant from, Instant to, Category category, Action action) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (configId != null) {
                predicates.add(cb.equal(root.get(FIELD_CONFIG_ID), configId));
            }
            if (from != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.<Instant>get(FIELD_TIMESTAMP), from));
            }
            if (to != null) {
                predicates.add(cb.lessThan(root.<Instant>get(FIELD_TIMESTAMP), to));
            }
            if (category != null) {
                predicates.add(cb.equal(root.get(FIELD_RULE_CATEGORY), category));
            }
            if (action != null) {
                predicates.add(cb.equal(root.get(FIELD_ACTION), action));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
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

    private static EnrichedEvent toDomain(EventEntity e) {
        Rule rule = new Rule(e.getRuleId(), e.getRuleName(), e.getRuleMessage(),
                e.getRuleSeverity(), e.getRuleCategory());
        GeoLocation geo = (e.getGeoCountry() == null && e.getGeoCity() == null)
                ? null
                : new GeoLocation(e.getGeoCountry(), e.getGeoCity());
        return new EnrichedEvent(
                e.getEventId(), e.getTimestamp(), e.getReceivedAt(), e.getConfigId(),
                e.getPolicyId(), e.getClientIp(), e.getHostname(), e.getPath(),
                e.getMethod(), e.getStatusCode(), e.getUserAgent(),
                rule, e.getAction(), geo,
                e.getRequestSize(), e.getResponseSize(),
                e.getAttackType(), e.getThreatScore()
        );
    }
}