package com.akamai.miniwsa.api;

import com.akamai.miniwsa.api.dto.SamplesResponse;
import com.akamai.miniwsa.domain.Action;
import com.akamai.miniwsa.domain.Category;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

@RestController
@RequestMapping(SamplesController.BASE_PATH)
public class SamplesController {

    static final String BASE_PATH = "/v1/events";
    static final String SAMPLES_PATH = "/samples";

    private static final String DEFAULT_LIMIT = "20";
    private static final String DEFAULT_OFFSET = "0";

    private final SamplesService samplesService;

    public SamplesController(SamplesService samplesService) {
        this.samplesService = samplesService;
    }

    @GetMapping(SAMPLES_PATH)
    public SamplesResponse samples(
            @RequestParam(required = false) Integer configId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) Category category,
            @RequestParam(required = false) Action action,
            @RequestParam(defaultValue = DEFAULT_LIMIT) int limit,
            @RequestParam(defaultValue = DEFAULT_OFFSET) int offset) {
        return samplesService.findSamples(configId, from, to, category, action, limit, offset);
    }
}
