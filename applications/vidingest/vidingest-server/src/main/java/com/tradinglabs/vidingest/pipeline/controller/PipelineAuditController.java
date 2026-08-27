package com.tradinglabs.vidingest.pipeline.controller;

import com.tradinglabs.vidingest.api.common.PageResponse;
import com.tradinglabs.vidingest.api.paths.VidIngestApiPaths;
import com.tradinglabs.vidingest.api.pipeline.RunItemAuditEvent;
import com.tradinglabs.vidingest.pipeline.service.PipelineAuditQueryService;
import com.tradinglabs.vidingest.pipeline.service.PipelineAuditQueryService.AuditFilters;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.UUID;

@RestController
@RequestMapping(value = VidIngestApiPaths.AUDIT, produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
@Tag(name = "audit", description = "Cross-run pipeline audit query APIs")
public class PipelineAuditController {

    private final PipelineAuditQueryService pipelineAuditQueryService;

    @GetMapping("/events")
    @Operation(
            operationId = "listEvents",
            summary = "List audit events across runs",
            description = "Query pipeline item audit events with optional filters. Results are sorted by occurredAt DESC. "
                    + "An unrecognised eventType, status, phase or errorCode matches nothing rather than failing the request."
    )
    public PageResponse<RunItemAuditEvent> listEvents(
            @RequestParam(name = "runId", required = false) UUID runId,
            @RequestParam(name = "eventType", required = false) String eventType,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "phase", required = false) String phase,
            @RequestParam(name = "errorCode", required = false) String errorCode,
            @RequestParam(name = "fromDate", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime fromDate,
            @RequestParam(name = "toDate", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime toDate,
            @RequestParam(name = "page", required = false) Integer page,
            @RequestParam(name = "size", required = false) Integer size
    ) {
        AuditFilters filters = new AuditFilters(runId, eventType, status, phase, errorCode, fromDate, toDate);
        return pipelineAuditQueryService.listEvents(filters, page, size);
    }
}
