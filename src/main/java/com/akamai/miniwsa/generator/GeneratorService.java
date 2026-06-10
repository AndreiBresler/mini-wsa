package com.akamai.miniwsa.generator;

import com.akamai.miniwsa.domain.SecurityEvent;
import com.akamai.miniwsa.generator.dto.GenerateResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Service
@Profile({"dev", "all"})
public class GeneratorService {

    private static final Logger log = LoggerFactory.getLogger(GeneratorService.class);
    private static final String INGEST_PATH = "/v1/events/ingest";

    private final ScenarioLibrary library;
    private final RestClient restClient;

    public GeneratorService(ScenarioLibrary library,
                            @Value("${miniwsa.generator.ingest-url}") String ingestUrl) {
        this.library = library;
        this.restClient = RestClient.builder().baseUrl(ingestUrl).build();
    }

    public GenerateResponse generate(String scenarioName, int configId, Long seed,
                                     Boolean batchingOverride, Integer batchSizeOverride) {
        Scenario scenario = library.findByName(scenarioName);
        long actualSeed = seed != null ? seed : System.nanoTime();

        boolean batching = batchingOverride != null ? batchingOverride : scenario.defaultBatching();
        int batchSize = batchSizeOverride != null ? batchSizeOverride : scenario.defaultBatchSize();
        if (batchSize < 1) batchSize = 1;

        List<SecurityEvent> events = EventGenerator.generate(scenario, configId, actualSeed, Instant.now());

        long start = System.currentTimeMillis();
        int httpRequests = 0;
        int generated = 0;

        if (batching) {
            for (List<SecurityEvent> chunk : chunk(events, batchSize)) {
                if (postArray(chunk)) {
                    httpRequests++;
                    generated += chunk.size();
                }
            }
        } else {
            for (SecurityEvent event : events) {
                if (postSingle(event)) {
                    httpRequests++;
                    generated++;
                }
            }
        }

        long durationMs = System.currentTimeMillis() - start;
        int waveCount = scenario.waves().size();
        int singletonCount = scenario.count() - scenario.totalWaveSize();
        return new GenerateResponse(
                scenario.name(), generated, waveCount, singletonCount,
                httpRequests, batching, batchSize, durationMs, actualSeed
        );
    }

    private boolean postSingle(SecurityEvent event) {
        try {
            restClient.post()
                    .uri(INGEST_PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(event)
                    .retrieve()
                    .toBodilessEntity();
            return true;
        } catch (Exception e) {
            log.warn("Failed to POST single event {}: {}", event.eventId(), e.getMessage());
            return false;
        }
    }

    private boolean postArray(List<SecurityEvent> chunk) {
        try {
            restClient.post()
                    .uri(INGEST_PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(chunk)
                    .retrieve()
                    .toBodilessEntity();
            return true;
        } catch (Exception e) {
            log.warn("Failed to POST batch of {} events: {}", chunk.size(), e.getMessage());
            return false;
        }
    }

    private static <T> List<List<T>> chunk(List<T> items, int size) {
        List<List<T>> chunks = new ArrayList<>();
        for (int i = 0; i < items.size(); i += size) {
            chunks.add(items.subList(i, Math.min(i + size, items.size())));
        }
        return chunks;
    }
}