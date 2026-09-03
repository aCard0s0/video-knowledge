package com.tradinglabs.vidingest.api.pipeline;

import java.time.OffsetDateTime;
import java.util.UUID;

/** One append-only audit event for a run item. See {@link RunSummary} on the typing. */
public record RunItemAuditEvent(
        UUID id,
        UUID runId,
        UUID itemId,
        String eventType,
        int attempt,
        String phase,
        String previousPhase,
        String status,
        String errorCode,
        String error,
        UUID videoId,
        OffsetDateTime occurredAt
) {
}
