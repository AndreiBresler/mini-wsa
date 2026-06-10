package com.akamai.miniwsa.generator;

import java.util.List;

public record Scenario(
        String name,
        String description,
        int count,
        int windowHours,
        boolean defaultBatching,
        int defaultBatchSize,
        List<Wave> waves
) {
    public Scenario {
        if (windowHours == 0) windowHours = 24;
        if (defaultBatchSize == 0) defaultBatchSize = 1;
        if (waves == null) waves = List.of();
    }

    public int totalWaveSize() {
        return waves.stream().mapToInt(Wave::size).sum();
    }
}
