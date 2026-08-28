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
 * <p>Order:
 * <pre>
 *   METADATA → DOWNLOAD → PERSIST → TRANSCRIBE → DIARIZE → FRAME_SAMPLE → OCR → FUSE → KNOWLEDGE → CONTEXT
 * </pre>
 * Order is this constructor's argument list, not the {@link PipelineRunPhase} declaration order —
 * reordering here reorders the pipeline. Every phase is implemented; whether one runs is decided
 * by its own {@code applies(ctx)}, which combines the run's skip set with a
 * {@code vidingest.<phase>.enabled} toggle that mostly defaults to {@code false}.
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
