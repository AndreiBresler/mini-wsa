package com.akamai.miniwsa.stats;

import com.akamai.miniwsa.domain.Category;

public record CategoryAggregation(Category ruleCategory, Long count, Double avgThreatScore) {
}
