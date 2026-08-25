package com.tradinglabs.vidingest.pipeline.service.phase;

import com.tradinglabs.vidingest.pipeline.domain.PipelineRunPhase;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Registry that defines the canonical execution order of the pipeline phases.
 *
 * <p>Order (M1):
 * <pre>
 *   METADATA → DOWNLOAD → PERSIST → TRANSCRIBE → DIARIZE → FRAME_SAMPLE → OCR → FUSE → KNOWLEDGE → CONTEXT
 * </pre>
 * The five new phases between {@code TRANSCRIBE} and {@code CONTEXT} are stubs in M1
 * (their {@code applies(ctx)} always returns {@code false}); subsequent milestones replace
 * them with real implementations one at a time.
 */
@Component
public class PipelinePhaseRegistry {

    private final List<PipelinePhase> orderedPhases;
    private final Map<PipelineRunPhase, PipelinePhase> byPhase;

    public PipelinePhaseRegistry(
            MetadataPhase metadataPhase,
            DownloadPhase downloadPhase,
            PersistPhase persistPhase,
            TranscribePhase transcribePhase,
            DiarizePhase diarizePhase,
            FrameSamplePhase frameSamplePhase,
            OcrPhase ocrPhase,
            FusePhase fusePhase,
            KnowledgePhase knowledgePhase,
            ContextPhase contextPhase
    ) {
        this.orderedPhases = List.of(
                metadataPhase,
                downloadPhase,
                persistPhase,
                transcribePhase,
                diarizePhase,
                frameSamplePhase,
                ocrPhase,
                fusePhase,
                knowledgePhase,
                contextPhase
        );

        Map<PipelineRunPhase, PipelinePhase> index = new EnumMap<>(PipelineRunPhase.class);
        for (PipelinePhase phase : this.orderedPhases) {
            index.put(phase.phase(), phase);
        }
        this.byPhase = index;
    }

    public List<PipelinePhase> phases() {
        return orderedPhases;
    }

    /**
     * Look up the implementation for a phase. Empty for enum constants that are run markers
     * rather than phases ({@code CREATED}, {@code DONE}).
     */
    public Optional<PipelinePhase> byPhase(PipelineRunPhase phase) {
        return Optional.ofNullable(byPhase.get(phase));
    }
}
