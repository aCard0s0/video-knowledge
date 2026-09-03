package com.tradinglabs.vidingest.pipeline.service;

import com.tradinglabs.vidingest.api.pipeline.RunItemAuditEvent;
import com.tradinglabs.vidingest.pipeline.domain.PipelineRunItemEvent;
import org.springframework.stereotype.Component;

@Component
public class PipelineAuditMapper {

    public RunItemAuditEvent toDto(PipelineRunItemEvent event) {
        return new RunItemAuditEvent(
                event.getId(),
                event.getPipelineRunId(),
                event.getRunItemId(),
                event.getEventType() != null ? event.getEventType().name() : null,
                event.getAttempt() != null ? event.getAttempt() : 0,
                event.getPhase() != null ? event.getPhase().name() : null,
                event.getPreviousPhase() != null ? event.getPreviousPhase().name() : null,
                event.getStatus() != null ? event.getStatus().name() : null,
                event.getErrorCode() != null ? event.getErrorCode().name() : null,
                event.getErrorMessage(),
                event.getVideoId(),
                event.getOccurredAt()
        );
    }
}
