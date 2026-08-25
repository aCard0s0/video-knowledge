package com.tradinglabs.vidingest.pipeline.service.phase;

import com.tradinglabs.vidingest.config.OcrConfig;
import com.tradinglabs.vidingest.core.ocr.service.OcrService;
import com.tradinglabs.vidingest.pipeline.domain.PipelineRunPhase;
import com.tradinglabs.vidingest.videos.domain.Video;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Gating + delegation tests for {@link OcrPhase}. Three knobs must be open: master switch,
 * per-run {@code skipOcr}, and {@code !skipFrames} (since OCR has no input without frames).
 */
@ExtendWith(MockitoExtension.class)
class OcrPhaseTest {

    @Mock
    private OcrService ocrService;

    @Test
    void phaseEnumIsOcr() {
        OcrPhase phase = new OcrPhase(ocrService, configWithEnabled(true));
        assertThat(phase.phase()).isEqualTo(PipelineRunPhase.OCR);
    }

    @Test
    void doesNotApplyWhenMasterSwitchOff() {
        OcrPhase phase = new OcrPhase(ocrService, configWithEnabled(false));
        assertThat(phase.applies(ctx(false, false))).isFalse();
    }

    @Test
    void doesNotApplyWhenSkipOcrFlagSet() {
        OcrPhase phase = new OcrPhase(ocrService, configWithEnabled(true));
        assertThat(phase.applies(ctx(/* skipFrames */ false, /* skipOcr */ true))).isFalse();
    }

    @Test
    void doesNotApplyWhenFrameSamplingWasSkipped() {
        OcrPhase phase = new OcrPhase(ocrService, configWithEnabled(true));
        // No frames means no input — OCR is a no-op anyway, so skip cleanly.
        assertThat(phase.applies(ctx(/* skipFrames */ true, /* skipOcr */ false))).isFalse();
    }

    @Test
    void appliesWhenAllGatesOpen() {
        OcrPhase phase = new OcrPhase(ocrService, configWithEnabled(true));
        assertThat(phase.applies(ctx(false, false))).isTrue();
    }

    @Test
    void executeDelegatesToServiceWithCtxVideo() {
        OcrPhase phase = new OcrPhase(ocrService, configWithEnabled(true));
        PipelinePhaseContext ctx = ctx(false, false);
        Video video = new Video();
        video.setId(UUID.randomUUID());
        ctx.setVideo(video);

        phase.execute(ctx);

        verify(ocrService, times(1)).ocrAllFrames(video);
    }

    @Test
    void executeNoOpsWhenCtxVideoIsNull() {
        OcrPhase phase = new OcrPhase(ocrService, configWithEnabled(true));
        PipelinePhaseContext ctx = ctx(false, false);

        phase.execute(ctx);

        verify(ocrService, never()).ocrAllFrames(org.mockito.ArgumentMatchers.any());
    }

    private static OcrConfig configWithEnabled(boolean enabled) {
        OcrConfig cfg = new OcrConfig();
        cfg.setEnabled(enabled);
        return cfg;
    }

    private static PipelinePhaseContext ctx(boolean skipFrames, boolean skipOcr) {
        return new PipelinePhaseContext(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "https://example.com/v",
                /* skipTranscription */ false,
                /* skipContext       */ false,
                /* skipDiarize       */ true,
                skipFrames,
                skipOcr,
                /* skipKnowledge     */ true
        );
    }
}
