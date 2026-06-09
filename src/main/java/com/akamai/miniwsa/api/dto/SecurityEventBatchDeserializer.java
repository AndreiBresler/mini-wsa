package com.akamai.miniwsa.api.dto;

import com.akamai.miniwsa.domain.SecurityEvent;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.List;

public class SecurityEventBatchDeserializer extends JsonDeserializer<SecurityEventBatch> {

    private static final TypeReference<List<SecurityEvent>> LIST_TYPE = new TypeReference<>() {
    };

    @Override
    public SecurityEventBatch deserialize(JsonParser parser, DeserializationContext ctxt) throws IOException {
        ObjectMapper mapper = (ObjectMapper) parser.getCodec();
        JsonNode node = mapper.readTree(parser);
        List<SecurityEvent> events = node.isArray()
                ? mapper.convertValue(node, LIST_TYPE)
                : List.of(mapper.convertValue(node, SecurityEvent.class));
        return new SecurityEventBatch(events);
    }
}
