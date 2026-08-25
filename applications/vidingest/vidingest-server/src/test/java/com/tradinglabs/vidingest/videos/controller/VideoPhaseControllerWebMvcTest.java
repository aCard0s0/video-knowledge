package com.tradinglabs.vidingest.videos.controller;

import com.tradinglabs.vidingest.api.videos.RunVideoPhaseResult;
import com.tradinglabs.vidingest.commons.VidingestApiExceptionHandler;
import com.tradinglabs.vidingest.core.ocr.service.OcrFailureException;
import com.tradinglabs.vidingest.videos.exceptions.VideoNotFoundException;
import com.tradinglabs.vidingest.videos.service.VideoPhaseRunnerService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pins the HTTP contract of the per-phase rerun endpoint. It used to answer 200 with
 * {@code {"status":"ERROR"}} for every failure, which no HTTP client could distinguish from
 * success; failures now render as ProblemDetail with the same status codes as the rest of the
 * API.
 */
@WebMvcTest(controllers = VideoPhaseController.class)
@Import(VidingestApiExceptionHandler.class)
class VideoPhaseControllerWebMvcTest {

    private static final UUID VIDEO_ID = UUID.fromString("29161251-874a-44b4-b875-1b9e230727ab");
    private static final String URL = "/api/v1/videos/" + VIDEO_ID + "/phases/OCR/run";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private VideoPhaseRunnerService runner;

    @Test
    void returnsTheResultOnSuccess() throws Exception {
        when(runner.runPhase(eq(VIDEO_ID), eq("OCR")))
                .thenReturn(new RunVideoPhaseResult(VIDEO_ID.toString(), "OCR", 138469L, 1972));

        mockMvc.perform(post(URL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phase").value("OCR"))
                .andExpect(jsonPath("$.rowsAffected").value(1972))
                .andExpect(jsonPath("$.elapsedMs").value(138469));
    }

    @Test
    void rendersAPhaseFailureAsA502ProblemDetail() throws Exception {
        when(runner.runPhase(any(), any())).thenThrow(new OcrFailureException("paddleocr-server unreachable"));

        mockMvc.perform(post(URL))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.title").value("Upstream failure"))
                .andExpect(jsonPath("$.detail").value("paddleocr-server unreachable"));
    }

    @Test
    void rendersAnUnusablePhaseNameAsA400ProblemDetail() throws Exception {
        when(runner.runPhase(any(), any())).thenThrow(new IllegalArgumentException("Unsupported phase: DOWNLOAD"));

        mockMvc.perform(post(URL))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Unsupported phase: DOWNLOAD"));
    }

    @Test
    void rendersAnUnknownVideoAsA404ProblemDetail() throws Exception {
        when(runner.runPhase(any(), any())).thenThrow(new VideoNotFoundException(VIDEO_ID));

        mockMvc.perform(post(URL))
                .andExpect(status().isNotFound());
    }

    @Test
    void rendersAGenuineBugAsA500ProblemDetail() throws Exception {
        // Used to be a 409: the handler mapped IllegalStateException wholesale to Conflict, so
        // any unexpected illegal state read to the caller as something worth retrying.
        when(runner.runPhase(any(), any())).thenThrow(new IllegalStateException("bad state"));

        mockMvc.perform(post(URL))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.title").value("Internal error"));
    }
}
