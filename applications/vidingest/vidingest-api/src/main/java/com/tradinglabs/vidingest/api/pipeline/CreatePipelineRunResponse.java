package com.tradinglabs.vidingest.api.pipeline;

import java.util.List;

public record CreatePipelineRunResponse(
        String runId,
        List<ItemResult> items
) {
    public enum ItemStatus {
        ACCEPTED,
        REJECTED
    }

    public record ItemResult(
            String url,
            ItemStatus status,
            String itemId,
            String reason
    ) {
    }
}

