package com.tradinglabs.vidingest.features.videos.web;

import com.tradinglabs.vidingest.commons.VidingestApiExceptionHandler;
import com.tradinglabs.vidingest.core.download.service.DownloadService;
import com.tradinglabs.vidingest.core.transcription.service.VideoTranscriptionQueryService;
import com.tradinglabs.vidingest.pipeline.service.PipelineService;
import com.tradinglabs.vidingest.search.service.embedding.ContextChunkGenerationService;
import com.tradinglabs.vidingest.api.videos.VideoArtifactCounts;
import com.tradinglabs.vidingest.api.videos.VideoDetail;
import com.tradinglabs.vidingest.api.videos.VideoSummary;
import com.tradinglabs.vidingest.api.transcription.VideoTranscriptionDetails;
import com.tradinglabs.vidingest.videos.service.VideoDeleteService;
import com.tradinglabs.vidingest.videos.service.VideoDetailQueryService;
import com.tradinglabs.vidingest.videos.exceptions.VideoNotFoundException;
import com.tradinglabs.vidingest.videos.service.VideoQueryService;
import com.tradinglabs.vidingest.videos.service.VideoSummaryMapper;
import com.tradinglabs.vidingest.videos.controller.VideosController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = VideosController.class)
@Import({VidingestApiExceptionHandler.class, VideoSummaryMapper.class})
class VideosControllerWebMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PipelineService pipelineService;
    @MockitoBean
    private DownloadService downloadService;
    @MockitoBean
    private VideoQueryService videoQueryService;
    @MockitoBean
    private VideoDeleteService videoDeleteService;
    @MockitoBean
    private VideoTranscriptionQueryService videoTranscriptionQueryService;
    @MockitoBean
    private ContextChunkGenerationService contextChunkGenerationService;
    @MockitoBean
    private VideoDetailQueryService videoDetailQueryService;

    @Test
    void getVideoReturns404ProblemDetailWhenMissing() throws Exception {
        UUID missingId = UUID.fromString("a66bd5af-69a5-4e19-9bc1-df0312716637");
        when(videoQueryService.getById(missingId)).thenThrow(new VideoNotFoundException(missingId));

        mockMvc.perform(get("/api/v1/videos/{id}", missingId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Not found"))
                .andExpect(jsonPath("$.detail").value("Video not found: " + missingId));
    }

    @Test
    void getVideoDetailReturns200WithNestedPayload() throws Exception {
        UUID id = UUID.fromString("5a4b99e4-e22e-4a50-8acb-5e1fe47b65df");

        var video = new VideoSummary(
                id.toString(),
                "run-1",
                "Test video",
                "YOUTUBE",
                "abc123",
                "COMPLETED",
                "/tmp/video.mp4",
                "Test channel",
                "2026-06-03T10:00:00Z"
        );
        var transcription = new VideoTranscriptionDetails(
                true,
                "tx-1",
                "READY",
                "WHISPER",
                "en",
                "2026-06-03T10:00:00Z",
                "2026-06-03T10:01:00Z",
                123,
                "hello world"
        );
        var counts = new VideoArtifactCounts(2, 3, 4, 5, 6);

        when(videoDetailQueryService.getVideoDetail(id)).thenReturn(new VideoDetail(video, transcription, counts));

        mockMvc.perform(get("/api/v1/videos/{id}/detail", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.video.id").value(id.toString()))
                .andExpect(jsonPath("$.transcription.present").value(true))
                .andExpect(jsonPath("$.counts.speakers").value(2))
                .andExpect(jsonPath("$.counts.ocrFrames").value(3))
                .andExpect(jsonPath("$.counts.multimodalSegments").value(4))
                .andExpect(jsonPath("$.counts.transcriptionSegments").value(5))
                .andExpect(jsonPath("$.counts.knowledgeUnits").value(6));
    }

    @Test
    void transcriptionEndpointsReturn404WhenTheVideoIsUnknown() throws Exception {
        // Both endpoints exist to give a consistent 404 rather than an empty transcription for
        // an id that was never ingested. They check existence without hydrating the entity, so
        // the guard is a doThrow on ensureExists — nothing else in the call would 404.
        UUID missingId = UUID.fromString("bd0f2d31-3c96-4a4e-9f8b-2a2a7f0f5f11");
        doThrow(new VideoNotFoundException(missingId)).when(videoQueryService).ensureExists(missingId);

        for (String path : new String[]{"/api/v1/videos/{id}/transcription",
                                        "/api/v1/videos/{id}/transcription/segments"}) {
            mockMvc.perform(get(path, missingId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.title").value("Not found"))
                    .andExpect(jsonPath("$.detail").value("Video not found: " + missingId));
        }

        verifyNoInteractions(videoTranscriptionQueryService);
    }

    @Test
    void getVideoDetailReturns404ProblemDetailWhenMissing() throws Exception {
        UUID missingId = UUID.fromString("e5fdc769-eae7-4df1-bf48-43ffb3e1bb9f");
        when(videoDetailQueryService.getVideoDetail(missingId)).thenThrow(new VideoNotFoundException(missingId));

        mockMvc.perform(get("/api/v1/videos/{id}/detail", missingId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Not found"))
                .andExpect(jsonPath("$.detail").value("Video not found: " + missingId));
    }
}

