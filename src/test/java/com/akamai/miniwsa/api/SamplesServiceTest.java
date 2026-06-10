package com.akamai.miniwsa.api;

import com.akamai.miniwsa.api.dto.SamplesResponse;
import com.akamai.miniwsa.domain.Action;
import com.akamai.miniwsa.domain.Category;
import com.akamai.miniwsa.storage.EventRepository;
import com.akamai.miniwsa.storage.EventRepository.SamplesResult;
import com.akamai.miniwsa.storage.OffsetPageable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SamplesServiceTest {

    private static final int MAX_LIMIT = 100;
    private static final Instant FROM = Instant.parse("2026-05-20T00:00:00Z");
    private static final Instant TO   = Instant.parse("2026-05-21T00:00:00Z");

    @Mock EventRepository repository;
    SamplesService service;

    @BeforeEach
    void setUp() {
        service = new SamplesService(repository, MAX_LIMIT);
    }

    @Test
    void limit_above_max_is_clamped_to_max_and_reported_in_response() {
        when(repository.findSamples(any(), any(), any(), any(), any(), any()))
                .thenReturn(new SamplesResult(0L, List.of()));

        SamplesResponse response = service.findSamples(null, null, null, null, null, 500, 0);

        assertThat(response.limit()).isEqualTo(MAX_LIMIT);
    }

    @Test
    void negative_offset_is_clamped_to_zero() {
        when(repository.findSamples(any(), any(), any(), any(), any(), any()))
                .thenReturn(new SamplesResult(0L, List.of()));

        SamplesResponse response = service.findSamples(null, null, null, null, null, 20, -5);

        assertThat(response.offset()).isZero();
    }

    @Test
    void zero_or_negative_limit_is_clamped_to_one() {
        when(repository.findSamples(any(), any(), any(), any(), any(), any()))
                .thenReturn(new SamplesResult(0L, List.of()));

        SamplesResponse response = service.findSamples(null, null, null, null, null, 0, 0);

        assertThat(response.limit()).isEqualTo(1);
    }

    @Test
    void filters_are_passed_through_to_repository() {
        when(repository.findSamples(eq(14227), eq(FROM), eq(TO), eq(Category.INJECTION), eq(Action.DENY), any()))
                .thenReturn(new SamplesResult(0L, List.of()));

        service.findSamples(14227, FROM, TO, Category.INJECTION, Action.DENY, 20, 0);

        verify(repository).findSamples(eq(14227), eq(FROM), eq(TO),
                eq(Category.INJECTION), eq(Action.DENY), any());
    }

    @Test
    void pageable_uses_requested_offset_and_limit() {
        when(repository.findSamples(any(), any(), any(), any(), any(), any()))
                .thenReturn(new SamplesResult(0L, List.of()));

        service.findSamples(null, null, null, null, null, 25, 75);

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(repository).findSamples(any(), any(), any(), any(), any(), pageableCaptor.capture());

        Pageable used = pageableCaptor.getValue();
        assertThat(used).isInstanceOf(OffsetPageable.class);
        assertThat(used.getOffset()).isEqualTo(75L);
        assertThat(used.getPageSize()).isEqualTo(25);
        assertThat(used.getSort().getOrderFor("timestamp")).isNotNull();
        assertThat(used.getSort().getOrderFor("timestamp").isDescending()).isTrue();
    }

    @Test
    void total_count_from_repository_is_returned() {
        when(repository.findSamples(any(), any(), any(), any(), any(), any()))
                .thenReturn(new SamplesResult(1523L, List.of()));

        SamplesResponse response = service.findSamples(null, null, null, null, null, 20, 0);

        assertThat(response.totalCount()).isEqualTo(1523L);
    }
}
