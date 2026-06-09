package com.akamai.miniwsa.stats;

public record AttackerAggregation(String clientIp, Long count, Double avgThreatScore) {
}
