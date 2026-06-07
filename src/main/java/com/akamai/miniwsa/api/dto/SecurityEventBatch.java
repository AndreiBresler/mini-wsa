package com.akamai.miniwsa.api.dto;

import com.akamai.miniwsa.domain.SecurityEvent;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import jakarta.validation.Valid;

import java.util.List;

@JsonDeserialize(using = SecurityEventBatchDeserializer.class)
public record SecurityEventBatch(@Valid List<@Valid SecurityEvent> events) {
}
