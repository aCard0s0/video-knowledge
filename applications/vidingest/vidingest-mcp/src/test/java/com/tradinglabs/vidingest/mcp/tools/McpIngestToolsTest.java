package com.tradinglabs.vidingest.mcp.tools;

import com.tradinglabs.vidingest.api.common.PageResponse;
import com.tradinglabs.vidingest.api.pipeline.CreatePipelineRunResponse;
import com.tradinglabs.vidingest.api.pipeline.RunSummary;
import com.tradinglabs.vidingest.api.pipeline.RetryRunRequest;
import com.tradinglabs.vidingest.api.search.SearchChunkResult;
import com.tradinglabs.vidingest.api.videos.DownloadToDiskResult;
import com.tradinglabs.vidingest.api.videos.DownloadVideoResponse;
import com.tradinglabs.vidingest.api.videos.VideoSummary;
import com.tradinglabs.vidingest.client.VidingestClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Set;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class McpIngestToolsTest {

    @Mock
    private VidingestClient client;

    @Test
    void downloadToDiskDelegatesToClientAndReturnsDiskResult() {
        when(client.downloadVideo("https://example.com/v", true, false))
                .thenReturn(new DownloadVideoResponse(null, new DownloadToDiskResult("/videos/v.mp4", "/videos/v.json")));

        McpIngestTools tools = new McpIngestTools(client);

        DownloadToDiskResult result = tools.downloadToDisk("https://example.com/v");
        assertThat(result.videoPath()).isEqualTo("/videos/v.mp4");
        assertThat(result.metadataPath()).isEqualTo("/videos/v.json");
    }

    @Test
    void listVideosAggregatesPagesUntilTotalIsReached() {
        VideoSummary v1 = new VideoSummary(
                "00000000-0000-0000-0000-000000000001",
                null,
                "Video 1",
                "YOUTUBE",
                "abc",
                "DOWNLOADED",
                "/videos/v1.mp4",
                "Channel",
                "2026-03-15T10:00:00"
        );
        VideoSummary v2 = new VideoSummary(
                "00000000-0000-0000-0000-000000000002",
                null,
                "Video 2",
                "YOUTUBE",
                "def",
                "DOWNLOADED",
                "/videos/v2.mp4",
                "Channel",
                "2026-03-15T10:00:01"
        );

        when(client.listVideos(0, 200)).thenReturn(new PageResponse<>(List.of(v1, v2), 0, 200, 2));

        McpIngestTools tools = new McpIngestTools(client);
        List<VideoSummary> all = tools.listVideos();

        assertThat(all).hasSize(2);
        assertThat(all.getFirst().title()).isEqualTo("Video 1");
    }

    @Test
    void listPipelineRunsDelegatesToClientWithDefaults() {
        RunSummary summary = new RunSummary(
                "73d1d901-0f69-4327-875c-6bb46cd80f00",
                "FAILED",
                "",
                "",
                "network timeout",
                "https://www.youtube.com/watch?v=abc123",
                "",
                "",
                "",
                0,
                "2026-03-15T10:00:00",
                "2026-03-15T10:01:00"
        );
        when(client.listPipelines("ALL", null, null)).thenReturn(new PageResponse<>(List.of(summary), 0, 20, 1));

        McpIngestTools tools = new McpIngestTools(client);

        PageResponse<RunSummary> result = tools.listPipelineRuns(null, null, null);
        assertThat(result.items()).hasSize(1);
        assertThat(result.items().getFirst().status()).isEqualTo("FAILED");
    }

    @Test
    void retryPipelineRunDelegatesToClient() {
        UUID runId = UUID.fromString("73d1d901-0f69-4327-875c-6bb46cd80f00");
        when(client.retryPipeline(runId, new RetryRunRequest(Set.of("DIARIZE", "FRAME_SAMPLE", "OCR", "KNOWLEDGE"))))
                .thenReturn(new CreatePipelineRunResponse(runId.toString(), List.of()));

        McpIngestTools tools = new McpIngestTools(client);

        CreatePipelineRunResponse result = tools.retryPipelineRun(
                runId.toString(), Set.of("DIARIZE", "FRAME_SAMPLE", "OCR", "KNOWLEDGE"));
        assertThat(result.runId()).isEqualTo(runId.toString());
    }

    @Test
    void searchVideosDelegatesToClient() {
        SearchChunkResult result = new SearchChunkResult(
                "f0afeb11-2bd9-470f-88fc-caa620632bc4",
                "eadf1978-f899-4f22-90ea-0929879f8253",
                3,
                "snippet",
                "Title",
                "Channel",
                "/videos/title.mp4"
        );
        when(client.search("support zone", 5)).thenReturn(List.of(result));

        McpIngestTools tools = new McpIngestTools(client);
        List<SearchChunkResult> results = tools.searchVideos("support zone", 5);

        assertThat(results).hasSize(1);
        assertThat(results.getFirst().chunkIndex()).isEqualTo(3);
    }
}

