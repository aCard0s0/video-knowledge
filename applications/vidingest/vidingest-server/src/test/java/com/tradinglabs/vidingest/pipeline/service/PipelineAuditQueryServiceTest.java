package com.tradinglabs.vidingest.pipeline.service;

import com.tradinglabs.vidingest.api.common.PageResponse;
import com.tradinglabs.vidingest.api.pipeline.RunItemAuditEvent;
import com.tradinglabs.vidingest.pipeline.domain.PipelineRun;
import com.tradinglabs.vidingest.pipeline.domain.PipelineRunItem;
import com.tradinglabs.vidingest.pipeline.domain.PipelineRunItemEvent;
import com.tradinglabs.vidingest.pipeline.domain.PipelineRunItemEventType;
import com.tradinglabs.vidingest.pipeline.exceptions.RunItemNotFoundException;
import com.tradinglabs.vidingest.pipeline.exceptions.RunNotFoundException;
import com.tradinglabs.vidingest.pipeline.repo.PipelineRunItemEventRepository;
import com.tradinglabs.vidingest.pipeline.repo.PipelineRunItemRepository;
import com.tradinglabs.vidingest.pipeline.repo.PipelineRunRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PipelineAuditQueryServiceTest {

    @Mock
    private PipelineRunItemEventRepository eventRepository;

    @Mock
    private PipelineRunRepository pipelineRunRepository;

    @Mock
    private PipelineRunItemRepository pipelineRunItemRepository;

    private PipelineAuditMapper mapper;
    private PipelineAuditQueryService service;

    @BeforeEach
    void setup() {
        mapper = new PipelineAuditMapper();
        service = new PipelineAuditQueryService(eventRepository, pipelineRunRepository, pipelineRunItemRepository, mapper);
    }

    @Test
    void listRunEventsThrowsWhenRunMissing() {
        UUID runId = UUID.randomUUID();
        when(pipelineRunRepository.existsById(runId)).thenReturn(false);

        assertThatThrownBy(() -> service.listRunEvents(runId, 0, 10))
                .isInstanceOf(RunNotFoundException.class);
    }

    @Test
    void listItemEventsThrowsWhenItemMissing() {
        UUID runId = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();
        when(pipelineRunRepository.existsById(runId)).thenReturn(true);
        when(pipelineRunItemRepository.findByIdAndPipelineRun_Id(itemId, runId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.listItemEvents(runId, itemId, 0, 10))
                .isInstanceOf(RunItemNotFoundException.class);
    }

    @Test
    void listItemEventsReturnsMappedPage() {
        UUID runId = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();
        PipelineRunItem item = PipelineRunItem.builder().pipelineRun(PipelineRun.builder().build()).build();
        item.setId(itemId);

        PipelineRunItemEvent event = PipelineRunItemEvent.builder()
                .id(UUID.randomUUID())
                .pipelineRunId(runId)
                .runItemId(itemId)
                .eventType(PipelineRunItemEventType.ITEM_CREATED)
                .attempt(1)
                .occurredAt(OffsetDateTime.now(ZoneOffset.UTC))
                .build();

        when(pipelineRunRepository.existsById(runId)).thenReturn(true);
        when(pipelineRunItemRepository.findByIdAndPipelineRun_Id(itemId, runId)).thenReturn(Optional.of(item));
        when(eventRepository.findByRunItemIdOrderByOccurredAtAscIdAsc(eq(itemId), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(event), PageRequest.of(0, 10), 1));

        PageResponse<RunItemAuditEvent> page = service.listItemEvents(runId, itemId, 0, 10);

        assertThat(page.items()).hasSize(1);
        assertThat(page.items().getFirst().eventType()).isEqualTo("ITEM_CREATED");
        assertThat(page.total()).isEqualTo(1L);
    }

    @Test
    void listRunEventsAppliesDefaultPageSizeWhenNull() {
        UUID runId = UUID.randomUUID();
        when(pipelineRunRepository.existsById(runId)).thenReturn(true);
        when(eventRepository.findByPipelineRunIdOrderByOccurredAtAscIdAsc(eq(runId), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 100), 0));

        PageResponse<RunItemAuditEvent> page = service.listRunEvents(runId, null, null);

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        org.mockito.Mockito.verify(eventRepository).findByPipelineRunIdOrderByOccurredAtAscIdAsc(eq(runId), captor.capture());
        assertThat(captor.getValue().getPageSize()).isEqualTo(100);
        assertThat(page.size()).isEqualTo(100);
    }
}
