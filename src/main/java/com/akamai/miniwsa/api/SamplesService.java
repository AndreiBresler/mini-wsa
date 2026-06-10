package com.akamai.miniwsa.api;

import com.akamai.miniwsa.api.dto.SamplesResponse;
import com.akamai.miniwsa.domain.Action;
import com.akamai.miniwsa.domain.Category;
import com.akamai.miniwsa.storage.EventRepository;
import com.akamai.miniwsa.storage.EventRepository.SamplesResult;
import com.akamai.miniwsa.storage.OffsetPageable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Service
public class SamplesService {

    private static final String TIMESTAMP_FIELD = "timestamp";

    private final EventRepository eventRepository;
    private final int maxLimit;

    public SamplesService(EventRepository eventRepository,
                          @Value("${miniwsa.samples.max-limit}") int maxLimit) {
        this.eventRepository = eventRepository;
        this.maxLimit = maxLimit;
    }

    public SamplesResponse findSamples(Integer configId, Instant from, Instant to,
                                       Category category, Action action,
                                       int limit, int offset) {
        int safeLimit = Math.max(1, Math.min(limit, maxLimit));
        int safeOffset = Math.max(0, offset);

        OffsetPageable pageable = new OffsetPageable(
                safeOffset, safeLimit, Sort.by(Sort.Direction.DESC, TIMESTAMP_FIELD));

        SamplesResult result = eventRepository.findSamples(
                configId, from, to, category, action, pageable);

        return new SamplesResponse(result.totalCount(), safeLimit, safeOffset, result.samples());
    }
}
