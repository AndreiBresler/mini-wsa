package com.akamai.miniwsa.stats;

import org.springframework.context.annotation.Profile;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

@RestController
@Profile({"query", "all"})
@RequestMapping(StatsController.BASE_PATH)
public class StatsController {

    static final String BASE_PATH = "/v1/stats";
    static final String SUMMARY_PATH = "/summary";

    private final StatsService statsService;

    public StatsController(StatsService statsService) {
        this.statsService = statsService;
    }

    @GetMapping(SUMMARY_PATH)
    public StatsSummary summary(
            @RequestParam(required = false) Integer configId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to
    ) {
        return statsService.summarize(configId, from, to);
    }
}