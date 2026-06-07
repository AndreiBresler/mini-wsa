package com.akamai.miniwsa.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record SecurityEvent(
        @NotBlank String eventId,
        @NotNull Instant timestamp,
        @NotNull Integer configId,
        String policyId,
        @NotBlank String clientIp,
        String hostname,
        String path,
        String method,
        Integer statusCode,
        String userAgent,
        @NotNull @Valid Rule rule,
        @NotNull Action action,
        GeoLocation geoLocation,
        Long requestSize,
        Long responseSize
) {
}
