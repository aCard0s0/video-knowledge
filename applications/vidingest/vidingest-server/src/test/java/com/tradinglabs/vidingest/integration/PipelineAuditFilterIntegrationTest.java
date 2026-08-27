package com.tradinglabs.vidingest.integration;

import com.tradinglabs.vidingest.api.common.PageResponse;
import com.tradinglabs.vidingest.api.pipeline.RunItemAuditEvent;
import com.tradinglabs.vidingest.pipeline.domain.PipelineErrorCode;
import com.tradinglabs.vidingest.pipeline.domain.PipelineRun;
import com.tradinglabs.vidingest.pipeline.domain.PipelineRunItem;
import com.tradinglabs.vidingest.pipeline.domain.PipelineRunItemEvent;
import com.tradinglabs.vidingest.pipeline.domain.PipelineRunItemEventType;
import com.tradinglabs.vidingest.pipeline.domain.PipelineRunPhase;
import com.tradinglabs.vidingest.pipeline.domain.RunStatus;
import com.tradinglabs.vidingest.pipeline.repo.PipelineRunItemEventRepository;
import com.tradinglabs.vidingest.pipeline.repo.PipelineRunItemRepository;
import com.tradinglabs.vidingest.pipeline.repo.PipelineRunRepository;
import com.tradinglabs.vidingest.pipeline.service.PipelineAuditQueryService;
import com.tradinglabs.vidingest.pipeline.service.PipelineAuditQueryService.AuditFilters;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The `phase` and `errorCode` filters, against a real database.
 *
 * `PipelineAuditQueryServiceTest` mocks the repository, so it sees a `Specification` lambda and
 * never the predicates inside it — which is where the whole behaviour lives. Two of these cases are
 * contracts rather than plumbing: an unrecognised value must match **nothing** rather than 400
 * (the parameter is a `String` precisely so a stale link cannot fail the request), and `CREATED`
 * must be filterable even though it is never a lane *step*, because `ITEM_CREATED` carries it.
 */
class PipelineAuditFilterIntegrationTest extends BaseVidingestIntegrationTest {

    @Autowired
    private PipelineAuditQueryService auditQueryService;

    @Autowired
    private PipelineRunRepository runRepository;

    @Autowired
    private PipelineRunItemRepository itemRepository;

    @Autowired
    private PipelineRunItemEventRepository eventRepository;

    @BeforeEach
    void seed() {
        PipelineRun run = runRepository.save(PipelineRun.builder()
                .status(RunStatus.FAILED)
                .phase(PipelineRunPhase.DONE)
                .videoUrl("https://example.test/a")
                .build());
        PipelineRunItem item = itemRepository.save(PipelineRunItem.builder()
                .pipelineRun(run)
                .url("https://example.test/a")
                .status(RunStatus.FAILED)
                .attempt(1)
                .build());

        eventRepository.saveAll(List.of(
                event(run, item, PipelineRunItemEventType.ITEM_CREATED, PipelineRunPhase.CREATED, null, 8),
                event(run, item, PipelineRunItemEventType.ITEM_PHASE_ENTERED, PipelineRunPhase.OCR, null, 9),
                event(run, item, PipelineRunItemEventType.ITEM_FAILED, PipelineRunPhase.OCR,
                        PipelineErrorCode.UPSTREAM_TOOL_FAILURE, 10),
                event(run, item, PipelineRunItemEventType.ITEM_FAILED, PipelineRunPhase.TRANSCRIBE,
                        PipelineErrorCode.TRANSCRIPTION_FAILURE, 11)
        ));
    }

    @Test
    void filtersByPhase() {
        assertThat(events(filters(PipelineRunPhase.OCR.name(), null))).hasSize(2);
    }

    @Test
    void filtersByErrorCode() {
        List<RunItemAuditEvent> found = events(filters(null, PipelineErrorCode.TRANSCRIPTION_FAILURE.name()));

        assertThat(found).hasSize(1);
        assertThat(found.getFirst().phase()).isEqualTo(PipelineRunPhase.TRANSCRIBE.name());
    }

    @Test
    void combinesPhaseAndErrorCode() {
        assertThat(events(filters(PipelineRunPhase.OCR.name(), PipelineErrorCode.UPSTREAM_TOOL_FAILURE.name())))
                .hasSize(1);
        assertThat(events(filters(PipelineRunPhase.OCR.name(), PipelineErrorCode.TRANSCRIPTION_FAILURE.name())))
                .isEmpty();
    }

    @Test
    void acceptsAnyCase() {
        assertThat(events(filters("ocr", "upstream_tool_failure"))).hasSize(1);
    }

    @Test
    void unknownValueMatchesNothingRatherThanFailing() {
        assertThat(events(filters("NOT_A_PHASE", null))).isEmpty();
        assertThat(events(filters(null, "NOT_A_CODE"))).isEmpty();
    }

    @Test
    void createdIsFilterableEvenThoughItIsNeverALaneStep() {
        List<RunItemAuditEvent> found = events(filters(PipelineRunPhase.CREATED.name(), null));

        assertThat(found).hasSize(1);
        assertThat(found.getFirst().eventType()).isEqualTo(PipelineRunItemEventType.ITEM_CREATED.name());
    }

    private static AuditFilters filters(String phase, String errorCode) {
        return new AuditFilters(null, null, null, phase, errorCode, null, null);
    }

    private List<RunItemAuditEvent> events(AuditFilters filters) {
        PageResponse<RunItemAuditEvent> page = auditQueryService.listEvents(filters, 0, 50);
        return page.items();
    }

    private static PipelineRunItemEvent event(
            PipelineRun run,
            PipelineRunItem item,
            PipelineRunItemEventType type,
            PipelineRunPhase phase,
            PipelineErrorCode errorCode,
            int hour
    ) {
        return PipelineRunItemEvent.builder()
                .pipelineRunId(run.getId())
                .runItemId(item.getId())
                .eventType(type)
                .attempt(1)
                .phase(phase)
                .status(errorCode == null ? RunStatus.IN_PROGRESS : RunStatus.FAILED)
                .errorCode(errorCode)
                .occurredAt(OffsetDateTime.of(2026, 8, 20, hour, 0, 0, 0, ZoneOffset.UTC))
                .build();
    }
}
