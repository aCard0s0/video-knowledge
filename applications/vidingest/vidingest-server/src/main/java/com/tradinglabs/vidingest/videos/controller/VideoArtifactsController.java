package com.tradinglabs.vidingest.videos.controller;

import com.tradinglabs.vidingest.api.paths.VidIngestApiPaths;
import com.tradinglabs.vidingest.videos.service.VideoArtifactsService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Files;
import java.util.UUID;

@RestController
@RequestMapping(VidIngestApiPaths.VIDEOS)
@RequiredArgsConstructor
@Slf4j
@Tag(name = "video-artifacts", description = "Download stored videos and transcription artifacts")
public class VideoArtifactsController {

    private final VideoArtifactsService artifacts;

    @GetMapping("/{videoId}/file")
    public ResponseEntity<Resource> downloadVideoFile(@PathVariable UUID videoId) {
        var file = artifacts.videoFile(videoId);
        return fileResponse(file);
    }

    @GetMapping("/{videoId}/transcription/whisper.txt")
    public ResponseEntity<Resource> downloadWhisperTxt(@PathVariable UUID videoId) {
        var file = artifacts.whisperTxt(videoId);
        return fileResponse(file);
    }

    @GetMapping("/{videoId}/transcription/whisper.json")
    public ResponseEntity<Resource> downloadWhisperJson(@PathVariable UUID videoId) {
        var file = artifacts.whisperJson(videoId);
        return fileResponse(file);
    }

    private ResponseEntity<Resource> fileResponse(VideoArtifactsService.ArtifactFile file) {
        Resource resource = new FileSystemResource(file.path());
        ContentDisposition cd = ContentDisposition.attachment().filename(file.downloadFileName()).build();

        Long contentLength = null;
        try {
            contentLength = Files.size(file.path());
        } catch (Exception ignored) {
            // best-effort
        }

        var builder = ResponseEntity.ok()
                .contentType(file.mediaType())
                .header(HttpHeaders.CONTENT_DISPOSITION, cd.toString());

        if (contentLength != null) {
            builder = builder.contentLength(contentLength);
        }

        return builder.body(resource);
    }
}

