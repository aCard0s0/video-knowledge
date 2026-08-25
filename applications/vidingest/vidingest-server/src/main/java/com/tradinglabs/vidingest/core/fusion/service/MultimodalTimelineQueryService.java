package com.tradinglabs.vidingest.core.fusion.service;

import com.tradinglabs.vidingest.api.common.PageResponse;
import com.tradinglabs.vidingest.api.fusion.MultimodalSegmentDto;
import com.tradinglabs.vidingest.core.fusion.mapper.MultimodalSegmentMapper;
import com.tradinglabs.vidingest.core.fusion.repo.MultimodalSegmentRepository;
import com.tradinglabs.vidingest.videos.service.VideoQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Read model over {@code vidingest_multimodal_segments} for the timeline endpoints.
 *
 * <p>The {@code fromSeconds}/{@code toSeconds} window has exactly one definition — the JPQL
 * predicate in {@link MultimodalSegmentRepository#findPageByVideoIdWindowedOrderBySegmentIndex}.
 * The unpaged variant runs the same query with {@link Pageable#unpaged()} rather than
 * re-implementing the comparison as a stream filter, which is how the two endpoints used to
 * disagree-in-waiting: identical rules written twice, one of them in a controller.
 */
@Service
@RequiredArgsConstructor
public class MultimodalTimelineQueryService {

    private final MultimodalSegmentRepository multimodalSegmentRepository;
    private final MultimodalSegmentMapper multimodalSegmentMapper;
    private final VideoQueryService videoQueryService;

    @Transactional(readOnly = true)
    public List<MultimodalSegmentDto> timeline(UUID videoId, Double fromSeconds, Double toSeconds) {
        videoQueryService.ensureExists(videoId);
        return multimodalSegmentRepository
                .findPageByVideoIdWindowedOrderBySegmentIndex(videoId, fromSeconds, toSeconds, Pageable.unpaged())
                .getContent().stream()
                .map(multimodalSegmentMapper::toDto)
                .toList();
    }

    @Transactional(readOnly = true)
    public PageResponse<MultimodalSegmentDto> timelinePage(
            UUID videoId, Double fromSeconds, Double toSeconds, int page, int size) {
        videoQueryService.ensureExists(videoId);
        var pageable = PageRequest.of(Math.max(0, page), Math.max(1, size));
        var result = multimodalSegmentRepository
                .findPageByVideoIdWindowedOrderBySegmentIndex(videoId, fromSeconds, toSeconds, pageable);
        var items = result.getContent().stream().map(multimodalSegmentMapper::toDto).toList();
        return new PageResponse<>(items, result.getNumber(), result.getSize(), result.getTotalElements());
    }
}
