package com.akamai.miniwsa.api.dto;

import com.akamai.miniwsa.domain.EnrichedEvent;

import java.util.List;

public record SamplesResponse(
        long totalCount,
        int limit,
        int offset,
        List<EnrichedEvent> samples
) {
}
