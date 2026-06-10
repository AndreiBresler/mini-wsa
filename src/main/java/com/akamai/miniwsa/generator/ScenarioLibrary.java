package com.akamai.miniwsa.generator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
@Profile({"dev", "all"})
public class ScenarioLibrary {

    private static final Logger log = LoggerFactory.getLogger(ScenarioLibrary.class);

    private final ResourceLoader resourceLoader;
    private final String scenariosFile;
    private final int maxCount;

    private final Map<String, Scenario> scenarios = new HashMap<>();

    public ScenarioLibrary(ResourceLoader resourceLoader,
                           @Value("${miniwsa.generator.scenarios-file}") String scenariosFile,
                           @Value("${miniwsa.generator.max-count}") int maxCount) {
        this.resourceLoader = resourceLoader;
        this.scenariosFile = scenariosFile;
        this.maxCount = maxCount;
    }

    @PostConstruct
    void load() {
        Resource resource = resourceLoader.getResource(scenariosFile);
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        ScenarioFile parsed;
        try (InputStream in = resource.getInputStream()) {
            parsed = mapper.readValue(in, ScenarioFile.class);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load scenarios from " + scenariosFile, e);
        }
        if (parsed.scenarios() == null || parsed.scenarios().isEmpty()) {
            throw new IllegalStateException("No scenarios defined in " + scenariosFile);
        }

        Set<String> seenNames = new HashSet<>();
        for (Scenario s : parsed.scenarios()) {
            validate(s, seenNames);
            scenarios.put(s.name(), s);
        }

        log.info("Loaded {} generator scenarios: {}", scenarios.size(), scenarios.keySet());
    }

    public Scenario findByName(String name) {
        Scenario s = scenarios.get(name);
        if (s == null) {
            throw new UnknownScenarioException(name, scenarios.keySet());
        }
        return s;
    }

    public List<Scenario> all() {
        return List.copyOf(scenarios.values());
    }

    private void validate(Scenario s, Set<String> seenNames) {
        if (s.name() == null || s.name().isBlank()) {
            throw new IllegalStateException("Scenario name must not be blank");
        }
        if (!seenNames.add(s.name())) {
            throw new IllegalStateException("Duplicate scenario name: " + s.name());
        }
        if (s.count() <= 0) {
            throw new IllegalStateException("Scenario '" + s.name() + "': count must be > 0");
        }
        if (s.count() > maxCount) {
            throw new IllegalStateException(
                    "Scenario '" + s.name() + "': count=" + s.count() + " exceeds max-count=" + maxCount);
        }
        if (s.windowHours() <= 0) {
            throw new IllegalStateException("Scenario '" + s.name() + "': windowHours must be > 0");
        }
        int waveSum = s.totalWaveSize();
        if (waveSum > s.count()) {
            throw new IllegalStateException(
                    "Scenario '" + s.name() + "': sum of wave sizes (" + waveSum + ") exceeds count (" + s.count() + ")");
        }
        for (Wave w : s.waves()) {
            if (w.size() < 2) {
                throw new IllegalStateException("Scenario '" + s.name() + "': wave size must be >= 2");
            }
            if (w.durationSeconds() <= 0) {
                throw new IllegalStateException("Scenario '" + s.name() + "': wave durationSeconds must be > 0");
            }
            if (w.category() == null) {
                throw new IllegalStateException("Scenario '" + s.name() + "': wave category is required");
            }
        }
    }

    public record ScenarioFile(List<Scenario> scenarios) {
    }

    public static class UnknownScenarioException extends RuntimeException {
        public UnknownScenarioException(String requested, Set<String> available) {
            super("Unknown scenario: '" + requested + "'. Available: " + available);
        }
    }
}
