package com.akamai.miniwsa.api.dto;

import com.akamai.miniwsa.domain.SecurityEvent;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Accepts either a single SecurityEvent object or an array of them.
 *
 * <p>Uses {@link ObjectMapper#treeToValue} (not {@code convertValue}) so that
 * Jackson's {@link com.fasterxml.jackson.databind.exc.InvalidFormatException}
 * propagates as-is. Spring then surfaces it as
 * {@code HttpMessageNotReadableException}, which {@code GlobalExceptionHandler}
 * turns into a 400 with field-level details.
 * {@code convertValue} would wrap the error in {@code IllegalArgumentException},
 * which bypasses that handler and becomes a 500.
 */
public class SecurityEventBatchDeserializer extends JsonDeserializer<SecurityEventBatch> {

    @Override
    public SecurityEventBatch deserialize(JsonParser parser, DeserializationContext ctxt) throws IOException {
        ObjectMapper mapper = (ObjectMapper) parser.getCodec();
        JsonNode node = mapper.readTree(parser);
        List<SecurityEvent> events = new ArrayList<>();
        if (node.isArray()) {
            for (JsonNode item : node) {
                events.add(mapper.treeToValue(item, SecurityEvent.class));
            }
        } else {
            events.add(mapper.treeToValue(node, SecurityEvent.class));
        }
        return new SecurityEventBatch(events);
    }
}
