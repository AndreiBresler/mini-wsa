package com.akamai.miniwsa.enrichment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Component
public class RepeatOffenderTracker {

    private static final Logger log = LoggerFactory.getLogger(RepeatOffenderTracker.class);
    private static final String KEY_PREFIX = "wsa:ip:";

    private final StringRedisTemplate redis;
    private final Duration window;
    private final int threshold;

    public RepeatOffenderTracker(
            StringRedisTemplate redis,
            @Value("${miniwsa.enrichment.repeat-offender-window-seconds}") long windowSeconds,
            @Value("${miniwsa.enrichment.repeat-offender-threshold}") int threshold) {
        this.redis = redis;
        this.window = Duration.ofSeconds(windowSeconds);
        this.threshold = threshold;
    }

    public boolean recordAndCheck(String clientIp, Instant eventTime) {
        String key = KEY_PREFIX + clientIp;
        long nowMs = eventTime.toEpochMilli();
        long cutoffMs = nowMs - window.toMillis();
        try {
            redis.opsForZSet().removeRangeByScore(key, Double.NEGATIVE_INFINITY, cutoffMs);
            redis.opsForZSet().add(key, UUID.randomUUID().toString(), nowMs);
            redis.expire(key, window.plusSeconds(60));
            Long count = redis.opsForZSet().zCard(key);
            return count != null && count > threshold;
        } catch (Exception ex) {
            log.warn("Redis unavailable, skipping repeat-offender check for {}: {}", clientIp, ex.getMessage());
            return false;
        }
    }
}
