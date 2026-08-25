package com.tradinglabs.vidingest.pipeline.domain;

/**
 * Stable error codes for pipeline failures.
 *
 * Keep these stable across refactors; they are meant for operators and clients.
 */
public enum PipelineErrorCode {
    DUPLICATE_VIDEO,
    UPSTREAM_TOOL_FAILURE,
    TRANSCRIPTION_FAILURE,
    INVALID_METADATA,
    UNEXPECTED
}

