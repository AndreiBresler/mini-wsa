package com.akamai.miniwsa.generator;

import com.akamai.miniwsa.generator.dto.GenerateResponse;
import com.akamai.miniwsa.generator.dto.ScenarioSummary;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@Profile({"dev", "all"})
@RequestMapping(DevController.BASE_PATH)
public class DevController {

    static final String BASE_PATH = "/v1/dev";
    static final String SCENARIOS_PATH = "/scenarios";
    static final String GENERATE_PATH = "/generate";

    private static final String DEFAULT_CONFIG_ID = "14227";

    private final GeneratorService generatorService;
    private final ScenarioLibrary scenarioLibrary;

    public DevController(GeneratorService generatorService, ScenarioLibrary scenarioLibrary) {
        this.generatorService = generatorService;
        this.scenarioLibrary = scenarioLibrary;
    }

    @GetMapping(SCENARIOS_PATH)
    public List<ScenarioSummary> listScenarios() {
        return scenarioLibrary.all().stream()
                .map(s -> new ScenarioSummary(s.name(), s.description()))
                .toList();
    }

    @PostMapping(GENERATE_PATH)
    public GenerateResponse generate(
            @RequestParam String scenario,
            @RequestParam(defaultValue = DEFAULT_CONFIG_ID) int configId,
            @RequestParam(required = false) Long seed,
            @RequestParam(required = false) Boolean batching,
            @RequestParam(required = false) Integer batchSize) {
        return generatorService.generate(scenario, configId, seed, batching, batchSize);
    }
}
