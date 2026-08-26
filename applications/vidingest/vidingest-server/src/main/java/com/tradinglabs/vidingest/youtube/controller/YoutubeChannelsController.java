package com.tradinglabs.vidingest.youtube.controller;

import com.tradinglabs.vidingest.api.common.PageResponse;
import com.tradinglabs.vidingest.api.paths.VidIngestApiPaths;
import com.tradinglabs.vidingest.api.pipeline.CreatePipelineRunResponse;
import com.tradinglabs.vidingest.api.youtube.CreatePipelineRunFromYoutubeVideosRequest;
import com.tradinglabs.vidingest.api.youtube.CreateYoutubeChannelRequest;
import com.tradinglabs.vidingest.api.youtube.YoutubeChannelSummary;
import com.tradinglabs.vidingest.api.youtube.YoutubeChannelVideoSummary;
import com.tradinglabs.vidingest.youtube.service.YoutubeChannelCommandService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
@RequestMapping(value = VidIngestApiPaths.YOUTUBE_CHANNELS, produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
@Slf4j
@Tag(name = "youtube", description = "YouTube channel discovery and ingestion APIs")
public class YoutubeChannelsController {

    private final YoutubeChannelCommandService youtubeChannels;

    @PostMapping
    @Operation(operationId = "createChannel", summary = "Add a YouTube channel", description = "Stores the channel URL and prepares it for periodic sync.")
    public ResponseEntity<YoutubeChannelSummary> create(@Valid @RequestBody CreateYoutubeChannelRequest request) {
        YoutubeChannelSummary summary = youtubeChannels.createChannel(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(summary);
    }

    @GetMapping
    @Operation(operationId = "listChannels", summary = "List YouTube channels")
    public PageResponse<YoutubeChannelSummary> list(
            @RequestParam(name = "page", required = false) Integer page,
            @RequestParam(name = "size", required = false) Integer size
    ) {
        return youtubeChannels.listChannels(page, size);
    }

    @GetMapping("/{channelId}")
    @Operation(operationId = "getChannel", summary = "Get a YouTube channel by id")
    public YoutubeChannelSummary get(@PathVariable UUID channelId) {
        return youtubeChannels.getChannel(channelId);
    }

    @PostMapping("/{channelId}/sync")
    @ResponseStatus(HttpStatus.OK)
    @Operation(operationId = "syncChannel", summary = "Sync a YouTube channel now", description = "Refreshes the stored list of available videos for the channel using yt-dlp.")
    public YoutubeChannelSummary sync(@PathVariable UUID channelId) throws IOException {
        log.info("REST sync YouTube channel: channelId={}", channelId);
        return youtubeChannels.syncChannel(channelId);
    }

    @GetMapping("/{channelId}/videos")
    @Operation(operationId = "listChannelVideos", summary = "List available videos for a channel")
    public PageResponse<YoutubeChannelVideoSummary> listVideos(
            @PathVariable UUID channelId,
            @RequestParam(name = "page", required = false) Integer page,
            @RequestParam(name = "size", required = false) Integer size
    ) {
        return youtubeChannels.listChannelVideos(channelId, page, size);
    }

    @PostMapping("/{channelId}/pipelines")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(operationId = "createRunsFromChannel", summary = "Create a pipeline run from selected channel videos",
            description = "Expands selected youtubeVideoIds into watch URLs and submits a batch pipeline run.")
    public CreatePipelineRunResponse createPipelineRun(
            @PathVariable UUID channelId,
            @Valid @RequestBody CreatePipelineRunFromYoutubeVideosRequest request
    ) {
        log.info("REST create pipeline run from YouTube videos: channelId={} count={}", channelId, request.youtubeVideoIds().size());
        return youtubeChannels.createPipelineRun(channelId, request);
    }
}

