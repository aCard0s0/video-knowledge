package com.tradinglabs.vidingest.pipeline.service;

import com.tradinglabs.vidingest.api.common.PageResponse;
import com.tradinglabs.vidingest.api.pipeline.RunSummary;
import com.tradinglabs.vidingest.pipeline.domain.PipelineRun;
import com.tradinglabs.vidingest.videos.domain.VideoStatus;
import com.tradinglabs.vidingest.videos.repo.RunVideoPreview;
import com.tradinglabs.vidingest.videos.repo.VideoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * #266: the pipeline run list must read previews/counts via the lightweight projection rather than
 * hydrating full {@code Video} entities (and their JSONB metadata), while preserving the existing
 * preview-selection ordering (status rank, then createdAt, then video id).
 */
@Tag("unit")
@ExtendWith(MockitoExtension.class)
class RunSummaryPageServiceTest {

    @Mock private RunQueryService runQueryService;
    @Mock private VideoRepository videoRepository;

    private final RunSummaryMapper mapper = new RunSummaryMapper();
    private RunSummaryPageService service;

    @BeforeEach
    void setUp() {
        service = new RunSummaryPageService(runQueryService, videoRepository, mapper);
    }

    @Test
    void usesProjectionAndSelectsHighestRankedPreviewWithCount() {
        UUID runId = UUID.randomUUID();
        PipelineRun run = mock(PipelineRun.class);
        when(run.getId()).thenReturn(runId);
        when(runQueryService.listPipelineRunsPage(null, 0, 20, "createdAt")).thenReturn(new PageImpl<>(List.of(run)));

        UUID pendingVideo = UUID.randomUUID();
        UUID completedVideo = UUID.randomUUID();
        when(videoRepository.findRunVideoPreviews(List.of(runId))).thenReturn(List.of(
                new RunVideoPreview(runId, pendingVideo, "chan", "pending video", VideoStatus.PENDING, LocalDateTime.now()),
                new RunVideoPreview(runId, completedVideo, "chan", "completed video", VideoStatus.COMPLETED, LocalDateTime.now())
        ));

        PageResponse<RunSummary> page = service.list(null, 0, 20, "createdAt");

        assertThat(page.items()).hasSize(1);
        RunSummary summary = page.items().get(0);
        // COMPLETED ranks above PENDING, so it is chosen as the preview.
        assertThat(summary.videoId()).isEqualTo(completedVideo.toString());
        assertThat(summary.videoTitle()).isEqualTo("completed video");
        assertThat(summary.videoCount()).isEqualTo(2);

        verify(videoRepository).findRunVideoPreviews(List.of(runId));
        // Must NOT hydrate full Video entities for the list view.
        verify(videoRepository, never()).findByPipelineRun_IdIn(any());
    }
}
