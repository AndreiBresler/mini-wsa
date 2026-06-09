package com.akamai.miniwsa.enrichment;

import com.akamai.miniwsa.domain.Action;
import com.akamai.miniwsa.domain.Category;
import com.akamai.miniwsa.domain.EnrichedEvent;
import com.akamai.miniwsa.domain.GeoLocation;
import com.akamai.miniwsa.domain.Rule;
import com.akamai.miniwsa.domain.SecurityEvent;
import com.akamai.miniwsa.domain.Severity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EnrichmentServiceTest {

    private static final Instant EVENT_TIME = Instant.parse("2026-05-20T14:32:10Z");
    private static final Instant RECEIVED_AT = Instant.parse("2026-05-20T14:32:11Z");
    private static final String CLIENT_IP = "203.0.113.42";

    @Mock RepeatOffenderTracker tracker;
    @InjectMocks EnrichmentService service;

    @Test
    void enriches_with_attack_type_score_and_received_at() {
        when(tracker.recordAndCheck(eq(CLIENT_IP), any())).thenReturn(false);
        SecurityEvent event = event(Severity.CRITICAL, Action.DENY, "/admin/users", Category.INJECTION);

        EnrichedEvent enriched = service.enrich(event, RECEIVED_AT);

        assertThat(enriched.attackType()).isEqualTo("SQL/Command Injection");
        assertThat(enriched.threatScore()).isEqualTo(75);
        assertThat(enriched.receivedAt()).isEqualTo(RECEIVED_AT);
        assertThat(enriched.eventId()).isEqualTo(event.eventId());
        assertThat(enriched.clientIp()).isEqualTo(CLIENT_IP);
    }

    @Test
    void repeat_offender_adds_bonus_to_score() {
        when(tracker.recordAndCheck(eq(CLIENT_IP), any())).thenReturn(true);
        SecurityEvent event = event(Severity.CRITICAL, Action.DENY, "/admin/users", Category.INJECTION);

        EnrichedEvent enriched = service.enrich(event, RECEIVED_AT);

        assertThat(enriched.threatScore()).isEqualTo(90);
    }

    @Test
    void xss_category_maps_to_cross_site_scripting() {
        when(tracker.recordAndCheck(eq(CLIENT_IP), any())).thenReturn(false);
        SecurityEvent event = event(Severity.HIGH, Action.ALERT, "/api/search", Category.XSS);

        EnrichedEvent enriched = service.enrich(event, RECEIVED_AT);

        assertThat(enriched.attackType()).isEqualTo("Cross-Site Scripting");
        assertThat(enriched.threatScore()).isEqualTo(40);
    }

    @Test
    void score_never_exceeds_100() {
        when(tracker.recordAndCheck(eq(CLIENT_IP), any())).thenReturn(true);
        SecurityEvent event = event(Severity.CRITICAL, Action.DENY, "/admin/login", Category.INJECTION);

        EnrichedEvent enriched = service.enrich(event, RECEIVED_AT);

        assertThat(enriched.threatScore()).isLessThanOrEqualTo(100);
    }

    private static SecurityEvent event(Severity severity, Action action, String path, Category category) {
        Rule rule = new Rule("950001", "TEST_RULE", "msg", severity, category);
        return new SecurityEvent(
                "evt-test-1", EVENT_TIME, 14227, "pol_web1",
                CLIENT_IP, "www.example.com", path, "POST", 403, "UA",
                rule, action, new GeoLocation("CN", "Beijing"), 1024L, 256L
        );
    }
}
