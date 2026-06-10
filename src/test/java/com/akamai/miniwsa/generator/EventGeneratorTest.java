package com.akamai.miniwsa.generator;

import com.akamai.miniwsa.domain.Action;
import com.akamai.miniwsa.domain.Category;
import com.akamai.miniwsa.domain.SecurityEvent;
import com.akamai.miniwsa.domain.Severity;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class EventGeneratorTest {

    private static final int CONFIG_ID = 14227;
    private static final Instant FIXED_NOW = Instant.parse("2026-06-10T12:00:00Z");

    @Test
    void produces_requested_count_of_events() {
        Scenario s = scenarioWithoutWaves(500);
        List<SecurityEvent> events = EventGenerator.generate(s, CONFIG_ID, 1L, Instant.now());
        assertThat(events).hasSize(500);
    }

    @Test
    void same_seed_produces_identical_output() {
        Scenario s = scenarioWithoutWaves(200);
        List<SecurityEvent> a = EventGenerator.generate(s, CONFIG_ID, 42L, FIXED_NOW);
        List<SecurityEvent> b = EventGenerator.generate(s, CONFIG_ID, 42L, FIXED_NOW);
        assertThat(a).usingRecursiveComparison().isEqualTo(b);
    }

    @Test
    void different_seeds_produce_different_output() {
        Scenario s = scenarioWithoutWaves(200);
        List<SecurityEvent> a = EventGenerator.generate(s, CONFIG_ID, 1L, FIXED_NOW);
        List<SecurityEvent> b = EventGenerator.generate(s, CONFIG_ID, 2L, FIXED_NOW);
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void wave_events_share_ip_and_path() {
        Wave wave = new Wave(10, 30, Category.INJECTION, "/api/v1/login", SeverityProfile.ESCALATING);
        Scenario s = new Scenario("t", "d", 50, 6, false, 1, List.of(wave));

        List<SecurityEvent> events = EventGenerator.generate(s, CONFIG_ID, 7L, FIXED_NOW);

        // At least 10 events hit the wave's target path. Singletons may
        // randomly draw this path too, so we assert >= wave.size(), not equality.
        long matchingPath = events.stream()
                .filter(e -> "/api/v1/login".equals(e.path()))
                .count();
        assertThat(matchingPath).isGreaterThanOrEqualTo(10);

        // Among the path-matched events, at least 10 share one client IP —
        // that's the wave attacker. Find it by max count, not by first()
        // (which is sort order dependent).
        Map<String, Long> ipCounts = events.stream()
                .filter(e -> "/api/v1/login".equals(e.path()))
                .collect(Collectors.groupingBy(SecurityEvent::clientIp, Collectors.counting()));
        long maxOnOneIp = ipCounts.values().stream().mapToLong(Long::longValue).max().orElse(0L);
        assertThat(maxOnOneIp).isGreaterThanOrEqualTo(10);
    }

    @Test
    void wave_events_span_configured_duration() {
        Wave wave = new Wave(10, 30, Category.INJECTION, "/api/v1/login", SeverityProfile.ESCALATING);
        Scenario s = new Scenario("t", "d", 10, 6, false, 1, List.of(wave));

        List<SecurityEvent> events = EventGenerator.generate(s, CONFIG_ID, 11L, FIXED_NOW);

        Instant min = events.stream().map(SecurityEvent::timestamp).min(Instant::compareTo).orElseThrow();
        Instant max = events.stream().map(SecurityEvent::timestamp).max(Instant::compareTo).orElseThrow();
        Duration span = Duration.between(min, max);
        assertThat(span.getSeconds()).isLessThanOrEqualTo(30);
    }

    @Test
    void wave_severity_profile_escalating_applied() {
        Wave wave = new Wave(10, 30, Category.INJECTION, "/api/v1/login", SeverityProfile.ESCALATING);
        Scenario s = new Scenario("t", "d", 10, 6, false, 1, List.of(wave));

        List<SecurityEvent> events = EventGenerator.generate(s, CONFIG_ID, 13L, FIXED_NOW);
        // After sorting by timestamp: first half HIGH, second half CRITICAL
        List<Severity> severities = events.stream().map(e -> e.rule().severity()).toList();
        assertThat(severities.subList(0, 5)).containsOnly(Severity.HIGH);
        assertThat(severities.subList(5, 10)).containsOnly(Severity.CRITICAL);
    }

    @Test
    void no_waves_scenario_produces_only_singletons() {
        Scenario s = scenarioWithoutWaves(500);
        List<SecurityEvent> events = EventGenerator.generate(s, CONFIG_ID, 17L, FIXED_NOW);
        // No IP should have 8 events within any 30-second window
        // (that would look like a wave; singletons spread over the window).
        Map<String, List<Instant>> byIp = new HashMap<>();
        for (SecurityEvent e : events) {
            byIp.computeIfAbsent(e.clientIp(), k -> new ArrayList<>()).add(e.timestamp());
        }
        for (Map.Entry<String, List<Instant>> entry : byIp.entrySet()) {
            List<Instant> sorted = entry.getValue().stream().sorted().toList();
            for (int i = 0; i + 8 <= sorted.size(); i++) {
                Duration window = Duration.between(sorted.get(i), sorted.get(i + 7));
                assertThat(window.getSeconds())
                        .withFailMessage("IP %s had 8 events within %ds (looks like a wave)",
                                entry.getKey(), window.getSeconds())
                        .isGreaterThan(30);
            }
        }
    }

    @Test
    void category_weights_roughly_honored_in_singletons() {
        Scenario s = scenarioWithoutWaves(10000);
        List<SecurityEvent> events = EventGenerator.generate(s, CONFIG_ID, 19L, FIXED_NOW);

        Map<Category, Long> counts = new HashMap<>();
        for (SecurityEvent e : events) {
            counts.merge(e.rule().category(), 1L, Long::sum);
        }

        assertCategoryWithin(counts, Category.BOT, 30, 5);
        assertCategoryWithin(counts, Category.INJECTION, 20, 5);
        assertCategoryWithin(counts, Category.XSS, 15, 5);
    }

    @Test
    void severity_action_pairs_are_consistent() {
        Scenario s = scenarioWithoutWaves(2000);
        List<SecurityEvent> events = EventGenerator.generate(s, CONFIG_ID, 23L, FIXED_NOW);
        for (SecurityEvent e : events) {
            Severity sev = e.rule().severity();
            Action act = e.action();
            if (sev == Severity.LOW) assertThat(act).isNotEqualTo(Action.DENY);
            if (sev == Severity.CRITICAL) assertThat(act).isNotEqualTo(Action.MONITOR);
        }
    }

    @Test
    void timestamps_within_window() {
        int hours = 6;
        Scenario s = new Scenario("t", "d", 500, hours, false, 1, List.of());
        Instant now = Instant.now();
        List<SecurityEvent> events = EventGenerator.generate(s, CONFIG_ID, 29L, now);

        Instant earliestAllowed = now.minusSeconds(hours * 3600L);
        for (SecurityEvent e : events) {
            assertThat(e.timestamp()).isAfterOrEqualTo(earliestAllowed);
            assertThat(e.timestamp()).isBeforeOrEqualTo(now);
        }
    }

    @Test
    void all_events_have_required_fields() {
        Scenario s = scenarioWithoutWaves(300);
        List<SecurityEvent> events = EventGenerator.generate(s, CONFIG_ID, 31L, FIXED_NOW);
        for (SecurityEvent e : events) {
            assertThat(e.eventId()).isNotBlank();
            assertThat(e.timestamp()).isNotNull();
            assertThat(e.configId()).isEqualTo(CONFIG_ID);
            assertThat(e.clientIp()).isNotBlank();
            assertThat(e.rule()).isNotNull();
            assertThat(e.rule().category()).isNotNull();
            assertThat(e.rule().severity()).isNotNull();
            assertThat(e.action()).isNotNull();
        }
    }

    private static Scenario scenarioWithoutWaves(int count) {
        return new Scenario("test", "test scenario", count, 24, false, 1, List.of());
    }

    private static void assertCategoryWithin(Map<Category, Long> counts, Category c,
                                             int expectedPercent, int tolerancePercent) {
        long total = counts.values().stream().mapToLong(Long::longValue).sum();
        double actualPercent = 100.0 * counts.getOrDefault(c, 0L) / total;
        assertThat(actualPercent)
                .withFailMessage("category %s: expected ~%d%%, got %.1f%%", c, expectedPercent, actualPercent)
                .isBetween((double) (expectedPercent - tolerancePercent),
                        (double) (expectedPercent + tolerancePercent));
    }
}