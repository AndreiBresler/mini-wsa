package com.akamai.miniwsa.enrichment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Tracks per-IP event timestamps in a Redis sorted set (ZSET) and exposes a
 * sliding-window "is this IP a repeat offender?" check.
 *
 * <p>Key:    {@value #KEY_PREFIX}{clientIp}
 * <p>Member: random UUID per event
 * <p>Score:  event timestamp in epoch milliseconds
 *
 * <p>The sliding-window semantic comes from ZREMRANGEBYSCORE evicting members
 * older than {@code now - window}. The Redis key TTL is a janitor only, so
 * keys for quiet IPs eventually disappear. The grace period extends the TTL
 * slightly past the window edge so a slow event near the boundary doesn't
 * see a missing key.
 */
@Component
@Profile({"consumer", "all"})
public class RepeatOffenderTracker {

    private static final Logger log = LoggerFactory.getLogger(RepeatOffenderTracker.class);
    private static final String KEY_PREFIX = "wsa:ip:";

    private final StringRedisTemplate redis;
    private final Duration window;
    private final Duration keyTtl;
    private final int threshold;

    public RepeatOffenderTracker(
            StringRedisTemplate redis,
            @Value("${miniwsa.enrichment.repeat-offender-window-seconds}") long windowSeconds,
            @Value("${miniwsa.enrichment.repeat-offender-threshold}") int threshold,
            @Value("${miniwsa.enrichment.repeat-offender-grace-seconds}") long graceSeconds) {
        this.redis = redis;
        this.window = Duration.ofSeconds(windowSeconds);
        this.keyTtl = this.window.plus(Duration.ofSeconds(graceSeconds));
        this.threshold = threshold;
    }

    /**
     * Records this event for the given IP, evicts entries older than the window,
     * and returns whether the IP has now exceeded the repeat-offender threshold.
     *
     * <p>Degrades gracefully: if Redis is unavailable, logs a warning and
     * returns {@code false} (no bonus applied, event still proceeds).
     */
    public boolean recordAndCheck(String clientIp, Instant eventTime) {
        String key = KEY_PREFIX + clientIp;
        long nowMs = eventTime.toEpochMilli();
        long windowStartMs = nowMs - window.toMillis();
        try {
            redis.opsForZSet().removeRangeByScore(key, Double.NEGATIVE_INFINITY, windowStartMs);
            redis.opsForZSet().add(key, UUID.randomUUID().toString(), nowMs);
            redis.expire(key, keyTtl);
            Long count = redis.opsForZSet().zCard(key);
            return count != null && count > threshold;
        } catch (Exception ex) {
            log.warn("Redis unavailable, skipping repeat-offender check for {}: {}", clientIp, ex.getMessage());
            return false;
        }
    }
}
