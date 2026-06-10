package com.akamai.miniwsa.storage;

import com.akamai.miniwsa.BaseIntegrationTest;
import com.akamai.miniwsa.domain.Action;
import com.akamai.miniwsa.domain.Category;
import com.akamai.miniwsa.domain.EnrichedEvent;
import com.akamai.miniwsa.domain.GeoLocation;
import com.akamai.miniwsa.domain.Rule;
import com.akamai.miniwsa.domain.SecurityEvent;
import com.akamai.miniwsa.domain.Severity;
import com.akamai.miniwsa.storage.EventRepository.SamplesResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace.NONE;

@DataJpaTest
@AutoConfigureTestDatabase(replace = NONE)
@Import(EventRepository.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class EventRepositoryFindSamplesIntegrationTest extends BaseIntegrationTest {

    private static final Sort TIMESTAMP_DESC = Sort.by(Sort.Direction.DESC, "timestamp");
    private static final Instant DAY_START = Instant.parse("2026-05-20T00:00:00Z");
    private static final Instant DAY_END   = Instant.parse("2026-05-21T00:00:00Z");

    @Autowired EventRepository repository;
    @Autowired EventJpaRepository jpa;

    @BeforeEach
    void seed() {
        jpa.deleteAll();

        Instant t0 = Instant.parse("2026-05-20T10:00:00Z");
        repository.upsert(event("s-1", t0,                  "1.1.1.1", 14227, "/api/v1/login", Category.INJECTION, Action.DENY,    Severity.CRITICAL, 75));
        repository.upsert(event("s-2", t0.plusSeconds(60),  "1.1.1.1", 14227, "/admin",        Category.INJECTION, Action.DENY,    Severity.HIGH,     65));
        repository.upsert(event("s-3", t0.plusSeconds(120), "2.2.2.2", 14227, "/search",       Category.XSS,       Action.ALERT,   Severity.HIGH,     40));
        repository.upsert(event("s-4", t0.plusSeconds(180), "3.3.3.3", 14227, "/robots.txt",   Category.BOT,       Action.MONITOR, Severity.LOW,      10));
        repository.upsert(event("s-5", t0.plusSeconds(240), "4.4.4.4", 99999, "/whatever",     Category.DOS,       Action.ALERT,   Severity.MEDIUM,   30));
    }

    @Test
    void unfiltered_returns_all_rows_sorted_desc_by_timestamp() {
        OffsetPageable pageable = new OffsetPageable(0, 100, TIMESTAMP_DESC);

        SamplesResult result = repository.findSamples(null, null, null, null, null, pageable);

        assertThat(result.totalCount()).isEqualTo(5L);
        assertThat(result.samples()).extracting(EnrichedEvent::eventId)
                .containsExactly("s-5", "s-4", "s-3", "s-2", "s-1");
    }

    @Test
    void filter_by_configId() {
        OffsetPageable pageable = new OffsetPageable(0, 100, TIMESTAMP_DESC);

        SamplesResult result = repository.findSamples(14227, null, null, null, null, pageable);

        assertThat(result.totalCount()).isEqualTo(4L);
        assertThat(result.samples()).extracting(EnrichedEvent::eventId)
                .doesNotContain("s-5");
    }

    @Test
    void filter_by_time_range_is_half_open() {
        OffsetPageable pageable = new OffsetPageable(0, 100, TIMESTAMP_DESC);
        Instant from = Instant.parse("2026-05-20T10:01:00Z");
        Instant to   = Instant.parse("2026-05-20T10:03:00Z");

        SamplesResult result = repository.findSamples(null, from, to, null, null, pageable);

        // s-2 at 10:01:00 (inclusive), s-3 at 10:02:00, s-4 at 10:03:00 (exclusive — half-open)
        assertThat(result.samples()).extracting(EnrichedEvent::eventId)
                .containsExactly("s-3", "s-2");
    }

    @Test
    void filter_by_category() {
        OffsetPageable pageable = new OffsetPageable(0, 100, TIMESTAMP_DESC);

        SamplesResult result = repository.findSamples(null, null, null, Category.INJECTION, null, pageable);

        assertThat(result.samples()).extracting(EnrichedEvent::eventId)
                .containsExactly("s-2", "s-1");
    }

    @Test
    void filter_by_action() {
        OffsetPageable pageable = new OffsetPageable(0, 100, TIMESTAMP_DESC);

        SamplesResult result = repository.findSamples(null, null, null, null, Action.MONITOR, pageable);

        assertThat(result.samples()).hasSize(1);
        assertThat(result.samples().getFirst().eventId()).isEqualTo("s-4");
    }

    @Test
    void multiple_filters_compose_with_AND() {
        OffsetPageable pageable = new OffsetPageable(0, 100, TIMESTAMP_DESC);

        SamplesResult result = repository.findSamples(
                14227, null, null, Category.INJECTION, Action.DENY, pageable);

        assertThat(result.samples()).extracting(EnrichedEvent::eventId)
                .containsExactly("s-2", "s-1");
    }

    @Test
    void pagination_with_offset_limit() {
        OffsetPageable pageable = new OffsetPageable(2, 2, TIMESTAMP_DESC);

        SamplesResult result = repository.findSamples(null, null, null, null, null, pageable);

        // Total is still all 5; content is the 3rd and 4th items in DESC order
        assertThat(result.totalCount()).isEqualTo(5L);
        assertThat(result.samples()).extracting(EnrichedEvent::eventId)
                .containsExactly("s-3", "s-2");
    }

    @Test
    void domain_mapping_round_trip_preserves_fields() {
        OffsetPageable pageable = new OffsetPageable(0, 1, TIMESTAMP_DESC);

        SamplesResult result = repository.findSamples(
                null, null, null, Category.INJECTION, null, pageable);

        EnrichedEvent sample = result.samples().getFirst();
        assertThat(sample.eventId()).isEqualTo("s-2");
        assertThat(sample.rule().severity()).isEqualTo(Severity.HIGH);
        assertThat(sample.rule().category()).isEqualTo(Category.INJECTION);
        assertThat(sample.action()).isEqualTo(Action.DENY);
        assertThat(sample.attackType()).isEqualTo("SQL/Command Injection");
        assertThat(sample.threatScore()).isEqualTo(65);
        assertThat(sample.geoLocation()).isNotNull();
        assertThat(sample.geoLocation().country()).isEqualTo("US");
    }

    @Test
    void empty_result_returns_zero_total_and_empty_list() {
        OffsetPageable pageable = new OffsetPageable(0, 100, TIMESTAMP_DESC);

        SamplesResult result = repository.findSamples(
                42, null, null, null, null, pageable);

        assertThat(result.totalCount()).isZero();
        assertThat(result.samples()).isEmpty();
    }

    private static EnrichedEvent event(String eventId, Instant timestamp, String clientIp, int configId,
                                       String path, Category category, Action action, Severity severity, int score) {
        SecurityEvent raw = new SecurityEvent(
                eventId, timestamp, configId, "pol_web1",
                clientIp, "www.example.com", path, "GET", 200, "UA",
                new Rule("r1", "name", "msg", severity, category),
                action, new GeoLocation("US", "Ashburn"), 256L, 256L
        );
        return EnrichedEvent.from(raw, Instant.now(), category.attackType(), score);
    }
}
