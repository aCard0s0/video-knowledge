package com.tradinglabs.vidingest.pipeline.service;

import com.tradinglabs.vidingest.api.pipeline.RunDetails;
import com.tradinglabs.vidingest.pipeline.domain.PipelineRun;
import com.tradinglabs.vidingest.pipeline.domain.PipelineRunItem;
import com.tradinglabs.vidingest.pipeline.domain.RunStatus;
import com.tradinglabs.vidingest.pipeline.repo.PipelineRunItemRepository;
import com.tradinglabs.vidingest.videos.domain.VideoStatus;
import com.tradinglabs.vidingest.videos.repo.RunVideoPreview;
import com.tradinglabs.vidingest.videos.repo.VideoRepository;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Run details used to hydrate full {@code Video} entities — JSONB metadata included — to read
 * three strings per item, and ranked previews with its own copy of the run-list algorithm. It
 * now reads the projection and shares {@link RunVideoPreview#PREVIEW_ORDER}; these cases pin
 * both the query it issues and the ordering it inherits.
 */
@Tag("unit")
@ExtendWith(MockitoExtension.class)
class RunDetailsMapperTest {

    @Mock private VideoRepository videoRepository;
    @Mock private PipelineRunItemRepository pipelineRunItemRepository;

    @InjectMocks private RunDetailsMapper mapper;

    private static final OffsetDateTime T0 = OffsetDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);

    @Test
    void readsPreviewsThroughTheProjectionAndRanksThemLikeTheRunList() {
        UUID runId = UUID.randomUUID();
        UUID pending = UUID.randomUUID();
        UUID completed = UUID.randomUUID();

        when(pipelineRunItemRepository.findByPipelineRunIdOrdered(runId)).thenReturn(List.of());
        when(videoRepository.findRunVideoPreviews(List.of(runId))).thenReturn(List.of(
                new RunVideoPreview(runId, pending, "chan", "pending video", VideoStatus.PENDING, T0),
                new RunVideoPreview(runId, completed, "chan", "completed video", VideoStatus.COMPLETED, T0)
        ));

        RunDetails details = mapper.toDetails(run(runId));

        // COMPLETED outranks PENDING, same as the run-list card.
        assertThat(details.videoId()).isEqualTo(completed);
        assertThat(details.videoTitle()).isEqualTo("completed video");
        assertThat(details.videoCount()).isEqualTo(2);
        verify(videoRepository).findRunVideoPreviews(List.of(runId));
    }

    @Test
    void joinsEachItemToItsOwnVideoRatherThanThePreview() {
        UUID runId = UUID.randomUUID();
        UUID videoA = UUID.randomUUID();
        UUID videoB = UUID.randomUUID();

        when(pipelineRunItemRepository.findByPipelineRunIdOrdered(runId))
                .thenReturn(List.of(item(videoA), item(videoB)));
        when(videoRepository.findRunVideoPreviews(List.of(runId))).thenReturn(List.of(
                new RunVideoPreview(runId, videoA, "chan-a", "title-a", VideoStatus.PENDING, T0),
                new RunVideoPreview(runId, videoB, "chan-b", "title-b", VideoStatus.COMPLETED, T0)
        ));

        RunDetails details = mapper.toDetails(run(runId));

        assertThat(details.items()).extracting(RunDetails.RunItem::videoTitle)
                .containsExactly("title-a", "title-b");
        assertThat(details.items()).extracting(RunDetails.RunItem::channelName)
                .containsExactly("chan-a", "chan-b");
    }

    @Test
    void fallsBackToASyntheticItemWhenTheRunHasNoItemRows() {
        UUID runId = UUID.randomUUID();
        when(pipelineRunItemRepository.findByPipelineRunIdOrdered(runId)).thenReturn(List.of());
        when(videoRepository.findRunVideoPreviews(List.of(runId))).thenReturn(List.of());

        RunDetails details = mapper.toDetails(run(runId));

        assertThat(details.items()).hasSize(1);
        // Absent, not empty: there is no item row and no video, and the record now says so.
        assertThat(details.items().get(0).itemId()).isNull();
        assertThat(details.videoId()).isNull();
        assertThat(details.videoCount()).isZero();
    }

    private static PipelineRun run(UUID id) {
        return PipelineRun.builder().id(id).status(RunStatus.IN_PROGRESS).videoUrl("https://example.com/v").build();
    }

    private static PipelineRunItem item(UUID videoId) {
        return PipelineRunItem.builder()
                .id(UUID.randomUUID())
                .url("https://example.com/" + videoId)
                .status(RunStatus.COMPLETED)
                .videoId(videoId)
                .attempt(1)
                .build();
    }
}
