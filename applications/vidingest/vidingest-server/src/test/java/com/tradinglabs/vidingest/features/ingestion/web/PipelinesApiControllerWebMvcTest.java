package com.tradinglabs.vidingest.features.ingestion.web;

import com.tradinglabs.vidingest.commons.VidingestApiExceptionHandler;
import com.tradinglabs.vidingest.api.pipeline.CreatePipelineRunResponse;
import com.tradinglabs.vidingest.pipeline.service.PipelineIntakeService;
import com.tradinglabs.vidingest.pipeline.service.PipelineService;
import com.tradinglabs.vidingest.pipeline.service.PipelineService.PipelineSkipFlags;
import com.tradinglabs.vidingest.pipeline.exceptions.RunNotFoundException;
import com.tradinglabs.vidingest.pipeline.exceptions.RunItemNotFoundException;
import com.tradinglabs.vidingest.pipeline.exceptions.RunRetryNotAllowedException;
import com.tradinglabs.vidingest.pipeline.service.PipelineAuditQueryService;
import com.tradinglabs.vidingest.pipeline.service.RunQueryService;
import com.tradinglabs.vidingest.pipeline.service.RunLiveSummaryService;
import com.tradinglabs.vidingest.pipeline.service.RunSummaryPageService;
import com.tradinglabs.vidingest.pipeline.service.RunSummaryMapper;
import com.tradinglabs.vidingest.pipeline.controller.PipelineController;
import com.tradinglabs.vidingest.pipeline.service.RunDetailsMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = PipelineController.class)
@Import({VidingestApiExceptionHandler.class, RunSummaryMapper.class})
class PipelinesApiControllerWebMvcTest {

    private static final PipelineSkipFlags DEFAULT_SKIP_ALL = new PipelineSkipFlags(
            true, true, true, true, true, true
    );

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PipelineIntakeService pipelineIntakeService;
    @MockitoBean
    private PipelineService pipelineService;
    @MockitoBean
    private RunQueryService runQueryService;
    @MockitoBean
    private RunSummaryPageService runSummaryPageService;
    @MockitoBean
    private RunLiveSummaryService runLiveSummaryService;
    @MockitoBean
    private RunDetailsMapper runDetailsMapper;
    @MockitoBean
    private PipelineAuditQueryService pipelineAuditQueryService;

    @Test
    void createPartiallyAcceptsValidAndInvalidUrls() throws Exception {
        UUID runId = UUID.fromString("f9d44a32-0de5-41bd-a75b-74d01cdfa0b2");
        UUID itemId = UUID.fromString("7a9d0c92-9a9e-4f4d-9ea1-7d7d0e4a43d3");
        when(pipelineIntakeService.intake(
                eq(List.of("https://example.com/video", "ftp://invalid")),
                eq(DEFAULT_SKIP_ALL)))
                .thenReturn(new CreatePipelineRunResponse(
                        runId.toString(),
                        List.of(
                                new CreatePipelineRunResponse.ItemResult(
                                        "https://example.com/video",
                                        CreatePipelineRunResponse.ItemStatus.ACCEPTED,
                                        itemId.toString(),
                                        null
                                ),
                                new CreatePipelineRunResponse.ItemResult(
                                        "ftp://invalid",
                                        CreatePipelineRunResponse.ItemStatus.REJECTED,
                                        null,
                                        "url must start with http:// or https://"
                                )
                        )
                ));

        mockMvc.perform(post("/api/v1/pipelines")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"urls":["https://example.com/video","ftp://invalid"],"skipTranscription":true,"skipContext":true,"skipDiarize":true,"skipFrames":true,"skipOcr":true,"skipKnowledge":true}
                                """))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.runId").value(runId.toString()))
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].url").value("https://example.com/video"))
                .andExpect(jsonPath("$.items[0].status").value("ACCEPTED"))
                .andExpect(jsonPath("$.items[0].itemId").value(itemId.toString()))
                .andExpect(jsonPath("$.items[1].url").value("ftp://invalid"))
                .andExpect(jsonPath("$.items[1].status").value("REJECTED"));
    }

    @Test
    void createReturns400WhenAllUrlsInvalid() throws Exception {
        when(pipelineIntakeService.intake(eq(List.of("ftp://invalid")), eq(DEFAULT_SKIP_ALL)))
                .thenReturn(new CreatePipelineRunResponse(
                        null,
                        List.of(new CreatePipelineRunResponse.ItemResult(
                                "ftp://invalid",
                                CreatePipelineRunResponse.ItemStatus.REJECTED,
                                null,
                                "url must start with http:// or https://"
                        ))
                ));

        mockMvc.perform(post("/api/v1/pipelines")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"urls":["ftp://invalid"],"skipTranscription":true,"skipContext":true,"skipDiarize":true,"skipFrames":true,"skipOcr":true,"skipKnowledge":true}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].status").value("REJECTED"));
    }

    @Test
    void retryReturns404ProblemDetailWhenPipelineMissing() throws Exception {
        UUID missingPipelineId = UUID.fromString("a6e7b0e8-8c7c-4b39-9d8a-d984f3fe5b3d");
        when(pipelineService.enqueueRetryBatch(eq(missingPipelineId), eq(DEFAULT_SKIP_ALL)))
                .thenThrow(new RunNotFoundException(missingPipelineId));

        mockMvc.perform(post("/api/v1/pipelines/{pipelineId}/retry", missingPipelineId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"skipTranscription":true,"skipContext":true,"skipDiarize":true,"skipFrames":true,"skipOcr":true,"skipKnowledge":true}
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Not found"))
                .andExpect(jsonPath("$.detail").value("Pipeline run not found: " + missingPipelineId));
    }

    @Test
    void retryReturns409ProblemDetailWhenNotAllowed() throws Exception {
        UUID pipelineId = UUID.fromString("fe6ed0f6-b7eb-4b84-97d4-31aab431224c");
        when(pipelineService.enqueueRetryBatch(eq(pipelineId), eq(DEFAULT_SKIP_ALL)))
                .thenThrow(new RunRetryNotAllowedException("Only FAILED pipeline runs can be retried"));

        mockMvc.perform(post("/api/v1/pipelines/{pipelineId}/retry", pipelineId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"skipTranscription":true,"skipContext":true,"skipDiarize":true,"skipFrames":true,"skipOcr":true,"skipKnowledge":true}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Conflict"))
                .andExpect(jsonPath("$.detail").value("Only FAILED pipeline runs can be retried"));
    }

    @Test
    void retryItemReturns202WithResponseBody() throws Exception {
        UUID runId = UUID.fromString("f9d44a32-0de5-41bd-a75b-74d01cdfa0b2");
        UUID itemId = UUID.fromString("7a9d0c92-9a9e-4f4d-9ea1-7d7d0e4a43d3");

        when(pipelineService.enqueueRetryItem(runId, itemId, DEFAULT_SKIP_ALL))
                .thenReturn(new CreatePipelineRunResponse(
                        runId.toString(),
                        List.of(new CreatePipelineRunResponse.ItemResult(
                                "https://example.com/video",
                                CreatePipelineRunResponse.ItemStatus.ACCEPTED,
                                itemId.toString(),
                                null
                        ))
                ));

        mockMvc.perform(post("/api/v1/pipelines/{runId}/items/{itemId}/retry", runId, itemId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"skipTranscription":true,"skipContext":true,"skipDiarize":true,"skipFrames":true,"skipOcr":true,"skipKnowledge":true}
                                """))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.runId").value(runId.toString()))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].status").value("ACCEPTED"))
                .andExpect(jsonPath("$.items[0].itemId").value(itemId.toString()));
    }

    @Test
    void retryItemReturns404ProblemDetailWhenPipelineMissing() throws Exception {
        UUID missingRunId = UUID.fromString("a6e7b0e8-8c7c-4b39-9d8a-d984f3fe5b3d");
        UUID itemId = UUID.fromString("7a9d0c92-9a9e-4f4d-9ea1-7d7d0e4a43d3");
        when(pipelineService.enqueueRetryItem(missingRunId, itemId, DEFAULT_SKIP_ALL))
                .thenThrow(new RunNotFoundException(missingRunId));

        mockMvc.perform(post("/api/v1/pipelines/{runId}/items/{itemId}/retry", missingRunId, itemId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"skipTranscription":true,"skipContext":true,"skipDiarize":true,"skipFrames":true,"skipOcr":true,"skipKnowledge":true}
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Not found"))
                .andExpect(jsonPath("$.detail").value("Pipeline run not found: " + missingRunId));
    }

    @Test
    void retryItemReturns404ProblemDetailWhenItemMissing() throws Exception {
        UUID runId = UUID.fromString("f9d44a32-0de5-41bd-a75b-74d01cdfa0b2");
        UUID missingItemId = UUID.fromString("a6e7b0e8-8c7c-4b39-9d8a-d984f3fe5b3d");
        when(pipelineService.enqueueRetryItem(runId, missingItemId, DEFAULT_SKIP_ALL))
                .thenThrow(new RunItemNotFoundException(runId, missingItemId));

        mockMvc.perform(post("/api/v1/pipelines/{runId}/items/{itemId}/retry", runId, missingItemId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"skipTranscription":true,"skipContext":true,"skipDiarize":true,"skipFrames":true,"skipOcr":true,"skipKnowledge":true}
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Not found"))
                .andExpect(jsonPath("$.detail").value("Pipeline run item not found: " + missingItemId + " (run " + runId + ")"));
    }

    @Test
    void retryItemReturns409ProblemDetailWhenNotAllowed() throws Exception {
        UUID runId = UUID.fromString("fe6ed0f6-b7eb-4b84-97d4-31aab431224c");
        UUID itemId = UUID.fromString("7a9d0c92-9a9e-4f4d-9ea1-7d7d0e4a43d3");
        when(pipelineService.enqueueRetryItem(runId, itemId, DEFAULT_SKIP_ALL))
                .thenThrow(new RunRetryNotAllowedException("Only FAILED pipeline runs can be retried"));

        mockMvc.perform(post("/api/v1/pipelines/{runId}/items/{itemId}/retry", runId, itemId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"skipTranscription":true,"skipContext":true,"skipDiarize":true,"skipFrames":true,"skipOcr":true,"skipKnowledge":true}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Conflict"))
                .andExpect(jsonPath("$.detail").value("Only FAILED pipeline runs can be retried"));
    }
}
