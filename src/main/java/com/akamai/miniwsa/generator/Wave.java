package com.akamai.miniwsa.generator;

import com.akamai.miniwsa.domain.Category;

public record Wave(
        int size,
        int durationSeconds,
        Category category,
        String targetPath,
        SeverityProfile severityProfile
) {
    public Wave {
        if (severityProfile == null) severityProfile = SeverityProfile.ESCALATING;
    }
}
