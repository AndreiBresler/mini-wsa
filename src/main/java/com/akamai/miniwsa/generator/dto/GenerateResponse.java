package com.akamai.miniwsa.generator.dto;

public record GenerateResponse(
        String scenario,
        int generated,
        int waveCount,
        int singletonCount,
        int httpRequests,
        boolean batching,
        int batchSize,
        long durationMs,
        long seed
) {
}
