package com.tradinglabs.vidingest.pipeline.service;

import com.tradinglabs.vidingest.api.pipeline.RunItemAuditEvent;
import com.tradinglabs.vidingest.pipeline.domain.PipelineRunItemEvent;
import org.springframework.stereotype.Component;

@Component
public class PipelineAuditMapper {

    public RunItemAuditEvent toDto(PipelineRunItemEvent event) {
        return new RunItemAuditEvent(
                event.getId() != null ? event.getId().toString() : "",
                event.getPipelineRunId() != null ? event.getPipelineRunId().toString() : "",
                event.getRunItemId() != null ? event.getRunItemId().toString() : "",
                event.getEventType() != null ? event.getEventType().name() : "",
                event.getAttempt() != null ? event.getAttempt() : 0,
                event.getPhase() != null ? event.getPhase().name() : "",
                event.getPreviousPhase() != null ? event.getPreviousPhase().name() : "",
                event.getStatus() != null ? event.getStatus().name() : "",
                event.getErrorCode() != null ? event.getErrorCode().name() : "",
                safe(event.getErrorMessage()),
                event.getVideoId() != null ? event.getVideoId().toString() : "",
                event.getOccurredAt() != null ? event.getOccurredAt().toString() : ""
        );
    }

    private String safe(String value) {
        return value != null ? value : "";
    }
}
