package com.akamai.miniwsa.stats;

import com.akamai.miniwsa.domain.Action;
import com.akamai.miniwsa.domain.Category;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Service
@Profile({"query", "all"})
public class StatsService {

    /** Lower bound when caller omits ?from — covers any plausible event history. */
    private static final Instant DEFAULT_FROM = Instant.EPOCH;

    private final StatsRepository repository;
    private final int topLimit;

    public StatsService(StatsRepository repository,
                        @Value("${miniwsa.stats.top-limit}") int topLimit) {
        this.repository = repository;
        this.topLimit = topLimit;
    }

    public StatsSummary summarize(Integer configId, Instant from, Instant to) {
        Instant effectiveFrom = from != null ? from : DEFAULT_FROM;
        Instant effectiveTo = to != null ? to : Instant.now();

        long total = repository.countTotal(configId, effectiveFrom, effectiveTo);
        Map<Category, CategoryStats> byCategory = repository.byCategory(configId, effectiveFrom, effectiveTo);
        Map<Action, Long> byAction = repository.byAction(configId, effectiveFrom, effectiveTo);
        List<TopAttacker> topAttackers = repository.topAttackers(configId, effectiveFrom, effectiveTo, topLimit);
        List<TopPath> topPaths = repository.topPaths(configId, effectiveFrom, effectiveTo, topLimit);

        return new StatsSummary(
                configId,
                new TimeRange(effectiveFrom, effectiveTo),
                total,
                byCategory,
                byAction,
                topAttackers,
                topPaths
        );
    }
}