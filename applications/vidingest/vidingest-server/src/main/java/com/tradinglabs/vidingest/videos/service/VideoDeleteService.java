package com.tradinglabs.vidingest.videos.service;

import com.tradinglabs.vidingest.config.ProjectPathResolver;
import com.tradinglabs.vidingest.config.VideoStorageConfig;
import com.tradinglabs.vidingest.videos.exceptions.LocalStorageException;
import com.tradinglabs.vidingest.videos.exceptions.VideoNotFoundException;
import com.tradinglabs.vidingest.videos.repo.VideoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

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
    private final TransactionOperations transactionOperations;

    /**
     * Deletes the video row, then its artifacts on disk — in that order, and deliberately.
     *
     * <p>The reverse is what this used to do, inside one transaction. A recursive directory delete
     * cannot roll back, so any failure of the row delete — a cascade deadlocking against a
     * concurrent OCR or KNOWLEDGE write, a dropped connection — left the row COMPLETED with a
     * {@code file_path} pointing at a folder that no longer existed. Every artifact endpoint 404s
     * from then on and every rerunnable phase fails on the missing file, with nothing on the row
     * to say why.
     *
     * <p>This ordering can instead leak a folder if the process dies between the commit and the
     * delete. That is the better failure: orphaned bytes are recoverable, an unplayable video row
     * is not. Path containment is still validated before anything is removed.
     */
    public void deleteVideo(UUID videoId) {
        Path videoDir = transactionOperations.execute(status -> {
            var video = videoRepository.findById(videoId)
                    .orElseThrow(() -> new VideoNotFoundException(videoId));
            Path dir = resolveArtifactDir(video.getFilePath());
            videoRepository.delete(video);
            return dir;
        });

        log.info("Deleted video {} and cascaded records", videoId);
        deleteArtifacts(videoId, videoDir);
    }

    /**
     * Validates that the video's artifacts sit where we expect and returns the directory to
     * remove, or {@code null} when there is nothing on disk. Runs inside the transaction so a
     * containment failure aborts the row delete too — refusing to touch the filesystem is not a
     * reason to orphan the database row.
     */
    private Path resolveArtifactDir(String filePath) {
        if (filePath == null || filePath.isBlank()) {
            return null;
        }
        Path allowedRoot = resolveVideoRoot().toAbsolutePath().normalize();
        Path resolved = Paths.get(filePath).toAbsolutePath().normalize();

        if (!resolved.startsWith(allowedRoot)) {
            throw new LocalStorageException(
                    "Refusing to delete video file outside storage root. file=" + resolved + ", root=" + allowedRoot);
        }

        // Layout: {root}/{channel}/{base}/{base}.mp4 — the parent dir is the
        // per-video folder containing all artifacts (mp4, metadata.json,
        // whisper.*, frames/). Delete the whole folder as the cascade unit.
        // Per-video dir must be a grandchild of allowedRoot (channel dir is the
        // intermediate level and must itself be a strict child of root).
        Path videoDir = resolved.getParent();
        Path channelDir = videoDir != null ? videoDir.getParent() : null;
        if (videoDir == null || channelDir == null
                || !channelDir.startsWith(allowedRoot) || channelDir.equals(allowedRoot)) {
            throw new LocalStorageException(
                    "Refusing to delete video parent dir: unexpected layout under root. dir="
                            + videoDir + ", root=" + allowedRoot);
        }
        return Files.isDirectory(videoDir) ? videoDir : resolved;
    }

    /**
     * Post-commit, so a failure here cannot take the row delete with it. Logged rather than
     * thrown: the row is already gone, and answering the caller with an error would say the
     * delete did not happen when most of it did.
     */
    private void deleteArtifacts(UUID videoId, Path target) {
        if (target == null) {
            return;
        }
        try {
            if (Files.isDirectory(target)) {
                deleteDirRecursively(target);
                log.info("Deleted video folder: {}", target);
            } else if (Files.exists(target)) {
                Files.delete(target);
                log.info("Deleted video file (no parent folder to remove): {}", target);
            }
        } catch (Exception e) {
            log.error("Video {} row deleted but its artifacts under {} could not be removed: {}",
                    videoId, target, e.getMessage(), e);
        }
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

