package com.tradinglabs.vidingest.videos.service;

import com.tradinglabs.vidingest.config.ProjectPathResolver;
import com.tradinglabs.vidingest.config.VideoStorageConfig;
import com.tradinglabs.vidingest.core.frames.domain.VideoFrame;
import com.tradinglabs.vidingest.core.frames.repo.VideoFrameRepository;
import com.tradinglabs.vidingest.videos.domain.Video;
import com.tradinglabs.vidingest.videos.exceptions.LocalStorageException;
import com.tradinglabs.vidingest.videos.exceptions.VideoArtifactNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class VideoArtifactsService {

    public record ArtifactFile(Path path, MediaType mediaType, String downloadFileName) {
    }

    private static final MediaType TEXT_PLAIN_UTF8 = new MediaType("text", "plain", StandardCharsets.UTF_8);

    private final VideoQueryService videoQueryService;
    private final VideoStorageConfig storageConfig;
    private final ProjectPathResolver pathResolver;
    private final VideoFrameRepository frameRepository;

    @Transactional(readOnly = true)
    public ArtifactFile videoFile(UUID videoId) {
        Video video = videoQueryService.getById(videoId);
        Path videoPath = resolveAndValidateUnderRoot(videoId, "video file", video.getFilePath());

        String probed = probeContentType(videoPath);
        MediaType mediaType = probed != null ? MediaType.parseMediaType(probed) : MediaType.APPLICATION_OCTET_STREAM;

        return new ArtifactFile(videoPath, mediaType, videoPath.getFileName().toString());
    }

    @Transactional(readOnly = true)
    public ArtifactFile whisperTxt(UUID videoId) {
        Video video = videoQueryService.getById(videoId);
        Path videoPath = resolveAndValidateUnderRoot(videoId, "video file", video.getFilePath());
        Path txt = companionPath(videoPath, ".whisper.txt");
        validateExistsUnderRoot(videoId, "whisper.txt", txt);
        return new ArtifactFile(txt, TEXT_PLAIN_UTF8, txt.getFileName().toString());
    }

    /**
     * Resolves and serves a sampled frame JPG by frame id. Path containment under the
     * configured video storage root is enforced before bytes are read.
     */
    @Transactional(readOnly = true)
    public ArtifactFile frameImage(UUID frameId) {
        VideoFrame frame = frameRepository.findById(frameId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Frame not found: " + frameId));

        String filePath = frame.getFilePath();
        if (filePath == null || filePath.isBlank()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Frame has no file path: " + frameId);
        }

        Path p = Paths.get(filePath).toAbsolutePath().normalize();
        Path allowedRoot = resolveVideoRoot().toAbsolutePath().normalize();
        if (!p.startsWith(allowedRoot)) {
            throw new LocalStorageException(
                    "Refusing to access frame file outside storage root. file=" + p + ", root=" + allowedRoot);
        }
        if (!Files.exists(p)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Frame file missing on disk: " + p);
        }

        return new ArtifactFile(p, MediaType.IMAGE_JPEG, p.getFileName().toString());
    }

    @Transactional(readOnly = true)
    public ArtifactFile whisperJson(UUID videoId) {
        Video video = videoQueryService.getById(videoId);
        Path videoPath = resolveAndValidateUnderRoot(videoId, "video file", video.getFilePath());
        Path json = companionPath(videoPath, ".whisper.json");
        validateExistsUnderRoot(videoId, "whisper.json", json);
        return new ArtifactFile(json, MediaType.APPLICATION_JSON, json.getFileName().toString());
    }

    private Path resolveAndValidateUnderRoot(UUID videoId, String artifact, String filePath) {
        if (filePath == null || filePath.isBlank()) {
            throw new VideoArtifactNotFoundException(videoId, artifact);
        }
        Path p = Paths.get(filePath).toAbsolutePath().normalize();
        validateExistsUnderRoot(videoId, artifact, p);
        return p;
    }

    private void validateExistsUnderRoot(UUID videoId, String artifact, Path p) {
        Path allowedRoot = resolveVideoRoot().toAbsolutePath().normalize();
        Path normalized = p.toAbsolutePath().normalize();

        if (!normalized.startsWith(allowedRoot)) {
            throw new LocalStorageException(
                    "Refusing to access video file outside storage root. file=" + normalized + ", root=" + allowedRoot);
        }
        if (!Files.exists(normalized)) {
            throw new VideoArtifactNotFoundException(videoId, artifact);
        }
    }

    private Path resolveVideoRoot() {
        String configured = storageConfig.getVideoPath();
        if (configured != null && !configured.isBlank()) {
            return Paths.get(configured);
        }
        return Paths.get(pathResolver.getVideoPath());
    }

    private static Path companionPath(Path videoFile, String suffix) {
        String name = videoFile.getFileName().toString();
        int lastDot = name.lastIndexOf('.');
        String base = lastDot > 0 ? name.substring(0, lastDot) : name;
        return videoFile.getParent().resolve(base + suffix);
    }

    private static String probeContentType(Path p) {
        try {
            return Files.probeContentType(p);
        } catch (Exception ignored) {
            return null;
        }
    }
}

