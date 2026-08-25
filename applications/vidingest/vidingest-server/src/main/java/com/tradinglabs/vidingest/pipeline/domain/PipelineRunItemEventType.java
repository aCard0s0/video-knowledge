package com.tradinglabs.vidingest.pipeline.domain;

public enum PipelineRunItemEventType {
    ITEM_CREATED,
    ITEM_PHASE_ENTERED,
    /**
     * Emitted when a phase finishes successfully — pairs with the preceding
     * {@code ITEM_PHASE_ENTERED} so API consumers can render a phase as
     * "Completed" instead of leaving it stuck at "In progress".
     */
    ITEM_PHASE_COMPLETED,
    ITEM_VIDEO_ATTACHED,
    ITEM_COMPLETED,
    ITEM_FAILED,
    ITEM_CANCELLED,
    ITEM_RETRY_REQUESTED
}
