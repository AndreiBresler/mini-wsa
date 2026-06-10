package com.akamai.miniwsa.stats;

import com.akamai.miniwsa.BaseIntegrationTest;
import com.akamai.miniwsa.domain.Action;
import com.akamai.miniwsa.domain.Category;
import com.akamai.miniwsa.domain.EnrichedEvent;
import com.akamai.miniwsa.domain.GeoLocation;
import com.akamai.miniwsa.domain.Rule;
import com.akamai.miniwsa.domain.SecurityEvent;
import com.akamai.miniwsa.domain.Severity;
import com.akamai.miniwsa.storage.EventEntity;
import com.akamai.miniwsa.storage.EventJpaRepository;
import com.akamai.miniwsa.storage.EventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace.NONE;

@DataJpaTest
@AutoConfigureTestDatabase(replace = NONE)
@Import({StatsRepository.class, EventRepository.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class StatsRepositoryIntegrationTest extends BaseIntegrationTest {

    private static final Instant T0 = Instant.parse("2026-05-20T14:00:00Z");
    private static final Instant DAY_START = Instant.parse("2026-05-20T00:00:00Z");
    private static final Instant DAY_END   = Instant.parse("2026-05-21T00:00:00Z");
    private static final int CONFIG_ID = 14227;
    private static final int OTHER_CONFIG_ID = 99999;
    private static final int TOP_LIMIT = 10;

    @Autowired StatsRepository stats;
    @Autowired EventRepository events;
    @Autowired EventJpaRepository jpa;

    @BeforeEach
    void seedData() {
        jpa.deleteAll();

        // 3 INJECTION from 203.0.113.42 on /login, scores 75 each → avg 75.0
        events.upsert(event("evt-1", T0,                   "203.0.113.42", CONFIG_ID, "/api/v1/login", Severity.CRITICAL, Category.INJECTION, Action.DENY, 75));
        events.upsert(event("evt-2", T0.plusSeconds(30),   "203.0.113.42", CONFIG_ID, "/api/v1/login", Severity.CRITICAL, Category.INJECTION, Action.DENY, 75));
        events.upsert(event("evt-3", T0.plusSeconds(60),   "203.0.113.42", CONFIG_ID, "/api/v1/login", Severity.CRITICAL, Category.INJECTION, Action.DENY, 75));

        // 2 XSS from 198.51.100.5, scores 40 and 30 → avg 35.0
        events.upsert(event("evt-4", T0.plusSeconds(90),   "198.51.100.5", CONFIG_ID, "/search",       Severity.HIGH,    Category.XSS,       Action.ALERT, 40));
        events.upsert(event("evt-5", T0.plusSeconds(120),  "198.51.100.5", CONFIG_ID, "/comments",     Severity.MEDIUM,  Category.XSS,       Action.ALERT, 30));

        // 1 BOT from 192.0.2.100, score 10
        events.upsert(event("evt-6", T0.plusSeconds(150),  "192.0.2.100",  CONFIG_ID, "/robots.txt",   Severity.LOW,     Category.BOT,       Action.MONITOR, 10));

        // 1 event on a different config — should be excluded when filtering by CONFIG_ID
        events.upsert(event("evt-other", T0.plusSeconds(180), "1.1.1.1", OTHER_CONFIG_ID, "/elsewhere", Severity.MEDIUM, Category.PROTOCOL_VIOLATION, Action.MONITOR, 20));
    }

    @Test
    void countTotal_includes_only_configured_events_when_filtered() {
        long total = stats.countTotal(CONFIG_ID, DAY_START, DAY_END);
        assertThat(total).isEqualTo(6L);
    }

    @Test
    void countTotal_includes_all_events_when_configId_null() {
        long total = stats.countTotal(null, DAY_START, DAY_END);
        assertThat(total).isEqualTo(7L);
    }

    @Test
    void countTotal_excludes_events_outside_time_range() {
        long total = stats.countTotal(CONFIG_ID,
                Instant.parse("2026-06-01T00:00:00Z"),
                Instant.parse("2026-06-02T00:00:00Z"));
        assertThat(total).isZero();
    }

    @Test
    void byCategory_aggregates_count_and_average_per_category() {
        Map<Category, CategoryStats> result = stats.byCategory(CONFIG_ID, DAY_START, DAY_END);

        assertThat(result).containsOnlyKeys(Category.INJECTION, Category.XSS, Category.BOT);
        assertThat(result.get(Category.INJECTION).count()).isEqualTo(3L);
        assertThat(result.get(Category.INJECTION).avgThreatScore()).isEqualTo(75.0);
        assertThat(result.get(Category.XSS).count()).isEqualTo(2L);
        assertThat(result.get(Category.XSS).avgThreatScore()).isEqualTo(35.0);
        assertThat(result.get(Category.BOT).count()).isEqualTo(1L);
        assertThat(result.get(Category.BOT).avgThreatScore()).isEqualTo(10.0);
    }

    @Test
    void byAction_aggregates_counts_per_action() {
        Map<Action, Long> result = stats.byAction(CONFIG_ID, DAY_START, DAY_END);

        assertThat(result)
                .containsEntry(Action.DENY, 3L)
                .containsEntry(Action.ALERT, 2L)
                .containsEntry(Action.MONITOR, 1L);
    }

    @Test
    void topAttackers_orders_by_count_descending() {
        List<TopAttacker> top = stats.topAttackers(CONFIG_ID, DAY_START, DAY_END, TOP_LIMIT);

        assertThat(top).extracting(TopAttacker::clientIp)
                .containsExactly("203.0.113.42", "198.51.100.5", "192.0.2.100");
        assertThat(top.get(0).count()).isEqualTo(3L);
        assertThat(top.get(0).avgThreatScore()).isEqualTo(75.0);
    }

    @Test
    void topAttackers_respects_limit() {
        List<TopAttacker> top = stats.topAttackers(CONFIG_ID, DAY_START, DAY_END, 2);
        assertThat(top).hasSize(2);
    }

    @Test
    void topPaths_orders_by_count_descending_and_excludes_null_paths() {
        List<TopPath> top = stats.topPaths(CONFIG_ID, DAY_START, DAY_END, TOP_LIMIT);

        assertThat(top).extracting(TopPath::path)
                .containsExactly("/api/v1/login", "/comments", "/robots.txt", "/search");
        assertThat(top.get(0).count()).isEqualTo(3L);
    }

    @Test
    void aggregations_exclude_other_config_when_filtered() {
        Map<Category, CategoryStats> byCat = stats.byCategory(CONFIG_ID, DAY_START, DAY_END);
        assertThat(byCat).doesNotContainKey(Category.PROTOCOL_VIOLATION);

        Map<Category, CategoryStats> all = stats.byCategory(null, DAY_START, DAY_END);
        assertThat(all).containsKey(Category.PROTOCOL_VIOLATION);
    }

    private static EnrichedEvent event(String eventId, Instant timestamp, String clientIp, int configId,
                                       String path, Severity severity, Category category, Action action, int score) {
        SecurityEvent raw = new SecurityEvent(
                eventId, timestamp, configId, "pol_web1",
                clientIp, "www.example.com", path, "GET", 200, "UA",
                new Rule("r1", "name", "msg", severity, category),
                action, new GeoLocation("US", "Ashburn"), 256L, 256L
        );
        return EnrichedEvent.from(raw, Instant.now(), category.attackType(), score);
    }
}
