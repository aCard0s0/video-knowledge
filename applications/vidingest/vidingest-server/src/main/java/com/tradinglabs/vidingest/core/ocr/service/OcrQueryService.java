package com.tradinglabs.vidingest.core.ocr.service;

import com.tradinglabs.vidingest.api.common.PageResponse;
import com.tradinglabs.vidingest.api.ocr.OcrFrameGroup;
import com.tradinglabs.vidingest.core.ocr.mapper.OcrResultMapper;
import com.tradinglabs.vidingest.core.ocr.repo.OcrResultRepository;
import com.tradinglabs.vidingest.videos.service.VideoQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Read model over {@code vidingest_ocr_results} for the per-video OCR endpoints. Paging is by
 * frame, not by detection: a page of 25 is 25 frames' worth of {@link OcrFrameGroup}, however
 * many individual detections those carry.
 */
@Service
@RequiredArgsConstructor
public class OcrQueryService {

    private final OcrResultRepository ocrResultRepository;
    private final OcrResultMapper ocrResultMapper;
    private final VideoQueryService videoQueryService;

    @Transactional(readOnly = true)
    public List<OcrFrameGroup> resultsFor(UUID videoId) {
        videoQueryService.ensureExists(videoId);
        return ocrResultMapper.toFrameGroups(ocrResultRepository.findByVideoIdOrderByFrameTimestamp(videoId));
    }

    @Transactional(readOnly = true)
    public PageResponse<OcrFrameGroup> resultsByFramePage(UUID videoId, int page, int size) {
        videoQueryService.ensureExists(videoId);

        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, size);

        long totalFrames = ocrResultRepository.countFramesWithOcrByVideoId(videoId);
        if (totalFrames == 0) {
            return new PageResponse<>(List.of(), safePage, safeSize, 0);
        }

        var frameIds = ocrResultRepository.findOcrFrameIdsByVideoIdOrderByTimestamp(
                videoId, PageRequest.of(safePage, safeSize));
        if (frameIds.isEmpty()) {
            return new PageResponse<>(List.of(), safePage, safeSize, totalFrames);
        }

        var groups = ocrResultMapper.toFrameGroups(ocrResultRepository.findByFrameIdInOrderByFrameTimestamp(frameIds));
        return new PageResponse<>(groups, safePage, safeSize, totalFrames);
    }
}
