package com.akamai.miniwsa.api;

import com.akamai.miniwsa.api.dto.IngestResponse;
import com.akamai.miniwsa.api.dto.SecurityEventBatch;
import com.akamai.miniwsa.domain.SecurityEvent;
import com.akamai.miniwsa.ingestion.EventProducer;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/events")
public class IngestionController {

    private final EventProducer producer;

    public IngestionController(EventProducer producer) {
        this.producer = producer;
    }

    @PostMapping("/ingest")
    public ResponseEntity<IngestResponse> ingest(@Valid @RequestBody SecurityEventBatch batch) {
        for (SecurityEvent event : batch.events()) {
            producer.publish(event);
        }
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new IngestResponse(batch.events().size(), "accepted"));
    }
}
