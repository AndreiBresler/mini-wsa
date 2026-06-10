package com.akamai.miniwsa.enrichment;

import com.akamai.miniwsa.domain.EnrichedEvent;
import com.akamai.miniwsa.domain.SecurityEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Service
@Profile({"consumer", "all"})
public class EnrichmentService {

    private final RepeatOffenderTracker tracker;

    public EnrichmentService(RepeatOffenderTracker tracker) {
        this.tracker = tracker;
    }

    public EnrichedEvent enrich(SecurityEvent event, Instant receivedAt) {
        boolean repeat = tracker.recordAndCheck(event.clientIp(), event.timestamp());
        int score = ThreatScoreCalculator.calculate(event, repeat);
        String attackType = event.rule().category().attackType();
        return EnrichedEvent.from(event, receivedAt, attackType, score);
    }
}
