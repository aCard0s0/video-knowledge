package com.tradinglabs.vidingest.core.fusion.controller;

import com.tradinglabs.vidingest.api.common.PageResponse;
import com.tradinglabs.vidingest.api.fusion.MultimodalSegmentDto;
import com.tradinglabs.vidingest.api.ocr.OcrFrameGroup;
import com.tradinglabs.vidingest.api.paths.VidIngestApiPaths;
import com.tradinglabs.vidingest.core.fusion.service.MultimodalTimelineQueryService;
import com.tradinglabs.vidingest.core.ocr.service.OcrQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Per-video artifact endpoints not covered by the existing {@code VideosController}:
 *
 * <ul>
 *   <li>{@code GET /api/v1/videos/{id}/multimodal-timeline} — paged-by-window view of the
 *       fused transcript + OCR + speaker signals (M5 output).</li>
 *   <li>{@code GET /api/v1/videos/{id}/ocr} — OCR detections grouped by frame.</li>
 * </ul>
 *
 * Kept separate from {@code VideosController} so the videos package doesn't grow a
 * dependency on every new core module.
 */
@RestController
@RequestMapping(produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
@Tag(name = "video-artifacts", description = "Multi-modal artifact APIs (M5–M8)")
public class VideoMultimodalArtifactsController {

    private final MultimodalTimelineQueryService multimodalTimelineQueryService;
    private final OcrQueryService ocrQueryService;

    @GetMapping(VidIngestApiPaths.VIDEO_MULTIMODAL_TIMELINE)
    @Operation(summary = "Get the fused multimodal timeline for a video",
            description = "Returns vidingest_multimodal_segments rows in segment_index order. Optional "
                    + "fromSeconds/toSeconds window the result to a temporal slice.")
    public List<MultimodalSegmentDto> multimodalTimeline(
            @PathVariable UUID videoId,
            @RequestParam(name = "fromSeconds", required = false) Double fromSeconds,
            @RequestParam(name = "toSeconds", required = false) Double toSeconds
    ) {
        return multimodalTimelineQueryService.timeline(videoId, fromSeconds, toSeconds);
    }

    @GetMapping(VidIngestApiPaths.VIDEO_MULTIMODAL_TIMELINE_PAGE)
    @Operation(summary = "Get the fused multimodal timeline for a video (paged)",
            description = "Returns a paged view of vidingest_multimodal_segments in segment_index order. Optional "
                    + "fromSeconds/toSeconds window the result to a temporal slice.")
    public PageResponse<MultimodalSegmentDto> multimodalTimelinePage(
            @PathVariable UUID videoId,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "50") int size,
            @RequestParam(name = "fromSeconds", required = false) Double fromSeconds,
            @RequestParam(name = "toSeconds", required = false) Double toSeconds
    ) {
        return multimodalTimelineQueryService.timelinePage(videoId, fromSeconds, toSeconds, page, size);
    }

    @GetMapping(VidIngestApiPaths.VIDEO_OCR)
    @Operation(summary = "Get OCR results for a video, grouped by frame",
            description = "Returns one OcrFrameGroup per frame that had at least one detection. Frames "
                    + "are ordered by timestamp ascending.")
    public List<OcrFrameGroup> ocrResults(@PathVariable UUID videoId) {
        return ocrQueryService.resultsFor(videoId);
    }

    @GetMapping(VidIngestApiPaths.VIDEO_OCR_FRAMES)
    @Operation(summary = "Get OCR results for a video, grouped by frame (paged)",
            description = "Returns a paged list of OcrFrameGroup records ordered by frame timestamp ascending.")
    public PageResponse<OcrFrameGroup> ocrResultsByFramePage(
            @PathVariable UUID videoId,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "25") int size
    ) {
        return ocrQueryService.resultsByFramePage(videoId, page, size);
    }
}
