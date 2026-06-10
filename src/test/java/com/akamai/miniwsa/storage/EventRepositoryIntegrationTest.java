package com.akamai.miniwsa.storage;

import com.akamai.miniwsa.BaseIntegrationTest;
import com.akamai.miniwsa.domain.Action;
import com.akamai.miniwsa.domain.Category;
import com.akamai.miniwsa.domain.EnrichedEvent;
import com.akamai.miniwsa.domain.GeoLocation;
import com.akamai.miniwsa.domain.Rule;
import com.akamai.miniwsa.domain.Severity;
import com.akamai.miniwsa.domain.SecurityEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace.NONE;

@DataJpaTest
@AutoConfigureTestDatabase(replace = NONE)
@Import(EventRepository.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class EventRepositoryIntegrationTest extends BaseIntegrationTest {

    @Autowired EventRepository repository;
    @Autowired EventJpaRepository jpa;

    @BeforeEach
    void cleanSlate() {
        jpa.deleteAll();
    }

    @Test
    void upsert_persists_all_fields() {
        EnrichedEvent event = enriched("evt-1", "203.0.113.42", 14227,
                Severity.CRITICAL, Category.INJECTION, Action.DENY, 90);

        boolean inserted = repository.upsert(event);

        assertThat(inserted).isTrue();
        assertThat(jpa.count()).isEqualTo(1);

        EventEntity stored = jpa.findAll().getFirst();
        assertThat(stored.getEventId()).isEqualTo("evt-1");
        assertThat(stored.getClientIp()).isEqualTo("203.0.113.42");
        assertThat(stored.getConfigId()).isEqualTo(14227);
        assertThat(stored.getRuleSeverity()).isEqualTo(Severity.CRITICAL);
        assertThat(stored.getRuleCategory()).isEqualTo(Category.INJECTION);
        assertThat(stored.getAction()).isEqualTo(Action.DENY);
        assertThat(stored.getAttackType()).isEqualTo("SQL/Command Injection");
        assertThat(stored.getThreatScore()).isEqualTo(90);
        assertThat(stored.getGeoCountry()).isEqualTo("RU");
        assertThat(stored.getGeoCity()).isEqualTo("Moscow");
    }

    @Test
    void upsert_with_duplicate_event_id_returns_false_and_does_not_insert() {
        EnrichedEvent event = enriched("evt-dup", "1.2.3.4", 14227,
                Severity.HIGH, Category.XSS, Action.ALERT, 40);

        boolean firstInsert = repository.upsert(event);
        boolean secondInsert = repository.upsert(event);

        assertThat(firstInsert).isTrue();
        assertThat(secondInsert).isFalse();
        assertThat(jpa.count()).isEqualTo(1);
    }

    @Test
    void upsert_persists_distinct_event_ids_separately() {
        repository.upsert(enriched("evt-a", "1.1.1.1", 14227, Severity.LOW, Category.BOT, Action.MONITOR, 10));
        repository.upsert(enriched("evt-b", "2.2.2.2", 14227, Severity.MEDIUM, Category.DOS, Action.ALERT, 30));
        repository.upsert(enriched("evt-c", "3.3.3.3", 14227, Severity.CRITICAL, Category.DATA_LEAKAGE, Action.DENY, 60));

        assertThat(jpa.count()).isEqualTo(3);
    }

    @Test
    void upsert_handles_null_geolocation() {
        SecurityEvent raw = new SecurityEvent(
                "evt-no-geo", Instant.parse("2026-05-20T14:32:10Z"), 14227, null,
                "5.6.7.8", null, null, null, null, null,
                new Rule("r1", "n1", null, Severity.LOW, Category.BOT),
                Action.MONITOR, null, null, null
        );
        EnrichedEvent enriched = EnrichedEvent.from(raw, Instant.now(), "Bot Activity", 10);

        boolean inserted = repository.upsert(enriched);

        assertThat(inserted).isTrue();
        EventEntity stored = jpa.findAll().getFirst();
        assertThat(stored.getGeoCountry()).isNull();
        assertThat(stored.getGeoCity()).isNull();
    }

    private static EnrichedEvent enriched(String eventId, String clientIp, int configId,
                                          Severity severity, Category category, Action action, int score) {
        SecurityEvent raw = new SecurityEvent(
                eventId,
                Instant.parse("2026-05-20T14:32:10Z"),
                configId,
                "pol_web1",
                clientIp,
                "www.example.com",
                "/api/v1/login",
                "POST",
                403,
                "sqlmap/1.7",
                new Rule("950001", "SQLI", "msg", severity, category),
                action,
                new GeoLocation("RU", "Moscow"),
                1024L,
                256L
        );
        return EnrichedEvent.from(raw, Instant.parse("2026-05-20T14:32:11Z"), category.attackType(), score);
    }
}
