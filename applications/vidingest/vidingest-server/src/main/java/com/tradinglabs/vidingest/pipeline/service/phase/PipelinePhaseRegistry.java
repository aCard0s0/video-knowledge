package com.tradinglabs.vidingest.pipeline.service.phase;

import org.springframework.stereotype.Component;

import java.util.List;

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
    }

    public List<PipelinePhase> phases() {
        return orderedPhases;
    }
}
