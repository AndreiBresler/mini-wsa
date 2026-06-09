package com.akamai.miniwsa.stats;

import com.akamai.miniwsa.domain.Action;
import com.akamai.miniwsa.domain.Category;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Facade over {@link StatsJpaRepository}. Maps record-based aggregations
 * (returned by JPQL constructor expressions) into the domain-friendly DTOs
 * that {@link StatsService} consumes.
 *
 * <p>Rounding (avg threat score → 2 decimals) lives here, not in the query,
 * so the query stays portable across SQL databases.
 */
@Repository
@Transactional(readOnly = true)
public class StatsRepository {

    private static final double ROUND_FACTOR = 100.0;

    private final StatsJpaRepository jpa;

    public StatsRepository(StatsJpaRepository jpa) {
        this.jpa = jpa;
    }

    public long countTotal(Integer configId, Instant from, Instant to) {
        return jpa.countTotal(configId, from, to);
    }

    public Map<Category, CategoryStats> byCategory(Integer configId, Instant from, Instant to) {
        List<CategoryAggregation> rows = jpa.aggregateByCategory(configId, from, to);
        Map<Category, CategoryStats> result = new EnumMap<>(Category.class);
        for (CategoryAggregation row : rows) {
            result.put(row.ruleCategory(), new CategoryStats(row.count(), round(row.avgThreatScore())));
        }
        return result;
    }

    public Map<Action, Long> byAction(Integer configId, Instant from, Instant to) {
        List<ActionAggregation> rows = jpa.aggregateByAction(configId, from, to);
        Map<Action, Long> result = new EnumMap<>(Action.class);
        for (ActionAggregation row : rows) {
            result.put(row.action(), row.count());
        }
        return result;
    }

    public List<TopAttacker> topAttackers(Integer configId, Instant from, Instant to, int limit) {
        Pageable page = PageRequest.ofSize(limit);
        return jpa.topAttackers(configId, from, to, page).stream()
                .map(a -> new TopAttacker(a.clientIp(), a.count(), round(a.avgThreatScore())))
                .toList();
    }

    public List<TopPath> topPaths(Integer configId, Instant from, Instant to, int limit) {
        Pageable page = PageRequest.ofSize(limit);
        return jpa.topPaths(configId, from, to, page).stream()
                .map(p -> new TopPath(p.path(), p.count()))
                .toList();
    }

    private static double round(Double value) {
        if (value == null) {
            return 0.0;
        }
        return Math.round(value * ROUND_FACTOR) / ROUND_FACTOR;
    }
}
