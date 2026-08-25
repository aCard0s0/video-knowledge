package com.tradinglabs.vidingest.pipeline.domain;

/**
 * High-level phases of an pipeline run.
 *
 * This is persisted on {@code PipelineRun} to make failures diagnosable and retries safer.
 *
 * <p>The {@code DIARIZE} / {@code FRAME_SAMPLE} / {@code OCR} / {@code FUSE} / {@code KNOWLEDGE}
 * values are introduced in M1 of the knowledge-extraction expansion. Their implementing phases
 * are no-ops in M1; subsequent milestones wire them up to real sidecars and LLM extraction.
 */
public enum PipelineRunPhase {
    CREATED,
    METADATA,
    DOWNLOAD,
    PERSIST,
    TRANSCRIBE,
    DIARIZE,
    FRAME_SAMPLE,
    OCR,
    FUSE,
    KNOWLEDGE,
    CONTEXT,
    DONE;

    /**
     * Whether this phase can be turned off for a single run, and equivalently whether it can be
     * re-run on its own against an already-ingested video. Both questions have the same answer
     * because they have the same cause: an optional phase consumes the persisted video row,
     * while METADATA/DOWNLOAD/PERSIST consume the source URL and the run cannot start without
     * them. CREATED and DONE are run markers, not phases.
     */
    public boolean isOptional() {
        return switch (this) {
            case TRANSCRIBE, DIARIZE, FRAME_SAMPLE, OCR, FUSE, KNOWLEDGE, CONTEXT -> true;
            case CREATED, METADATA, DOWNLOAD, PERSIST, DONE -> false;
        };
    }

    /** The optional phases, in pipeline order. */
    public static java.util.List<PipelineRunPhase> optionalPhases() {
        return java.util.Arrays.stream(values()).filter(PipelineRunPhase::isOptional).toList();
    }
}
