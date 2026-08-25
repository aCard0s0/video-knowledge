package com.tradinglabs.vidingest.videos.service;

import com.tradinglabs.vidingest.videos.domain.Video;
import com.tradinglabs.vidingest.videos.domain.VideoStatus;
import com.tradinglabs.vidingest.videos.exceptions.VideoNotFoundException;
import com.tradinglabs.vidingest.videos.repo.VideoRepository;
import com.tradinglabs.vidingest.videos.repo.VideoSpecifications;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class VideoQueryService {

    private static final Sort DEFAULT_SORT = Sort.by(Sort.Direction.DESC, "createdAt");
    private static final int DEFAULT_PAGE_SIZE = 50;
    private static final int MAX_PAGE_SIZE = 200;

    private final VideoRepository videoRepository;

    @Transactional(readOnly = true)
    public List<Video> listAll() {
        return videoRepository.findAll(DEFAULT_SORT);
    }

    @Transactional(readOnly = true)
    public List<Video> list(String status, String source, String channelName, Integer page, Integer size) {
        Specification<Video> spec = VideoSpecifications.filter(parseStatus(status), source, channelName);
        boolean paged = page != null || size != null;
        if (paged) {
            return videoRepository.findAll(spec, pageable(page, size)).getContent();
        }
        return videoRepository.findAll(spec, DEFAULT_SORT);
    }

    @Transactional(readOnly = true)
    public Page<Video> listPage(String status, String source, String channelName, Integer page, Integer size) {
        Specification<Video> spec = VideoSpecifications.filter(parseStatus(status), source, channelName);
        return videoRepository.findAll(spec, pageable(page, size));
    }

    @Transactional(readOnly = true)
    public Video getById(UUID videoId) {
        return videoRepository.findById(videoId)
                .orElseThrow(() -> new VideoNotFoundException(videoId));
    }

    private static Pageable pageable(Integer page, Integer size) {
        int pageValue = page != null ? Math.max(0, page) : 0;
        int sizeValue = size != null ? Math.clamp(size, 1, MAX_PAGE_SIZE) : DEFAULT_PAGE_SIZE;
        return PageRequest.of(pageValue, sizeValue, DEFAULT_SORT);
    }

    private static VideoStatus parseStatus(String status) {
        if (status == null || status.isBlank() || "ALL".equalsIgnoreCase(status)) {
            return null;
        }
        return VideoStatus.valueOf(status.toUpperCase(Locale.ROOT));
    }
}
