package com.tradinglabs.vidingest.core.transcription.service;

import com.tradinglabs.vidingest.api.common.PageResponse;
import com.tradinglabs.vidingest.core.transcription.domain.Transcription;
import com.tradinglabs.vidingest.core.transcription.domain.TranscriptionSegment;
import com.tradinglabs.vidingest.api.transcription.TranscriptionSegmentSummary;
import com.tradinglabs.vidingest.api.transcription.VideoTranscriptionDetails;
import com.tradinglabs.vidingest.core.transcription.repo.TranscriptionRepository;
import com.tradinglabs.vidingest.core.transcription.repo.TranscriptionSegmentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class VideoTranscriptionQueryService {

    private static final int DEFAULT_PAGE_SIZE = 50;
    private static final int MAX_PAGE_SIZE = 200;
    private static final int SNIPPET_CHARS = 400;

    private final TranscriptionRepository transcriptionRepository;
    private final TranscriptionSegmentRepository transcriptionSegmentRepository;

    @Transactional(readOnly = true)
    public VideoTranscriptionDetails getTranscriptionDetails(UUID videoId) {
        Transcription transcription = transcriptionRepository.findByVideoId(videoId).orElse(null);
        if (transcription == null) {
            return new VideoTranscriptionDetails(false, null, null, null, null, null, null, null, null);
        }

        String text = transcription.getFullText();
        Integer chars = text != null ? text.length() : null;
        String snippet = null;
        if (text != null && !text.isBlank()) {
            snippet = text.substring(0, Math.min(text.length(), SNIPPET_CHARS));
        }

        return new VideoTranscriptionDetails(
                true,
                transcription.getId() != null ? transcription.getId().toString() : null,
                transcription.getStatus() != null ? transcription.getStatus().name() : null,
                transcription.getProvider(),
                transcription.getLanguage(),
                transcription.getCreatedAt() != null ? transcription.getCreatedAt().toString() : null,
                transcription.getUpdatedAt() != null ? transcription.getUpdatedAt().toString() : null,
                chars,
                snippet
        );
    }

    @Transactional(readOnly = true)
    public PageResponse<TranscriptionSegmentSummary> listSegments(UUID videoId, Integer page, Integer size) {
        Transcription transcription = transcriptionRepository.findByVideoId(videoId).orElse(null);
        int pageValue = page != null ? Math.max(0, page) : 0;
        int sizeValue = size != null ? Math.max(1, Math.min(size, MAX_PAGE_SIZE)) : DEFAULT_PAGE_SIZE;

        if (transcription == null || transcription.getId() == null) {
            return new PageResponse<>(java.util.List.of(), pageValue, sizeValue, 0);
        }

        var pageable = PageRequest.of(pageValue, sizeValue, Sort.by(Sort.Direction.ASC, "startSeconds"));
        var pageResult = transcriptionSegmentRepository.findByTranscriptionId(transcription.getId(), pageable);
        var items = pageResult.getContent().stream()
                .map(VideoTranscriptionQueryService::toSummary)
                .toList();

        return new PageResponse<>(items, pageResult.getNumber(), pageResult.getSize(), pageResult.getTotalElements());
    }

    private static TranscriptionSegmentSummary toSummary(TranscriptionSegment segment) {
        return new TranscriptionSegmentSummary(
                segment.getId() != null ? segment.getId().toString() : null,
                segment.getStartSeconds(),
                segment.getEndSeconds(),
                segment.getText(),
                segment.getCreatedAt() != null ? segment.getCreatedAt().toString() : null
        );
    }
}

