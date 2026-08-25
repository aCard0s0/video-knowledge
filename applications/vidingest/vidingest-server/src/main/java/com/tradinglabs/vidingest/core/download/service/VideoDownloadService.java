package com.tradinglabs.vidingest.core.download.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradinglabs.vidingest.config.ProjectPathResolver;
import com.tradinglabs.vidingest.config.VideoDownloadConfig;
import com.tradinglabs.vidingest.config.VideoStorageConfig;
import com.tradinglabs.vidingest.core.download.util.FileSystemHelper;
import com.tradinglabs.vidingest.core.download.util.MetadataExtractor;
import com.tradinglabs.vidingest.core.download.util.YtDlpCommandBuilder;
import com.tradinglabs.vidingest.core.download.util.YtDlpExecutor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.exec.CommandLine;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

@Service
@Slf4j
@RequiredArgsConstructor
public class VideoDownloadService {

    private final ProjectPathResolver pathResolver;
    private final VideoStorageConfig storageConfig;
    private final VideoDownloadConfig downloadConfig;
    private final YtDlpCommandBuilder commandBuilder;
    private final ObjectMapper objectMapper;

    private String videoPath;

    @PostConstruct
    public void init() {
        // Use configured paths if provided, otherwise use project path resolver
        this.videoPath = storageConfig.getVideoPath() != null && !storageConfig.getVideoPath().isEmpty()
                ? storageConfig.getVideoPath()
                : pathResolver.getVideoPath();

        log.info("Video storage path: {}", videoPath);
    }

    /**
     * Download a video from the given URL
     *
     * @param videoUrl URL of the video
     * @param outputFilename Desired output filename (without extension)
     * @return Path to the downloaded video file
     * @throws IOException if download fails
     */
    public String downloadVideo(String videoUrl, String outputFilename) throws IOException {
        return downloadVideo(videoUrl, outputFilename, false);
    }

    /**
     * Download a video with optional real-time progress output.
     *
     * @param videoUrl URL of the video
     * @param outputFilename Desired output filename (without extension)
     * @param showProgress whether to stream yt-dlp progress to the terminal
     * @return Path to the downloaded video file
     * @throws IOException if download fails
     */
    public String downloadVideo(String videoUrl, String outputFilename, boolean showProgress) throws IOException {
        FileSystemHelper.ensureDirectoriesExist(videoPath);
        Path tempDir = createTempDownloadDir(outputFilename);
        Path outputPath = tempDir.resolve(outputFilename + ".%(ext)s");
        log.info("Downloading video from {} to {}", videoUrl, outputPath);

        CommandLine cmdLine = commandBuilder.buildDownloadCommand(videoUrl, outputPath.toString(), true);
        try {
            YtDlpExecutor.executeDownload(cmdLine, tempDir.toString(), downloadConfig.getTimeoutSeconds(), showProgress);

            log.debug("Download completed successfully, searching for file: {}", outputFilename);
            Path actual = Path.of(FileSystemHelper.findFileByPrefix(tempDir, outputFilename));
            Path dest = Paths.get(videoPath).resolve(actual.getFileName());

            if (Files.exists(dest)) {
                log.info("Destination already exists; reusing existing file: {}", dest);
                return dest.toString();
            }

            atomicMove(actual, dest);
            log.info("Video downloaded successfully: {}", dest);
            return dest.toString();
        } catch (IOException e) {
            // Cleanup only within temp directory to avoid cross-request deletion.
            FileSystemHelper.bestEffortDeleteFilesByPrefix(tempDir, outputFilename);
            throw e;
        } finally {
            bestEffortDeleteDirectory(tempDir);
        }
    }

    /**
     * Extract metadata from a video URL without downloading
     *
     * @param videoUrl URL of the video
     * @return Metadata as JSON map
     * @throws IOException if extraction fails
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> extractMetadata(String videoUrl) throws IOException {
        log.info("Extracting metadata from: {}", videoUrl);

        CommandLine cmdLine = commandBuilder.buildMetadataCommand(videoUrl);
        String jsonOutput = YtDlpExecutor.executeForOutput(cmdLine, downloadConfig.getTimeoutSeconds());

        try {
            String cleanJson = cleanJsonOutput(jsonOutput);
            
            if (cleanJson.isEmpty()) {
                throw new IOException("No JSON output received from yt-dlp");
            }
            
            log.debug("Parsing JSON metadata ({} chars)", cleanJson.length());
            return objectMapper.readValue(cleanJson, Map.class);

        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            log.error("Failed to parse JSON metadata: {}", e.getMessage());
            log.debug("Raw output: {}", jsonOutput);
            throw new IOException("Metadata extraction failed: Invalid JSON response. " + e.getMessage(), e);
        }
    }

    /**
     * Download video to disk with channel + per-video folder structure.
     * Video filename: YYYYMMDD.title.ext
     * Saved in folder: {videoPath}/{channelName}/{YYYYMMDD.title}/
     * Companion files (metadata json, whisper json/txt) and the {@code frames/}
     * subdirectory live alongside the video inside the same per-video folder.
     *
     * @param videoUrl URL of the video
     * @param metadata Video metadata map
     * @return Path to the downloaded video file
     * @throws IOException if download fails
     */
    public String downloadVideoToDisk(String videoUrl, Map<String, Object> metadata) throws IOException {
        return downloadVideoToDisk(videoUrl, metadata, false);
    }

    /**
     * Download video to disk with optional real-time progress output.
     *
     * @param videoUrl URL of the video
     * @param metadata Video metadata map
     * @param showProgress whether to stream yt-dlp progress to the terminal
     * @return Path to the downloaded video file
     * @throws IOException if download fails
     */
    public String downloadVideoToDisk(String videoUrl, Map<String, Object> metadata, boolean showProgress) throws IOException {
        FileSystemHelper.ensureDirectoriesExist(videoPath);

        // Extract and sanitize metadata for folder structure
        String channelName = MetadataExtractor.extractChannelName(metadata);
        String sanitizedChannelName = FileSystemHelper.sanitizeFilename(
                channelName != null ? channelName : "Unknown");

        // Build desired filename + per-video folder name: YYYYMMDD.title
        String dateStr = MetadataExtractor.extractDateString(metadata);
        String title = MetadataExtractor.extractTitle(metadata);
        String sanitizedTitle = FileSystemHelper.sanitizeFilename(title != null ? title : "video");
        String desiredFilename = dateStr + "." + sanitizedTitle;

        // Create channel/{date.title}/ folder — all artifacts (mp4, metadata.json,
        // whisper.*, frames/) for this video live inside it.
        Path videoDir = FileSystemHelper.createAndVerifyDirectory(
                Paths.get(videoPath, sanitizedChannelName, desiredFilename).toString());

        log.info("Downloading video to disk-only location: {}", videoDir);
        log.debug("Channel: {}, Date: {}, Title: {}, Desired filename: {}",
                channelName, dateStr, title, desiredFilename);

        // Download into an isolated temp dir, then move into place.
        String sourceVideoId = MetadataExtractor.extractSourceVideoId(metadata);
        String tempKey = FileSystemHelper.sanitizeFilename(sourceVideoId != null ? sourceVideoId : "video");
        Path tempDir = createTempDownloadDir(tempKey);

        try {
            CommandLine cmdLine = commandBuilder.buildDownloadCommand(videoUrl, "%(title)s.%(ext)s", false);
            YtDlpExecutor.executeDownload(cmdLine, tempDir.toString(), downloadConfig.getTimeoutSeconds(), showProgress);

            // Find and rename downloaded file
            Path downloadedFile = FileSystemHelper.findLatestVideoFile(tempDir);
            String extension = FileSystemHelper.getFileExtension(downloadedFile.getFileName().toString());

            Path renamedFile = resolveNonCollidingPath(videoDir, desiredFilename, extension);
            atomicMove(downloadedFile, renamedFile);

            log.info("Video downloaded and renamed: {} -> {}",
                    downloadedFile.getFileName(), renamedFile.getFileName());
            return renamedFile.toString();
        } finally {
            bestEffortDeleteDirectory(tempDir);
        }
    }

    private static Path resolveNonCollidingPath(Path directory, String baseName, String extension) throws IOException {
        Path candidate = directory.resolve(baseName + extension);
        if (!Files.exists(candidate)) {
            return candidate;
        }

        for (int i = 2; i <= 999; i++) {
            Path next = directory.resolve(baseName + " (" + i + ")" + extension);
            if (!Files.exists(next)) {
                return next;
            }
        }

        throw new IOException("Unable to choose non-colliding filename for " + candidate);
    }

    private Path createTempDownloadDir(String prefix) throws IOException {
        Path base = Paths.get(videoPath, ".tmp", "downloads", prefix);
        Path dir = base.resolve(UUID.randomUUID().toString());
        Files.createDirectories(dir);
        return dir;
    }

    private static void atomicMove(Path source, Path dest) throws IOException {
        Files.createDirectories(dest.getParent());
        try {
            Files.move(source, dest, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, dest);
        }
    }

    private static void bestEffortDeleteDirectory(Path dir) {
        if (dir == null) return;
        if (!Files.exists(dir)) return;
        try (Stream<Path> stream = Files.walk(dir)) {
            stream
                    .sorted((a, b) -> b.getNameCount() - a.getNameCount())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignored) {
                            // best-effort
                        }
                    });
        } catch (IOException ignored) {
            // best-effort
        }
    }

    /**
     * Save metadata to disk as JSON file.
     * Filename: YYYYMMDD.title.metadata.json (saved in the same folder as the video).
     *
     * @param metadata Video metadata map
     * @param videoFilePath Path to the video file
     * @return Path to the metadata JSON file
     * @throws IOException if writing the metadata file fails
     */
    public String saveMetadataToDisk(Map<String, Object> metadata, String videoFilePath) throws IOException {
        Path videoPathObj = Paths.get(videoFilePath);
        Path videoDir = videoPathObj.getParent();
        String videoFileName = videoPathObj.getFileName().toString();

        int lastDot = videoFileName.lastIndexOf('.');
        String baseName = lastDot > 0 ? videoFileName.substring(0, lastDot) : videoFileName;

        String metadataFileName = baseName + ".metadata.json";
        Path metadataPath = videoDir.resolve(metadataFileName);

        String jsonContent = objectMapper.writerWithDefaultPrettyPrinter()
                .writeValueAsString(metadata);

        Files.write(metadataPath, jsonContent.getBytes(StandardCharsets.UTF_8));
        log.info("Metadata saved to: {}", metadataPath);

        return metadataPath.toString();
    }

    /**
     * Clean JSON output by removing non-JSON lines (warnings, progress messages)
     * Finds the first line that starts with '{' or '[' and collects from there
     */
    private String cleanJsonOutput(String jsonOutput) {
        String[] lines = jsonOutput.split("\n");
        StringBuilder jsonBuilder = new StringBuilder();
        boolean jsonStarted = false;
        
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            // Start collecting when we find JSON start
            if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
                jsonStarted = true;
            }
            if (jsonStarted) {
                if (jsonBuilder.length() > 0) {
                    jsonBuilder.append("\n");
                }
                jsonBuilder.append(line);
            }
        }
        
        return jsonBuilder.toString();
    }
}
