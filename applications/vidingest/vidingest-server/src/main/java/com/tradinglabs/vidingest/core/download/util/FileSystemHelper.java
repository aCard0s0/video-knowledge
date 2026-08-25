package com.tradinglabs.vidingest.core.download.util;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Utility class for file system operations
 * Handles file sanitization, directory creation, and file discovery
 */
@Slf4j
public final class FileSystemHelper {

    private FileSystemHelper() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Sanitize filename by removing invalid characters
     * Removes: < > : " / \ | ? * and control characters
     * 
     * @param filename Original filename
     * @return Sanitized filename safe for file systems
     */
    public static String sanitizeFilename(String filename) {
        if (filename == null || filename.isEmpty()) {
            return "video";
        }
        
        // Remove invalid characters for filenames
        String sanitized = filename
                .replaceAll("[<>:\"/\\\\|?*]", "_")  // Invalid filename chars
                .replaceAll("[\\x00-\\x1F\\x7F]", "")  // Control characters
                .replaceAll("\\s+", " ")  // Multiple spaces to single space
                .trim();
        
        // Limit length to avoid filesystem issues (max 200 chars)
        if (sanitized.length() > 200) {
            sanitized = sanitized.substring(0, 200);
        }
        
        // Remove trailing dots, spaces, and underscores.
        sanitized = sanitized.replaceAll("[.\\s_]+$", "");
        
        return sanitized.isEmpty() ? "video" : sanitized;
    }

    /**
     * Ensure directories exist, create them if they don't
     * 
     * @param paths Variable number of directory paths to ensure
     * @throws IOException if directory creation fails
     */
    public static void ensureDirectoriesExist(String... paths) throws IOException {
        for (String pathStr : paths) {
            Path path = Paths.get(pathStr);
            if (!Files.exists(path)) {
                Files.createDirectories(path);
                log.info("Created directory: {}", path);
            }
        }
    }

    /**
     * Create and verify a directory exists
     * 
     * @param dirPath Directory path to create
     * @return Path object of the created directory
     * @throws IOException if creation or verification fails
     */
    public static Path createAndVerifyDirectory(String dirPath) throws IOException {
        Path dir = Paths.get(dirPath);
        
        try {
            Files.createDirectories(dir);
            log.debug("Ensured directory exists: {}", dir);
            
            // Verify directory was created successfully
            if (!Files.exists(dir) || !Files.isDirectory(dir)) {
                throw new IOException("Failed to create directory: " + dir);
            }
            
            return dir;
            
        } catch (IOException e) {
            log.error("Failed to create directory: {}", dir, e);
            throw new IOException("Cannot create directory: " + dir, e);
        }
    }

    /**
     * Find a downloaded file with the given base filename
     * 
     * @param directory Directory to search in
     * @param baseFilename Base filename (without extension)
     * @return Full path to the found file
     * @throws IOException if file not found or directory doesn't exist
     */
    public static String findFileByPrefix(Path directory, String baseFilename) throws IOException {
        if (!Files.exists(directory) || !Files.isDirectory(directory)) {
            throw new IOException("Directory does not exist: " + directory);
        }

        try (var stream = Files.list(directory)) {
            return stream
                    .filter(p -> p.getFileName().toString().startsWith(baseFilename))
                    .findFirst()
                    .map(Path::toString)
                    .orElseThrow(() -> new IOException("File not found with prefix: " + baseFilename));
        }
    }

    /**
     * Best-effort cleanup for partial downloads.
     * Deletes any regular files in {@code directory} that start with {@code prefix}.
     */
    public static void bestEffortDeleteFilesByPrefix(Path directory, String prefix) {
        if (directory == null || prefix == null || prefix.isBlank()) {
            return;
        }
        if (!Files.exists(directory) || !Files.isDirectory(directory)) {
            return;
        }

        try (var stream = Files.list(directory)) {
            stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().startsWith(prefix))
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                            log.warn("Deleted partial download artifact: {}", p);
                        } catch (IOException e) {
                            log.warn("Failed to delete partial download artifact: {} ({})", p, e.getMessage());
                        }
                    });
        } catch (IOException e) {
            log.warn("Failed to scan directory for partial downloads: {} ({})", directory, e.getMessage());
        }
    }

    /**
     * Find the most recently modified video file in a directory
     * Excludes metadata files (JSON, descriptions, thumbnails, subtitles)
     * 
     * @param directory Directory to search in
     * @return Path to the video file
     * @throws IOException if no video file found or directory doesn't exist
     */
    public static Path findLatestVideoFile(Path directory) throws IOException {
        // Verify directory exists
        if (!Files.exists(directory) || !Files.isDirectory(directory)) {
            throw new IOException("Directory does not exist: " + directory);
        }
        
        // List all files in directory
        List<Path> allFiles;
        try (var stream = Files.list(directory)) {
            allFiles = stream
                    .filter(Files::isRegularFile)
                    .toList();
        }
        
        log.debug("Searching for video file in directory: {}", directory);
        log.debug("Files in directory ({}): {}", allFiles.size(), 
                allFiles.stream()
                        .map(p -> p.getFileName().toString())
                        .toList());
        
        // Find video files (exclude metadata files)
        List<Path> videoFiles = allFiles.stream()
                .filter(FileSystemHelper::isVideoFile)
                .sorted((p1, p2) -> {
                    // Sort by modification time, most recent first
                    try {
                        return Files.getLastModifiedTime(p2).compareTo(Files.getLastModifiedTime(p1));
                    } catch (IOException e) {
                        return 0;
                    }
                })
                .toList();
        
        if (videoFiles.isEmpty()) {
            String fileList = allFiles.isEmpty() 
                    ? "Directory is empty" 
                    : "Files found: " + allFiles.stream()
                            .map(p -> p.getFileName().toString())
                            .collect(Collectors.joining(", "));
            throw new IOException(
                    String.format("No video file found in directory: %s%n%s", directory, fileList));
        }
        
        Path videoFile = videoFiles.get(0);
        log.debug("Found video file: {}", videoFile.getFileName());
        return videoFile;
    }

    /**
     * Check if a file is a video file based on extension
     * Excludes metadata files
     */
    private static boolean isVideoFile(Path file) {
        String fileName = file.getFileName().toString().toLowerCase();
        
        // Exclude metadata files
        if (fileName.endsWith(".metadata.json") ||
            fileName.endsWith(".description") ||
            fileName.endsWith(".info.json") ||
            fileName.endsWith(".jpg") ||
            fileName.endsWith(".png") ||
            fileName.endsWith(".webp") ||
            fileName.endsWith(".vtt") ||
            fileName.endsWith(".srt") ||
            fileName.endsWith(".ass") ||
            fileName.endsWith(".ttml")) {
            return false;
        }
        
        // Include common video/audio formats
        return fileName.endsWith(".mp4") || fileName.endsWith(".webm") || 
               fileName.endsWith(".mkv") || fileName.endsWith(".m4a") ||
               fileName.endsWith(".mp3") || fileName.endsWith(".avi") ||
               fileName.endsWith(".flv") || fileName.endsWith(".mov");
    }

    /**
     * Extract file extension from a filename
     * 
     * @param filename Filename to extract extension from
     * @return Extension including the dot (e.g., ".mp4") or empty string if no extension
     */
    public static String getFileExtension(String filename) {
        int lastDot = filename.lastIndexOf('.');
        return lastDot > 0 ? filename.substring(lastDot) : "";
    }
}



