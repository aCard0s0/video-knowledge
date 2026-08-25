package com.tradinglabs.vidingest.videos.service;

import com.tradinglabs.vidingest.config.ProjectPathResolver;
import com.tradinglabs.vidingest.config.VideoStorageConfig;
import com.tradinglabs.vidingest.videos.exceptions.LocalStorageException;
import com.tradinglabs.vidingest.videos.exceptions.VideoNotFoundException;
import com.tradinglabs.vidingest.videos.repo.VideoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class VideoDeleteService {

    private final VideoRepository videoRepository;
    private final VideoStorageConfig storageConfig;
    private final ProjectPathResolver pathResolver;

    @Transactional
    public void deleteVideo(UUID videoId) {
        var video = videoRepository.findById(videoId)
                .orElseThrow(() -> new VideoNotFoundException(videoId));

        if (video.getFilePath() != null && !video.getFilePath().isBlank()) {
            Path allowedRoot = resolveVideoRoot().toAbsolutePath().normalize();
            Path filePath = Paths.get(video.getFilePath()).toAbsolutePath().normalize();

            if (!filePath.startsWith(allowedRoot)) {
                throw new LocalStorageException(
                        "Refusing to delete video file outside storage root. file=" + filePath + ", root=" + allowedRoot);
            }

            // Layout: {root}/{channel}/{base}/{base}.mp4 — the parent dir is the
            // per-video folder containing all artifacts (mp4, metadata.json,
            // whisper.*, frames/). Delete the whole folder as the cascade unit.
            // Per-video dir must be a grandchild of allowedRoot (channel dir is the
            // intermediate level and must itself be a strict child of root).
            Path videoDir = filePath.getParent();
            Path channelDir = videoDir != null ? videoDir.getParent() : null;
            if (videoDir == null || channelDir == null
                    || !channelDir.startsWith(allowedRoot) || channelDir.equals(allowedRoot)) {
                throw new LocalStorageException(
                        "Refusing to delete video parent dir: unexpected layout under root. dir="
                                + videoDir + ", root=" + allowedRoot);
            }

            try {
                if (Files.isDirectory(videoDir)) {
                    deleteDirRecursively(videoDir);
                    log.info("Deleted video folder: {}", videoDir);
                } else if (Files.exists(filePath)) {
                    Files.delete(filePath);
                    log.info("Deleted video file (no parent folder to remove): {}", filePath);
                }
            } catch (Exception e) {
                throw new LocalStorageException("Failed to delete video artifacts under " + allowedRoot, e);
            }
        }

        videoRepository.delete(video);
        log.info("Deleted video {} and cascaded records", videoId);
    }

    private Path resolveVideoRoot() {
        String configured = storageConfig.getVideoPath();
        if (configured != null && !configured.isBlank()) {
            return Paths.get(configured);
        }
        return Paths.get(pathResolver.getVideoPath());
    }

    /**
     * Recursive directory delete used by the per-video folder cleanup. Kept here rather
     * than in a util class because {@link VideoDeleteService} is the only caller and we
     * don't want callers further afield reaching for an "rm -rf" helper.
     */
    private static void deleteDirRecursively(Path dir) throws IOException {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path child : stream) {
                if (Files.isDirectory(child)) {
                    deleteDirRecursively(child);
                } else {
                    Files.delete(child);
                }
            }
        }
        Files.delete(dir);
    }
}

