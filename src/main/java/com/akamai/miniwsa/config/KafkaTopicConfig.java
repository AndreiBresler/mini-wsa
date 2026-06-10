package com.akamai.miniwsa.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
@Profile({"ingest", "all"})
public class KafkaTopicConfig {

    @Value("${miniwsa.kafka.topic}")
    private String topic;

    @Bean
    NewTopic securityEventsTopic() {
        return TopicBuilder.name(topic)
                .partitions(3)
                .replicas(1)
                .build();
    }
}
