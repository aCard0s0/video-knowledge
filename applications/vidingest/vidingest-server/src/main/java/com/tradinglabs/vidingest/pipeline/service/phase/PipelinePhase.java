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

    /**
     * Whether this phase runs for the given item. The default honours the run's opt-outs, so a
     * phase only overrides this to add a deployment toggle or an upstream dependency.
     */
    default boolean applies(PipelinePhaseContext ctx) {
        return !ctx.skipped(phase());
    }

    void execute(PipelinePhaseContext ctx) throws Exception;
}
