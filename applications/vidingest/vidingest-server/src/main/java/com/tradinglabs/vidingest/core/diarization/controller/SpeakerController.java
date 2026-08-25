package com.tradinglabs.vidingest.core.diarization.controller;

import com.tradinglabs.vidingest.api.paths.VidIngestApiPaths;
import com.tradinglabs.vidingest.api.speakers.RenameSpeakerRequest;
import com.tradinglabs.vidingest.api.speakers.SpeakerDto;
import com.tradinglabs.vidingest.core.diarization.domain.Speaker;
import com.tradinglabs.vidingest.core.diarization.mapper.SpeakerMapper;
import com.tradinglabs.vidingest.core.diarization.repo.SpeakerRepository;
import com.tradinglabs.vidingest.core.transcription.repo.TranscriptionSegmentRepository;
import com.tradinglabs.vidingest.videos.exceptions.VideoNotFoundException;
import com.tradinglabs.vidingest.videos.repo.VideoRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.transaction.annotation.Transactional;

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
@Slf4j
@Tag(name = "speakers", description = "Speaker APIs (M2/M8)")
public class SpeakerController {

    private final SpeakerRepository speakerRepository;
    private final SpeakerMapper speakerMapper;
    private final TranscriptionSegmentRepository transcriptionSegmentRepository;
    private final VideoRepository videoRepository;

    @GetMapping(VidIngestApiPaths.VIDEO_SPEAKERS)
    @Operation(summary = "List speakers for a video",
            description = "Returns one row per pyannote-identified speaker, with the count of transcript "
                    + "segments tagged with that speaker.")
    @Transactional(readOnly = true)
    public List<SpeakerDto> listForVideo(@PathVariable UUID videoId) {
        if (!videoRepository.existsById(videoId)) {
            throw new VideoNotFoundException(videoId);
        }
        return speakerRepository.findByVideo_Id(videoId).stream()
                .map(s -> speakerMapper.toDto(s, transcriptionSegmentRepository.countBySpeakerId(s.getId())))
                .toList();
    }

    @PatchMapping(value = VidIngestApiPaths.SPEAKER, consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Rename a speaker",
            description = "Sets or clears the operator-supplied displayName. The pyannote label is immutable. "
                    + "Pass an empty body or null displayName to clear the override.")
    @ResponseStatus(HttpStatus.OK)
    @Transactional
    public SpeakerDto rename(
            @PathVariable UUID speakerId,
            @Valid @RequestBody RenameSpeakerRequest request
    ) {
        Speaker speaker = speakerRepository.findById(speakerId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Speaker not found: " + speakerId));
        String newName = request != null ? request.displayName() : null;
        String normalized = (newName == null || newName.isBlank()) ? null : newName.trim();
        speaker.setDisplayName(normalized);
        speakerRepository.save(speaker);
        log.info("REST rename speaker: speakerId={}, displayName={}", speakerId, normalized);
        return speakerMapper.toDto(speaker, transcriptionSegmentRepository.countBySpeakerId(speakerId));
    }
}
