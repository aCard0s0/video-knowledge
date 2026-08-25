package com.tradinglabs.vidingest.core.download.service;

import com.tradinglabs.vidingest.core.download.util.MetadataExtractor;
import com.tradinglabs.vidingest.videos.domain.VideoStatus;
import com.tradinglabs.vidingest.videos.domain.Video;
import com.tradinglabs.vidingest.videos.repo.VideoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;

@Service
@RequiredArgsConstructor
@Slf4j
public class DownloadService {

    private final VideoDownloadService downloadService;
    private final MetadataService metadataService;
    private final VideoRepository videoRepository;

    /**
     * In-flight single-flight map keyed by (source, sourceVideoId). This prevents concurrent downloads
     * for the same video from colliding on temp/part files and deleting each other's artifacts.
     */
    private final ConcurrentHashMap<String, CompletableFuture<Video>> inFlightBySourceVideo = new ConcurrentHashMap<>();

    @Transactional
    public Video downloadToDatabase(String videoUrl) throws IOException {
        return downloadToDatabase(videoUrl, false);
    }

    @Transactional
    public Video downloadToDatabase(String videoUrl, boolean showProgress) throws IOException {
        log.info("Download-to-database for URL: {}", videoUrl);

        Map<String, Object> metadata = downloadService.extractMetadata(videoUrl);
        String source = MetadataExtractor.extractSource(metadata);
        String sourceVideoId = MetadataExtractor.extractSourceVideoId(metadata);
        String key = source + "/" + sourceVideoId;

        // Fast path: already ingested and the file still exists.
        Video existing = videoRepository.findBySourceAndSourceVideoId(source, sourceVideoId).orElse(null);
        if (existing != null && isDownloaded(existing) && fileExists(existing.getFilePath())) {
            log.info("Download-to-database skipped (already ingested): {}", key);
            return existing;
        }

        CompletableFuture<Video> promise = new CompletableFuture<>();
        CompletableFuture<Video> prior = inFlightBySourceVideo.putIfAbsent(key, promise);
        if (prior != null) {
            log.info("Coalescing concurrent download request; waiting for in-flight result: {}", key);
            return awaitInFlight(key, prior);
        }

        try {
            String filePath = downloadService.downloadVideoToDisk(videoUrl, metadata, showProgress);

            Video video = metadataService.processMetadata(metadata, filePath);
            try {
                downloadService.saveMetadataToDisk(metadata, filePath);
            } catch (IOException e) {
                log.warn("Video persisted but metadata companion file could not be saved: {}", e.getMessage());
            }

            promise.complete(video);
            return video;
        } catch (Throwable t) {
            promise.completeExceptionally(t);
            if (t instanceof IOException io) throw io;
            if (t instanceof RuntimeException re) throw re;
            throw new IOException("Video download failed: " + t.getMessage(), t);
        } finally {
            inFlightBySourceVideo.remove(key, promise);
        }
    }

    public String[] downloadToDisk(String videoUrl) throws IOException {
        return downloadToDisk(videoUrl, false);
    }

    public String[] downloadToDisk(String videoUrl, boolean showProgress) throws IOException {
        log.info("Download-to-disk for URL: {}", videoUrl);

        Map<String, Object> metadata = downloadService.extractMetadata(videoUrl);
        String filePath = downloadService.downloadVideoToDisk(videoUrl, metadata, showProgress);

        String metadataPath = null;
        try {
            metadataPath = downloadService.saveMetadataToDisk(metadata, filePath);
        } catch (IOException e) {
            log.warn("Video downloaded but metadata file could not be saved: {}", e.getMessage());
        }

        return new String[]{filePath, metadataPath};
    }

    private Video awaitInFlight(String key, CompletableFuture<Video> future) throws IOException {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while awaiting in-flight download for " + key, e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException io) throw io;
            if (cause instanceof RuntimeException re) throw re;
            throw new IOException("In-flight download failed for " + key + ": " + cause.getMessage(), cause);
        }
    }

    private static boolean isDownloaded(Video video) {
        VideoStatus status = video.getStatus();
        if (status == null) return false;
        return status == VideoStatus.DOWNLOADED
                || status == VideoStatus.TRANSCRIBING
                || status == VideoStatus.PROCESSING
                || status == VideoStatus.COMPLETED;
    }

    private static boolean fileExists(String filePath) {
        if (filePath == null || filePath.isBlank()) return false;
        return Files.exists(Path.of(filePath));
    }
}

