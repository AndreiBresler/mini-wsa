package com.akamai.miniwsa.stats;

import com.akamai.miniwsa.domain.Action;
import com.akamai.miniwsa.domain.Category;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StatsServiceTest {

    private static final Instant FROM = Instant.parse("2026-05-20T00:00:00Z");
    private static final Instant TO   = Instant.parse("2026-05-21T00:00:00Z");
    private static final int TOP_LIMIT = 10;

    @Mock StatsRepository repository;
    StatsService service;

    @BeforeEach
    void setUp() {
        service = new StatsService(repository, TOP_LIMIT);
    }

    @Test
    void aggregates_all_parts_for_a_given_config() {
        Integer configId = 14227;
        when(repository.countTotal(configId, FROM, TO)).thenReturn(100L);
        when(repository.byCategory(configId, FROM, TO))
                .thenReturn(Map.of(Category.INJECTION, new CategoryStats(50L, 75.5)));
        when(repository.byAction(configId, FROM, TO))
                .thenReturn(Map.of(Action.DENY, 60L, Action.ALERT, 40L));
        when(repository.topAttackers(configId, FROM, TO, TOP_LIMIT))
                .thenReturn(List.of(new TopAttacker("203.0.113.42", 30L, 80.0)));
        when(repository.topPaths(configId, FROM, TO, TOP_LIMIT))
                .thenReturn(List.of(new TopPath("/admin", 25L)));

        StatsSummary summary = service.summarize(configId, FROM, TO);

        assertThat(summary.configId()).isEqualTo(configId);
        assertThat(summary.timeRange()).isEqualTo(new TimeRange(FROM, TO));
        assertThat(summary.totalEvents()).isEqualTo(100L);
        assertThat(summary.byCategory()).containsEntry(Category.INJECTION, new CategoryStats(50L, 75.5));
        assertThat(summary.byAction()).containsEntry(Action.DENY, 60L).containsEntry(Action.ALERT, 40L);
        assertThat(summary.topAttackers()).hasSize(1);
        assertThat(summary.topTargetedPaths()).hasSize(1);
    }

    @Test
    void omitted_config_id_passes_null_through_to_repository() {
        when(repository.countTotal(null, FROM, TO)).thenReturn(5L);
        when(repository.byCategory(null, FROM, TO)).thenReturn(Map.of());
        when(repository.byAction(null, FROM, TO)).thenReturn(Map.of());
        when(repository.topAttackers(null, FROM, TO, TOP_LIMIT)).thenReturn(List.of());
        when(repository.topPaths(null, FROM, TO, TOP_LIMIT)).thenReturn(List.of());

        StatsSummary summary = service.summarize(null, FROM, TO);

        assertThat(summary.configId()).isNull();
        assertThat(summary.totalEvents()).isEqualTo(5L);
        assertThat(summary.byCategory()).isEmpty();
    }

    @Test
    void uses_configured_top_limit_when_calling_top_queries() {
        int customLimit = 25;
        StatsService customService = new StatsService(repository, customLimit);
        when(repository.countTotal(null, FROM, TO)).thenReturn(0L);
        when(repository.byCategory(null, FROM, TO)).thenReturn(Map.of());
        when(repository.byAction(null, FROM, TO)).thenReturn(Map.of());
        when(repository.topAttackers(null, FROM, TO, customLimit)).thenReturn(List.of());
        when(repository.topPaths(null, FROM, TO, customLimit)).thenReturn(List.of());

        customService.summarize(null, FROM, TO);

        verify(repository).topAttackers(null, FROM, TO, customLimit);
        verify(repository).topPaths(null, FROM, TO, customLimit);
    }
}
