package com.akamai.miniwsa.stats;

import com.akamai.miniwsa.domain.Action;

public record ActionAggregation(Action action, Long count) {
}
