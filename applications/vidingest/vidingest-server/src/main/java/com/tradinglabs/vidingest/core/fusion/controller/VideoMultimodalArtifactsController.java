package com.tradinglabs.vidingest.core.fusion.controller;

import com.tradinglabs.vidingest.api.common.PageResponse;
import com.tradinglabs.vidingest.api.fusion.MultimodalSegmentDto;
import com.tradinglabs.vidingest.api.ocr.OcrFrameGroup;
import com.tradinglabs.vidingest.api.paths.VidIngestApiPaths;
import com.tradinglabs.vidingest.core.fusion.mapper.MultimodalSegmentMapper;
import com.tradinglabs.vidingest.core.fusion.repo.MultimodalSegmentRepository;
import com.tradinglabs.vidingest.core.ocr.mapper.OcrResultMapper;
import com.tradinglabs.vidingest.core.ocr.repo.OcrResultRepository;
import com.tradinglabs.vidingest.videos.exceptions.VideoNotFoundException;
import com.tradinglabs.vidingest.videos.repo.VideoRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.transaction.annotation.Transactional;
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
@Slf4j
@Tag(name = "video-artifacts", description = "Multi-modal artifact APIs (M5–M8)")
public class VideoMultimodalArtifactsController {

    private final MultimodalSegmentRepository multimodalSegmentRepository;
    private final MultimodalSegmentMapper multimodalSegmentMapper;
    private final OcrResultRepository ocrResultRepository;
    private final OcrResultMapper ocrResultMapper;
    private final VideoRepository videoRepository;

    @GetMapping(VidIngestApiPaths.VIDEO_MULTIMODAL_TIMELINE)
    @Operation(summary = "Get the fused multimodal timeline for a video",
            description = "Returns vidingest_multimodal_segments rows in segment_index order. Optional "
                    + "fromSeconds/toSeconds window the result to a temporal slice.")
    @Transactional(readOnly = true)
    public List<MultimodalSegmentDto> multimodalTimeline(
            @PathVariable UUID videoId,
            @RequestParam(name = "fromSeconds", required = false) Double fromSeconds,
            @RequestParam(name = "toSeconds", required = false) Double toSeconds
    ) {
        ensureVideoExists(videoId);
        var rows = multimodalSegmentRepository.findByVideo_IdOrderBySegmentIndexAsc(videoId);
        return rows.stream()
                .filter(seg -> fromSeconds == null
                        || (seg.getEndSeconds() != null && seg.getEndSeconds() > fromSeconds))
                .filter(seg -> toSeconds == null
                        || (seg.getStartSeconds() != null && seg.getStartSeconds() < toSeconds))
                .map(multimodalSegmentMapper::toDto)
                .toList();
    }

    @GetMapping(VidIngestApiPaths.VIDEO_MULTIMODAL_TIMELINE_PAGE)
    @Operation(summary = "Get the fused multimodal timeline for a video (paged)",
            description = "Returns a paged view of vidingest_multimodal_segments in segment_index order. Optional "
                    + "fromSeconds/toSeconds window the result to a temporal slice.")
    @Transactional(readOnly = true)
    public PageResponse<MultimodalSegmentDto> multimodalTimelinePage(
            @PathVariable UUID videoId,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "50") int size,
            @RequestParam(name = "fromSeconds", required = false) Double fromSeconds,
            @RequestParam(name = "toSeconds", required = false) Double toSeconds
    ) {
        ensureVideoExists(videoId);
        var pageable = PageRequest.of(Math.max(0, page), Math.max(1, size));
        var result = multimodalSegmentRepository.findPageByVideoIdWindowedOrderBySegmentIndex(videoId, fromSeconds, toSeconds, pageable);
        var items = result.getContent().stream().map(multimodalSegmentMapper::toDto).toList();
        return new PageResponse<>(items, result.getNumber(), result.getSize(), result.getTotalElements());
    }

    @GetMapping(VidIngestApiPaths.VIDEO_OCR)
    @Operation(summary = "Get OCR results for a video, grouped by frame",
            description = "Returns one OcrFrameGroup per frame that had at least one detection. Frames "
                    + "are ordered by timestamp ascending.")
    @Transactional(readOnly = true)
    public List<OcrFrameGroup> ocrResults(@PathVariable UUID videoId) {
        ensureVideoExists(videoId);
        var rows = ocrResultRepository.findByVideoIdOrderByFrameTimestamp(videoId);
        return ocrResultMapper.toFrameGroups(rows);
    }

    @GetMapping(VidIngestApiPaths.VIDEO_OCR_FRAMES)
    @Operation(summary = "Get OCR results for a video, grouped by frame (paged)",
            description = "Returns a paged list of OcrFrameGroup records ordered by frame timestamp ascending.")
    @Transactional(readOnly = true)
    public PageResponse<OcrFrameGroup> ocrResultsByFramePage(
            @PathVariable UUID videoId,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "25") int size
    ) {
        ensureVideoExists(videoId);

        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, size);

        long totalFrames = ocrResultRepository.countFramesWithOcrByVideoId(videoId);
        if (totalFrames == 0) {
            return new PageResponse<>(List.of(), safePage, safeSize, 0);
        }

        var pageable = PageRequest.of(safePage, safeSize);
        var frameIds = ocrResultRepository.findOcrFrameIdsByVideoIdOrderByTimestamp(videoId, pageable);
        if (frameIds.isEmpty()) {
            return new PageResponse<>(List.of(), safePage, safeSize, totalFrames);
        }

        var rows = ocrResultRepository.findByFrameIdInOrderByFrameTimestamp(frameIds);
        var groups = ocrResultMapper.toFrameGroups(rows);
        return new PageResponse<>(groups, safePage, safeSize, totalFrames);
    }

    private void ensureVideoExists(UUID videoId) {
        if (!videoRepository.existsById(videoId)) {
            throw new VideoNotFoundException(videoId);
        }
    }
}
