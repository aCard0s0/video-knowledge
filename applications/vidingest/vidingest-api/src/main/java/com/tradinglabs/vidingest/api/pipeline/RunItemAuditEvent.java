package com.tradinglabs.vidingest.api.pipeline;

public record RunItemAuditEvent(
        String id,
        String runId,
        String itemId,
        String eventType,
        int attempt,
        String phase,
        String previousPhase,
        String status,
        String errorCode,
        String error,
        String videoId,
        String occurredAt
) {
}
