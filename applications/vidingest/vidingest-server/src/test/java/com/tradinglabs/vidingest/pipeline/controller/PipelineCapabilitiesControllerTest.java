package com.tradinglabs.vidingest.pipeline.controller;

import com.tradinglabs.vidingest.api.pipeline.PipelineCapabilities;
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
import com.tradinglabs.vidingest.youtube.config.YoutubeSyncProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * The capabilities endpoint answers from the phases' own {@code applies}, so these assert the two
 * things that separate it from re-reading the config: it reports the deployment toggle, and it
 * reports it for a run that skips nothing.
 *
 * <p>Only the optional phases are reported. METADATA/DOWNLOAD/PERSIST always run and the server
 * 400s a request that tries to skip them, so listing them here would invite the console's picker
 * to offer a toggle that cannot be turned off.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PipelineCapabilitiesControllerTest {

    @Mock private MetadataPhase metadataPhase;
    @Mock private DownloadPhase downloadPhase;
    @Mock private PersistPhase persistPhase;

    @Test
    void reportsTheDeploymentToggleForEveryOptionalPhase() {
        // The compose defaults: enrichment off, fusion and context on.
        assertThat(capabilities(false).enabledPhases())
                .contains(PipelineRunPhase.FUSE.name(), PipelineRunPhase.CONTEXT.name())
                .doesNotContain(
                        PipelineRunPhase.DIARIZE.name(),
                        PipelineRunPhase.FRAME_SAMPLE.name(),
                        PipelineRunPhase.OCR.name(),
                        PipelineRunPhase.KNOWLEDGE.name());
    }

    @Test
    void turningTheTogglesOnFlipsEveryAnswer() {
        assertThat(capabilities(true).enabledPhases())
                .containsExactlyInAnyOrderElementsOf(
                        PipelineRunPhase.optionalPhases().stream().map(Enum::name).toList());
    }

    @Test
    void neverReportsAMandatoryPhase() {
        assertThat(capabilities(true).enabledPhases())
                .doesNotContain(
                        PipelineRunPhase.METADATA.name(),
                        PipelineRunPhase.DOWNLOAD.name(),
                        PipelineRunPhase.PERSIST.name());
    }

    @Test
    void carriesTheChannelSyncLimit() {
        assertThat(capabilities(true).channelSyncLimit())
                .isEqualTo(new YoutubeSyncProperties().getPlaylistLimit());
    }

    /**
     * OCR on, frame sampling off. Asserted here and not only at {@code OcrPhase.applies} because
     * this endpoint is the contract the console reads, and advertising a phase that cannot produce
     * anything is the shape of the original defect: the full pipeline run entered OCR and worked
     * through an empty frame set. Reachable in one click, since the connections API can flip OCR
     * but has no entry for frame sampling.
     *
     * <p>The helper took one flag for every toggle, so this mixed combination could not be
     * expressed and went uncovered — the all-on and all-off cases both pass with the gate removed.
     */
    @Test
    void doesNotReportOcrWhenFrameSamplingIsOffEvenThoughOcrIsOn() {
        PipelineCapabilities caps = capabilities(true, /* framesEnabled */ false);

        assertThat(caps.enabledPhases())
                .doesNotContain(PipelineRunPhase.OCR.name(), PipelineRunPhase.FRAME_SAMPLE.name())
                // The other enrichment phases are unaffected — this is a dependency, not a cascade.
                .contains(PipelineRunPhase.DIARIZE.name(), PipelineRunPhase.KNOWLEDGE.name());
    }

    private PipelineCapabilities capabilities(boolean enrichmentEnabled) {
        return capabilities(enrichmentEnabled, enrichmentEnabled);
    }

    private PipelineCapabilities capabilities(boolean enrichmentEnabled, boolean framesEnabled) {
        // The registry indexes by phase(), so the three mandatory phases have to answer with theirs;
        // nothing else about them is reached — they are never optional, so never probed.
        when(metadataPhase.phase()).thenReturn(PipelineRunPhase.METADATA);
        when(downloadPhase.phase()).thenReturn(PipelineRunPhase.DOWNLOAD);
        when(persistPhase.phase()).thenReturn(PipelineRunPhase.PERSIST);

        DiarizationConfig diarization = new DiarizationConfig();
        diarization.setEnabled(enrichmentEnabled);
        FrameSamplingConfig frames = new FrameSamplingConfig();
        frames.setEnabled(framesEnabled);
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
                new OcrPhase(null, ocr, frames),
                new FusePhase(null, fusion),
                new KnowledgePhase(null, knowledge),
                new ContextPhase(null, null, search)
        );
        return new PipelineCapabilitiesController(registry, new YoutubeSyncProperties()).get();
    }
}
