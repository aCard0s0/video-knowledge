package com.tradinglabs.vidingest.pipeline.service.phase;

import com.tradinglabs.vidingest.config.FrameSamplingConfig;
import com.tradinglabs.vidingest.config.OcrConfig;
import com.tradinglabs.vidingest.core.ocr.service.OcrService;
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
 * Gating + delegation tests for {@link OcrPhase}. Four knobs must be open: the master switch,
 * the run's own opt-out, and FRAME_SAMPLE both ways — its deployment toggle and its skip flag —
 * since OCR has no input without frames whichever of the two stopped them being written.
 */
@ExtendWith(MockitoExtension.class)
class OcrPhaseTest {

    @Mock
    private OcrService ocrService;

    @Test
    void phaseEnumIsOcr() {
        OcrPhase phase = new OcrPhase(ocrService, configWithEnabled(true), frames(true));
        assertThat(phase.phase()).isEqualTo(PipelineRunPhase.OCR);
    }

    @Test
    void doesNotApplyWhenMasterSwitchOff() {
        OcrPhase phase = new OcrPhase(ocrService, configWithEnabled(false), frames(true));
        assertThat(phase.applies(ctx(false, false))).isFalse();
    }

    @Test
    void doesNotApplyWhenSkipOcrFlagSet() {
        OcrPhase phase = new OcrPhase(ocrService, configWithEnabled(true), frames(true));
        assertThat(phase.applies(ctx(/* skipFrames */ false, /* skipOcr */ true))).isFalse();
    }

    @Test
    void doesNotApplyWhenFrameSamplingWasSkipped() {
        OcrPhase phase = new OcrPhase(ocrService, configWithEnabled(true), frames(true));
        // No frames means no input — OCR is a no-op anyway, so skip cleanly.
        assertThat(phase.applies(ctx(/* skipFrames */ true, /* skipOcr */ false))).isFalse();
    }

    @Test
    void doesNotApplyWhenFrameSamplingIsOffOnTheDeployment() {
        // No frames were ever written, so there is nothing to OCR — same outcome as the skip
        // flag. Without this the settings screen could enable OCR alone and the phase would run
        // over an empty frame set.
        OcrPhase phase = new OcrPhase(ocrService, configWithEnabled(true), frames(false));
        assertThat(phase.applies(ctx(false, false))).isFalse();
    }

    @Test
    void appliesWhenAllGatesOpen() {
        OcrPhase phase = new OcrPhase(ocrService, configWithEnabled(true), frames(true));
        assertThat(phase.applies(ctx(false, false))).isTrue();
    }

    @Test
    void executeDelegatesToServiceWithCtxVideo() {
        OcrPhase phase = new OcrPhase(ocrService, configWithEnabled(true), frames(true));
        PipelinePhaseContext ctx = ctx(false, false);
        Video video = new Video();
        video.setId(UUID.randomUUID());
        ctx.setVideo(video);

        phase.execute(ctx);

        verify(ocrService, times(1)).ocrAllFrames(video);
    }

    @Test
    void executeNoOpsWhenCtxVideoIsNull() {
        OcrPhase phase = new OcrPhase(ocrService, configWithEnabled(true), frames(true));
        PipelinePhaseContext ctx = ctx(false, false);

        phase.execute(ctx);

        verify(ocrService, never()).ocrAllFrames(org.mockito.ArgumentMatchers.any());
    }

    private static OcrConfig configWithEnabled(boolean enabled) {
        OcrConfig cfg = new OcrConfig();
        cfg.setEnabled(enabled);
        return cfg;
    }

    private static FrameSamplingConfig frames(boolean enabled) {
        FrameSamplingConfig cfg = new FrameSamplingConfig();
        cfg.setEnabled(enabled);
        return cfg;
    }

    private static PipelinePhaseContext ctx(boolean skipFrames, boolean skipOcr) {
        Set<PipelineRunPhase> skip = EnumSet.of(PipelineRunPhase.DIARIZE, PipelineRunPhase.KNOWLEDGE);
        if (skipFrames) {
            skip.add(PipelineRunPhase.FRAME_SAMPLE);
        }
        if (skipOcr) {
            skip.add(PipelineRunPhase.OCR);
        }
        return new PipelinePhaseContext(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "https://example.com/v",
                skip
        );
    }
}
