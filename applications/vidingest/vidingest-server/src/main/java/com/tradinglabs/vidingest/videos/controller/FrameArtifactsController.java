package com.tradinglabs.vidingest.videos.controller;

import com.tradinglabs.vidingest.api.paths.VidIngestApiPaths;
import com.tradinglabs.vidingest.videos.service.VideoArtifactsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Files;
import java.time.Duration;
import java.util.UUID;

/**
 * Serves inline JPG bytes for sampled frames (M3) so the UI can render &lt;img&gt; tags
 * directly without needing the on-disk path.
 *
 * <p>Separate from {@link VideoArtifactsController} because the URL pattern lives under
 * {@code /api/v1/frames}, not {@code /api/v1/videos}.
 */
@RestController
@RequiredArgsConstructor
@Slf4j
@Tag(name = "frame-artifacts", description = "Inline JPG bytes for sampled frames")
public class FrameArtifactsController {

    private final VideoArtifactsService artifacts;

    @GetMapping(VidIngestApiPaths.FRAME_IMAGE)
    @Operation(
            summary = "Get the JPG bytes for a sampled frame",
            description = "Returns the on-disk frame JPG inline (not an attachment) so the UI can render <img> tags."
    )
    public ResponseEntity<Resource> frameImage(@PathVariable UUID frameId) {
        VideoArtifactsService.ArtifactFile file = artifacts.frameImage(frameId);

        Resource resource = new FileSystemResource(file.path());
        ContentDisposition cd = ContentDisposition.inline().filename(file.downloadFileName()).build();

        Long contentLength = null;
        try {
            contentLength = Files.size(file.path());
        } catch (Exception ignored) {
            // best-effort
        }

        var builder = ResponseEntity.ok()
                .contentType(file.mediaType())
                .cacheControl(CacheControl.maxAge(Duration.ofDays(7)).cachePublic())
                .header(HttpHeaders.CONTENT_DISPOSITION, cd.toString());

        if (contentLength != null) {
            builder = builder.contentLength(contentLength);
        }

        return builder.body(resource);
    }
}
