package com.akamai.miniwsa.enrichment;

import com.akamai.miniwsa.domain.SecurityEvent;

public final class ThreatScoreCalculator {

    private static final int SENSITIVE_PATH_BONUS = 15;
    private static final int REPEAT_OFFENDER_BONUS = 15;
    private static final int MAX_SCORE = 100;

    private ThreatScoreCalculator() {
    }

    public static int calculate(SecurityEvent event, boolean isRepeatOffender) {
        int score = event.rule().severity().points() + event.action().points();
        if (hasSensitivePath(event.path())) {
            score += SENSITIVE_PATH_BONUS;
        }
        if (isRepeatOffender) {
            score += REPEAT_OFFENDER_BONUS;
        }
        return Math.min(score, MAX_SCORE);
    }

    private static boolean hasSensitivePath(String path) {
        if (path == null) {
            return false;
        }
        return path.contains("/admin") || path.contains("/login");
    }
}
