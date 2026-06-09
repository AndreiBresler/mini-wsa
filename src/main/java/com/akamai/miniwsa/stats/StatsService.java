package com.akamai.miniwsa.stats;

import com.akamai.miniwsa.domain.Action;
import com.akamai.miniwsa.domain.Category;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Service
public class StatsService {

    private final StatsRepository repository;
    private final int topLimit;

    public StatsService(StatsRepository repository,
                        @Value("${miniwsa.stats.top-limit}") int topLimit) {
        this.repository = repository;
        this.topLimit = topLimit;
    }

    public StatsSummary summarize(Integer configId, Instant from, Instant to) {
        long total = repository.countTotal(configId, from, to);
        Map<Category, CategoryStats> byCategory = repository.byCategory(configId, from, to);
        Map<Action, Long> byAction = repository.byAction(configId, from, to);
        List<TopAttacker> topAttackers = repository.topAttackers(configId, from, to, topLimit);
        List<TopPath> topPaths = repository.topPaths(configId, from, to, topLimit);

        return new StatsSummary(
                configId,
                new TimeRange(from, to),
                total,
                byCategory,
                byAction,
                topAttackers,
                topPaths
        );
    }
}
