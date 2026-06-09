package com.akamai.miniwsa.storage;

import com.akamai.miniwsa.domain.EnrichedEvent;
import com.akamai.miniwsa.domain.GeoLocation;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

@Repository
public class EventRepository {

    private static final String UPSERT_SQL = """
            INSERT INTO events (
                event_id, timestamp, received_at, config_id, policy_id,
                client_ip, hostname, path, method, status_code, user_agent,
                rule_id, rule_name, rule_message, rule_severity, rule_category,
                action, geo_country, geo_city, request_size, response_size,
                attack_type, threat_score
            ) VALUES (
                :event_id, :timestamp, :received_at, :config_id, :policy_id,
                :client_ip, :hostname, :path, :method, :status_code, :user_agent,
                :rule_id, :rule_name, :rule_message, :rule_severity, :rule_category,
                :action, :geo_country, :geo_city, :request_size, :response_size,
                :attack_type, :threat_score
            )
            ON CONFLICT (event_id) DO NOTHING
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public EventRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Idempotent insert of an enriched event.
     * Returns {@code true} if a row was inserted, {@code false} if the event_id already existed.
     */
    public boolean upsert(EnrichedEvent e) {
        GeoLocation geo = e.geoLocation();
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("event_id", e.eventId())
                .addValue("timestamp", toOffsetDateTime(e.timestamp()))
                .addValue("received_at", toOffsetDateTime(e.receivedAt()))
                .addValue("config_id", e.configId())
                .addValue("policy_id", e.policyId())
                .addValue("client_ip", e.clientIp())
                .addValue("hostname", e.hostname())
                .addValue("path", e.path())
                .addValue("method", e.method())
                .addValue("status_code", e.statusCode())
                .addValue("user_agent", e.userAgent())
                .addValue("rule_id", e.rule().id())
                .addValue("rule_name", e.rule().name())
                .addValue("rule_message", e.rule().message())
                .addValue("rule_severity", e.rule().severity().name())
                .addValue("rule_category", e.rule().category().name())
                .addValue("action", e.action().name())
                .addValue("geo_country", geo == null ? null : geo.country())
                .addValue("geo_city", geo == null ? null : geo.city())
                .addValue("request_size", e.requestSize())
                .addValue("response_size", e.responseSize())
                .addValue("attack_type", e.attackType())
                .addValue("threat_score", e.threatScore());

        return jdbc.update(UPSERT_SQL, params) > 0;
    }

    private static OffsetDateTime toOffsetDateTime(java.time.Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
