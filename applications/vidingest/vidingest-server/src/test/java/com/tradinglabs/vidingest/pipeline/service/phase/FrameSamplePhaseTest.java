package com.tradinglabs.vidingest.pipeline.service.phase;

import com.tradinglabs.vidingest.config.FrameSamplingConfig;
import com.tradinglabs.vidingest.core.frames.service.FrameSamplingService;
import com.tradinglabs.vidingest.pipeline.domain.PipelineRunPhase;
import com.tradinglabs.vidingest.videos.domain.Video;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Gating + delegation tests for {@link FrameSamplePhase}. The phase has two knobs that must
 * both be open: the {@code vidingest.frames.enabled} master switch and the per-run
 * opt-out. Unlike {@code DiarizePhase}, it does not depend on transcription
 * because frames are useful for downstream OCR / vision regardless.
 */
@ExtendWith(MockitoExtension.class)
class FrameSamplePhaseTest {

    @Mock
    private FrameSamplingService frameSamplingService;

    @Test
    void phaseEnumIsFrameSample() {
        FrameSamplePhase phase = new FrameSamplePhase(frameSamplingService, configWithEnabled(true));
        assertThat(phase.phase()).isEqualTo(PipelineRunPhase.FRAME_SAMPLE);
    }

    @Test
    void doesNotApplyWhenMasterSwitchOff() {
        FrameSamplePhase phase = new FrameSamplePhase(frameSamplingService, configWithEnabled(false));
        assertThat(phase.applies(ctx(false))).isFalse();
    }

    @Test
    void doesNotApplyWhenSkipFramesFlagSet() {
        FrameSamplePhase phase = new FrameSamplePhase(frameSamplingService, configWithEnabled(true));
        assertThat(phase.applies(ctx(/* skipFrames */ true))).isFalse();
    }

    @Test
    void appliesEvenWhenTranscriptionIsSkipped() {
        // Different from DiarizePhase — frames don't depend on transcription.
        FrameSamplePhase phase = new FrameSamplePhase(frameSamplingService, configWithEnabled(true));

        PipelinePhaseContext ctx = new PipelinePhaseContext(
                UUID.randomUUID(), UUID.randomUUID(), "https://example.com/v",
                EnumSet.of(PipelineRunPhase.TRANSCRIBE, PipelineRunPhase.CONTEXT,
                        PipelineRunPhase.DIARIZE, PipelineRunPhase.OCR, PipelineRunPhase.KNOWLEDGE)
        );

        assertThat(phase.applies(ctx)).isTrue();
    }

    @Test
    void appliesWhenAllGatesOpen() {
        FrameSamplePhase phase = new FrameSamplePhase(frameSamplingService, configWithEnabled(true));
        assertThat(phase.applies(ctx(false))).isTrue();
    }

    @Test
    void executeDelegatesToServiceWithCtxVideo() {
        FrameSamplePhase phase = new FrameSamplePhase(frameSamplingService, configWithEnabled(true));
        PipelinePhaseContext ctx = ctx(false);
        Video video = new Video();
        video.setId(UUID.randomUUID());
        ctx.setVideo(video);

        phase.execute(ctx);

        verify(frameSamplingService, times(1)).sampleFrames(video);
    }

    @Test
    void executeNoOpsWhenCtxVideoIsNull() {
        FrameSamplePhase phase = new FrameSamplePhase(frameSamplingService, configWithEnabled(true));
        PipelinePhaseContext ctx = ctx(false);

        phase.execute(ctx);

        verify(frameSamplingService, never()).sampleFrames(org.mockito.ArgumentMatchers.any());
    }

    private static FrameSamplingConfig configWithEnabled(boolean enabled) {
        FrameSamplingConfig cfg = new FrameSamplingConfig();
        cfg.setEnabled(enabled);
        return cfg;
    }

    private static PipelinePhaseContext ctx(boolean skipFrames) {
        Set<PipelineRunPhase> skip = EnumSet.of(
                PipelineRunPhase.DIARIZE, PipelineRunPhase.OCR, PipelineRunPhase.KNOWLEDGE);
        if (skipFrames) {
            skip.add(PipelineRunPhase.FRAME_SAMPLE);
        }
        return new PipelinePhaseContext(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "https://example.com/v",
                skip
        );
    }
}
