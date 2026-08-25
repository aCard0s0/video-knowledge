package com.tradinglabs.vidingest.pipeline.controller;

import com.tradinglabs.vidingest.api.common.PageResponse;
import com.tradinglabs.vidingest.api.paths.VidIngestApiPaths;
import com.tradinglabs.vidingest.api.pipeline.CreatePipelineRunRequest;
import com.tradinglabs.vidingest.api.pipeline.CreatePipelineRunResponse;
import com.tradinglabs.vidingest.api.pipeline.RunDetails;
import com.tradinglabs.vidingest.api.pipeline.RunItemAuditEvent;
import com.tradinglabs.vidingest.api.pipeline.RunSummary;
import com.tradinglabs.vidingest.api.pipeline.RetryRunRequest;
import com.tradinglabs.vidingest.pipeline.service.PipelineAuditQueryService;
import com.tradinglabs.vidingest.pipeline.service.PipelineIntakeService;
import com.tradinglabs.vidingest.pipeline.service.PipelineService;
import com.tradinglabs.vidingest.pipeline.service.PipelineService.PipelineSkipFlags;
import com.tradinglabs.vidingest.pipeline.service.RunDetailsMapper;
import com.tradinglabs.vidingest.pipeline.service.RunLiveSummaryService;
import com.tradinglabs.vidingest.pipeline.service.RunQueryService;
import com.tradinglabs.vidingest.pipeline.service.RunSummaryPageService;
import jakarta.validation.Valid;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping(value = VidIngestApiPaths.PIPELINES, produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
@Slf4j
@Tag(name = "pipelines", description = "Pipeline run APIs")
public class PipelineController {

    private final PipelineIntakeService pipelineIntakeService;
    private final PipelineService pipelineService;
    private final RunQueryService runQueryService;
    private final RunSummaryPageService runSummaryPageService;
    private final RunLiveSummaryService runLiveSummaryService;
    private final RunDetailsMapper runDetailsMapper;
    private final PipelineAuditQueryService pipelineAuditQueryService;

    @PostMapping
    @Operation(summary = "Create a pipeline run", description = "Starts one asynchronous pipeline run containing one run item per accepted URL.")
    public ResponseEntity<CreatePipelineRunResponse> create(@Valid @RequestBody CreatePipelineRunRequest request) {
        CreatePipelineRunResponse response = pipelineIntakeService.intake(
                request.urls(),
                new PipelineSkipFlags(
                        request.skipTranscription(),
                        request.skipContext(),
                        request.skipDiarize(),
                        request.skipFrames(),
                        request.skipOcr(),
                        request.skipKnowledge()
                )
        );
        HttpStatus status = response.runId() != null ? HttpStatus.ACCEPTED : HttpStatus.BAD_REQUEST;
        return ResponseEntity.status(status).body(response);
    }

    @GetMapping
    @Operation(summary = "List pipeline runs")
    public PageResponse<RunSummary> list(
            @RequestParam(name = "status", defaultValue = "ALL") String status,
            @RequestParam(name = "page", required = false) Integer page,
            @RequestParam(name = "size", required = false) Integer size,
            @RequestParam(name = "ids", required = false) List<UUID> ids,
            @RequestParam(name = "live", defaultValue = "false") boolean live
    ) {
        if (live && ids != null && !ids.isEmpty()) {
            List<RunSummary> items = runLiveSummaryService.listLiveSummariesInOrder(ids);
            return new PageResponse<>(items, 0, items.size(), items.size());
        }
        return runSummaryPageService.list(status, page, size);
    }

    @PostMapping("/{runId}/retry")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(
            summary = "Retry a failed run",
            description = "Resets the run state and retries all FAILED run items asynchronously using the same run id."
    )
    public CreatePipelineRunResponse retry(@PathVariable UUID runId, @Valid @RequestBody RetryRunRequest request) {
        log.info("REST retry run: {} (skipTranscription={}, skipContext={}, skipDiarize={}, skipFrames={}, skipOcr={}, skipKnowledge={})",
                runId, request.skipTranscription(), request.skipContext(),
                request.skipDiarize(), request.skipFrames(), request.skipOcr(), request.skipKnowledge());
        return pipelineService.enqueueRetryBatch(runId, new PipelineSkipFlags(
                request.skipTranscription(),
                request.skipContext(),
                request.skipDiarize(),
                request.skipFrames(),
                request.skipOcr(),
                request.skipKnowledge()
        ));
    }

    @PostMapping("/{runId}/items/{itemId}/retry")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "Retry a failed run item", description = "Resets a single FAILED run item and retries it asynchronously using the same run id and item id.")
    public CreatePipelineRunResponse retryItem(
            @PathVariable UUID runId,
            @PathVariable UUID itemId,
            @Valid @RequestBody RetryRunRequest request
    ) {
        log.info("REST retry run item: runId={} itemId={} (skipTranscription={}, skipContext={}, skipDiarize={}, skipFrames={}, skipOcr={}, skipKnowledge={})",
                runId, itemId, request.skipTranscription(), request.skipContext(),
                request.skipDiarize(), request.skipFrames(), request.skipOcr(), request.skipKnowledge());
        return pipelineService.enqueueRetryItem(runId, itemId, new PipelineSkipFlags(
                request.skipTranscription(),
                request.skipContext(),
                request.skipDiarize(),
                request.skipFrames(),
                request.skipOcr(),
                request.skipKnowledge()
        ));
    }

    @GetMapping("/{runId}")
    @Operation(summary = "Get an pipeline run by id")
    public RunDetails get(@PathVariable UUID runId) {
        return runDetailsMapper.toDetails(runQueryService.getPipelineRun(runId));
    }

    @GetMapping("/{runId}/audit")
    @Operation(summary = "List audit events for a pipeline run", description = "Returns the ordered, append-only event timeline for every run item in this run.")
    public PageResponse<RunItemAuditEvent> auditRun(
            @PathVariable UUID runId,
            @RequestParam(name = "page", required = false) Integer page,
            @RequestParam(name = "size", required = false) Integer size
    ) {
        return pipelineAuditQueryService.listRunEvents(runId, page, size);
    }

    @GetMapping("/{runId}/items/{itemId}/audit")
    @Operation(summary = "List audit events for a single run item", description = "Returns the ordered, append-only event timeline for one run item.")
    public PageResponse<RunItemAuditEvent> auditItem(
            @PathVariable UUID runId,
            @PathVariable UUID itemId,
            @RequestParam(name = "page", required = false) Integer page,
            @RequestParam(name = "size", required = false) Integer size
    ) {
        return pipelineAuditQueryService.listItemEvents(runId, itemId, page, size);
    }
}
