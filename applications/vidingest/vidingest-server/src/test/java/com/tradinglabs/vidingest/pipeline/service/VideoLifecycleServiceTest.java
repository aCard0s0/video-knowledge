package com.tradinglabs.vidingest.pipeline.service;

import com.tradinglabs.vidingest.pipeline.repo.PipelineRunRepository;
import com.tradinglabs.vidingest.videos.domain.Video;
import com.tradinglabs.vidingest.videos.domain.VideoStatus;
import com.tradinglabs.vidingest.videos.repo.VideoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VideoLifecycleServiceTest {

    @Mock
    private PipelineRunRepository pipelineRunRepository;

    @Mock
    private VideoRepository videoRepository;

    private VideoLifecycleService service;
    private UUID videoId;

    @BeforeEach
    void setup() {
        service = new VideoLifecycleService(pipelineRunRepository, videoRepository);
        videoId = UUID.randomUUID();
    }

    private Video stubVideo(VideoStatus status) {
        Video video = Video.builder().status(status).build();
        video.setId(videoId);
        when(videoRepository.findById(videoId)).thenReturn(Optional.of(video));
        return video;
    }

    /**
     * Every non-terminal status has to be reachable out of, or a process that dies mid-phase
     * leaves the row stuck there with nothing that ever re-reads it.
     */
    @ParameterizedTest
    @EnumSource(value = VideoStatus.class,
            names = {"PENDING", "DOWNLOADING", "DOWNLOADED", "EXTRACTING", "TRANSCRIBING", "PROCESSING"})
    void unfinishedVideoBecomesFailed(VideoStatus status) {
        Video video = stubVideo(status);

        service.markFailedIfUnfinished(videoId);

        assertThat(video.getStatus()).isEqualTo(VideoStatus.FAILED);
        verify(videoRepository).save(video);
    }

    @Test
    void completedVideoIsNotRewritten() {
        Video video = stubVideo(VideoStatus.COMPLETED);

        service.markFailedIfUnfinished(videoId);

        assertThat(video.getStatus()).isEqualTo(VideoStatus.COMPLETED);
        verify(videoRepository, never()).save(any());
    }

    @Test
    void alreadyFailedVideoIsNotWrittenAgain() {
        Video video = stubVideo(VideoStatus.FAILED);

        service.markFailedIfUnfinished(videoId);

        assertThat(video.getStatus()).isEqualTo(VideoStatus.FAILED);
        verify(videoRepository, never()).save(any());
    }

    /** An item can fail before PERSIST ever attached a video. */
    @Test
    void nullVideoIdIsANoOp() {
        service.markFailedIfUnfinished(null);

        verifyNoInteractions(videoRepository);
    }

    @Test
    void missingVideoIsANoOp() {
        when(videoRepository.findById(videoId)).thenReturn(Optional.empty());

        service.markFailedIfUnfinished(videoId);

        verify(videoRepository, never()).save(any());
    }
}
