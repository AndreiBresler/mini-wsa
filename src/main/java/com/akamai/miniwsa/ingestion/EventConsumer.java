package com.akamai.miniwsa.ingestion;

import com.akamai.miniwsa.domain.SecurityEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

@Component
public class EventConsumer {

    private static final Logger log = LoggerFactory.getLogger(EventConsumer.class);

    @KafkaListener(topics = "${miniwsa.kafka.topic}")
    public void consume(SecurityEvent event, Acknowledgment ack) {
        log.info("Consumed event: id={} clientIp={} category={}",
                event.eventId(), event.clientIp(), event.rule().category());
        ack.acknowledge();
    }
}
