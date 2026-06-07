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
