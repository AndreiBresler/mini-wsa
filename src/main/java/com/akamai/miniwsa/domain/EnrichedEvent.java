package com.akamai.miniwsa.domain;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record EnrichedEvent(
        String eventId,
        Instant timestamp,
        Instant receivedAt,
        Integer configId,
        String policyId,
        String clientIp,
        String hostname,
        String path,
        String method,
        Integer statusCode,
        String userAgent,
        Rule rule,
        Action action,
        GeoLocation geoLocation,
        Long requestSize,
        Long responseSize,
        String attackType,
        int threatScore
) {
    public static EnrichedEvent from(SecurityEvent e, Instant receivedAt, String attackType, int threatScore) {
        return new EnrichedEvent(
                e.eventId(), e.timestamp(), receivedAt, e.configId(), e.policyId(),
                e.clientIp(), e.hostname(), e.path(), e.method(), e.statusCode(),
                e.userAgent(), e.rule(), e.action(), e.geoLocation(),
                e.requestSize(), e.responseSize(),
                attackType, threatScore
        );
    }
}
