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
    DONE
}
