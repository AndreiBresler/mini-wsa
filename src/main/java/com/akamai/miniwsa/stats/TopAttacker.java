package com.akamai.miniwsa.stats;

public record TopAttacker(String clientIp, long count, double avgThreatScore) {
}
