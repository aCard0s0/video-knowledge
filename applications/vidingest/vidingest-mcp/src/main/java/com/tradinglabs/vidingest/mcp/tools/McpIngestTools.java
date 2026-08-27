package com.tradinglabs.vidingest.mcp.tools;

import com.tradinglabs.vidingest.api.common.PageResponse;
import com.tradinglabs.vidingest.api.fusion.MultimodalSegmentDto;
import com.tradinglabs.vidingest.api.knowledge.KnowledgeUnitDto;
import com.tradinglabs.vidingest.api.knowledge.KnowledgeUnitType;
import com.tradinglabs.vidingest.api.knowledge.RegenerateKnowledgeResult;
import com.tradinglabs.vidingest.api.knowledge.SearchKnowledgeHit;
import com.tradinglabs.vidingest.api.ocr.OcrFrameGroup;
import com.tradinglabs.vidingest.api.pipeline.CreatePipelineRunRequest;
import com.tradinglabs.vidingest.api.pipeline.CreatePipelineRunResponse;
import com.tradinglabs.vidingest.api.pipeline.RunSummary;
import com.tradinglabs.vidingest.api.pipeline.RetryRunRequest;
import com.tradinglabs.vidingest.api.search.SearchChunkResult;
import com.tradinglabs.vidingest.api.speakers.SpeakerDto;
import com.tradinglabs.vidingest.api.videos.DeleteVideoResult;
import com.tradinglabs.vidingest.api.videos.DownloadToDiskResult;
import com.tradinglabs.vidingest.api.videos.DownloadVideoResponse;
import com.tradinglabs.vidingest.api.videos.VideoSummary;
import com.tradinglabs.vidingest.client.VidingestClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class McpIngestTools {

    private static final String SKIP_PHASES_DOC =
            "Optional phases to skip for this run: TRANSCRIBE, DIARIZE, FRAME_SAMPLE, OCR, FUSE, "
            + "KNOWLEDGE, CONTEXT. Empty or omitted runs every phase the deployment has enabled. "
            + "The enrichment phases (DIARIZE/FRAME_SAMPLE/OCR/KNOWLEDGE) are also gated by "
            + "deployment config, so skipping them is only needed to opt out of an enabled one.";

    private static final int DEFAULT_PAGE_SIZE = 200;
    private static final int MAX_ITEMS = 5_000;

    private final VidingestClient client;

    @McpTool(description = "Create one pipeline run containing one run-item per accepted URL. "
            + "skipPhases names the optional phases this run opts out of; omit it or pass an "
            + "empty list to run everything the deployment has enabled.")
    public CreatePipelineRunResponse createPipelineRuns(
            @McpToolParam(description = "List of video URLs (YouTube, Vimeo, or any yt-dlp-supported platform)") List<String> urls,
            @McpToolParam(description = SKIP_PHASES_DOC, required = false) Set<String> skipPhases
    ) {
        return client.createPipelineRun(new CreatePipelineRunRequest(urls, skipPhases));
    }

    @McpTool(description = "Download a video to disk without database persistence. "
            + "Saves to {videoPath}/{channelName}/YYYYMMDD.title.ext with a companion metadata JSON file.")
    public DownloadToDiskResult downloadToDisk(
            @McpToolParam(description = "Video URL") String url
    ) {
        log.info("MCP: Downloading video to disk from {}", url);
        DownloadVideoResponse response = client.downloadVideo(url, true, false);
        DownloadToDiskResult result = response.downloadToDisk();
        if (result == null) {
            throw new IllegalStateException("downloadToDisk expected disk-only response but got null");
        }
        return result;
    }

    @McpTool(description = "Download a video and persist metadata to the database without running the full ingestion pipeline.")
    public VideoSummary downloadToDatabase(
            @McpToolParam(description = "Video URL") String url
    ) {
        log.info("MCP: Downloading video to database from {}", url);
        DownloadVideoResponse response = client.downloadVideo(url, false, false);
        VideoSummary video = response.video();
        if (video == null) {
            throw new IllegalStateException("downloadToDatabase expected database-backed response but got null");
        }
        return video;
    }

    @McpTool(description = "List all ingested videos with their ID, title, source, status, and file path.")
    public List<VideoSummary> listVideos() {
        log.info("MCP: Listing all videos (paged)");
        List<VideoSummary> all = new ArrayList<>();

        int page = 0;
        while (all.size() < MAX_ITEMS) {
            PageResponse<VideoSummary> pageResponse = client.listVideos(page, DEFAULT_PAGE_SIZE);
            List<VideoSummary> items = pageResponse.items();
            if (items.isEmpty()) {
                break;
            }

            for (VideoSummary item : items) {
                if (all.size() >= MAX_ITEMS) {
                    break;
                }
                all.add(item);
            }

            if (all.size() >= pageResponse.total()) {
                break;
            }
            page++;
        }

        if (all.size() >= MAX_ITEMS) {
            log.warn("MCP: listVideos truncated at {} items", MAX_ITEMS);
        }
        return all;
    }

    @McpTool(description = "Get the status and details of a video by its UUID.")
    public VideoSummary getVideoStatus(
            @McpToolParam(description = "Video UUID") String videoId
    ) {
        log.info("MCP: Getting status for video {}", videoId);
        return client.getVideo(UUID.fromString(videoId));
    }

    @McpTool(description = "Delete a video, its file on disk, and all cascading database records "
            + "(transcriptions, segments, context chunks).")
    public DeleteVideoResult deleteVideo(
            @McpToolParam(description = "Video UUID") String videoId
    ) {
        log.info("MCP: Deleting video {}", videoId);
        return client.deleteVideo(UUID.fromString(videoId));
    }

    @McpTool(description = "List pipeline runs (paged). Each run tracks a full ingestion pipeline execution with status and timestamps.")
    public PageResponse<RunSummary> listPipelineRuns(
            @McpToolParam(description = "Filter by status (PENDING, IN_PROGRESS, COMPLETED, FAILED, CANCELLED, or ALL)") String status,
            @McpToolParam(description = "Page index (0-based). Optional.") Integer page,
            @McpToolParam(description = "Page size. Optional.") Integer size
    ) {
        String effectiveStatus = (status == null || status.isBlank()) ? "ALL" : status;
        log.info("MCP: Listing pipeline runs status={} page={} size={}", effectiveStatus, page, size);
        return client.listPipelines(effectiveStatus, page, size);
    }

    @McpTool(description = "Run semantic search over context chunks using pgvector similarity.")
    public List<SearchChunkResult> searchVideos(
            @McpToolParam(description = "Natural language query") String query,
            @McpToolParam(description = "Maximum number of results (1-50)") int limit
    ) {
        log.info("MCP: Searching chunks for query: {}", query);
        return client.search(query, limit);
    }

    @McpTool(description = "Retry a failed pipeline run by UUID. "
            + "Omit skipPhases to retry with the phases the run was created with; pass an empty set "
            + "to run every enabled phase.")
    public CreatePipelineRunResponse retryPipelineRun(
            @McpToolParam(description = "Pipeline run UUID") String pipelineId,
            @McpToolParam(description = SKIP_PHASES_DOC, required = false) Set<String> skipPhases
    ) {
        log.info("MCP: Retrying failed pipeline run {}", pipelineId);
        return client.retryPipeline(UUID.fromString(pipelineId), new RetryRunRequest(skipPhases));
    }

    // ---------------------------------------------------------------------------
    // M8 — knowledge / speakers / multimodal / OCR tools
    // ---------------------------------------------------------------------------

    @McpTool(description = "Semantic search across LLM-extracted knowledge units (entities, topics, "
            + "summaries, claims, questions) using pgvector similarity. Requires vidingest.search.semantic-enabled "
            + "and that the M6 KnowledgePhase has populated vidingest_knowledge_units. "
            + "Pass type=null or empty to search all unit types.")
    public List<SearchKnowledgeHit> searchKnowledge(
            @McpToolParam(description = "Natural language query") String query,
            @McpToolParam(description = "Knowledge unit type filter (ENTITY, TOPIC, SUMMARY, CLAIM, QUESTION). Null or blank = all types.") String type,
            @McpToolParam(description = "Maximum results (1-50)") int limit
    ) {
        log.info("MCP: Searching knowledge units. query={}, type={}, limit={}", query, type, limit);
        KnowledgeUnitType parsed = parseTypeOrNull(type);
        return client.searchKnowledge(query, parsed, limit);
    }

    @McpTool(description = "List all knowledge units for a video, optionally filtered by type. "
            + "Ordered by creation time (which matches the LLM's emission order across batches).")
    public List<KnowledgeUnitDto> getKnowledgeUnits(
            @McpToolParam(description = "Video UUID") String videoId,
            @McpToolParam(description = "Optional unit type filter (ENTITY, TOPIC, SUMMARY, CLAIM, QUESTION). Null or blank = all types.") String type
    ) {
        log.info("MCP: Listing knowledge units. videoId={}, type={}", videoId, type);
        return client.getKnowledgeUnits(UUID.fromString(videoId), parseTypeOrNull(type));
    }

    @McpTool(description = "Re-run LLM knowledge extraction for a single video. Wipes prior knowledge units "
            + "and re-derives them from the current multimodal segments. Equivalent to running the M6 KNOWLEDGE "
            + "phase in isolation — useful after fixing OCR / fusion problems or upgrading the chat model.")
    public RegenerateKnowledgeResult regenerateKnowledge(
            @McpToolParam(description = "Video UUID") String videoId
    ) {
        log.info("MCP: Regenerating knowledge for video {}", videoId);
        return client.regenerateKnowledge(UUID.fromString(videoId));
    }

    @McpTool(description = "List speakers identified in a video by the M2 diarization phase, with "
            + "per-speaker segment counts. The displayName field is the operator-supplied friendly label, "
            + "if any; otherwise fall back to the pyannote label.")
    public List<SpeakerDto> getSpeakers(
            @McpToolParam(description = "Video UUID") String videoId
    ) {
        log.info("MCP: Listing speakers for video {}", videoId);
        return client.getSpeakers(UUID.fromString(videoId));
    }

    @McpTool(description = "Set or clear the operator-supplied displayName for a speaker. "
            + "Pass an empty displayName to clear the override and fall back to the pyannote label.")
    public SpeakerDto renameSpeaker(
            @McpToolParam(description = "Speaker UUID") String speakerId,
            @McpToolParam(description = "Friendly display name (max 255 chars). Empty or null clears the override.") String displayName
    ) {
        log.info("MCP: Renaming speaker {} -> {}", speakerId, displayName);
        return client.renameSpeaker(UUID.fromString(speakerId), displayName);
    }

    @McpTool(description = "Return the fused multimodal timeline for a video — one row per fusion window "
            + "containing transcript, OCR text, and speaker UUIDs. Use fromSeconds/toSeconds to clip to a "
            + "specific span (both null = whole video).")
    public List<MultimodalSegmentDto> getMultimodalTimeline(
            @McpToolParam(description = "Video UUID") String videoId,
            @McpToolParam(description = "Inclusive start of the time window in seconds. Null = video start.") Double fromSeconds,
            @McpToolParam(description = "Exclusive end of the time window in seconds. Null = video end.") Double toSeconds
    ) {
        log.info("MCP: Listing multimodal timeline. videoId={}, from={}, to={}", videoId, fromSeconds, toSeconds);
        return client.getMultimodalTimeline(UUID.fromString(videoId), fromSeconds, toSeconds);
    }

    @McpTool(description = "Return OCR detections for a video, grouped by their source frame. Frames are "
            + "ordered by timestamp. Each group includes the frame's on-disk JPG path for visual review.")
    public List<OcrFrameGroup> getOcrResults(
            @McpToolParam(description = "Video UUID") String videoId
    ) {
        log.info("MCP: Listing OCR results for video {}", videoId);
        return client.getOcrResults(UUID.fromString(videoId));
    }

    private static KnowledgeUnitType parseTypeOrNull(String type) {
        if (type == null || type.isBlank()) return null;
        try {
            return KnowledgeUnitType.valueOf(type.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            log.warn("MCP: Unknown KnowledgeUnitType '{}'; treating as null", type);
            return null;
        }
    }
}

