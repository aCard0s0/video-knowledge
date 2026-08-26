package com.tradinglabs.vidingest.core.diarization.controller;

import com.tradinglabs.vidingest.api.paths.VidIngestApiPaths;
import com.tradinglabs.vidingest.api.speakers.RenameSpeakerRequest;
import com.tradinglabs.vidingest.api.speakers.SpeakerDto;
import com.tradinglabs.vidingest.core.diarization.service.SpeakerService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * REST endpoints for {@code vidingest_speakers}.
 *
 * <ul>
 *   <li>{@code GET /api/v1/videos/{videoId}/speakers} — list speakers identified in a
 *       video, with the count of transcript segments tagged with each.</li>
 *   <li>{@code PATCH /api/v1/speakers/{speakerId}} — rename a speaker (operator-supplied
 *       friendly label).</li>
 * </ul>
 */
@RestController
@RequestMapping(produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
@Tag(name = "speakers", description = "Speaker APIs (M2/M8)")
public class SpeakerController {

    private final SpeakerService speakerService;

    @GetMapping(VidIngestApiPaths.VIDEO_SPEAKERS)
    @Operation(operationId = "listVideoSpeakers", summary = "List speakers for a video",
            description = "Returns one row per pyannote-identified speaker, with the count of transcript "
                    + "segments tagged with that speaker.")
    public List<SpeakerDto> listForVideo(@PathVariable UUID videoId) {
        return speakerService.listForVideo(videoId);
    }

    @PatchMapping(value = VidIngestApiPaths.SPEAKER, consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "renameSpeaker", summary = "Rename a speaker",
            description = "Sets or clears the operator-supplied displayName. The pyannote label is immutable. "
                    + "Pass an empty body or null displayName to clear the override.")
    @ResponseStatus(HttpStatus.OK)
    public SpeakerDto rename(
            @PathVariable UUID speakerId,
            @Valid @RequestBody RenameSpeakerRequest request
    ) {
        return speakerService.rename(speakerId, request != null ? request.displayName() : null);
    }
}
