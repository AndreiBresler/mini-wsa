package com.akamai.miniwsa.generator;

import com.akamai.miniwsa.domain.Action;
import com.akamai.miniwsa.domain.GeoLocation;
import com.akamai.miniwsa.domain.Rule;
import com.akamai.miniwsa.domain.SecurityEvent;
import com.akamai.miniwsa.domain.Severity;
import com.akamai.miniwsa.generator.GeneratorConstants.Geo;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

/**
 * Pure deterministic event generator. No Spring, no Kafka, no HTTP.
 * Same inputs always produce the same output.
 *
 * <p>The {@code now} parameter is the wall-clock anchor for all timestamps.
 * Callers pass {@code Instant.now()} in production; tests pass a fixed
 * {@code Instant} to keep output reproducible across runs.
 */
public final class EventGenerator {

    private static final int WAVE_PLACEMENT_ATTEMPTS = 100;
    private static final String EVENT_ID_FORMAT = "gen-%d-%d";

    private EventGenerator() {
    }

    public static List<SecurityEvent> generate(Scenario scenario, int configId, long seed, Instant now) {
        Random random = new Random(seed);
        long windowSeconds = scenario.windowHours() * 3600L;

        List<WavePlacement> placements = placeWaves(scenario.waves(), windowSeconds, random);
        List<SecurityEvent> raw = new ArrayList<>(scenario.count());

        for (int i = 0; i < placements.size(); i++) {
            emitWave(raw, placements.get(i), now, configId, random);
        }
        int singletonCount = scenario.count() - scenario.totalWaveSize();
        for (int i = 0; i < singletonCount; i++) {
            raw.add(buildSingleton(random, now, windowSeconds, configId));
        }

        raw.sort(Comparator.comparing(SecurityEvent::timestamp));

        List<SecurityEvent> result = new ArrayList<>(raw.size());
        for (int i = 0; i < raw.size(); i++) {
            result.add(withEventId(raw.get(i), String.format(EVENT_ID_FORMAT, seed, i)));
        }
        return result;
    }

    // ---------- wave placement ----------

    private record WavePlacement(Wave wave, long offsetSecondsFromNow) {
    }

    private static List<WavePlacement> placeWaves(List<Wave> waves, long windowSeconds, Random random) {
        List<WavePlacement> placed = new ArrayList<>();
        for (Wave w : waves) {
            long offset = pickWaveOffset(w, windowSeconds, placed, random);
            placed.add(new WavePlacement(w, offset));
        }
        return placed;
    }

    private static long pickWaveOffset(Wave wave, long windowSeconds,
                                       List<WavePlacement> placed, Random random) {
        long maxStart = Math.max(1, windowSeconds - wave.durationSeconds());
        for (int attempt = 0; attempt < WAVE_PLACEMENT_ATTEMPTS; attempt++) {
            long candidate = (long) (random.nextDouble() * maxStart);
            if (!overlaps(candidate, wave.durationSeconds(), placed)) {
                return candidate;
            }
        }
        return (long) (random.nextDouble() * maxStart);
    }

    private static boolean overlaps(long startSeconds, long durationSeconds, List<WavePlacement> placed) {
        long end = startSeconds + durationSeconds;
        for (WavePlacement other : placed) {
            long otherEnd = other.offsetSecondsFromNow() + other.wave().durationSeconds();
            if (startSeconds < otherEnd && end > other.offsetSecondsFromNow()) {
                return true;
            }
        }
        return false;
    }

    // ---------- wave emission ----------

    private static void emitWave(List<SecurityEvent> out, WavePlacement placement,
                                 Instant now, int configId, Random random) {
        Wave wave = placement.wave();
        String attackerIp = pick(GeneratorConstants.BAD_ACTOR_IPS, random);
        String targetPath = wave.targetPath() != null
                ? wave.targetPath()
                : pick(GeneratorConstants.SENSITIVE_PATHS, random);
        Geo geo = pickGeo(random);
        String userAgent = pick(GeneratorConstants.USER_AGENTS, random);

        Instant waveStart = now.minusSeconds(placement.offsetSecondsFromNow() + wave.durationSeconds());
        long gapMillis = (wave.size() > 1)
                ? (wave.durationSeconds() * 1000L) / (wave.size() - 1)
                : 0L;

        for (int i = 0; i < wave.size(); i++) {
            Instant ts = waveStart.plusMillis(gapMillis * i);
            Severity severity = severityFor(wave.severityProfile(), i, wave.size(), random);
            Action action = pickAction(severity, random);
            Rule rule = new Rule(
                    GeneratorConstants.RULE_ID_BY_CATEGORY.get(wave.category()),
                    GeneratorConstants.RULE_NAME_BY_CATEGORY.get(wave.category()),
                    "Detected " + wave.category().name(),
                    severity,
                    wave.category()
            );
            out.add(new SecurityEvent(
                    null, ts, configId, "pol_web1",
                    attackerIp, "www.example.com", targetPath,
                    pick(GeneratorConstants.HTTP_METHODS, random), 403, userAgent,
                    rule, action, new GeoLocation(geo.country(), geo.city()), 1024L, 256L
            ));
        }
    }

    private static Severity severityFor(SeverityProfile profile, int index, int waveSize, Random random) {
        return switch (profile) {
            case CRITICAL -> Severity.CRITICAL;
            case HIGH -> Severity.HIGH;
            case MIXED -> weighted(GeneratorConstants.SEVERITIES, GeneratorConstants.SEVERITY_WEIGHTS, random);
            case ESCALATING -> (index < waveSize / 2) ? Severity.HIGH : Severity.CRITICAL;
        };
    }

    // ---------- singleton emission ----------

    private static SecurityEvent buildSingleton(Random random, Instant now, long windowSeconds, int configId) {
        com.akamai.miniwsa.domain.Category category = weighted(GeneratorConstants.CATEGORIES, GeneratorConstants.CATEGORY_WEIGHTS, random);
        Severity severity = weighted(GeneratorConstants.SEVERITIES, GeneratorConstants.SEVERITY_WEIGHTS, random);
        Action action = pickAction(severity, random);
        Geo geo = pickGeo(random);

        double r = random.nextDouble();
        long secondsBack = (long) (r * r * windowSeconds);
        Instant ts = now.minusSeconds(secondsBack);

        Rule rule = new Rule(
                GeneratorConstants.RULE_ID_BY_CATEGORY.get(category),
                GeneratorConstants.RULE_NAME_BY_CATEGORY.get(category),
                "Detected " + category.name(),
                severity,
                category
        );
        return new SecurityEvent(
                null, ts, configId, "pol_web1",
                pick(GeneratorConstants.CLIENT_IPS, random),
                "www.example.com",
                pick(GeneratorConstants.PATHS, random),
                pick(GeneratorConstants.HTTP_METHODS, random),
                200, pick(GeneratorConstants.USER_AGENTS, random),
                rule, action, new GeoLocation(geo.country(), geo.city()),
                512L, 256L
        );
    }

    // ---------- pickers ----------

    private static Action pickAction(Severity severity, Random random) {
        int[] weights = GeneratorConstants.ACTION_WEIGHTS_BY_SEVERITY.get(severity);
        return weighted(GeneratorConstants.ACTIONS_ORDER, weights, random);
    }

    private static Geo pickGeo(Random random) {
        return weighted(GeneratorConstants.GEO_COUNTRIES, GeneratorConstants.GEO_WEIGHTS, random);
    }

    private static <T> T pick(List<T> from, Random random) {
        return from.get(random.nextInt(from.size()));
    }

    private static <T> T weighted(List<T> values, int[] weights, Random random) {
        int total = 0;
        for (int w : weights) total += w;
        int draw = random.nextInt(total);
        int cumulative = 0;
        for (int i = 0; i < weights.length; i++) {
            cumulative += weights[i];
            if (draw < cumulative) return values.get(i);
        }
        return values.get(values.size() - 1);
    }

    private static SecurityEvent withEventId(SecurityEvent e, String eventId) {
        return new SecurityEvent(
                eventId, e.timestamp(), e.configId(), e.policyId(),
                e.clientIp(), e.hostname(), e.path(), e.method(),
                e.statusCode(), e.userAgent(), e.rule(), e.action(),
                e.geoLocation(), e.requestSize(), e.responseSize()
        );
    }
}