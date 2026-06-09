package com.akamai.miniwsa.api;

import com.akamai.miniwsa.api.dto.IngestResponse;
import com.akamai.miniwsa.api.dto.SecurityEventBatch;
import com.akamai.miniwsa.domain.SecurityEvent;
import com.akamai.miniwsa.ingestion.EventProducer;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(IngestionController.BASE_PATH)
public class IngestionController {

    static final String BASE_PATH = "/v1/events";
    static final String INGEST_PATH = "/ingest";
    private static final String ACCEPTED_MESSAGE = "accepted";

    private final EventProducer producer;

    public IngestionController(EventProducer producer) {
        this.producer = producer;
    }

    @PostMapping(INGEST_PATH)
    public ResponseEntity<IngestResponse> ingest(@Valid @RequestBody SecurityEventBatch batch) {
        for (SecurityEvent event : batch.events()) {
            producer.publish(event);
        }
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new IngestResponse(batch.events().size(), ACCEPTED_MESSAGE));
    }
}
