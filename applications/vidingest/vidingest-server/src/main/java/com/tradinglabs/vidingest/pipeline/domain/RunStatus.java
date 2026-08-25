package com.tradinglabs.vidingest.pipeline.domain;

/**
 * Status of an pipeline run.
 */
public enum RunStatus {
    PENDING,       // Run created but not started
    IN_PROGRESS,   // Run is running
    COMPLETED,     // Run completed successfully
    FAILED,        // Run failed
    CANCELLED      // Run was cancelled
}

