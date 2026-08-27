package com.tradinglabs.vidingest.health;

import com.tradinglabs.vidingest.api.health.PhaseAvailability;
import com.tradinglabs.vidingest.config.DiarizationConfig;
import com.tradinglabs.vidingest.config.FrameSamplingConfig;
import com.tradinglabs.vidingest.config.FusionConfig;
import com.tradinglabs.vidingest.config.KnowledgeExtractionConfig;
import com.tradinglabs.vidingest.config.OcrConfig;
import com.tradinglabs.vidingest.config.VideoSearchConfig;
import com.tradinglabs.vidingest.pipeline.domain.PipelineRunPhase;
import com.tradinglabs.vidingest.pipeline.service.phase.ContextPhase;
import com.tradinglabs.vidingest.pipeline.service.phase.DiarizePhase;
import com.tradinglabs.vidingest.pipeline.service.phase.DownloadPhase;
import com.tradinglabs.vidingest.pipeline.service.phase.FrameSamplePhase;
import com.tradinglabs.vidingest.pipeline.service.phase.FusePhase;
import com.tradinglabs.vidingest.pipeline.service.phase.KnowledgePhase;
import com.tradinglabs.vidingest.pipeline.service.phase.MetadataPhase;
import com.tradinglabs.vidingest.pipeline.service.phase.OcrPhase;
import com.tradinglabs.vidingest.pipeline.service.phase.PersistPhase;
import com.tradinglabs.vidingest.pipeline.service.phase.PipelinePhaseRegistry;
import com.tradinglabs.vidingest.pipeline.service.phase.TranscribePhase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * The availability probe answers from the phases' own {@code applies}, so these assert the two
 * things that separates it from re-reading the config: it reports the deployment toggle, and it
 * reports it for a run that skips nothing.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PhaseAvailabilityServiceTest {

    @Mock private MetadataPhase metadataPhase;
    @Mock private DownloadPhase downloadPhase;
    @Mock private PersistPhase persistPhase;

    @Test
    void reportsTheDeploymentToggleForEveryOptionalPhase() {
        PhaseAvailability result = availability(false);

        // The compose defaults: enrichment off, fusion and context on.
        assertThat(result.phases())
                .containsEntry(PipelineRunPhase.DIARIZE.name(), false)
                .containsEntry(PipelineRunPhase.FRAME_SAMPLE.name(), false)
                .containsEntry(PipelineRunPhase.OCR.name(), false)
                .containsEntry(PipelineRunPhase.KNOWLEDGE.name(), false)
                .containsEntry(PipelineRunPhase.FUSE.name(), true)
                .containsEntry(PipelineRunPhase.CONTEXT.name(), true);
    }

    @Test
    void turningTheTogglesOnFlipsEveryAnswer() {
        assertThat(availability(true).phases()).containsOnlyKeys(
                        PipelineRunPhase.optionalPhases().stream().map(Enum::name).toArray(String[]::new))
                .containsValue(true)
                .doesNotContainValue(false);
    }

    /** Only the optional phases are reported: METADATA, DOWNLOAD and PERSIST can never be off. */
    @Test
    void mandatoryPhasesAreNotReported() {
        assertThat(availability(true).phases())
                .doesNotContainKey(PipelineRunPhase.METADATA.name())
                .doesNotContainKey(PipelineRunPhase.DOWNLOAD.name())
                .doesNotContainKey(PipelineRunPhase.PERSIST.name());
    }

    private PhaseAvailability availability(boolean enrichmentEnabled) {
        // The registry indexes by phase(), so the three mandatory phases have to answer with theirs;
        // nothing else about them is reached — they are never optional, so never probed.
        when(metadataPhase.phase()).thenReturn(PipelineRunPhase.METADATA);
        when(downloadPhase.phase()).thenReturn(PipelineRunPhase.DOWNLOAD);
        when(persistPhase.phase()).thenReturn(PipelineRunPhase.PERSIST);

        DiarizationConfig diarization = new DiarizationConfig();
        diarization.setEnabled(enrichmentEnabled);
        FrameSamplingConfig frames = new FrameSamplingConfig();
        frames.setEnabled(enrichmentEnabled);
        OcrConfig ocr = new OcrConfig();
        ocr.setEnabled(enrichmentEnabled);
        KnowledgeExtractionConfig knowledge = new KnowledgeExtractionConfig();
        knowledge.setEnabled(enrichmentEnabled);
        FusionConfig fusion = new FusionConfig();
        fusion.setEnabled(true);
        VideoSearchConfig search = new VideoSearchConfig();
        search.setSemanticEnabled(true);

        PipelinePhaseRegistry registry = new PipelinePhaseRegistry(
                metadataPhase,
                downloadPhase,
                persistPhase,
                new TranscribePhase(null, null),
                new DiarizePhase(null, diarization),
                new FrameSamplePhase(null, frames),
                new OcrPhase(null, ocr),
                new FusePhase(null, fusion),
                new KnowledgePhase(null, knowledge),
                new ContextPhase(null, null, search)
        );
        return new PhaseAvailabilityService(registry).availability();
    }
}
