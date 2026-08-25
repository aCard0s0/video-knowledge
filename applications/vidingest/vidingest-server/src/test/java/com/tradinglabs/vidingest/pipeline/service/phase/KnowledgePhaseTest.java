package com.tradinglabs.vidingest.pipeline.service.phase;

import com.tradinglabs.vidingest.config.KnowledgeExtractionConfig;
import com.tradinglabs.vidingest.core.knowledge.service.KnowledgeExtractionService;
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
 * Gating + delegation tests for {@link KnowledgePhase}. Two knobs must be open: the
 * config master switch and the per-run {@code skipKnowledge} flag. The phase is
 * deliberately independent of upstream skip flags — the fusion phase produces an empty
 * segment set when transcripts/OCR/diarization are all absent, and the LLM call on an
 * empty input is a fast no-op.
 */
@ExtendWith(MockitoExtension.class)
class KnowledgePhaseTest {

    @Mock
    private KnowledgeExtractionService knowledgeExtractionService;

    @Test
    void phaseEnumIsKnowledge() {
        KnowledgePhase phase = new KnowledgePhase(knowledgeExtractionService, configWithEnabled(true));
        assertThat(phase.phase()).isEqualTo(PipelineRunPhase.KNOWLEDGE);
    }

    @Test
    void doesNotApplyWhenMasterSwitchOff() {
        KnowledgePhase phase = new KnowledgePhase(knowledgeExtractionService, configWithEnabled(false));
        assertThat(phase.applies(ctx(false))).isFalse();
    }

    @Test
    void doesNotApplyWhenSkipKnowledgeFlagSet() {
        KnowledgePhase phase = new KnowledgePhase(knowledgeExtractionService, configWithEnabled(true));
        assertThat(phase.applies(ctx(/* skipKnowledge */ true))).isFalse();
    }

    @Test
    void appliesWhenAllGatesOpen() {
        KnowledgePhase phase = new KnowledgePhase(knowledgeExtractionService, configWithEnabled(true));
        assertThat(phase.applies(ctx(false))).isTrue();
    }

    @Test
    void executeDelegatesToServiceWithCtxVideo() {
        KnowledgePhase phase = new KnowledgePhase(knowledgeExtractionService, configWithEnabled(true));
        PipelinePhaseContext ctx = ctx(false);
        Video video = new Video();
        video.setId(UUID.randomUUID());
        ctx.setVideo(video);

        phase.execute(ctx);

        verify(knowledgeExtractionService, times(1)).extractKnowledge(video);
    }

    @Test
    void executeNoOpsWhenCtxVideoIsNull() {
        KnowledgePhase phase = new KnowledgePhase(knowledgeExtractionService, configWithEnabled(true));
        PipelinePhaseContext ctx = ctx(false);

        phase.execute(ctx);

        verify(knowledgeExtractionService, never()).extractKnowledge(org.mockito.ArgumentMatchers.any());
    }

    private static KnowledgeExtractionConfig configWithEnabled(boolean enabled) {
        KnowledgeExtractionConfig cfg = new KnowledgeExtractionConfig();
        cfg.setEnabled(enabled);
        return cfg;
    }

    private static PipelinePhaseContext ctx(boolean skipKnowledge) {
        return new PipelinePhaseContext(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "https://example.com/v",
                /* skipTranscription */ false,
                /* skipContext       */ false,
                /* skipDiarize       */ true,
                /* skipFrames        */ true,
                /* skipOcr           */ true,
                skipKnowledge
        );
    }
}
