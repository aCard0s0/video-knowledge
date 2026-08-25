package com.tradinglabs.vidingest.pipeline.service;

import com.tradinglabs.vidingest.api.pipeline.CreatePipelineRunResponse;
import com.tradinglabs.vidingest.api.pipeline.CreatePipelineRunResponse.ItemStatus;
import com.tradinglabs.vidingest.pipeline.domain.PipelineRunPhase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.EnumSet;
import java.util.Set;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PipelineIntakeServiceTest {

    @Mock
    private PipelineService pipelineService;

    /** The enrichment phases only; TRANSCRIBE/CONTEXT stay on unless a case says otherwise. */
    private static Set<PipelineRunPhase> skipEnrichment() {
        return EnumSet.of(PipelineRunPhase.DIARIZE, PipelineRunPhase.FRAME_SAMPLE,
                PipelineRunPhase.OCR, PipelineRunPhase.KNOWLEDGE);
    }

    private static Set<PipelineRunPhase> skipEverything() {
        return EnumSet.of(PipelineRunPhase.TRANSCRIBE, PipelineRunPhase.CONTEXT,
                PipelineRunPhase.DIARIZE, PipelineRunPhase.FRAME_SAMPLE,
                PipelineRunPhase.OCR, PipelineRunPhase.KNOWLEDGE);
    }

    @Test
    void emptyUrlListReturnsSingleRejectionAndDoesNotEnqueue() {
        PipelineIntakeService intake = new PipelineIntakeService(pipelineService);

        CreatePipelineRunResponse response = intake.intake(List.of(), skipEnrichment());

        assertThat(response.runId()).isNull();
        assertThat(response.items()).hasSize(1);
        assertThat(response.items().getFirst().status()).isEqualTo(ItemStatus.REJECTED);
        assertThat(response.items().getFirst().reason()).isEqualTo("urls must not be empty");
        verify(pipelineService, never()).enqueuePipelineRunBatch(anyList(), anySet());
    }

    @Test
    void nullUrlListBehavesLikeEmpty() {
        PipelineIntakeService intake = new PipelineIntakeService(pipelineService);

        CreatePipelineRunResponse response = intake.intake(null, skipEnrichment());

        assertThat(response.runId()).isNull();
        assertThat(response.items()).hasSize(1);
        assertThat(response.items().getFirst().reason()).isEqualTo("urls must not be empty");
        verify(pipelineService, never()).enqueuePipelineRunBatch(anyList(), anySet());
    }

    @Test
    void blankAndNullUrlsRejectedWithoutDispatch() {
        PipelineIntakeService intake = new PipelineIntakeService(pipelineService);

        CreatePipelineRunResponse response = intake.intake(Arrays.asList("", "  ", null), skipEnrichment());

        assertThat(response.runId()).isNull();
        assertThat(response.items()).hasSize(3);
        assertThat(response.items()).allMatch(i -> i.status() == ItemStatus.REJECTED);
        assertThat(response.items()).allMatch(i -> "url must not be blank".equals(i.reason()));
        verify(pipelineService, never()).enqueuePipelineRunBatch(anyList(), anySet());
    }

    @Test
    void nonHttpSchemeRejected() {
        PipelineIntakeService intake = new PipelineIntakeService(pipelineService);

        CreatePipelineRunResponse response = intake.intake(List.of("ftp://example.com/video"), skipEnrichment());

        assertThat(response.runId()).isNull();
        assertThat(response.items()).hasSize(1);
        assertThat(response.items().getFirst().status()).isEqualTo(ItemStatus.REJECTED);
        assertThat(response.items().getFirst().reason()).isEqualTo("url must start with http:// or https://");
    }

    @Test
    void duplicateUrlInRequestIsRejectedSecondTimeOnly() {
        UUID runId = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();
        Set<PipelineRunPhase> legacyFlags = skipEnrichment();
        when(pipelineService.enqueuePipelineRunBatch(eq(List.of("https://example.com/v")), eq(legacyFlags)))
                .thenReturn(new PipelineService.BatchEnqueueResult(
                        runId,
                        List.of(new PipelineService.EnqueuedItem("https://example.com/v", itemId))
                ));
        PipelineIntakeService intake = new PipelineIntakeService(pipelineService);

        CreatePipelineRunResponse response = intake.intake(
                List.of("https://example.com/v", "https://example.com/v"),
                skipEnrichment()
        );

        assertThat(response.runId()).isEqualTo(runId.toString());
        assertThat(response.items()).hasSize(2);
        assertThat(response.items().get(0).status()).isEqualTo(ItemStatus.ACCEPTED);
        assertThat(response.items().get(0).itemId()).isEqualTo(itemId.toString());
        assertThat(response.items().get(1).status()).isEqualTo(ItemStatus.REJECTED);
        assertThat(response.items().get(1).reason()).isEqualTo("duplicate url in request");
    }

    @Test
    void mixedInputPreservesOrderAndPropagatesEnqueuedItemIds() {
        UUID runId = UUID.randomUUID();
        UUID itemA = UUID.randomUUID();
        UUID itemB = UUID.randomUUID();
        Set<PipelineRunPhase> legacyFlags = skipEverything();
        when(pipelineService.enqueuePipelineRunBatch(
                eq(List.of("https://example.com/a", "https://example.com/b")),
                eq(legacyFlags)))
                .thenReturn(new PipelineService.BatchEnqueueResult(
                        runId,
                        List.of(
                                new PipelineService.EnqueuedItem("https://example.com/a", itemA),
                                new PipelineService.EnqueuedItem("https://example.com/b", itemB)
                        )
                ));
        PipelineIntakeService intake = new PipelineIntakeService(pipelineService);

        CreatePipelineRunResponse response = intake.intake(
                Arrays.asList(
                        "https://example.com/a",
                        "ftp://nope",
                        "  ",
                        "https://example.com/b",
                        "https://example.com/a"
                ),
                skipEverything()
        );

        assertThat(response.runId()).isEqualTo(runId.toString());
        assertThat(response.items()).hasSize(5);
        assertThat(response.items().get(0).status()).isEqualTo(ItemStatus.ACCEPTED);
        assertThat(response.items().get(0).itemId()).isEqualTo(itemA.toString());
        assertThat(response.items().get(1).status()).isEqualTo(ItemStatus.REJECTED);
        assertThat(response.items().get(1).reason()).isEqualTo("url must start with http:// or https://");
        assertThat(response.items().get(2).status()).isEqualTo(ItemStatus.REJECTED);
        assertThat(response.items().get(2).reason()).isEqualTo("url must not be blank");
        assertThat(response.items().get(3).status()).isEqualTo(ItemStatus.ACCEPTED);
        assertThat(response.items().get(3).itemId()).isEqualTo(itemB.toString());
        assertThat(response.items().get(4).status()).isEqualTo(ItemStatus.REJECTED);
        assertThat(response.items().get(4).reason()).isEqualTo("duplicate url in request");
    }

    @Test
    void trimsLeadingAndTrailingWhitespaceBeforeEnqueuing() {
        UUID runId = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();
        Set<PipelineRunPhase> legacyFlags = skipEnrichment();
        when(pipelineService.enqueuePipelineRunBatch(eq(List.of("https://example.com/v")), eq(legacyFlags)))
                .thenReturn(new PipelineService.BatchEnqueueResult(
                        runId,
                        List.of(new PipelineService.EnqueuedItem("https://example.com/v", itemId))
                ));
        PipelineIntakeService intake = new PipelineIntakeService(pipelineService);

        CreatePipelineRunResponse response = intake.intake(
                List.of("  https://example.com/v  "),
                skipEnrichment()
        );

        ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
        verify(pipelineService).enqueuePipelineRunBatch(captor.capture(), anySet());
        assertThat(captor.getValue()).containsExactly("https://example.com/v");
        assertThat(response.items().getFirst().url()).isEqualTo("https://example.com/v");
    }

    @Test
    void enrichmentSkipFlagsPassThroughToPipelineService() {
        UUID runId = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();
        Set<PipelineRunPhase> fullFlags = Set.of();
        when(pipelineService.enqueuePipelineRunBatch(eq(List.of("https://example.com/v")), eq(fullFlags)))
                .thenReturn(new PipelineService.BatchEnqueueResult(
                        runId,
                        List.of(new PipelineService.EnqueuedItem("https://example.com/v", itemId))
                ));
        PipelineIntakeService intake = new PipelineIntakeService(pipelineService);

        CreatePipelineRunResponse response = intake.intake(
                List.of("https://example.com/v"),
                fullFlags
        );

        assertThat(response.runId()).isEqualTo(runId.toString());
        verify(pipelineService).enqueuePipelineRunBatch(
                eq(List.of("https://example.com/v")),
                eq(fullFlags)
        );
    }
}
