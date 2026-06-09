package com.akamai.miniwsa.stats;

import com.akamai.miniwsa.domain.Action;
import com.akamai.miniwsa.domain.Category;

import java.util.List;
import java.util.Map;

public record StatsSummary(
        Integer configId,
        TimeRange timeRange,
        long totalEvents,
        Map<Category, CategoryStats> byCategory,
        Map<Action, Long> byAction,
        List<TopAttacker> topAttackers,
        List<TopPath> topTargetedPaths
) {
}
