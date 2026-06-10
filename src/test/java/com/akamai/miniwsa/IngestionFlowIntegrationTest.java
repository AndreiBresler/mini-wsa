package com.akamai.miniwsa;

import com.akamai.miniwsa.storage.EventJpaRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.redis.testcontainers.RedisContainer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class IngestionFlowIntegrationTest {

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("miniwsa")
            .withUsername("miniwsa")
            .withPassword("miniwsa");

    @Container
    @SuppressWarnings("resource")
    static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("apache/kafka:3.9.1"));

    @Container
    @SuppressWarnings("resource")
    static final RedisContainer REDIS = new RedisContainer(DockerImageName.parse("redis:7-alpine"));

    @DynamicPropertySource
    static void containerProps(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);

        r.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);

        r.add("spring.data.redis.host", REDIS::getHost);
        r.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @Autowired MockMvc mockMvc;
    @Autowired EventJpaRepository jpa;
    @Autowired ObjectMapper objectMapper;
    @Autowired RedisConnectionFactory redisConnectionFactory;

    @BeforeEach
    void cleanSlate() {
        jpa.deleteAll();
        // Flush Redis so per-IP sliding-window state from a previous test
        // doesn't carry over into this one.
        redisConnectionFactory.getConnection().serverCommands().flushDb();
    }

    @Test
    void post_event_eventually_appears_in_stats_summary() throws Exception {
        String body = """
                {
                  "eventId": "e2e-1",
                  "timestamp": "2026-05-20T14:00:00Z",
                  "configId": 14227,
                  "clientIp": "203.0.113.42",
                  "path": "/api/v1/login",
                  "rule": {
                    "id": "950001",
                    "name": "SQLI",
                    "severity": "CRITICAL",
                    "category": "INJECTION"
                  },
                  "action": "DENY"
                }
                """;

        mockMvc.perform(post("/v1/events/ingest").contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accepted").value(1));

        // Wait for the Kafka consumer to drain and persist
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(jpa.count()).isEqualTo(1L)
        );

        String response = mockMvc.perform(get("/v1/stats/summary")
                        .param("configId", "14227")
                        .param("from", "2026-05-20T00:00:00Z")
                        .param("to", "2026-05-21T00:00:00Z"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode json = objectMapper.readTree(response);
        assertThat(json.get("totalEvents").asLong()).isEqualTo(1L);
        assertThat(json.get("byCategory").get("INJECTION").get("count").asLong()).isEqualTo(1L);
        assertThat(json.get("byCategory").get("INJECTION").get("avgThreatScore").asDouble()).isEqualTo(75.0);
        assertThat(json.get("topAttackers").get(0).get("clientIp").asText()).isEqualTo("203.0.113.42");
        assertThat(json.get("topTargetedPaths").get(0).get("path").asText()).isEqualTo("/api/v1/login");
    }

    @Test
    void duplicate_event_id_is_persisted_once() throws Exception {
        String body = """
                {
                  "eventId": "e2e-dup",
                  "timestamp": "2026-05-20T14:00:00Z",
                  "configId": 14227,
                  "clientIp": "1.2.3.4",
                  "rule": {
                    "id": "950001",
                    "name": "SQLI",
                    "severity": "HIGH",
                    "category": "INJECTION"
                  },
                  "action": "ALERT"
                }
                """;

        // Post the same event 3 times
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(post("/v1/events/ingest").contentType(APPLICATION_JSON).content(body))
                    .andExpect(status().isCreated());
        }

        // Eventually exactly one row exists
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(jpa.count()).isEqualTo(1L)
        );

        // Wait a beat to make sure no further duplicates land
        Thread.sleep(1500);
        assertThat(jpa.count()).isEqualTo(1L);
    }

    @Test
    void invalid_payload_returns_400_with_field_details() throws Exception {
        String body = """
                {
                  "eventId": "bad",
                  "timestamp": "2026-05-20T14:00:00Z",
                  "configId": 14227,
                  "clientIp": "1.1.1.1",
                  "rule": {
                    "id": "1",
                    "name": "n",
                    "severity": "NUCLEAR",
                    "category": "INJECTION"
                  },
                  "action": "DENY"
                }
                """;

        mockMvc.perform(post("/v1/events/ingest").contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.details[0].field").value("rule.severity"));
    }
}
