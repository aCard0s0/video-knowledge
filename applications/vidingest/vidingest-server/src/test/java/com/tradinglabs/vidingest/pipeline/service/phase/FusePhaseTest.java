package com.tradinglabs.vidingest.pipeline.service.phase;

import com.tradinglabs.vidingest.config.FusionConfig;
import com.tradinglabs.vidingest.core.fusion.service.SegmentFusionService;
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
 * Gating + delegation tests for {@link FusePhase}. Fusion has the simplest gate of any
 * enrichment phase — just the config master switch, no per-run skip flag.
 */
@ExtendWith(MockitoExtension.class)
class FusePhaseTest {

    @Mock
    private SegmentFusionService segmentFusionService;

    @Test
    void phaseEnumIsFuse() {
        FusePhase phase = new FusePhase(segmentFusionService, configWithEnabled(true));
        assertThat(phase.phase()).isEqualTo(PipelineRunPhase.FUSE);
    }

    @Test
    void doesNotApplyWhenMasterSwitchOff() {
        FusePhase phase = new FusePhase(segmentFusionService, configWithEnabled(false));
        assertThat(phase.applies(ctx())).isFalse();
    }

    @Test
    void appliesByDefaultEvenWhenAllEnrichmentSkipFlagsSet() {
        // Fusion is pure-Java and cheap; it runs whenever the master switch is on,
        // regardless of which per-run skip flags the caller set. The output is still
        // useful to the M7 enhanced CONTEXT phase even when M6 knowledge is skipped.
        FusePhase phase = new FusePhase(segmentFusionService, configWithEnabled(true));
        assertThat(phase.applies(ctx())).isTrue();
    }

    @Test
    void executeDelegatesToServiceWithCtxVideo() {
        FusePhase phase = new FusePhase(segmentFusionService, configWithEnabled(true));
        PipelinePhaseContext ctx = ctx();
        Video video = new Video();
        video.setId(UUID.randomUUID());
        ctx.setVideo(video);

        phase.execute(ctx);

        verify(segmentFusionService, times(1)).fuse(video);
    }

    @Test
    void executeNoOpsWhenCtxVideoIsNull() {
        FusePhase phase = new FusePhase(segmentFusionService, configWithEnabled(true));
        PipelinePhaseContext ctx = ctx();

        phase.execute(ctx);

        verify(segmentFusionService, never()).fuse(org.mockito.ArgumentMatchers.any());
    }

    private static FusionConfig configWithEnabled(boolean enabled) {
        FusionConfig cfg = new FusionConfig();
        cfg.setEnabled(enabled);
        return cfg;
    }

    private static PipelinePhaseContext ctx() {
        return new PipelinePhaseContext(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "https://example.com/v",
                /* skipTranscription */ false,
                /* skipContext       */ false,
                /* skipDiarize       */ true,
                /* skipFrames        */ true,
                /* skipOcr           */ true,
                /* skipKnowledge     */ true
        );
    }
}
