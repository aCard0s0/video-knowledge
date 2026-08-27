package com.tradinglabs.vidingest.core.download.service;

import com.tradinglabs.vidingest.core.download.util.MetadataExtractor;
import com.tradinglabs.vidingest.videos.exceptions.DuplicateVideoException;
import com.tradinglabs.vidingest.videos.domain.Video;
import com.tradinglabs.vidingest.videos.domain.VideoStatus;
import com.tradinglabs.vidingest.videos.repo.VideoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;

/**
 * Service for extracting and storing video metadata
 * Refactored to use MetadataExtractor utility for centralized metadata extraction
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MetadataService {

    private final VideoRepository videoRepository;

    /**
     * Parse metadata from yt-dlp JSON output and create/update Video entity
     *
     * @param metadata Raw metadata map from yt-dlp
     * @param filePath Path to the downloaded video file
     * @return Created or updated Video entity
     */
    @Transactional
    public Video processMetadata(Map<String, Object> metadata, String filePath) {
        String source = MetadataExtractor.extractSource(metadata);
        String sourceVideoId = MetadataExtractor.extractSourceVideoId(metadata);

        log.info("Processing metadata for {}/{}", source, sourceVideoId);

        Video video = videoRepository.findBySourceAndSourceVideoId(source, sourceVideoId)
                .orElse(Video.builder()
                        .source(source)
                        .sourceVideoId(sourceVideoId)
                        .status(VideoStatus.PENDING)
                        .build());

        // Extract and set metadata fields using centralized extractor
        video.setTitle(MetadataExtractor.extractTitle(metadata));
        video.setDescription(MetadataExtractor.extractString(metadata, "description"));
        video.setChannelName(MetadataExtractor.extractChannelName(metadata));
        video.setDurationSeconds(MetadataExtractor.extractInteger(metadata, "duration"));
        video.setPublishedAt(MetadataExtractor.extractPublishDate(metadata));
        video.setFilePath(filePath);
        video.setMetadata(metadata);
        video.setStatus(VideoStatus.DOWNLOADED);
        video.setDownloadedAt(OffsetDateTime.now(ZoneOffset.UTC));

        Video savedVideo = videoRepository.save(video);
        log.info("Metadata processed for video: {}", savedVideo.getId());

        return savedVideo;
    }

    /**
     * Create a new video from metadata. If a video already exists for the same (source, sourceVideoId),
     * this throws {@link DuplicateVideoException}.
     */
    @Transactional
    public Video createVideoFromMetadata(Map<String, Object> metadata, String filePath) {
        String source = MetadataExtractor.extractSource(metadata);
        String sourceVideoId = MetadataExtractor.extractSourceVideoId(metadata);

        log.info("Creating video from metadata for {}/{}", source, sourceVideoId);

        Video video = Video.builder()
                .source(source)
                .sourceVideoId(sourceVideoId)
                .status(VideoStatus.PENDING)
                .build();

        video.setTitle(MetadataExtractor.extractTitle(metadata));
        video.setDescription(MetadataExtractor.extractString(metadata, "description"));
        video.setChannelName(MetadataExtractor.extractChannelName(metadata));
        video.setDurationSeconds(MetadataExtractor.extractInteger(metadata, "duration"));
        video.setPublishedAt(MetadataExtractor.extractPublishDate(metadata));
        video.setFilePath(filePath);
        video.setMetadata(metadata);
        video.setStatus(VideoStatus.DOWNLOADED);
        video.setDownloadedAt(OffsetDateTime.now(ZoneOffset.UTC));

        try {
            return videoRepository.saveAndFlush(video);
        } catch (DataIntegrityViolationException e) {
            throw new DuplicateVideoException(source, sourceVideoId, e);
        }
    }
}
