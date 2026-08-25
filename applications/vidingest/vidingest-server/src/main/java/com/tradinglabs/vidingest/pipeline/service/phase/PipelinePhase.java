package com.tradinglabs.vidingest.pipeline.service.phase;

import com.tradinglabs.vidingest.pipeline.domain.PipelineRunPhase;

public sealed interface PipelinePhase
        permits MetadataPhase,
                DownloadPhase,
                PersistPhase,
                TranscribePhase,
                DiarizePhase,
                FrameSamplePhase,
                OcrPhase,
                FusePhase,
                KnowledgePhase,
                ContextPhase {

    PipelineRunPhase phase();

    default boolean applies(PipelinePhaseContext ctx) {
        return true;
    }

    void execute(PipelinePhaseContext ctx) throws Exception;
}
