package com.akamai.miniwsa.ingestion;

import com.akamai.miniwsa.domain.EnrichedEvent;
import com.akamai.miniwsa.domain.SecurityEvent;
import com.akamai.miniwsa.enrichment.EnrichmentService;
import com.akamai.miniwsa.storage.EventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class EventConsumer {

    private static final Logger log = LoggerFactory.getLogger(EventConsumer.class);
    private static final String TOPIC = "${miniwsa.kafka.topic}";

    private final EnrichmentService enrichmentService;
    private final EventRepository eventRepository;

    public EventConsumer(EnrichmentService enrichmentService, EventRepository eventRepository) {
        this.enrichmentService = enrichmentService;
        this.eventRepository = eventRepository;
    }

    /**
     * Consumes a raw event, enriches it (classification + threat score + repeat-offender check),
     * persists the enriched form, then commits the Kafka offset.
     *
     * <p>The runtime guarantees: at-least-once delivery via Kafka + manual ack only after a
     * successful DB write + {@code ON CONFLICT DO NOTHING} on the unique {@code event_id} →
     * effectively-once persistence. If any step throws, the offset is not committed and Kafka
     * redelivers; the duplicate insert is a no-op.
     *
     * <p>This method blocks on Redis and Postgres. With virtual threads enabled
     * ({@code spring.threads.virtual.enabled=true}), the carrier OS thread unmounts during
     * those blocking calls and serves other partitions' consumers.
     */
    @KafkaListener(topics = TOPIC)
    public void consume(SecurityEvent event, Acknowledgment ack) {
        Instant receivedAt = Instant.now();
        EnrichedEvent enriched = enrichmentService.enrich(event, receivedAt);
        boolean inserted = eventRepository.upsert(enriched);
        ack.acknowledge();
        if (log.isDebugEnabled()) {
            log.debug("Processed event id={} clientIp={} attackType={} score={} inserted={}",
                    enriched.eventId(), enriched.clientIp(), enriched.attackType(),
                    enriched.threatScore(), inserted);
        }
    }
}
