package com.akamai.miniwsa.ingestion;

import com.akamai.miniwsa.domain.SecurityEvent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
public class EventProducer {

    private final KafkaTemplate<String, SecurityEvent> kafkaTemplate;
    private final String topic;

    public EventProducer(KafkaTemplate<String, SecurityEvent> kafkaTemplate,
                         @Value("${miniwsa.kafka.topic}") String topic) {
        this.kafkaTemplate = kafkaTemplate;
        this.topic = topic;
    }

    public void publish(SecurityEvent event) {
        kafkaTemplate.send(topic, event.clientIp(), event);
    }
}
