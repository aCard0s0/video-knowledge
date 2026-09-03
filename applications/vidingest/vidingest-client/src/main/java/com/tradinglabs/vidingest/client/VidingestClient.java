package com.tradinglabs.vidingest.client;

import com.tradinglabs.vidingest.api.common.PageResponse;
import com.tradinglabs.vidingest.api.fusion.MultimodalSegmentDto;
import com.tradinglabs.vidingest.api.knowledge.KnowledgeUnitDto;
import com.tradinglabs.vidingest.api.knowledge.KnowledgeUnitType;
import com.tradinglabs.vidingest.api.knowledge.SearchKnowledgeHit;
import com.tradinglabs.vidingest.api.ocr.OcrFrameGroup;
import com.tradinglabs.vidingest.api.paths.VidIngestApiPaths;
import com.tradinglabs.vidingest.api.pipeline.CreatePipelineRunRequest;
import com.tradinglabs.vidingest.api.pipeline.CreatePipelineRunResponse;
import com.tradinglabs.vidingest.api.pipeline.RunDetails;
import com.tradinglabs.vidingest.api.pipeline.RunSummary;
import com.tradinglabs.vidingest.api.pipeline.RetryRunRequest;
import com.tradinglabs.vidingest.api.search.SearchChunkResult;
import com.tradinglabs.vidingest.api.speakers.RenameSpeakerRequest;
import com.tradinglabs.vidingest.api.speakers.SpeakerDto;
import com.tradinglabs.vidingest.api.videos.DeleteVideoResult;
import com.tradinglabs.vidingest.api.videos.DownloadVideoRequest;
import com.tradinglabs.vidingest.api.videos.DownloadVideoResponse;
import com.tradinglabs.vidingest.api.videos.RunVideoPhaseResult;
import com.tradinglabs.vidingest.api.videos.VideoSummary;
import com.tradinglabs.web.client.RestClientErrorSupport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.util.List;
import java.util.UUID;

@Slf4j
@RequiredArgsConstructor
public class VidingestClient {

    private final RestClient restClient;

    public DownloadVideoResponse downloadVideo(String url, boolean diskOnly, boolean progress) {
        return downloadVideo(new DownloadVideoRequest(url, diskOnly, progress));
    }

    public DownloadVideoResponse downloadVideo(DownloadVideoRequest request) {
        String endpoint = VidIngestApiPaths.VIDEOS_DOWNLOAD;
        try {
            DownloadVideoResponse body = restClient.post()
                    .uri(endpoint)
                    .header("Content-Type", "application/json")
                    .body(request)
                    .retrieve()
                    .body(DownloadVideoResponse.class);
            return requireBody(body, endpoint);
        } catch (RestClientResponseException e) {
            throw wrapHttpError(endpoint, e);
        } catch (RestClientException e) {
            throw wrapTransportError(endpoint, e);
        }
    }

    public PageResponse<VideoSummary> listVideos() {
        return listVideos(null, null, null, null, null);
    }

    public PageResponse<VideoSummary> listVideos(Integer page, Integer size) {
        return listVideos(null, null, null, page, size);
    }

    public PageResponse<VideoSummary> listVideos(String status, String source, String channelName, Integer page, Integer size) {
        String endpoint = VidIngestApiPaths.VIDEOS;
        try {
            PageResponse<VideoSummary> body = restClient.get()
                    .uri(uriBuilder -> {
                        var b = uriBuilder.path(endpoint);
                        if (status != null && !status.isBlank()) {
                            b = b.queryParam("status", status);
                        }
                        if (source != null && !source.isBlank()) {
                            b = b.queryParam("source", source);
                        }
                        if (channelName != null && !channelName.isBlank()) {
                            b = b.queryParam("channelName", channelName);
                        }
                        if (page != null) {
                            b = b.queryParam("page", page);
                        }
                        if (size != null) {
                            b = b.queryParam("size", size);
                        }
                        return b.build();
                    })
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {
                    });
            return requireBody(body, endpoint);
        } catch (RestClientResponseException e) {
            throw wrapHttpError(endpoint, e);
        } catch (RestClientException e) {
            throw wrapTransportError(endpoint, e);
        }
    }

    public VideoSummary getVideo(UUID videoId) {
        String endpoint = VidIngestApiPaths.VIDEOS + "/" + videoId;
        try {
            VideoSummary body = restClient.get()
                    .uri(VidIngestApiPaths.VIDEO, videoId)
                    .retrieve()
                    .body(VideoSummary.class);
            return requireBody(body, endpoint);
        } catch (RestClientResponseException e) {
            throw wrapHttpError(endpoint, e);
        } catch (RestClientException e) {
            throw wrapTransportError(endpoint, e);
        }
    }

    public DeleteVideoResult deleteVideo(UUID videoId) {
        String endpoint = VidIngestApiPaths.VIDEOS + "/" + videoId;
        try {
            DeleteVideoResult body = restClient.delete()
                    .uri(VidIngestApiPaths.VIDEO, videoId)
                    .retrieve()
                    .body(DeleteVideoResult.class);
            return requireBody(body, endpoint);
        } catch (RestClientResponseException e) {
            throw wrapHttpError(endpoint, e);
        } catch (RestClientException e) {
            throw wrapTransportError(endpoint, e);
        }
    }

    public CreatePipelineRunResponse createPipelineRun(CreatePipelineRunRequest request) {
        String endpoint = VidIngestApiPaths.PIPELINES;
        try {
            CreatePipelineRunResponse body = restClient.post()
                    .uri(endpoint)
                    .header("Content-Type", "application/json")
                    .body(request)
                    .retrieve()
                    .body(CreatePipelineRunResponse.class);
            return requireBody(body, endpoint);
        } catch (RestClientResponseException e) {
            throw wrapHttpError(endpoint, e);
        } catch (RestClientException e) {
            throw wrapTransportError(endpoint, e);
        }
    }

    public PageResponse<RunSummary> listPipelines(String status) {
        return listPipelines(status, null, null);
    }

    public PageResponse<RunSummary> listPipelines(String status, Integer page, Integer size) {
        String endpoint = VidIngestApiPaths.PIPELINES;
        try {
            PageResponse<RunSummary> body = restClient.get()
                    .uri(uriBuilder -> {
                        var b = uriBuilder.path(endpoint);
                        if (status != null && !status.isBlank()) {
                            b = b.queryParam("status", status);
                        }
                        if (page != null) {
                            b = b.queryParam("page", page);
                        }
                        if (size != null) {
                            b = b.queryParam("size", size);
                        }
                        return b.build();
                    })
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {
                    });
            return requireBody(body, endpoint);
        } catch (RestClientResponseException e) {
            throw wrapHttpError(endpoint, e);
        } catch (RestClientException e) {
            throw wrapTransportError(endpoint, e);
        }
    }

    public RunDetails getPipeline(UUID pipelineId) {
        String endpoint = VidIngestApiPaths.PIPELINES + "/" + pipelineId;
        try {
            RunDetails body = restClient.get()
                    .uri(VidIngestApiPaths.PIPELINE, pipelineId)
                    .retrieve()
                    .body(RunDetails.class);
            return requireBody(body, endpoint);
        } catch (RestClientResponseException e) {
            throw wrapHttpError(endpoint, e);
        } catch (RestClientException e) {
            throw wrapTransportError(endpoint, e);
        }
    }

    public CreatePipelineRunResponse retryPipeline(UUID pipelineId, RetryRunRequest request) {
        String endpoint = VidIngestApiPaths.PIPELINES + "/" + pipelineId + "/retry";
        try {
            CreatePipelineRunResponse body = restClient.post()
                    .uri(VidIngestApiPaths.PIPELINE_RETRY, pipelineId)
                    .header("Content-Type", "application/json")
                    .body(request)
                    .retrieve()
                    .body(CreatePipelineRunResponse.class);
            return requireBody(body, endpoint);
        } catch (RestClientResponseException e) {
            throw wrapHttpError(endpoint, e);
        } catch (RestClientException e) {
            throw wrapTransportError(endpoint, e);
        }
    }

    public CreatePipelineRunResponse retryPipelineItem(UUID runId, UUID itemId, RetryRunRequest request) {
        String endpoint = VidIngestApiPaths.PIPELINES + "/" + runId + "/items/" + itemId + "/retry";
        try {
            CreatePipelineRunResponse body = restClient.post()
                    .uri(VidIngestApiPaths.PIPELINE_ITEM_RETRY, runId, itemId)
                    .header("Content-Type", "application/json")
                    .body(request)
                    .retrieve()
                    .body(CreatePipelineRunResponse.class);
            return requireBody(body, endpoint);
        } catch (RestClientResponseException e) {
            throw wrapHttpError(endpoint, e);
        } catch (RestClientException e) {
            throw wrapTransportError(endpoint, e);
        }
    }

    public List<SearchChunkResult> search(String query, int limit) {
        String endpoint = VidIngestApiPaths.SEARCH;
        try {
            List<SearchChunkResult> body = restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path(endpoint)
                            .queryParam("query", query)
                            .queryParam("limit", limit)
                            .build())
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {
                    });
            return requireBody(body, endpoint);
        } catch (RestClientResponseException e) {
            throw wrapHttpError(endpoint, e);
        } catch (RestClientException e) {
            throw wrapTransportError(endpoint, e);
        }
    }

    // --- M8: knowledge / speakers / multimodal / ocr ---

    /**
     * Cross-video semantic search over knowledge units (M8). Pass {@code type=null} to
     * search across all unit types.
     */
    public List<SearchKnowledgeHit> searchKnowledge(String query, KnowledgeUnitType type, int limit) {
        String endpoint = VidIngestApiPaths.KNOWLEDGE_SEARCH;
        try {
            List<SearchKnowledgeHit> body = restClient.get()
                    .uri(uriBuilder -> {
                        var b = uriBuilder.path(endpoint).queryParam("query", query).queryParam("limit", limit);
                        if (type != null) {
                            b = b.queryParam("type", type.name());
                        }
                        return b.build();
                    })
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {
                    });
            return requireBody(body, endpoint);
        } catch (RestClientResponseException e) {
            throw wrapHttpError(endpoint, e);
        } catch (RestClientException e) {
            throw wrapTransportError(endpoint, e);
        }
    }

    /** Returns all knowledge units for a video, optionally filtered by type. */
    public List<KnowledgeUnitDto> getKnowledgeUnits(UUID videoId, KnowledgeUnitType type) {
        String endpoint = VidIngestApiPaths.VIDEO_KNOWLEDGE.replace("{videoId}", videoId.toString());
        try {
            List<KnowledgeUnitDto> body = restClient.get()
                    .uri(uriBuilder -> {
                        var b = uriBuilder.path(VidIngestApiPaths.VIDEO_KNOWLEDGE);
                        if (type != null) {
                            b = b.queryParam("type", type.name());
                        }
                        return b.build(videoId);
                    })
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {
                    });
            return requireBody(body, endpoint);
        } catch (RestClientResponseException e) {
            throw wrapHttpError(endpoint, e);
        } catch (RestClientException e) {
            throw wrapTransportError(endpoint, e);
        }
    }

    /**
     * Re-runs one pipeline phase against an already-ingested video, synchronously.
     *
     * <p>One method for all seven optional phases, replacing the per-phase twins
     * ({@code /knowledge/regenerate}, {@code /context/regenerate}) this client used to carry: the
     * server routes every phase through {@code PipelinePhaseRegistry}, so a rerun cannot drift
     * from what the pipeline runs, and a caller naming a mandatory or unknown phase gets a 400
     * rather than a missing method.
     *
     * @param phase TRANSCRIBE | DIARIZE | FRAME_SAMPLE | OCR | FUSE | KNOWLEDGE | CONTEXT
     */
    public RunVideoPhaseResult runVideoPhase(UUID videoId, String phase) {
        String endpoint = VidIngestApiPaths.VIDEO_PHASE_RUN
                .replace("{videoId}", videoId.toString())
                .replace("{phase}", phase);
        try {
            RunVideoPhaseResult body = restClient.post()
                    .uri(VidIngestApiPaths.VIDEO_PHASE_RUN, videoId, phase)
                    .retrieve()
                    .body(RunVideoPhaseResult.class);
            return requireBody(body, endpoint);
        } catch (RestClientResponseException e) {
            throw wrapHttpError(endpoint, e);
        } catch (RestClientException e) {
            throw wrapTransportError(endpoint, e);
        }
    }

    /** Lists speakers for a video with segment counts. */
    public List<SpeakerDto> getSpeakers(UUID videoId) {
        String endpoint = VidIngestApiPaths.VIDEO_SPEAKERS.replace("{videoId}", videoId.toString());
        try {
            List<SpeakerDto> body = restClient.get()
                    .uri(VidIngestApiPaths.VIDEO_SPEAKERS, videoId)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {
                    });
            return requireBody(body, endpoint);
        } catch (RestClientResponseException e) {
            throw wrapHttpError(endpoint, e);
        } catch (RestClientException e) {
            throw wrapTransportError(endpoint, e);
        }
    }

    /** Renames a speaker. {@code null} or blank {@code displayName} clears the override. */
    public SpeakerDto renameSpeaker(UUID speakerId, String displayName) {
        String endpoint = VidIngestApiPaths.SPEAKER.replace("{speakerId}", speakerId.toString());
        try {
            SpeakerDto body = restClient.patch()
                    .uri(VidIngestApiPaths.SPEAKER, speakerId)
                    .header("Content-Type", "application/json")
                    .body(new RenameSpeakerRequest(displayName))
                    .retrieve()
                    .body(SpeakerDto.class);
            return requireBody(body, endpoint);
        } catch (RestClientResponseException e) {
            throw wrapHttpError(endpoint, e);
        } catch (RestClientException e) {
            throw wrapTransportError(endpoint, e);
        }
    }

    /** Returns the M5 multimodal-fusion timeline, optionally clipped to a time window. */
    public List<MultimodalSegmentDto> getMultimodalTimeline(UUID videoId, Double fromSeconds, Double toSeconds) {
        String endpoint = VidIngestApiPaths.VIDEO_MULTIMODAL_TIMELINE.replace("{videoId}", videoId.toString());
        try {
            List<MultimodalSegmentDto> body = restClient.get()
                    .uri(uriBuilder -> {
                        var b = uriBuilder.path(VidIngestApiPaths.VIDEO_MULTIMODAL_TIMELINE);
                        if (fromSeconds != null) b = b.queryParam("fromSeconds", fromSeconds);
                        if (toSeconds != null) b = b.queryParam("toSeconds", toSeconds);
                        return b.build(videoId);
                    })
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {
                    });
            return requireBody(body, endpoint);
        } catch (RestClientResponseException e) {
            throw wrapHttpError(endpoint, e);
        } catch (RestClientException e) {
            throw wrapTransportError(endpoint, e);
        }
    }

    /** Returns OCR detections grouped by frame, ordered by frame timestamp. */
    public List<OcrFrameGroup> getOcrResults(UUID videoId) {
        String endpoint = VidIngestApiPaths.VIDEO_OCR.replace("{videoId}", videoId.toString());
        try {
            List<OcrFrameGroup> body = restClient.get()
                    .uri(VidIngestApiPaths.VIDEO_OCR, videoId)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {
                    });
            return requireBody(body, endpoint);
        } catch (RestClientResponseException e) {
            throw wrapHttpError(endpoint, e);
        } catch (RestClientException e) {
            throw wrapTransportError(endpoint, e);
        }
    }

    private VidingestClientException wrapHttpError(String endpoint, RestClientResponseException e) {
        return RestClientErrorSupport.httpError(endpoint, e, VidingestClientException::new);
    }

    private VidingestClientException wrapTransportError(String endpoint, RestClientException e) {
        return RestClientErrorSupport.transportError(endpoint, e, VidingestClientException::new);
    }

    private <T> T requireBody(T body, String endpoint) {
        if (body == null) {
            throw RestClientErrorSupport.emptyBody(endpoint, VidingestClientException::new);
        }
        return body;
    }
}

