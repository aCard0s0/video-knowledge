package com.tradinglabs.vidingest.pipeline.service.phase;

import com.tradinglabs.vidingest.config.DiarizationConfig;
import com.tradinglabs.vidingest.core.diarization.service.DiarizationService;
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
 * Exercises the gating logic of {@link DiarizePhase} — the most-skipped phase by default.
 * Three knobs must all be open for it to run: the config master switch, the run's own
 * opt-out, and the implicit TRANSCRIBE guard (no transcript = nothing to tag).
 */
@ExtendWith(MockitoExtension.class)
class DiarizePhaseTest {

    @Mock
    private DiarizationService diarizationService;

    @Test
    void phaseEnumIsDiarize() {
        DiarizePhase phase = new DiarizePhase(diarizationService, configWithEnabled(true));
        assertThat(phase.phase()).isEqualTo(PipelineRunPhase.DIARIZE);
    }

    @Test
    void doesNotApplyWhenMasterSwitchOff() {
        DiarizePhase phase = new DiarizePhase(diarizationService, configWithEnabled(false));
        assertThat(phase.applies(ctx(false, false))).isFalse();
    }

    @Test
    void doesNotApplyWhenSkipDiarizeFlagSet() {
        DiarizePhase phase = new DiarizePhase(diarizationService, configWithEnabled(true));
        assertThat(phase.applies(ctx(/* skipTrans */ false, /* skipDiarize */ true))).isFalse();
    }

    @Test
    void doesNotApplyWhenTranscriptionWasSkipped() {
        DiarizePhase phase = new DiarizePhase(diarizationService, configWithEnabled(true));
        // No transcript means no rows to label — skip silently.
        assertThat(phase.applies(ctx(/* skipTrans */ true, /* skipDiarize */ false))).isFalse();
    }

    @Test
    void appliesWhenAllGatesOpen() {
        DiarizePhase phase = new DiarizePhase(diarizationService, configWithEnabled(true));
        assertThat(phase.applies(ctx(false, false))).isTrue();
    }

    @Test
    void executeDelegatesToServiceWithCtxVideo() throws Exception {
        DiarizePhase phase = new DiarizePhase(diarizationService, configWithEnabled(true));
        PipelinePhaseContext ctx = ctx(false, false);
        Video video = new Video();
        video.setId(UUID.randomUUID());
        ctx.setVideo(video);

        phase.execute(ctx);

        verify(diarizationService, times(1)).diarize(video);
    }

    @Test
    void executeNoOpsWhenCtxVideoIsNull() throws Exception {
        DiarizePhase phase = new DiarizePhase(diarizationService, configWithEnabled(true));
        PipelinePhaseContext ctx = ctx(false, false);

        phase.execute(ctx);

        verify(diarizationService, never()).diarize(org.mockito.ArgumentMatchers.any());
    }

    private static DiarizationConfig configWithEnabled(boolean enabled) {
        DiarizationConfig cfg = new DiarizationConfig();
        cfg.setEnabled(enabled);
        return cfg;
    }

    private static PipelinePhaseContext ctx(boolean skipTranscription, boolean skipDiarize) {
        Set<PipelineRunPhase> skip = EnumSet.of(
                PipelineRunPhase.FRAME_SAMPLE, PipelineRunPhase.OCR, PipelineRunPhase.KNOWLEDGE);
        if (skipTranscription) {
            skip.add(PipelineRunPhase.TRANSCRIBE);
        }
        if (skipDiarize) {
            skip.add(PipelineRunPhase.DIARIZE);
        }
        return new PipelinePhaseContext(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "https://example.com/v",
                skip
        );
    }
}
