package com.tradinglabs.vidingest.videos.service;

import com.tradinglabs.vidingest.api.videos.RunVideoPhaseResult;
import com.tradinglabs.vidingest.config.FrameSamplingConfig;
import com.tradinglabs.vidingest.config.FusionConfig;
import com.tradinglabs.vidingest.config.OcrConfig;
import com.tradinglabs.vidingest.core.frames.domain.VideoFrame;
import com.tradinglabs.vidingest.core.frames.service.FrameSamplingService;
import com.tradinglabs.vidingest.core.fusion.service.SegmentFusionService;
import com.tradinglabs.vidingest.core.ocr.service.OcrService;
import com.tradinglabs.vidingest.core.transcription.service.TranscriptionService;
import com.tradinglabs.vidingest.pipeline.service.phase.FrameSamplePhase;
import com.tradinglabs.vidingest.pipeline.service.phase.FusePhase;
import com.tradinglabs.vidingest.pipeline.service.phase.OcrPhase;
import com.tradinglabs.vidingest.pipeline.service.phase.PipelinePhase;
import com.tradinglabs.vidingest.pipeline.service.phase.PipelinePhaseRegistry;
import com.tradinglabs.vidingest.pipeline.service.phase.TranscribePhase;
import com.tradinglabs.vidingest.videos.domain.Video;
import com.tradinglabs.vidingest.videos.domain.VideoStatus;
import com.tradinglabs.vidingest.videos.repo.VideoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the contract the rerun endpoint depends on: phases come from
 * {@link PipelinePhaseRegistry} rather than a local switch, the video does not get parked in a
 * working status after a successful rerun, and the phase's row count reaches the response.
 *
 * <p>{@link PipelinePhase} is sealed, so these use the real phase implementations with mocked
 * services rather than a test double — which also pins the runner to what the phases actually
 * do to the video row.
 */
@ExtendWith(MockitoExtension.class)
class VideoPhaseRunnerServiceTest {

    @Mock
    private VideoQueryService videoQueryService;

    @Mock
    private VideoRepository videoRepository;

    @Mock
    private PipelinePhaseRegistry registry;

    @Mock
    private TranscriptionService transcriptionService;

    @Mock
    private OcrService ocrService;

    @Mock
    private SegmentFusionService segmentFusionService;

    @Mock
    private FrameSamplingService frameSamplingService;

    private VideoPhaseRunnerService runner;
    private UUID videoId;
    private Video video;

    @BeforeEach
    void setUp() {
        runner = new VideoPhaseRunnerService(videoQueryService, videoRepository, registry);
        videoId = UUID.randomUUID();
        video = new Video();
        video.setId(videoId);
        video.setStatus(VideoStatus.COMPLETED);
        lenient().when(videoQueryService.getById(videoId)).thenReturn(video);
    }

    @Test
    void runsThePhaseFromTheRegistryAndReportsItsRowCount() {
        register(new OcrPhase(ocrService, new OcrConfig()));
        when(ocrService.ocrAllFrames(video)).thenReturn(42);

        RunVideoPhaseResult result = runner.runPhase(videoId, "ocr");

        assertThat(result.status()).isEqualTo("OK");
        assertThat(result.phase()).isEqualTo("OCR");
        assertThat(result.rowsAffected()).isEqualTo(42);
        assertThat(result.message()).isNull();
    }

    @Test
    void runsThePhaseEvenWhenItsDeploymentToggleIsOff() {
        // The endpoint is the operator escape hatch — "re-OCR after a paddleocr upgrade" —
        // so it bypasses applies() and the vidingest.<phase>.enabled toggle behind it.
        OcrConfig disabled = new OcrConfig();
        disabled.setEnabled(false);
        register(new OcrPhase(ocrService, disabled));
        when(ocrService.ocrAllFrames(video)).thenReturn(1);

        assertThat(runner.runPhase(videoId, "ocr").status()).isEqualTo("OK");
    }

    @Test
    void restoresTheStatusAPhaseMovedOnSuccess() {
        // TranscribePhase / ContextPhase flip the video into a working status and rely on
        // PipelineService to finalise it. A rerun has no run to finalise.
        register(new TranscribePhase(transcriptionService, videoRepository));
        when(videoRepository.save(video)).thenReturn(video);

        RunVideoPhaseResult result = runner.runPhase(videoId, "transcribe");

        assertThat(result.status()).isEqualTo("OK");
        assertThat(result.rowsAffected()).isNull();
        assertThat(video.getStatus()).isEqualTo(VideoStatus.COMPLETED);
    }

    @Test
    void leavesTheStatusAloneWhenThePhaseDidNotTouchIt() {
        register(new FusePhase(segmentFusionService, new FusionConfig()));
        when(segmentFusionService.fuse(video)).thenReturn(List.of());

        RunVideoPhaseResult result = runner.runPhase(videoId, "fuse");

        assertThat(result.rowsAffected()).isZero();
        assertThat(video.getStatus()).isEqualTo(VideoStatus.COMPLETED);
        verify(videoRepository, never()).save(any());
    }

    @Test
    void keepsTheFailedStatusAPhaseSetWhenItThrows() {
        register(new TranscribePhase(transcriptionService, videoRepository));
        when(videoRepository.save(video)).thenReturn(video);
        doThrow(new IllegalStateException("whisper unreachable"))
                .when(transcriptionService).transcribe(video);

        RunVideoPhaseResult result = runner.runPhase(videoId, "transcribe");

        assertThat(result.status()).isEqualTo("ERROR");
        assertThat(result.message()).isEqualTo("whisper unreachable");
        assertThat(result.rowsAffected()).isNull();
        assertThat(video.getStatus()).isEqualTo(VideoStatus.FAILED);
    }

    @Test
    void rejectsPhasesThatNeedASourceUrl() {
        assertThatThrownBy(() -> runner.runPhase(videoId, "DOWNLOAD"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported phase: DOWNLOAD")
                .hasMessageContaining("TRANSCRIBE");
        verify(videoQueryService, never()).getById(any());
    }

    @Test
    void rejectsUnknownPhaseNames() {
        assertThatThrownBy(() -> runner.runPhase(videoId, "nope"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported phase: nope");
    }

    @Test
    void rejectsABlankPhase() {
        assertThatThrownBy(() -> runner.runPhase(videoId, "  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("phase path variable is required");
    }

    @Test
    void acceptsHyphenatedAndLowercasePhaseNames() {
        register(new FrameSamplePhase(frameSamplingService, new FrameSamplingConfig()));
        when(frameSamplingService.sampleFrames(video)).thenReturn(List.of(new VideoFrame(), new VideoFrame(), new VideoFrame()));

        RunVideoPhaseResult result = runner.runPhase(videoId, "frame-sample");

        assertThat(result.phase()).isEqualTo("FRAME_SAMPLE");
        assertThat(result.rowsAffected()).isEqualTo(3);
    }

    private void register(PipelinePhase phase) {
        when(registry.byPhase(phase.phase())).thenReturn(Optional.of(phase));
    }
}
