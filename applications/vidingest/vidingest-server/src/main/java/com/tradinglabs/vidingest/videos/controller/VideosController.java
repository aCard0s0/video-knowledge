package com.tradinglabs.vidingest.videos.controller;

import com.tradinglabs.vidingest.api.common.PageResponse;
import com.tradinglabs.vidingest.api.paths.VidIngestApiPaths;
import com.tradinglabs.vidingest.api.transcription.TranscriptionSegmentSummary;
import com.tradinglabs.vidingest.api.transcription.VideoTranscriptionDetails;
import com.tradinglabs.vidingest.api.videos.VideoDetail;
import com.tradinglabs.vidingest.api.videos.DeleteVideoResult;
import com.tradinglabs.vidingest.api.videos.DownloadToDiskResult;
import com.tradinglabs.vidingest.api.videos.DownloadVideoRequest;
import com.tradinglabs.vidingest.api.videos.DownloadVideoResponse;
import com.tradinglabs.vidingest.api.videos.RegenerateContextResult;
import com.tradinglabs.vidingest.api.videos.VideoSummary;
import com.tradinglabs.vidingest.core.download.service.DownloadService;
import com.tradinglabs.vidingest.core.transcription.service.VideoTranscriptionQueryService;
import com.tradinglabs.vidingest.search.service.embedding.ContextChunkGenerationService;
import com.tradinglabs.vidingest.videos.service.VideoDeleteService;
import com.tradinglabs.vidingest.videos.service.VideoDetailQueryService;
import com.tradinglabs.vidingest.videos.service.VideoQueryService;
import com.tradinglabs.vidingest.videos.service.VideoSummaryMapper;
import com.tradinglabs.vidingest.videos.domain.Video;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.UUID;

@RestController
@RequestMapping(value = VidIngestApiPaths.VIDEOS, produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
@Slf4j
@Tag(name = "videos", description = "Video download, listing, and deletion APIs")
public class VideosController {

    private final DownloadService downloadService;
    private final VideoQueryService videoQueryService;
    private final VideoDeleteService videoDeleteService;
    private final VideoSummaryMapper videoSummaryMapper;
    private final VideoTranscriptionQueryService videoTranscriptionQueryService;
    private final ContextChunkGenerationService contextChunkGenerationService;
    private final VideoDetailQueryService videoDetailQueryService;

    @PostMapping("/download")
    @ResponseStatus(HttpStatus.OK)
    @Operation(summary = "Download a video", description = "Downloads a video using yt-dlp. Supports disk-only (no DB) and database-backed modes.")
    public DownloadVideoResponse download(@Valid @RequestBody DownloadVideoRequest request) throws IOException {
        validateHttpUrl(request.url());
        if (request.diskOnly()) {
            log.info("REST download: diskOnly=true, progress={}, url={}", request.progress(), request.url());
            String[] paths = downloadService.downloadToDisk(request.url(), request.progress());
            return new DownloadVideoResponse(null, new DownloadToDiskResult(paths[0], paths[1]));
        }

        log.info("REST download: diskOnly=false, progress={}, url={}", request.progress(), request.url());
        Video video = downloadService.downloadToDatabase(request.url(), request.progress());
        return new DownloadVideoResponse(videoSummaryMapper.toSummary(video), null);
    }

    @GetMapping
    @Operation(summary = "List videos")
    public PageResponse<VideoSummary> list(
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "source", required = false) String source,
            @RequestParam(name = "channelName", required = false) String channelName,
            @RequestParam(name = "page", required = false) Integer page,
            @RequestParam(name = "size", required = false) Integer size
    ) {
        var pageResult = videoQueryService.listPage(status, source, channelName, page, size);
        var items = pageResult.getContent().stream()
                .map(videoSummaryMapper::toSummary)
                .toList();
        return new PageResponse<>(items, pageResult.getNumber(), pageResult.getSize(), pageResult.getTotalElements());
    }

    @GetMapping("/{videoId}")
    @Operation(summary = "Get a video by id")
    public VideoSummary get(@PathVariable UUID videoId) {
        Video video = videoQueryService.getById(videoId);
        return videoSummaryMapper.toSummary(video);
    }

    @GetMapping("/{videoId}/detail")
    @Operation(summary = "Get video detail read model",
            description = "Aggregate read model for UI consumption: video summary, transcription details, and lightweight artifact counts.")
    public VideoDetail getVideoDetail(@PathVariable UUID videoId) {
        return videoDetailQueryService.getVideoDetail(videoId);
    }

    @GetMapping("/{videoId}/transcription")
    @Operation(summary = "Get transcription for a video", description = "Returns transcription metadata and a short snippet if present.")
    public VideoTranscriptionDetails getTranscription(@PathVariable UUID videoId) {
        // Ensure the video exists so callers get a consistent 404 for unknown ids.
        videoQueryService.getById(videoId);
        return videoTranscriptionQueryService.getTranscriptionDetails(videoId);
    }

    @GetMapping("/{videoId}/transcription/segments")
    @Operation(summary = "List transcription segments for a video")
    public PageResponse<TranscriptionSegmentSummary> listTranscriptionSegments(
            @PathVariable UUID videoId,
            @RequestParam(name = "page", required = false) Integer page,
            @RequestParam(name = "size", required = false) Integer size
    ) {
        // Ensure the video exists so callers get a consistent 404 for unknown ids.
        videoQueryService.getById(videoId);
        return videoTranscriptionQueryService.listSegments(videoId, page, size);
    }

    @DeleteMapping("/{videoId}")
    @Operation(summary = "Delete a video", description = "Deletes the video DB record and its file on disk (best-effort).")
    public DeleteVideoResult delete(@PathVariable UUID videoId) throws IOException {
        videoDeleteService.deleteVideo(videoId);
        return new DeleteVideoResult("deleted", videoId.toString());
    }

    @PostMapping("/{videoId}/context/regenerate")
    @ResponseStatus(HttpStatus.OK)
    @Operation(summary = "Regenerate context chunks for a video", description = "Uses the configured embeddings provider to generate context chunks for semantic search.")
    public RegenerateContextResult regenerateContext(@PathVariable UUID videoId) throws IOException {
        Video video = videoQueryService.getById(videoId);
        log.info("REST regenerate context: videoId={}", videoId);
        int chunks = contextChunkGenerationService.regenerateFor(video);
        return new RegenerateContextResult(videoId, chunks);
    }

    private void validateHttpUrl(String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("url must not be blank");
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            throw new IllegalArgumentException("url must start with http:// or https://");
        }
    }
}
