package com.akamai.miniwsa;

import com.akamai.miniwsa.api.IngestionController;
import com.akamai.miniwsa.api.SamplesController;
import com.akamai.miniwsa.api.SamplesService;
import com.akamai.miniwsa.config.KafkaTopicConfig;
import com.akamai.miniwsa.enrichment.EnrichmentService;
import com.akamai.miniwsa.enrichment.RepeatOffenderTracker;
import com.akamai.miniwsa.generator.DevController;
import com.akamai.miniwsa.generator.GeneratorService;
import com.akamai.miniwsa.generator.ScenarioLibrary;
import com.akamai.miniwsa.ingestion.EventConsumer;
import com.akamai.miniwsa.ingestion.EventProducer;
import com.akamai.miniwsa.stats.StatsController;
import com.akamai.miniwsa.stats.StatsRepository;
import com.akamai.miniwsa.stats.StatsService;
import com.akamai.miniwsa.storage.EventRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Profile;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that each component declares the {@link Profile} annotation
 * required for the production deployment topology — ingest tier, consumer
 * tier, query tier, and dev tier (data generator).
 *
 * <p>The {@code all} profile is the convenience profile that activates every
 * tier in a single JVM; it is the default when {@code SPRING_PROFILES_ACTIVE}
 * is unset (see {@code application.yml}). Production deployments override
 * the default with one of {@code ingest}, {@code consumer}, or {@code query}.
 *
 * <p>This is a pure JUnit test — no Spring context boot — so it runs in
 * milliseconds. The actual context wiring is exercised end-to-end by
 * {@code IngestionFlowIntegrationTest}, which boots with the {@code all}
 * profile (default) and proves the full pipeline works as a single JAR.
 */
class ProfileWiringTest {

    private static final String P_INGEST = "ingest";
    private static final String P_CONSUMER = "consumer";
    private static final String P_QUERY = "query";
    private static final String P_DEV = "dev";
    private static final String P_ALL = "all";

    @Test
    @DisplayName("ingest tier: IngestionController, EventProducer, KafkaTopicConfig")
    void ingest_tier_components() {
        assertProfile(IngestionController.class, P_INGEST, P_ALL);
        assertProfile(EventProducer.class, P_INGEST, P_ALL);
        assertProfile(KafkaTopicConfig.class, P_INGEST, P_ALL);
    }

    @Test
    @DisplayName("consumer tier: EventConsumer, EnrichmentService, RepeatOffenderTracker")
    void consumer_tier_components() {
        assertProfile(EventConsumer.class, P_CONSUMER, P_ALL);
        assertProfile(EnrichmentService.class, P_CONSUMER, P_ALL);
        assertProfile(RepeatOffenderTracker.class, P_CONSUMER, P_ALL);
    }

    @Test
    @DisplayName("query tier: StatsController/Service, SamplesController/Service")
    void query_tier_components() {
        assertProfile(StatsController.class, P_QUERY, P_ALL);
        assertProfile(StatsService.class, P_QUERY, P_ALL);
        assertProfile(SamplesController.class, P_QUERY, P_ALL);
        assertProfile(SamplesService.class, P_QUERY, P_ALL);
    }

    @Test
    @DisplayName("dev tier: DevController, GeneratorService, ScenarioLibrary")
    void dev_tier_components() {
        assertProfile(DevController.class, P_DEV, P_ALL);
        assertProfile(GeneratorService.class, P_DEV, P_ALL);
        assertProfile(ScenarioLibrary.class, P_DEV, P_ALL);
    }

    @Test
    @DisplayName("shared infra: EventRepository participates in consumer (writes) AND query (reads)")
    void event_repository_is_in_consumer_and_query() {
        Profile profile = EventRepository.class.getAnnotation(Profile.class);
        assertThat(profile)
                .as("EventRepository must declare @Profile so it loads in both the consumer (writes) and query (reads) tiers")
                .isNotNull();
        assertThat(Set.of(profile.value()))
                .as("EventRepository must be active in consumer, query, and all profiles")
                .contains(P_CONSUMER, P_QUERY, P_ALL);
    }

    @Test
    @DisplayName("StatsRepository participates in query (primary) and consumer (in case enrichment ever needs stats)")
    void stats_repository_profile() {
        Profile profile = StatsRepository.class.getAnnotation(Profile.class);
        assertThat(profile).isNotNull();
        assertThat(Set.of(profile.value())).contains(P_QUERY, P_ALL);
    }

    /**
     * Common assertion: the class must have a {@link Profile} annotation
     * declaring exactly the expected set of profile values. Order in the
     * annotation doesn't matter.
     */
    private static void assertProfile(Class<?> clazz, String... expected) {
        Profile profile = clazz.getAnnotation(Profile.class);
        assertThat(profile)
                .as("%s must be annotated with @Profile for tier-based deployment", clazz.getSimpleName())
                .isNotNull();
        assertThat(Set.of(profile.value()))
                .as("%s @Profile values", clazz.getSimpleName())
                .containsExactlyInAnyOrder(expected);
    }
}
