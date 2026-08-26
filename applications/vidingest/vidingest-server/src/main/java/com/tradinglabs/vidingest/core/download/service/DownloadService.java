package com.tradinglabs.vidingest.core.download.service;

import com.tradinglabs.vidingest.config.VideoDownloadConfig;
import com.tradinglabs.vidingest.core.download.util.MetadataExtractor;
import com.tradinglabs.vidingest.videos.domain.VideoStatus;
import com.tradinglabs.vidingest.videos.domain.Video;
import com.tradinglabs.vidingest.videos.repo.VideoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Service
@RequiredArgsConstructor
@Slf4j
public class DownloadService {

    /**
     * Ceiling on how long a coalesced caller waits for the leader, when
     * {@code vidingest.download.timeout-seconds} is 0 — which is the class default, and means the
     * yt-dlp watchdog is off. Something has to bound the wait even then.
     */
    private static final long UNBOUNDED_DOWNLOAD_AWAIT_SECONDS = 3600;

    private final VideoDownloadService downloadService;
    private final MetadataService metadataService;
    private final VideoRepository videoRepository;
    private final VideoDownloadConfig downloadConfig;

    /**
     * In-flight single-flight map keyed by (source, sourceVideoId). This prevents concurrent downloads
     * for the same video from colliding on temp/part files and deleting each other's artifacts.
     */
    private final ConcurrentHashMap<String, CompletableFuture<Video>> inFlightBySourceVideo = new ConcurrentHashMap<>();

    public Video downloadToDatabase(String videoUrl) throws IOException {
        return downloadToDatabase(videoUrl, false);
    }

    /**
     * Deliberately <b>not</b> {@code @Transactional}. The repository read below binds a pooled
     * connection for the rest of the method, and the rest of the method is a yt-dlp download
     * bounded only by {@code vidingest.download.timeout-seconds} — 1800 by default. Ten concurrent
     * calls to this endpoint held all ten Hikari connections for half an hour, and every other DB
     * user in the JVM — phase writes, audit inserts, the readiness probe — failed waiting for one.
     *
     * <p>The only write is {@code MetadataService.processMetadata}, which carries its own
     * transaction. {@code SubprocessTransactionBoundaryIntegrationTest} fails if this comes back.
     */
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

    /**
     * Bounded by the same ceiling the download itself runs under, plus a margin. An unbounded
     * {@code get()} here meant a coalesced caller waited on the leader forever if the leader's own
     * bound failed to fire — and back when this method ran inside a transaction, it did so holding
     * a pooled connection it was not using.
     */
    private Video awaitInFlight(String key, CompletableFuture<Video> future) throws IOException {
        try {
            return future.get(awaitTimeoutSeconds(), TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            throw new IOException("Timed out awaiting in-flight download for " + key, e);
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

    /** The download's own ceiling plus a margin, or a fixed bound when the watchdog is off. */
    private long awaitTimeoutSeconds() {
        long configured = downloadConfig.getTimeoutSeconds();
        return configured > 0 ? configured + 60 : UNBOUNDED_DOWNLOAD_AWAIT_SECONDS;
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

